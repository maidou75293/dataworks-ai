package com.lab.dataworks.common;

/**
 * 列表搜索框的 LIKE 模式。
 *
 * <p>用户在搜索框里输入的 {@code %}、{@code _} 是普通字符，不能当成通配符：
 * 之前实测 {@code /api/users?keyword=%} 返回全部用户，{@code _} 也会吞掉任意单字符。
 * PostgreSQL 的 LIKE 默认转义符是反斜杠，所以这里把 {@code \}、{@code %}、{@code _}
 * 逐一加反斜杠即可，不需要额外的 ESCAPE 子句。
 */
public final class LikePattern {

    private LikePattern() {
    }

    /** 把用户输入转成「包含」语义的 LIKE 模式；null/空白返回 null。 */
    public static String contains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
