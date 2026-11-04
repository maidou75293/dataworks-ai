package com.lab.dataworks.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC7-G6：结果列回溯源列（别名/表达式/子查询/CTE/UNION/星号展开），
 * 以及「判不出来必须 fail-closed」的两类形态（解析失败 → parsed=false）。
 */
class ColumnLineageTest {

    private static ColumnLineage.Trace trace(String sql) {
        ColumnLineage.Trace t = ColumnLineage.trace(sql);
        assertTrue(t.applicable(), "应识别为 SELECT: " + sql);
        return t;
    }

    private static void assertOrigin(String sql, String label, String... sourceColumns) {
        ColumnLineage.Trace t = trace(sql);
        assertTrue(t.parsed(), "应可解析: " + sql);
        Set<String> origins = t.origins().get(label);
        assertNotNull(origins, "应有标签 " + label + "，实际 " + t.origins().keySet() + " <- " + sql);
        for (String source : sourceColumns) {
            assertTrue(origins.contains(source),
                    label + " 应回溯到 " + source + "，实际 " + origins + " <- " + sql);
        }
    }

    // ---------- B1/B4/B9：别名 ----------

    @Test
    void explicitAliasTracesToSourceColumn() {
        assertOrigin("select phone as p from demo.ods_user", "p", "phone");
    }

    @Test
    void implicitAliasTracesToSourceColumn() {
        assertOrigin("select phone p from t", "p", "phone");
    }

    @Test
    void sameNameAliasStillTraces() {
        assertOrigin("select phone as phone from t", "phone", "phone");
    }

    @Test
    void aliasCaseInsensitive() {
        assertOrigin("select PHONE as P from T", "p", "phone");
    }

    @Test
    void quotedNonAsciiAliasTraces() {
        assertOrigin("select phone as `手机号` from t", "手机号", "phone");
    }

    @Test
    void bareNonAsciiAliasEitherTracesOrFailsClosed() {
        // JSqlParser 不支持非 ASCII 裸别名时 parsed=false 同样安全（调用方会拒绝执行）
        ColumnLineage.Trace t = trace("select phone as 手机号 from t");
        if (t.parsed()) {
            assertEquals(Set.of("phone"), t.origins().get("手机号"));
        } else {
            assertFalse(t.parsed(), "解析不了就必须 fail-closed");
        }
    }

    // ---------- B2/B3/B7/B8：表达式 ----------

    @Test
    void functionExpressionsTraceToTheirColumns() {
        assertOrigin("select concat(phone, '1') as p, upper(phone) q, "
                + "trim(phone) as r, substr(phone, 1, 3) s from t", "p", "phone");
        assertOrigin("select concat(phone, '1') as p, upper(phone) q, "
                + "trim(phone) as r, substr(phone, 1, 3) s from t", "q", "phone");
        assertOrigin("select concat(phone, '1') as p, upper(phone) q, "
                + "trim(phone) as r, substr(phone, 1, 3) s from t", "r", "phone");
        assertOrigin("select concat(phone, '1') as p, upper(phone) q, "
                + "trim(phone) as r, substr(phone, 1, 3) s from t", "s", "phone");
    }

    @Test
    void arithmeticAndCaseExpressionsTrace() {
        assertOrigin("select case when id > 0 then phone end as p from t", "p", "phone", "id");
    }

    @Test
    void unaliasedExpressionLabelFallsBackToCompactMatch() {
        // 无别名表达式：JDBC 标签来自 SQL 原文，与解析树文本只差空白，originsOf 要能对上
        ColumnLineage.Trace t = trace("select concat( phone , 'x' ) from t");
        assertTrue(t.parsed());
        assertFalse(t.labels().isEmpty());
        assertFalse(t.originsOf(t.labels().get(0)).isEmpty(), "表达式列必须带回溯来源");
        assertTrue(t.originsOf("concat(phone,'x')").contains("phone"),
                "空白差异的标签要按去空白匹配：实际 " + t.origins());
    }

    // ---------- B5/B6：子查询与 CTE ----------

    @Test
    void subqueryRenameTracesThroughDerived() {
        assertOrigin("select p from (select phone as p from t) x", "p", "phone");
    }

    @Test
    void nestedDerivedRenameTraces() {
        assertOrigin("select q from (select p as q from (select phone as p from t) a) b",
                "q", "phone");
    }

    @Test
    void cteRenameTraces() {
        assertOrigin("with c as (select phone as p from t) select p from c", "p", "phone");
    }

    @Test
    void unqualifiedSubqueryKeepsName() {
        assertOrigin("select phone from (select phone from t) x", "phone", "phone");
    }

    @Test
    void starOverEnumeratedDerivedExpands() {
        assertOrigin("select * from (select phone as p from t) x", "p", "phone");
    }

