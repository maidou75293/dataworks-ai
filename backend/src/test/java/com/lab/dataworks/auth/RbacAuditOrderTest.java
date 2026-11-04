package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S12 + S13：auth 域破坏性动作的审计顺序与内容。
 *
 * <p>S13：删用户 / 删角色 / 删租户 / 移除成员原来都是「先做后记」——守卫或底层
 * 一旦抛异常（内置角色不可删、租户还有成员、级联回收失败……）就一条审计都不留，
 * 安全中心回答不了「谁在什么时候试过删它」。现在统一走
 * {@link SessionService#auditAround}：先落 PENDING，成功回填 SUCCESS、
 * 失败回填 FAILED，异常原样抛出。</p>
 *
 * <p>S12：这些记录必须带 {@code tenant_id}（NULL 会被
 * {@code (? or tenant_id = ?)} 的列表过滤永久挡掉），且 detail 不落空。</p>
 */
class RbacAuditOrderTest {

    private JdbcTemplate jdbc;
    private SessionService sessions;
    private RbacService rbac;
    private PermissionResolver permissions;
    private RbacController rbacController;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // 真 SessionService + 假 JdbcTemplate：验证最终落库的 SQL 与参数
        sessions = new SessionService(jdbc, 12);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        rbac = mock(RbacService.class);
        permissions = mock(PermissionResolver.class);
        // D52：移除成员走「同事务回收授权」——TransactionTemplate 用透传桩（测试里
        // 没有真数据源，回调直接执行），回收服务 mock 返回条数即可
        org.springframework.transaction.support.TransactionTemplate txTpl =
                mock(org.springframework.transaction.support.TransactionTemplate.class);
        when(txTpl.execute(any())).thenAnswer(inv ->
                ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0)).doInTransaction(null));
        com.lab.dataworks.access.AccessRevokeService revokes =
                mock(com.lab.dataworks.access.AccessRevokeService.class);
        when(revokes.revokeForTenant(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        rbacController = new RbacController(rbac, permissions, sessions, revokes, txTpl);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "platform_admin");
        http.setRemoteAddr("10.99.99.11");
        return http;
    }

    /** 动作抛异常时回填的那条 update 参数：[result, detail, id]。 */
    private List<Object> settledArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update platform.audit_log"), args.capture());
        return java.util.Arrays.asList(args.getValue());
    }

    // -------------------------------------------------------- 删角色

    @Test
    void roleDeleteWritesPendingBeforeTheActionAndSettlesSuccess() {
        List<String> trace = new ArrayList<>();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(inv -> {
            trace.add("pending");
            return 7L;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            trace.add(((String) inv.getArgument(0)).startsWith("update platform.audit_log")
                    ? "settle" : "other-update");
            return 1;
        });
        when(rbac.rolesOfTenant(anyString())).thenReturn(List.of());

        rbacController.deleteRole("AUDITOR", null, request());

        assertEquals(List.of("pending", "settle"), trace,
                "S13：先有 PENDING 审计才动手，做完回填（rbac 是 mock 不进 trace）");
        verify(rbac).deleteRole("default", "AUDITOR");
        // S12：detail 不落空——保留删除时的权限点快照
        List<Object> settle = java.util.Arrays.asList(
                captureUpdateArgs("update platform.audit_log"));
        assertEquals("SUCCESS", settle.get(0));
        assertTrue(String.valueOf(settle.get(1)).contains("删除时该角色的权限点="),
                String.valueOf(settle.get(1)));
    }

    @Test
    void roleDeleteGuardFailureStillLeavesFailedAuditAndRethrows() {
        doThrow(ApiException.conflict("内置角色不可删除"))
                .when(rbac).deleteRole("default", "ADMIN");

        ApiException thrown = assertThrows(ApiException.class,
                () -> rbacController.deleteRole("ADMIN", null, request()));
        assertEquals(409, thrown.getStatus(), "守卫异常必须原样抛出");

        List<Object> settle = settledArgs();
        assertEquals("FAILED", settle.get(0));
        assertTrue(String.valueOf(settle.get(1)).contains("失败：内置角色不可删除"),
                String.valueOf(settle.get(1)));
    }

    // -------------------------------------------------------- 删租户

    @Test
    void tenantDeleteGuardFailureLeavesFailedAuditAndSkipsCacheEviction() {
        when(rbac.allTenants()).thenReturn(List.of(Map.of("code", "t2", "name", "二组")));
        doThrow(ApiException.conflict("租户还有成员，不能删除"))
                .when(rbac).deleteTenant("t2");

        assertThrows(ApiException.class, () -> rbacController.deleteTenant("t2", "t2", request()));

        List<Object> settle = settledArgs();
        assertEquals("FAILED", settle.get(0));
        assertTrue(String.valueOf(settle.get(1)).contains("名称=二组"), "detail 要能定位删的是哪个租户");
        assertTrue(String.valueOf(settle.get(1)).contains("失败：租户还有成员"),
                String.valueOf(settle.get(1)));
        verify(permissions, never()).invalidateAll();
    }

    @Test
    void tenantDeleteSuccessSettlesAndEvictsCache() {
        when(rbac.allTenants()).thenReturn(List.of(Map.of("code", "t2", "name", "二组")));

        rbacController.deleteTenant("t2", "t2", request());

        verify(rbac).deleteTenant("t2");
        verify(permissions).invalidateAll();
        List<Object> settle = settledArgs();
        assertEquals("SUCCESS", settle.get(0));
    }

    /** D54：confirm 缺失/不一致 → 400，且发生在任何业务与审计之前（护栏不靠界面）。 */
    @Test
    @SuppressWarnings("unchecked")
    void tenantDeleteWithoutConfirmIsRejectedBeforeAudit() {
        ApiException missing = assertThrows(ApiException.class,
                () -> rbacController.deleteTenant("t2", null, request()));
        assertEquals(400, missing.getStatus());
        ApiException mismatch = assertThrows(ApiException.class,
                () -> rbacController.deleteTenant("t2", "other", request()));
        assertEquals(400, mismatch.getStatus());

        verify(rbac, never()).deleteTenant(anyString());
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    // -------------------------------------------------------- 移除成员

    @Test
    void memberRemoveKeepsOldRolesInDetailAndTenantIdOnPendingRow() {
        when(rbac.usernameOf(3L)).thenReturn("bob");
        when(rbac.members("t9")).thenReturn(List.of(Map.of("userId", 3L, "roles", List.of("DEV"))));

        rbacController.removeMember("t9", 3L, request());

        verify(rbac).removeMember("t9", 3L);
        verify(permissions).invalidate("bob");
        List<Object> settle = settledArgs();
        assertEquals("SUCCESS", settle.get(0));
        assertTrue(String.valueOf(settle.get(1)).contains("移除前该成员在本租户的角色=DEV"),
                String.valueOf(settle.get(1)));

        // S12：先记那条必须带 tenant_id（绑定值非空），否则租户管理员永远看不到
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), args.capture());
        assertTrue(sql.getValue().contains("tenant_id"), sql.getValue());
        assertTrue(String.valueOf(args.getValue()[6]).length() > 0, "tenant_id 不能为 null");
    }

    // -------------------------------------------------------- 删用户

    @Test
    void userDeleteWritesPendingBeforeCascadeAndSettlesFailedOnThrow() {
        UserService users = mock(UserService.class);
        TenantGuard guard = mock(TenantGuard.class);
        when(users.requireUser(5L)).thenReturn(new UserService.Account(
                5L, "bob", "Bob", "VIEWER", "ACTIVE", "LOCAL", false, null, null));
        when(guard.userInTenant("bob")).thenReturn(true);
        when(guard.userExclusiveToCurrentTenant("bob")).thenReturn(true);
        doThrow(ApiException.conflict("该用户还有业务数据")).when(users).delete(5L, "platform_admin");

        UserController controller = new UserController(users, sessions, guard, rbac);
        assertThrows(ApiException.class, () -> controller.delete(5L, request()));

        List<Object> settle = settledArgs();
        assertEquals("FAILED", settle.get(0));
        assertTrue(String.valueOf(settle.get(1)).contains("角色=VIEWER"),
                "删用户失败也要留「谁试过删谁、ta 是什么角色」: " + settle.get(1));
        assertTrue(String.valueOf(settle.get(1)).contains("失败：该用户还有业务数据"),
                String.valueOf(settle.get(1)));
    }

    // -------------------------------------------------------- 工具

    private Object[] captureUpdateArgs(String sqlFragment) {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(contains(sqlFragment), args.capture());
        return args.getValue();
    }
}
