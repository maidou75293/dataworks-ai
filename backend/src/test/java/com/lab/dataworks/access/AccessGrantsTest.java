package com.lab.dataworks.access;

import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-TC6-2：回收按钮曾经是死分支——渲染条件 {@code status === 'APPROVED'}
 * 挂在「待我审批」页签，而那条数据源写死 {@code status in ('PENDING','PENDING_SECOND')}。
 *
 * <p>修法：新增 {@code scope=grants}（已授权、可回收），列表与 revoke 动作共用
 * {@link AccessController#canRevoke} 判定，保证「列表看得见 ⇔ 点下去有权限」。</p>
 *
 * <p>钉住四件事：</p>
 * <ol>
 *   <li>grants 只查 APPROVED（不混审批中的单子）；</li>
 *   <li>没有 access:revoke 的人拿到空列表（不显示点不动的按钮）；</li>
 *   <li>回收范围沿用 v448 P0-2 / v467 口径：只有与本租户相关的授权，平台管理员兜底；</li>
 *   <li>approvals 仍是 PENDING / PENDING_SECOND（本卡不放宽审批页签，免得审批人看见已完成的单子）。</li>
 * </ol>
 */
class AccessGrantsTest {

    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        List<Map<String, Object>> rows = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... rest) {
            sqls.add(sql);
            return sql.contains("from platform.access_request ar") ? rows : List.of();
        }

        String grantSql() {
            return sqls.stream()
                    .filter(s -> s.contains("from platform.access_request ar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("没有发出申请单查询：" + sqls));
        }
    }

    private final FakeJdbc jdbc = new FakeJdbc();
    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private AccessController controller;

    @BeforeEach
    void setUp() {
        // TC7-G5 给 AccessController 构造加了 AccessEscalationJob（本测试在 main 上、
        // 不在 wm4 分支视野内）；发布线合流按 AccessReasonLimitTest 同法补第 6 参
        controller = new AccessController(jdbc, mock(SessionService.class), permissions,
                mock(TransactionTemplate.class), mock(AccessExpiryJob.class),
                mock(AccessEscalationJob.class));
        TenantScope.set("default");
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    private static MockHttpServletRequest as(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/access/requests");
        request.setAttribute("username", username);
        return request;
    }

    private static Map<String, Object> row(long id, String status,
                                           String ownerTenant, String requesterTenant) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("requester", "alice");
        row.put("requesterTenant", requesterTenant);
        row.put("ownerTenant", ownerTenant);
        row.put("resourceType", "TABLE");
        row.put("status", status);
        return row;
    }

    @Test
    void grantsListsApprovedOnly() {
        when(permissions.atoms("rev", "default")).thenReturn(Set.of("access:revoke"));
        jdbc.rows = List.of(row(1L, "APPROVED", "default", "tenant_b"));

        List<Map<String, Object>> out = controller.requests("grants", as("rev"));

        assertEquals(1, out.size());
        String sql = jdbc.grantSql();
        assertTrue(sql.contains("ar.status = 'APPROVED'"), sql);
        assertFalse(sql.contains("'PENDING'"), "已授权列表不能混进审批中的单子：" + sql);
    }

    @Test
    void userWithoutRevokeAtomSeesNothing() {
        when(permissions.atoms("viewer", "default")).thenReturn(Set.of("access:request"));
        jdbc.rows = List.of(row(1L, "APPROVED", "default", "default"));

        assertTrue(controller.requests("grants", as("viewer")).isEmpty());
    }

    @Test
    void onlyTenantRelatedGrantsAreVisible() {
        when(permissions.atoms("rev", "default")).thenReturn(Set.of("access:revoke"));
        // 1: 本租户提交的申请；2: 本租户资源的申请；3: 两个租户都不是 → 不在本租户可回收范围
        jdbc.rows = List.of(
                row(1L, "APPROVED", "tenant_b", "default"),
                row(2L, "APPROVED", "default", "tenant_b"),
                row(3L, "APPROVED", "tenant_b", "tenant_b"));

        List<Map<String, Object>> out = controller.requests("grants", as("rev"));

        assertEquals(List.of(1L, 2L), out.stream().map(r -> r.get("id")).toList());
    }

    @Test
    void platformAdminSeesEveryTenant() {
        when(permissions.atoms("root", "default")).thenReturn(Set.of("user:manage"));
        jdbc.rows = List.of(
                row(1L, "APPROVED", "tenant_b", "tenant_b"),
                row(2L, "APPROVED", "default", "default"));

        assertEquals(2, controller.requests("grants", as("root")).size());
    }

    @Test
    void approvalsStayPendingOnly() {
        when(permissions.atoms("appr", "default")).thenReturn(Set.of("access:approve"));
        jdbc.rows = List.of();

        controller.requests("approvals", as("appr"));

        String sql = jdbc.grantSql();
        assertTrue(sql.contains("ar.status in ('PENDING', 'PENDING_SECOND')"), sql);
        assertFalse(sql.contains("ar.status = 'APPROVED'"), "审批页签不放宽到已完成的单子：" + sql);
    }

    /** 决策矩阵：列表与动作共用的唯一口径。 */
    @Test
    void canRevokeMatrix() {
        assertTrue(AccessController.canRevoke(true, false, "default", "tenant_b", "tenant_b"),
                "平台管理员兜底");
        assertFalse(AccessController.canRevoke(false, false, "default", "default", "default"),
                "没有 access:revoke 不能回收");
        assertTrue(AccessController.canRevoke(false, true, "default", "default", "tenant_b"),
                "资源属于本租户");
        assertTrue(AccessController.canRevoke(false, true, "default", "tenant_b", "default"),
                "申请由本租户提交");
        assertFalse(AccessController.canRevoke(false, true, "default", "tenant_b", "tenant_b"),
                "与本租户无关的授权不能碰（v467 口径）");
        assertFalse(AccessController.canRevoke(false, true, null, "default", "default"),
                "拿不到租户上下文时拒绝，不做「等于空串」的误判");
        assertFalse(AccessController.canRevoke(false, true, "default", null, null),
                "资源与申请租户都缺失（残留单）不能回收");
    }
}
