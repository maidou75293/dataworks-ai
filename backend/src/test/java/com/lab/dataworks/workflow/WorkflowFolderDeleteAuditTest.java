package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.lineage.LineageService;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.prefect.PrefectPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S12（工作流文件夹落点）：删文件夹原先零审计。现在先记 PENDING 再做，
 * 「目录下还有工作流 / 子目录」400 与不存在 404 都回填 FAILED——
 * 破坏性尝试必须留痕，异常原样上抛（状态码口径不变）。
 */
class WorkflowFolderDeleteAuditTest {

    private JdbcTemplate biz;
    private JdbcTemplate auditJdbc;
    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        biz = mock(JdbcTemplate.class);
        auditJdbc = mock(JdbcTemplate.class);
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(biz.queryForObject(contains("platform.project"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        controller = new WorkflowController(biz, mock(PrefectFlowRenderer.class),
                mock(PrefectPublisher.class), sessions, new ObjectMapper(),
                mock(LineageService.class), mock(WorkflowMutationService.class),
                mock(WorkflowValidationService.class), mock(WorkflowTriggerService.class),
                mock(BackfillService.class));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "wf_admin");
        http.setRemoteAddr("10.99.99.16");
        return http;
    }

    private List<Object> pendingArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).queryForObject(contains("insert into platform.audit_log"),
                eq(Long.class), args.capture());
        return List.of(args.getValue());
    }

    private List<Object> settledArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).update(contains("update platform.audit_log"), args.capture());
        return List.of(args.getValue());
    }

    private void stubEmptyFolder() {
        when(biz.queryForObject(contains("starts_with(folder"), eq(Integer.class),
                any(Object[].class))).thenReturn(0);
        when(biz.queryForObject(contains("workflow_folder"), eq(Integer.class),
                any(Object[].class))).thenReturn(0);
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test
    void deleteFolderIsAuditedPendingThenSuccess() {
        stubEmptyFolder();

        controller.deleteFolder(5, "ods/日报", request());

        List<Object> pending = pendingArgs();
        assertEquals("wf_admin", pending.get(0));
        assertEquals("WORKFLOW_FOLDER_DELETE", pending.get(1));
        assertEquals("ods/日报", pending.get(2));
        assertTrue(String.valueOf(pending.get(3)).contains("projectId=5"),
                "S12：要能定位是哪个项目的目录");
        assertEquals("PENDING", pending.get(4));
        assertEquals("default", pending.get(6), "S12：tenant_id 不能缺");
        assertEquals("SUCCESS", settledArgs().get(0));
    }

    /** S13：非空目录删除被拒，也要留下 FAILED（谁试过删它）。 */
    @Test
    void deleteNonEmptyFolderAuditsFailedAndKeepsStatusCode() {
        when(biz.queryForObject(contains("starts_with(folder"), eq(Integer.class),
                any(Object[].class))).thenReturn(2);

        ApiException e = assertThrows(ApiException.class,
                () -> controller.deleteFolder(5, "ods/日报", request()));
        assertEquals(400, e.getStatus(), "状态码口径不变");

        pendingArgs(); // 被拒的尝试也必须先留有 PENDING
        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("目录下还有工作流"),
                String.valueOf(settled.get(1)));
    }
}
