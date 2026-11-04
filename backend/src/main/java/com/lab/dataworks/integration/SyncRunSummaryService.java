package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REQ-DW-17：同步运行「结果摘要」统一契约（一次性立即执行与周期任务最近一次运行同一个口径）。
 *
 * <p>摘要只回答四件事：同步了多少条、这次同步了哪些字段、落到哪里（项目 → 库 → 表）、
 * 失败卡在哪个阶段（连接/抽取/写入）。数据全部来自 {@code sync_task_run} + {@code sync_task}
 * 已有列，不新造状态；渲染逻辑是纯函数（static），单测直接测。</p>
 *
 * <p>by-id 访问一律经 run → task → project → tenant 收口（v447 口径），查不到按 404，
 * 不编造别的租户的运行。</p>
 */
@Service
public class SyncRunSummaryService {

    /** 运行终态：前端轮询到这些状态就停。 */
    static final Set<String> TERMINAL = Set.of(
            "SUCCESS", "FAILED", "TIMEOUT", "STOPPED", "GONE", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SyncRunSummaryService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final String SUMMARY_SQL = """
            select r.id as "runId", r.task_id as "taskId", t.code as "taskCode", t.name as "taskName",
                   r.status, r.run_type as "runType", r.started_at as "startedAt",
                   r.finished_at as "finishedAt", r.duration_ms as "durationMs",
                   r.rows_read as "rowsRead", r.rows_written as "rowsWritten",
                   r.failure_reason as "failureReason", r.verify_result as "verifyResult",
                   r.rendered_sql::text as "renderedSql",
                   t.source_type as "sourceType", t.sink_type as "sinkType",
                   t.source_config::text as "sourceConfig", t.sink_config::text as "sinkConfig",
                   t.sink_database as "sinkDatabase", t.sink_table as "sinkTable",
                   p.name as "projectName"
              from platform.sync_task_run r
              join platform.sync_task t on t.id = r.task_id
              join platform.project p on p.id = t.project_id
            """;

    /** 单次运行摘要（立即执行轮询用）。 */
    public Map<String, Object> forRun(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SUMMARY_SQL + " where r.id = ? and p.tenant_id = ?",
                runId, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("运行实例不存在: " + runId);
        }
        return assemble(rows.get(0));
    }

    /** 任务最近一次运行摘要（卡片「最近运行」用）；没运行过也回同契约的 NEVER_RUN。 */
    public Map<String, Object> forTask(long taskId) {
        Object tenant = com.lab.dataworks.auth.TenantScope.current();
        List<Map<String, Object>> rows = jdbc.queryForList(
                SUMMARY_SQL + " where t.id = ? and p.tenant_id = ? order by r.id desc limit 1",
                taskId, tenant);
        if (!rows.isEmpty()) {
            return assemble(rows.get(0));
        }
        List<Map<String, Object>> tasks = jdbc.queryForList("""
                select t.id as "taskId", t.code as "taskCode", t.name as "taskName",
                       t.source_type as "sourceType", t.sink_type as "sinkType",
                       t.source_config::text as "sourceConfig", t.sink_config::text as "sinkConfig",
                       t.sink_database as "sinkDatabase", t.sink_table as "sinkTable",
                       p.name as "projectName"
                  from platform.sync_task t
                  join platform.project p on p.id = t.project_id
                 where t.id = ? and p.tenant_id = ?
                """, taskId, tenant);
        if (tasks.isEmpty()) {
            throw ApiException.notFound("同步任务不存在: " + taskId);
        }
        Map<String, Object> row = new LinkedHashMap<>(tasks.get(0));
        row.put("runId", null);
        row.put("status", "NEVER_RUN");
        return assemble(row);
    }

