package com.lab.dataworks.project;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V98：项目 → DORIS 数据源 → warehouse_connection 的解析规则。
 *
 * <p>覆盖任务要求的四类单测：默认内置、非 DORIS 400、跨租户 404、旧项目回退。</p>
 */
class WarehouseResolverTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SecretCipher cipher = mock(SecretCipher.class);
    private WarehouseResolver resolver;

    @BeforeEach
    void setUp() {
        TenantScope.set("default");
        resolver = new WarehouseResolver(jdbc, cipher, "doris-fe", 9030);
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    @Test
    void builtinDatasourceResolvesToNull() {
        stubProject(1L, 10L, null);
        stubDatasource(datasource(10L, "default", "DORIS", true));

        assertNull(resolver.connectionIdForProject(1L),
                "内置数据源必须返回 null，查询继续走 MCP");
    }

    @Test
    void nonDorisDatasourceIsBadRequest() {
        stubProject(1L, 11L, null);
        stubDatasource(datasource(11L, "default", "MYSQL", false));

        ApiException e = assertThrows(ApiException.class,
                () -> resolver.connectionIdForProject(1L));
        assertEquals(400, e.getStatus(), e.getMessage());
    }

    @Test
    void crossTenantDatasourceIsNotFound() {
        stubProject(1L, 12L, null);
        stubDatasource(datasource(12L, "other", "DORIS", false));

        ApiException e = assertThrows(ApiException.class,
                () -> resolver.connectionIdForProject(1L));
        assertEquals(404, e.getStatus(), e.getMessage());
    }

    @Test
    void legacyProjectFallsBackToDorisConnectionId() {
        stubProject(1L, null, 77L);

        assertEquals(77L, resolver.connectionIdForProject(1L),
                "warehouse_datasource_id 为空时必须回退旧 doris_connection_id");
    }

    // ---------------------------------------------------------------- 夹具

    private void stubProject(long projectId, Long datasourceId, Long legacyConnectionId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", projectId);
        row.put("tenant_id", "default");
        row.put("warehouse_datasource_id", datasourceId);
        row.put("doris_connection_id", legacyConnectionId);
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.<String>argThat(
                        sql -> sql != null && sql.contains("from platform.project")),
                any(Object[].class))).thenReturn(List.of(row));
    }

    private void stubDatasource(Map<String, Object> row) {
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.<String>argThat(
                        sql -> sql != null && sql.contains("from platform.datasource")),
                any(Object[].class))).thenReturn(List.of(row));
    }

    private static Map<String, Object> datasource(long id, String tenant, String type,
                                                  boolean builtin) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("project_id", 1L);
        row.put("name", builtin ? "内置 Doris" : "ds-" + id);
        row.put("ds_type", type);
        row.put("jdbc_url", "jdbc:mysql://doris-fe:9030/");
        row.put("username", "dataworks");
        row.put("password", "");
        row.put("project_tenant", tenant);
        row.put("builtin", builtin);
        return row;
    }
}
