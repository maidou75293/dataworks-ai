package com.lab.dataworks.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第二批：元数据读取网关的缓存、回退、降级标记与 direct 开关。
 *
 * <p>全部用 Mockito 直接构造，不依赖 Spring/PG/Doris；时钟可推进，TTL 可控。</p>
 */
class MetadataReaderTest {

    private GravitinoClient client;
    private long now;

    @BeforeEach
    void setUp() {
        client = mock(GravitinoClient.class);
        now = 1_700_000_000_000L;
    }

    private MetadataReader reader(String source, long ttlMs) {
        return new MetadataReader(client, () -> source, projectId -> "cat",
                ttlMs, () -> now);
    }

    @Test
    void listCacheHitsWithinTtl() {
        when(client.listSchemas("cat")).thenReturn(List.of("demo"));
        when(client.listTables("cat", "demo")).thenReturn(List.of("t1", "t2"));
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);

        List<Map<String, Object>> fallback = List.of(Map.of("database", "fallback", "tables", 0));
        MetadataReader.Read<List<Map<String, Object>>> first = reader.databases(null, () -> fallback);

        assertTrue(first.fromGravitino());
        assertFalse(first.degraded());
        assertFalse(first.cached());
        assertEquals(2, first.data().get(0).get("tables"));

        now += 1_000;
        MetadataReader.Read<List<Map<String, Object>>> second = reader.databases(null, () -> fallback);

