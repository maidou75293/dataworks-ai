package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 模板与动态参数。
 *
 * - 任务里声明参数（name/type/required/default），运行时传值覆盖；
 * - SQL 用 ${name} 引用，按声明类型转义后替换，未定义/必填缺失直接拒绝；
 * - ${name:ident} 只允许表名/列名标识符；
 * - 内置变量：bizdate/bizdatetime/now/timestamp/task_id/project_id/env/hour，
 *   支持 ${bizdate-1d}、${now-2h} 这类偏移；
 * - 源端 SQL 只允许单条 SELECT/WITH，拦截 DML/DDL/OUTFILE/LOAD_FILE/SLEEP。
 */
public final class SqlTemplates {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern EXPR = Pattern.compile(
            "^(bizdate|now)(?:([+-])(\\d+)([dhmM]))?$");
    private static final Pattern IDENT = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?");
    private static final Pattern NUMERIC_TYPE = Pattern.compile(
            "int|integer|long|decimal|number|double|float");
    private static final Set<String> BANNED = Set.of(
            "insert", "update", "delete", "drop", "alter", "truncate", "create",
            "grant", "revoke", "call", "load_file", "outfile",
            "dumpfile", "sleep", "benchmark",
            // PostgreSQL 文件/网络/延时函数，以及 SELECT INTO / DO 块
            "into", "do", "merge", "copy", "vacuum", "analyze",
            "pg_sleep", "pg_read_file", "pg_read_binary_file", "pg_ls_dir",
            "pg_stat_file", "pg_terminate_backend", "pg_cancel_backend",
            "pg_reload_conf", "pg_rotate_logfile", "pg_advisory_lock",
            "pg_advisory_xact_lock", "pg_try_advisory_lock",
            "setval", "nextval", "lo_import", "lo_export", "lo_unlink",
            "dblink", "dblink_exec", "dblink_connect", "set_config");

    /**
     * 带副作用的函数即使出现在 SELECT 里也必须拒绝。
     *
     * <p>先只掩掉单引号字符串、保留双引号标识符：PG 允许
     * {@code select "pg_read_file"('/etc/passwd')}，若把双引号内容一起抹掉，
     * 关键字黑名单就看不到函数名。</p>
     */
    private static final Pattern DANGEROUS_CALL = Pattern.compile(
            "(?i)(?:^|[^a-z0-9_])[`\"]?("
                    + "pg_read_file|pg_read_binary_file|pg_ls_dir|pg_stat_file|"
                    + "pg_terminate_backend|pg_cancel_backend|pg_reload_conf|pg_rotate_logfile|"
                    + "pg_advisory_lock|pg_advisory_xact_lock|pg_try_advisory_lock|"
                    + "setval|nextval|lo_import|lo_export|lo_unlink|"
                    + "dblink|dblink_exec|dblink_connect|load_file|sleep|benchmark"
                    + ")[`\"]?\\s*\\(");

    private static final Pattern INTO_FILE = Pattern.compile(
            "(?i)\\binto\\s+(outfile|dumpfile)\\b");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private SqlTemplates() {
    }

    public record Result(Map<String, Object> params, List<String> sqls) {
    }

