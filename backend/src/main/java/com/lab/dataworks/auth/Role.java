package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;

import java.util.EnumSet;
import java.util.Set;

/**
 * 平台角色与权限矩阵。
 *
 * <p>VIEWER 只读；OPERATOR 可以运行/重跑/补数、处理告警；
 * DEVELOPER 额外可以配置开发资产并发布数据服务；ADMIN 拥有全部权限。</p>
 */
public enum Role {
    VIEWER("只读访客", EnumSet.of(Permission.READ)),
    OPERATOR("运维", EnumSet.of(Permission.READ, Permission.OPS_RUN, Permission.ALERT_MANAGE)),
    DEVELOPER("数据开发", EnumSet.of(Permission.READ, Permission.OPS_RUN,
            Permission.ALERT_MANAGE, Permission.DEV_WRITE, Permission.SERVICE_PUBLISH)),
    ADMIN("平台管理员", EnumSet.allOf(Permission.class));

    private final String label;
    private final Set<Permission> permissions;

    Role(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = permissions;
    }

    public String label() {
        return label;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public static Role parse(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("角色不能为空");
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("不支持的角色: " + value);
        }
    }

    /**
     * D18：内置角色码 → {@code app_user.role} 历史列取值。
     *
     * <p>RBAC 角色表每个租户有 5 个内置角色（REQ-DW-12 收敛：ADMIN / OWNER /
     * SECURITY_ADMIN（显示名「安全审核员」）/ DEVELOPER / VIEWER），
     * 而 {@code app_user.role} 受 {@code ck_app_user_role} CHECK 约束只允许 4 个旧值，
     * 且真实鉴权已完全走权限点（{@code PermissionResolver} 不再回退旧枚举）。
     * 所以非旧角色统一落到「只读桶」VIEWER，具体权限由 member_role 绑定的角色决定；
     * 这与既有「先建 VIEWER、再改成员角色」两步走的落库结果一致。
     * APPROVER / STEWARD / AUDITOR / SECURITY / OPERATOR 为 V130 前的历史角色码，
     * 存量会话与历史数据仍可能出现，映射保留作兼容。</p>
     */
    public static Role legacyOf(String builtinRoleCode) {
        String code = builtinRoleCode == null ? "" : builtinRoleCode.trim()
                .toUpperCase(java.util.Locale.ROOT);
        return switch (code) {
            case "VIEWER" -> VIEWER;
            case "OPERATOR" -> OPERATOR;
            case "DEVELOPER" -> DEVELOPER;
            case "ADMIN" -> ADMIN;
            // 只读/审批/治理类内置角色：旧列没有对应值，按只读桶存放
            case "APPROVER", "STEWARD", "SECURITY_ADMIN", "AUDITOR", "SECURITY", "OWNER" -> VIEWER;
            default -> throw ApiException.badRequest("不支持的角色: " + builtinRoleCode);
        };
    }
}
