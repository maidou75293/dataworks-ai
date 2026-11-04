package com.lab.dataworks.security;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.ProjectController;
import com.lab.dataworks.project.WarehouseConnectionService;
import com.lab.dataworks.project.WarehouseResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目安全策略写接口的权限与审计（v483）。
 *
 * <p>规则：项目 owner 或 system:manage 才可改；非 owner 且无 system:manage → 403；
 * 设为 OFF 必须二次确认；审计记录三个字段的旧值 → 新值并标 [高风险]。</p>
 */
class ProjectSecurityControllerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final DataGuardService security = mock(DataGuardService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        controller = new ProjectController(jdbc, sessions,
                mock(DorisAdminService.class), mock(WarehouseConnectionService.class),
                mock(TransactionTemplate.class),
                mock(com.lab.dataworks.workflow.WorkflowMutationService.class), security,
                mock(WarehouseResolver.class));
    }

    @AfterEach
    void tearDown() {
        com.lab.dataworks.auth.TenantScope.clear();
    }

    @Test
    void nonOwnerWithoutSystemManageIsForbidden() {
        when(request.getAttribute("username")).thenReturn("bob");
        when(security.projectSecurity(7L)).thenReturn(
                new DataGuardService.ProjectSecurity(7L, "alice", "INHERIT", null, null));

        ApiException e = assertThrows(ApiException.class, () -> controller.updateSecurity(7L,
                new ProjectController.SecurityRequest("TABLE", null, null, null, null), request));
        assertEquals(403, e.getStatus(), e.getMessage());
        verify(jdbc, never()).update(contains("set security_mode"), any(Object[].class));
        verify(sessions, never()).audit(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void ownerCanSwitchOffWithConfirmAndAuditIsHighRisk() {
        when(request.getAttribute("username")).thenReturn("alice");
        DataGuardService.ProjectSecurity before =
                new DataGuardService.ProjectSecurity(7L, "alice", "INHERIT", "L3", null);
        DataGuardService.ProjectSecurity after =
                new DataGuardService.ProjectSecurity(7L, "alice", "OFF", null, true);
        when(security.projectSecurity(7L)).thenReturn(before, after);
        when(security.policy(7L)).thenReturn(new DataGuardService.Policy(
                DataGuardService.PERMISSION_COLUMN, DataGuardService.SOURCE_PROJECT,
                DataGuardService.LOG, DataGuardService.PERMISSION_TABLE, null, 7L,
                "OFF", null, true));
        when(jdbc.update(contains("set security_mode"), any(Object[].class))).thenReturn(1);

        controller.updateSecurity(7L, new ProjectController.SecurityRequest(
                "OFF", null, true, "CONFIRM", "测试关闭"), request);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("set security_mode"), args.capture());
        Object[] values = args.getValue();
        assertEquals("OFF", values[0]);
        org.junit.jupiter.api.Assertions.assertNull(values[1]);
        assertEquals(true, values[2]);
        assertEquals(7L, values[3]);
        assertEquals("default", values[4]);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("alice"), eq("PROJECT_SECURITY_UPDATE"), eq("p7"),
                detail.capture(), eq("SUCCESS"), any());
        String text = detail.getValue();
        assertTrue(text.startsWith("[高风险]"), text);
        assertTrue(text.contains("securityMode: INHERIT → OFF"), text);
        assertTrue(text.contains("defaultColumnLevel: L3 → （继承）"), text);
        assertTrue(text.contains("columnControlRequired: （继承） → true"), text);
        assertTrue(text.contains("reason: 测试关闭"), text);
        verify(security).invalidateProjectSecurity(7L);
    }

    @Test
    void switchingOffWithoutConfirmIsRejected() {
        when(request.getAttribute("username")).thenReturn("alice");
        when(security.projectSecurity(7L)).thenReturn(
                new DataGuardService.ProjectSecurity(7L, "alice", "INHERIT", null, null));

        ApiException e = assertThrows(ApiException.class, () -> controller.updateSecurity(7L,
                new ProjectController.SecurityRequest("OFF", null, null, null, null), request));
        assertEquals(400, e.getStatus(), e.getMessage());
        verify(jdbc, never()).update(contains("set security_mode"), any(Object[].class));
    }

    @Test
    void invalidModeOrLevelIsRejected() {
        when(request.getAttribute("username")).thenReturn("alice");
        when(security.projectSecurity(7L)).thenReturn(
                new DataGuardService.ProjectSecurity(7L, "alice", "INHERIT", null, null));

        ApiException badMode = assertThrows(ApiException.class, () -> controller.updateSecurity(7L,
                new ProjectController.SecurityRequest("LOG", null, null, null, null), request));
        assertEquals(400, badMode.getStatus(), badMode.getMessage());

        ApiException badLevel = assertThrows(ApiException.class, () -> controller.updateSecurity(7L,
                new ProjectController.SecurityRequest("TABLE", "L9", null, null, null), request));
        assertEquals(400, badLevel.getStatus(), badLevel.getMessage());
        assertTrue(badLevel.getMessage().contains("defaultColumnLevel"), badLevel.getMessage());
    }

    @Test
    void readViewMarksOwnerEditableAndShowsEffectiveValues() {
        when(request.getAttribute("username")).thenReturn("alice");
        when(security.projectSecurity(7L)).thenReturn(
                new DataGuardService.ProjectSecurity(7L, "alice", "INHERIT", "L3", null));
        when(security.policy(7L)).thenReturn(new DataGuardService.Policy(
                DataGuardService.PERMISSION_TABLE, DataGuardService.SOURCE_PLATFORM,
                DataGuardService.ENFORCE, DataGuardService.PERMISSION_TABLE, null, 7L,
                "INHERIT", "L3", null));

        var view = controller.securityPolicy(7L, request);
        assertEquals("INHERIT", view.get("securityMode"));
        assertEquals("TABLE", view.get("effectiveMode"));
        assertEquals("PLATFORM", view.get("effectiveSource"));
        assertEquals("L3", view.get("effectiveDefaultColumnLevel"));
        assertEquals(true, view.get("editable"));
        assertEquals(List.of("L1", "L2", "L3", "L4"), view.get("levels"));
    }
}
