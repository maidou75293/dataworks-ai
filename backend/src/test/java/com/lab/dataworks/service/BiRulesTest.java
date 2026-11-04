package com.lab.dataworks.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BI 纯逻辑：命名参数扫描、声明校验、发布版本号。
 *
 * <p>对应「SQL 里写了未声明的 :param 会被静默绑定 NULL」与「并发发布版本号 +1 竞态」两项修复。</p>
 */
class BiRulesTest {

    @Test
    void namedParamsSkipsStringsCommentsCastsAndDollarQuotes() {
        String sql = """
                select :day, amount::numeric, ':not_param', "col:also_not"
                  from sales
                 where id = :min_id -- :comment_param
                   /* :block_param */
                """;
        assertEquals(List.of("day", "min_id"), BiRules.namedParams(sql));
        // 重复参数只保留一次；美元引用字符串里的冒号不算参数
        assertEquals(List.of("a"), BiRules.namedParams("select :a, :a, $tag$ :x $tag$"));
    }

    @Test
    void validationDetectsBadNamesAndAdvancesVersions() {
        List<String> used = BiRules.namedParams("select :day, :region from t");
        assertEquals(List.of("region"), BiRules.undeclared(used, Set.of("day")));
        assertTrue(BiRules.undeclared(used, Set.of("day", "region")).isEmpty());

        assertEquals(List.of("dya"), BiRules.unknownKeys(Set.of("day", "dya"), Set.of("day")));
        assertTrue(BiRules.unknownKeys(Set.of("day"), Set.of("day")).isEmpty());

        assertEquals(1, BiRules.nextVersion(null));
        assertEquals(2, BiRules.nextVersion(1));
        assertEquals(8, BiRules.nextVersion(7));
    }

    /** D22：看板 env 只认 DEV / PRD，非法值 400（原来 env=zzz 静默按 PRD 执行）。 */
    @Test
    void envAcceptsDevAndPrdOnly() {
        assertEquals("DEV", BiRules.envOf("DEV"));
        assertEquals("DEV", BiRules.envOf("dev"));
        assertEquals("DEV", BiRules.envOf(" Dev "));
        assertEquals("PRD", BiRules.envOf("PRD"));
        assertEquals("PRD", BiRules.envOf("prd"));
        // 不传＝生产快照（与 @RequestParam(defaultValue = "PRD") 一致）
        assertEquals("PRD", BiRules.envOf(null));
        assertEquals("PRD", BiRules.envOf("  "));
    }

    @Test
    void envRejectsUnknownValue() {
        for (String value : new String[]{"zzz", "PROD", "prod", "DEVV", "1", "pre"}) {
            com.lab.dataworks.common.ApiException e = assertThrows(
                    com.lab.dataworks.common.ApiException.class,
                    () -> BiRules.envOf(value), "非法 env 必须 400: " + value);
            assertEquals(400, e.getStatus());
            assertTrue(e.getMessage().contains(value), e.getMessage());
            assertTrue(e.getMessage().contains("DEV") && e.getMessage().contains("PRD"),
                    "提示要给出可选值: " + e.getMessage());
        }
    }
}
