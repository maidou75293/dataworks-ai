package com.lab.dataworks.udf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-TC4-1 第二条：函数的「环境作用域」回显（TC-4 用例 UD-03 FAIL）。
 *
 * <p>立案事实：{@code GET /api/udf/projects/{id}/functions} 的响应里没有任何 env /
 * 库归属字段。Doris 侧发布走 {@code CREATE GLOBAL FUNCTION}（集群级），
 * 也就是<b>开发项目发布的函数在生产库同样立刻可调用</b>，「发布」这一步不含环境语义；
 * 用户却会以为「在开发项目发布」＝「只影响开发环境」。</p>
 *
 * <p>本测试锁两件事：① 列表/可用函数一定带出作用域字段（不至于再让人误以为有隔离）；
 * ② 不会假造一个 {@code env=DEV} 出来——作用域是 GLOBAL 就必须写 GLOBAL。</p>
 */
class UdfEnvScopeTest {

    private static Map<String, Object> function(String engine) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("name", "fadd");
        row.put("engine", engine);
        return row;
    }

    @Test
    void dorisFunctionIsClusterGlobal() {
        Map<String, Object> row = function("DORIS");

        UdfService.applyEnvScope(row);

        assertEquals("GLOBAL", row.get("envScope"));
        assertEquals(false, row.get("envIsolation"), "Doris 函数没有 DEV/PROD 隔离，不能写 true");
        assertTrue(String.valueOf(row.get("envNote")).contains("CREATE GLOBAL FUNCTION"),
                String.valueOf(row.get("envNote")));
    }

    @Test
    void flinkFunctionIsJobScopedButStillNotIsolated() {
        Map<String, Object> row = function("FLINK");

        UdfService.applyEnvScope(row);

        assertEquals("JOB", row.get("envScope"));
        assertEquals(false, row.get("envIsolation"));
        assertTrue(String.valueOf(row.get("envNote")).contains("ADD JAR"),
                String.valueOf(row.get("envNote")));
    }

    /** 引擎名大小写不敏感（库里是枚举，但别让一个 lower() 就掉进错误分支）。 */
    @Test
    void engineMatchingIsCaseInsensitive() {
        assertEquals("GLOBAL", scopeOf("doris"));
        assertEquals("JOB", scopeOf("flink"));
    }

    /** 绝不把作用域写成「有隔离」，也不假造 env=DEV 让调用方以为按环境分开了。 */
    @Test
    void neverFabricatesEnvField() {
        Map<String, Object> row = function("DORIS");

        UdfService.applyEnvScope(row);

        assertFalse(row.containsKey("env"), "不能凭空造一个 env=DEV: " + row);
        assertTrue(row.containsKey("envScope"));
        assertTrue(row.containsKey("envIsolation"));
        assertTrue(row.containsKey("envNote"));
    }

    private static String scopeOf(String engine) {
        Map<String, Object> row = function(engine);
        UdfService.applyEnvScope(row);
        return String.valueOf(row.get("envScope"));
    }
}
