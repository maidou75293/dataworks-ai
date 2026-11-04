package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.flink.FlinkSqlService;
import com.lab.dataworks.integration.SqlTemplates;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.udf.UdfService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流发布前置校验（v450 P1-3）。
 *
 * <p>以前工作流没有 validate/smoke，坏 SQL 可以直接发布到 DEV，调度同时 ONLINE，
 * 上线即定时失败。这里提供两级：
 * <ul>
 *   <li>{@link #validate}：结构校验（renderer 的任务/依赖检查）+ SQL 语法探测
 *       （只读查询用 {@code select * from (<sql>) limit 0} 让引擎先解析一遍）；</li>
 *   <li>{@link #smoke}：在 validate 基础上真实执行最多 {@value #SMOKE_ROWS} 行，
 *       返回列/行数/耗时，供页面「先在 DEV 跑通后发布」。</li>
 * </ul>
 * 发布路径（DEV 发布与 PROD 审批发布）会强制跑 {@link #validate}，失败直接 400。</p>
 */
@Component
public class WorkflowValidationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SMOKE_ROWS = 5;

    /** 引擎报错里的位置：Doris/MySQL 的「at line 1」与 Doris 的「(line 1, pos 14)」。 */
    private static final Pattern AT_LINE = Pattern.compile("(?i)\\bat line (\\d+)");
    private static final Pattern LINE_POS = Pattern.compile("(?i)\\(line (\\d+), pos (\\d+)\\)");
    private static final Pattern POSITION_CN = Pattern.compile("第\\s*(\\d+)\\s*行(?:第\\s*(\\d+)\\s*列)?");

    private final ObjectMapper mapper;
    private final DorisQueryService doris;
    private final UdfService udf;
    private final PrefectFlowRenderer renderer;
    private final FlinkSqlService flink;
    /** SPARK 校验走的常驻 Thrift Server；请求时再归一化，配置错不会拖垮启动。 */
    private final String sparkUrl;
    private final String sparkUsername;
    private final String sparkPassword;
    private final int sparkTimeoutSeconds;

    public WorkflowValidationService(ObjectMapper mapper, DorisQueryService doris,
                                     UdfService udf, PrefectFlowRenderer renderer,
                                     FlinkSqlService flink,
                                     @Value("${spark.thrift.url:"
                                             + SparkThriftSettings.DEFAULT_URL + "}") String sparkUrl,
                                     @Value("${spark.thrift.username:"
                                             + SparkThriftSettings.DEFAULT_USERNAME + "}") String sparkUsername,
                                     @Value("${spark.thrift.password:}") String sparkPassword,
                                     @Value("${spark.thrift.query-timeout-seconds:"
                                             + SparkThriftSettings.DEFAULT_TIMEOUT_SECONDS + "}")
                                     int sparkTimeoutSeconds) {
        this.mapper = mapper;
        this.doris = doris;
        this.udf = udf;
        this.renderer = renderer;
        this.flink = flink;
        this.sparkUrl = sparkUrl;
        this.sparkUsername = sparkUsername;
        this.sparkPassword = sparkPassword;
        this.sparkTimeoutSeconds = sparkTimeoutSeconds;
    }

    public record TaskCheck(String code, String type, String status, Integer line,
                            Integer column, String message, Integer rowCount, Long elapsedMs,
                            List<String> columns) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", code);
            out.put("type", type);
            out.put("status", status);
            out.put("line", line);
            out.put("column", column);
            out.put("message", message);
            if (rowCount != null) {
                out.put("rowCount", rowCount);
                out.put("elapsedMs", elapsedMs);
                out.put("columns", columns);
            }
            return out;
        }
    }

    public record Outcome(boolean ok, List<TaskCheck> tasks, String model) {
        public Map<String, Object> toMap() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TaskCheck task : tasks) {
                rows.add(task.toMap());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", ok);
            out.put("tasks", rows);
            return out;
        }
    }

    /** 只校验不执行（发布闸门用）。失败时返回的 tasks 里 status=failed。 */
    public Outcome validate(long workflowId, long projectId, String projectCode, String code,
                            String name, String env, String modelJson) {
        JsonNode model = structure(workflowId, projectCode, code, name, env, modelJson);
        List<TaskCheck> tasks = new ArrayList<>();
        boolean ok = true;
        for (JsonNode task : model.path("tasks")) {
            String taskCode = task.path("code").asText("");
            String type = task.path("type").asText("SQL").toUpperCase(Locale.ROOT);
            if (!"SQL".equals(type)) {
                tasks.add(new TaskCheck(taskCode, type, "skipped", null, null,
                        "非 SQL 节点，不做语法探测", null, null, null));
                continue;
            }
            String sql = script(task);
            if (sql.isBlank()) {
                tasks.add(new TaskCheck(taskCode, type, "failed", 1, null,
                        "节点 SQL 不能为空", null, null, null));
                ok = false;
                continue;
            }
            ScriptEngine engine = ScriptEngine.fromTask(task);
            String conflict = ScriptEngineRules.crossEngineSyntax(engine, sql);
            if (conflict != null) {
                tasks.add(new TaskCheck(taskCode, type, "failed", 1, null,
                        conflict, null, null, null));
                ok = false;
                continue;
            }
            if (engine == ScriptEngine.FLINK && task.hasNonNull("datasourceId")) {
                tasks.add(new TaskCheck(taskCode, type, "failed", null, null,
                        "FLINK 引擎不支持绑定数据源，请先解除绑定（Flink 走 SQL Gateway 连接）",
                        null, null, null));
                ok = false;
                continue;
            }
            try {
                if (engine == ScriptEngine.DORIS) {
                    udf.checkUsage(projectId, sql);
                }
                if (engine == ScriptEngine.FLINK) {
                    flink.validate(sql);
                    tasks.add(new TaskCheck(taskCode, type, "ok", null, null,
                            "Flink 校验通过（EXPLAIN）", null, null, null));
                    continue;
                }
                if (task.hasNonNull("datasourceId")) {
                    tasks.add(new TaskCheck(taskCode, type, "skipped", null, null,
                            "节点绑定独立数据源，发布前不在这里探测", null, null, null));
                    continue;
                }
                if (!isReadQuery(sql)) {
                    tasks.add(new TaskCheck(taskCode, type, "skipped", null, null,
                            "非查询语句（写/DDL），发布前不做执行探测", null, null, null));
                    continue;
                }
                if (engine == ScriptEngine.SPARK) {
                    probeSpark(probe(sql), 0);
                } else {
                    doris.query(projectId, probe(sql), 0);
                }
                tasks.add(new TaskCheck(taskCode, type, "ok", null, null, null,
                        null, null, null));
            } catch (RuntimeException e) {
                String message = readable(e.getMessage());
                Position position = position(message);
                String status = blocking(message) ? "failed" : "warning";
                if ("failed".equals(status)) {
                    ok = false;
                }
                tasks.add(new TaskCheck(taskCode, type, status, position.line,
                        position.column, message, null, null, null));
            }
        }
        return new Outcome(ok, tasks, modelJson);
    }

    /** DEV 跑通：真实执行只读查询（最多 5 行），返回列/行数/耗时。 */
    public Outcome smoke(long workflowId, long projectId, String projectCode, String code,
                         String name, String env, String modelJson) {
        JsonNode model = structure(workflowId, projectCode, code, name, env, modelJson);
        List<TaskCheck> tasks = new ArrayList<>();
        boolean ok = true;
        for (JsonNode task : model.path("tasks")) {
            String taskCode = task.path("code").asText("");
            String type = task.path("type").asText("SQL").toUpperCase(Locale.ROOT);
            if (!"SQL".equals(type)) {
                tasks.add(new TaskCheck(taskCode, type, "skipped", null, null,
                        "非 SQL 节点，DEV 跑通不执行", null, null, null));
                continue;
            }
            String sql = script(task);
            if (sql.isBlank()) {
                ok = false;
                tasks.add(new TaskCheck(taskCode, type, "failed", 1, null,
                        "节点 SQL 不能为空", null, null, null));
                continue;
            }
            ScriptEngine engine = ScriptEngine.fromTask(task);
            String conflict = ScriptEngineRules.crossEngineSyntax(engine, sql);
            if (conflict != null) {
                ok = false;
                tasks.add(new TaskCheck(taskCode, type, "failed", 1, null,
                        conflict, null, null, null));
                continue;
            }
            if (engine == ScriptEngine.FLINK && task.hasNonNull("datasourceId")) {
                ok = false;
                tasks.add(new TaskCheck(taskCode, type, "failed", null, null,
                        "FLINK 引擎不支持绑定数据源，请先解除绑定（Flink 走 SQL Gateway 连接）",
                        null, null, null));
                continue;
            }
            try {
                if (engine == ScriptEngine.DORIS) {
                    udf.checkUsage(projectId, sql);
                }
                if (engine == ScriptEngine.FLINK) {
                    // 流式作业不能同步取回结果：DEV 跑通只做 EXPLAIN，明确标注，不留常驻作业
                    flink.validate(sql);
                    tasks.add(new TaskCheck(taskCode, type, "ok", null, null,
                            "Flink 为异步执行，DEV 跑通只做 EXPLAIN 校验", null, null, null));
                    continue;
                }
                if (task.hasNonNull("datasourceId")) {
                    tasks.add(new TaskCheck(taskCode, type, "skipped", null, null,
                            "节点绑定独立数据源，DEV 跑通请用单节点试跑", null, null, null));
                    continue;
                }
                if (!isReadQuery(sql)) {
                    tasks.add(new TaskCheck(taskCode, type, "skipped", null, null,
                            "非查询语句，DEV 跑通不执行", null, null, null));
                    continue;
                }
                if (engine == ScriptEngine.SPARK) {
                    SparkProbe result = probeSpark(sql, SMOKE_ROWS);
                    tasks.add(new TaskCheck(taskCode, type, "ok", null, null, null,
                            result.rowCount(), result.elapsedMs(), result.columns()));
                } else {
                    DorisQueryService.Result result = doris.query(projectId, sql, SMOKE_ROWS);
                    tasks.add(new TaskCheck(taskCode, type, "ok", null, null, null,
                            result.rowCount(), result.elapsedMs(), result.columns()));
                }
            } catch (RuntimeException e) {
                ok = false;
                String message = readable(e.getMessage());
                Position position = position(message);
                tasks.add(new TaskCheck(taskCode, type, "failed", position.line,
                        position.column, message, null, null, null));
            }
        }
        return new Outcome(ok, tasks, modelJson);
    }

    /** 发布闸门：任何任务失败都抛 400，消息里带任务编码与行号。 */
    public void requireValid(long workflowId, long projectId, String projectCode, String code,
                             String name, String env, String modelJson) {
        Outcome outcome = validate(workflowId, projectId, projectCode, code, name, env, modelJson);
        if (outcome.ok()) {
            return;
        }
        for (TaskCheck task : outcome.tasks()) {
            if (!"failed".equals(task.status())) {
                continue;
            }
            String where = task.line() == null ? ""
                    : "（第 " + task.line() + " 行"
                      + (task.column() == null ? "" : "第 " + task.column() + " 列") + "）";
            throw ApiException.badRequest(
                    "发布前校验失败：任务 " + task.code() + where + "：" + task.message());
        }
        throw ApiException.badRequest("发布前校验失败");
    }

    // ---------- 内部 ----------

    private JsonNode structure(long workflowId, String projectCode, String code, String name,
                               String env, String modelJson) {
        // renderer 会做「至少一个任务 / 任务类型 / 依赖存在 / 无环」这些结构校验
        renderer.render(workflowId, projectCode, code, name, env, modelJson, null, 0,
                "validate-" + workflowId);
        try {
            return mapper.readTree(modelJson == null || modelJson.isBlank() ? "{}" : modelJson);
        } catch (Exception e) {
            throw ApiException.badRequest("工作流模型不是合法 JSON: " + e.getMessage());
        }
    }

    private String script(JsonNode task) {
        String sql = task.path("script").asText("");
        return sql.replace("${biz_date}", LocalDate.now(ZONE).toString()).trim();
    }

    // ------------------------------------------------------------- SPARK 探测

    private record SparkProbe(List<String> columns, int rowCount, long elapsedMs) {
    }

    /**
     * SPARK 校验/DEV 跑通走常驻 Spark Thrift Server（HiveServer2）。
     *
     * <p>{@code maxRows<=0} 用于发布前校验：调用方已把 SQL 包成 {@code limit 0}，
     * 这里只让 Spark 解析/出计划；{@code maxRows>0} 用于 DEV 跑通，真实执行但最多取 5 行。
     * 连接失败/未部署不在这里武断判死：由 {@link #blocking(String)} 归类成 warning，
     * 避免集群抖动时把整个发布闸门卡住。</p>
     */
    private SparkProbe probeSpark(String sql, int maxRows) {
        SparkThriftSettings settings = SparkThriftSettings.of(sparkUrl, sparkUsername,
                sparkPassword, sparkTimeoutSeconds);
        // Spark/Hive 也支持多语句，先过只读守卫，防止校验路径被塞入写操作
        SqlTemplates.guardReadOnly(sql, "SPARK 校验 SQL");
        String driver = JdbcUrlGuard.driverFor(settings.url());
        long start = System.currentTimeMillis();
        Properties props = new Properties();
        props.setProperty("user", settings.username());
        props.setProperty("password", settings.password());
        try {
            if (driver != null) {
                Class.forName(driver);
            }
            DriverManager.setLoginTimeout(10);
            try (Connection conn = DriverManager.getConnection(settings.url(), props);
                 Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(Math.max(1, settings.queryTimeoutSeconds()));
                stmt.setMaxRows(maxRows <= 0 ? 1 : maxRows);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int count = meta.getColumnCount();
                    List<String> columns = new ArrayList<>(count);
                    for (int i = 1; i <= count; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                    }
                    return new SparkProbe(columns, rows, System.currentTimeMillis() - start);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // 连不上时给「未就绪 + 地址 + 原因」，由 blocking() 归为 warning，
            // 避免 Spark 集群抖动把整个发布闸门卡死。
            if (SparkThriftSettings.unavailable(e)) {
                throw new ApiException(503,
                        SparkThriftSettings.unavailableHint(settings.url(), e));
            }
            throw ApiException.badRequest("SPARK 校验失败：" + e.getMessage());
        }
    }

    /**
     * 是否做语法探测：只跳过**已知的**写/DDL/会话语句；不认识的开头（例如拼错的
     * `selec`）也要探测——否则语法错会被当成「写语句」跳过，发布闸门形同虚设。
     */
    private boolean isReadQuery(String sql) {
        String lower = sql.stripLeading().toLowerCase(Locale.ROOT);
        for (String write : WRITE_STARTS) {
            if (lower.startsWith(write)) {
                return false;
            }
        }
        return true;
    }

    private static final List<String> WRITE_STARTS = List.of(
            "insert", "update", "delete", "create", "drop", "alter", "truncate", "merge",
            "replace", "set ", "use ", "load", "export", "install", "uninstall", "grant",
            "revoke", "analyze", "refresh", "kill", "call ", "begin", "commit", "rollback");

    /**
     * 语法探测：外包一层 limit 0，让引擎完整解析但不产生结果。
     * 去掉结尾分号，避免拼出 `(...; )` 的非法 SQL。
     */
    private String probe(String sql) {
        String body = sql.strip();
        while (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).stripTrailing();
        }
        return "select * from (" + body + ") _dw_validate limit 0";
    }

    /**
     * 只有「语法/解析错误」才阻断发布。表不存在、连接失败这类要区分：
     * 前一个任务可能刚建表、Doris 也可能暂时连不上，拿它们卡发布会造成误伤。
     */
    private boolean blocking(String message) {
        if (message == null) {
            return true;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("mismatched input") || lower.contains("error 1064")
                || lower.contains("syntax error") || lower.contains("cannot be parsed")
                || lower.contains("parse failed") || lower.contains("expecting")
                || lower.contains("语法")) {
            return true;
        }
        if (lower.contains("does not exist") || lower.contains("doesn't exist")
                || lower.contains("unknown database") || lower.contains("unknown column")
                || lower.contains("unknown function") || lower.contains("not supported")
                || lower.contains("communications link failure") || lower.contains("connection refused")
                || lower.contains("connect timed out") || lower.contains("current database is not set")
                || lower.contains("查询服务暂时不可用")
                // Spark Thrift / Flink Gateway 暂时连不上：属于基础设施抖动，不阻断发布
                || lower.contains("could not open client transport")
                || lower.contains("spark thrift 未就绪")
                || lower.contains("unable to connect") || lower.contains("connection reset")
                || lower.contains("no route to host") || lower.contains("i/o error on post request")
                || lower.contains("connection timed out")) {
            return false;
        }
        return true;
    }

    private String readable(String message) {
        if (message == null || message.isBlank()) {
            return "执行失败（引擎未返回原因）";
        }
        String value = message.trim();
        return value.length() > 600 ? value.substring(0, 600) + "…" : value;
    }

    private record Position(Integer line, Integer column) {
    }

    private Position position(String message) {
        if (message == null) {
            return new Position(null, null);
        }
        Matcher linePos = LINE_POS.matcher(message);
        if (linePos.find()) {
            return new Position(Integer.valueOf(linePos.group(1)),
                    Integer.valueOf(linePos.group(2)));
        }
        Matcher atLine = AT_LINE.matcher(message);
        if (atLine.find()) {
            return new Position(Integer.valueOf(atLine.group(1)), null);
        }
        Matcher cn = POSITION_CN.matcher(message);
        if (cn.find()) {
            Integer line = Integer.valueOf(cn.group(1));
            Integer column = cn.group(2) == null ? null : Integer.valueOf(cn.group(2));
            return new Position(line, column);
        }
        return new Position(null, null);
    }
}
