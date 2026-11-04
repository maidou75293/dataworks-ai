package com.lab.dataworks.datasource;

import com.lab.dataworks.metadata.GravitinoCatalogNaming;
import com.lab.dataworks.metadata.GravitinoClient;
import com.lab.dataworks.metadata.GravitinoException;
import com.lab.dataworks.metadata.MetadataSettings;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V101：DORIS 数据源 → Gravitino Catalog 的同步语义。
 *
 * <p>覆盖任务要求的四类单测：命名（见 {@code GravitinoCatalogNamingTest}）、
 * 幂等（已存在走 ALTER、409 退化 ALTER）、失败状态（写 error 不抛）、
 * 开关（direct 跳过）。</p>
 */
class GravitinoCatalogSyncTest {

    private static final String CATALOG = GravitinoCatalogNaming.catalogName("default", "doris_kb");

    private JdbcTemplate jdbc;
    private GravitinoClient client;
    private MetadataSettings settings;
    private SecretCipher cipher;
    private GravitinoCatalogSync sync;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        client = mock(GravitinoClient.class);
        settings = mock(MetadataSettings.class);
        cipher = mock(SecretCipher.class);
        sync = new GravitinoCatalogSync(jdbc, client, settings, cipher, "jdbc-doris", "doris_dev");

        when(settings.gravitinoEnabled()).thenReturn(true);
        when(cipher.decrypt("enc")).thenReturn("secret");
        stubDatasource(datasource("DORIS", null));
        // 同名冲突检查：默认没有其他数据源占用
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    @Test
    void createsCatalogWhenMissing() {
        when(client.catalogExists(CATALOG)).thenReturn(false);

        Map<String, Object> out = sync.sync(1L);

        assertEquals("created", out.get("operation"));
        assertEquals("synced", out.get("status"));
        ArgumentCaptor<Map> props = ArgumentCaptor.forClass(Map.class);
        verify(client).createCatalog(eq(CATALOG), eq("jdbc-doris"), props.capture(), anyString());
        assertEquals("jdbc:mysql://doris-fe:9030/", props.getValue().get("jdbc-url"));
        assertEquals("dataworks", props.getValue().get("jdbc-user"));
        assertEquals("secret", props.getValue().get("jdbc-password"));
        assertStatusWrite("synced");
    }

    @Test
    void existingCatalogIsAlteredInsteadOfDuplicated() {
        when(client.catalogExists(CATALOG)).thenReturn(true);

        Map<String, Object> out = sync.sync(1L);

        assertEquals("updated", out.get("operation"));
        verify(client).alterCatalog(eq(CATALOG), anyMap());
        verify(client, never()).createCatalog(anyString(), anyString(), anyMap(), anyString());
        assertStatusWrite("synced");
    }

    @Test
    void createConflictFallsBackToAlter() {
        when(client.catalogExists(CATALOG)).thenReturn(false);
        doThrow(new GravitinoException(GravitinoException.CONFLICT, 409,
                "POST /catalogs", "already exists", false))
                .when(client).createCatalog(eq(CATALOG), eq("jdbc-doris"), anyMap(), anyString());

        Map<String, Object> out = sync.sync(1L);

        assertEquals("updated", out.get("operation"));
        verify(client).alterCatalog(eq(CATALOG), anyMap());
        assertStatusWrite("synced");
    }

    @Test
    void failureWritesErrorStatusAndDoesNotThrow() {
        when(client.catalogExists(CATALOG)).thenReturn(false);
        doThrow(new GravitinoException(GravitinoException.UNAVAILABLE, 0,
                "POST /catalogs", "Connection refused", true))
                .when(client).createCatalog(eq(CATALOG), eq("jdbc-doris"), anyMap(), anyString());

        Map<String, Object> out = sync.sync(1L);

        assertFalse((Boolean) out.get("ok"));
        assertEquals("error", out.get("status"));
        assertTrue(String.valueOf(out.get("message")).contains("GRAVITINO_UNAVAILABLE"),
                String.valueOf(out.get("message")));
        assertStatusWrite("error");
    }

