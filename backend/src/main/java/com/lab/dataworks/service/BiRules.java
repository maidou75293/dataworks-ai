package com.lab.dataworks.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BI 看板的纯逻辑：SQL 命名参数扫描、参数声明校验、发布版本号推进。
 *
 * <p>为什么不直接用 {@link DataServiceController#rewrite}：那个方法服务执行期，
 * 会把 SQL 里所有 {@code :name} 变成占位符；BI 保存/查看时需要在<b>不连数据库</b>的前提下
 * 判断「SQL 用到的参数是否都在看板参数列表里声明」。未声明的参数以前会被静默绑定 NULL，
 * 查询看起来成功但结果为空，很难排查。</p>
 *
 * <p>扫描规则与 rewrite 保持一致：跳过单/双引号字符串、{@code ::} 类型转换；
 * 额外跳过 {@code --} / {@code #} / {@code /* *}{@code /} 注释，避免注释里的冒号被当成参数。</p>
 */
final class BiRules {

    private BiRules() {
    }

    /**
     * D22：看板数据接口的 {@code env} 只认 DEV / PRD（大小写不敏感，缺省 PRD）。
     *
     * <p>以前 {@code env=zzz} 会被静默当成 PRD 执行：调用方以为读的是开发态，
     * 实际拿到生产快照（未发布时还直接 403），排查方向被带偏。非法值现在 400。</p>
     *
     * @return 规范化后的大写 env（DEV / PRD）
     */
    static String envOf(String raw) {
        String value = raw == null || raw.isBlank()
                ? "PRD" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"DEV".equals(value) && !"PRD".equals(value)) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "不支持的 env: " + raw.trim() + "（可选 DEV / PRD）");
        }
        return value;
    }

    /**
     * 返回 SQL 里出现的命名参数（按首次出现顺序去重）。
     * 字符串与注释里的 {@code :name} 不算。
     */
    static List<String> namedParams(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                i = skipLine(sql, i);
                continue;
            }
            if (c == '#') {
                i = skipLine(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                continue;
            }
            if (c == '$') {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > i) {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    i = close < 0 ? length : close + tag.length();
                    continue;
                }
            }
            if (c == ':') {
                boolean cast = i + 1 < length && sql.charAt(i + 1) == ':';
                boolean prevColon = i > 0 && sql.charAt(i - 1) == ':';
                if (!cast && !prevColon && i + 1 < length
                        && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                    int j = i + 1;
                    while (j < length
                            && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                        j++;
                    }
                    seen.add(sql.substring(i + 1, j));
                    i = j;
                    continue;
                }
            }
            i++;
        }
        out.addAll(seen);
        return out;
    }

    /** {@code used} 里没有在 {@code declared} 中声明的参数名。 */
    static List<String> undeclared(List<String> used, Set<String> declared) {
        List<String> out = new ArrayList<>();
        for (String name : used == null ? List.<String>of() : used) {
            if (name != null && (declared == null || !declared.contains(name))) {
                out.add(name);
            }
        }
        return out;
    }

    /** 调用方传了但看板没有声明的参数名；拼写错误应当 400，而不是静默忽略。 */
    static List<String> unknownKeys(Set<String> keys, Set<String> declared) {
        List<String> out = new ArrayList<>();
        for (String key : keys == null ? Set.<String>of() : keys) {
            if (key != null && (declared == null || !declared.contains(key))) {
                out.add(key);
            }
        }
        return out;
    }

    /** 发布版本号：首次发布 v1，之后在最新成功版本上 +1。 */
    static int nextVersion(Integer latest) {
        return latest == null ? 1 : latest + 1;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    private static int skipLine(String sql, int start) {
        int i = start;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int dollarTagEnd(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '$') {
                return i;
            }
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return -1;
            }
            i++;
        }
        return -1;
    }
}
