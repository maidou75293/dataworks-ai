package com.lab.dataworks.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.integration.SqlTemplates;
import com.lab.dataworks.project.DorisTargetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 统一的 Doris 查询入口：数据查询页与「节点试跑」共用同一套调用与错误处理，
 * 避免两处各写一份 HTTP 细节后行为漂移。
 *
 * <p>平台内置 Doris 走 MCP（凭据只在 MCP 侧）；项目选了外部 Doris 时后端按连接
 * JDBC 直连，并在执行前做与 MCP 相同的只读校验——不能因为换了执行入口就放松限制。
 */
@Service
public class DorisQueryService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DorisQueryService.class);

    /** MCP 通道的读超时（秒）。租户超时设得比它小才有必要自己掐表；更大时由通道兜底。 */
    static final int MCP_CHANNEL_READ_TIMEOUT_S = 60;

    /** D45：掐表用的守护线程池——中断后等待线程立刻归还，底层读最迟 60s 自然超时。 */
    private static final java.util.concurrent.ExecutorService MCP_CALLS =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "doris-mcp-call");
                t.setDaemon(true);
                return t;
            });


    /** 查询结果；elapsedMs 含 MCP 往返时间，target 用于界面提示实际连的是哪个 Doris。 */
    public record Result(List<String> columns, List<List<String>> rows, int rowCount,
                         int maxRows, long elapsedMs, String target) {
    }

    private final RestTemplate http;
    private final String mcpBaseUrl;
    private final String mcpToken;
    private final DorisTargetService targets;
    private final String builtinUrl;
    private final String builtinUser;
    private final String builtinPassword;
    private final ObjectMapper mapper = new ObjectMapper();

    public DorisQueryService(RestTemplateBuilder builder,
                             @Value("${mcp.base-url}") String mcpBaseUrl,
                             @Value("${mcp.token:}") String mcpToken,
                             DorisTargetService targets,
                             @Value("${doris.host:127.0.0.1}") String dorisHost,
                             @Value("${doris.port:9030}") int dorisPort,
                             @Value("${doris.user:dataworks}") String dorisUser,
                             @Value("${doris.password:}") String dorisPassword) {
        this.http = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(MCP_CHANNEL_READ_TIMEOUT_S))
                .build();
        this.mcpBaseUrl = mcpBaseUrl.endsWith("/")
                ? mcpBaseUrl.substring(0, mcpBaseUrl.length() - 1) : mcpBaseUrl;
        this.mcpToken = mcpToken;
        this.targets = targets;
        this.builtinUrl = "jdbc:mysql://" + dorisHost + ":" + dorisPort
                + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=5000&socketTimeout=60000";
        this.builtinUser = dorisUser;
        this.builtinPassword = dorisPassword;
    }

    /** 平台内置 Doris（MCP 通道）。 */
    public Result query(String sql, int maxRows) {
        return query(null, sql, maxRows);
    }

    /**
     * 绕过 MCP 包装的 JDBC 查询，用于 information_schema 这类元数据表。
     *
     * <p>MCP 会把 SQL 包成 {@code SELECT * FROM (...) LIMIT n}；Doris 4.1 对
     * information_schema.columns 套子查询会返回 0 行（实测 803 行变 0 行），
     * 所以元数据必须走未包装的路径。
     */
    public Result rawQuery(Long projectId, String sql, int maxRows) {
        DorisTargetService.Target target = targets.forProject(projectId);
        if (target.external()) {
            return jdbcQuery(sql, maxRows, target);
        }
        DorisTargetService.Target builtin = new DorisTargetService.Target(
                true, target.label(), builtinUrl, builtinUser, builtinPassword);
        return jdbcQuery(sql, maxRows, builtin);
    }

    /** projectId 为空 = 平台内置；否则按项目选择的 Doris 连接执行。 */
    public Result query(Long projectId, String sql, int maxRows) {
        DorisTargetService.Target target = targets.forProject(projectId);
        return target.external() ? jdbcQuery(sql, maxRows, target) : mcpQuery(sql, maxRows, target);
    }

    /**
     * 这次操作是否落在平台内置的<b>共享</b> Doris 上（D05）。
     *
     * <p>平台只有一个内置 Doris，所有租户项目的库都建在同一实例上，库名才由
     * {@code platform.project.dev_database / prod_database} 声明归属；项目引用外部
     * Doris 时整个实例都是这个项目的，按库名再收口会把项目自己的表全部藏掉。
     * 与 {@code DataGuardService.usesBuiltinDoris} 同一口径，这里直接复用
     * {@link DorisTargetService} 的解析结果，避免两处判断漂移。</p>
     */
    public boolean usesBuiltinDoris(Long projectId) {
        return !targets.forProject(projectId).external();
    }

    private Result mcpQuery(String sql, int maxRows, DorisTargetService.Target target) {
        long start = System.nanoTime();
        // D45：内置通道走 MCP，MCP 侧没有租户限额的上下文，限额必须在这里落地——
        // 之前只有外部 Doris 的 jdbcQuery 消费 limits，租户把 maxRows 调成 10、
        // timeoutS 调成 1，内置库查询照样 1000 行 / 60 秒，fields 里 advertised 的
        // enforced=true 在默认路径上不成立（卡面验收「证明新上限生效」在现网必红）。
        QueryLimitsService.Limits limits = QueryLimitsService.current();
        int effectiveMaxRows = limits == null
                ? maxRows : Math.max(1, Math.min(maxRows, limits.maxRows()));
        // MCP 单次调用没有会话变量可下发，超时改由调用侧中断等待实现：
        // 到点不再等结果并显式 400（Doris 端由它自己的 query_timeout 兜底清理，
        // 这是与 JDBC 通道的已知差异，界面文案按这个口径写）。
        int timeoutS = limits == null ? 60 : Math.max(1, limits.timeoutS());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sql", sql);
        params.put("max_rows", effectiveMaxRows);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (mcpToken != null && !mcpToken.isBlank()) {
            headers.setBearerAuth(mcpToken);
        }
        try {
            ResponseEntity<JsonNode> response;
            if (timeoutS < MCP_CHANNEL_READ_TIMEOUT_S) {
                // 通道级读超时是 60s；租户设了更小的超时必须由我们主动掐表，
                // 不能等 RestTemplate 自己超时（那是 502「服务不可用」，不是「你超时」）。
                java.util.concurrent.Future<ResponseEntity<JsonNode>> call = MCP_CALLS.submit(
                        () -> http.exchange(
                                mcpBaseUrl + "/tools/doris.sql_query",
                                HttpMethod.POST,
                                new HttpEntity<>(Map.of("params", params), headers),
                                JsonNode.class));
                try {
                    response = call.get(timeoutS, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    call.cancel(true);
                    log.warn("doris mcp query interrupted after timeoutS={} sqlHead={}",
                            timeoutS, sql.substring(0, Math.min(80, sql.length())));
                    throw new ApiException(400, "查询超过租户超时上限 " + timeoutS
                            + " 秒，已被中断（traceId=" + requestId() + "）");
                } catch (java.util.concurrent.ExecutionException ee) {
                    // 保持与直调一致：HTTP 错误（HttpStatusCodeException 等）原样上抛，
                    // 落到下面的分类处理；其它非运行时异常不该出现，包一层再走 502 分支
                    if (ee.getCause() instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException(ee.getCause());
                }
            } else {
                response = http.exchange(
                        mcpBaseUrl + "/tools/doris.sql_query",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("params", params), headers),
                        JsonNode.class);
            }
            JsonNode result = response.getBody() == null ? null : response.getBody().path("result");
            List<String> columns = new ArrayList<>();
            if (result != null) {
                result.path("columns").forEach(col -> columns.add(col.asText()));
            }
            List<List<String>> rows = new ArrayList<>();
            if (result != null) {
                for (JsonNode row : result.path("rows")) {
                    List<String> cells = new ArrayList<>();
                    row.forEach(cell -> cells.add(cell.isNull() ? null : cell.asText()));
                    rows.add(cells);
                }
            }
            long elapsedMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
            // maxRows 回显实际生效的上限（min(请求, 租户)），界面/脚本据此能看出限额真落地了
            return new Result(columns, rows, rows.size(), effectiveMaxRows, elapsedMs, target.label());
        } catch (ApiException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // v449 P2-4：不再把内部改写后的 SQL、Doris 原始报文回显给调用方，
            // 详细原因写日志（带 requestId），对外只给可操作的一句话。
            String detail = cleanDetail(e.getResponseBodyAsString());
            log.warn("doris query failed status={} body={}", e.getStatusCode(), detail);
            // 只读校验 / SQL 不合法是我们自己的策略与用户输入问题，保留可操作提示；
            // 其余（Doris 原始报错、内部包装 SQL）一律不外泄。
            if (detail.contains("SQL_NOT_READ_ONLY")) {
                throw new ApiException(400, "SQL_NOT_READ_ONLY：查询页只允许只读 SQL（SELECT / WITH），不允许写操作");
            }
            if (detail.contains("Current database is not set")) {
                throw new ApiException(400,
                        "项目未配置默认库：请在 SQL 中使用 <库>.<表>，或为项目设置默认库");
            }
            if (detail.contains("SQL_INVALID")) {
                throw new ApiException(400, detail.replaceFirst("^.*?SQL_INVALID:?\s*", ""));
            }
            throw new ApiException(400, queryFailureMessage());
        } catch (Exception e) {
            log.warn("doris query error: {}", e.getMessage());
            throw new ApiException(502, "查询服务暂时不可用，请稍后重试（traceId=" + requestId() + "）");
        }
    }

    private Result jdbcQuery(String sql, int maxRows, DorisTargetService.Target target) {
        // 外部连接绕过了 MCP，只读限制必须在这里补上，且规则与 MCP 一致（fail-closed）
        SqlTemplates.guardReadOnly(sql, "查询 SQL");
        long start = System.nanoTime();
        Properties props = new Properties();
        props.setProperty("user", target.username());
        props.setProperty("password", target.password());
        DriverManager.setLoginTimeout(5);
        try (Connection conn = DriverManager.getConnection(target.jdbcUrl(), props);
             Statement stmt = conn.createStatement()) {
            try {
                conn.setReadOnly(true);
            } catch (SQLException ignored) {
                // Doris 驱动不保证支持，只读语义仍由上面的 SQL 校验兜住
            }
            // D4：把租户限额下发到这条连接（会话级，不会影响别的并发查询）
            QueryLimitsService.Limits limits = QueryLimitsService.current();
            if (limits != null) {
                QueryLimitsService.apply(conn, limits);
            }
            stmt.setQueryTimeout(limits == null ? 60 : limits.timeoutS());
            stmt.setMaxRows(limits == null ? maxRows : Math.min(maxRows, limits.maxRows()));
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                List<String> columns = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> cells = new ArrayList<>(count);
                    for (int i = 1; i <= count; i++) {
                        // 与 MCP 返回保持同一形态：单元格一律字符串，NULL 为 null
                        String value = rs.getString(i);
                        cells.add(rs.wasNull() ? null : value);
                    }
                    rows.add(cells);
                }
                long elapsedMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
                return new Result(columns, rows, rows.size(), maxRows, elapsedMs, target.label());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("doris jdbc query failed target={}: {}", target.label(),
                    cleanDetail(e.getMessage()));
            throw new ApiException(400, queryFailureMessage());
        }
    }

    /** 对外统一的查询失败文案：不暴露包装 SQL / 库状态，详细原因只在日志里。 */
    private String queryFailureMessage() {
        return "查询失败：SQL 语法错误、对象不存在或无权访问（traceId=" + requestId() + "）";
    }

    private static String requestId() {
        String id = org.slf4j.MDC.get("requestId");
        return id == null || id.isBlank() ? "-" : id;
    }

    private String cleanDetail(String body) {
        if (body == null || body.isBlank()) {
            return "未知错误";
        }
        try {
            JsonNode node = mapper.readTree(body);
            String detail = node.path("detail").asText("");
            if (!detail.isBlank()) {
                body = detail;
            }
        } catch (Exception ignored) {
            // body 不是 JSON，按原文处理
        }
        body = body.replaceAll("(?i)-{3,}\\s*", "").trim();
        return body.length() > 600 ? body.substring(0, 600) + "…" : body;
    }
}