        assertTrue(second.cached());
        assertEquals(first.data(), second.data());
        verify(client, times(1)).listSchemas("cat");
        verify(client, times(1)).listTables("cat", "demo");
    }

    @Test
    void cacheExpiresAfterTtl() {
        when(client.listSchemas("cat")).thenReturn(List.of("demo"));
        when(client.listTables("cat", "demo")).thenReturn(List.of("t1"));
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);

        reader.databases(null, List::of);
        now += 45_001;
        MetadataReader.Read<List<Map<String, Object>>> again = reader.databases(null, List::of);

        assertFalse(again.cached());
        verify(client, times(2)).listSchemas("cat");
    }

    @Test
    void invalidationRefetchesOnNextRead() {
        when(client.listSchemas("cat")).thenReturn(List.of("demo"));
        when(client.listTables("cat", "demo")).thenReturn(List.of("t1"));
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);

        reader.databases(null, List::of);
        reader.invalidateCatalog("cat");
        now += 1_000;
        MetadataReader.Read<List<Map<String, Object>>> again = reader.databases(null, List::of);

        assertFalse(again.cached());
        verify(client, times(2)).listSchemas("cat");
    }

    @Test
    void unavailableGravitinoFallsBackAndMarksDegraded() {
        when(client.listSchemas(anyString())).thenThrow(new GravitinoException(
                GravitinoException.UNAVAILABLE, 503, "GET /schemas", "连通失败", true));
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);
        List<Map<String, Object>> fallback = List.of(Map.of("database", "direct_db", "tables", 7));

        MetadataReader.Read<List<Map<String, Object>>> read = reader.databases(null, () -> fallback);

        assertEquals(MetadataReader.SOURCE_DORIS, read.source());
        assertTrue(read.degraded());
        assertFalse(read.cached());
        assertSame(fallback, read.data());
        // 接口字段：metadataSource=doris + degraded=true
        assertEquals("doris", read.meta().get("metadataSource"));
        assertEquals(true, read.meta().get("degraded"));
        // 进程状态与小时计数
        Map<String, Object> stats = reader.stats();
        assertEquals(true, stats.get("degraded"));
        assertEquals(1L, stats.get("degradedCount"));
        assertTrue(String.valueOf(stats.get("lastDegradedReason")).contains("连通失败"));
    }

    @Test
    void successfulReadClearsDegradedFlag() {
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);
        when(client.listSchemas("cat")).thenThrow(new GravitinoException(
                GravitinoException.UNAVAILABLE, 503, "GET /schemas", "down", true));
        reader.databases(null, List::of);
        assertTrue((Boolean) reader.stats().get("degraded"));

        // 下一次成功读取（不同 kind，避免命中因失败未写入的缓存）
        when(client.listTables("cat", "demo")).thenReturn(List.of("t1"));
        MetadataReader.Read<List<Map<String, Object>>> ok = reader.tables(null, "demo", List::of);

        assertTrue(ok.fromGravitino());
        assertEquals(false, reader.stats().get("degraded"));
    }

    @Test
    void directSwitchNeverCallsGravitino() {
        MetadataReader reader = reader(MetadataSettings.DIRECT, 45_000);
        List<Map<String, Object>> fallback = List.of(Map.of("database", "direct_db", "tables", 3));

        MetadataReader.Read<List<Map<String, Object>>> read = reader.databases(null, () -> fallback);

        assertEquals(MetadataReader.SOURCE_DORIS, read.source());
        assertFalse(read.degraded());
        assertFalse(read.cached());
        assertSame(fallback, read.data());
        verifyNoInteractions(client);
    }

    @Test
    void missingCatalogMappingUsesDirectWithoutDegrading() {
        MetadataReader direct = new MetadataReader(client, () -> MetadataSettings.GRAVITINO,
                projectId -> null, 45_000, () -> now);
        List<Map<String, Object>> fallback = List.of(Map.of("database", "internal", "tables", 1));

        MetadataReader.Read<List<Map<String, Object>>> read = direct.databases(null, () -> fallback);

        assertEquals(MetadataReader.SOURCE_DORIS, read.source());
        assertFalse(read.degraded());
        verifyNoInteractions(client);
    }

    @Test
    void unknownTableFallsBackWithoutMarkingDegraded() {
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);
        when(client.getTable("cat", "db", "t")).thenReturn(null);
        Map<String, Object> fallback = Map.of("database", "db", "table", "t", "columns", List.of());

        MetadataReader.Read<Map<String, Object>> read = reader.table(null, "db", "t", () -> fallback);

        assertEquals(MetadataReader.SOURCE_DORIS, read.source());
        assertFalse(read.degraded());
        assertSame(fallback, read.data());
    }

    @Test
    void invalidateAllClearsDegradedDiagnostics() {
        when(client.listSchemas(anyString())).thenThrow(new GravitinoException(
                GravitinoException.UNAVAILABLE, 503, "GET /schemas", "down", true));
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);
        reader.databases(null, List::of);

        Map<String, Object> before = reader.stats();
        assertEquals(true, before.get("degraded"));

        reader.invalidateAll();

        Map<String, Object> after = reader.stats();
        assertEquals(false, after.get("degraded"));
        assertEquals(null, after.get("lastDegradedReason"));
        assertEquals(null, after.get("lastDegradedAt"));
        assertEquals(0, after.get("cachedEntries"));
    }

    @Test
    void tableColumnsAreNormalizedFromGravitino() {
        MetadataReader reader = reader(MetadataSettings.GRAVITINO, 45_000);
        when(client.getTable("cat", "db", "t")).thenReturn(Map.of(
                "name", "t",
                "columns", List.of(
                        Map.of("name", "id", "type", "long", "nullable", false, "comment", "主键"),
                        Map.of("name", "name", "type", "string", "nullable", true))));

        MetadataReader.Read<Map<String, Object>> read = reader.table(null, "db", "t",
                () -> Map.of());

        assertTrue(read.fromGravitino());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) read.data().get("columns");
        assertEquals(2, columns.size());
        assertEquals("id", columns.get(0).get("name"));
        assertEquals(false, columns.get(0).get("nullable"));
        assertEquals("主键", columns.get(0).get("comment"));
        assertEquals("", read.data().get("ddl"));
        verify(client, never()).listSchemas(anyString());
    }
}
