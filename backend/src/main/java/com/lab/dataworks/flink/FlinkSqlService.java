package com.lab.dataworks.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FlinkSQL 流任务运行时：SQL Gateway 负责执行语句，Flink REST 负责看/停 Job。
 *
 * <p>一次提交 = 新建一个 SQL Gateway 会话 → 按顺序执行脚本里的语句 →
 * 从 INSERT 的结果里拿到 Job ID。会话句柄存库，停止时关闭；
 * 网关重启后会话会丢失，但 Flink REST 仍能按 Job ID 取消，所以运行态以 Job ID 为准。</p>
 */
@Service
public class FlinkSqlService {

    private static final Logger log = LoggerFactory.getLogger(FlinkSqlService.class);
    /** INSERT 结果里的 Job ID（Flink 用 32 位十六进制）。 */
    private static final Pattern JOB_ID = Pattern.compile("^[0-9a-f]{32}$", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final RestTemplate http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String gatewayBase;
    private final String restBase;
    private final String prodGatewayBase;
    private final String prodRestBase;
    /** 当前线程是否在操作 PROD 集群（prod 启停/对账时由 asProd 设置）。 */
    private static final ThreadLocal<Boolean> USE_PROD = new ThreadLocal<>();
    private final String dorisHost;
    private final int dorisPort;
    private final String dorisUser;
    private final String dorisPassword;
    /**
     * 速率采样：jobId -> 最近若干次 {时间ms, 累计入, 累计出}。
     *
     * <p>Flink 自己的 {@code numRecordsInPerSecond} 在链式算子/单并行度下实测只有真实值的 1/3
     * （20 条/秒报 6.67），{@code numRecordsIn} 又按统计周期台阶跳；所以用平台自己 30 秒以上
     * 窗口的差分算平均吞吐，窗口不够时才退回 Flink 值。</p>
     */
    private static final Map<String, java.util.ArrayDeque<long[]>> RATE_SAMPLES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RATE_WINDOW_MS = 60_000L;

    /** 共享会话（session_mode=SHARED）；同一个后端进程内复用。 */
    private String sharedSession;

    // 有两个构造器：显式指定 Spring 用这个（test 构造器只管单测注入）
    @org.springframework.beans.factory.annotation.Autowired
    public FlinkSqlService(JdbcTemplate jdbc, RestTemplateBuilder builder,
                           @Value("${flink.gateway-url:http://flink-sql-gateway.dw-dev.svc.cluster.local:8083}")
                           String gatewayUrl,
                           @Value("${flink.rest-url:http://flink-jobmanager.dw-dev.svc.cluster.local:8081}")
                           String restUrl,
                           @Value("${flink.prod-gateway-url:}")
                           String prodGatewayUrl,
                           @Value("${flink.prod-rest-url:}")
                           String prodRestUrl,
                           @Value("${doris.host:127.0.0.1}") String dorisHost,
                           @Value("${doris.port:9030}") int dorisPort,
                           @Value("${doris.user:dataworks}") String dorisUser,
                           @Value("${doris.password:}") String dorisPassword) {
        this(jdbc, builder
                        .setConnectTimeout(Duration.ofSeconds(5))
                        .setReadTimeout(Duration.ofSeconds(90))
                        .build(),
                gatewayUrl, restUrl, prodGatewayUrl, prodRestUrl,
                dorisHost, dorisPort, dorisUser, dorisPassword);
    }

    /** 单测用：注入已绑定的 RestTemplate（线上仍走上面的构造路径，行为不变）。 */
    FlinkSqlService(JdbcTemplate jdbc, RestTemplate http,
                    String gatewayUrl, String restUrl,
                    String prodGatewayUrl, String prodRestUrl,
                    String dorisHost, int dorisPort, String dorisUser, String dorisPassword) {
        this.jdbc = jdbc;
        this.http = http;
        this.gatewayBase = trimSlash(gatewayUrl);
        this.restBase = trimSlash(restUrl);
        // 未配置独立 PROD 集群时退化成同一个集群（保持旧行为）
        this.prodGatewayBase = trimSlash(prodGatewayUrl.isBlank() ? gatewayUrl : prodGatewayUrl);
        this.prodRestBase = trimSlash(prodRestUrl.isBlank() ? restUrl : prodRestUrl);
        this.dorisHost = dorisHost;
        this.dorisPort = dorisPort;
        this.dorisUser = dorisUser;
        this.dorisPassword = dorisPassword;
    }

    /**
     * SQL 里的连接凭据用占位符，库里不落明文：
     * ${DORIS_HOST} / ${DORIS_PORT} / ${DORIS_USER} / ${DORIS_PASSWORD}。
     */
    private String resolveSecrets(String sql) {
        return sql.replace("${DORIS_HOST}", dorisHost)
                .replace("${DORIS_PORT}", String.valueOf(dorisPort))
                .replace("${DORIS_USER}", dorisUser)
                .replace("${DORIS_PASSWORD}", dorisPassword == null ? "" : dorisPassword);
    }

    /** 错误信息里如果带出了口令，替换成 *** 再返回/落库。 */
    private String scrub(String text) {
        if (text == null || dorisPassword == null || dorisPassword.isBlank()) {
            return text;
        }
        return text.replace(dorisPassword, "***");
    }

    private String gw() {
        return Boolean.TRUE.equals(USE_PROD.get()) ? prodGatewayBase : gatewayBase;
    }

    private String rest() {
        return Boolean.TRUE.equals(USE_PROD.get()) ? prodRestBase : restBase;
    }

    /** 在 PROD 集群上执行一段逻辑（线程内生效）。 */
    public <T> T asProd(java.util.function.Supplier<T> action) {
        Boolean prev = USE_PROD.get();
        USE_PROD.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                USE_PROD.remove();
            } else {
                USE_PROD.set(prev);
            }
        }
    }

    public void asProd(Runnable action) {
        asProd(() -> {
            action.run();
            return null;
        });
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ------------------------------------------------------------------ 运行时

    /** 校验脚本：执行除最后一条外的语句（DDL 只在会话内生效），最后一条用 EXPLAIN。 */
    public Map<String, Object> validate(String sql) {
        List<String> statements = splitStatements(resolveSecrets(sql));
        if (statements.isEmpty()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        String session = createSession();
        try {
            for (int i = 0; i < statements.size() - 1; i++) {
                executeStatement(session, statements.get(i), false);
            }
            String last = statements.get(statements.size() - 1);
            String explain = "EXPLAIN " + last;
            Map<String, Object> result = executeStatement(session, explain, false);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("statements", statements.size());
            out.put("planRows", result.getOrDefault("rowCount", 0));
            return out;
        } finally {
            closeSession(session);
        }
    }

    /** 提交：执行完整脚本，返回 Flink Job ID。shared=true 时复用共享会话。 */
    public Map<String, Object> submit(String sql, boolean shared) {
        List<String> statements = splitStatements(resolveSecrets(sql));
        if (statements.isEmpty()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        String session = shared ? sharedSession() : createSession();
        String jobId = null;
        // 结果 token 只能消费一次；INSERT 的结果偶尔在 RUNNING 瞬间还没就绪，
        // 这里用「提交前后作业列表的差集」兜底，避免依赖结果接口的时序。
        java.util.Set<String> before = overviewJobIds();
        String sinkTable = sinkTableOf(statements);
        try {
            jobId = executeScript(session, statements);
            if (jobId == null) {
                jobId = awaitNewJob(before, sinkTable);
            }
            // 共享会话的状态可能已经漂移（表被清、操作被取消）：没有产生新作业时
            // 回落到一个全新会话再跑一次，脚本自带 DDL，不依赖旧会话。
            if (jobId == null && shared) {
                invalidateSharedSession();
                session = sharedSession();
                java.util.Set<String> beforeRetry = overviewJobIds();
                jobId = executeScript(session, statements);
                if (jobId == null) {
                    jobId = awaitNewJob(beforeRetry, sinkTable);
                }
            }
        } catch (RuntimeException e) {
            if (shared) {
                // 共享会话可能已经失效（Gateway 重启）：同一次提交内重建并重试一次，
                // 不能把「会话过期」这种可自愈错误直接甩给用户。
                invalidateSharedSession();
                session = sharedSession();
                try {
                    jobId = executeScript(session, statements);
                    if (jobId == null) {
                        jobId = awaitNewJob(before, sinkTable);
                    }
                } catch (RuntimeException retryError) {
                    invalidateSharedSession();
                    throw retryError;
                }
            } else {
                closeSession(session);
                throw e;
            }
        }
        if (jobId == null) {
            if (!shared) {
                closeSession(session);
            }
            throw ApiException.badRequest(
                    "脚本已执行但没有产生 Job：流任务最后需要一条 INSERT INTO ... SELECT ..."
                    + "（如果语句本身有语法/函数错误，提交会直接返回引擎原文与行列号）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("session", session);
        out.put("shared", shared);
        out.put("state", "RUNNING");
        return out;
    }

    /**
     * 共享会话句柄。Gateway 重启/会话被清理后，旧 handle 会一直 404：
     * 每次取用先探一下，失效就自动重建，避免把「会话过期」当成脚本错误抛给用户。
     */
    private synchronized String sharedSession() {
        if (sharedSession != null && !sharedSession.isBlank() && sessionAlive(sharedSession)) {
            return sharedSession;
        }
        sharedSession = createSession();
        return sharedSession;
    }

    private boolean sessionAlive(String handle) {
        try {
            // 会话在 SQL Gateway 上（8083 /v1/sessions），不是 JobManager REST（8081）
            exchange(gw() + "/v1/sessions/" + handle, HttpMethod.GET, null);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private synchronized void invalidateSharedSession() {
        sharedSession = null;
    }

    /**
     * Flink 集群概览：DEV 与会话隔离的 PROD 各一份。
     *
     * <p>PROD 未单独配置时和 DEV 是同一个集群，响应里带 {@code prodSeparate=false}，
     * 页面据此提示「线上与开发共用集群」。</p>
     */
    public Map<String, Object> cluster() {
        Map<String, Object> out = clusterOf();
        out.put("prodSeparate", !prodRestBase.equals(restBase));
        try {
            out.put("prod", asProd(this::clusterOf));
        } catch (RuntimeException e) {
            out.put("prod", Map.of("error", "PROD 集群不可用: " + e.getMessage()));
        }
        return out;
    }

    /**
     * 流开发页用的最小集群状态（BUG-TC6-12）：只回答「现在能不能提交」。
     *
     * <p>只返回 ready / reason / sessionMode 三个字段：作业数、槽位、TaskManager 明细
     * 是平台基础设施画像，仍只走 {@link #cluster()}（system:manage）。失败原因也只给
     * 分类（服务未就绪 / HTTP 状态码），不回显内网地址与堆栈——这个接口数据开发者能读。</p>
     */
    public Map<String, Object> clusterStatus() {
        boolean ready;
        String reason = "";
        try {
            JsonNode overview = callRest(HttpMethod.GET, "/overview", null);
            int taskmanagers = overview.path("taskmanagers").asInt(0);
            int slotsTotal = overview.path("slots-total").asInt(0);
            int slotsAvailable = overview.path("slots-available").asInt(0);
            if (taskmanagers <= 0) {
                ready = false;
                reason = "Flink 集群还没有可用的 TaskManager，现在提交的作业会一直等待资源";
            } else if (slotsTotal <= 0) {
                ready = false;
                reason = "Flink 集群还没有分配执行槽（Slot），现在提交的作业会一直等待资源";
            } else if (slotsAvailable <= 0) {
                // 槽位满不是错误：作业能提交，只是要排队。文案必须说清「排队」而不是「失败」
                ready = false;
                reason = "执行槽已全部占用，现在提交的作业会排队等待；详情需平台管理员查看";
            } else {
                ready = true;
            }
        } catch (ApiException e) {
            ready = false;
            reason = "暂时连不上 Flink 集群（" + (e.getStatus() >= 500 ? "服务未就绪" : "HTTP " + e.getStatus())
                    + "），现在提交会失败；请联系平台管理员确认集群状态";
        } catch (RuntimeException e) {
            ready = false;
            reason = "暂时连不上 Flink 集群，现在提交会失败；请联系平台管理员确认集群状态";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", ready);
        out.put("reason", reason);
        // DEV 是 JobManager + TaskManager 的会话集群，作业共享这一个集群（区别于 per-job）
        out.put("sessionMode", "SESSION");
        return out;
    }

    private Map<String, Object> clusterOf() {
        JsonNode overview = callRest(HttpMethod.GET, "/overview", null);
        JsonNode tms = callRest(HttpMethod.GET, "/taskmanagers", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskmanagers", overview.path("taskmanagers").asInt(0));
        out.put("slotsTotal", overview.path("slots-total").asInt(0));
        out.put("slotsAvailable", overview.path("slots-available").asInt(0));
        out.put("jobsRunning", overview.path("jobs-running").asInt(0));
        out.put("jobsFinished", overview.path("jobs-finished").asInt(0));
        out.put("jobsCancelled", overview.path("jobs-cancelled").asInt(0));
        out.put("jobsFailed", overview.path("jobs-failed").asInt(0));
        List<Map<String, Object>> workers = new ArrayList<>();
        for (JsonNode tm : tms.path("taskmanagers")) {
            JsonNode hardware = tm.path("hardware");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tm.path("id").asText(""));
            item.put("slots", tm.path("slotsNumber").asInt(0));
            item.put("freeSlots", tm.path("freeSlots").asInt(0));
            item.put("cpuCores", hardware.path("cpuCores").asInt(0));
            item.put("memory", hardware.path("physicalMemory").asLong(0));
            item.put("freeMemory", hardware.path("freeMemory").asLong(0));
            workers.add(item);
        }
        out.put("workers", workers);
        // 系统监控要能看到「集群上到底跑着什么」：作业名 + 状态 + 运行时长
        try {
            JsonNode jobs = callRest(HttpMethod.GET, "/jobs/overview", null);
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode job : jobs.path("jobs")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", job.path("jid").asText(""));
                item.put("name", job.path("name").asText(""));
                item.put("state", job.path("state").asText(""));
                item.put("startTime", job.path("start-time").asLong(0));
                item.put("duration", job.path("duration").asLong(0));
                item.put("tasksTotal", job.path("tasks").path("total").asInt(0));
                list.add(item);
            }
            list.sort((a, b) -> Long.compare(
                    ((Number) b.get("startTime")).longValue(),
                    ((Number) a.get("startTime")).longValue()));
            out.put("jobs", list.subList(0, Math.min(list.size(), 20)));
        } catch (RuntimeException e) {
            out.put("jobs", List.of());
        }
        return out;
    }

    /** 读 Flink 作业状态；找不到直接返回 GONE，不猜。 */
    public Map<String, Object> jobStatus(String jobId) {
        try {
            JsonNode body = callRest(HttpMethod.GET, "/jobs/" + jobId, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", body.path("state").asText("UNKNOWN"));
            out.put("name", body.path("name").asText(""));
            out.put("startTime", body.path("start-time").asLong(0));
            out.put("duration", body.path("duration").asLong(0));
            JsonNode tasks = body.path("tasks");
            out.put("tasks", tasks.isObject() ? tasks.path("total").asInt() : tasks.path("total").asInt());
            return out;
        } catch (ApiException e) {
            if (e.getStatus() == 404) {
                return Map.of("state", "GONE");
            }
            throw e;
        }
    }

    public String jobException(String jobId) {
        try {
            JsonNode body = callRest(HttpMethod.GET, "/jobs/" + jobId + "/exceptions", null);
            JsonNode root = body.path("root-exception");
            if (!root.isMissingNode() && !root.asText("").isBlank()) {
                return readable(root.asText());
            }
            JsonNode all = body.path("all-exceptions");
            if (all.isArray() && !all.isEmpty()) {
                return scrub(all.get(0).path("exception").asText(""));
            }
            return "";
        } catch (ApiException e) {
            return "";
        }
    }

    /**
     * DEV 跑通：在 DEV 真跑一次（流作业稳定 RUNNING，批作业 FINISHED），然后取消。
     * 返回作业 ID 和观测到的状态；失败抛 400。
     */
    public Map<String, Object> submitAndObserve(String sql) {
        Map<String, Object> started = submit(sql, false);
        String jobId = String.valueOf(started.get("jobId"));
        String session = started.get("session") == null ? null
                : String.valueOf(started.get("session"));
        String state = "";
        try {
            long deadline = System.currentTimeMillis() + 30_000;
            long stableSince = 0;
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> status = jobStatus(jobId);
                state = String.valueOf(status.get("state"));
                if ("FINISHED".equals(state)) {
                    break;
                }
                if ("FAILED".equals(state) || "CANCELED".equals(state)) {
                    throw ApiException.badRequest("DEV 跑通失败: 作业状态 " + state
                            + (jobException(jobId).isBlank() ? ""
                            : "；" + scrub(jobException(jobId))));
                }
                if ("RUNNING".equals(state)) {
                    if (stableSince == 0) {
                        stableSince = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - stableSince >= 5_000) {
                        break;
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if ("INITIALIZING".equals(state) || "RESTARTING".equals(state) || state.isBlank()) {
                throw ApiException.badRequest("DEV 跑通失败: 作业没有稳定运行（" + state + "）");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("jobId", jobId);
            out.put("state", state);
            return out;
        } finally {
            try {
                cancel(jobId);
            } catch (Exception ignored) {
                // 取消失败不影响 DEV 跑通结论，作业会随会话关闭
            }
            closeSession(session);
        }
    }

    /**
     * 作业日志：Flink 异常摘要 + TaskManager 里该作业的输出/日志行。
     *
     * <p>print sink 的输出（+I[...]）只在 TaskManager 的 stdout 里，
     * Flink REST 不提供；会话集群只有一个 TM，按作业行过滤即可。</p>
     */
    public String jobLogs(String jobId, int lines) {
        return jobLogs(jobId, lines, false);
    }

    /**
     * @param prod true 时读 PROD 会话集群的 TaskManager。DEV / PROD 是两套集群，
     *             固定读 DEV 会把线上作业的 print 输出读成「暂无日志」。
     */
    public String jobLogs(String jobId, int lines, boolean prod) {
        final String nl = String.valueOf((char) 10);
        int limit = Math.max(20, Math.min(2000, lines));
        StringBuilder out = new StringBuilder();
        String exception = jobException(jobId);
        if (exception != null && !exception.isBlank()) {
            out.append("===== Flink 作业异常 =====").append(nl)
                    .append(exception).append(nl).append(nl);
        }
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var pods = client.pods().inNamespace("dw-dev")
                    .withLabel("app", prod ? "flink-prod-taskmanager" : "flink-taskmanager")
                    .list().getItems();
            if (pods.isEmpty()) {
                out.append("（TaskManager Pod 不存在）");
                return trim(out.toString());
            }
            for (var pod : pods) {
                String log = client.pods().inNamespace("dw-dev")
                        .withName(pod.getMetadata().getName()).getLog();
                if (log == null || log.isBlank()) {
                    continue;
                }
                List<String> matched = new ArrayList<>();
                for (String line : log.split(nl)) {
                    if (line.contains(jobId) || isPrintLine(line)) {
                        matched.add(line);
                    }
                }
                List<String> tail = matched.isEmpty()
                        ? lastLines(log, limit) : lastLines(matched, limit);
                if (!tail.isEmpty()) {
                    out.append("===== TaskManager ").append(pod.getMetadata().getName())
                            .append(" =====").append(nl);
                    for (String line : tail) {
                        out.append(line).append(nl);
                    }
                }
            }
            if (out.length() == 0) {
                out.append("（暂无日志：作业可能还没开始输出）");
            }
            return trim(scrub(out.toString()));
        } catch (Exception e) {
            throw new ApiException(502, "读取 Flink 日志失败: " + e.getMessage());
        }
    }

    private boolean isPrintLine(String line) {
        String value = line.trim();
        return value.startsWith("+I[") || value.startsWith("-D[")
                || value.startsWith("+U[") || value.startsWith("-U[")
                || value.startsWith("-R[");
    }

    private List<String> lastLines(String log, int limit) {
        final String nl = String.valueOf((char) 10);
        String[] all = log.split(nl);
        int from = Math.max(0, all.length - limit);
        List<String> out = new ArrayList<>();
        for (int i = from; i < all.length; i++) {
            out.add(all[i]);
        }
        return out;
    }

    private List<String> lastLines(List<String> lines, int limit) {
        int from = Math.max(0, lines.size() - limit);
        return new ArrayList<>(lines.subList(from, lines.size()));
    }

    private String trim(String text) {
        return text.length() > 200_000 ? text.substring(text.length() - 200_000) : text;
    }

    /** 作业 DAG：Flink plan 的节点/边 + 每个算子的运行状态与读写记录数。 */
    public Map<String, Object> jobGraph(String jobId) {
        JsonNode detail = callRest(HttpMethod.GET, "/jobs/" + jobId, null);
        Map<String, Map<String, Object>> statusById = new LinkedHashMap<>();
        for (JsonNode vertex : detail.path("vertices")) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", vertex.path("name").asText(""));
            info.put("parallelism", vertex.path("parallelism").asInt(0));
            info.put("status", vertex.path("status").asText(""));
            info.put("recordsIn", vertex.path("metrics").path("read-records").asLong(0));
            info.put("recordsOut", vertex.path("metrics").path("write-records").asLong(0));
            statusById.put(vertex.path("id").asText(""), info);
        }
        JsonNode plan = detail.path("plan");
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (JsonNode node : plan.path("nodes")) {
            String id = node.path("id").asText("");
            Map<String, Object> info = statusById.getOrDefault(id, Map.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("type", node.path("type").asText(""));
            item.put("pact", node.path("pact").asText(""));
            item.put("contents", node.path("contents").asText(""));
            item.put("parallelism", node.path("parallelism").asInt(0));
            item.putAll(info);
            nodes.add(item);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (JsonNode edge : plan.path("edges")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", edge.path("source").asText(""));
            item.put("target", edge.path("target").asText(""));
            edges.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("state", detail.path("state").asText(""));
        out.put("jobType", detail.path("job-type").asText(""));
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /**
     * 运行指标：记录数/速率、水位、积压、背压、checkpoint。
     * 指标接口对已结束的作业可能返回空数组，这里按空值处理，不报错。
     */
    public Map<String, Object> jobMetrics(String jobId) {
        JsonNode detail = callRest(HttpMethod.GET, "/jobs/" + jobId, null);
        long now = System.currentTimeMillis();
        long recordsIn = 0;
        long recordsOut = 0;
        double inRate = 0;
        double outRate = 0;
        String backpressure = "UNKNOWN";
        Long watermark = null;
        long pending = 0;
        long lagMax = 0;
        List<Map<String, Object>> vertices = new ArrayList<>();
        int vertexIndex = 0;
        for (JsonNode vertex : detail.path("vertices")) {
            if (vertexIndex++ >= 30) {
                break;
            }
            String vid = vertex.path("id").asText("");
            int parallelism = vertex.path("parallelism").asInt(1);
            Map<String, String> m = vertexMetrics(jobId, vid);
            VertexSummary summary = summarize(m);
            long vin = summary.recordsIn();
            long vout = summary.recordsOut();
            double vinRate = summary.recordsInPerSecond();
            double voutRate = summary.recordsOutPerSecond();
            Long wmOut = summary.watermark();
            String bp = summary.backpressure();
            recordsIn += vin;
            recordsOut += vout;
            inRate += vinRate;
            outRate += voutRate;
            if (wmOut != null && (watermark == null || wmOut < watermark)) {
                watermark = wmOut;
            }
            if (!"OK".equals(bp)) {
                backpressure = bp;
            }
            long vPending = summary.pendingRecords();
            long vLagMax = summary.recordsLagMax();
            pending += vPending;
            lagMax = Math.max(lagMax, vLagMax);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", vid);
            row.put("name", vertex.path("name").asText(""));
            row.put("parallelism", parallelism);
            row.put("status", vertex.path("status").asText(""));
            row.put("recordsIn", vin);
            row.put("recordsOut", vout);
            row.put("recordsInPerSecond", round(vinRate));
            row.put("recordsOutPerSecond", round(voutRate));
            row.put("watermark", wmOut == null ? null : wmOut);
            row.put("watermarkTime", wmOut == null ? null
                    : java.time.Instant.ofEpochMilli(wmOut).toString());
            row.put("backpressure", bp);
            row.put("pendingRecords", vPending);
            row.put("recordsLagMax", vLagMax);
            vertices.add(row);
        }
        // 用 ≥30 秒窗口的差分算平均吞吐（Flink 的 PerSecond 实测只有真实值 1/3）
        java.util.ArrayDeque<long[]> samples =
                RATE_SAMPLES.computeIfAbsent(jobId, k -> new java.util.ArrayDeque<>());
        long[] baseline = null;
        synchronized (samples) {
            samples.addLast(new long[]{now, recordsIn, recordsOut});
            while (samples.size() > 40) {
                samples.removeFirst();
            }
            // Flink 的累计计数按统计周期大幅跳变：窗口太短会把一波计数算成瞬时尖峰
            // （实测 20 条/秒会被算成 30-36）。用 ≥60 秒的最早「计数已动」样本做基准，
            // 得到的是长窗口平均吞吐，跨周期稳定。
            long[] oldestMoving = null;
            long[] oldestAny = null;
            java.util.Iterator<long[]> it = samples.iterator();
            while (it.hasNext()) {
                long[] sample = it.next();
                long age = now - sample[0];
                if (age < RATE_WINDOW_MS) {
                    continue;
                }
                if (oldestAny == null || sample[0] < oldestAny[0]) {
                    oldestAny = sample;
                }
                if (sample[1] > 0 && (oldestMoving == null || sample[0] < oldestMoving[0])) {
                    oldestMoving = sample;
                }
            }
            baseline = oldestMoving != null ? oldestMoving : oldestAny;
        }
        if (baseline != null) {
            double seconds = (now - baseline[0]) / 1000.0;
            if (seconds >= 1 && recordsIn >= baseline[1] && recordsOut >= baseline[2]) {
                inRate = (recordsIn - baseline[1]) / seconds;
                outRate = (recordsOut - baseline[2]) / seconds;
            } else if (recordsIn < baseline[1]) {
                // 计数器回落 = 作业重启，窗口作废
                synchronized (samples) {
                    samples.clear();
                }
            }
        }

        Map<String, Object> checkpoints = checkpoints(jobId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("state", detail.path("state").asText(""));
        out.put("uptime", detail.path("duration").asLong(0));
        out.put("recordsIn", recordsIn);
        out.put("recordsOut", recordsOut);
        out.put("recordsInPerSecond", round(inRate));
        out.put("recordsOutPerSecond", round(outRate));
        // v450 P2-2：速率要 ≥60 秒差分窗口才准；启动前 60 秒标「预热中」，
        // 前端显示「速率预热中（读数偏低）」而不是让用户拿早期读数当账用。
        out.put("rateWarming", inRate <= 0 && recordsIn > 0);
        out.put("watermark", watermark);
        out.put("watermarkTime", watermark == null ? null
                : java.time.Instant.ofEpochMilli(watermark).toString());
        out.put("watermarkLagSeconds", watermark == null ? null
                : Math.max(0, (now - watermark) / 1000));
        out.put("backpressure", backpressure);
        out.put("pendingRecords", pending);
        out.put("recordsLagMax", lagMax);
        out.put("checkpoints", checkpoints);
        out.put("vertices", vertices);
        return out;
    }

    /** 顶点级聚合口径（0.xxx）；算子级是 0.算子名[子任务].xxx，点号更多。 */
    private boolean vertexLevel(String id) {
        return id != null && id.chars().filter(c -> c == '.').count() == 1;
    }

    /** 顶点级固定指标：一次请求就能拿到，不用把 80 个指标全拉下来。 */
    private static final List<String> VERTEX_METRICS = List.of(
            "0.numRecordsIn", "0.numRecordsOut",
            "0.numRecordsInPerSecond", "0.numRecordsOutPerSecond",
            "0.currentInputWatermark", "0.currentOutputWatermark",
            "0.backPressuredTimeMsPerSecond", "0.pendingRecords",
            "0.records-lag-max", "0.records-lag");

    /**
     * 取一个顶点的指标。
     *
     * <p>先拿固定的顶点级指标；如果顶点级 numRecords 全是 0（部分版本/链式算子下如此），
     * 再退回算子级 numRecords* 取最大值。每个请求单独 try/catch：某个指标 id 过期
     * 不能让整个顶点变成 0（之前一次 400 就会把 80 个指标全丢掉，页面显示 0 条）。</p>
     */
    private static final List<String> MINIMAL_METRICS =
            List.of("0.numRecordsIn", "0.numRecordsOut");

    private Map<String, String> vertexMetrics(String jobId, String vertexId) {
        Map<String, String> out = new LinkedHashMap<>(
                metricMap(metricsCall(jobId, vertexId, VERTEX_METRICS)));
        if (out.isEmpty()) {
            // 作业刚起时指标端点偶尔整批失败：重试一次，再退到只问两个计数
            out.putAll(metricMap(metricsCall(jobId, vertexId, VERTEX_METRICS)));
        }
        if (out.isEmpty()) {
            out.putAll(metricMap(metricsCall(jobId, vertexId, MINIMAL_METRICS)));
        }
        long in = longOf(out.getOrDefault("0.numRecordsIn", "0"));
        long outRecords = longOf(out.getOrDefault("0.numRecordsOut", "0"));
        if (in > 0 || outRecords > 0) {
            return out;
        }
        List<String> ids;
        try {
            JsonNode list = callRest(HttpMethod.GET, "/jobs/" + jobId + "/vertices/"
                    + vertexId + "/metrics", null);
            ids = new ArrayList<>();
            for (JsonNode item : list) {
                String id = item.path("id").asText("");
                String lower = id.toLowerCase(java.util.Locale.ROOT);
                if (id.endsWith(".numRecordsIn") || id.endsWith(".numRecordsOut")
                        || id.endsWith(".numRecordsInPerSecond")
                        || id.endsWith(".numRecordsOutPerSecond")
                        || lower.endsWith(".records-lag") || lower.endsWith(".records-lag-max")
                        || id.endsWith(".pendingRecords")) {
                    ids.add(id);
                }
            }
        } catch (ApiException e) {
            return out;
        }
        for (int i = 0; i < ids.size(); i += 10) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 10));
            out.putAll(metricMap(metricsCall(jobId, vertexId, chunk)));
        }
        return out;
    }

    /**
     * 单个 metrics 请求。
     *
     * <p>不要把 id 预先 URLEncoder 一次：RestTemplate 自己会编码，预编码会变成
     * {@code %255B}，Flink 认不出指标名（算子级 id 带 {@code [子任务号]}，于是整批落空）。
     * 直接拼原文，交给 RestTemplate 处理非法字符。</p>
     */
    private JsonNode metricsCall(String jobId, String vertexId, List<String> ids) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return callRest(HttpMethod.GET, "/jobs/" + jobId + "/vertices/" + vertexId
                        + "/metrics?get=" + String.join(",", ids), null);
            } catch (ApiException e) {
                if (attempt == 1) {
                    return null;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private record VertexSummary(long recordsIn, long recordsOut, double recordsInPerSecond,
                                 double recordsOutPerSecond, Long watermark,
                                 String backpressure, long pendingRecords, long recordsLagMax) {
    }

    /**
     * 一个顶点只算一次。
     *
     * <p>链式算子（source + watermark + sink 链在同一个顶点里）每个算子都会报
     * {@code numRecordsIn}，逐项累加会按链深放大（实测 3 倍）。正确口径是顶点级
     * {@code 0.numRecordsIn}；个别版本该值为 0 时退回「算子级取最大值」，
     * 因为同一条记录在同一顶点内经过的算子看到的是同一批记录。</p>
     */
    private VertexSummary summarize(Map<String, String> metrics) {
        long vertexIn = 0;
        long vertexOut = 0;
        double vertexInRate = 0;
        double vertexOutRate = 0;
        long opMaxIn = 0;
        long opMaxOut = 0;
        double opMaxInRate = 0;
        double opMaxOutRate = 0;
        long pending = 0;
        long lag = 0;
        double backpressuredMs = 0;
        Long watermark = null;
        for (Map.Entry<String, String> entry : metrics.entrySet()) {
            String id = entry.getKey();
            String value = entry.getValue();
            boolean vertex = vertexLevel(id);
            if (id.endsWith(".numRecordsIn")) {
                if (vertex) {
                    vertexIn = longOf(value);
                } else {
                    opMaxIn = Math.max(opMaxIn, longOf(value));
                }
            } else if (id.endsWith(".numRecordsOut")) {
                if (vertex) {
                    vertexOut = longOf(value);
                } else {
                    opMaxOut = Math.max(opMaxOut, longOf(value));
                }
            } else if (id.endsWith(".numRecordsInPerSecond")) {
                if (vertex) {
                    vertexInRate = doubleOf(value);
                } else {
                    opMaxInRate = Math.max(opMaxInRate, doubleOf(value));
                }
            } else if (id.endsWith(".numRecordsOutPerSecond")) {
                if (vertex) {
                    vertexOutRate = doubleOf(value);
                } else {
                    opMaxOutRate = Math.max(opMaxOutRate, doubleOf(value));
                }
            } else if (id.endsWith(".pendingRecords")) {
                pending = Math.max(pending, longOf(value));
            } else if (id.endsWith(".records-lag-max") || id.endsWith(".records-lag")) {
                lag = Math.max(lag, longOf(value));
            } else if (id.endsWith(".backPressuredTimeMsPerSecond")) {
                backpressuredMs = Math.max(backpressuredMs, doubleOf(value));
            } else if (id.endsWith(".currentInputWatermark")
                    || id.endsWith(".currentOutputWatermark")) {
                Long wm = watermarkOf(value);
                if (wm != null && (watermark == null || wm < watermark)) {
                    watermark = wm;
                }
            }
        }
        long in = vertexIn > 0 ? vertexIn : opMaxIn;
        long out = vertexOut > 0 ? vertexOut : opMaxOut;
        double inRate = vertexInRate > 0 ? vertexInRate : opMaxInRate;
        double outRate = vertexOutRate > 0 ? vertexOutRate : opMaxOutRate;
        String bp = backpressuredMs >= 500 ? "HIGH" : (backpressuredMs > 0 ? "LOW" : "OK");
        return new VertexSummary(in, out, inRate, outRate, watermark, bp, pending, lag);
    }

    private Map<String, String> metricMap(JsonNode body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body != null && body.isArray()) {
            for (JsonNode item : body) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    out.put(id, item.path("value").asText(""));
                }
            }
        }
        return out;
    }

    private Map<String, Object> checkpoints(String jobId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode body = callRest(HttpMethod.GET, "/jobs/" + jobId + "/checkpoints", null);
            out.put("completed", body.path("counts").path("completed").asLong(0));
            out.put("failed", body.path("counts").path("failed").asLong(0));
            JsonNode latest = body.path("latest").path("completed");
            out.put("latestId", latest.path("id").asLong(0));
            out.put("latestSize", latest.path("state_size").asLong(0));
            // Flink 1.17+ 只有 latest_ack_timestamp；completed_timestamp 已不存在
            long at = latest.path("completed_timestamp").asLong(0);
            if (at == 0) {
                at = latest.path("latest_ack_timestamp").asLong(0);
            }
            out.put("latestAt", at);
        } catch (ApiException e) {
            out.put("completed", 0);
            out.put("failed", 0);
        }
        return out;
    }

    private long longOf(String value) {
        try {
            return value == null ? 0 : (long) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double doubleOf(String value) {
        try {
            return value == null ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Flink 用 Long.MIN_VALUE / MAX_VALUE 表示「没有水位」。 */
    private Long watermarkOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = (long) Double.parseDouble(value);
            if (parsed == Long.MIN_VALUE || parsed == Long.MAX_VALUE || parsed == 0) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String backpressureLabel(long level) {
        return switch ((int) level) {
            case 1 -> "LOW";
            case 2 -> "HIGH";
            default -> "OK";
        };
    }

    public void cancel(String jobId) {
        try {
            callRest(HttpMethod.PATCH, "/jobs/" + jobId + "?mode=cancel", null);
        } catch (ApiException e) {
            if (e.getStatus() == 404) {
                return; // 已经不在了：按已停止处理
            }
            // 作业已经到终态（FINISHED / CANCELED / FAILED）时 Flink 返回 400：
            // "Job cancellation failed because the job has already reached ..."。
            // 用户要的结果（作业不在跑了）已经满足，不能把这个当失败抛给页面。
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("already reached")
                    || message.contains("is not in state")
                    || message.contains("Job not found")) {
                return;
            }
            throw e;
        }
    }

    public void closeSession(String session) {
        if (session == null || session.isBlank()) {
            return;
        }
        try {
            exchange(gw() + "/v1/sessions/" + session, HttpMethod.DELETE, null);
        } catch (Exception e) {
            log.debug("关闭 SQL Gateway 会话失败（忽略）: {}", session);
        }
    }

    // ---------------------------------------------------------------- 脚本解析

    /**
     * 按分号拆语句，跳过引号与注释里的分号。
     * Flink SQL 用单引号字符串、双引号/反引号标识符，三者都要识别。
     */
    public static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    // 连续两个引号是转义，不算结束
                    if (i + 1 < script.length() && script.charAt(i + 1) == quote) {
                        current.append(script.charAt(++i));
                    } else {
                        quote = 0;
                    }
                } else if (c == '\\' && quote == '\'' && i + 1 < script.length()) {
                    current.append(script.charAt(++i));
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;
                }
                current.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < script.length()
                        && !(script.charAt(i) == '*' && script.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                current.append(' ');
                continue;
            }
            if (c == ';') {
                addStatement(out, current);
                continue;
            }
            current.append(c);
        }
        addStatement(out, current);
        return out;
    }

    private static void addStatement(List<String> out, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isEmpty()) {
            out.add(statement);
        }
    }

    // -------------------------------------------------------------- gateway 调用

    /** 顺序执行脚本里的语句，返回最后一个 INSERT 产生的 Job ID（没有则 null）。 */
    private String executeScript(String session, List<String> statements) {
        String jobId = null;
        for (String statement : statements) {
            Map<String, Object> result = executeStatement(session, statement, true);
            Object found = result.get("jobId");
            if (found != null) {
                jobId = String.valueOf(found);
            }
        }
        return jobId;
    }

    /** 当前 Flink 集群里的作业 ID 集合（含历史，用于提交前后做差集）。 */
    private java.util.Set<String> overviewJobIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        try {
            JsonNode body = callRest(HttpMethod.GET, "/jobs/overview", null);
            for (JsonNode job : body.path("jobs")) {
                ids.add(job.path("jid").asText(""));
            }
        } catch (ApiException e) {
            // Flink 暂时不可用时返回空集合，提交本身会报错
        }
        return ids;
    }

    /** 从 INSERT 语句里取目标表名，用于在多个新作业里选中正确的一个。 */
    private String sinkTableOf(List<String> statements) {
        for (int i = statements.size() - 1; i >= 0; i--) {
            String text = statements.get(i);
            int idx = text.toLowerCase().lastIndexOf("insert into");
            if (idx < 0) {
                continue;
            }
            String rest = text.substring(idx + "insert into".length()).trim();
            StringBuilder token = new StringBuilder();
            for (int j = 0; j < rest.length(); j++) {
                char c = rest.charAt(j);
                if (c == '`' || c == '"') {
                    continue;
                }
                if (Character.isWhitespace(c) || c == '(') {
                    break;
                }
                token.append(c);
            }
            String value = token.toString();
            if (value.isEmpty()) {
                continue;
            }
            int dot = value.lastIndexOf('.');
            return dot >= 0 ? value.substring(dot + 1) : value;
        }
        return null;
    }

    /** 轮询 15 秒，取新增作业里名字匹配目标表的一个。 */
    private String awaitNewJob(java.util.Set<String> before, String sinkTable) {
        long deadline = System.currentTimeMillis() + 15_000;
        List<String> seen = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode body = callRest(HttpMethod.GET, "/jobs/overview", null);
                List<String> all = new ArrayList<>();
                List<String> matched = new ArrayList<>();
                for (JsonNode job : body.path("jobs")) {
                    String id = job.path("jid").asText("");
                    if (id.isBlank() || before.contains(id)) {
                        continue;
                    }
                    all.add(id);
                    seen.add(id + ":" + job.path("name").asText("") + ":" + job.path("state").asText(""));
                    String name = job.path("name").asText("");
                    if (sinkTable != null && name.endsWith("." + sinkTable)) {
                        matched.add(id);
                    }
                }
                if (matched.size() == 1) {
                    return matched.get(0);
                }
                if (matched.size() > 1) {
                    return matched.get(matched.size() - 1);
                }
                // 作业名可能被 Flink 改写（catalog/database 前缀变化），
                // 只有一个新作业时直接用，避免因为名字不匹配误报「没有产生 Job」
                if (all.size() == 1) {
                    return all.get(0);
                }
            } catch (ApiException e) {
                // 继续重试
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("awaitNewJob 未找到新作业 sink={} fresh={}", sinkTable, seen);
        return null;
    }

    private String createSession() {
        Map<String, Object> body = Map.of("properties", Map.of(
                "execution.runtime-mode", "STREAMING",
                "parallelism.default", "1"));
        JsonNode response = exchange(gw() + "/v1/sessions", HttpMethod.POST, body);
        String handle = response.path("sessionHandle").asText("");
        if (handle.isBlank()) {
            throw new ApiException(502, "Flink SQL Gateway 未返回 sessionHandle");
        }
        return handle;
    }

    /**
     * 执行一条语句并等它结束。
     *
     * @param collectJob 为 true 时从结果行里找 INSERT 产生的 Job ID
     */
    private Map<String, Object> executeStatement(String session, String statement,
                                                 boolean collectJob) {
        JsonNode submitted = exchange(
                gw() + "/v1/sessions/" + session + "/statements",
                HttpMethod.POST, Map.of("statement", statement));
        String operation = submitted.path("operationHandle").asText("");
        if (operation.isBlank()) {
            throw new ApiException(502, "Flink SQL Gateway 未返回 operationHandle");
        }
        long deadline = System.currentTimeMillis() + 90_000;
        String status = "";
        boolean finished = false;
        String jobId = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode body = exchange(gw() + "/v1/sessions/" + session
                    + "/operations/" + operation + "/status", HttpMethod.GET, null);
            status = body.path("status").asText("").toUpperCase(Locale.ROOT);
            if ("ERROR".equals(status) || "CANCELED".equals(status)
                    || "CLOSED".equals(status)) {
                break;
            }
            if ("FINISHED".equals(status)) {
                finished = true;
                break;
            }
            // 流式 INSERT 一旦 RUNNING 就代表作业已提交，可以先取结果；
            // 结果 token 只能消费一次，所以循环里只等状态，结果在循环外取一次。
            if ("RUNNING".equals(status) && collectJob) {
                break;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(500, "执行被中断");
            }
        }
        if ("ERROR".equals(status)) {
            throw ApiException.badRequest("FlinkSQL 执行失败: "
                    + readable(scrub(operationError(session, operation))));
        }
        boolean running = "RUNNING".equals(status);
        if (jobId == null && collectJob && (running || finished)) {
            jobId = readJobId(session, operation);
        }
        if (!finished && !running) {
            throw new ApiException(502, "FlinkSQL 执行超时或状态异常: " + status);
        }
        if (!collectJob && !finished) {
            throw new ApiException(502, "语句执行超时: " + status);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowCount", 0);
        if (jobId != null) {
            out.put("jobId", jobId);
        }
        return out;
    }

    /**
     * 取失败原因。Gateway 的 /status 只有 {"status":"ERROR"}，
     * 真正的 SQL 错误在 /result/0 的 errors[] 里（整段 Java 堆栈），
     * 这里抽出最后一条 Caused by 的可读信息。
     */
    private String operationError(String session, String operation) {
        // v450 P1-1：exchange() 的异常消息会被 clean() 截到 800 字符，而网关把真正的
        // 异常栈放在 500 响应的 errors[] 里（位置在很后面）。这里直接拿原始响应体。
        String raw;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> response = http.exchange(
                    gw() + "/v1/sessions/" + session + "/operations/" + operation + "/result/0",
                    HttpMethod.GET, new HttpEntity<>(null, headers), JsonNode.class);
            raw = gatewayErrors(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            raw = gatewayErrorsText(e.getResponseBodyAsString());
        } catch (Exception e) {
            return "语句执行失败（Gateway 不可用：" + e.getMessage() + "）";
        }
        return fromErrorText(raw);
    }

    /** 优先取 errors[] 拼起来；没有就返回原文。 */
    private String gatewayErrors(JsonNode body) {
        if (body == null) {
            return null;
        }
        JsonNode errors = body.path("errors");
        if (errors.isArray()) {
            StringBuilder all = new StringBuilder();
            for (JsonNode item : errors) {
                all.append(item.asText("")).append("\n");
            }
            return all.toString();
        }
        return body.toString();
    }

    private String gatewayErrorsText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            return gatewayErrors(mapper.readTree(text));
        } catch (Exception e) {
            return text;
        }
    }

    private static final Pattern ERROR_POSITION = Pattern.compile(
            "From line (\\d+), column (\\d+)(?: to line \\d+, column \\d+)?:\s*([^\\n]+)");

    /**
     * 把引擎异常变成人能读的一句话。
     *
     * <p>v450 P1-1：以前只取最后一个 `Caused by:`，而真正带行列号的
     * `CalciteContextException: From line 1, column 22 to ...` 在栈的中间，
     * 最后一个 Caused by 往往是不带位置的 SqlValidatorException → 位置信息丢失。
     * 现在优先找「From line X, column Y」，并区分语法 / 校验 / UDF 未注册。</p>
     */
    private String fromErrorText(String text) {
        String value = text == null ? "" : text
                .replace("\\n", "\n").replace("\\t", "\t");
        if (value.isBlank()) {
            return "语句执行失败（Gateway 未返回详情）";
        }
        boolean syntax = value.contains("SqlParseException") || value.contains("ParseException")
                || value.contains("SQL parse failed");
        Matcher position = ERROR_POSITION.matcher(value);
        if (position.find()) {
            String line = position.group(1);
            String column = position.group(2);
            String message = position.group(3).trim();
            String prefix = syntax ? "SQL 语法错误" : "SQL 校验失败";
            String hint = message.contains("No match found for function signature")
                    ? "（函数未注册，或引擎不支持该函数；UDF 请先在「函数管理」注册并启用）"
                    : "";
            return prefix + "：第 " + line + " 行第 " + column + " 列：" + message + hint;
        }
        Matcher caused = Pattern.compile("Caused by: ([^\\n]+)").matcher(value);
        String last = null;
        while (caused.find()) {
            last = caused.group(1);
        }
        String chosen = last == null ? value : last;
        if (chosen.contains("No match found for function signature")) {
            return "SQL 校验失败：" + cleanError(chosen)
                    + "（函数未注册，或引擎不支持该函数；UDF 请先在「函数管理」注册并启用）";
        }
        return cleanError(chosen);
    }

    /** 去掉异常类名前缀，只留人能看懂的部分。 */
    private String cleanError(String line) {
        String value = line.trim();
        int split = value.indexOf("Exception: ");
        if (split > 0) {
            value = value.substring(split + "Exception: ".length());
        }
        return value.length() > 800 ? value.substring(0, 800) + "…" : value;
    }

    /** 结果第一页里 INSERT 行的第一个字段就是 Job ID。 */
    private String readJobId(String session, String operation) {
        String token = "0";
        for (int page = 0; page < 5 && token != null; page++) {
            JsonNode body;
            try {
                body = exchange(gw() + "/v1/sessions/" + session + "/operations/"
                        + operation + "/result/" + token, HttpMethod.GET, null);
            } catch (ApiException e) {
                return null;
            }
            JsonNode data = body.path("results").path("data");
            if (data.isArray()) {
                for (JsonNode row : data) {
                    if (!"INSERT".equalsIgnoreCase(row.path("kind").asText(""))) {
                        continue;
                    }
                    JsonNode fields = row.path("fields");
                    for (JsonNode field : fields) {
                        String value = field.asText("");
                        if (JOB_ID.matcher(value).matches()) {
                            return value;
                        }
                    }
                }
            }
            JsonNode next = body.path("nextResultUri");
            token = next.isMissingNode() || next.isNull() || next.asText("").isBlank()
                    ? null : String.valueOf(page + 1);
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP 细节

    private JsonNode callRest(HttpMethod method, String path, Object body) {
        return exchange(rest() + path, method, body);
    }

    private JsonNode exchange(String url, HttpMethod method, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<JsonNode> response = http.exchange(url, method,
                    new HttpEntity<>(body, headers), JsonNode.class);
            return response.getBody() == null ? mapper.createObjectNode() : response.getBody();
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String detail = e.getResponseBodyAsString();
            throw new ApiException(status >= 500 ? 502 : status,
                    "Flink 调用失败: " + readable(clean(detail)));
        } catch (Exception e) {
            throw new ApiException(502, "Flink 不可用: " + e.getMessage());
        }
    }

    /**
     * 错误信息截断：前端只需要「错在哪」，不需要 Java 堆栈（审计 P2-7）。
     * 保留前 6 行、最多 800 字符，并去掉 at org... 这类栈帧。
     */
    /**
     * 把引擎异常变成人能读的一行：去掉 `org.apache.flink...SqlGatewayException:` 这类类名，
     * 也去掉 `Failed to execute statement:` 这种包装前缀（审计 P2-7）。
     */
    private String readable(String text) {
        // Gateway 的 errors[] 里换行是字面量 "\n\t"（反斜杠+n），先还原成真换行再清洗
        String raw = text == null ? null : text
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "");
        String value = shorten(raw);
        if (value == null) {
            return null;
        }
        value = value.replaceAll("(?m)^(?:[a-zA-Z0-9_$]+\\.)+[A-Za-z0-9_$]+(?:Exception|Error):\\s*", "");
        // clean() 会把换行压成空格，栈帧可能黏在一行里：整体删掉 at xxx.yyy(File.java:12)
        value = value.replaceAll("\\s+at\\s+(?:[A-Za-z0-9_$.]+\\.)+[A-Za-z0-9_$]+\\.[A-Za-z0-9_$]+\\([^)]*\\)", "");
        value = value.replaceAll("\\s*\\.\\.\\.\\s+\\d+ more", "");
        value = value.replaceFirst("(?i)^Failed to execute statement:\\s*", "");
        value = value.replaceFirst("(?i)^Failed to fetchResults\\.?\\s*", "SQL 执行失败：");
        value = value.replaceFirst("(?i)^Failed to parse:\\s*", "SQL 解析失败：");
        return value.trim();
    }
    private String shorten(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int lines = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at ") || trimmed.startsWith("... ")
                    || trimmed.startsWith("Suppressed:")) {
                continue;
            }
            if (trimmed.isEmpty() && out.length() == 0) {
                continue;
            }
            out.append(trimmed).append('\n');
            if (++lines >= 6) {
                break;
            }
        }
        String result = out.toString().trim();
        return result.length() > 800 ? result.substring(0, 800) + "…" : result;
    }

    private String clean(String body) {
        if (body == null || body.isBlank()) {
            return "未知错误";
        }
        try {
            JsonNode node = mapper.readTree(body);
            for (String key : new String[]{"errors", "message", "detail"}) {
                String value = node.path(key).asText("");
                if (!value.isBlank()) {
                    body = value;
                    break;
                }
            }
        } catch (Exception ignored) {
            // 原文返回
        }
        body = body.replaceAll("\\s+", " ").trim();
        return body.length() > 800 ? body.substring(0, 800) + "…" : body;
    }

    /** 供控制器在出错时记录，不影响业务数据。 */
    public void logSubmitFailure(long id, String message) {
        try {
            jdbc.update("""
                    update platform.flink_sql_job
                       set status = 'FAILED', last_error = ?, updated_at = now()
                     where id = ?
                    """, message == null ? "" : message.substring(0, Math.min(2000, message.length())), id);
        } catch (Exception e) {
            log.warn("记录 Flink 提交失败状态失败 job={}", id, e);
        }
    }
}
