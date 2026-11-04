package com.lab.dataworks.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 元数据读取网关（第二批）：{@code metadata.source=gravitino} 时，库列表/表列表/
 * 表结构（columns）从 Gravitino 读；Gravitino 超时/不可用/找不到对象时自动回退
 * 到调用方传入的「直连 Doris」实现，保持原有逻辑不变。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>列表类结果（库/表）缓存 {@code metadata.cache.ttl-seconds}（默认 45 秒，
 *       生产夹在 30–60 秒）；表结构也按同一 TTL 缓存，避免详情抽屉反复拉 columns。</li>
 *   <li>缓存按 catalog + kind + schema + table 做键；catalog 名由
 *       {@link MetadataCatalogResolver} 按项目/租户解析（租户隔离在 controller 层
 *       用 TenantDataScope 再过滤一次，缓存里不落租户可见性结论）。</li>
 *   <li>回退时把 {@code degraded=true} 写回响应，WARN 日志固定包含
 *       {@code kind/catalog/schema/table/hour/count/reason}，并按小时计数；
 *       下一次 Gravitino 读取成功会自动清除 degraded 标记。</li>
 *   <li>{@code metadata.source=direct} 时不访问 Gravitino、不缓存，直接走原有直连。</li>
 *   <li>{@code DDL} 不在 Gravitino 的元数据模型里：表结构走 Gravitino 时
 *       {@code ddl} 返回空串，前端隐藏该区块（SQL 执行路径未改动）。</li>
 * </ul>
 */
@Component
public class MetadataReader {

    public static final String SOURCE_GRAVITINO = "gravitino";
    public static final String SOURCE_DORIS = "doris";

    private static final Logger log = LoggerFactory.getLogger(MetadataReader.class);
    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("information_schema", "mysql", "__internal_schema");
    private static final int MAX_CACHE_ENTRIES = 4096;
    private static final DateTimeFormatter HOUR_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final GravitinoClient client;
    private final Supplier<String> sourceSupplier;
    private final Function<Long, String> catalogResolver;
    private final long ttlMs;
    private final LongSupplier clock;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> hourlyFallbacks = new ConcurrentHashMap<>();

    private volatile boolean degraded;
    private volatile long lastDegradedAt;
    private volatile String lastDegradedReason;

    @Autowired
    public MetadataReader(GravitinoClient client, MetadataSettings settings,
                          MetadataCatalogResolver catalogs,
                          @Value("${metadata.cache.ttl-seconds:45}") long ttlSeconds) {
        // 产品要求 30–60 秒：配置写错（0 / 3600）也夹回该区间
        this(client, settings::source, catalogs::catalogFor,
                Math.max(30, Math.min(60, ttlSeconds)) * 1000L, System::currentTimeMillis);
    }

    /** 测试用：可注入 source 读取器、catalog 解析器、TTL 与时钟。 */
    MetadataReader(GravitinoClient client, Supplier<String> sourceSupplier,
                   Function<Long, String> catalogResolver, long ttlMs, LongSupplier clock) {
        this.client = client;
        this.sourceSupplier = sourceSupplier;
        this.catalogResolver = catalogResolver;
        this.ttlMs = Math.max(0L, ttlMs);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    private record CacheEntry(long at, Object data) {
    }

    /**
     * 一次读取的结果。
     *
     * @param data     实际数据（Gravitino 或直连回退）
     * @param source   {@link #SOURCE_GRAVITINO} / {@link #SOURCE_DORIS}
     * @param degraded 是否因 Gravitino 故障回退到直连
     * @param cached   是否命中进程内缓存（仅 Gravitino 路径）
     */
    public record Read<T>(T data, String source, boolean degraded, boolean cached) {

        public boolean fromGravitino() {
            return SOURCE_GRAVITINO.equals(source);
        }

        /** 接口返回字段：metadataSource / degraded（+ metadataCached 便于排查）。 */
        public Map<String, Object> meta() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("metadataSource", source);
            out.put("degraded", degraded);
            out.put("metadataCached", cached);
            return out;
        }
    }

    // ---------------------------------------------------------------- 对外读取

    /** 库列表：{@code [{database, tables}]}。 */
    public Read<List<Map<String, Object>>> databases(
            Long projectId, Supplier<List<Map<String, Object>>> fallback) {
        return read(projectId, "databases", null, null, fallback, this::loadDatabases);
    }

