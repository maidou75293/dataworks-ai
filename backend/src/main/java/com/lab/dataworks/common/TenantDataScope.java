package com.lab.dataworks.common;

import com.lab.dataworks.auth.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 租户数据面边界。
 *
 * <p>平台只有一个内置 Doris，库归项目所有（项目建库取 {@code <business>_dev/_prd}）。
 * 之前表管理/直查接口在**不带 projectId** 时直接连内置 Doris 且不做任何过滤，
 * 外部审计实测：tenant_b 的用户能列出默认租户的库、<b>直接预览 1000 行真实订单</b>。</p>
 *
 * <p>规则：默认租户（平台自身）不受限；其他租户只能访问自己项目名下的库。
 * 对外一律用 404（不暴露「这个库存在但不属于你」）。</p>
 */
@Component
public class TenantDataScope {

    private final JdbcTemplate jdbc;

    public TenantDataScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean defaultTenant() {
        return TenantScope.DEFAULT.equalsIgnoreCase(TenantScope.current());
    }

    /**
     * 这张表是否已纳管（BUG-TC4-5：403 文案要分清「未授权」与「未纳管」）。
     *
     * <p>以前两者共用一句「如需访问请提交权限申请」，但未纳管的表在申请入口会
     * 404「资源不存在或未纳管」——等于把用户指向一条走不通的路。</p>
     */
    public boolean registeredTable(String database, String table) {
        if (database == null || database.isBlank() || table == null || table.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.managed_table
                 where lower(schema_name) = lower(?)
                   and (lower(table_name) = lower(?) or table_name = '*')
                """, Integer.class, database, table);
        return n != null && n > 0;
    }

    /**
     * 当前租户项目名下的库（dev + prod + 附加库登记 {@code platform.project_database}），小写。
     *
     * <p>V120 / REQ-DW-6：附加库与项目库同权属口径——库归项目所有，登记即拥有；
     * 列表/建表/预览等数据面继续只看本方法，不必逐点认表。</p>
     *
     * <p>两次查询分开跑、不并进同一条 SQL：老 stub（{@code queryForList(anyString(),
     * eq(String.class), any(), any())}）按 varargs 个数匹配，多一个绑定参数就不命中，
     * 会把老用例变成假绿/假红（D48 {@code WarehouseConnAuditTest} 同款坑）。</p>
     */
    public Set<String> ownedDatabases() {
        Set<String> out = new HashSet<>();
        List<String> rows = jdbc.queryForList("""
                select dev_database from platform.project
                 where tenant_id = ? and dev_database is not null and dev_database <> ''
                union
                select prod_database from platform.project
                 where tenant_id = ? and prod_database is not null and prod_database <> ''
                """, String.class, TenantScope.current(), TenantScope.current());
        rows.forEach(name -> out.add(name.toLowerCase(Locale.ROOT)));
        List<String> extra = jdbc.queryForList("""
                select database_name from platform.project_database
                 where tenant_id = ?
                """, String.class, TenantScope.current());
        extra.forEach(name -> out.add(name.toLowerCase(Locale.ROOT)));
        return out;
    }

    public boolean owns(String database) {
        if (database == null || database.isBlank()) {
            return false;
        }
        if (defaultTenant()) {
            return true;
        }
        return ownedDatabases().contains(database.toLowerCase(Locale.ROOT));
    }

    /**
     * 库的归属租户集合（D01）。
     *
     * <p>判据优先级：项目 {@code dev_database/prod_database} 是权威归属，其次
     * {@code project_database} 附加库登记（V120）；只有都没有证据时才回退到
     * {@code managed_table.tenant}（历史纳管数据）。</p>
     *
     * <p>返回空集合表示「没有归属证据」——外部数仓、共享库、尚未纳管的库都会走到这里，
     * 调用方不能据此判定为跨租户。</p>
     */
    public Set<String> ownerTenantsOf(String database) {
        if (database == null || database.isBlank()) {
            return Set.of();
        }
        String db = database.trim();
        Set<String> owners = new LinkedHashSet<>();
        List<String> byProject = jdbc.queryForList("""
                select distinct tenant_id from platform.project
                 where lower(coalesce(dev_database, '')) = lower(?)
                    or lower(coalesce(prod_database, '')) = lower(?)
                """, String.class, db, db);
        byProject.forEach(tenant -> addTenant(owners, tenant));
        if (!owners.isEmpty()) {
            return owners;
        }
        // V120 / REQ-DW-6：附加库登记表；项目库仍是第一权威，登记行其次，managed_table 只兜历史
        List<String> byExtra = jdbc.queryForList("""
                select distinct tenant_id from platform.project_database
                 where lower(database_name) = lower(?)
                """, String.class, db);
        byExtra.forEach(tenant -> addTenant(owners, tenant));
        if (!owners.isEmpty()) {
            return owners;
        }
        List<String> byManaged = jdbc.queryForList("""
                select distinct tenant from platform.managed_table
                 where lower(schema_name) = lower(?)
                """, String.class, db);
        byManaged.forEach(tenant -> addTenant(owners, tenant));
        return owners;
    }

    /**
     * 目标库是否明确属于<b>其他租户</b>（D01）。
     *
     * <p>只有「有归属证据且不等于当前租户」才返回 true。没有归属证据时 fail-open，
     * 避免把外部 Doris 的库名误判成跨租户。平台管理员不调用本方法（直接放行）。</p>
     */
    public boolean belongsToOtherTenant(String database) {
        return crossTenant(ownerTenantsOf(database), TenantScope.current());
    }

    /** 纯函数，便于单测锁住口径：归属集合非空且不含当前租户 → 跨租户。 */
    static boolean crossTenant(Set<String> owners, String tenant) {
        if (owners == null || owners.isEmpty()) {
            return false;
        }
        String current = tenant == null || tenant.isBlank() ? TenantScope.DEFAULT : tenant;
        return owners.stream().noneMatch(owner ->
                owner != null && owner.equalsIgnoreCase(current));
    }

    private static void addTenant(Set<String> out, String tenant) {
        if (tenant != null && !tenant.isBlank()) {
            out.add(tenant.trim());
        }
    }

    /** 非默认租户访问别的库 → 404（不暴露库是否存在）。 */
    public void requireDatabase(String database) {
        if (!owns(database)) {
            throw ApiException.notFound("库不存在: " + database);
        }
    }

    /** 直查/元数据这类「没有 projectId」的入口：租户没有任何数据面就直接拒绝。 */
    public void requireDataPlane() {
        if (!defaultTenant() && ownedDatabases().isEmpty()) {
            throw new ApiException(403, "当前租户没有数据面：请先创建项目并接入 Doris");
        }
    }
}
