package com.lab.dataworks.datasource;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.metadata.GravitinoCatalogNaming;
import com.lab.dataworks.metadata.GravitinoClient;
import com.lab.dataworks.metadata.MetadataCatalogResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gravitino Catalog 巡检与清理（收尾）。
 *
 * <p>Gravitino 是外部元数据服务，同步是「投影」：数据源删除时 Gravitino 不可达、
 * 改名时旧 Catalog 只删了平台映射等情况都会留下没人引用的 Catalog。本服务提供
 * 管理员可手动执行的巡检，默认 dry-run，只有 {@code confirm=true} 才真正删除。</p>
 *
 * <p>安全边界（任何一条不满足就跳过并给出原因，绝不「猜着删」）：</p>
 * <ul>
 *   <li>只看当前租户命名空间（{@link GravitinoCatalogNaming#tenantPrefix}）里的
 *       Catalog；平台内置（{@code gravitino.builtin-catalog}）与其他租户/平台级
 *       Catalog 一律标为 protected，不动。</li>
 *   <li>被任意租户数据源登记在 {@code gravitino_catalog} 上的一律 in-use；删除前
 *       再复检一次引用，防止「枚举之后、删除之前」又被保存的数据源引用。</li>
 *   <li>按确定性命名算出「本该属于某个数据源、但这个数据源已经指向别的 Catalog」
 *       的重复残留时才允许清理；映射为空或状态不是 synced 的（同步失败/未同步）
 *       说明无法确认权威名字，跳过。</li>
 *   <li>Gravitino 的 {@code catalog_meta} 在「删除后同名重建」「并发创建」时会留下
 *       同名记录，REST 枚举可能把同一个名字返回多次。第 1 条按正常规则分类，
 *       后续同名条目一律额外标成 {@code duplicate}（带 occurrence/occurrences），
 *       且只有当正常分类本身可删（孤儿、无任何引用）时才允许删除；
 *       平台内置（如 {@code doris_dev}）或仍被引用的名字即使重复也绝不删。</li>
 * </ul>
 */
@Service
public class GravitinoCatalogAdmin {

    private static final Logger log = LoggerFactory.getLogger(GravitinoCatalogAdmin.class);

    public static final String CATEGORY_IN_USE = "in-use";
    public static final String CATEGORY_PROTECTED = "protected";
    public static final String CATEGORY_ORPHAN = "orphan";
    public static final String CATEGORY_DUPLICATE = "duplicate";

    private final JdbcTemplate jdbc;
    private final GravitinoClient client;
    private final MetadataCatalogResolver catalogs;

    public GravitinoCatalogAdmin(JdbcTemplate jdbc, GravitinoClient client,
                                 MetadataCatalogResolver catalogs) {
        this.jdbc = jdbc;
        this.client = client;
        this.catalogs = catalogs;
    }

    /** 巡检（dry-run）：只分类，不删除。 */
    public Map<String, Object> list() {
        return inspect(false);
    }

    /** 清理：confirm=false 与 {@link #list()} 完全一致；confirm=true 才删除可清理项。 */
    public Map<String, Object> cleanup(boolean confirm) {
        return inspect(confirm);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> inspect(boolean confirm) {
        String tenant = TenantScope.current();
        String prefix = GravitinoCatalogNaming.tenantPrefix(tenant);
        String builtin = catalogs == null ? null : catalogs.builtinCatalog();

        List<String> remote;
        try {
            remote = client.listCatalogs();
        } catch (RuntimeException e) {
            // 枚举不到就不能假装「没有孤儿」：明确告诉管理员 Gravitino 不可用
            throw new ApiException(503, "Gravitino 不可用，无法枚举 Catalog: " + message(e));
        }

        // 任意租户登记的引用：跨租户也不能误删别人的 Catalog
        Map<String, Long> referenced = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select id, gravitino_catalog from platform.datasource
                 where gravitino_catalog is not null and btrim(gravitino_catalog) <> ''
                """)) {
            String name = text(row.get("gravitino_catalog"));
            if (name != null) {
                referenced.put(name.trim().toLowerCase(Locale.ROOT), number(row.get("id")));
            }
        }

        // 当前租户 DORIS 数据源 → 确定性命名
        Map<String, Ref> expected = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select d.id, d.name, d.ds_type, d.gravitino_catalog, d.gravitino_status
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where p.tenant_id = ?
                   and upper(d.ds_type) = 'DORIS'
                   and coalesce(d.config_json ->> 'builtin', 'false') <> 'true'
                """, tenant)) {
            Ref ref = new Ref(number(row.get("id")), text(row.get("name")),
                    text(row.get("gravitino_catalog")), text(row.get("gravitino_status")));
            expected.put(GravitinoCatalogNaming.catalogName(tenant, ref.name()), ref);
        }

        // 同名记录计数（大小写不敏感）：catalog_meta 里同名的软删/并发残留会让
        // REST 枚举把同一个名字返回多次，必须显式识别，不能当成两个独立 Catalog。
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String name : remote) {
            if (name == null || name.isBlank()) {
                continue;
            }
            occurrences.merge(name.trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String name : remote) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String key = name.trim().toLowerCase(Locale.ROOT);
            int occurrence = seen.merge(key, 1, Integer::sum);
            entries.add(classify(name, prefix, builtin, referenced, expected,
                    occurrence, occurrences.getOrDefault(key, 1)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", !confirm);
        out.put("tenant", tenant);
        out.put("namespacePrefix", prefix);
        out.put("catalogs", entries);
        out.put("summary", summarize(entries));

        if (confirm) {
            out.putAll(delete(entries, prefix));
        }
        return out;
    }

    private Map<String, Object> classify(String name, String prefix, String builtin,
                                         Map<String, Long> referenced,
                                         Map<String, Ref> expected,
                                         int occurrence, int occurrences) {
        Map<String, Object> item = baseClassify(name, prefix, builtin, referenced, expected);
        if (occurrences <= 1) {
            return item;
        }
        // 同名多条：第 1 条是「正常分类」，后续条目额外标为 duplicate。
        item.put("occurrence", occurrence);
        item.put("occurrences", occurrences);
        if (occurrence <= 1) {
            return item;
        }
        Object baseCategory = item.get("category");
        boolean baseDeletable = Boolean.TRUE.equals(item.get("deletable"));
        String baseReason = String.valueOf(item.get("reason"));
        item.put("duplicateOfCategory", baseCategory);
        // 只有正常分类本身可删（孤儿、无引用、非平台内置）时，重复副本才可删。
        item.put("deletable", baseDeletable);
        return categorize(item, CATEGORY_DUPLICATE,
                "Gravitino catalog_meta 中同名记录重复（第 " + occurrence + "/" + occurrences
                        + " 条）；基准分类 " + baseCategory + "：" + baseReason);
    }

    private Map<String, Object> baseClassify(String name, String prefix, String builtin,
                                             Map<String, Long> referenced,
                                             Map<String, Ref> expected) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("deletable", false);
        String lower = name.toLowerCase(Locale.ROOT);

        if (builtin != null && !builtin.isBlank() && builtin.equalsIgnoreCase(name)) {
            return categorize(item, CATEGORY_PROTECTED,
                    "平台内置 Doris Catalog（gravitino.builtin-catalog），不清理");
        }
        Long owner = referenced.get(lower);
        if (owner != null) {
            item.put("datasourceId", owner);
            return categorize(item, CATEGORY_IN_USE, "仍被数据源 " + owner + " 引用");
        }
        if (!lower.startsWith(prefix)) {
            return categorize(item, CATEGORY_PROTECTED,
                    "不在当前租户命名空间（平台级或其他租户），不清理");
        }
        Ref ref = expected.get(lower);
        if (ref != null) {
            item.put("datasourceId", ref.id());
            boolean authoritative = ref.catalog() != null && !ref.catalog().isBlank()
                    && "synced".equalsIgnoreCase(String.valueOf(ref.status()));
            if (!authoritative) {
                // 映射为空/同步失败：可能是下一次保存要复用的 Catalog，删了会丢元数据
                return categorize(item, CATEGORY_DUPLICATE,
                        "数据源 " + ref.id() + " 的确定性命名重复，但映射未确认"
                                + "（catalog=" + dash(ref.catalog())
                                + ", status=" + dash(ref.status()) + "），跳过");
            }
            item.put("deletable", true);
            return categorize(item, CATEGORY_DUPLICATE,
                    "数据源 " + ref.id() + " 的权威 Catalog 是 " + ref.catalog()
                            + "，该名字是重命名后的重复残留");
        }
        item.put("deletable", true);
        return categorize(item, CATEGORY_ORPHAN,
                "Gravitino 中存在但没有数据源引用（命名空间 " + prefix + "）");
    }

    private Map<String, Object> delete(List<Map<String, Object>> entries, String prefix) {
        List<Map<String, Object>> deleted = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        // 同名条目可能被枚举多次（catalog_meta 重复）：每个名字只执行一次删除，
        // 避免第二次 DELETE 误删「刚被重新创建」的同名 Catalog（TOCTOU）。
        java.util.Set<String> handled = new java.util.HashSet<>();
        for (Map<String, Object> entry : entries) {
            String name = String.valueOf(entry.get("name"));
            if (!Boolean.TRUE.equals(entry.get("deletable"))) {
                continue;
            }
            if (!handled.add(name.toLowerCase(Locale.ROOT))) {
                log.info("gravitino catalog duplicated in listing, delete once: {}", name);
                continue;
            }
            String reason = recheck(name, prefix);
            if (reason != null) {
                skipped.add(skip(name, reason));
                continue;
            }
            try {
                client.deleteCatalog(name);
                deleted.add(Map.of("name", name, "category", entry.get("category")));
                log.info("gravitino catalog cleaned: {}", name);
            } catch (RuntimeException e) {
                // 单个删除失败不影响其他项；响应里给出原因，管理员可重试
                failed.add(Map.of("name", name, "error", message(e)));
                log.warn("gravitino catalog cleanup failed name={}: {}", name, message(e));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("skipped", skipped);
        out.put("failed", failed);
        return out;
    }

    /** 删除前复检：命名空间 + 是否已被引用。返回 null 表示可以删。 */
    private String recheck(String name, String prefix) {
        if (!name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return "复检：不在当前租户命名空间";
        }
        Long refs = jdbc.queryForObject("""
                select count(*) from platform.datasource
                 where lower(gravitino_catalog) = lower(?)
                """, Long.class, name);
        if (refs != null && refs > 0) {
            return "复检：已被 " + refs + " 个数据源引用，取消删除";
        }
        return null;
    }

    private static Map<String, Object> categorize(Map<String, Object> item, String category,
                                                  String reason) {
        item.put("category", category);
        item.put("reason", reason);
        return item;
    }

    private static Map<String, Object> skip(String name, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("reason", reason);
        return out;
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", entries.size());
        for (String category : List.of(CATEGORY_IN_USE, CATEGORY_PROTECTED,
                CATEGORY_ORPHAN, CATEGORY_DUPLICATE)) {
            out.put(category, entries.stream()
                    .filter(entry -> category.equals(entry.get("category")))
                    .count());
        }
        out.put("deletable", entries.stream()
                .filter(entry -> Boolean.TRUE.equals(entry.get("deletable")))
                .count());
        // catalog_meta 同名重复：按「名字出现 >1 次」统计去重后的名字数，
        // 便于管理员判断是 Gravitino 内部历史残留还是平台同步问题。
        out.put("repeated", entries.stream()
                .filter(entry -> entry.get("occurrences") instanceof Number n && n.intValue() > 1)
                .map(entry -> String.valueOf(entry.get("name")).toLowerCase(Locale.ROOT))
                .distinct()
                .count());
        return out;
    }

    private static String message(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private record Ref(long id, String name, String catalog, String status) {
    }
}
