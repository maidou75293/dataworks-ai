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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收尾：数据源 CRUD 与 Gravitino 同步的接线，以及 SPARK（Spark Thrift）类型。
 *
 * <p>全部用 mock：不连 PG/Doris/Gravitino，也不真正建立 JDBC 连接——只验证
 * 参数校验、类型归一化与「哪个分支该调用 Gravitino 同步」。</p>
 */
class DataSourceControllerTest {

    private static final String SPARK_URL =
            "jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/default";

    private JdbcTemplate jdbc;
    private ConnectorCatalog catalog;
    private GravitinoCatalogSync gravitinoSync;
    private MetadataReader metadata;
    private DataSourceController controller;

    @BeforeEach
    void setUp() {
        JdbcUrlGuard.resetAllowlist();
        jdbc = mock(JdbcTemplate.class);
        SessionService sessions = mock(SessionService.class);
        // S13 改造后 delete/update 走 auditAround：mock 必须透传 supplier，否则返回 null
        when(sessions.auditAround(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(5)).get());
        SecretCipher cipher = mock(SecretCipher.class);
        catalog = mock(ConnectorCatalog.class);
        when(catalog.has("KAFKA")).thenReturn(true);
        when(catalog.require("KAFKA")).thenReturn(Map.of("name", "Kafka"));
        WarehouseResolver warehouses = mock(WarehouseResolver.class);
        LakeCatalogService lake = mock(LakeCatalogService.class);
        gravitinoSync = mock(GravitinoCatalogSync.class);
        GravitinoCatalogAdmin admin = mock(GravitinoCatalogAdmin.class);
        metadata = mock(MetadataReader.class);
        controller = new DataSourceController(jdbc, sessions, cipher, new ObjectMapper(),
                catalog, warehouses, lake, gravitinoSync, admin, metadata, "dataworks", "");

        when(jdbc.queryForObject(contains("from platform.project where id"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("from platform.datasource where project_id"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("insert into platform.datasource"), eq(Long.class),
                any(Object[].class))).thenReturn(5L);
        when(jdbc.update(contains("update platform.datasource"), any(Object[].class)))
                .thenReturn(1);
    }

    // ---------------------------------------------------------------- SPARK

    @Test
    void sparkCanBeSavedWithoutPassword() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "spark_lake", "SPARK", SPARK_URL, "hive", null, null, null, null, null, null, null);

        Map<String, Object> out = controller.create(1L, req, null);

        assertEquals(5L, out.get("id"));
        // SPARK 不属于 DORIS：不触发 Gravitino Catalog 同步
        verify(gravitinoSync, never()).sync(anyLong());
        verify(metadata, never()).invalidateCatalog(anyString());
    }

