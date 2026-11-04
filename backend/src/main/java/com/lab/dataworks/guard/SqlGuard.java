package com.lab.dataworks.guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v1 破坏性 SQL 判定（关键词级，不引 Druid / JSqlParser）。
 *
 * 取语句动词；命中文档中的破坏性动词（或含 OVERWRITE）即需要审批，
 * 若该句涉及的表名全部是 tmp_ / temp_ / tmp 前缀则直接放行。</p>
 *
 * <p>只做判断，不执行、不落库；调用方用 {@link Result#needReview()} 决定是否建变更单。</p>
 */
public final class SqlGuard {

    /** 需要人工审批的语句动词 */
    private static final Set<String> REVIEW_VERBS =
            Set.of("DROP", "TRUNCATE", "ALTER", "DELETE", "UPDATE", "MERGE");

    /** 识别语句动词时认得的词，避免把 WITH 后面的 CTE 名当成动词 */
    private static final Set<String> VERBS = Set.of(
            "DROP", "TRUNCATE", "ALTER", "DELETE", "UPDATE", "MERGE",
            "SELECT", "INSERT", "CREATE", "REPLACE", "ANALYZE", "SHOW",
            "DESC", "DESCRIBE", "USE", "SET", "CALL", "OPTIMIZE", "GRANT", "REVOKE");

    /** WITH / EXPLAIN / ANALYZE 只是前置词，真正的动词在后面 */
    private static final Set<String> PREFIX = Set.of("WITH", "RECURSIVE", "EXPLAIN", "ANALYZE");

    /** 表名前的限定词 */
    private static final Set<String> TABLE_MARKERS = Set.of("TABLE", "FROM", "INTO", "UPDATE");

    /** 限定词与表名之间的噪音词 */
    private static final Set<String> NOISE = Set.of(
            "IF", "NOT", "EXISTS", "ONLY", "TABLE", "TEMPORARY", "TEMP",
            "UNLOGGED", "LOW_PRIORITY", "IGNORE", "QUICK");

    private SqlGuard() {
    }

    public record Result(boolean needReview, List<String> tables, String reason) {
        public Result {
            tables = List.copyOf(tables);
        }
    }

    public static Result check(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Result(false, List.of(), "空 SQL");
        }
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        String hit = null;
        for (String stmt : splitStatements(sql)) {
            List<Tok> toks = tokens(stmt);
            if (toks.isEmpty()) {
                continue;
            }
            String verb = verb(toks);
            boolean overwrite = toks.stream().anyMatch(t -> "OVERWRITE".equals(t.upper()));
            if (overwrite) {
                hit = "OVERWRITE";
            } else if (verb != null && REVIEW_VERBS.contains(verb)) {
                hit = verb;
            }
            tables.addAll(tables(toks));
        }
        if (hit == null) {
            return new Result(false, List.copyOf(tables), "非破坏性语句");
        }
        if (!tables.isEmpty() && tables.stream().allMatch(SqlGuard::isTmp)) {
            return new Result(false, List.copyOf(tables), "tmp 表放行");
        }
        return new Result(true, List.copyOf(tables), "破坏性语句需审批：" + hit);
    }

    /** 去注释/去字符串后按顶层分号拆句；引号标识符只去引号、保留内容 */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                cur.append(' ');
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 1;
                cur.append(' ');
            } else if (c == '\'') {
                cur.append(' ');
                i = skipLiteral(sql, i);
            } else if (c == '"' || c == '`') {
                int j = i + 1;
                for (; j < sql.length(); j++) {
                    char d = sql.charAt(j);
                    if (d == c) {
                        if (j + 1 < sql.length() && sql.charAt(j + 1) == c) {
                            cur.append(c);
                            j++;
                            continue;
                        }
                        break;
                    }
                    cur.append(d);
                }
                i = j;
            } else {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                }
                if (c == ';' && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 跳过 '...'（'' 与反斜杠转义），返回闭引号位置 */
    private static int skipLiteral(String s, int start) {
        for (int j = start + 1; j < s.length(); j++) {
            char d = s.charAt(j);
            if (d == '\\') {
                j++;
            } else if (d == '\'') {
                if (j + 1 < s.length() && s.charAt(j + 1) == '\'') {
                    j++;
                } else {
                    return j;
                }
            }
        }
        return s.length() - 1;
    }

    private record Tok(String raw, String upper, int depth) {
    }

    private static List<Tok> tokens(String stmt) {
        List<Tok> out = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < stmt.length(); ) {
            char c = stmt.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                i++;
            } else if (isIdent(c)) {
                int j = i;
                while (j < stmt.length() && isIdent(stmt.charAt(j))) {
                    j++;
                }
                String raw = stmt.substring(i, j);
                out.add(new Tok(raw, raw.toUpperCase(Locale.ROOT), depth));
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }

    /** 语句动词：跳过 WITH/EXPLAIN/ANALYZE 前缀，取第一个顶层已知动词 */
    private static String verb(List<Tok> toks) {
        int i = 0;
        while (i < toks.size() && PREFIX.contains(toks.get(i).upper())) {
            i++;
        }
        while (i < toks.size()
                && (toks.get(i).depth() != 0 || !VERBS.contains(toks.get(i).upper()))) {
            i++;
        }
        return i < toks.size() ? toks.get(i).upper() : null;
    }

    /** 提表名：TABLE / FROM / INTO / UPDATE 后第一个标识符 */
    private static List<String> tables(List<Tok> toks) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            Tok tok = toks.get(i);
            if (tok.depth() != 0 || !TABLE_MARKERS.contains(tok.upper())) {
                continue;
            }
            int j = i + 1;
            while (j < toks.size() && toks.get(j).depth() == 0 && NOISE.contains(toks.get(j).upper())) {
                j++;
            }
            if (j < toks.size() && toks.get(j).depth() == 0 && !VERBS.contains(toks.get(j).upper())) {
                out.add(toks.get(j).raw());
            }
            i = j;
        }
        return out;
    }

    /** tmp 前缀白名单（inbox/008 口径：tmp_ / temp_ / tmp） */
    private static boolean isTmp(String table) {
        String t = table.toLowerCase(Locale.ROOT);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        return t.startsWith("tmp_") || t.startsWith("temp_") || t.startsWith("tmp");
    }
}
