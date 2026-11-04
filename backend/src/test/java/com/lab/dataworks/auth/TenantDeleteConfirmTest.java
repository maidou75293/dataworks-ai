package com.lab.dataworks.auth;

import com.lab.dataworks.access.AccessRevokeService;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D54：删租户的 confirm 护栏。
 *
 * <p>租户下面挂着成员关系、租户内角色、项目归属与授权，误点一次就是整片下线。
 * 界面弹窗只能骗过手滑，<b>脚本直发 DELETE 一样能删</b>，所以护栏必须在后端：
 * 请求必须带 {@code confirm=<租户code>} 且完全一致（忽略大小写与首尾空格）。</p>
 *
 * <p>钉住三件事：</p>
 * <ol>
 *   <li>不带 confirm → 400，且<em>没有</em>走到删除（这条是关键：光报错不拦动作等于没护栏）；</li>
 *   <li>带错 code → 400，报错里同时给出「需要哪个、收到哪个」；</li>
 *   <li>带对 code → 200 并正常审计。</li>
 * </ol>
 */
class TenantDeleteConfirmTest {

    private final RbacService rbac = mock(RbacService.class);
    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AccessRevokeService revokes = mock(AccessRevokeService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final RbacController controller =
            new RbacController(rbac, permissions, sessions, revokes, tx);

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/rbac/tenants/tenant_b");
        request.setAttribute("username", "admin");
        return request;
    }

    @Test
    void missingConfirmIsRejectedBeforeAnyDelete() {
        ApiException e = assertThrows(ApiException.class, () -> controller.deleteTenant("tenant_b", null, request()));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("confirm"), e.getMessage());
        // 关键一条：报错了还不能真删
        verify(rbac, never()).deleteTenant(anyString());
        verify(sessions, never()).audit(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void blankConfirmIsRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.deleteTenant("tenant_b", "   ", request()));

        assertEquals(400, e.getStatus());
        verify(rbac, never()).deleteTenant(anyString());
    }

    @Test
    void mismatchedConfirmIsRejectedWithBothValues() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.deleteTenant("tenant_b", "tenant_c", request()));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("tenant_b"), "要告诉调用方需要什么：" + e.getMessage());
        assertTrue(e.getMessage().contains("tenant_c"), "也要告诉调用方收到了什么：" + e.getMessage());
        verify(rbac, never()).deleteTenant(anyString());
    }

    /**
     * S13 合入后删租户/移除成员都走 {@link SessionService#auditAround}（先记后做）：
     * 这里把两个重载都打桩成「执行 supplier、并把 outcomeDetail 算出来留档」，
     * 断言的仍是 D54/D52 关心的事——动作真发生了、审计里带得上关键信息。
     */
    private final java.util.List<String> settledDetails = new java.util.ArrayList<>();

    private void stubAuditAround() {
        when(sessions.auditAround(anyString(), anyString(), anyString(), anyString(), anyString(),
                any())).thenAnswer(inv -> ((java.util.function.Supplier<?>)
                        inv.getArgument(5)).get());
        when(sessions.auditAround(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any())).thenAnswer(inv -> {
            Object out = ((java.util.function.Supplier<?>) inv.getArgument(5)).get();
            @SuppressWarnings("unchecked")
            java.util.function.Function<Object, Object> fn =
                    (java.util.function.Function<Object, Object>) inv.getArgument(6);
            settledDetails.add(String.valueOf(fn.apply(out)));
            return out;
        });
    }

    /** 大小写与首尾空格不算不一致：确认的是同一个编码就该放行。 */
    @Test
    void confirmIsCaseAndSpaceInsensitive() {
        when(rbac.allTenants()).thenReturn(List.of(Map.of("code", "tenant_b", "name", "租户B")));
        stubAuditAround();

        controller.deleteTenant("tenant_b", " Tenant_B ", request());

        verify(rbac).deleteTenant("tenant_b");
        verify(permissions).invalidateAll();
        verify(sessions).auditAround(eq("admin"), eq("TENANT_DELETE"), eq("tenant_b"),
                org.mockito.ArgumentMatchers.argThat(detail -> detail != null
                        && detail.contains("名称=租户B") && detail.contains("confirm")),
                anyString(), any());
    }

    @Test
    void requireConfirmCodePureFunction() {
        RbacController.requireConfirmCode("acme", "acme");
        RbacController.requireConfirmCode("acme", " ACME ");
        assertThrows(ApiException.class, () -> RbacController.requireConfirmCode("acme", "acm"));
        assertThrows(ApiException.class, () -> RbacController.requireConfirmCode("acme", ""));
        assertThrows(ApiException.class, () -> RbacController.requireConfirmCode("acme", null));
    }

    /**
     * 移出成员那一步也要留痕（D52 收口后由回收服务给出条数）：
     * 确认框把成员/授权影响摆出来之前，后端至少不能「删了但没人知道删了什么」。
     */
    @Test
    void removeMemberAuditsRevokedCount() {
        when(rbac.usernameOf(7L)).thenReturn("alice");
        when(permissions.atoms(anyString(), anyString())).thenReturn(Set.of("tenant:manage"));
        when(revokes.revokeForTenant(eq("alice"), eq("tenant_b"), eq("admin"), anyString()))
                .thenReturn(3);
        when(tx.execute(any())).thenAnswer(inv -> ((org.springframework.transaction.support
                .TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        stubAuditAround();

        var out = controller.removeMember("tenant_b", 7L, request());

        assertEquals(3, out.get("revokedGrants"), "响应必须报告回收了几条授权：" + out);
        // S13 合并实现：回收条数是动作成功后才知道的，走 auditAround 的 outcomeDetail 补充
        assertTrue(settledDetails.stream().anyMatch(d -> d.contains("自动回收") && d.contains("3 条")),
                "审计补充里要写清回收了几条：" + settledDetails);
    }
}