    /** 把一行 run（或任务兜底行）拼成前端直接渲染的摘要对象。 */
    Map<String, Object> assemble(Map<String, Object> row) {
        Map<String, Object> sourceCfg = parseMap(row.get("sourceConfig"));
        Map<String, Object> sinkCfg = parseMap(row.get("sinkConfig"));
        List<String> sqls = parseSqls(row.get("renderedSql"));
        String status = row.get("status") == null ? "NEVER_RUN" : String.valueOf(row.get("status"));
        List<Map<String, String>> fields = extractFields(sourceCfg, sinkCfg, sqls);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("runId", row.get("runId"));
        s.put("taskId", row.get("taskId"));
        s.put("taskCode", row.get("taskCode"));
        s.put("taskName", row.get("taskName"));
        s.put("status", status);
        s.put("terminal", TERMINAL.contains(status));
        s.put("runType", row.get("runType"));
        s.put("startedAt", row.get("startedAt"));
        s.put("finishedAt", row.get("finishedAt"));
        s.put("durationMs", row.get("durationMs"));
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("read", row.get("rowsRead"));
        rows.put("written", row.get("rowsWritten"));
        s.put("rows", rows);
        s.put("records", row.get("rowsWritten") != null ? row.get("rowsWritten") : row.get("rowsRead"));
        s.put("fields", fields);
        if (fields.isEmpty()) {
            s.put("fieldsNote", "未声明字段清单：源端为动态 schema，以查询结果为准");
        }
        s.put("landing", landing(row, sinkCfg));
        s.put("tableBehavior", tableBehavior(sinkCfg));
        s.put("failure", failure(status, row.get("failureReason") == null
                ? null : String.valueOf(row.get("failureReason"))));
        s.put("verifyResult", row.get("verifyResult"));
        return s;
    }

    // ---------- 纯函数：字段清单 / 失败阶段 / 落点 / 建表行为 ----------

