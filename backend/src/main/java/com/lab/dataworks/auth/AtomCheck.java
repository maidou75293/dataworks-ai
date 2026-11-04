package com.lab.dataworks.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * 读取 {@link AuthInterceptor} 放进 request 的权限点，用于注解表达不了的细粒度判断
 * （例如「负责人或 access:approve 才能审批」这类组合条件）。
 *
 * <p>不要在 Service 层调用：那里拿不到 request，应该改用
 * {@link PermissionResolver#atoms(String, String)}。</p>
 */
public final class AtomCheck {

    private AtomCheck() {
    }

    public static boolean has(HttpServletRequest request, String atom) {
        Object value = request == null ? null : request.getAttribute(AuthInterceptor.ATOMS_ATTR);
        return value instanceof Set<?> set && set.contains(atom);
    }

    public static boolean any(HttpServletRequest request, String... atoms) {
        for (String atom : atoms) {
            if (has(request, atom)) return true;
        }
        return false;
    }
}
