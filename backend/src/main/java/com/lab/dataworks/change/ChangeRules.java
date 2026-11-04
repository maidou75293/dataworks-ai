package com.lab.dataworks.change;

import com.lab.dataworks.guard.SqlGuard;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变更单 v1 的纯规则（inbox/007）：拆句、语句动词、备份计划、备份表名、审批前置判定。
 *
 * <p>不含数据库访问，便于单测。判定本身仍走 {@link SqlGuard}（同一个口径，避免
 * 「分类说 tmp 放行、执行又要审批」这种不一致）。</p>
 */
public final class ChangeRules {

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 需要执行前备份的动词（007 第六节：DROP / TRUNCATE 单表）。 */
    private static final Set<String> BACKUP_VERBS = Set.of("DROP", "TRUNCATE");

    /** 识别语句动词时认得的词（与 SqlGuard 保持一致的动词表）。 */
    private static final Set<String> VERBS = Set.of(
            "DROP", "TRUNCATE", "ALTER", "DELETE", "UPDATE", "MERGE",
            "SELECT", "INSERT", "CREATE", "REPLACE", "ANALYZE", "SHOW",
            "DESC", "DESCRIBE", "USE", "SET", "CALL", "OPTIMIZE", "GRANT", "REVOKE");

    /** WITH / EXPLAIN / ANALYZE 只是前置词，真正的动词在后面。 */
    private static final Set<String> PREFIX = Set.of("WITH", "RECURSIVE", "EXPLAIN", "ANALYZE");

