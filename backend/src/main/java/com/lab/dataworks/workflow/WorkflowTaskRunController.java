package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.flink.FlinkSqlService;
import com.lab.dataworks.query.DorisQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单节点试跑：只取当前工作流里的一个 SQL 节点，按节点的 {@code engine} 分派执行。
 *
 * <p>与整条工作流触发相比，试跑不创建 Prefect run、不写实例表、不触发下游，
 * 目的是在编排阶段快速验证 SQL 与 ${biz_date} 替换是否正确。</p>
 *
 * <ul>
 *   <li>{@code DORIS}（默认）：跟随工作流所属项目的 Doris；绑定独立数据源时直连该数据源。</li>
 *   <li>{@code SPARK}：走 Spark Thrift Server（HiveServer2），同步返回 columns/rows；
 *       绑定数据源时必须是 SPARK 类型的数据源。</li>
 *   <li>{@code FLINK}：走 Flink SQL Gateway。INSERT 作业提交并 DEV 跑通后自动取消、返回 jobId/状态；
 *       纯 SELECT 结果无法同步取回，改用 EXPLAIN 校验并明确提示「异步提交」。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workflows/{id}/tasks/{code}")
public class WorkflowTaskRunController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 试跑时允许直连的数据源类型；SPARK 走 Spark Thrift（HiveServer2）。 */
    private static final List<String> DIRECT_TYPES =
            List.of("POSTGRESQL", "DORIS", "MYSQL", "CLICKHOUSE", "SPARK");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DorisQueryService doris;
    private final com.lab.dataworks.udf.UdfService udf;
    private final com.lab.dataworks.secret.SecretCipher cipher;
    private final FlinkSqlService flink;
    /**
     * BUG-TC4-2：试跑落点闸（开发态只能落开发库）。字段注入：这个控制器的构造里
     * 还有一串 @Value 参数，加位置参数会牵动不在本卡文件域的调用方。
     * CONFLICT-WM1 裁决：该闸只在 env=DEV 时生效，与 D38 的请求口径环境闸并存一套；
     * 取不到 Bean 时少一道 _prd 兜底闸（本项目生产库仍有 D38 的 prodReference 挡）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lab.dataworks.project.ProjectEnvironmentService environment;
    /** spark.thrift.* 原始配置；请求时再归一化/校验，配错地址不会拖垮应用启动。 */
    private final String sparkUrl;
    private final String sparkUsername;
    private final String sparkPassword;
    private final int sparkTimeoutSeconds;

    public WorkflowTaskRunController(JdbcTemplate jdbc, ObjectMapper mapper, DorisQueryService doris,
                                     com.lab.dataworks.udf.UdfService udf,
                                     com.lab.dataworks.secret.SecretCipher cipher,
                                     FlinkSqlService flink,
                                     @Value("${spark.thrift.url:" + SparkThriftSettings.DEFAULT_URL + "}")
                                     String sparkUrl,
                                     @Value("${spark.thrift.username:" + SparkThriftSettings.DEFAULT_USERNAME + "}")
                                     String sparkUsername,
                                     @Value("${spark.thrift.password:}") String sparkPassword,
                                     @Value("${spark.thrift.query-timeout-seconds:"
                                             + SparkThriftSettings.DEFAULT_TIMEOUT_SECONDS + "}")
                                     int sparkTimeoutSeconds) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.doris = doris;
        this.udf = udf;
        this.cipher = cipher;
        this.flink = flink;
        this.sparkUrl = sparkUrl;
        this.sparkUsername = sparkUsername;
        this.sparkPassword = sparkPassword;
        this.sparkTimeoutSeconds = sparkTimeoutSeconds;
    }

    /**
     * sql 为空时用节点已保存的脚本；bizDate 为空时用今天。
     *
     * <p>D38（2026-10-26）：新增 {@code env}（默认 DEV）与 {@code confirmProd}。
     * 原先试跑没有环境概念，Doris/Spark 直接落到项目数仓（＝生产库）、Flink 的 INSERT
     * 还会真提交作业写数据，等于「编辑期的试跑」跑在生产上。现在默认只允许 DEV。</p>
     */
    public record RunRequest(String sql, Integer maxRows, String bizDate, String env,
                             Boolean confirmProd) {
    }

    /** 试跑环境。只有这两档，别的写法直接 400（不静默按某一档跑）。 */
    enum Env {
        DEV, PROD
    }

    /** PROD 试跑需要的权限点（默认没人有；不在 60 个权限点清单里，需主控加迁移才能授予）。 */
    static final String PROD_ATOM = "ops:run:prod";

    /**
     * D38：env 判定（纯函数，便于单测）。
     *
     * <ul>
     *   <li>不传/空串＝DEV；写错（dev/prod 之外）＝400，不静默降级；</li>
     *   <li>PROD 需要权限点 {@code ops:run:prod}，缺权限 403；</li>
     *   <li>PROD 还要显式二次确认 {@code confirmProd=true}，否则 400。</li>
     * </ul>
     */
    static Env resolveEnv(String rawEnv, boolean confirmProd, boolean hasProdAtom) {
        String value = rawEnv == null || rawEnv.isBlank() ? "DEV" : rawEnv.trim().toUpperCase(Locale.ROOT);
        if (!"DEV".equals(value) && !"PROD".equals(value)) {
            throw ApiException.badRequest("env 只能是 DEV 或 PROD，当前值: " + rawEnv
                    + "（默认 DEV，生产试跑需 " + PROD_ATOM + " 权限）");
        }
        if ("DEV".equals(value)) {
            return Env.DEV;
        }
        if (!hasProdAtom) {
            throw new ApiException(403, "生产环境试跑需要权限点 " + PROD_ATOM
                    + "（默认不授予）；请改用 DEV 试跑");
        }
        if (!confirmProd) {
            throw ApiException.badRequest("生产环境试跑会真实读写生产库，需要显式二次确认：confirmProd=true");
        }
        return Env.PROD;
    }

    /**
     * D38：按环境解析这次试跑使用的库。
     *
     * <p>DEV 且项目<b>配了生产库</b>却没配开发库 → 400，绝不静默落到生产库——这是本卡要堵的口子。
     * 项目<b>两档都没配</b>时（2026-10-26 现网 demo/permproj/xproj920193 三个项目全部如此）
     * 谈不上「回落」：仍按既有的项目数仓路由执行，响应里 {@code database=null}，界面显示
     * 「未使用项目库」。<b>一旦给项目配上 {@code prod_database}，闸门自动生效</b>，
     * 所以「严格挡住静默上生产」与「存量项目还能试跑」不互斥。</b>
     * 若主控要求「未配开发库一律不许跑」，把下面 {@code return null;} 换成抛 ApiException 即可。</p>
     */
    static String databaseForEnv(String devDatabase, String prodDatabase, Env env) {
        String dev = blankToNull(devDatabase);
        String prod = blankToNull(prodDatabase);
        if (env == Env.PROD) {
            return prod;
        }
        if (dev != null) {
            return dev;
        }
        if (prod != null) {
            throw ApiException.badRequest("该项目配置了生产库 " + prod + " 却没有配置开发库"
                    + "（dev_database），试跑默认只在 DEV 执行、不回落生产库；请在「项目管理」"
                    + "补开发库后重试，或确需跑生产时传 env=PROD（需 " + PROD_ATOM + " 权限 + confirmProd=true）");
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 去掉注释与字符串字面量，避免把注释/常量里的词当成表引用（hasInsert 与生产库引用判定共用）。 */
    static String maskSql(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--[^\\n]*", " ")
                .replaceAll("'(?:[^'\\\\]|\\\\.)*'", "''");
    }

    /**
     * D38：SQL 里是否显式引用了本项目的<b>生产库</b>（{@code 库.表} 写法，忽略大小写与反引号）。
     * 命中时返回库名片段用于提示，不回显用户 SQL。
     */
    static String prodReference(String sql, String prodDatabase) {
        if (sql == null || sql.isBlank() || prodDatabase == null || prodDatabase.isBlank()) {
            return null;
        }
        // 反引号/双引号先去掉：`` `demo_prod`.t `` 这种写法很常见，带着引号正则匹配不到库名
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)([a-z_][\\w$]*)\\s*\\.\\s*[a-z_`\"\\[]")
                .matcher(maskSql(sql).replace("`", " ").replace("\"", " ").replace("[", " "));
        while (matcher.find()) {
            String db = matcher.group(1).replace("`", "").replace("\"", "");
            if (db.equalsIgnoreCase(prodDatabase.trim())) {
                return db;
            }
        }
        return null;
    }

    @PostMapping("/run")
    @RequireAtom("ops:run")
    public Map<String, Object> run(@PathVariable long id, @PathVariable String code,
                                   @RequestBody(required = false) RunRequest req,
                                   jakarta.servlet.http.HttpServletRequest request) {
        TaskRef task = findTask(id, code);
        String type = task.task().path("type").asText("SQL");
        if (!"SQL".equalsIgnoreCase(type)) {
            throw ApiException.badRequest("只有 SQL 节点支持试跑，当前节点类型是 " + type);
        }
        // 老模型没有 engine 字段 → DORIS；非法值 400（不静默跑错引擎）
        ScriptEngine engine = ScriptEngine.fromTask(task.task());

        String sql = req != null && req.sql() != null && !req.sql().isBlank()
                ? req.sql() : task.task().path("script").asText("");
        if (sql.isBlank()) {
            throw ApiException.badRequest("节点 SQL 不能为空");
        }
        String bizDate = req == null || req.bizDate() == null || req.bizDate().isBlank()
                ? LocalDate.now(ZONE).toString() : req.bizDate();
        try {
            LocalDate.parse(bizDate);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日期格式应为 yyyy-MM-dd: " + bizDate);
        }
        int maxRows = req == null || req.maxRows() == null
                ? 100 : Math.max(1, Math.min(1000, req.maxRows()));

        String executedSql = sql.replace("${biz_date}", bizDate);
        // 引擎与语法对不上时先给可行动提示（例如 Doris 节点里贴了 Flink 建表语法），
        // 不要等引擎返回 1064 让用户猜。引擎创建后只读，提示里直接说「新建脚本」。
        String conflict = ScriptEngineRules.crossEngineSyntax(engine, executedSql);
        if (conflict != null) {
            throw ApiException.badRequest(conflict);
        }
        // UDF：未发布 / 别的项目的函数明确拒绝（v447）
        udf.checkUsage(task.projectId(), executedSql);

        // D38：先定环境，再动数据。PROD 需要权限点 + 二次确认，缺任何一条都不会误跑生产
        Env env = resolveEnv(req == null ? null : req.env(),
                req != null && Boolean.TRUE.equals(req.confirmProd()),
                com.lab.dataworks.auth.AtomCheck.has(request, PROD_ATOM));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskCode", code);
        out.put("taskName", task.task().path("name").asText(code));
        out.put("engine", engine.name());
        out.put("env", env.name());
        out.put("bizDate", bizDate);
        out.put("executedSql", executedSql);

        switch (engine) {
            case SPARK -> runSpark(task, executedSql, maxRows, out, env);
            case FLINK -> runFlink(task, executedSql, maxRows, out, env);
            default -> runDoris(task, executedSql, maxRows, out, env);
        }
        return out;
    }

    /**
     * D38：按环境解析这次试跑实际用的库，并挡住「DEV 里引用本项目生产库」。
     *
     * <p>解析结果写进响应的 {@code database} 字段，界面与验收都能核对连的是哪个库；
     * 绑定独立数据源的节点不走这里（数据源本身就是目标，语义与既有行为一致）。</p>
     */
    private String applyEnvDatabase(TaskRef task, Env env, String sql, Map<String, Object> out) {
        if (task.projectId() <= 0) {
            // TaskRef.projectId 是原始 long（0＝没有项目），DEV 没有项目就无从解析开发库
            if (env == Env.DEV) {
                throw ApiException.badRequest("工作流未归属项目，无法确定开发库；"
                        + "请先把工作流挂到项目下再试跑（DEV 需要项目的 dev_database）");
            }
            out.put("database", null);
            return null;
        }
        Map<String, Object> dbs = projectDatabases(task.projectId());
        String database = databaseForEnv(str(dbs.get("devDatabase")), str(dbs.get("prodDatabase")), env);
        if (env == Env.DEV) {
            String prod = str(dbs.get("prodDatabase"));
            String hit = prodReference(sql, prod);
            if (hit != null) {
                throw ApiException.badRequest("试跑（DEV）不能引用本项目的生产库 " + hit
                        + "（SQL 里出现了 " + hit + ".<表>）；请改成 " + database
                        + ".<表>，或确需跑生产时传 env=PROD（需 " + PROD_ATOM + " 权限 + confirmProd=true）");
            }
        }
        out.put("database", database);
        if (env == Env.DEV && database == null) {
            // BUG-TC4-2：项目两档库都没配时落点无法解析（不静默——回显提示指向 D57 回填），
            // 裸表名没有默认库，_prd 兜底闸只能按命名约定判
            out.put("landingHint", "本项目未配置 dev_database：SQL 里的裸表名没有默认库，"
                    + "且生产库闸门只能按命名约定兜底。请在「管理中心 · 项目设置」补开发库，"
                    + "或用一次性回填接口 POST /api/projects/backfill-databases");
        }
        return database;
    }

    /** 项目的开发/生产库名（D38 按 env 解析用）；项目不存在或不属于当前租户 → 404。 */
    private Map<String, Object> projectDatabases(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select dev_database as "devDatabase", prod_database as "prodDatabase"
                  from platform.project
                 where id = ? and tenant_id = ?
                """, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
        return rows.get(0);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ------------------------------------------------------------------ DORIS

    /**
     * 项目 Doris（默认引擎）。绑定独立数据源时直连该连接，语义与旧版一致。
     *
     * <p>D38：未绑定数据源时按 {@code env} 解析项目的库（DEV＝dev_database，缺库 400 不回落），
     * 并把实际库名回显在响应的 {@code database} 里；绑定独立数据源的节点仍按数据源自身口径执行。</p>
     *
     * <p>CONFLICT-WM1：{@code env=DEV} 时两种路径都先过 {@code requireDevLanding}——
     * 挡本项目生产库、本租户其他项目的生产库、按命名约定（{@code *_prd}）的生产库；
     * {@code env=PROD}（权限点 {@code ops:run:prod} + confirmProd）才放行，按请求口径执行。
     * 响应里的 {@code env} 回显请求解析结果（run() 统一写），不再硬编码 DEV。</p>
     */
    private void runDoris(TaskRef task, String sql, int maxRows, Map<String, Object> out, Env env) {
        // CONFLICT-WM1 裁决（2026-10-27）：两套落点闸收敛成一个口径——
        // D38 的 prodReference 只比对本项目 prod_database，本租户别的项目的生产库、
        // 无人登记的 *_prd 库仍会 200 放行（BUG-TC4-2 的复现原文正是这种库）。
        // env=DEV 时先过 requireDevLanding，且位置在 datasourceId 分支**之前**：
        // 绑定数据源只豁免「按项目解析库」，不豁免「开发态不许碰生产库」——
        // 绑一块同样的内置 Doris 就能绕开闸门，豁免等于留口子。
        // 确需跑生产：显式 env=PROD（需 ops:run:prod 权限 + confirmProd=true），此时不再按开发态拦。
        if (env == Env.DEV && environment != null) {
            environment.requireDevLanding(task.projectId(),
                    com.lab.dataworks.project.ProjectEnvironmentService.referencedDatabases(sql),
                    "试跑");
        }
        if (task.task().hasNonNull("datasourceId")) {
            Map<String, Object> ds = loadDatasource(task.projectId(),
                    task.task().path("datasourceId").asLong());
            // 绑定关系可以绕过界面直接写进模型：这里按同一口径再拦一次
            String binding = ScriptEngineRules.datasourceBindingError(ScriptEngine.DORIS,
                    String.valueOf(ds.get("ds_type")), queryable(ds),
                    ds.get("jdbc_url") == null ? "" : String.valueOf(ds.get("jdbc_url")));
            if (binding != null) {
                throw ApiException.badRequest(binding);
            }
            DirectResult direct = runDirect(ds, sql, maxRows, "DORIS");
            fillResult(out, direct, maxRows, "数据源 " + ds.get("name"));
            // 绑定数据源时库由数据源自己的连接决定，这里不假装是项目库（响应里 database=null）
            out.put("database", null);
            return;
        }
        // D38：先按环境定库（DEV 缺开发库就 400），再执行；试跑跟随工作流所属项目
        applyEnvDatabase(task, env, sql, out);
        DorisQueryService.Result result = doris.query(task.projectId(), sql, maxRows);
        out.put("columns", result.columns());
        out.put("rows", result.rows());
        out.put("rowCount", result.rowCount());
        out.put("maxRows", result.maxRows());
        out.put("elapsedMs", result.elapsedMs());
        out.put("target", result.target());
    }

    // ------------------------------------------------------------------ SPARK

    /**
     * Spark Thrift（HiveServer2）：只读 SQL，同步返回结果；超时与 maxRows 都受控。
     *
     * <p>D38：未绑定数据源时与 Doris 同一口径按 env 解析项目库（DEV 缺开发库 400、
     * DEV 里引用本项目生产库 400），响应回显实际库名。</p>
     */
    private void runSpark(TaskRef task, String sql, int maxRows, Map<String, Object> out, Env env) {
        if (task.task().hasNonNull("datasourceId")) {
            Map<String, Object> ds = loadDatasource(task.projectId(),
                    task.task().path("datasourceId").asLong());
            String binding = ScriptEngineRules.datasourceBindingError(ScriptEngine.SPARK,
                    String.valueOf(ds.get("ds_type")), queryable(ds),
                    ds.get("jdbc_url") == null ? "" : String.valueOf(ds.get("jdbc_url")));
            if (binding != null) {
                throw ApiException.badRequest(binding);
            }
            DirectResult direct = runDirect(ds, sql, maxRows, "SPARK");
            fillResult(out, direct, maxRows, "数据源 " + ds.get("name"));
            out.put("database", null);   // 同上：绑定数据源时不假装是项目库
            return;
        }
        // D38：Spark 也按环境定库（DEV 缺开发库 400，不回落生产库）
        applyEnvDatabase(task, env, sql, out);
        SparkThriftSettings settings = SparkThriftSettings.of(sparkUrl, sparkUsername,
                sparkPassword, sparkTimeoutSeconds);
        DirectResult direct;
        try {
            direct = runJdbc(settings.url(), settings.username(), settings.password(),
                    sql, maxRows, settings.queryTimeoutSeconds(), "SPARK");
        } catch (RuntimeException e) {
            // 连不上是基础设施未就绪（503 + 地址 + 原因），不是用户 SQL 错；
            // 只读守卫 / 语法错误等业务错误原样抛出。
            throw sparkFailure(settings.url(), e);
        }
        fillResult(out, direct, maxRows, "Spark Thrift " + settings.url());
    }

    /** SPARK 试跑失败分流：连接类错误 → 503 + 可行动提示；业务错误原样返回。 */
    static RuntimeException sparkFailure(String url, RuntimeException error) {
        if (SparkThriftSettings.unavailable(error)) {
            return new ApiException(503, SparkThriftSettings.unavailableHint(url, error));
        }
        return error;
    }

    // ------------------------------------------------------------------ FLINK

    /**
     * Flink SQL Gateway：流式作业是异步提交，没有同步结果集。
     *
     * <p>D38（2026-10-26）：INSERT 类脚本**不再在试跑里提交作业**。原先这里调
     * {@code submitAndObserve}（原名 smokeTest）（提交 → 等稳定 → 取消），等于在真实集群上起一个会写目标表的作业，
     * 与「试跑只是验证 SQL」的预期相反，也绕过了 DEV→发布→PROD 的闸门。现在直接 400，
     * 让用户走「流开发」的正常提交路径；纯 SELECT 仍用 {@code validate}（EXPLAIN）同步校验。</p>
     */
    private void runFlink(TaskRef task, String sql, int maxRows, Map<String, Object> out, Env env) {
        if (task.task().hasNonNull("datasourceId")) {
            throw ApiException.badRequest(
                    "FLINK 引擎不支持绑定数据源，请解除节点绑定（Flink 走 SQL Gateway 连接）");
        }
        long start = System.currentTimeMillis();
        out.put("columns", List.of());
        out.put("rows", List.of());
        out.put("rowCount", 0);
        out.put("maxRows", maxRows);
        out.put("target", "Flink SQL Gateway");
        // Flink 的目标表由 SQL 里的 catalog/database 决定，不跟随项目数仓库名，回显 null 以免误读
        out.put("database", null);
        if (hasInsert(sql)) {
            throw ApiException.badRequest("Flink 的 INSERT 类脚本不能在试跑里提交："
                    + "那会在真实集群起作业并写入目标表。请到「流开发」保存后提交到 DEV 运行，"
                    + "验证过再走发布流程");
        }
        Map<String, Object> checked = flink.validate(sql);
        out.put("flinkValidate", checked);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        out.put("message", "Flink 试跑为异步提交：纯 SELECT 的查询结果不会同步返回，"
                + "已通过 EXPLAIN 校验（" + checked.getOrDefault("statements", 0) + " 条语句）；"
                + "INSERT 类脚本不允许在试跑里提交，请到「流开发」提交到 DEV 运行");
    }

    /** INSERT INTO / INSERT OVERWRITE（忽略注释与字符串里的同名词）。 */
    static boolean hasInsert(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile("(?i)\\binsert\\s+(?:into|overwrite)\\b")
                .matcher(maskSql(sql)).find();
    }

    // --------------------------------------------------------------- JDBC 执行

    private record DirectResult(List<String> columns, List<List<String>> rows,
                                int rowCount, long elapsedMs) {
    }

    private void fillResult(Map<String, Object> out, DirectResult direct, int maxRows,
                            String target) {
        out.put("columns", direct.columns());
        out.put("rows", direct.rows());
        out.put("rowCount", direct.rowCount());
        out.put("maxRows", maxRows);
        out.put("elapsedMs", direct.elapsedMs());
        out.put("target", target);
    }

    /** 绑定独立数据源时必须属于同一项目（项目归属 = 租户 + 项目）。 */
    private Map<String, Object> loadDatasource(long projectId, long datasourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.name, d.ds_type, d.jdbc_url, d.username, d.password,
                       d.queryable
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where d.id = ? and d.project_id = ? and p.tenant_id = ?
                """, datasourceId, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + datasourceId);
        }
        return rows.get(0);
    }

    /** V99 的 queryable：旧数据没有列值时按「可查」处理，与数据源接口的兜底一致。 */
    private static boolean queryable(Map<String, Object> ds) {
        Object raw = ds.get("queryable");
        if (raw instanceof Boolean value) {
            return value;
        }
        return raw == null || !"false".equalsIgnoreCase(String.valueOf(raw));
    }

    /** 直接用数据源连接执行只读 SQL（试跑最多 maxRows 行）。 */
    private DirectResult runDirect(Map<String, Object> ds, String sql, int maxRows,
                                   String engine) {
        String type = String.valueOf(ds.get("ds_type")).toUpperCase(Locale.ROOT);
        if (!DIRECT_TYPES.contains(type)) {
            throw ApiException.badRequest(engine + " 试跑不支持该类型的数据源: " + type);
        }
        String password = ds.get("password") == null
                ? "" : cipher.decrypt(String.valueOf(ds.get("password")));
        return runJdbc(String.valueOf(ds.get("jdbc_url")),
                ds.get("username") == null ? "" : String.valueOf(ds.get("username")),
                password, sql, maxRows, 60, engine);
    }

    /** 统一 JDBC 只读执行：URL 守卫 + 只读拦截 + 驱动解析 + 行数/超时限制。 */
    private DirectResult runJdbc(String jdbcUrl, String username, String password,
                                 String sql, int maxRows, int timeoutSeconds, String engine) {
        com.lab.dataworks.integration.SqlTemplates.guardReadOnly(sql, engine + " 试跑 SQL");
        String url = com.lab.dataworks.common.JdbcUrlGuard.validate(jdbcUrl);
        String driver = com.lab.dataworks.common.JdbcUrlGuard.driverFor(url);
        long start = System.currentTimeMillis();
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", username == null ? "" : username);
        props.setProperty("password", password == null ? "" : password);
        try {
            if (driver != null) {
                Class.forName(driver);
            }
            java.sql.DriverManager.setLoginTimeout(10);
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, props);
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(Math.max(1, timeoutSeconds));
                stmt.setMaxRows(maxRows);
                try (java.sql.ResultSet rs = stmt.executeQuery(sql)) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int count = meta.getColumnCount();
                    List<String> columns = new java.util.ArrayList<>(count);
                    for (int i = 1; i <= count; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    List<List<String>> rows = new java.util.ArrayList<>();
                    while (rs.next()) {
                        List<String> cells = new java.util.ArrayList<>(count);
                        for (int i = 1; i <= count; i++) {
                            String value = rs.getString(i);
                            cells.add(rs.wasNull() ? null : value);
                        }
                        rows.add(cells);
                    }
                    return new DirectResult(columns, rows, rows.size(),
                            System.currentTimeMillis() - start);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // 保留 cause：SPARK 的「未就绪」识别要沿异常链找真实的连接原因
            ApiException failure = ApiException.badRequest(engine + " 试跑失败：" + e.getMessage());
            failure.initCause(e);
            throw failure;
        }
    }

    private record TaskRef(long projectId, JsonNode task) {
    }

    private TaskRef findTask(long workflowId, String code) {
        // v447：先按租户校验工作流归属，否则 tenant_b 能拿别人的节点 SQL 直接跑
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select w.model_json::text as model, w.project_id
                  from platform.workflow w
                  join platform.project p on p.id = w.project_id
                 where w.id = ? and p.tenant_id = ?
                """,
                workflowId, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("工作流不存在: " + workflowId);
        }
        JsonNode root;
        try {
            root = mapper.readTree(String.valueOf(rows.get(0).get("model")));
        } catch (Exception e) {
            throw ApiException.badRequest("工作流模型解析失败: " + e.getMessage());
        }
        Object rawProject = rows.get(0).get("project_id");
        long projectId = rawProject == null ? 0L : ((Number) rawProject).longValue();
        for (JsonNode task : root.path("tasks")) {
            if (code.equals(task.path("code").asText())) {
                return new TaskRef(projectId == 0L ? null : projectId, task);
            }
        }
        throw ApiException.notFound("节点不存在: " + code);
    }
}