    @Test
    void directSwitchSkipsGravitinoEntirely() {
        when(settings.gravitinoEnabled()).thenReturn(false);

        Map<String, Object> out = sync.sync(1L);

        assertEquals("disabled", out.get("status"));
        assertEquals("skipped", out.get("operation"));
        verifyNoInteractions(client);
        assertStatusWrite("disabled");
    }

    @Test
    void nonDorisDatasourceIsSkipped() {
        stubDatasource(datasource("KAFKA", null));

        Map<String, Object> out = sync.sync(1L);

        assertEquals("skipped", out.get("operation"));
        verifyNoInteractions(client);
        verify(jdbc, never()).update(contains("set gravitino_catalog"), any(Object[].class));
    }

    @Test
    void renameUsesAlterRenameAndKeepsOneCatalog() {
        stubDatasource(datasource("DORIS", "old_catalog"));
        when(client.catalogExists(CATALOG)).thenReturn(true);

        Map<String, Object> out = sync.sync(1L);

        assertEquals("renamed", out.get("operation"));
        verify(client).renameCatalog("old_catalog", CATALOG);
        verify(client).alterCatalog(eq(CATALOG), anyMap());
        // 改名不是删除重建：否则会留下新的软删记录（catalog_meta 重复的来源）
        verify(client, never()).deleteCatalog(anyString());
        verify(client, never()).createCatalog(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void renameFallsBackToCreateWhenOldCatalogIsGone() {
        stubDatasource(datasource("DORIS", "old_catalog"));
        doThrow(new GravitinoException(GravitinoException.NOT_FOUND, 404,
                "PUT /catalogs/old_catalog", "not found", false))
                .when(client).renameCatalog("old_catalog", CATALOG);
        when(client.catalogExists(CATALOG)).thenReturn(false);

        Map<String, Object> out = sync.sync(1L);

        assertEquals("created", out.get("operation"));
        verify(client).createCatalog(eq(CATALOG), eq("jdbc-doris"), anyMap(), anyString());
    }

    @Test
    void sameNameIsPointedAtTheOwningDatasource() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(99L));

        Map<String, Object> out = sync.sync(1L);

        assertEquals("error", out.get("status"));
        assertTrue(String.valueOf(out.get("message")).contains("GRAVITINO_CATALOG_CONFLICT"),
                String.valueOf(out.get("message")));
        verifyNoInteractions(client);
        assertStatusWrite("error");
    }

    @Test
    void detachDeletesStoredCatalogAndClearsMapping() {
        stubDatasource(datasource("DORIS", "default_old_catalog"));

        Map<String, Object> out = sync.detach(1L);

        assertEquals("removed", out.get("operation"));
        verify(client).deleteCatalog("default_old_catalog");
        // 清除映射必须带租户条件，避免跨租户改到别人的行
        verify(jdbc).update(contains("set gravitino_catalog = null"), any(Object[].class));
    }

    @Test
    void removeDoesNotThrowWhenGravitinoIsDown() {
        stubDatasource(datasource("DORIS", "default_old_catalog"));
        doThrow(new GravitinoException(GravitinoException.UNAVAILABLE, 0,
                "DELETE /catalogs/old", "connection refused", true))
                .when(client).deleteCatalog(anyString());

        // 删除数据源不能被外部元数据服务反向锁死
        sync.remove(1L);

        verify(client).deleteCatalog("default_old_catalog");
    }

