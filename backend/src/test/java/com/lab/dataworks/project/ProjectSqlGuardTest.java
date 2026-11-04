package com.lab.dataworks.project;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
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
 * D43 回归：SQL 守卫开关的界面入口 + 只读态 + 审计。
 *
 * <p>立案事实：后端早就有 {@code GET/PUT /api/projects/{id}/sql-guard}（PUT 要求
 * {@code system:manage}），但 {@code grep -r "sql-guard" web/src} 是 0 处 ——
 * 功能存在、用户永远点不到。本卡补界面入口，同时把「能不能改」下放到 GET 响应里，
 * 让无权限的人看到只读态与原因，而不是点了才吃 403。</p>
 *
 * <p>审计口径沿用现有动作名 {@code SQL_GUARD_ENABLE / SQL_GUARD_DISABLE}
 * （已带前后值与 [高风险] 标记）；卡里写的 {@code PROJECT_SQL_GUARD_CHANGE} 是
 * 「若后端未写审计才补」，后端已写，故不改名 —— 改名会打断既有审计查询与 e2e。</p>
 */
class ProjectSqlGuardTest {

    private static final long PROJECT_ID = 7L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private ProjectController controller;
    private boolean guardEnabled = true;

    @BeforeEach
    void setUp() {
        TenantScope.set(TenantScope.DEFAULT);          // platformAdmin 要求默认租户
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        controller = new ProjectController(jdbc, sessions, mock(DorisAdminService.class),
                mock(WarehouseConnectionService.class), tx,
                mock(com.lab.dataworks.workflow.WorkflowMutationService.class),
                mock(DataGuardService.class), mock(WarehouseResolver.class));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv ->
                List.of(projectRow()));
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("set sql_guard_enabled = ?")) {
                guardEnabled = Boolean.TRUE.equals(inv.getArguments()[1]);
            }
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    @Test
    void getReportsCanManageForPlatformAdminOnly() {
        when(http.getAttribute(AuthInterceptor.ATOMS_ATTR)).thenReturn(Set.of("system:manage"));
        assertEquals(Boolean.TRUE, controller.sqlGuard(PROJECT_ID, http).get("canManage"));

        when(http.getAttribute(AuthInterceptor.ATOMS_ATTR)).thenReturn(Set.of("project:read"));
        assertEquals(Boolean.FALSE, controller.sqlGuard(PROJECT_ID, http).get("canManage"));
    }

    @Test
    void getIsFailSafeWhenColumnMissing() {
        when(http.getAttribute(AuthInterceptor.ATOMS_ATTR)).thenReturn(Set.of("project:read"));
        Map<String, Object> row = projectRow();
        row.put("sql_guard_enabled", null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> List.of(row));

        assertEquals(Boolean.TRUE, controller.sqlGuard(PROJECT_ID, http).get("sqlGuardEnabled"),
                "列缺失/为 null 必须按开启展示（fail-safe，与 ChangeService.guardEnabled 一致）");
    }

    @Test
    void turningOffWritesHighRiskAuditWithBeforeAndAfter() {
        when(http.getAttribute("username")).thenReturn("root");
        when(http.getAttribute(AuthInterceptor.ATOMS_ATTR)).thenReturn(Set.of("system:manage"));

        Map<String, Object> out = controller.updateSqlGuard(PROJECT_ID,
                new ProjectController.SqlGuardRequest(false, "临时放开"), http);

        assertEquals(Boolean.FALSE, out.get("sqlGuardEnabled"));
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("root"), action.capture(), eq("p7"), detail.capture(),
                eq("SUCCESS"), any());
        assertEquals("SQL_GUARD_DISABLE", action.getValue());
        assertTrue(detail.getValue().startsWith("[高风险]"), detail.getValue());
        assertTrue(detail.getValue().contains("sqlGuardEnabled: true → false"), detail.getValue());
        assertTrue(detail.getValue().contains("reason: 临时放开"), detail.getValue());
    }

    @Test
    void turningOnIsNotTaggedHighRisk() {
        guardEnabled = false;
        when(http.getAttribute("username")).thenReturn("root");
        when(http.getAttribute(AuthInterceptor.ATOMS_ATTR)).thenReturn(Set.of("system:manage"));

        controller.updateSqlGuard(PROJECT_ID, new ProjectController.SqlGuardRequest(true, null), http);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("root"), action.capture(), eq("p7"), detail.capture(),
                eq("SUCCESS"), any());
        assertEquals("SQL_GUARD_ENABLE", action.getValue());
        assertTrue(!detail.getValue().startsWith("[高风险]"), detail.getValue());
    }

    @Test
    void missingEnabled_is400AndWritesNothing() {
        when(http.getAttribute(AuthInterceptor.ATOMS_ATTR)).thenReturn(Set.of("system:manage"));

        ApiException e = assertThrows(ApiException.class, () -> controller.updateSqlGuard(
                PROJECT_ID, new ProjectController.SqlGuardRequest(null, null), http));
        assertEquals(400, e.getStatus());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private Map<String, Object> projectRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", PROJECT_ID);
        row.put("tenant_id", TenantScope.DEFAULT);
        row.put("code", "gx_01_sales");
        row.put("name", "销售域");
        row.put("owner_user", "alice");
        row.put("sql_guard_enabled", guardEnabled);
        return row;
    }
}
