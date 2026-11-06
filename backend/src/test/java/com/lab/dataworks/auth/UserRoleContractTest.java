package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

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
 * D18：建用户 / 改角色接受的角色码，与 RBAC 内置角色表（{@code GET /api/rbac/roles}）同口径。
 *
 * <p>原状：{@code Role.parse()} 只枚举 4 个历史角色，而 V55 给每个租户建了 8 个内置角色、
 * V105 又加到 9 个，于是「建一个项目负责人」必须先建 VIEWER、再调成员角色接口两步走，
 * 一步式 {@code POST /api/users {"role":"OWNER"}} 回 400「不支持的角色: OWNER」。</p>
 *
 * <p>REQ-DW-12（V130）把内置角色收敛为 5 个：ADMIN / OWNER / SECURITY_ADMIN（安全审核员）/
 * DEVELOPER / VIEWER；本测试随之用新角色码，旧码（APPROVER 等）走「租户内没有该内置角色」分支。</p>
 *
 * <p>{@code app_user.role} 受 V55 之前的 {@code ck_app_user_role} CHECK 约束（只允许 4 个旧值），
 * 而鉴权早已走权限点（{@code PermissionResolver} 不回退旧枚举），所以历史列继续存
 * 粗粒度值，真实角色落到 {@code member_role} —— 与既有「两步走」的最终落库状态一致。</p>
 */
class UserRoleContractTest {

    private static final String PASSWORD = "MockP@ss1";

    /** roleRows＝该租户内置角色查询的命中行数。 */
    private JdbcTemplate jdbc;
    private RbacService rbac;
    private UserService users;

    private void setUpWith(int roleRows) {
        jdbc = mock(JdbcTemplate.class);
        rbac = mock(RbacService.class);
        users = new UserService(jdbc, "admin", "admin", false, rbac,
                mock(PermissionResolver.class), mock(SessionService.class));
        // find() 返回空：创建路径只关心「校验 + 写库 + 绑定角色」
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("platform.role")) {
                        return roleRows;      // 内置角色是否存在（D18 的判据）
                    }
                    return 1;                 // 租户存在 / 其他计数
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    // -------------------------------------------------- 旧枚举粗粒度映射

    @Test
    void legacyColumnKeepsTheFourKnownValues() {
        assertEquals(Role.VIEWER, Role.legacyOf("VIEWER"));
        assertEquals(Role.OPERATOR, Role.legacyOf("OPERATOR"));
        assertEquals(Role.DEVELOPER, Role.legacyOf("DEVELOPER"));
        assertEquals(Role.ADMIN, Role.legacyOf("ADMIN"));
        assertEquals(Role.ADMIN, Role.legacyOf("admin"));   // 大小写不敏感
    }

    @Test
    void rbacOnlyRolesMapToTheReadBucket() {
        // OWNER / SECURITY_ADMIN＝REQ-DW-12 收敛后的内置角色；其余为 V130 前历史码（兼容映射）
        for (String code : List.of("OWNER", "SECURITY_ADMIN", "APPROVER", "STEWARD", "AUDITOR", "SECURITY")) {
            assertEquals(Role.VIEWER, Role.legacyOf(code),
                    "非旧角色落 VIEWER（真实权限由 member_role 的权限点决定）: " + code);
        }
    }

    @Test
    void unknownRoleIsRejectedNotSilentlyDowngraded() {
        // 与「授予 ADMIN」的守卫无关：这里要保证的是拼错的角色码不会被当成 VIEWER 收下来
        assertEquals(400, assertThrows(ApiException.class,
                () -> Role.legacyOf("SUPER_USER")).getStatus());
    }

    // -------------------------------------------------- 建用户

