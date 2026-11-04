package com.lab.dataworks.udf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UDF 引用扫描的纯逻辑：字符串字面量与注释里的函数名不算调用。
 *
 * <p>对应 {@link UdfService#checkUsage} / {@link UdfService#flinkPreamble} 的误判修复：
 * 注释或字符串里出现 {@code p1_fadd(} 时不应 403/400，也不应额外注入 Flink 依赖。</p>
 */
class UdfSqlRulesTest {

    @Test
    void callInsideCommentsOrStringLiteralsIsIgnored() {
        String sql = """
                -- 参考 p1_fadd(1)，这里只是注释
                /* 也不要匹配 p1_mul(2) */
                select 'p1_div(3)' as s, 'it''s p1_mod(' as t, $$p1_abs(4)$$ as u
                  from tbl
                """;
        assertFalse(UdfSqlRules.calls(sql, "p1_fadd"));
        assertFalse(UdfSqlRules.calls(sql, "p1_mul"));
        assertFalse(UdfSqlRules.calls(sql, "p1_div"));
        assertFalse(UdfSqlRules.calls(sql, "p1_mod"));
        assertFalse(UdfSqlRules.calls(sql, "p1_abs"));
        // MySQL 的 # 行注释同样跳过
        assertFalse(UdfSqlRules.calls("select 1 # p1_fadd(\n from t", "p1_fadd"));
    }

    @Test
    void realCallsMatchAndMaskingKeepsIdentifiers() {
        assertTrue(UdfSqlRules.calls("select p1_fadd(id) from t", "p1_fadd"));
        assertTrue(UdfSqlRules.calls("select `P1_FADD`(id) from t", "p1_fadd"));
        assertTrue(UdfSqlRules.calls("select \"p1_fadd\" (id) from t", "p1_fadd"));
        // 长标识符里包含函数名不算调用
        assertFalse(UdfSqlRules.calls("select xp1_fadd(id) from t", "p1_fadd"));

        // 双引号/反引号是标识符，必须保留；注释内容被抹掉。
        String masked = UdfSqlRules.maskNonCode(
                "select \"col\", `p1_add`(1) -- tail\nfrom t");
        assertTrue(masked.contains("\"col\""));
        assertTrue(masked.contains("`p1_add`(1)"));
        assertFalse(masked.contains("tail"));
    }
}
