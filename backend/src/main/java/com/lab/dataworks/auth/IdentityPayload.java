package com.lab.dataworks.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录 / 当前用户响应里的身份视图。
 *
 * <p>第三期起「角色」不再是 app_user.role 一个枚举值，而是租户内的角色集合
 * （{@code member_role}），实际能做什么由权限点决定。因此响应同时给出：</p>
 * <ul>
 *   <li>{@code roles} / {@code roleLabels}：当前租户里的角色编码与中文名；</li>
 *   <li>{@code atoms}：当前租户里的权限点并集（前端按权限点裁剪菜单与按钮）；</li>
 *   <li>{@code role} / {@code permissions}：旧字段，保留给升级前的客户端。</li>
 * </ul>
 */
@Component
public class IdentityPayload {

    private final PermissionResolver permissions;
    private final RbacService rbac;

    public IdentityPayload(PermissionResolver permissions, RbacService rbac) {
        this.permissions = permissions;
        this.rbac = rbac;
    }

    public Map<String, Object> login(String token, UserService.Account account) {
        return login(token, account, null);
    }

    /** 指定租户签发身份（登录带 tenant / switch-tenant 用）。 */
    public Map<String, Object> login(String token, UserService.Account account, String tenantOverride) {
        Map<String, Object> out = describe(account, tenantOverride);
        out.put("token", token);
        return out;
    }

    public Map<String, Object> describe(UserService.Account account) {
        return describe(account, null);
    }

    public Map<String, Object> describe(UserService.Account account, String tenantOverride) {
        String username = account.username();
        // 登录请求不带 X-Tenant-Id，此时不能直接用 current()——它把「未设置」
        // 也返回 "default"，会让被邀请到其他租户的用户登录后落在 default 下、角色为空。
        String tenant = tenantOverride;
        if (tenant == null || tenant.isBlank()) {
            tenant = TenantScope.currentOrNull();
        }
        if (tenant == null || tenant.isBlank()) {
            tenant = rbac.defaultTenant(username);
        }
        if (tenant == null) {
            // 租户被删除后的孤儿账号：不再回落到 default 生成身份（D03）
            throw new com.lab.dataworks.common.ApiException(401,
                    "账号不属于任何有效租户，请联系平台管理员");
        }
        List<String> roles = rbac.roles(username, tenant);
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> row : rbac.rolesOfTenant(tenant)) {
            names.put(String.valueOf(row.get("code")), String.valueOf(row.get("name")));
        }
        List<String> labels = roles.stream()
                .map(code -> names.getOrDefault(code, code))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        out.put("displayName", account.displayName());
        // 旧字段：app_user.role（历史枚举）。新界面请读 roles / atoms。
        out.put("role", account.role());
        out.put("roleLabel", labels.isEmpty() ? legacyLabel(account) : String.join(" / ", labels));
        out.put("roles", roles);
        out.put("roleLabels", labels);
        out.put("tenant", tenant);
        List<String> atoms = new ArrayList<>(permissions.atoms(username, tenant));
        out.put("atoms", atoms);
        // v449 P2：权限点为空（角色被清空/未知角色）时不能再回 legacy ["READ"]——
        // 鉴权按 atoms 走会全部 403，而 /me 说「有 READ」，排障时自相矛盾。
        out.put("permissions", atoms.isEmpty() ? List.of() : legacyPermissions(account));
        return out;
    }

    private static String legacyLabel(UserService.Account account) {
        try {
            return account.roleEnum().label();
        } catch (IllegalArgumentException e) {
            return account.role();
        }
    }

    private static List<String> legacyPermissions(UserService.Account account) {
        try {
            return account.roleEnum().permissions().stream().map(Enum::name).sorted().toList();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }
}
