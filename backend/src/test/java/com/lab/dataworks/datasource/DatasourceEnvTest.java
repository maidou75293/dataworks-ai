package com.lab.dataworks.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.integration.ConnectorCatalog;
import com.lab.dataworks.lake.LakeCatalogService;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-TC4-1 第一条：数据源的环境维度（TC-4 用例 DS-01 / DS-02 / DS-03）。
 *
 * <p>立案事实：{@code platform.datasource} 没有 env 列、{@code DataSourceRequest} 没有
 * env 字段、响应键清单里也没有（实测键：id/project_id/name/ds_type/jdbc_url/username/
 * config/engine/queryable/…）。一条连生产库的连接和一条连开发库的连接，在平台上除了
 * {@code jdbc_url} 里的库名以外无法区分；「发布 → prd 库」在数据源层无从配置，
 * 也没法按环境列出 / 授权。</p>
 *
 * <p>这里锁三层：<b>落库</b>（新建/编辑真的写进 env 列）、<b>回显</b>
 * （列表把 env 带出来）、<b>过滤</b>（{@code ?env=PROD} 真的过滤）。
 * 同时锁住「不许静默」：非法取值 400 点名收到什么，不退回默默按 DEV 收单。</p>
 */
class DatasourceEnvTest {

    private JdbcTemplate jdbc;
    private DataSourceController controller;