    /** 表列表：{@code [{table, comment, rows, database}]}。 */
    public Read<List<Map<String, Object>>> tables(
            Long projectId, String database, Supplier<List<Map<String, Object>>> fallback) {
        return read(projectId, "tables", database, null, fallback,
                catalog -> loadTables(catalog, database));
    }

    /** 表结构：{@code {database, table, columns, ddl}}。 */
    public Read<Map<String, Object>> table(
            Long projectId, String database, String table,
            Supplier<Map<String, Object>> fallback) {
        return read(projectId, "table", database, table, fallback,
                catalog -> loadTable(catalog, database, table));
    }

    // ---------------------------------------------------------------- 缓存与回退

    private <T> Read<T> read(Long projectId, String kind, String database, String table,
                             Supplier<T> fallback, Function<String, T> loader) {
        String configured = sourceSupplier == null ? MetadataSettings.GRAVITINO : sourceSupplier.get();
        if (!MetadataSettings.GRAVITINO.equalsIgnoreCase(
                configured == null ? "" : configured.trim())) {
            // direct 开关：完全不碰 Gravitino，也不缓存，行为与第一批之前一致
            return new Read<>(fallback.get(), SOURCE_DORIS, false, false);
        }

        String catalog;
        try {
            catalog = catalogResolver == null ? null : catalogResolver.apply(projectId);
        } catch (RuntimeException e) {
            log.warn("resolve metadata catalog failed projectId={}: {}", projectId, e.getMessage());
            catalog = null;
        }
        if (catalog == null || catalog.isBlank()) {
            // 该入口没有对应 Catalog（非 DORIS 项目/未同步数据源）：正常直连，不降级
            return new Read<>(fallback.get(), SOURCE_DORIS, false, false);
        }
        catalog = catalog.trim();

        long now = clock.getAsLong();
        String key = cacheKey(catalog, kind, database, table);
        if (ttlMs > 0) {
            CacheEntry hit = cache.get(key);
            if (hit != null && now - hit.at() < ttlMs) {
                @SuppressWarnings("unchecked")
                T data = (T) hit.data();
                return new Read<>(data, SOURCE_GRAVITINO, false, true);
            }
        }

        try {
            T data = loader.apply(catalog);
            if (data == null) {
                // Gravitino 不认识这个对象（典型是表刚建/刚删，或 Catalog 未同步）：
                // 用直连拿实时结果，但这不属于「元数据服务不可用」，不标 degraded。
                return new Read<>(fallback.get(), SOURCE_DORIS, false, false);
            }
            cache.put(key, new CacheEntry(now, data));
            evict(now);
            degraded = false;
            lastDegradedReason = null;
            return new Read<>(data, SOURCE_GRAVITINO, false, false);
        } catch (RuntimeException e) {
            degrade(kind, catalog, database, table, e);
            // 回退保持原直连逻辑；直连自己抛的 404/502 照常向上抛
            return new Read<>(fallback.get(), SOURCE_DORIS, true, false);
        }
    }

    /**
     * 建表/删表/建库后让对应 catalog 的缓存全部失效。
     * catalog 为空时是无操作（调用方拿不到映射不该报错）。
     */
    public void invalidateCatalog(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return;
        }
        String prefix = catalog.trim() + "|";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 按项目解析 catalog 后失效；解析失败只记日志（不阻塞 DDL 成功返回）。 */
    public void invalidateProject(Long projectId) {
        try {
            invalidateCatalog(catalogResolver == null ? null : catalogResolver.apply(projectId));
        } catch (RuntimeException e) {
            log.warn("invalidate metadata cache failed projectId={}: {}", projectId, e.getMessage());
        }
    }

    /** 全量清理（数据源删除/切换来源时兜底）。 */
    public void invalidateAll() {
        cache.clear();
        // 切换来源后「上一来源的降级诊断」不再有意义：不清掉会让页面在新来源下
        // 继续显示旧的降级横幅与原因。
        degraded = false;
        lastDegradedAt = 0L;
        lastDegradedReason = null;
    }

