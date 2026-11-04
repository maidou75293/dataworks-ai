package com.lab.dataworks.udf;

import java.util.regex.Pattern;

/**
 * UDF 引用扫描的纯逻辑：把「不是代码」的部分（字符串字面量、注释）抹掉后再找函数调用。
 *
 * <p>背景：{@link UdfService#checkUsage} 与 {@link UdfService#flinkPreamble} 直接用正则
 * 匹配 {@code p1_xxx(}。SQL 注释里写「参考 p1_xxx(...)」或字符串里出现同样文本时，
 * 会被误判成「使用了函数」：查询被 403/400 拒绝，Flink 作业也会多注入无关依赖。
 * 这里统一做一次去字面量处理，保持双引号/反引号标识符（它们可能是真正的函数名）。</p>
 *
 * <p>纯静态、无状态，便于单测；不依赖 Spring / 数据库。</p>
 */
final class UdfSqlRules {

    private UdfSqlRules() {
    }

    /**
     * 用空格替换字符串与注释，保留可执行 SQL 结构。
     *
     * <p>处理：单引号字符串（含 {@code ''} 与反斜杠转义）、{@code --} / {@code #} 行注释、
     * {@code /* *}{@code /} 块注释、PostgreSQL 美元引用字符串（{@code $$..$$} / {@code $tag$..$tag$}）。
     * 双引号与反引号是标识符，原样保留，避免漏掉 {@code `p1_fadd`(...)} 这种写法。</p>
     */
    static String maskNonCode(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // 单引号字符串：抹到闭合引号；'' 表示转义的单引号，继续留在字符串里
                out.append(' ');
                i++;
                while (i < length) {
                    char d = sql.charAt(i);
                    if (d == '\\') {
                        out.append(' ');
                        i++;
                        if (i < length) {
                            out.append(' ');
                            i++;
                        }
                        continue;
                    }
                    if (d == '\'') {
                        out.append(' ');
                        i++;
                        if (i < length && sql.charAt(i) == '\'') {
                            out.append(' ');
                            i++;
                            continue;
                        }
                        break;
                    }
                    out.append(d == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                i = maskLine(sql, i, out);
                continue;
            }
            if (c == '#') {
                i = maskLine(sql, i, out);
                continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                out.append(' ').append(' ');
                i += 2;
                while (i < length) {
                    if (sql.charAt(i) == '*' && i + 1 < length && sql.charAt(i + 1) == '/') {
                        out.append(' ').append(' ');
                        i += 2;
                        break;
                    }
                    out.append(sql.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            if (c == '$') {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > i) {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    int stop = close < 0 ? length : close + tag.length();
                    while (i < stop) {
                        out.append(sql.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** SQL 里是否真的调用了 {@code fullName}（忽略大小写、允许反引号/双引号包裹）。 */
    static boolean calls(String sql, String fullName) {
        if (sql == null || fullName == null || fullName.isBlank()) {
            return false;
        }
        String code = maskNonCode(sql);
        // 前面不能紧贴标识符字符：避免把 xp1_fadd( 这样的长标识符当成 p1_fadd 调用。
        return Pattern.compile("(?i)(?<![A-Za-z0-9_$])[`\"]?"
                        + Pattern.quote(fullName) + "[`\"]?\\s*\\(")
                .matcher(code).find();
    }

    private static int maskLine(String sql, int start, StringBuilder out) {
        int i = start;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            out.append(' ');
            i++;
        }
        // 换行保留，便于后续匹配跨行（函数调用通常不跨行，但保留结构更接近原文）
        if (i < sql.length()) {
            out.append('\n');
            i++;
        }
        return i;
    }

    /** 从 {@code $} 开始识别美元引用标签，返回结束 {@code $} 的下标；不是标签返回 -1。 */
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
