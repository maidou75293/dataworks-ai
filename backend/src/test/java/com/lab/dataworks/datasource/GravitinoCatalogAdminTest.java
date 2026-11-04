package com.lab.dataworks.datasource;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.metadata.GravitinoCatalogNaming;
import com.lab.dataworks.metadata.GravitinoClient;
import com.lab.dataworks.metadata.MetadataCatalogResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收尾：Gravitino Catalog 巡检/清理。
 *
 * <p>重点验证「没有把握的一律不删」：dry-run 不删、平台级/其他租户不删、
 * 映射未确认的重复残留不删、删除前复检发现被引用也跳过。</p>
 */
class GravitinoCatalogAdminTest {

    private static final String PREFIX = GravitinoCatalogNaming.tenantPrefix("default");

    private JdbcTemplate jdbc;
    private GravitinoClient client;
    private MetadataCatalogResolver catalogs;
    private GravitinoCatalogAdmin admin;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        client = mock(GravitinoClient.class);
        catalogs = mock(MetadataCatalogResolver.class);
        admin = new GravitinoCatalogAdmin(jdbc, client, catalogs);
        when(catalogs.builtinCatalog()).thenReturn("doris_dev");
        stubReferenced(Map.of());
        stubExpected(List.of());
        when(jdbc.queryForObject(contains("lower(gravitino_catalog)"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
    }

    @Test
    void dryRunListsOrphansWithoutDeleting() {
        when(client.listCatalogs()).thenReturn(List.of(PREFIX + "orphan_a", "doris_dev"));
        when(jdbc.queryForObject(contains("lower(gravitino_catalog)"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);

        Map<String, Object> out = admin.list();

        assertEquals(true, out.get("dryRun"));
        assertFalse(out.containsKey("deleted"));
        List<Map<String, Object>> rows = rows(out);
        assertEquals("orphan", rows.get(0).get("category"));
        assertEquals(true, rows.get(0).get("deletable"));
        assertEquals("protected", rows.get(1).get("category"));
        assertEquals("doris_dev", rows.get(1).get("name"));
        verify(client, never()).deleteCatalog(anyString());
    }

    @Test
    void otherTenantCatalogsAreProtected() {
        when(client.listCatalogs()).thenReturn(List.of("other_tenant_x", "doris_dev"));

        List<Map<String, Object>> rows = rows(admin.list());

        assertEquals("protected", rows.get(0).get("category"));
        assertEquals(false, rows.get(0).get("deletable"));
        assertEquals("protected", rows.get(1).get("category"));
    }

    @Test
    void referencedCatalogIsNeverDeleted() {
        stubReferenced(Map.of(lower(PREFIX + "live"), 42L));
        when(client.listCatalogs()).thenReturn(List.of(PREFIX + "live"));

        Map<String, Object> out = admin.cleanup(true);

        List<Map<String, Object>> rows = rows(out);
        assertEquals("in-use", rows.get(0).get("category"));
        assertEquals(42L, rows.get(0).get("datasourceId"));
        verify(client, never()).deleteCatalog(anyString());
    }

    @Test
    void duplicateIsSkippedWhenMappingIsNotConfirmed() {
        // 数据源改名后同步失败：确定性命名存在，但 gravitino_catalog 还没确认
        Map<String, Object> row = new HashMap<>();
        row.put("id", 5L);
        row.put("name", "dup");
        row.put("ds_type", "DORIS");
        row.put("gravitino_catalog", null);
        row.put("gravitino_status", "error");
        stubExpected(List.of(row));
        when(client.listCatalogs())
                .thenReturn(List.of(GravitinoCatalogNaming.catalogName("default", "dup")));

        Map<String, Object> out = admin.cleanup(true);

        List<Map<String, Object>> rows = rows(out);
        assertEquals("duplicate", rows.get(0).get("category"));
        assertEquals(false, rows.get(0).get("deletable"));
        assertTrue(String.valueOf(rows.get(0).get("reason")).contains("映射未确认"));
        verify(client, never()).deleteCatalog(anyString());
    }

    @Test
    void confirmedDuplicateIsDeletedWhenAuthoritativeCatalogDiffers() {
        String stale = GravitinoCatalogNaming.catalogName("default", "old_name");
        stubExpected(List.of(Map.of("id", 7L, "name", "old_name", "ds_type", "DORIS",
                "gravitino_catalog", "default_old_name_new", "gravitino_status", "synced")));
        when(client.listCatalogs()).thenReturn(List.of(stale));

        Map<String, Object> out = admin.cleanup(true);

        assertEquals(1, ((List<?>) out.get("deleted")).size());
        verify(client).deleteCatalog(stale);
    }

    @Test
    void recheckBlocksDeletionWhenCatalogGotReferenced() {
        when(client.listCatalogs()).thenReturn(List.of(PREFIX + "orphan_b"));
        when(jdbc.queryForObject(contains("lower(gravitino_catalog)"), eq(Long.class),
                any(Object[].class))).thenReturn(2L);

        Map<String, Object> out = admin.cleanup(true);

        assertEquals(0, ((List<?>) out.get("deleted")).size());
        assertEquals(1, ((List<?>) out.get("skipped")).size());
        verify(client, never()).deleteCatalog(anyString());
    }

    @Test
    void deleteFailureIsReportedNotThrown() {
        when(client.listCatalogs()).thenReturn(List.of(PREFIX + "orphan_c"));
        doThrow(new RuntimeException("gravitino 500"))
                .when(client).deleteCatalog(PREFIX + "orphan_c");

        Map<String, Object> out = admin.cleanup(true);

        assertEquals(0, ((List<?>) out.get("deleted")).size());
        assertEquals(1, ((List<?>) out.get("failed")).size());
    }

    @Test
    void gravitinoUnavailableReturns503() {
        when(client.listCatalogs()).thenThrow(new RuntimeException("Connection refused"));

        ApiException e = assertThrows(ApiException.class, () -> admin.list());

        assertEquals(503, e.getStatus());
    }

    // -------------------------------------------------- catalog_meta 同名重复

    @Test
    void duplicateBuiltinCatalogIsListedAsDuplicateButNeverDeleted() {
        // Gravitino catalog_meta 里同名记录重复时，REST 枚举可能把 doris_dev 返回两次。
        // 大小写不敏感：不能因为大小写差异漏判。
        when(client.listCatalogs()).thenReturn(List.of("doris_dev", "DORIS_DEV"));

        Map<String, Object> dry = admin.list();

        assertEquals(true, dry.get("dryRun"));
        assertFalse(dry.containsKey("deleted"), "dry-run 不能返回删除结果");
        List<Map<String, Object>> rows = rows(dry);
        // 第 1 条是平台内置，保持 protected；第 2 条额外标成 duplicate
        assertEquals("protected", rows.get(0).get("category"));
        assertEquals("duplicate", rows.get(1).get("category"));
        assertEquals("protected", rows.get(1).get("duplicateOfCategory"));
        assertEquals(2, ((Number) rows.get(1).get("occurrence")).intValue());
        assertEquals(2, ((Number) rows.get(1).get("occurrences")).intValue());
        assertEquals(false, rows.get(1).get("deletable"));
        assertTrue(String.valueOf(rows.get(1).get("reason")).contains("catalog_meta"));
        Map<String, Object> summary = summary(dry);
        assertEquals(1L, ((Number) summary.get("repeated")).longValue());
        assertEquals(1L, ((Number) summary.get("duplicate")).longValue());
        verify(client, never()).deleteCatalog(anyString());

        Map<String, Object> applied = admin.cleanup(true);
        assertEquals(0, ((List<?>) applied.get("deleted")).size(),
                "平台内置 Catalog 的重复记录也不能删（删名字会删到正在用的那个）");
        verify(client, never()).deleteCatalog(anyString());
    }

    @Test
    void duplicateOrphanRecordsAreDeletedOnceWhenUnreferenced() {
        String orphan = PREFIX + "dup_orphan";
        when(client.listCatalogs()).thenReturn(List.of(orphan, orphan));

        Map<String, Object> out = admin.cleanup(true);

        List<Map<String, Object>> rows = rows(out);
        assertEquals("orphan", rows.get(0).get("category"));
        assertEquals(true, rows.get(0).get("deletable"));
        assertEquals("duplicate", rows.get(1).get("category"));
        assertEquals("orphan", rows.get(1).get("duplicateOfCategory"));
        assertEquals(true, rows.get(1).get("deletable"), "无引用的孤儿重复项允许清理");
        assertEquals(1, ((List<?>) out.get("deleted")).size());
        // 同名只 DELETE 一次：避免第二次误删「刚被重新创建」的同名 Catalog
        verify(client, times(1)).deleteCatalog(orphan);
    }

    @Test
    void duplicateInUseRecordsAreNeverDeleted() {
        String live = PREFIX + "live";
        stubReferenced(Map.of(lower(live), 42L));
        when(client.listCatalogs()).thenReturn(List.of(live, live));

        Map<String, Object> out = admin.cleanup(true);

        List<Map<String, Object>> rows = rows(out);
        assertEquals("in-use", rows.get(0).get("category"));
        assertEquals("duplicate", rows.get(1).get("category"));
        assertEquals("in-use", rows.get(1).get("duplicateOfCategory"));
        assertEquals(false, rows.get(1).get("deletable"));
        assertEquals(0, ((List<?>) out.get("deleted")).size());
        verify(client, never()).deleteCatalog(anyString());
    }

    // ---------------------------------------------------------------- 夹具

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("catalogs");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> out) {
        return (Map<String, Object>) out.get("summary");
    }

    private void stubReferenced(Map<String, Long> values) {
        List<Map<String, Object>> rows = values.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", entry.getValue());
                    row.put("gravitino_catalog", entry.getKey());
                    return row;
                })
                .toList();
        when(jdbc.queryForList(argThat(sql -> sql != null
                && sql.contains("where gravitino_catalog is not null")))).thenReturn(rows);
    }

    private void stubExpected(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(
                argThat(sql -> sql != null && sql.contains("upper(d.ds_type) = 'DORIS'")),
                any(Object[].class))).thenReturn(rows);
    }

    private static String lower(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