    @SuppressWarnings("unchecked")
    public static Result apply(Map<String, Object> task, Map<String, Object> overrides) {
        Object rawSource = task.get("sourceConfig");
        Map<String, Object> source = rawSource instanceof Map<?, ?>
                ? (Map<String, Object>) rawSource : new LinkedHashMap<>();
        List<Map<String, Object>> specs = listMaps(source.get("params"));
        Map<String, Object> input = overrides == null ? Map.of() : overrides;
        boolean hasSql = source.get("query") != null || source.get("tables") != null;
        if (!hasSql && specs.isEmpty() && input.isEmpty()) {
            return new Result(Map.of(), List.of());
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(text(source.get("timezone"), "Asia/Shanghai"));
        } catch (Exception e) {
            throw ApiException.badRequest("sourceConfig.timezone 不是合法时区");
        }
        int offset = intValue(source.get("bizDateOffsetDays"), -1);
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime biz = LocalDate.now(zone).plusDays(offset).atStartOfDay(zone);

        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        values.put("bizdate", biz.format(BASIC_DATE));
        values.put("bizdatetime", biz.format(DATETIME));
        values.put("now", now.format(DATETIME));
        values.put("timestamp", String.valueOf(now.toInstant().toEpochMilli()));
        values.put("yyyyMMdd", now.format(BASIC_DATE));
        values.put("yyyy-MM-dd", now.format(DATE));
        values.put("yyyy-MM-dd HH:mm:ss", now.format(DATETIME));
        values.put("hour", String.format("%02d", now.getHour()));
        values.put("task_id", String.valueOf(task.getOrDefault("id", "")));
        values.put("project_id", String.valueOf(task.getOrDefault("projectId", "")));
        values.put("env", text(task.get("env"), "DEV"));
        for (String numeric : List.of("timestamp", "task_id", "project_id")) {
            types.put(numeric, "long");
        }
        types.put("bizdate", "date");
        types.put("bizdatetime", "datetime");
        types.put("now", "datetime");

        Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
        for (Map<String, Object> spec : specs) {
            String name = text(spec.get("name"), "");
            if (!IDENT.matcher(name).matches() || name.contains(".")) {
                throw ApiException.badRequest("参数名不合法: " + name);
            }
            declared.put(name, spec);
        }
        for (String key : input.keySet()) {
            if (!declared.containsKey(key) && !values.containsKey(key)) {
                throw ApiException.badRequest("未声明的参数: " + key
                        + "（请先在任务的 sourceConfig.params 中声明）");
            }
        }

        // 默认值/覆盖值可能引用其他变量，最多解析 3 轮
        for (Map.Entry<String, Map<String, Object>> e : declared.entrySet()) {
            String name = e.getKey();
            Map<String, Object> spec = e.getValue();
            String type = normType(text(spec.get("type"), "string"));
            Object raw = input.containsKey(name) ? input.get(name) : spec.get("default");
            if (blank(raw) && Boolean.TRUE.equals(spec.get("required"))) {
                throw ApiException.badRequest("缺少必填参数: " + name);
            }
            if (raw == null) {
                continue;
            }
            if (String.valueOf(raw).isBlank() && !"string".equals(type)) {
                continue;
            }
            types.put(name, type);
            values.put(name, coerce(resolve(String.valueOf(raw), values, biz, now), type, name));
        }
        // 运行时覆盖内置变量（例如补数时直接传 bizdate）
        for (String key : input.keySet()) {
            if (!declared.containsKey(key)) {
                values.put(key, coerce(resolve(String.valueOf(input.get(key)), values, biz, now),
                        types.getOrDefault(key, "string"), key));
            }
        }

        List<String> sqls = new ArrayList<>();
        String query = text(source.get("query"), null);
        if (query != null) {
            String rendered = render(query, values, types, biz, now);
            guardReadOnly(rendered);
            sqls.add(rendered);
            source.put("query", rendered);
        }
        Object rawTables = source.get("tables");
        if (rawTables instanceof List<?> tables) {
            for (Object item : tables) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> table = (Map<String, Object>) item;
                String q = text(table.get("query"), null);
                if (q != null) {
                    String rendered = render(q, values, types, biz, now);
                    guardReadOnly(rendered);
                    sqls.add(rendered);
                    table.put("query", rendered);
                }
                String t = text(table.get("table"), null);
                if (t != null && t.contains("${")) {
                    table.put("table", render(t, values, types, biz, now));
                }
            }
        }
        return new Result(values, sqls);
    }

    /** 预览：只渲染不提交，返回参数快照与渲染后的 SQL。 */
    public static Result preview(Map<String, Object> task, Map<String, Object> overrides) {
        return apply(task, overrides);
    }

    /** 配置期校验：给必填但无默认值的参数填样例值，仅用于 SQL 语法/安全校验。 */
    public static Map<String, Object> sampleOverrides(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> spec : listMaps(source.get("params"))) {
            String name = text(spec.get("name"), "");
            if (name.isBlank() || !Boolean.TRUE.equals(spec.get("required"))
                    || !blank(spec.get("default"))) {
                continue;
            }
            String type = normType(text(spec.get("type"), "string"));
            out.put(name, switch (type) {
                case "int", "integer", "long", "decimal", "number" -> "1";
                case "bool", "boolean" -> "0";
                case "date" -> "2026-01-01";
                case "datetime" -> "2026-01-01 00:00:00";
                case "ident" -> "sample_table";
                default -> "sample";
            });
        }
        return out;
    }

    private static String resolve(String template, Map<String, Object> values,
                                  ZonedDateTime biz, ZonedDateTime now) {
        if (template == null) {
            return null;
        }
        String out = template;
        for (int pass = 0; pass < 3 && out.contains("${"); pass++) {
            Matcher m = VAR.matcher(out);
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            while (m.find()) {
                String key = m.group(1).trim();
                Object value = lookup(key, values, biz, now);
                if (value == null) {
                    throw ApiException.badRequest("SQL 变量未定义或未传值: " + key);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
                replaced = true;
            }
            m.appendTail(sb);
            out = sb.toString();
            if (!replaced) {
                break;
            }
        }
        return out;
    }

    private static String render(String sql, Map<String, Object> values,
                                 Map<String, String> types,
                                 ZonedDateTime biz, ZonedDateTime now) {
        Matcher m = VAR.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1).trim();
            String mode = null;
            int colon = expr.lastIndexOf(':');
            if (colon > 0) {
                mode = expr.substring(colon + 1).trim();
                expr = expr.substring(0, colon).trim();
            }
            Object value = lookup(expr, values, biz, now);
            if (value == null) {
                throw ApiException.badRequest("SQL 变量未定义或未传值: " + expr);
            }
            String type = types.getOrDefault(expr, "string");
            String replacement;
            if ("ident".equalsIgnoreCase(mode) || "ident".equals(type)) {
                String ident = String.valueOf(value);
                if (!IDENT.matcher(ident).matches()) {
                    throw ApiException.badRequest("变量 " + expr + " 不是合法标识符: " + ident);
                }
                replacement = ident;
            } else if (NUMERIC_TYPE.matcher(type).matches()) {
                replacement = new BigDecimal(String.valueOf(value)).toPlainString();
            } else if ("bool".equals(type) || "boolean".equals(type)) {
                replacement = truth(String.valueOf(value)) ? "1" : "0";
            } else {
                // string 参数：如果占位符不在引号里，必须自动加引号。
                // 以前直接替换，`where id = ${id}` 传 "0 or 1=1" 就能改查询语义。
                String escaped = escapeSql(String.valueOf(value));
                replacement = inSingleLiteral(sql, m.start())
                        ? escaped : "'" + escaped + "'";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Object lookup(String key, Map<String, Object> values,
                                 ZonedDateTime biz, ZonedDateTime now) {
        Matcher e = EXPR.matcher(key);
        if (e.matches()) {
            boolean isBiz = "bizdate".equals(e.group(1));
            ZonedDateTime base = isBiz ? biz : now;
            // 运行时覆盖优先：补数时 runParams 直接传 bizdate / now，内置变量不能盖掉它
            Object override = values.get(e.group(1));
            if (override != null) {
                ZonedDateTime parsed = parseBase(String.valueOf(override), isBiz,
                        base.getZone());
                if (parsed != null) {
                    base = parsed;
                }
            }
            if (e.group(2) != null) {
                int n = Integer.parseInt(e.group(3));
                int sign = "+".equals(e.group(2)) ? 1 : -1;
                String unit = e.group(4);
                switch (unit) {
                    case "d" -> base = base.plusDays((long) sign * n);
                    case "h" -> base = base.plusHours((long) sign * n);
                    case "m" -> base = base.plusMinutes((long) sign * n);
                    case "M" -> base = base.plusMonths((long) sign * n);
                    default -> {
                    }
                }
            }
            return isBiz ? base.format(BASIC_DATE) : base.format(DATETIME);
        }
        return values.get(key);
    }

    /** 解析运行时覆盖的基准时间：bizdate 支持 yyyy-MM-dd / yyyyMMdd，其余按日期时间解析。 */
    private static ZonedDateTime parseBase(String raw, boolean isBiz, ZoneId zone) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            if (isBiz) {
                java.time.LocalDate d = v.matches("\\d{8}")
                        ? java.time.LocalDate.parse(v, BASIC_DATE)
                        : java.time.LocalDate.parse(v);
                return d.atStartOfDay(zone);
            }
            String normalized = v.replace(' ', 'T');
            java.time.LocalDateTime t = normalized.length() == 10
                    ? java.time.LocalDate.parse(normalized).atStartOfDay()
                    : java.time.LocalDateTime.parse(normalized);
            return t.atZone(zone);
        } catch (Exception e) {
            return null;
        }
    }

    private static String coerce(String value, String type, String name) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        try {
            switch (type) {
                case "int", "integer":
                    return String.valueOf(Integer.parseInt(v));
                case "long":
                    return String.valueOf(Long.parseLong(v));
                case "decimal", "number", "double", "float":
                    return new BigDecimal(v).toPlainString();
                case "bool", "boolean":
                    return truth(v) ? "1" : "0";
                case "date":
                    if (v.matches("\\d{8}")) {
                        return LocalDate.parse(v, BASIC_DATE).format(DATE);
                    }
                    return LocalDate.parse(v).format(DATE);
                case "datetime":
                    if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        return v + " 00:00:00";
                    }
                    return LocalDateTime.parse(v.replace(' ', 'T')).format(DATETIME);
                case "ident":
                    if (!IDENT.matcher(v).matches()) {
                        throw ApiException.badRequest("参数 " + name + " 不是合法标识符: " + v);
                    }
                    return v;
                default:
                    return v;
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw ApiException.badRequest("参数 " + name + " 类型应为 " + type + "，实际值: " + v);
        }
    }

    /** 判断位置 pos 是否落在单引号字符串字面量内（'' 视为转义的引号）。 */
    private static boolean inSingleLiteral(String sql, int pos) {
        boolean in = false;
        for (int i = 0; i < pos && i < sql.length(); i++) {
            if (sql.charAt(i) == '\'') {
                if (in && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                in = !in;
            }
        }
        return in;
    }

    private static String escapeSql(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "''")
                .replace("\u0000", "");
    }

    /** 源端只读校验：单条 SELECT/WITH；拒绝 DML/DDL/文件/延时/有副作用函数。 */
    public static void guardReadOnly(String sql) {
        guardReadOnly(sql, "源端 SQL");
    }

    /**
     * 只读校验。方言差异必须按「两种解释都合法」处理：
     * 反斜杠在 MySQL 里转义、在 PG 标准字符串里是普通字符；
     * {@code --} 在 PG 里总是注释、在 MySQL 里必须跟空白。
     * 只要任一种解释下能藏进第二条语句或写操作，就拒绝（fail-closed）。
     */
    public static void guardReadOnly(String sql, String subject) {
        if (sql == null || sql.isBlank()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        if (sql.contains("/*!")) {
            throw ApiException.badRequest(subject + " 不允许执行型注释 /*! */");
        }
        for (boolean backslash : new boolean[]{true, false}) {
            String masked = maskSingleQuoted(sql, backslash);
            if (DANGEROUS_CALL.matcher(masked).find()) {
                throw ApiException.badRequest(subject + " 不允许调用有副作用的函数");
            }
            if (INTO_FILE.matcher(masked).find()) {
                throw ApiException.badRequest(subject + " 不允许写入文件（INTO OUTFILE/DUMPFILE）");
            }
        }
        checkVariant(strip(sql, true, true), subject);
        checkVariant(strip(sql, false, false), subject);
    }

    private static void checkVariant(String stripped, String subject) {
        String code = stripped.trim();
        while (code.endsWith(";")) {
            code = code.substring(0, code.length() - 1).trim();
        }
        if (code.contains(";")) {
            throw ApiException.badRequest(subject + " 只允许单条语句");
        }
        String lower = code.toLowerCase(Locale.ROOT);
        // v457 MT-P1-2：SHOW / DESC / EXPLAIN 是只读元数据语句，与 SELECT 同级放行；
        // 仍然过下面的危险关键字与 FOR UPDATE 检查。
        if (!(lower.startsWith("select") || lower.startsWith("with")
                || lower.startsWith("show") || lower.startsWith("desc")
                || lower.startsWith("describe") || lower.startsWith("explain"))) {
            throw ApiException.badRequest(subject + " 只允许 SELECT / WITH / SHOW / DESC / EXPLAIN");
        }
        for (String word : BANNED) {
            if (Pattern.compile("(^|[^a-z0-9_])" + word + "([^a-z0-9_]|$)")
                    .matcher(lower).find()) {
                throw ApiException.badRequest(subject + " 不允许包含 "
                        + word.toUpperCase(Locale.ROOT));
            }
        }
        if (Pattern.compile("(?is)\\bfor\\s+update\\b").matcher(lower).find()) {
            throw ApiException.badRequest(subject + " 不允许 FOR UPDATE");
        }
    }

    /** 只掩掉单引号字符串，保留双引号标识符/反引号，供函数名扫描。 */
    private static String maskSingleQuoted(String sql, boolean backslashEscapes) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean in = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (!in) {
                if (c == '\'') {
                    in = true;
                }
                out.append(in ? ' ' : c);
                i++;
                continue;
            }
            if (backslashEscapes && c == '\\') {
                i += Math.min(2, sql.length() - i);
                out.append(' ');
                continue;
            }
            if (c == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                } else {
                    in = false;
                    i++;
                }
                out.append(' ');
                continue;
            }
            out.append(' ');
            i++;
        }
        return out.toString();
    }

    /**
     * 去掉字符串与注释，避免把字面量里的关键字当成语句。
     *
     * @param backslashEscapes 字符串内反斜杠是否转义下一个字符（MySQL 行为）
     * @param mysqlDashComment {@code --} 是否必须跟空白才算注释（MySQL 行为）
     */
    private static String strip(String sql, boolean backslashEscapes, boolean mysqlDashComment) {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (backslashEscapes && c == '\\') {
                    i += Math.min(2, sql.length() - i);
                    out.append(' ');
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                        i += 2;
                    } else {
                        quote = 0;
                        i++;
                    }
                    out.append(' ');
                    continue;
                }
                out.append(' ');
                i++;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(' ');
                i++;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                boolean comment = !mysqlDashComment
                        || i + 2 >= sql.length()
                        || Character.isWhitespace(sql.charAt(i + 2))
                        || Character.isISOControl(sql.charAt(i + 2));
                if (comment) {
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                    out.append(' ');
                    continue;
                }
            }
            if (c == '#') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String normType(String type) {
        String t = type == null ? "string" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "integer" -> "int";
            case "boolean" -> "bool";
            case "double", "float" -> "number";
            default -> t;
        };
    }

    private static boolean truth(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "y".equals(v);
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank()
                ? fallback : String.valueOf(value).trim();
    }

    private static int intValue(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> listMaps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?>) {
                    out.add(map(item));
                }
            }
        }
        return out;
    }
}