    @Test
    void ownerIsAcceptedAndBound() {
        setUpWith(1);
        users.create("bug_b_own", "项目负责人", "OWNER", PASSWORD, "default");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("insert into platform.app_user"), sql.getValue());
        List<Object> bound = List.of(args.getValue());
        // 历史列＝粗粒度 VIEWER（CHECK 约束只允许 4 个旧值）
        assertTrue(bound.contains("VIEWER"), "app_user.role 写粗粒度旧值: " + bound);
        // 真实角色＝请求的内置角色码，不能退化成旧枚举名
        verify(rbac).attach("bug_b_own", "default", "OWNER");
    }

    @Test
    void allFiveBuiltinRolesAreAccepted() {
        // REQ-DW-12（V130）收敛后的内置角色集
        for (String code : List.of("ADMIN", "OWNER", "SECURITY_ADMIN", "DEVELOPER", "VIEWER")) {
            setUpWith(1);
            users.create("bug_b_u", "u", code, PASSWORD, "default");
            verify(rbac).attach("bug_b_u", "default", code);
        }
    }

    @Test
    void legacyRolesStillBindThemselves() {
        setUpWith(1);
        users.create("bug_b_dev", "开发", "DEVELOPER", PASSWORD, "default");
        verify(rbac).attach("bug_b_dev", "default", "DEVELOPER");
    }

    @Test
    void roleMissingInTenantIsBadRequestAndNothingIsWritten() {
        setUpWith(0);   // 该租户没有这个内置角色（V130 起 APPROVER 已整体不存在）
        ApiException e = assertThrows(ApiException.class, () ->
                users.create("bug_b_ghost", "幽灵", "APPROVER", PASSWORD, "default"));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("APPROVER"), e.getMessage());
        assertTrue(e.getMessage().contains("/api/rbac/roles"),
                "提示要指出可用角色的来源: " + e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(rbac, never()).attach(anyString(), anyString(), anyString());
    }

    @Test
    void blankOrMalformedRoleCodeIsBadRequest() {
        setUpWith(1);
        for (String code : List.of("", "   ", "1abc", "a b", "角色")) {
            ApiException e = assertThrows(ApiException.class, () ->
                    users.create("bug_b_x", "x", code, PASSWORD, "default"));
            assertEquals(400, e.getStatus(), "非法角色码必须 400: [" + code + "]");
        }
    }

    // -------------------------------------------------- 改角色

    @Test
    void updateToBuiltinRoleBindsIt() {
        setUpWith(1);
        stubExistingUser("bug_b_u", "VIEWER");
        when(rbac.defaultTenant("bug_b_u")).thenReturn("default");

        users.update(9L, null, "SECURITY_ADMIN", null, "admin");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("update platform.app_user"), sql.getValue());
        assertTrue(List.of(args.getValue()).contains("VIEWER"),
                "历史列写粗粒度旧值: " + List.of(args.getValue()));
        verify(rbac).attach("bug_b_u", "default", "SECURITY_ADMIN");
    }

    @Test
    void updateWithoutRoleChangeKeepsExistingBindings() {
        // REQ-DW-12 附带修复：attach 是覆盖式重绑；只改显示名/状态绝不能把
        // OWNER/SECURITY_ADMIN（历史列落在 VIEWER 桶）的用户降成只读访客。
        setUpWith(1);
        stubExistingUser("bug_b_u", "VIEWER");
        when(rbac.defaultTenant("bug_b_u")).thenReturn("default");

        users.update(9L, "新显示名", null, "ACTIVE", "admin");

        verify(rbac, never()).attach(anyString(), anyString(), anyString());
    }

    @Test
    void updateWithUnknownRoleIsRejectedBeforeAnyWrite() {
        setUpWith(0);
        stubExistingUser("bug_b_u", "VIEWER");
        when(rbac.defaultTenant("bug_b_u")).thenReturn("default");

        ApiException e = assertThrows(ApiException.class,
                () -> users.update(9L, null, "APPROVER", null, "admin"));
        assertEquals(400, e.getStatus());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(rbac, never()).attach(anyString(), anyString(), anyString());
    }

    /** 让 requireUser(id) / find(username) 返回一个已存在的普通账号。 */
    private void stubExistingUser(String username, String role) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (!sql.contains("platform.app_user")) {
                        return List.of();
                    }
                    return List.of(new UserService.Account(9L, username, username,
                            role, "ACTIVE", "LOCAL", false, null, null));
                });
    }
}
