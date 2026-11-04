package com.lab.dataworks.security;

import com.lab.dataworks.auth.RequireAtom;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D07 回归：只读审计员（仅 security:read）不得吊销他人会话。
 *
 * <p>两层收口都在这里钉住：</p>
 * <ol>
 *   <li>revoke 方法必须显式标注 {@code @RequireAtom({"security:read","user:manage"})}
 *       —— 方法级注解覆盖类级只读判定（AuthInterceptor 优先取方法级）；</li>
 *   <li>即使有 user:manage，也只能下线「权限不高于自己」的账号
 *       （目标权限点集合 ⊆ 调用方集合），防止自定义 user:manage 角色踢掉租户 ADMIN。</li>
 * </ol>
 */
class SecurityControllerRevokeTest {

    @Test
    void revokeRequiresUserManageAtom() {
        RequireAtom atom = requireAnnotatedMethod().getAnnotation(RequireAtom.class);
        assertNotNull(atom, "revoke 必须有方法级 @RequireAtom，否则落到类级 security:read");
        List<String> required = List.of(atom.value());
        assertTrue(required.contains("user:manage"), "必须要求写权限点 user:manage: " + required);
        assertTrue(required.contains("security:read"), "仍要求在安全中心可见: " + required);
    }

    @Test
    void auditorAtomsCannotRevokeAdminAtoms() {
        Set<String> auditor = Set.of("security:read");
        Set<String> admin = Set.of("security:read", "user:manage", "project:manage", "workflow:read");
        assertTrue(!SecurityController.allowsRevoke(auditor, admin),
                "只读审计员不得下线权限更高的 ADMIN");
    }

    @Test
    void userManageRoleCannotRevokeHigherPrivilegeAccount() {
        Set<String> limiter = Set.of("security:read", "user:manage", "query:run");
        Set<String> admin = Set.of("security:read", "user:manage", "project:manage", "workflow:read");
        // ADMIN 有 project:manage/workflow:read 不在 limiter 里 → 权限更高，拒绝
        assertTrue(!SecurityController.allowsRevoke(limiter, admin));
    }

    @Test
    void equalOrLowerPrivilegeAllowed() {
        Set<String> admin = Set.of("security:read", "user:manage", "project:manage");
        // 同权限（含下线自己：同一用户同一集合）
        assertTrue(SecurityController.allowsRevoke(admin, admin));
        // 目标权限是调用方的子集
        assertTrue(SecurityController.allowsRevoke(admin, Set.of("security:read")));
        assertTrue(SecurityController.allowsRevoke(admin, Set.of()));
    }

    @Test
    void missingAtomsFailClosed() {
        assertTrue(!SecurityController.allowsRevoke(null, Set.of("query:run")));
        assertTrue(!SecurityController.allowsRevoke(Set.of("user:manage"), null));
    }

    private static java.lang.reflect.Method requireAnnotatedMethod() {
        try {
            return SecurityController.class.getMethod("revoke", long.class, HttpServletRequest.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("SecurityController.revoke(long, HttpServletRequest) 签名变了", e);
        }
    }
}