    @Test
    void ownerCheckFailureDoesNotBreakSave() {
        stubDatasource(datasource("DORIS", null));
        when(jdbc.queryForList(contains("where gravitino_catalog = ? and id <> ?"),
                eq(Long.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("platform db down"));

        Map<String, Object> out = sync.sync(1L);

        assertFalse((Boolean) out.get("ok"));
        assertEquals("error", out.get("status"));
        assertTrue(String.valueOf(out.get("message")).contains("检查 Catalog 占用失败"),
                String.valueOf(out.get("message")));
        verifyNoInteractions(client);
    }

    @Test
    void dorisCatalogAlwaysUsesQueryPort9030() {
        assertEquals("jdbc:mysql://doris-fe:9030/", GravitinoCatalogSync.dorisJdbcUrl(
                "jdbc:mysql://doris-fe:8030/"));
        assertEquals("jdbc:mysql://doris-fe:9030/db",
                GravitinoCatalogSync.dorisJdbcUrl("jdbc:mysql://doris-fe/db"));
        assertEquals("jdbc:mysql://doris-fe:9030/",
                GravitinoCatalogSync.dorisJdbcUrl("jdbc:mysql://doris-fe:9030/"));
        // 显式自定义端口不覆盖
        assertEquals("jdbc:mysql://proxy:19030/",
                GravitinoCatalogSync.dorisJdbcUrl("jdbc:mysql://proxy:19030/"));
    }

    @Test
    void builtinDorisSyncsPlatformCatalogWithCredentials() {
        stubDatasource(builtinDatasource());
        when(client.catalogExists("doris_dev")).thenReturn(true);

        Map<String, Object> out = sync.sync(1L);

        assertEquals("updated", out.get("operation"));
        ArgumentCaptor<Map> props = ArgumentCaptor.forClass(Map.class);
        verify(client).alterCatalog(eq("doris_dev"), props.capture());
        assertEquals("dataworks", props.getValue().get("jdbc-user"));
        assertEquals("secret", props.getValue().get("jdbc-password"));
        // 内置数据源不走租户命名，也不做同名占用检查
        verify(client, never()).createCatalog(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void builtinCatalogIsNeverDeleted() {
        stubDatasource(builtinDatasource());

        sync.remove(1L);
        sync.detach(1L);

        verify(client, never()).deleteCatalog(anyString());
    }

    @Test
    void deleteRepairsMissingCredentialsBeforeDrop() {
        stubDatasource(datasource("DORIS", "default_old_catalog"));
        // 历史 Catalog 只有 jdbc-url/driver，缺账号口令 → Gravitino 删不掉
        Map<String, Object> detail = new HashMap<>();
        detail.put("properties", Map.of("jdbc-url", "jdbc:mysql://doris-fe:9030/",
                "jdbc-driver", "com.mysql.cj.jdbc.Driver"));
        when(client.getCatalog("default_old_catalog")).thenReturn(detail);

        sync.remove(1L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(client);
        order.verify(client).alterCatalog(eq("default_old_catalog"), anyMap());
        order.verify(client).deleteCatalog("default_old_catalog");
    }

    @Test
    void missingUsernameIsConfigErrorNotDisabled() {
        Map<String, Object> row = datasource("DORIS", null);
        row.put("username", "");
        stubDatasource(row);

        Map<String, Object> out = sync.sync(1L);

        assertEquals("error", out.get("status"));
        assertTrue(String.valueOf(out.get("message")).contains("GRAVITINO_CONFIG_INVALID"),
                String.valueOf(out.get("message")));
        assertStatusWrite("error");
    }

    // ---------------------------------------------------------------- 夹具

    private static Map<String, Object> builtinDatasource() {
        Map<String, Object> row = datasource("DORIS", "doris_dev");
        row.put("builtin", true);
        return row;
    }

    private void stubDatasource(Map<String, Object> row) {
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.<String>argThat(
                        sql -> sql != null && sql.contains("from platform.datasource d")),
                any(Object[].class))).thenReturn(List.of(row));
    }

    private static Map<String, Object> datasource(String type, String catalog) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("name", "doris_kb");
        row.put("ds_type", type);
        row.put("jdbc_url", "jdbc:mysql://doris-fe:9030/");
        row.put("username", "dataworks");
        row.put("password", "enc");
        row.put("tenant_id", "default");
        row.put("gravitino_catalog", catalog);
        return row;
    }

    private void assertStatusWrite(String expectedStatus) {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("set gravitino_catalog"), args.capture());
        assertEquals(expectedStatus, args.getValue()[1]);
    }
}