    /** 表名 + 备份后缀只允许这些字符：备份语句是拼出来的，必须 fail-closed。 */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_$.]+$");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    /** 007 的执行通道只有 Doris 与 Spark。 */
    private static final Set<String> EXECUTABLE_TYPES = Set.of("DORIS", "SPARK");

    private ChangeRules() {
    }

    public enum BackupKind {
        /** 不是 DROP/TRUNCATE 单表，不需要备份。 */
        NOT_NEEDED,
        /** 需要在执行前备份这一张表。 */
        REQUIRED,
        /** 是破坏性单表语句，但 v1 无法安全备份（多表等）——拒绝执行。 */
        UNSUPPORTED
    }

    public record BackupPlan(BackupKind kind, String table, String message) {
        static BackupPlan none() {
            return new BackupPlan(BackupKind.NOT_NEEDED, null, null);
        }

        static BackupPlan required(String table) {
            return new BackupPlan(BackupKind.REQUIRED, table, null);
        }

        static BackupPlan unsupported(String message) {
            return new BackupPlan(BackupKind.UNSUPPORTED, null, message);
        }
    }

    /** 执行通道是否支持该数据源类型（007：Doris / Spark 两条）。 */
    public static boolean executable(String dsType) {
        return dsType != null && EXECUTABLE_TYPES.contains(dsType.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 按顶层分号拆句，**保留原文**（注释、字符串原样带出，用于执行）。
     *
     * <p>与 {@link SqlGuard} 的拆句不同：那边为了判断会去掉字符串内容，
     * 这里要拿去执行，字符串里的分号不能被当成语句分隔。</p>
     */
    public static List<String> statements(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = sql.length();
                }
                cur.append(sql, i, end);
                i = end - 1;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? sql.length() : end + 2;
                cur.append(sql, i, end);
                i = end - 1;
            } else if (c == '\'') {
                int end = skipQuoted(sql, i, '\'');
                cur.append(sql, i, Math.min(end, sql.length()));
                i = end - 1;
            } else if (c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                cur.append(sql, i, Math.min(end, sql.length()));
                i = end - 1;
            } else {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                }
                if (c == ';' && depth == 0) {
                    addIfContent(out, cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        addIfContent(out, cur.toString());
        return out;
    }

    /** 语句动词（跳过 WITH/EXPLAIN/ANALYZE 前缀），识别不了返回 null。 */
    public static String verb(String stmt) {
        List<String> words = words(masked(stmt));
        int i = 0;
        while (i < words.size() && PREFIX.contains(words.get(i))) {
            i++;
        }
        while (i < words.size() && !VERBS.contains(words.get(i))) {
            i++;
        }
        return i < words.size() ? words.get(i) : null;
    }

    /**
     * 这条语句执行前要不要备份、备份哪张表。
     *
     * <p>007 只承诺「DROP / TRUNCATE 单表」；多表 DROP 无法保证每张都备份，
     * 这里明确拒绝执行，而不是悄悄只备份第一张。</p>
     */
    public static BackupPlan backupPlan(String stmt) {
        if (!BACKUP_VERBS.contains(verb(stmt))) {
            return BackupPlan.none();
        }
        List<String> tables = SqlGuard.check(stmt).tables();
        if (tables.size() != 1) {
            return BackupPlan.unsupported(
                    "DROP/TRUNCATE 无法确定唯一的表（解析到 " + tables.size() + " 张），"
                            + "v1 只支持单表自动备份，请拆成多条变更单");
        }
        String table = tables.get(0);
        if (!IDENT.matcher(table).matches()) {
            return BackupPlan.unsupported("表名含不支持的字符，拒绝自动备份: " + table);
        }
        if ("DROP".equals(verb(stmt)) && hasTopLevelCommaAfterTable(stmt)) {
            return BackupPlan.unsupported(
                    "DROP 一次多表（含逗号），v1 只支持单表自动备份，请拆成多条变更单");
        }
        return BackupPlan.required(table);
    }

    /** 备份表名：{@code db.t} → {@code db.t_bak_20261026153000}。 */
    public static String backupName(String table, LocalDateTime at) {
        if (table == null || !IDENT.matcher(table).matches()) {
            throw new IllegalArgumentException("表名不能用于备份: " + table);
        }
        return table + "_bak_" + at.format(BACKUP_TS);
    }

    /**
     * 审批前置判定（纯函数）：返回 null 表示可以继续，否则是拒绝原因。
     * 007 第七节：提交人不能审自己的单。
     */
    public static String reviewGuard(String requester, String actor, String status) {
        if (requester != null && requester.equals(actor)) {
            return "不能审批自己提交的变更单";
        }
        if (!"PENDING".equals(status)) {
            return "该变更单已经处理过了";
        }
        return null;
    }

    // ------------------------------------------------------- D53：回滚方案（纯函数）

    /**
     * 提交时 DROP/TRUNCATE 的回滚 SQL 里的占位符：备份表名要到审批执行时才产生，
     * 执行成功后由 ChangeService 用真实备份表名重算回填。
     * 回滚入口检测到该占位符一律 400（执行未成功完成，不许回滚）。
     */
    public static final String BACKUP_PLACEHOLDER = "<备份表·执行后回填>";

    /** 平台备份表名：{@code db.t_bak_20261026153000}（清理接口只认这个形态）。 */
    private static final Pattern BACKUP_NAME = Pattern.compile("^(.+)_bak_(\\d{14})$");

    /** ALTER TABLE <表> <操作…>；表名只认裸标识符，带引号/反引号一律 fail-closed。 */
    private static final Pattern ALTER_HEAD = Pattern.compile(
            "(?is)^alter\\s+table\\s+(?:if\\s+exists\\s+)?"
                    + "([A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*)\\s*(.*)$");

    /** CREATE TABLE <表>（含 IF NOT EXISTS / AS SELECT）；非 TABLE 的 CREATE 不支持。 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?"
                    + "([A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*)\\b.*$");

    /** ADD [COLUMN] 前缀（group(1) 非空 = 带 COLUMN 关键字；列清单/列定义在其后）。 */
    private static final Pattern ADD_PREFIX = Pattern.compile("(?i)^add\\s+(column\\s+)?");

    private static final Pattern IF_NOT_EXISTS = Pattern.compile("(?i)^if\\s+not\\s+exists\\s+");

    /** 合法列名（反向 DROP COLUMN 是拼出来的，必须 fail-closed）。 */
    private static final Pattern COLUMN_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");

    /** ADD 后面跟这些词不是加列（索引/约束等），v1 不支持反向生成。 */
    private static final Set<String> NOT_COLUMN = Set.of(
            "CONSTRAINT", "PRIMARY", "KEY", "INDEX", "UNIQUE", "FOREIGN",
            "PARTITION", "ROLLUP", "DUPLICATE", "AGGREGATE");

    /** 单条语句的反向方案。inverseSql 为 null 且 irreversible=false 表示无回滚语义。 */
    public record InversePlan(String inverseSql, boolean irreversible, String note) {
        static InversePlan none() {
            return new InversePlan(null, false, null);
        }

        static InversePlan of(String inverseSql, String note) {
            return new InversePlan(inverseSql, false, note);
        }

        static InversePlan irreversible(String note) {
            return new InversePlan(null, true, note);
        }
    }

    /**
     * 整个变更单的回滚方案。
     *
     * @param inverseSql   逆序拼接的回滚 SQL（无任何可反向语句时为 null）
     * @param irreversible 任一句不可自动还原即整单不可逆（审批前必须勾选「已知不可逆」）
     * @param note         需要人工确认/注意的说明（分号拼接，可为 null）
     */
    public record RollbackPlan(String inverseSql, boolean irreversible, String note) {
    }

    /**
     * 单条语句的反向方案（纯函数，便于单测）。
     *
     * <p>D53 口径：</p>
     * <ul>
     *   <li>{@code ALTER TABLE … ADD COLUMN} → {@code ALTER TABLE … DROP COLUMN <同名>}；</li>
     *   <li>{@code CREATE TABLE} → {@code DROP TABLE <同名>}（标注「需确认」）；</li>
     *   <li>{@code TRUNCATE} 单表（已备份）→ {@code INSERT INTO <表> SELECT * FROM <备份表>}；
     *       {@code DROP} 单表另在回灌前补 {@code CREATE TABLE IF NOT EXISTS <表> LIKE <备份表>}
     *       （表结构已随 DROP 删除，只 INSERT 必然失败）；
     *       备份表名未产生时用 {@link #BACKUP_PLACEHOLDER} 占位；</li>
     *   <li>{@code ALTER … DROP / MODIFY / CHANGE COLUMN} 及 DELETE/UPDATE/MERGE/INSERT
     *       → irreversible（数据无法还原）；</li>
     *   <li>识别不了的形态一律 fail-closed 按 irreversible 处理，宁多勾一次不可少勾。</li>
     * </ul>
     *
     * @param backupTable 该语句执行时产生的备份表名（未执行传 null）
     */
    public static InversePlan inversePlan(String stmt, String backupTable) {
        String verb = verb(stmt);
        if (verb == null) {
            return InversePlan.none();
        }
        switch (verb) {
            case "DROP":
            case "TRUNCATE":
                return inverseDropTruncate(stmt, verb, backupTable);
            case "CREATE":
                return inverseCreate(stmt);
            case "ALTER":
                return inverseAlter(stmt);
            case "DELETE":
            case "UPDATE":
            case "MERGE":
                return InversePlan.irreversible(
                        verb + " 的数据修改无法自动还原（v1 不为该类语句备份）");
            case "INSERT":
            case "REPLACE":
                return InversePlan.irreversible(verb + " 写入的数据无法自动撤销");
            default:
                // SELECT / SHOW / SET / USE / GRANT 等无数据破坏语义，无回滚动作
                return InversePlan.none();
        }
    }

    /**
     * 整个变更单的回滚方案：逐句生成、**逆序**拼接（先撤销最后执行的那句）。
     *
     * @param backupTables 执行时按语句顺序产生的备份表名；提交阶段传空列表，
     *                     DROP/TRUNCATE 的回滚 SQL 用 {@link #BACKUP_PLACEHOLDER} 占位
     */
    public static RollbackPlan rollbackPlan(String sql, List<String> backupTables) {
        List<String> inverses = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean irreversible = false;
        int backupIdx = 0;
        for (String stmt : statements(sql)) {
            String backup = null;
            if (backupPlan(stmt).kind() == BackupKind.REQUIRED
                    && backupTables != null && backupIdx < backupTables.size()) {
                backup = backupTables.get(backupIdx++);
            }
            InversePlan plan = inversePlan(stmt, backup);
            if (plan.irreversible()) {
                irreversible = true;
            } else if (plan.inverseSql() != null) {
                inverses.add(plan.inverseSql());
            }
            if (plan.note() != null && !plan.note().isBlank()) {
                notes.add(plan.note());
            }
        }
        Collections.reverse(inverses);
        return new RollbackPlan(
                inverses.isEmpty() ? null : String.join(";\n", inverses),
                irreversible,
                notes.isEmpty() ? null : String.join("；", notes));
    }

    /** 是否平台生成的备份表名（清理接口只允许 DROP 这种表，防变成任意删表通道）。 */
    public static boolean isBackupName(String table) {
        return table != null
                && IDENT.matcher(table).matches()
                && BACKUP_NAME.matcher(table).matches();
    }

    /** 从备份表名解析备份时间；解析不出返回 null。 */
    public static LocalDateTime backupTime(String backupName) {
        if (backupName == null) {
            return null;
        }
        Matcher matcher = BACKUP_NAME.matcher(backupName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDateTime.parse(matcher.group(2), BACKUP_TS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 备份表名 → 源表名（去掉 {@code _bak_<时间戳>} 后缀）；不是备份名则原样返回。 */
    public static String sourceOf(String backupName) {
        if (backupName == null) {
            return null;
        }
        Matcher matcher = BACKUP_NAME.matcher(backupName);
        return matcher.matches() ? matcher.group(1) : backupName;
    }

    // ------------------------------------------------- D53 反向生成的内部实现

    private static InversePlan inverseDropTruncate(String stmt, String verb, String backupTable) {
        BackupPlan plan = backupPlan(stmt);
        if (plan.kind() != BackupKind.REQUIRED) {
            return InversePlan.irreversible(plan.message() != null ? plan.message()
                    : "无法确定唯一的表，不能生成回滚 SQL");
        }
        String table = plan.table();
        String backup = backupTable == null || backupTable.isBlank()
                ? BACKUP_PLACEHOLDER : backupTable;
        if ("TRUNCATE".equals(verb)) {
            // TRUNCATE 后表结构还在，直接回灌
            return InversePlan.of("INSERT INTO " + table + " SELECT * FROM " + backup, null);
        }
        // DROP 后表结构也没了：只 INSERT 必然失败，先按备份表重建结构再回灌。
        // 备份表本身是 CTAS 产物（DUPLICATE 模型），重建出的结构与原表键模型可能不同——
        // 这是 v1 备份机制（CREATE TABLE … AS SELECT）的既有上限，note 里说清楚。
        return InversePlan.of(
                "CREATE TABLE IF NOT EXISTS " + table + " LIKE " + backup + ";\n"
                        + "INSERT INTO " + table + " SELECT * FROM " + backup,
                "表结构已随 DROP 删除，回滚先按备份表重建结构（CTAS 备份为 DUPLICATE 模型，"
                        + "与原表键模型可能不同）再回灌数据");
    }

    private static InversePlan inverseCreate(String stmt) {
        Matcher matcher = CREATE_TABLE.matcher(masked(stmt).trim());
        if (!matcher.matches()) {
            return InversePlan.irreversible("非 CREATE TABLE 或格式无法识别，不能生成回滚 SQL");
        }
        return InversePlan.of("DROP TABLE " + matcher.group(1), "回滚将删除整张表，需确认");
    }

    private static InversePlan inverseAlter(String stmt) {
        Matcher matcher = ALTER_HEAD.matcher(masked(stmt).trim());
        if (!matcher.matches()) {
            return InversePlan.irreversible("无法识别的 ALTER TABLE 格式，不能生成回滚 SQL");
        }
        String table = matcher.group(1);
        String rest = matcher.group(2).trim();
        if (!IDENT.matcher(table).matches() || rest.isEmpty()) {
            return InversePlan.irreversible("无法识别的 ALTER TABLE 格式，不能生成回滚 SQL");
        }
        List<String> drops = new ArrayList<>();
        for (String op : splitTopLevelCommas(rest)) {
            if (op.isBlank()) {
                continue;
            }
            List<String> words = words(op);
            if (words.isEmpty()) {
                return InversePlan.irreversible("无法识别的 ALTER 操作，不能生成回滚 SQL");
            }
            String head = words.get(0);
            if ("ADD".equals(head)) {
                List<String> columns = addedColumns(op);
                if (columns == null) {
                    return InversePlan.irreversible(
                            "无法识别的 ADD COLUMN 格式（或不是加列），不能生成回滚 SQL: "
                                    + op.trim());
                }
                for (String column : columns) {
                    drops.add("ALTER TABLE " + table + " DROP COLUMN " + column);
                }
            } else if ("DROP".equals(head)) {
                return InversePlan.irreversible(words.size() > 1 && "COLUMN".equals(words.get(1))
                        ? "DROP COLUMN 的数据无法还原"
                        : "ALTER DROP " + (words.size() > 1 ? words.get(1) : "")
                          + " 不支持自动回滚");
            } else if ("MODIFY".equals(head) || "CHANGE".equals(head)) {
                return InversePlan.irreversible(head + " COLUMN 的原值未备份，数据无法还原");
            } else {
                return InversePlan.irreversible(
                        "ALTER " + head + " 不支持自动回滚（无法生成反向语句）");
            }
        }
        if (drops.isEmpty()) {
            return InversePlan.irreversible("ALTER 未识别到任何操作，不能生成回滚 SQL");
        }
        return InversePlan.of(String.join(";\n", drops), null);
    }

    /** 解析 ADD COLUMN 的列名；不是「加列」或解析不出来返回 null（fail-closed）。 */
    private static List<String> addedColumns(String op) {
        String body = op.trim();
        Matcher add = ADD_PREFIX.matcher(body);
        if (!add.find()) {
            return null;
        }
        boolean hasColumnKeyword = add.group(1) != null;
        String after = IF_NOT_EXISTS.matcher(body.substring(add.end()).trim()).replaceFirst("");
        List<String> columns = new ArrayList<>();
        if (after.startsWith("(")) {
            // 列清单形态 ADD COLUMN (a INT, b VARCHAR(10))：必须带 COLUMN 关键字，
            // 否则可能是 ADD INDEX/KEY (…) 等，v1 不支持反向
            if (!hasColumnKeyword) {
                return null;
            }
            int close = matchingParen(after, 0);
            if (close < 0 || !after.substring(close + 1).isBlank()) {
                return null;
            }
            for (String part : splitTopLevelCommas(after.substring(1, close))) {
                String name = firstWord(part);
                if (name == null) {
                    return null;
                }
                columns.add(name);
            }
        } else {
            // ADD COLUMN c INT [NULL] [DEFAULT …]：第一个词就是列名
            String name = firstWord(after);
            if (name == null) {
                return null;
            }
            columns.add(name);
        }
        for (String name : columns) {
            if (!COLUMN_NAME.matcher(name).matches()
                    || NOT_COLUMN.contains(name.toUpperCase(Locale.ROOT))) {
                return null;
            }
        }
        return columns.isEmpty() ? null : columns;
    }

    /** 第一个单词（保留原文大小写）；没有返回 null。 */
    private static String firstWord(String text) {
        Matcher matcher = WORD.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** 顶层逗号拆操作（括号内的逗号不拆，如 DECIMAL(10,2)）。 */
    private static List<String> splitTopLevelCommas(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 与 open 处左括号配对的右括号下标；未闭合返回 -1。 */
    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 内部

    /** 去注释 + 去字符串字面量，保留其余字符（只用于判断）。 */
    static String masked(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() - 1 : end - 1;
                out.append(' ');
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() - 1 : end + 1;
                out.append(' ');
            } else if (c == '\'') {
                i = Math.min(skipQuoted(sql, i, '\''), sql.length()) - 1;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** DROP TABLE a, b —— TABLE 关键字之后、括号外出现逗号即视为多表。 */
    private static boolean hasTopLevelCommaAfterTable(String stmt) {
        String text = masked(stmt).toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("\\btable\\b").matcher(text);
        if (!matcher.find()) {
            return false;
        }
        int depth = 0;
        for (int i = matcher.end(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    /** 返回闭引号之后的下标（未闭合时返回 sql.length()）。 */
    private static int skipQuoted(String sql, int start, char quote) {
        for (int j = start + 1; j < sql.length(); j++) {
            char d = sql.charAt(j);
            if (d == '\\') {
                j++;
            } else if (d == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j++;
                } else {
                    return j + 1;
                }
            }
        }
        return sql.length();
    }

    private static void addIfContent(List<String> out, String stmt) {
        if (stmt != null && !masked(stmt).isBlank()) {
            out.add(stmt.trim());
        }
    }
}