    @Test
    void sparkRequiresJdbcUrl() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "spark_bad", "SPARK", "spark://host:10000", "hive", null, null, null,
                null, null, null, null);

        ApiException e = assertThrows(ApiException.class, () -> controller.create(1L, req, null));

        assertEquals(400, e.getStatus());
        verify(jdbc, never()).queryForObject(contains("insert into platform.datasource"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void sparkRejectsPrivateHostAndDangerousParams() {
        for (String url : List.of("jdbc:hive2://127.0.0.1:10000/default",
                "jdbc:hive2://10.0.0.9:10000/default",
                SPARK_URL + "?queryInterceptors=com.evil.Payload")) {
            DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                    "spark_bad", "SPARK", url, "hive", null, null, null, null, null, null, null);
            ApiException e = assertThrows(ApiException.class,
                    () -> controller.create(1L, req, null), url);
            assertEquals(400, e.getStatus(), url);
        }
    }

    @Test
    void sparkTestRequiresUsername() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                null, "SPARK", SPARK_URL, " ", null, null, null, null, null, null, null);

        ApiException e = assertThrows(ApiException.class,
                () -> controller.testUnsaved(req));

        assertEquals(400, e.getStatus());
    }

    @Test
    void sparkTestUnsavedValidatesUrlBeforeConnecting() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                null, "SPARK", "jdbc:hive2://10.0.0.9:10000/default", "hive", null, null,
                null, null, null, null, null);

        // 出网守卫在建立连接之前就拒绝，测试不会真的连外部服务
        ApiException e = assertThrows(ApiException.class, () -> controller.testUnsaved(req));
        assertEquals(400, e.getStatus());
    }

    @Test
    void unknownTypeIsRejected() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "x", "NOPE", null, null, null, null, null, null, null, null, null);

        ApiException e = assertThrows(ApiException.class, () -> controller.create(1L, req, null));
        assertEquals(400, e.getStatus());
    }

    // ---------------------------------------------------------------- REQ-DW-5

    @Test
    void dorisDefaultStorageAcceptsSsoMethod() {
        when(gravitinoSync.sync(5L)).thenReturn(Map.of("ok", true, "status", "synced"));
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "doris_sso", "DORIS",
                "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/kb",
                "dataworks", "secret", null, null, "sso", "https://sso.example.com", null, null);

        Map<String, Object> out = controller.create(1L, req, null);

        assertEquals(5L, out.get("id"));
    }

    @Test
    void dorisDefaultStorageRejectsUnknownMethod() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "doris_ftp", "DORIS",
                "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/kb",
                "dataworks", "secret", null, null, "ftp", "ftp://x", null, null);

        ApiException e = assertThrows(ApiException.class, () -> controller.create(1L, req, null));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("sso"), e.getMessage());
        verify(jdbc, never()).queryForObject(contains("insert into platform.datasource"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void dorisDevWarehouseFlagIsPersistedInConfigJson() {
        when(gravitinoSync.sync(5L)).thenReturn(Map.of("ok", true, "status", "synced"));
        // varargs 的 argThat 只对齐单个元素（首轮踩坑），改在打桩里捕获实参再断言
        List<Object[]> captured = new java.util.ArrayList<>();
        when(jdbc.queryForObject(contains("insert into platform.datasource"), eq(Long.class),
                any(Object[].class)))
                .thenAnswer(inv -> {
                    captured.add(inv.getArguments());
                    return 5L;
                });
        // 前端把「作为开发数仓」开关合进 config；顶层没有未知字段（record 是严格模式）
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "doris_dev", "DORIS",
                "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/kb",
                "dataworks", "secret", Map.of("devWarehouse", true),
                null, "hdfs", "http://namenode:9870", null, null);

        controller.create(1L, req, null);

        assertEquals(1, captured.size());
        boolean hit = false;
        for (Object arg : captured.get(0)) {
            if (String.valueOf(arg).contains("\"devWarehouse\":true")) {
                hit = true;
                break;
            }
        }
        assertTrue(hit, () -> java.util.Arrays.toString(captured.get(0)));
    }

    // ---------------------------------------------------------------- 同步接线

    @Test
    void createDorisTriggersCatalogSyncAndCacheInvalidation() {
        when(gravitinoSync.sync(5L)).thenReturn(Map.of(
                "ok", true, "status", "synced", "catalog", "default_doris_new"));
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "doris_new", "DORIS", "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/kb",
                "dataworks", "secret", null, null, null, null, null, null);

        Map<String, Object> out = controller.create(1L, req, null);

        assertTrue(out.containsKey("gravitino"));
        verify(gravitinoSync).sync(5L);
        verify(metadata).invalidateCatalog("default_doris_new");
    }

    @Test
    void changingTypeAwayFromDorisDetachesCatalog() {
        stubFind(datasource("DORIS", "default_old"));
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "doris_gone", "KAFKA", null, null, null,
                Map.of("bootstrap.servers", "kafka-demo.dw-dev.svc.cluster.local:9092"),
                null, null, null, null, null);

        Map<String, Object> out = controller.update(11L, req, null);

        assertEquals(true, out.get("ok"));
        verify(gravitinoSync).detach(11L);
        verify(gravitinoSync, never()).sync(anyLong());
        // 旧 Catalog 的元数据缓存必须立刻失效
        verify(metadata).invalidateCatalog("default_old");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listExposesGravitinoCatalogMapping() {
        Map<String, Object> row = datasource("DORIS", "default_doris_kb");
        row.put("config_json", "{}");
        when(jdbc.queryForList(argThat(sql -> sql != null
                && sql.contains("from platform.datasource d")
                && sql.contains("order by d.id")), any(Object[].class)))
                .thenReturn(List.of(row));

        List<Map<String, Object>> rows = controller.list(1L);

        // 验收项：建完数据源后列表行必须带 Catalog 映射与同步状态
        assertEquals("default_doris_kb", rows.get(0).get("gravitinoCatalog"));
        assertEquals("synced", rows.get(0).get("gravitinoStatus"));
        Map<String, Object> gravitino = (Map<String, Object>) rows.get(0).get("gravitino");
        assertEquals("default_doris_kb", gravitino.get("catalog"));
        assertEquals("synced", gravitino.get("status"));
    }

    @Test
    void dorisUpdateSyncsAndInvalidatesOldCatalog() {
        stubFind(datasource("DORIS", "default_old"));
        when(gravitinoSync.sync(11L)).thenReturn(Map.of(
                "ok", true, "status", "synced", "catalog", "default_renamed"));
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "renamed", "DORIS", "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/kb",
                "dataworks", "", null, null, null, null, null, null);

        controller.update(11L, req, null);

        verify(gravitinoSync).sync(11L);
        verify(metadata).invalidateCatalog("default_old");
        verify(metadata).invalidateCatalog("default_renamed");
    }

    @Test
    void deletingDorisRemovesCatalogBeforeRow() {
        stubFind(datasource("DORIS", "default_old"));
        when(jdbc.queryForObject(contains("from platform.project where warehouse_datasource_id"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("from platform.workflow w"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("from platform.data_service"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("from platform.sync_task"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("from platform.quality_rule"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbc.update(contains("delete from platform.datasource"), any(Object[].class)))
                .thenReturn(1);

        Map<String, Object> out = controller.delete(11L, null);

        assertEquals(true, out.get("ok"));
        verify(gravitinoSync).remove(11L);
        verify(metadata).invalidateCatalog("default_old");
    }

    // ---------------------------------------------------------------- 夹具

    private void stubFind(Map<String, Object> row) {
        when(jdbc.queryForList(
                argThat(sql -> sql != null && sql.contains("join platform.project p")),
                any(Object[].class))).thenReturn(List.of(row));
    }

    private static Map<String, Object> datasource(String type, String catalog) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 11L);
        row.put("project_id", 1L);
        row.put("name", "old_name");
        row.put("ds_type", type);
        row.put("jdbc_url", "jdbc:mysql://doris-fe.dw-dev.svc.cluster.local:9030/kb");
        row.put("username", "dataworks");
        row.put("password", "enc:v1:xxx");
        row.put("config_json", null);
        row.put("queryable", true);
        row.put("engine", "doris");
        row.put("lake_catalog", null);
        row.put("lake_status", null);
        row.put("lake_status_message", null);
        row.put("gravitino_catalog", catalog);
        row.put("gravitino_status", "synced");
        row.put("gravitino_message", null);
        row.put("gravitino_synced_at", null);
        row.put("tenant_id", "default");
        return row;
    }

    /**
     * BUG-DEMO-DS-HYGIENE-1：内置 Doris 行连接测试的凭据回落四态——
     * 只有「内置行 + 行上口令为空 + 平台 doris.password 已配置」才跟随平台凭据；
     * 其余组合一律沿用行上自己的口令（没配就按空口令如实测红，不假绿）。
     */
    @Test
    void builtinDorisTestFallsBackToPlatformCredentialsOnlyWhenRowEmpty() {
        String[] fell = DataSourceController.resolveTestCredentials(
                true, "dataworks", "", "dataworks", "doris-secret");
        assertEquals("dataworks", fell[0]);
        assertEquals("doris-secret", fell[1]);

        String[] kept = DataSourceController.resolveTestCredentials(
                true, "root", "row-pass", "dataworks", "doris-secret");
        assertEquals("root", kept[0]);
        assertEquals("row-pass", kept[1], "行上存过口令就按行上的测，不能被平台凭据悄悄顶掉");

        String[] notBuiltin = DataSourceController.resolveTestCredentials(
                false, "app", "", "dataworks", "doris-secret");
        assertEquals("app", notBuiltin[0]);
        assertEquals("", notBuiltin[1], "非内置行绝不发平台凭据");

        String[] noPlatform = DataSourceController.resolveTestCredentials(
                true, "dataworks", "", "dataworks", "");
        assertEquals("", noPlatform[1], "平台口令未配置时沿用空口令如实报红，不藏");
    }
}
