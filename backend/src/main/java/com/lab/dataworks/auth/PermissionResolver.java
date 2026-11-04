package com.lab.dataworks.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限模型第二期（第一步）：判定来源从「角色枚举」切到「权限点并集」。
 *
 * <p>接口上的 {@link RequirePermission} 暂时仍写旧的 6 个点，这里把它们映射成
 * 权限点（每个旧点取一个所有内置角色都具备的代表点），因此**内置角色的行为不变**；
 * 自定义角色按实际权限点判定（缺代表点时会被保守拒绝，等第二步逐接口细化）。</p>
 */
@Component
public class PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(PermissionResolver.class);

    /** 旧权限点 → 必须具备的权限点（选每个旧点下所有内置角色共有的代表点）。 */
    private static final Map<Permission, List<String>> LEGACY_ATOMS = Map.of(
            Permission.READ, List.of("query:run"),
            Permission.DEV_WRITE, List.of("workflow:update"),
            Permission.OPS_RUN, List.of("ops:run"),
            Permission.SERVICE_PUBLISH, List.of("service:publish"),
            Permission.ALERT_MANAGE, List.of("alert:manage"),
            Permission.ADMIN, List.of("user:manage"));

    /** 通用读接口（未单独标注的 GET）只要有任一读权限点即可。 */
    private static final List<String> READ_ANY = List.of(
            "project:read", "workflow:read", "monitor:read", "sync:read",
            "datasource:read", "query:run", "catalog:read", "lineage:read",
            "quality:read", "service:read", "alert:read");

    private final RbacService rbac;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000L;

    private record Cached(Set<String> atoms, long at) {
    }

    public PermissionResolver(RbacService rbac) {
        this.rbac = rbac;
    }

    /** 当前用户在其默认租户下的权限点（60 秒缓存）。 */
    public Set<String> atoms(String username) {
        return atoms(username, rbac.defaultTenant(username));
    }

    /**
     * 指定租户下的权限点。同一用户在 A / B 两个租户可以有不同角色，
     * 所以缓存按「用户|租户」隔离，不能只按用户名缓存。
     */
    public Set<String> atoms(String username, String tenantCode) {
        String tenant = tenantCode == null || tenantCode.isBlank()
                ? rbac.defaultTenant(username) : tenantCode;
        if (tenant == null) {
            // 没有任何有效租户（租户被删除后的孤儿账号）：没有权限点，也不再回落 default
            return Set.of();
        }
        String key = username + "|" + tenant;
        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < TTL_MS) {
            return hit.atoms();
        }
        Set<String> atoms = rbac.permissions(username, tenant);
        cache.put(key, new Cached(atoms, now));
        return atoms;
    }

    public void invalidate(String username) {
        cache.keySet().removeIf(key -> key.startsWith(username + "|"));
    }

    public void invalidateAll() {
        cache.clear();
    }

    /**
     * 判定：优先用权限点；权限点为空时一律拒绝。
     *
     * <p>D03 后不再回退 {@code app_user.role} 旧枚举：租户被删除的孤儿账号没有有效成员
     * 关系，回退旧枚举会让它凭 ADMIN/VIEWER 读到 default 租户数据。V55 已把存量账号都
     * 写入 tenant_member，线上不存在「迁移没覆盖」的账号（实测 0 个），因此直接拒绝是安全的。</p>
     */
    public boolean has(String username, UserService.Account account, Permission required) {
        List<String> atoms = LEGACY_ATOMS.get(required);
        if (atoms == null || atoms.isEmpty()) {
            return account.roleEnum().has(required);
        }
        Set<String> owned = atoms(username, TenantScope.current());
        if (!owned.isEmpty()) {
            if (required == Permission.READ) {
                return READ_ANY.stream().anyMatch(owned::contains);
            }
            return owned.containsAll(atoms);
        }
        log.warn("permission atoms empty for user={} in tenant={}, deny (no active membership "
                        + "or roles cleared), legacy role={} ignored",
                username, TenantScope.current(), account.role());
        return false;
    }

    /** 账号是否至少属于一个有效租户（拦截器用来区分「孤儿账号」与「切到了别人租户」）。 */
    public boolean hasActiveTenant(String username) {
        return rbac.hasActiveTenant(username);
    }

    /**
     * 解析请求租户：没指定就用默认租户；指定了必须校验成员关系（不是成员返回 null）。
     */
    public String resolveTenant(String username, String requested) {
        if (requested == null || requested.isBlank()) {
            return rbac.defaultTenant(username);
        }
        String code = requested.trim();
        boolean member = rbac.tenants(username).stream()
                .anyMatch(row -> code.equals(row.get("code")));
        return member ? code : null;
    }

    /** 给 /me 与前端用：权限点按分组返回。 */
    public Map<String, Object> describe(String username) {
        String tenant = rbac.defaultTenant(username);
        if (tenant == null) {
            throw new com.lab.dataworks.common.ApiException(401,
                    "账号不属于任何有效租户，请联系平台管理员");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant);
        out.put("roles", rbac.roles(username, tenant));
        out.put("permissions", atoms(username, tenant));
        return out;
    }
}