    @BeforeEach
    void setUp() {
        JdbcUrlGuard.resetAllowlist();
        jdbc = mock(JdbcTemplate.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.auditAround(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(5)).get());
        controller = new DataSourceController(jdbc, sessions, mock(SecretCipher.class),
                new ObjectMapper(), mock(ConnectorCatalog.class), mock(WarehouseResolver.class),
                mock(LakeCatalogService.class), mock(GravitinoCatalogSync.class),
                mock(GravitinoCatalogAdmin.class), mock(MetadataReader.class), "dataworks", "");

        when(jdbc.queryForObject(contains("from platform.project where id"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("from platform.datasource where project_id"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("insert into platform.datasource"), eq(Long.class),
                any(Object[].class))).thenReturn(66L);
        when(jdbc.update(contains("update platform.datasource"), any(Object[].class)))
                .thenReturn(1);
    }

    // ---------------------------------------------------------------- 落库

    @Test
    void createWithoutEnvLandsDev() {
        Map<String, Object> out = controller.create(1L, req(null), null);

        assertEquals("DEV", out.get("env"), "缺省环境必须是 DEV，并且回显给调用方");
        // insert 参数序：… engine, queryable, env
        verify(jdbc).queryForObject(contains("insert into platform.datasource"), eq(Long.class),
                eq(1L), eq("bug_env_ds"), eq("MYSQL"), any(), any(), any(),
                any(), any(), eq("jdbc"), eq(true), eq("DEV"));
    }

    @Test
    void createWithEnvProdLandsProd() {
        controller.create(1L, req("PROD"), null);

        verify(jdbc).queryForObject(contains("insert into platform.datasource"), eq(Long.class),
                eq(1L), eq("bug_env_ds"), eq("MYSQL"), any(), any(), any(),
                any(), any(), eq("jdbc"), eq(true), eq("PROD"));
    }

    @Test
    void envIsCaseAndSpaceTolerant() {
        for (String raw : List.of(" prod ", "Prod", "PROD\t")) {
            assertEquals("PROD", DataSourceController.normalizeEnv(raw), "env=[" + raw + "]");
        }
        assertEquals("DEV", DataSourceController.normalizeEnv(null));
        assertEquals("DEV", DataSourceController.normalizeEnv("   "));
    }

    /** DS-02：非法取值不能 200 收单再丢弃（acceptance-rules 第 4 条）。 */
    @Test
    void illegalEnvIs400AndNothingIsWritten() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.create(1L, req("STAGING"), null));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("DEV 或 PROD"), e.getMessage());
        assertTrue(e.getMessage().contains("STAGING"), "要说清收到的是什么: " + e.getMessage());
        verify(jdbc, never()).queryForObject(contains("insert into platform.datasource"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void illegalEnvOnUpdateIs400Too() {
        stubFind("MYSQL", "PROD");
        assertThrows(ApiException.class, () -> controller.update(11L, req("STAGING"), null));

        verify(jdbc, never()).update(contains("update platform.datasource"), any(Object[].class));
    }

    /** 编辑时不传 env 视为「不改」，不能默默打回 DEV。 */
    @Test
    void updateWithoutEnvKeepsExistingValue() {
        stubFind("MYSQL", "PROD");

        controller.update(11L, req(null), null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update platform.datasource"), args.capture());
        java.util.List<Object> values = java.util.Arrays.asList(args.getValue());
        assertTrue(values.contains("PROD"), "编辑不带 env 时必须沿用库里的 PROD: " + values);
        assertFalse(values.contains("DEV"), "不能默默打回 DEV: " + values);
    }

    /** 内置数据源的环境不许改，且不能静默忽略（用户会以为改成功了）。 */
    @Test
    void builtinEnvChangeIs400NotSilent() {
        when(jdbc.queryForList(contains("join platform.project p"), any(Object[].class)))
                .thenReturn(List.of(builtinRow()));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.update(11L, dorisReq("PROD"), null));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("内置数据源的环境固定"), e.getMessage());
    }

    // ---------------------------------------------------------------- 回显

    /** DS-01：列表必须能把「这条连接属于哪个环境」带出来。 */
    @Test
    void listEchoesEnvAndDefaultsLegacyRowsToDev() {
        Map<String, Object> prod = row("prod_ds", "PROD");
        Map<String, Object> legacy = row("legacy_ds", null);   // 迁移前的历史行
        when(jdbc.queryForList(contains("from platform.datasource d"), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of(prod, legacy)));

        List<Map<String, Object>> rows = controller.list(1L);

        assertEquals("PROD", rows.get(0).get("env"));
        assertEquals("DEV", rows.get(1).get("env"), "历史行没有值时按 DEV 回显");
    }

    /** 脏值不许被回显洗成 DEV（洗掉就看不出来了）。 */
    @Test
    void listDoesNotLaunderIllegalStoredValue() {
        Map<String, Object> dirty = row("dirty_ds", "STAGING");
        when(jdbc.queryForList(contains("from platform.datasource d"), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of(dirty)));

        assertEquals("STAGING", controller.list(1L).get(0).get("env"));
    }

    // ---------------------------------------------------------------- 过滤

    /** DS-03：{@code ?env=PROD} 必须真的过滤，不能返回全量让人以为过滤生效。 */
    @Test
    void allFiltersByEnv() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(new ArrayList<Map<String, Object>>());

        controller.all(null, " prod ");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("and d.env = ?"), sql.getValue());
        assertTrue(List.of(args.getValue()).contains("PROD"),
                "过滤值要按归一后的 PROD 传: " + List.of(args.getValue()));
    }

    @Test
    void allWithoutEnvDoesNotFilter() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(new ArrayList<Map<String, Object>>());

        controller.all(null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertFalse(sql.getValue().contains("d.env = ?"), sql.getValue());
    }

    @Test
    void allWithIllegalEnvIs400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.all(null, "STAGING"));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("DEV 或 PROD"), e.getMessage());
    }

    // ---------------------------------------------------------------- fixtures

    private static DataSourceController.DataSourceRequest req(String env) {
        return new DataSourceController.DataSourceRequest(
                "bug_env_ds", "MYSQL",
                "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/bug_a_env",
                "root", "NotARealPassword1!", null, null, null, null, null, env);
    }

    /** 内置数据源是 DORIS，改它的环境要先过「类型不许改」这一关。 */
    private static DataSourceController.DataSourceRequest dorisReq(String env) {
        return new DataSourceController.DataSourceRequest(
                "内置 Doris", "DORIS",
                "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/internal",
                "root", "NotARealPassword1!", null, null, null, null, null, env);
    }

    private static Map<String, Object> row(String name, String env) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("project_id", 1L);
        row.put("name", name);
        row.put("ds_type", "MYSQL");
        row.put("jdbc_url", "jdbc:mysql://h:3306/x");
        row.put("username", "u");
        row.put("config_json", "{}");
        if (env != null) {
            row.put("env", env);
        }
        return row;
    }

    private static Map<String, Object> builtinRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 11L);
        row.put("project_id", 1L);
        row.put("name", "内置 Doris");
        row.put("ds_type", "DORIS");
        row.put("jdbc_url", "jdbc:mysql://doris-fe:9030/internal");
        row.put("username", "root");
        row.put("password", "cipher-text");
        row.put("config_json", "{\"builtin\":true}");
        row.put("tenant_id", "default");
        row.put("engine", "doris");
        row.put("queryable", true);
        row.put("env", "DEV");
        return row;
    }

    private void stubFind(String dsType, String env) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 11L);
        row.put("project_id", 1L);
        row.put("name", "old_name");
        row.put("ds_type", dsType);
        row.put("jdbc_url", "jdbc:postgresql://pg.dw-dev.svc.cluster.local:5432/x");
        row.put("username", "u");
        row.put("password", "cipher-text");
        row.put("config_json", "{}");
        row.put("tenant_id", "default");
        row.put("engine", "jdbc");
        row.put("queryable", true);
        row.put("env", env);
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null
                        && sql.contains("join platform.project p")), any(Object[].class)))
                .thenReturn(List.of(row));
    }
}
