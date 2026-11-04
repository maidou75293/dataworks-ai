package com.lab.dataworks.auth;

/**
 * 平台权限点。角色到权限点的映射在 {@link Role} 里，接口用
 * {@link RequirePermission} 标注所需权限；非 GET 接口未标注时默认拒绝。
 */
public enum Permission {
    READ("只读访问"),
    DEV_WRITE("开发配置"),
    OPS_RUN("运行与运维操作"),
    SERVICE_PUBLISH("数据服务发布"),
    ALERT_MANAGE("告警规则管理"),
    ADMIN("平台管理");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
