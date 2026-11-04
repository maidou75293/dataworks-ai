package com.lab.dataworks.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D24：{@code GET /api/query/capabilities} 不能把其他租户的基础设施信息摊开。
 *
 * <p>平台内置 Doris 对所有租户可查是设计（{@code load()} 故意带 builtin 数据源，
 * 否则别的租户连平台数仓都查不了），但它的项目号与库名清单属于<b>默认租户</b>：
 * 实测任意租户都能读到 {@code {"projectId":1,"databases":["内置 Doris","doris","platform_dev"]}}，
 * 可与 D01/D05 组合踩点。</p>
 *
 * <p>口径：跨租户的只回<b>能力</b>（引擎、是否可查、数据源 id——前端提交前判定要带
 * datasourceId），不回<b>归属</b>（projectId 置空、databases 置空、shared=true、
 * 库名前缀也不进补全清单）。</p>
 */
class QueryCapabilitiesTenantTest {

    private static final long BUILTIN_DS_ID = 19L;
    private static final long OWN_DS_ID = 77L;

    private JdbcTemplate jdbc;
    private QueryCapabilities capabilities;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        capabilities = new QueryCapabilities(jdbc, new ObjectMapper());
        // 一条本租户项目的 Doris（库名 bugb_dev）+ 一条默认租户的内置 Doris（库名 platform_dev）
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                source(OWN_DS_ID, 123L, "本项目 Doris", "bugb_dev", "false"),
                source(BUILTIN_DS_ID, 1L, "内置 Doris", "platform_dev", "true")));
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    /** @param ownProjectIds 当前租户名下的项目 id */
    private void stubOwnProjects(List<Long> ownProjectIds) {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(ownProjectIds);
    }

    private Map<String, Object> source(long id, long projectId, String name,
                                       String database, String builtin) {
        return Map.of(
                "id", id,
                "project_id", projectId,
                "name", name,
                "ds_type", "DORIS",
                "jdbc_url", "jdbc:mysql://10.99.99.11:9030/" + database,
                "config_json", "{\"builtin\":\"" + builtin + "\",\"database\":\""
                        + database + "\"}",
                "engine", "DORIS",
                "queryable", true,
                "project_code", "p" + projectId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, long id) {
        return rows.stream()
                .filter(row -> ((Number) row.get("id")).longValue() == id)
                .findFirst().orElseThrow();
    }

    @Test
    void crossTenantBuiltinKeepsCapabilityButHidesOwnership() {
        TenantScope.set("bugb");
        stubOwnProjects(List.of(123L));

        Map<String, Object> out = capabilities.describe(null);
        Map<String, Object> builtin = find(items(out.get("queryable")), BUILTIN_DS_ID);

        // 能力仍在：非默认租户还要能查平台内置 Doris
        assertEquals(BUILTIN_DS_ID, ((Number) builtin.get("id")).longValue());
        assertEquals("DORIS", builtin.get("engine"));
        assertEquals(true, builtin.get("queryable"));
        assertEquals(true, builtin.get("shared"), "要能看出这是平台共享数据源");
        // 归属信息不回
        assertNull(builtin.get("projectId"), "不能暴露默认租户的项目号（D24 实测 projectId=1）");
        assertEquals(List.of(), builtin.get("databases"), "不能暴露默认租户的库名清单");

        Map<String, Object> own = find(items(out.get("queryable")), OWN_DS_ID);
        assertEquals(123L, ((Number) own.get("projectId")).longValue());
        assertEquals(false, own.get("shared"), "本租户数据源不算共享");
        assertTrue(strings(own.get("databases")).contains("bugb_dev"),
                "本租户数据源的库名照常回（前端补全要用）: " + own.get("databases"));
    }

    @Test
    void crossTenantDatabaseNamesDoNotLeakIntoCompletionPrefixes() {
        TenantScope.set("bugb");
        stubOwnProjects(List.of(123L));

        Map<String, Object> out = capabilities.describe(null);
        List<String> prefixes = strings(out.get("tablePrefixes"));
        assertFalse(prefixes.contains("platform_dev."),
                "别的租户的库名不能进补全前缀: " + prefixes);
        assertFalse(strings(out.get("notQueryablePrefixes")).contains("platform_dev."),
                "也不能进补全剔除前缀: " + out.get("notQueryablePrefixes"));
        assertTrue(prefixes.contains("bugb_dev."),
                "本租户的库名前缀不受影响: " + prefixes);
    }

    @Test
    void defaultTenantStillSeesItsOwnBuiltinDetails() {
        TenantScope.set(TenantScope.DEFAULT);
        // 内置 Doris 挂在默认租户的项目 1 下＝本租户资产，归属信息照回
        stubOwnProjects(List.of(1L, 123L));

        Map<String, Object> out = capabilities.describe(null);
        Map<String, Object> builtin = find(items(out.get("queryable")), BUILTIN_DS_ID);
        assertEquals(1L, ((Number) builtin.get("projectId")).longValue());
        assertEquals(false, builtin.get("shared"));
        assertTrue(strings(builtin.get("databases")).contains("platform_dev"),
                "默认租户读自己的内置 Doris 不受影响: " + builtin.get("databases"));
        assertTrue(strings(out.get("tablePrefixes")).contains("platform_dev."),
                "补全前缀保持可用: " + out.get("tablePrefixes"));
    }

    @Test
    void projectScopedRequestStillWorks() {
        TenantScope.set("bugb");
        stubOwnProjects(List.of(123L));
        Map<String, Object> out = capabilities.describe(123L);
        assertEquals(2, items(out.get("queryable")).size(),
                "带 projectId 时仍然「本项目 + 平台内置」两条");
    }
}
