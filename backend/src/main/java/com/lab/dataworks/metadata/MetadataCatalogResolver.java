package com.lab.dataworks.metadata;

import com.lab.dataworks.auth.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 项目 → Gravitino Catalog 的解析（第二批元数据读取）。
 *
 * <p>项目只引用一个 DORIS 数据源（V98），所以读取哪套元数据的规则是确定的：</p>
 * <ul>
 *   <li>外部 DORIS 数据源：用 V101 同步落库的 {@code gravitino_catalog}
 *       （status 不参与判断——最后一次同步成功过就说明名字可用；真不可用时
 *       {@link MetadataReader} 会回退直连并标记 degraded）。</li>
 *   <li>内置 Doris：用平台预置的 {@code gravitino.builtin-catalog}（默认
 *       {@code doris_dev}，见 scripts/ops/gravitino-init.sh）。内置数据源本身
 *       不参与 V101 同步，但 Gravitino 里仍有一个指向它的 Catalog。</li>
 *   <li>没有数据源的老项目、非 DORIS 项目、映射为空：返回 null，
 *       调用方按「直连 Doris」处理，不产生降级告警。</li>
 * </ul>
 *
 * <p>任何数据库/读取异常都返回 null 并 WARN：元数据来源解析不能把表管理接口打挂。</p>
 */
@Component
public class MetadataCatalogResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataCatalogResolver.class);

    private final JdbcTemplate jdbc;
    private final String builtinCatalog;

    public MetadataCatalogResolver(JdbcTemplate jdbc,
                                   @Value("${gravitino.builtin-catalog:doris_dev}") String builtinCatalog) {
        this.jdbc = jdbc;
        this.builtinCatalog = builtinCatalog == null ? "" : builtinCatalog.trim();
    }

    /** 内置 Doris 对应的 Catalog 名；空字符串表示不为内置 Doris 走 Gravitino。 */
    public String builtinCatalog() {
        return builtinCatalog;
    }

    /**
     * @param projectId null = 平台内置 Doris
     * @return Gravitino catalog 名；null/空白表示该入口应直连 Doris
     */
    public String catalogFor(Long projectId) {
        if (projectId == null) {
            return builtinCatalog.isBlank() ? null : builtinCatalog;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select d.name, d.ds_type, d.gravitino_catalog,
                           coalesce(d.config_json ->> 'builtin', 'false') as builtin,
                           p.warehouse_datasource_id, p.doris_connection_id
                      from platform.project p
                      left join platform.datasource d on d.id = p.warehouse_datasource_id
                     where p.id = ? and p.tenant_id = ?
                    """, projectId, TenantScope.current());
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String type = text(row.get("ds_type"));
            if (type == null || type.isBlank()) {
                // 老项目还没回填 warehouse_datasource_id：
                // 有旧 doris_connection_id = 外部 Doris，没有 Catalog 映射，直连；
                // 两者都没有才与 WarehouseResolver 一致按内置 Doris 处理。
                return row.get("doris_connection_id") == null && !builtinCatalog.isBlank()
                        ? builtinCatalog : null;
            }
            if (!"DORIS".equalsIgnoreCase(type)) {
                return null;
            }
            if (truthy(row.get("builtin"))) {
                return builtinCatalog.isBlank() ? null : builtinCatalog;
            }
            String catalog = text(row.get("gravitino_catalog"));
            return catalog == null || catalog.isBlank() ? null : catalog.trim();
        } catch (RuntimeException e) {
            log.warn("resolve metadata catalog failed projectId={}: {}", projectId, e.getMessage());
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