    /**
     * 字段清单：优先 schema.fields 显式声明（Kafka 源必填、也允许写在 sink 侧），
     * 其次从渲染 SQL 的第一条 SELECT 解析列；都拿不到返回空表（调用方给 fieldsNote）。
     */
    static List<Map<String, String>> extractFields(Map<String, Object> sourceCfg,
                                                   Map<String, Object> sinkCfg,
                                                   List<String> sqls) {
        List<Map<String, String>> f = fieldsFromSchema(sourceCfg);
        if (f.isEmpty()) {
            f = fieldsFromSchema(sinkCfg);
        }
        if (f.isEmpty() && sqls != null) {
            for (String sql : sqls) {
                if (sql == null || !sql.trim().regionMatches(true, 0, "select", 0, 6)) {
                    continue;
                }
                List<Map<String, String>> parsed = parseSelectColumns(sql.trim());
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }
        return f;
    }

    private static List<Map<String, String>> fieldsFromSchema(Map<String, Object> cfg) {
        if (cfg == null || cfg.isEmpty()) {
            return List.of();
        }
        if (cfg.get("schema") instanceof Map<?, ?> m
                && m.get("fields") instanceof Map<?, ?> fm && !fm.isEmpty()) {
            return toFieldList(fm);
        }
        if (cfg.get("fields") instanceof Map<?, ?> fm && !fm.isEmpty()) {
            return toFieldList(fm);
        }
        return List.of();
    }

    private static List<Map<String, String>> toFieldList(Map<?, ?> fields) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : fields.entrySet()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(e.getKey()));
            item.put("type", e.getValue() == null ? "" : String.valueOf(e.getValue()));
            out.add(item);
        }
        return out;
    }

    /**
     * 保守解析 {@code select a, b as c, t.d from ...} 的顶层列：
     * 支持 {@code x.y}、{@code as 别名}、裸 {@code *}（返回单个 "*" 占位）；
     * 无别名的表达式（count(1) 等）不硬猜，直接跳过——宁可少报也不报错列名。
     */
    static List<Map<String, String>> parseSelectColumns(String sql) {
        String lower = sql.toLowerCase();
        int from = -1;
        int depth = 0;
        char quote = 0;
        for (int i = 6; i + 4 <= sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (depth == 0 && c == 'f' && lower.startsWith("from", i)
                    && Character.isWhitespace(sql.charAt(i - 1))
                    && (i + 4 == sql.length() || !Character.isLetterOrDigit(sql.charAt(i + 4)))) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return List.of();
        }
        String cols = sql.substring(6, from).trim();
        if (cols.regionMatches(true, 0, "distinct", 0, 8)) {
            cols = cols.substring(8).trim();
        }
        if (cols.isEmpty()) {
            return List.of();
        }
        if (cols.equals("*")) {
            return List.of(Map.of("name", "*", "type", ""));
        }
        List<Map<String, String>> out = new ArrayList<>();
        depth = 0;
        quote = 0;
        int start = 0;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i <= cols.length(); i++) {
            char c = i == cols.length() ? ',' : cols.charAt(i);
            if (i < cols.length()) {
                char ch = cols.charAt(i);
                if (quote != 0) {
                    if (ch == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (ch == '\'' || ch == '"' || ch == '`') {
                    quote = ch;
                    continue;
                }
                if (ch == '(' || ch == '[') {
                    depth++;
                    continue;
                }
                if (ch == ')' || ch == ']') {
                    depth--;
                    continue;
                }
            }
            if (c == ',' && depth == 0) {
                parts.add(cols.substring(start, i).trim());
                start = i + 1;
            }
        }
        for (String part : parts) {
            String name = columnName(part);
            if (name != null && !name.isEmpty()) {
                out.add(Map.of("name", name, "type", ""));
            }
        }
        return out;
    }

    /** 单个 select 列 → 输出列名；解析不出可信名字时返回 null（跳过该列）。 */
    private static String columnName(String part) {
        if (part.isEmpty()) {
            return null;
        }
        String alias = topLevelSuffixAfterAs(part);
        if (alias != null) {
            return alias;
        }
        String token = part.trim();
        if (token.contains("(") || token.contains("*")
                || token.contains("+") || token.contains("|") || token.contains("%")) {
            return null;   // 表达式/通配：不猜列名
        }
        String[] words = token.split("\\s+");
        String last = words[words.length - 1];
        if (words.length > 1) {
            // 隐式别名「t.id tid」：最后一个词是可信标识符才认，否则整列跳过
            return last.matches("[A-Za-z_]\\w*") ? last : null;
        }
        if (!last.matches("[A-Za-z_][\\w$]*(\\.[A-Za-z_][\\w$]*)*")) {
            return null;
        }
        int dot = last.lastIndexOf('.');
        return dot >= 0 ? last.substring(dot + 1) : last;
    }

    /** 顶层（括号外）的 {@code " as xxx"} → 返回 xxx；没有返回 null。 */
    private static String topLevelSuffixAfterAs(String part) {
        String lower = part.toLowerCase();
        int depth = 0;
        char quote = 0;
        for (int j = 0; j + 4 <= part.length(); j++) {
            char c = part.charAt(j);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ' ' && lower.startsWith("as ", j + 1)) {
                String alias = part.substring(j + 4).trim();
                if (!alias.isEmpty()) {
                    return alias;
                }
            }
        }
        return null;
    }

    /**
     * 失败阶段归类。顺序：连接 → 校验 → 写入 → 抽取（先匹配先赢）：
     * 计数不一致归「校验」而不是「写入」，含 connect 的一律归「连接」。
     */
    static String classifyStage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "未知";
        }
        String r = reason.toLowerCase();
        if (containsAny(r, "connection", "connect", "refused", "communications link",
                "access denied", "authentication", "unreachable", "no route",
                "socket", "handshake", "dns", "timeout connecting")) {
            return "连接";
        }
        if (containsAny(r, "行数不一致", "校验", "verify")) {
            return "校验";
        }
        if (containsAny(r, "sink", "stream load", "streamload", "doris", "insert into",
                "write", "target table", "flush", "commit failed")) {
            return "写入";
        }
        if (containsAny(r, "source", "extract", "read", "query", "fetch", "cursor",
                "kafka", "topic", "resultset")) {
            return "抽取";
        }
        return "未知";
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** FAILED/TIMEOUT/STOPPED/CANCELLED/GONE 给出阶段+原因；成功/未运行/进行中/跳过返回 null。 */
    static Map<String, Object> failure(String status, String reason) {
        if (status == null) {
            return null;
        }
        String stage = switch (status) {
            case "STOPPED" -> "已停止";
            case "TIMEOUT" -> "超时";
            case "CANCELLED", "GONE" -> "作业消失";
            case "FAILED" -> classifyStage(reason);
            default -> null;   // SUCCESS / RUNNING / PENDING / NEVER_RUN / SKIPPED
        };
        if (stage == null) {
            return null;
        }
        String text = reason == null || reason.isBlank()
                ? "未记录失败原因：请查看运行日志" : reason;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stage", stage);
        f.put("reason", text);
        return f;
    }

    /** 目标落点全路径：项目 → 库 → 表；非表类目标（文件/集合）退化到配置里的路径。 */
    static Map<String, Object> landing(Map<String, Object> row, Map<String, Object> sinkCfg) {
        String project = str(row.get("projectName"), "未知项目");
        String database = str(row.get("sinkDatabase"), null);
        String table = str(row.get("sinkTable"), null);
        String sinkType = str(row.get("sinkType"), "");
        if (database == null && sinkCfg.get("database") != null) {
            database = String.valueOf(sinkCfg.get("database"));
        }
        if (table == null) {
            table = firstText(sinkCfg, "table", "collection", "path");
        }
        List<String> parts = new ArrayList<>();
        parts.add(project);
        if (database != null && !database.isBlank()) {
            parts.add(database);
        }
        parts.add(table != null && !table.isBlank() ? table
                : "(" + (sinkType.isBlank() ? "sink" : sinkType) + " 目标未声明表)");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", project);
        m.put("database", database);
        m.put("table", table);
        m.put("sinkType", sinkType);
        m.put("path", String.join(" → ", parts));
        return m;
    }

    /** 目标表不存在时的行为：与 SyncTaskService 渲染 sink 的 schema_save_mode 同源缺省。 */
    static Map<String, Object> tableBehavior(Map<String, Object> sinkCfg) {
        Object mode = sinkCfg.get("schema_save_mode");
        if (mode == null) {
            mode = sinkCfg.get("schemaSaveMode");
        }
        String m = mode == null || String.valueOf(mode).isBlank()
                ? "CREATE_SCHEMA_WHEN_NOT_EXIST" : String.valueOf(mode);
        String text = switch (m) {
            case "CREATE_SCHEMA_WHEN_NOT_EXIST" -> "自动建表（CREATE_SCHEMA_WHEN_NOT_EXIST）";
            case "ERROR_WHEN_SCHEMA_NOT_EXIST" -> "报错（ERROR_WHEN_SCHEMA_NOT_EXIST）";
            case "IGNORE" -> "不建表，表不存在时报错（IGNORE）";
            default -> "按 sink 配置执行（" + m + "）";
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", m);
        out.put("text", text);
        return out;
    }

    // ---------- 解析工具 ----------

    private Map<String, Object> parseMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(text, Map.class);
        } catch (Exception e) {
            // 脏配置不影响摘要出其余部分
            return Map.of();
        }
    }

    private List<String> parseSqls(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return List.of();
        }
        try {
            List<Object> parsed = json.readValue(text, List.class);
            List<String> out = new ArrayList<>();
            for (Object o : parsed) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of(text);
        }
    }

    private static String str(Object v, String fallback) {
        if (v == null) {
            return fallback;
        }
        String text = String.valueOf(v);
        return text.isBlank() ? fallback : text;
    }

    private static String firstText(Map<String, Object> cfg, String... keys) {
        for (String k : keys) {
            Object v = cfg.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }
}
