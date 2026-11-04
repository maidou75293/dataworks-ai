package com.lab.dataworks.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前请求的租户。由 {@link AuthInterceptor} 解析并写入 request 属性：
 * 优先取 `X-Tenant-Id`（必须校验过成员关系），否则取用户默认租户。
 *
 * <p>所有业务查询都应带上 `tenant_id = TenantScope.of(request)`；这是第三期的核心不变量。</p>
 */
public final class TenantScope {

    public static final String ATTR = "tenant";
    public static final String DEFAULT = "default";

    /** 线程内上下文：没有 request 的深层方法（如 getWorkflow）也能拿到当前租户。 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantScope() {
    }

    public static void set(String tenant) {
        CURRENT.set(tenant == null || tenant.isBlank() ? DEFAULT : tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String current() {
        String value = CURRENT.get();
        return value == null || value.isBlank() ? DEFAULT : value;
    }

    /** 没解析出租户时返回 null，供「先看默认租户、再决定」的场景使用（如登录响应）。 */
    public static String currentOrNull() {
        String value = CURRENT.get();
        return value == null || value.isBlank() ? null : value;
    }

    public static String of(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ATTR);
        String code = value == null ? "" : String.valueOf(value).trim();
        return code.isEmpty() ? current() : code;
    }
}