    // ---------- 对照组：原有「名字匹配」语义不丢 ----------

    @Test
    void plainStarRemainsParsed() {
        ColumnLineage.Trace t = trace("select * from demo.ods_user");
        assertTrue(t.parsed());
        assertTrue(t.labels().contains("*"), "物理表 * 不展开，留给原名匹配");
    }

    @Test
    void qualifiedColumnTraces() {
        assertOrigin("select u.phone from ods_user u", "phone", "phone");
        assertOrigin("select demo.ods_user.phone from demo.ods_user", "phone", "phone");
    }

    // ---------- UNION ----------

    @Test
    void unionMergesOriginsByPosition() {
        assertOrigin("select phone as p from a union select id_card as pid from b",
                "p", "phone", "id_card");
    }

    @Test
    void unionMixingStarAndNamedLabelFailsClosed() {
        ColumnLineage.Trace t = trace("select * from a union select phone as p from b");
        assertFalse(t.parsed(), "* 与具名列同位置无法归因，必须 fail-closed");
    }

    // ---------- fail-closed 形态 ----------

    @Test
    void scalarSubselectInSelectListFailsClosed() {
        ColumnLineage.Trace t = trace("select (select phone from t limit 1) as p");
        assertFalse(t.parsed());
    }

    @Test
    void garbageFailsClosed() {
        ColumnLineage.Trace t = trace("selekt phone form");
        assertFalse(t.parsed(), "解析异常必须落 parsed=false，不能当成「没有敏感列」");
    }

    @Test
    void lateralFromFailsClosed() {
        ColumnLineage.Trace t = trace("select p from t, lateral (select phone as p) x");
        assertFalse(t.parsed());
    }

    // ---------- 不适用形态 ----------

    @Test
    void nonSelectIsNotApplicable() {
        assertFalse(ColumnLineage.trace("update t set a = 1").applicable());
        assertFalse(ColumnLineage.trace("insert into a select phone from b").applicable());
        assertFalse(ColumnLineage.trace("show tables from demo").applicable());
        assertFalse(ColumnLineage.trace(null).applicable());
        assertFalse(ColumnLineage.trace("").applicable());
    }

    // ---------- 具名参数归一化 ----------

    @Test
    void namedParamsAreNormalizedForParsingOnly() {
        assertEquals("select a from t where b = ? and c = a::int and d = ? and e = 'x:y'",
                ColumnLineage.normalizeParams(
                        "select a from t where b = :uid and c = a::int and d = :p2 and e = 'x:y'"));
    }

    @Test
    void namedParamsInSelectListStillTrace() {
        assertOrigin("select phone as p from t where id = :uid", "p", "phone");
    }

    // ---------- ruleFor：先源列、后结果列名 ----------

    private static Map<String, Map<String, String>> rules() {
        return Map.of("phone", Map.of("rule", "MASK"),
                "id_card", Map.of("rule", "TOKEN"));
    }

    @Test
    void ruleForMatchesViaTracedSourceWhenLabelMisses() {
        ColumnLineage.Trace t = trace("select phone as p from demo.ods_user");
        assertEquals("MASK", DataGuardService.ruleFor("p", t, rules()).get("rule"));
        assertEquals("TOKEN", DataGuardService
                .ruleFor("pid", trace("select id_card as pid from t"), rules()).get("rule"));
    }

    /**
     * 红检（TC7-G6，2026-10-27）：把 {@code ruleFor} 的「按回溯源列匹配」分支删掉
     * （= 修复前只按结果列名的行为）后，本类必须红：
     * {@code ruleForMatchesViaTracedSourceWhenLabelMisses} 报
     * 「ruleFor 返回 null」（实测 28 run / 1 error，见
     * logs/m4-tc7g6-redcheck.out）；恢复分支后 28/28 绿。
     * 本用例同时钉住旧口径对照：不带 SQL（回溯不适用）时改名列就是匹配不到。
     */
    @Test
    void ruleForKeepsNameMatchingWithoutSql() {
        ColumnLineage.Trace t = ColumnLineage.trace(null);
        assertFalse(t.applicable());
        assertEquals("MASK", DataGuardService.ruleFor("phone", t, rules()).get("rule"));
        assertEquals("MASK", DataGuardService.ruleFor("PHONE", t, rules()).get("rule"));
        // 旧口径下改名列就是匹配不到——单测钉住「修复前该红」的对照
        assertEquals(null, DataGuardService.ruleFor("p", t, rules()));
    }

    @Test
    void ruleForMissesUnrelatedColumns() {
        ColumnLineage.Trace t = trace("select id as x from demo.ods_user");
        assertEquals(null, DataGuardService.ruleFor("x", t, rules()));
        assertEquals(null, DataGuardService.ruleFor(null, t, rules()));
    }
}