    /** 当前进程的降级状态与小时计数，供 /api/metadata/source 与排障使用。 */
    public Map<String, Object> stats() {
        long now = clock.getAsLong();
        String hour = hourKey(now);
        AtomicLong counter = hourlyFallbacks.get(hour);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metadataSource", sourceSupplier == null
                ? MetadataSettings.GRAVITINO : sourceSupplier.get());
        out.put("degraded", degraded);
        out.put("degradedCount", counter == null ? 0L : counter.get());
        out.put("degradedCountHour", hour);
        out.put("lastDegradedAt", lastDegradedAt == 0L ? null
                : Instant.ofEpochMilli(lastDegradedAt).toString());
        out.put("lastDegradedReason", lastDegradedReason);
        out.put("cacheTtlSeconds", ttlMs / 1000);
        out.put("cachedEntries", cache.size());
        return out;
    }

    // ---------------------------------------------------------------- Gravitino 归一化

    private List<Map<String, Object>> loadDatabases(String catalog) {
        List<String> schemas = client.listSchemas(catalog);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String schema : schemas) {
            if (skipSchema(schema)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("database", schema);
            // Gravitino 的 schema 列表不带表数量，逐库取一次 identifiers（整体走 TTL 缓存）
            item.put("tables", client.listTables(catalog, schema).size());
            out.add(item);
        }
        out.sort(Comparator.comparing(item -> String.valueOf(item.get("database"))));
        return out;
    }

    private List<Map<String, Object>> loadTables(String catalog, String database) {
        List<String> names = client.listTables(catalog, database);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", name);
            // Gravitino 的 identifiers 只有名字；表注释/行数估算不在其元数据模型里，
            // 保持字段存在但为 null，前端显示 "-"（不再逐表 N+1 拉详情）。
            item.put("comment", null);
            item.put("rows", null);
            item.put("database", database);
            out.add(item);
        }
        out.sort(Comparator.comparing(item -> String.valueOf(item.get("table"))));
        return out;
    }

    private Map<String, Object> loadTable(String catalog, String database, String table) {
        Map<String, Object> raw = client.getTable(catalog, database, table);
        if (raw == null) {
            return null;
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        if (raw.get("columns") instanceof List<?> rawColumns) {
            for (Object element : rawColumns) {
                if (!(element instanceof Map<?, ?> column)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", value(column, "name"));
                item.put("type", value(column, "type"));
                item.put("nullable", boolValue(value(column, "nullable"), true));
                // Gravitino 列模型没有 Doris 的 Key 标记；编辑弹窗按非主键展示
                item.put("key", null);
                item.put("comment", value(column, "comment"));
                columns.add(item);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", database);
        out.put("table", table);
        out.put("columns", columns);
        // DDL 不来自 Gravitino（SQL 执行路径不变），前端 ddl 为空时隐藏「建表 DDL」
        out.put("ddl", "");
        return out;
    }

    // ---------------------------------------------------------------- 内部工具

    private void degrade(String kind, String catalog, String database, String table,
                         RuntimeException error) {
        degraded = true;
        lastDegradedAt = clock.getAsLong();
        String reason = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        lastDegradedReason = reason.length() > 300 ? reason.substring(0, 300) + "…" : reason;

        String hour = hourKey(lastDegradedAt);
        // 只保留当前小时的计数，避免长期运行后 map 无限增长
        hourlyFallbacks.keySet().removeIf(key -> !key.equals(hour));
        long count = hourlyFallbacks
                .computeIfAbsent(hour, ignored -> new AtomicLong())
                .incrementAndGet();
        log.warn("metadata degrade: fallback to doris kind={} catalog={} schema={} table={} "
                        + "hour={} count={} reason={}",
                kind, catalog, dash(database), dash(table), hour, count, reason);
    }

    private void evict(long now) {
        if (cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        if (ttlMs > 0) {
            cache.entrySet().removeIf(entry -> now - entry.getValue().at() >= ttlMs);
        }
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.clear();
        }
    }

    private static String cacheKey(String catalog, String kind, String database, String table) {
        return catalog + "|" + kind + "|" + dash(database) + "|" + dash(table);
    }

    private static boolean skipSchema(String schema) {
        return schema == null || schema.isBlank() || SYSTEM_SCHEMAS.contains(schema);
    }

    private static String hourKey(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(HOUR_FORMAT);
    }

    private static String dash(String value) {
        return value == null ? "-" : value;
    }

    private static Object value(Map<?, ?> map, String key) {
        Object direct = map.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean boolValue(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }
}
