package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisTargetService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QualityService {

    private static final Logger log = LoggerFactory.getLogger(QualityService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RestTemplate http;
    /** 建规则时的「这张表到底在不在数仓里」探针专用：宁可快速失败，也不能把创建请求挂 60 秒。 */
    private final RestTemplate probeHttp;
    private final String mcpBaseUrl;
    private final String mcpToken;
    private final SecretCipher cipher;
    private final DorisTargetService dorisTargets;
    private final WarehouseResolver warehouses;
    /** BUG-TC4-4：环境落点（与 D57 / TC-2 / TC-3 同一处口径，不在质量里再写一份）。 */
    private final com.lab.dataworks.project.ProjectEnvironmentService environments;

    public QualityService(JdbcTemplate jdbc, ObjectMapper mapper, RestTemplateBuilder builder,
                          @Value("${mcp.base-url}") String mcpBaseUrl,
                          @Value("${mcp.token:}") String mcpToken,
                          SecretCipher cipher, DorisTargetService dorisTargets,
                          WarehouseResolver warehouses,
                          com.lab.dataworks.project.ProjectEnvironmentService environments) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
        this.dorisTargets = dorisTargets;
        this.warehouses = warehouses;
        this.environments = environments;
        this.http = builder.setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(60)).build();
        this.probeHttp = builder.setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(8)).build();
        this.mcpBaseUrl = mcpBaseUrl.endsWith("/")
                ? mcpBaseUrl.substring(0, mcpBaseUrl.length() - 1) : mcpBaseUrl;
        this.mcpToken = mcpToken;
    }

    public Map<String, Object> evaluate(long ruleId, LocalDate bizDate) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, rule_type, table_name, column_name,
                       config_json::text as config, datasource, datasource_id, project_id
                from platform.quality_rule where id = ?
                """, ruleId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("质量规则不存在: " + ruleId);
        }
        // 交互式运行（页面上的「执行」按钮）= 开发态：不能跑生产库（BUG-TC4-4）
        return evaluate(rows.get(0), bizDate, false);
    }

    public Map<String, Object> evaluateByCode(String ruleCode, LocalDate bizDate) {
        return evaluateByCode(ruleCode, bizDate, null);
    }

    /**
     * 按编码执行规则。
     *
     * @param tenant 非空时只在当前租户的项目里找规则（登录会话调用闸门必须传，
     *               否则知道别人的规则编码就能跨租户执行并读到结果明细）；
     *               内部 token（CI/Flow）传 null，保持「编码全局唯一」的旧语义。
     */
    public Map<String, Object> evaluateByCode(String ruleCode, LocalDate bizDate, String tenant) {
        String scoped = tenant == null ? "" : " and project_id in"
                + " (select id from platform.project where tenant_id = ?)";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, rule_type, table_name, column_name,
                       config_json::text as config, datasource, datasource_id, project_id
                from platform.quality_rule where code = ? and enabled = true
                """ + scoped + " order by id",
                tenant == null ? new Object[]{ruleCode} : new Object[]{ruleCode, tenant});
        if (rows.isEmpty()) {
            throw ApiException.notFound("质量规则不存在或未启用: " + ruleCode);
        }
        // 编码只在项目内唯一：跨项目重名时不能猜，否则会拿别的项目的规则/数据源
        if (rows.size() > 1) {
            throw ApiException.badRequest("规则编码 " + ruleCode
                    + " 在多个项目中重复，请改成唯一编码后再用于质量闸门");
        }
        // tenant == null 表示内部 token 调用（发布线 / Prefect Flow）：保留跑生产库的能力
        return evaluate(rows.get(0), bizDate, tenant == null);
    }

    private Map<String, Object> evaluate(Map<String, Object> rule, LocalDate bizDate,
                                         boolean releaseLine) {
        long id = ((Number) rule.get("id")).longValue();
        String code = String.valueOf(rule.get("code"));
        String type = String.valueOf(rule.get("rule_type")).toUpperCase();
        String table = String.valueOf(rule.get("table_name"));
        String column = rule.get("column_name") == null ? null : String.valueOf(rule.get("column_name"));
        String datasource = rule.get("datasource") == null
                ? "PG" : String.valueOf(rule.get("datasource")).toUpperCase();
        Long datasourceId = rule.get("datasource_id") == null
                ? null : ((Number) rule.get("datasource_id")).longValue();
        Long projectId = rule.get("project_id") == null
                ? null : ((Number) rule.get("project_id")).longValue();
        Datasource ds = datasourceId == null ? null : findDatasource(datasourceId);
        JsonNode config;
        try {
            config = mapper.readTree(String.valueOf(rule.get("config")));
        } catch (Exception e) {
            throw ApiException.badRequest("规则配置不是合法 JSON: " + e.getMessage());
        }
        if (!table.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw ApiException.badRequest("非法表名: " + table);
        }
        if (column != null && !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw ApiException.badRequest("非法字段名: " + column);
        }
        if (column == null && java.util.Set.of(
                "NULL_RATE", "UNIQUE", "RANGE", "REGEX", "ENUM", "DISCRETE").contains(type)) {
            throw ApiException.badRequest("规则 " + code + " 缺少字段（" + type + " 必须指定 column）");
        }
        // D56：改口径之前建的存量规则里，「表其实在 Doris、数据源写成 PG」的行很多。
        // 执行前先把话说清楚（哪个库、该配什么），不要等 PG 抛原文再当成执行结果。
        requireReachableTarget(code, table, datasource, datasourceId);
        // BUG-TC4-4：质量规则以前不看环境——tableName 写哪个库就跑哪个库（生产库照跑），
        // 响应里也没有 env / database，用户与验收都无从核对「这条规则落在哪」。
        String landingDatabase = databaseOf(table);
        String landingEnv = environments == null || landingDatabase == null || projectId == null
                ? null : environments.landingEnv(projectId, landingDatabase);
        if (!releaseLine && environments != null && landingDatabase != null) {
            environments.requireDevLanding(projectId, java.util.Set.of(landingDatabase),
                    "质量规则运行");
        }

        boolean passed;
        String detail;
        // OK = 规则执行成功（passed 才是数据结论）；ERROR = 规则没执行成功，passed 不可信。
        String execStatus = "OK";
        try {
            switch (type) {
                case "ROW_COUNT" -> {
                    double value = scalar("select count(*) from " + table, datasource, ds, projectId);
                    Long min = config.hasNonNull("min") ? config.get("min").asLong() : null;
                    Long max = config.hasNonNull("max") ? config.get("max").asLong() : null;
                    passed = (min == null || value >= min) && (max == null || value <= max);
                    detail = "row_count=" + (long) value + ", min=" + min + ", max=" + max;
                }
                case "NULL_RATE" -> {
                    // 用两个 count 计算，避免 Doris/PG 的 filter 语法差异
                    double total = scalar("select count(*) from " + table, datasource, ds, projectId);
                    double nulls = scalar("select count(*) from " + table
                            + " where " + column + " is null", datasource, ds, projectId);
                    double value = total == 0 ? 0.0 : nulls / total;
                    double max = config.path("max").asDouble(0.0);
                    passed = value <= max;
                    detail = "null_rate=" + value + ", max=" + max;
                }
                case "UNIQUE" -> {
                    // count(col) 不计 NULL；用 count(*) 会把 NULL 当成重复
                    double dup = scalar("select count(" + column + ") - count(distinct " + column
                                    + ") from " + table, datasource, ds, projectId);
                    long max = config.path("max").asLong(0);
                    passed = dup <= max;
                    detail = "duplicates=" + (long) dup + ", max=" + max;
                }
                case "RANGE" -> {
                    // 没配置的边界就是不限制。以前 min 缺省按 0 处理，只想约束上限的规则
                    // 会把所有负数判成越界，结论不可信。
                    boolean hasMin = config.hasNonNull("min");
                    boolean hasMax = config.hasNonNull("max");
                    if (!hasMin && !hasMax) {
                        throw ApiException.badRequest("RANGE 规则至少需要配置 min 或 max 之一");
                    }
                    StringBuilder condition = new StringBuilder();
                    if (hasMin) {
                        condition.append(column).append(" < ").append(lit(config.get("min").asDouble()));
                    }
                    if (hasMax) {
                        if (!condition.isEmpty()) {
                            condition.append(" or ");
                        }
                        condition.append(column).append(" > ").append(lit(config.get("max").asDouble()));
                    }
                    double bad = scalar("select count(*) from " + table
                            + " where " + condition, datasource, ds, projectId);
                    passed = bad == 0;
                    detail = "out_of_range=" + (long) bad + ", min="
                            + (hasMin ? config.get("min").asText() : "不限") + ", max="
                            + (hasMax ? config.get("max").asText() : "不限");
                }
                case "FRESHNESS" -> {
                    String dateColumn = config.path("dateColumn").asText("");
                    if (dateColumn.isBlank()) {
                        dateColumn = column;
                    }
                    if (dateColumn == null || dateColumn.isBlank()
                            || !dateColumn.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw ApiException.badRequest("FRESHNESS 规则需要日期字段（columnName 或 config.dateColumn）");
                    }
                    double value = scalar("select count(*) from " + table
                            + " where " + dateColumn + " = '" + bizDate + "'", datasource, ds, projectId);
                    long min = config.path("min").asLong(1);
                    passed = value >= min;
                    detail = "fresh_rows=" + (long) value + ", min=" + min;
                }
                case "CUSTOM_SQL" -> {
                    String sql = config.path("sql").asText("");
                    if (sql.isBlank() && config.hasNonNull("sql_b64")) {
                        sql = new String(Base64.getDecoder().decode(config.get("sql_b64").asText()),
                                StandardCharsets.UTF_8);
                    }
                    if (sql.isBlank()) {
                        throw ApiException.badRequest("CUSTOM_SQL 规则缺少 sql/sql_b64");
                    }
                    sql = sql.replace("${biz_date}", bizDate.toString());
                    // 质量规则只允许单条只读查询：这里用的是平台 DB 账号/业务数据源
                    // 账号，不能让规则执行 DELETE/DDL 或多语句。
                    com.lab.dataworks.integration.SqlTemplates.guardReadOnly(sql);
                    double actual = scalar(sql, datasource, ds, projectId);
                    double expect = config.path("expect").asDouble(1);
                    passed = Math.abs(actual - expect) < 1e-9;
                    detail = "value=" + actual + ", expect=" + expect;
                }
                case "VOLATILITY" -> {
                    // TC7-G1：行数波动率。基线=本规则在 bizDate-N 天最近一次执行明细里的
                    // row_count（首跑没基线 ⇒ 记录缺失、不判定、通过——新表建规则不该立刻拦人）；
                    // 口径与取舍写 docs/质量规则新类型-波动率正则枚举离散值-20261027.md。
                    double current = scalar("select count(*) from " + table, datasource, ds, projectId);
                    List<Integer> windows = volatilityWindows(config);
                    double maxRate = config.path("maxChangeRate").asDouble(-1);
                    if (maxRate < 0) {
                        throw ApiException.badRequest("VOLATILITY 规则需要 maxChangeRate（如 0.3 表示 ±30%）");
                    }
                    List<String> parts = new ArrayList<>();
                    boolean compared = false;
                    boolean violated = false;
                    for (int w : windows) {
                        Double base = baselineRowCount(id, bizDate.minusDays(w));
                        if (base == null) {
                            parts.add(w + "d=基线缺失");
                            continue;
                        }
                        compared = true;
                        double rate = base == 0
                                ? (current == 0 ? 0.0 : Double.POSITIVE_INFINITY)
                                : (current - base) / base;
                        boolean ok = Math.abs(rate) <= maxRate;
                        violated = violated || !ok;
                        parts.add(w + "d=" + (Double.isInfinite(rate)
                                ? "0→" + (long) current + " 超限"
                                : String.format("%+.1f%%", rate * 100) + (ok ? " 达标" : " 超限")));
                    }
                    passed = !violated;
                    detail = "row_count=" + (long) current + ", " + String.join("; ", parts)
                            + ", maxChangeRate=" + maxRate
                            + (compared ? "" : "（各窗口均缺基线，首轮不判定）");
                }
                case "REGEX" -> {
                    // TC7-G1：格式校验。不匹配的行数 ≤ maxBad；NULL 不匹配也不计入（口径同 UNIQUE）。
                    String pattern = config.path("pattern").asText("");
                    compilePatternOrThrow(pattern);
                    long maxBad = config.path("maxBad").asLong(0);
                    double bad = scalar(regexBadSql(sqlDialect(datasource, ds), table, column, pattern),
                            datasource, ds, projectId);
                    passed = bad <= maxBad;
                    detail = "regex_bad=" + (long) bad + ", maxBad=" + maxBad
                            + ", pattern=" + (pattern.length() > 60 ? pattern.substring(0, 60) + "…" : pattern);
                }
                case "ENUM" -> {
                    // TC7-G1：枚举白名单。值不在白名单的行数 ≤ maxBad；NULL 不计入（契约 §五）。
                    List<String> allowed = enumAllowed(config);
                    long maxBad = config.path("maxBad").asLong(0);
                    double bad = scalar(enumBadSql(table, column, allowed), datasource, ds, projectId);
                    passed = bad <= maxBad;
                    detail = "enum_bad=" + (long) bad + ", maxBad=" + maxBad + ", allowed=" + allowed;
                }
                case "DISCRETE" -> {
                    // TC7-G1：离散值（分组个数）：count(distinct col) 落在 [min,max]；NULL 不参与 distinct。
                    boolean hasMin = config.hasNonNull("min");
                    boolean hasMax = config.hasNonNull("max");
                    if (!hasMin && !hasMax) {
                        throw ApiException.badRequest("DISCRETE 规则至少需要配置 min 或 max 之一");
                    }
                    double distinct = scalar("select count(distinct " + column + ") from " + table,
                            datasource, ds, projectId);
                    passed = (!hasMin || distinct >= config.get("min").asDouble())
                            && (!hasMax || distinct <= config.get("max").asDouble());
                    detail = "distinct_count=" + (long) distinct + ", min="
                            + (hasMin ? config.get("min").asText() : "不限") + ", max="
                            + (hasMax ? config.get("max").asText() : "不限");
                }
                default -> throw ApiException.badRequest("不支持的规则类型: " + type);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("quality rule {} evaluation failed", code, e);
            passed = false;
            execStatus = "ERROR";
            // D56：驱动原文里有 SQL、连接串甚至账号，既不能进响应也不能写进结果明细；
            // 完整堆栈留在日志里排查，用户看到的是「哪里不行 + 下一步做什么」。
            detail = explainFailure(e, targetLabel(datasource, ds, projectId), table);
        }
        jdbc.update("""
                insert into platform.quality_result (rule_id, biz_date, passed, detail, exec_status)
                values (?, ?::date, ?, ?, ?)
                """, id, bizDate.toString(), passed, detail, execStatus);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", id);
        out.put("ruleCode", code);
        out.put("bizDate", bizDate.toString());
        out.put("datasource", ds == null ? datasource : ds.type());
        out.put("env", landingEnv);                 // BUG-TC4-4：DEV / PROD / UNCONFIGURED / OTHER
        out.put("database", landingDatabase);        // 实际执行的库（table_name 的前缀）
        out.put("releaseLine", releaseLine);
        out.put("datasourceId", datasourceId);
        out.put("datasourceName", ds == null ? null : ds.name());
        out.put("passed", passed);
        out.put("status", execStatus);
        out.put("detail", detail);
        log.info("quality rule={} datasource={} datasourceId={} bizDate={} status={} passed={} detail={}",
                code, ds == null ? datasource : ds.name(), datasourceId, bizDate, execStatus, passed, detail);
        return out;
    }

    // ------------------------------------------- TC7-G1 新类型的口径助手（波动率/正则/枚举/离散值）

    /** 波动率窗口清单：config.windows 正整数数组（1–3660 天），空/非法一律 400，不静默兜底。 */
    static List<Integer> volatilityWindows(JsonNode config) {
        List<Integer> out = new ArrayList<>();
        JsonNode arr = config.path("windows");
        if (arr.isArray()) {
            for (JsonNode w : arr) {
                int days = w.asInt(0);
                if (days >= 1 && days <= 3660) {
                    out.add(days);
                }
            }
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("VOLATILITY 规则需要 windows（正整数天数组，如 [1,7,30]）");
        }
        return out;
    }

    /**
     * 波动率基线：本规则在 {@code day} 当天最近一次执行明细里的 row_count。
     * 读不到（没跑过 / 明细里没有 row_count）返回 null，由调用方按「基线缺失」处理；
     * 历史查询本身失败也只当缺失（不让平台库抖动把规则判成 ERROR 拦下游）。
     */
    Double baselineRowCount(long ruleId, LocalDate day) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select detail from platform.quality_result
                     where rule_id = ? and biz_date = ?::date
                     order by id desc limit 1
                    """, ruleId, day.toString());
            if (rows.isEmpty()) {
                return null;
            }
            Long count = parseRowCount(String.valueOf(rows.get(0).get("detail")));
            return count == null ? null : count.doubleValue();
        } catch (Exception e) {
            log.warn("quality baseline lookup failed rule={} day={}", ruleId, day, e);
            return null;
        }
    }

    /** ROW_COUNT 与 VOLATILITY 的明细都以 `row_count=<n>` 落笔，基线取数就按这个约定解析。 */
    private static final Pattern ROW_COUNT_IN_DETAIL = Pattern.compile("row_count=([0-9]+)");

    static Long parseRowCount(String detail) {
        if (detail == null) {
            return null;
        }
        Matcher m = ROW_COUNT_IN_DETAIL.matcher(detail);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    /** 正则表达式创建期先按 Java 方言编译一遍，拦住明显写坏的串；执行期以目标引擎方言为准。 */
    static void compilePatternOrThrow(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw ApiException.badRequest("REGEX 规则需要 pattern（正则表达式）");
        }
        try {
            Pattern.compile(pattern);
        } catch (Exception e) {
            throw ApiException.badRequest("正则表达式无法编译: " + e.getMessage());
        }
    }

    /** 枚举白名单：非空字符串数组，最多 500 项、单项 ≤256 字符（挡住把整张维表塞进规则）。 */
    static List<String> enumAllowed(JsonNode config) {
        JsonNode arr = config.path("allowed");
        if (!arr.isArray() || arr.isEmpty()) {
            throw ApiException.badRequest("ENUM 规则需要 allowed（枚举值数组，如 [\"DEV\",\"PROD\"]）");
        }
        if (arr.size() > 500) {
            throw ApiException.badRequest("ENUM 白名单最多 500 个值，请改用 REGEX 或自定义 SQL");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) {
            String value = v.asText(null);
            if (value == null || value.isEmpty() || value.length() > 256) {
                throw ApiException.badRequest("ENUM 白名单里的值必须是非空字符串（≤256 字符）");
            }
            out.add(value);
        }
        return out;
    }

    /** PG 用 !~，Doris/MySQL 系用 regexp；绑定的其他类型数据源按 regexp 方言处理。 */
    static String sqlDialect(String datasource, Datasource ds) {
        String raw = ds == null ? datasource : ds.type();
        String up = raw == null ? "PG" : raw.trim().toUpperCase(Locale.ROOT);
        if (up.equals("PG") || up.startsWith("POSTGRE")) {
            return "PG";
        }
        return "REGEXP";
    }

    /** 不匹配行计数 SQL。NULL 行不计（`is not null` 前置），与 UNIQUE/ENUM 口径一致。 */
    static String regexBadSql(String dialect, String table, String column, String pattern) {
        String notMatch = "PG".equals(dialect)
                ? column + " !~ '" + sqlQuote(pattern) + "'"
                : "not (" + column + " regexp '" + sqlQuote(pattern) + "')";
        return "select count(*) from " + table + " where " + column
                + " is not null and " + notMatch;
    }

    /** 不在白名单的行计数 SQL（引擎无方言差异）；NULL 不计入。 */
    static String enumBadSql(String table, String column, List<String> allowed) {
        StringBuilder in = new StringBuilder();
        for (String value : allowed) {
            if (!in.isEmpty()) {
                in.append(", ");
            }
            in.append('\'').append(sqlQuote(value)).append('\'');
        }
        return "select count(*) from " + table + " where " + column
                + " is not null and " + column + " not in (" + in + ")";
    }

    /** SQL 字符串字面量转义：标识符已在入口按白名单正则校验，这里只管值里的单引号。 */
    static String sqlQuote(String value) {
        return value.replace("'", "''");
    }

    /**
     * 创建期配置校验（Controller.validate 调用）：只管 TC7-G1 新增四类，
     * 存量六类的老行为不动（契约要求与 CUSTOM_SQL 语义兼容）。
     */
    static void validateConfig(String type, JsonNode config) {
        switch (type) {
            case "VOLATILITY" -> {
                volatilityWindows(config);
                if (config.path("maxChangeRate").asDouble(-1) < 0) {
                    throw ApiException.badRequest("VOLATILITY 规则需要 maxChangeRate（如 0.3 表示 ±30%）");
                }
            }
            case "REGEX" -> compilePatternOrThrow(config.path("pattern").asText(""));
            case "ENUM" -> enumAllowed(config);
            case "DISCRETE" -> {
                if (!config.hasNonNull("min") && !config.hasNonNull("max")) {
                    throw ApiException.badRequest("DISCRETE 规则至少需要配置 min 或 max 之一");
                }
            }
            default -> {
            }
        }
    }

    private record Datasource(String name, String type, String url, String username, String password) {
    }

    private Datasource findDatasource(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select name, ds_type, jdbc_url, username, password
                from platform.datasource where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("质量规则绑定的数据源已不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        return new Datasource(String.valueOf(row.get("name")), String.valueOf(row.get("ds_type")),
                String.valueOf(row.get("jdbc_url")), String.valueOf(row.get("username")),
                row.get("password") == null ? "" : cipher.decrypt(String.valueOf(row.get("password"))));
    }

    private double scalar(String sql, String datasource, Datasource ds, Long projectId) {
        if (ds != null) {
            return jdbcScalar(sql, ds);
        }
        if ("DORIS".equals(datasource)) {
            // 未绑数据源时跟随规则所属项目：外部 Doris 项目不能悄悄跑在内置库上
            DorisTargetService.Target target = dorisTargets.forProject(projectId);
            if (target.external()) {
                return jdbcScalar(sql, "项目 Doris（" + target.label() + "）",
                        target.jdbcUrl(), target.username(), target.password());
            }
            return mcpScalar(sql);
        }
        Object value = jdbc.queryForObject(sql, Object.class);
        return value instanceof Number n ? n.doubleValue() : 0d;
    }

    /**
     * 规则绑定数据源时直接按 JDBC 连接查询，PG / MySQL / Doris 都走同一路径，
     * 不再依赖 MCP 只支持 Doris 的限制。
     */
    private double jdbcScalar(String sql, Datasource ds) {
        return jdbcScalar(sql, "数据源 " + ds.name(),
                com.lab.dataworks.common.JdbcUrlGuard.validate(ds.url()), ds.username(), ds.password());
    }

    private double jdbcScalar(String sql, String label, String url, String username, String password) {
        DriverManager.setLoginTimeout(5);
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            try {
                conn.setReadOnly(true);
            } catch (Exception ignored) {
                // 部分驱动不支持
            }
            try (Statement statement = conn.createStatement()) {
                statement.setQueryTimeout(15);
                // CUSTOM_SQL 可能返回整表；只读第一行，避免驱动把全部结果缓冲进内存
                statement.setMaxRows(1);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    return rs.next() ? rs.getDouble(1) : 0d;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(label + " 质量查询失败: " + e.getMessage(), e);
        }
    }

    private double mcpScalar(String sql) {
        Map<String, Object> params = Map.of("sql", sql, "max_rows", 1);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (mcpToken != null && !mcpToken.isBlank()) {
            headers.setBearerAuth(mcpToken);
        }
        try {
            ResponseEntity<JsonNode> response = http.exchange(
                    mcpBaseUrl + "/tools/doris.sql_query", HttpMethod.POST,
                    new HttpEntity<>(Map.of("params", params), headers), JsonNode.class);
            JsonNode rows = response.getBody() == null
                    ? null : response.getBody().path("result").path("rows");
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                return 0d;
            }
            JsonNode first = rows.get(0);
            if (first == null || !first.isArray() || first.isEmpty()) {
                return 0d;
            }
            return first.get(0).asDouble(0d);
        } catch (Exception e) {
            throw new RuntimeException("Doris 质量查询失败: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------- 规则的数据源归属（D56）

    /**
     * 规则落点。{@code engine} = PG / DORIS；{@code datasourceId} 为空表示按规则所属项目的
     * 数仓路由执行（内置走 MCP、外部走项目连接），非空表示绑死「数据源管理」里的某个连接。
     */
    public record RuleTarget(String engine, Long datasourceId, String database, String datasourceName) {
    }

    /** 探针结果：found=表在不在；error 非空表示「问不到」（连不上/超时），不能当成「表不存在」。 */
    private record Probe(boolean found, String error) {
    }

    /**
     * 定这条规则该落哪个数据源（D56）。
     *
     * <p>旧口径是「没传 datasourceId 就写 datasource='PG'」，于是 Doris 表建出来的规则
     * 一定在平台库上跑 SQL：门禁必然执行失败，还把数据库原文回显给调用方。现在按
     * 显式绑定 → 显式老字段 → 按表名解析 的优先级定归属，解析不出来直接 400 并说清下一步，
     * 不再留下「建得出来、跑不了」的规则。</p>
     */
    public RuleTarget bindTarget(long projectId, String tableName, Long datasourceId, String legacy) {
        String database = databaseOf(tableName);
        String bare = bareTableOf(tableName);
        if (datasourceId != null) {
            return boundToDatasource(projectId, datasourceId, database, bare, tableName);
        }
        if (legacy != null && !legacy.isBlank()) {
            return explicitEngine(legacy, database, tableName);
        }
        return resolveByTableName(projectId, database, bare, tableName);
    }

    /** 显式绑定数据源：连接必须属于本项目；表前缀与连接对不上就 400，不要留到执行时炸 502。 */
    private RuleTarget boundToDatasource(long projectId, Long datasourceId,
                                        String database, String bare, String tableName) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.name, d.ds_type, coalesce(d.config_json ->> 'builtin', 'false') as builtin
                  from platform.datasource d
                 where d.id = ? and d.project_id = ?
                """, datasourceId, projectId);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("数据源不存在或不属于当前项目: " + datasourceId);
        }
        Map<String, Object> row = rows.get(0);
        String name = String.valueOf(row.get("name"));
        boolean doris = "DORIS".equalsIgnoreCase(String.valueOf(row.get("ds_type")));
        if (database == null) {
            // 裸表名跟随规则所属项目：绑哪个连接就用哪个连接的默认口径
            return new RuleTarget(doris ? "DORIS" : "PG", datasourceId, null, name);
        }
        if (!doris) {
            Map<String, Object> owner = projectDatabaseOwner(database);
            if (owner != null) {
                throw ApiException.badRequest("表 " + tableName + " 在 Doris 数仓库 " + database
                        + "（项目「" + owner.get("name") + "」），不能绑 PostgreSQL 数据源「" + name + "」；"
                        + "请改选该库对应的 Doris 数据源");
            }
            return new RuleTarget("PG", datasourceId, database, name);
        }
        if (!projectWarehouse(projectId).owns(database)) {
            Probe probe = probeDatasource(datasourceId, database, bare);
            if (!probe.found()) {
                throw ApiException.badRequest(probe.error() == null
                        ? "数据源「" + name + "」里没有表 " + tableName
                                + "（该连接下找不到库 " + database + "，或表还没建）；请改选数据源或先建表"
                        : "暂时无法在数据源「" + name + "」里确认表 " + tableName + " 是否存在（"
                                + probe.error() + "）；请先在数据源页测试连接，再创建规则");
            }
        }
        return new RuleTarget("DORIS", datasourceId, database, name);
    }

    /** 老调用方显式传 PG / DORIS：尊重它的选择，但与表前缀矛盾时仍然 400（指错比不指更危险）。 */
    private RuleTarget explicitEngine(String legacy, String database, String tableName) {
        String engine = legacy.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PG", "DORIS").contains(engine)) {
            throw ApiException.badRequest("数据源只能是 PG 或 DORIS");
        }
        if (database != null) {
            Map<String, Object> owner = projectDatabaseOwner(database);
            if ("PG".equals(engine) && owner != null) {
                throw ApiException.badRequest("表 " + tableName + " 在 Doris 数仓库 " + database
                        + "（项目「" + owner.get("name") + "」），规则不能按平台库(PG)执行；"
                        + "请把数据源选成 Doris，或绑定该库的数据源");
            }
        }
        return new RuleTarget(engine, null, database, null);
    }

    /** 没给数据源时按表名解析归属；解析不出来就 400，并说明「该配哪个库/数据源」。 */
    private RuleTarget resolveByTableName(long projectId, String database, String bare, String tableName) {
        ProjectWarehouse warehouse = projectWarehouse(projectId);
        if (database == null) {
            // 裸表名跟随项目数仓（平台口径：查询、元数据、质量、试跑都按项目路由）；
            // 项目没接数仓时才退回平台库，保持历史规则可执行。
            return warehouse.hasWarehouse()
                    ? new RuleTarget("DORIS", bindableDatasourceId(warehouse), null, warehouse.datasourceName())
                    : new RuleTarget("PG", null, null, null);
        }
        if (warehouse.owns(database)) {
            return new RuleTarget("DORIS", bindableDatasourceId(warehouse), database, warehouse.datasourceName());
        }
        Map<String, Object> owner = projectDatabaseOwner(database);
        if (owner != null) {
            Long ownerDs = longOrNull(owner.get("warehouse_datasource_id"));
            boolean ownerBuiltin = ownerDs == null || truthy(owner.get("builtin"));
            if (ownerBuiltin && warehouse.builtinRouting()) {
                // 两边都落在平台内置数仓，读得到，直接绑内置 Doris 数据源
                return new RuleTarget("DORIS", warehouses.builtinDatasourceId(), database, "内置 Doris");
            }
            throw ApiException.badRequest("库 " + database + " 属于项目「" + owner.get("name")
                    + "」的数仓，当前项目（" + (warehouse.builtinRouting() ? "内置 Doris"
                    : "外部 Doris：" + warehouse.datasourceName()) + "）读不到它；"
                    + "请把规则建在那个项目中，或在当前项目绑定同一个 Doris 数据源");
        }
        if (pgSchemaExists(database)) {
            return new RuleTarget("PG", null, database, null);
        }
        if (managedAsDoris(database, bare)) {
            return new RuleTarget("DORIS", bindableDatasourceId(warehouse), database, warehouse.datasourceName());
        }
        Probe probe = probeWarehouse(database, bare, projectId);
        if (probe.found()) {
            return new RuleTarget("DORIS", bindableDatasourceId(warehouse), database, warehouse.datasourceName());
        }
        throw ApiException.badRequest("表 " + tableName + " 的归属判不出来：库 " + database
                + " 既不是本项目或本租户任何项目的数仓库，也不是平台库(PG)里的模式"
                + (probe.error() == null ? "" : "，向数仓确认时" + probe.error())
                + "。请在「数据源管理」登记这个库的数据源后绑定，或改成真实存在的表名");
    }

    /** 项目数仓（V98 起 = warehouse_datasource_id，旧项目可能只有库名）。 */
    private record ProjectWarehouse(String devDatabase, String prodDatabase, Long datasourceId,
                                    String datasourceName, String datasourceType, boolean builtin) {
        boolean hasWarehouse() {
            return notBlank(devDatabase) || notBlank(prodDatabase) || "DORIS".equalsIgnoreCase(datasourceType);
        }

        boolean owns(String database) {
            return equalsDb(devDatabase, database) || equalsDb(prodDatabase, database);
        }

        /** 没绑数据源、或绑的是平台内置数据源 = 走内置数仓（MCP）。 */
        boolean builtinRouting() {
            return datasourceId == null || builtin;
        }
    }

    private ProjectWarehouse projectWarehouse(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.dev_database, p.prod_database, p.warehouse_datasource_id,
                       d.name as datasource_name, d.ds_type as datasource_type,
                       coalesce(d.config_json ->> 'builtin', 'false') as datasource_builtin
                  from platform.project p
                  left join platform.datasource d on d.id = p.warehouse_datasource_id
                 where p.id = ?
                """, projectId);
        if (rows.isEmpty()) {
            return new ProjectWarehouse(null, null, null, null, null, false);
        }
        Map<String, Object> row = rows.get(0);
        return new ProjectWarehouse(str(row.get("dev_database")), str(row.get("prod_database")),
                longOrNull(row.get("warehouse_datasource_id")), str(row.get("datasource_name")),
                str(row.get("datasource_type")), truthy(row.get("datasource_builtin")));
    }

    /** 本租户里管着这个库的项目；库里唯一（V49 唯一索引），最多一行。查不到返回 null。 */
    private Map<String, Object> projectDatabaseOwner(String database) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.id, p.name, p.warehouse_datasource_id,
                       coalesce(d.config_json ->> 'builtin', 'false') as builtin
                  from platform.project p
                  left join platform.datasource d on d.id = p.warehouse_datasource_id
                 where p.tenant_id = ?
                   and (lower(coalesce(p.dev_database, '')) = lower(?)
                     or lower(coalesce(p.prod_database, '')) = lower(?))
                 order by p.id limit 1
                """, TenantScope.current(), database, database);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 能写进规则的绑定 id：只写「本项目自己的」或「平台内置的」，避免把规则绑到别的项目的连接上。 */
    private Long bindableDatasourceId(ProjectWarehouse warehouse) {
        if (warehouse.datasourceId() != null && !warehouse.builtin()) {
            return warehouse.datasourceId();
        }
        return warehouses.builtinDatasourceId();
    }

    /** 平台库里有没有这个模式（PG 规则的表前缀是 schema，不是 database）。 */
    private boolean pgSchemaExists(String database) {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.schemata where lower(schema_name) = lower(?)",
                Integer.class, database);
        return n != null && n > 0;
    }

    /** 表管理里这张表登记成了 Doris 对象（库级登记用 table_name='*'）。 */
    private boolean managedAsDoris(String database, String table) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.managed_table
                 where tenant = ? and upper(catalog) = 'DORIS'
                   and lower(schema_name) = lower(?)
                   and (table_name = '*' or lower(table_name) = lower(?))
                """, Integer.class, TenantScope.current(), database, table);
        return n != null && n > 0;
    }

    /** 在规则所属项目实际路由到的数仓上问一句「这张表在不在」。 */
    private Probe probeWarehouse(String database, String table, Long projectId) {
        String sql = existenceSql(database, table);
        if (sql == null) {
            return new Probe(false, null);
        }
        DorisTargetService.Target target;
        try {
            target = dorisTargets.forProject(projectId);
        } catch (Exception e) {
            log.warn("quality probe: cannot resolve warehouse for project {}", projectId, e);
            return new Probe(false, "解析项目数仓目标失败");
        }
        try {
            // 走可覆盖的接缝：单测不碰真实连接也能验「探针命中」这条分支
            return new Probe(probeValue(target, sql) != null, null);
        } catch (Exception e) {
            log.warn("quality probe: warehouse lookup failed for {}.{}", database, table, e);
            return new Probe(false, target.external() ? "项目 Doris 连不上或查询失败" : "内置 Doris 连不上或查询失败");
        }
    }

    /** 在指定数据源上问同一句话，用来拦「规则绑了个对不上的连接」。 */
    private Probe probeDatasource(long datasourceId, String database, String table) {
        String sql = existenceSql(database, table);
        if (sql == null) {
            return new Probe(false, null);
        }
        try {
            return new Probe(probeValueOnBoundDatasource(datasourceId, sql) != null, null);
        } catch (Exception e) {
            log.warn("quality probe: datasource {} lookup failed", datasourceId, e);
            return new Probe(false, e instanceof IllegalStateException ? e.getMessage() : "连不上或查询失败");
        }
    }

    /** 在项目实际路由到的数仓上取探针结果；单测覆盖这里，不必碰真实连接。 */
    String probeValue(DorisTargetService.Target target, String sql) {
        return target.external()
                ? firstValue(sql, target.jdbcUrl(), target.username(), target.password())
                : firstValueViaMcp(sql);
    }

    /** 在被绑定的数据源上取探针结果。 */
    String probeValueOnBoundDatasource(long datasourceId, String sql) {
        Datasource ds = findDatasource(datasourceId);
        if (ds.url() == null || ds.url().isBlank()) {
            throw new IllegalStateException("该数据源没有 JDBC 地址");
        }
        return firstValue(sql, com.lab.dataworks.common.JdbcUrlGuard.validate(ds.url()),
                ds.username(), ds.password());
    }

    /** 标识符白名单后才拼探针 SQL：库表名来自用户输入，不能原样进语句。 */
    private String existenceSql(String database, String table) {
        if (database == null || table == null
                || !database.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            return null;
        }
        return "select table_name from information_schema.tables where lower(table_schema) = '"
                + database.toLowerCase(Locale.ROOT) + "' and lower(table_name) = '"
                + table.toLowerCase(Locale.ROOT) + "' limit 1";
    }

    private String firstValue(String sql, String url, String username, String password) {
        DriverManager.setLoginTimeout(5);
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            try {
                conn.setReadOnly(true);
            } catch (Exception ignored) {
                // 部分驱动不支持
            }
            try (Statement statement = conn.createStatement()) {
                statement.setQueryTimeout(10);
                statement.setMaxRows(1);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }

    private String firstValueViaMcp(String sql) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (mcpToken != null && !mcpToken.isBlank()) {
            headers.setBearerAuth(mcpToken);
        }
        ResponseEntity<JsonNode> response = probeHttp.exchange(
                mcpBaseUrl + "/tools/doris.sql_query", HttpMethod.POST,
                new HttpEntity<>(Map.of("params", Map.of("sql", sql, "max_rows", 1)), headers),
                JsonNode.class);
        JsonNode rows = response.getBody() == null ? null : response.getBody().path("result").path("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return null;
        }
        JsonNode first = rows.get(0);
        if (first == null || !first.isArray() || first.isEmpty()) {
            return null;
        }
        return first.get(0).asText(null);
    }

    /** fm_orders_911144.orders → fm_orders_911144；裸表名返回 null。 */
    static String databaseOf(String tableName) {
        if (tableName == null) {
            return null;
        }
        int dot = tableName.lastIndexOf('.');
        return dot <= 0 ? null : tableName.substring(0, dot);
    }

    /** fm_orders_911144.orders → orders；catalog.db.table 只取最后一段。 */
    static String bareTableOf(String tableName) {
        if (tableName == null) {
            return null;
        }
        int dot = tableName.lastIndexOf('.');
        return dot < 0 ? tableName : tableName.substring(dot + 1);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean equalsDb(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    // ------------------------------------------------------ 执行失败的可读说明（D56）

    /**
     * 存量规则里「表其实在 Doris、数据源写成 PG」的行很多（D56 之前的默认值）。
     * 执行前先看平台库里有没有这个模式，没有就直接 400 说清楚该改哪里，
     * 而不是让 PG 抛原文再包成 502 回给调用方。
     */
    private void requireReachableTarget(String code, String table, String datasource, Long datasourceId) {
        if (!"PG".equals(datasource) || datasourceId != null) {
            return;
        }
        String database = databaseOf(table);
        if (database == null || pgSchemaExists(database)) {
            return;
        }
        Map<String, Object> owner = projectDatabaseOwner(database);
        throw ApiException.badRequest("规则 " + code + " 要在平台库(PG)上查 " + database + "." + bareTableOf(table)
                + "，但平台库里没有模式 " + database
                + (owner == null
                ? "；这个库也没登记在本租户任何项目的数仓上，请先确认表名，或在「数据源管理」登记该库后重新绑定数据源"
                : "；库 " + database + " 属于项目「" + owner.get("name") + "」的 Doris 数仓，"
                        + "请把规则的数据源改成 Doris（或绑定该数仓的数据源）"));
    }

    /** 执行目标的人话名字，出现在错误说明里。 */
    private String targetLabel(String datasource, Datasource ds, Long projectId) {
        try {
            if (ds != null) {
                return "数据源「" + ds.name() + "」(" + ds.type() + ")";
            }
            if ("DORIS".equals(datasource)) {
                DorisTargetService.Target target = dorisTargets.forProject(projectId);
                return target == null ? "内置 Doris（跟随项目）"
                        : target.external() ? "项目 Doris（" + target.label() + "）" : "内置 Doris（跟随项目）";
            }
            return "平台库(PG)";
        } catch (Exception e) {
            return "规则绑定的目标";
        }
    }

    /**
     * 把驱动/数据库原文翻译成「哪里不行 + 下一步做什么」（D56，分类口径 D58 起统一到
     * {@link com.lab.dataworks.common.DorisErrors}，不再各处各写一份关键字表）。
     *
     * <p>原文里有 SQL、连接串甚至账号，既不能进响应，也不该写进质量结果明细；
     * 完整堆栈只留在日志。质量门禁的语义保持 D56 的口径：规则跑不起来 = 422，
     * 数据不达标 = 409（见 {@code QualityController.gate}）。</p>
     */
    static String explainFailure(Throwable error, String target, String table) {
        String object = com.lab.dataworks.common.DorisErrors.objectHint(error);
        return switch (com.lab.dataworks.common.DorisErrors.classify(error)) {
            case UNREACHABLE -> target + " 连不上，规则没执行成功；"
                    + "请到「数据源管理」点测试连接（目标表 " + table + "）";
            case TIMEOUT -> target + " 查询超时（表 " + table
                    + "）；请缩小检查范围或改用分区条件，不要在高闸门里跑全表";
            case DENIED -> target + " 的账号没有查 " + table
                    + " 的权限；需要该库表的 SELECT 权限后重试";
            case MISSING_OBJECT -> "在 " + target + " 里找不到表 "
                    + (object == null ? table : object)
                    + "；请确认库名/表名，或把规则绑到这张表实际所在的数据源";
            case BAD_SQL -> target + " 不支持这条检查 SQL（表 " + table
                    + " 的字段名或规则类型与该引擎不匹配）";
            case ALREADY_EXISTS -> target + " 里同名对象已存在，规则 " + table
                    + " 没执行成功；请确认规则指向的表名与实际库表是否一致";
            case INTERNAL -> target + " 执行失败（错误类型 "
                    + rootCause(error).getClass().getSimpleName()
                    + "），详情见后端日志 quality rule";
        };
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int i = 0; i < 12 && current.getCause() != null && current.getCause() != current; i++) {
            current = current.getCause();
        }
        return current;
    }

    private String lit(double value) {
        if (value == Double.MAX_VALUE) {
            return "9223372036854775807";
        }
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
