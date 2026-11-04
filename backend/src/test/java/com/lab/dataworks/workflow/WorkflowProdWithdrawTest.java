package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.lineage.LineageService;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.prefect.PrefectPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D31b 回归：PROD 待审批（PENDING_APPROVAL）必须有提交人撤回出口。
 *
 * <p>缺陷现场（与变更单 D31 同形，docs/功能缺陷清单-20261026.md）：v460 双人闸门让
 * 非平台管理员批不了自己创建的版本，而待审批又没有撤回接口 ——
 * 单管理员「提交 → 撤不掉、批不了」，版本永久停在 PENDING_APPROVAL。</p>
 *
 * <p>判定与期望：
 * 提交人撤自己的待审批单 → 200 + prod_status=CANCELLED（终态，可重提）；
 * 撤他人单 → 403，且不得触库；已批准/发布中/已上线 → 409；
 * 撤回接口必须显式声明权限点（D31 教训：无声明的写接口被拦截器默认拒绝，
 * 撤回口本身变成「403 接口未授权」死路）。</p>
 *
 * <p>撤掉修复必红：删判定分支 → 403/409 用例红；删 {@code @RequireAtom} → 注解用例红；
 * 改撤回 SQL / 改状态口径 → 对应 SQL 与终态用例红。</p>
 */
class WorkflowProdWithdrawTest {

    private static final long WF = 257L;
    private static final int V = 7;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final WorkflowMutationService mutations = mock(WorkflowMutationService.class);

    private final WorkflowController controller = new WorkflowController(
            jdbc, mock(PrefectFlowRenderer.class), mock(PrefectPublisher.class),
            sessions, new ObjectMapper(), mock(LineageService.class), mutations,
            mock(WorkflowValidationService.class), mock(WorkflowTriggerService.class),
            mock(BackfillService.class));

    /** withLock 在单测里直接执行回调（与真实实现的同步语义一致，只是不需要锁）。 */
    @SuppressWarnings("unchecked")
    private void passThroughLock() {
        when(mutations.withLock(anyLong(), any())).thenAnswer(inv ->
                ((Supplier<Object>) inv.getArgument(1)).get());
    }

    private static MockHttpServletRequest request(String username) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", username);
        return http;
    }

    /**
     * 按 SQL 片段分流查询：workflow 主表 / workflow_version。
     * versionRow 传 null 表示版本不存在（应 404）。
     */
    private void givenRows(Map<String, Object> versionRow) {
        Map<String, Object> wfRow = new LinkedHashMap<>();
        wfRow.put("id", WF);
        wfRow.put("code", "demo_wf");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.workflow w")) {
                return List.of(wfRow);
            }
            if (sql.contains("from platform.workflow_version")) {
                return versionRow == null ? List.of() : List.of(versionRow);
            }
            return List.of();
        });
    }

    private static Map<String, Object> versionRow(Object prodStatus, String status, String createdBy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9000L);
        row.put("workflow_id", WF);
        row.put("version_no", V);
        row.put("status", status);
        row.put("prod_status", prodStatus);
        row.put("created_by", createdBy);
        return row;
    }

    /** 撤回成功时记录 update 的 SQL 与绑定参数，供「不得撤他人单」的落库口径断言。 */
    private void givenUpdateResult(int updated) {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(updated);
    }

    private String lastUpdateSql() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(captor.capture(), any(Object[].class));
        return captor.getValue();
    }

    // ---------- 提交→撤回→终态 ----------

    @Test
    void submitterWithdrawsOwnPendingVersionToCancelled() {
        passThroughLock();
        givenRows(versionRow("PENDING_APPROVAL", "DRAFT", "alice"));
        givenUpdateResult(1);

        Map<String, Object> out = controller.withdraw(WF, V, request("alice"));

        assertEquals("CANCELLED", out.get("status"), "撤回终态必须是 CANCELLED");
        assertEquals("PROD", out.get("env"));
        assertEquals(V, out.get("versionNo"));
        // 请求原文：POST /api/workflows/257/versions/7/withdraw（alice）
        // 期望 vs 实际：清待审批时间戳、按版本行主键 + 提交人收口
        String sql = lastUpdateSql();
        assertTrue(sql.contains("prod_status = 'CANCELLED'"), "撤回必须写 prod_status=CANCELLED");
        assertTrue(sql.contains("prod_pending_at = null"), "撤回必须清掉 prod_pending_at");
        assertTrue(sql.contains("created_by = ?"), "撤回 SQL 必须绑定提交人，不得撤他人单");
        assertTrue(sql.contains("prod_status = 'PENDING_APPROVAL'"),
                "撤回必须是条件更新：只认领待审批行");
        verify(sessions).audit(org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.eq("PUBLISH_WITHDRAW"),
                org.mockito.ArgumentMatchers.eq("w" + WF), anyString());
    }

    @Test
    void legacySingleColumnPendingVersionIsStillWithdrawable() {
        // V62 之前的老数据只有单列 status='PENDING_APPROVAL'：撤回必须认它，
        // 否则老单据仍然卡死（口径与 prodStatus()/approve 一致）
        passThroughLock();
        givenRows(versionRow(null, "PENDING_APPROVAL", "alice"));
        givenUpdateResult(1);

        Map<String, Object> out = controller.withdraw(WF, V, request("alice"));
        assertEquals("CANCELLED", out.get("status"));
    }

    @Test
    void withdrawnVersionCannotBeWithdrawnAgainIsTerminal() {
        passThroughLock();
        givenRows(versionRow("CANCELLED", "DRAFT", "alice"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.withdraw(WF, V, request("alice")));
        assertEquals(409, e.getStatus(), "已撤回再撤 → 409");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // ---------- 撤他人单 403 ----------

    @Test
    void withdrawingForeignPendingVersionIsForbidden() {
        passThroughLock();
        givenRows(versionRow("PENDING_APPROVAL", "DRAFT", "alice"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.withdraw(WF, V, request("bob")));
        assertEquals(403, e.getStatus(), "撤他人单必须 403，而不是 400/409");
        assertTrue(e.getMessage().contains("自己"), "提示要说清只能撤自己提交的：" + e.getMessage());
        // 判定在落库前：他人单一条 update 都不许发
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(sessions).audit(org.mockito.ArgumentMatchers.eq("bob"),
                org.mockito.ArgumentMatchers.eq("PUBLISH_WITHDRAW_DENIED"),
                org.mockito.ArgumentMatchers.eq("w" + WF), anyString(),
                org.mockito.ArgumentMatchers.eq("FAILED"), any());
    }

    @Test
    void foreignAndApprovedStill403NotLeakingState() {
        // 先判人再判状态：他人已批准的单回 403，不借 409 泄露单据状态
        passThroughLock();
        givenRows(versionRow("PUBLISHED", "PUBLISHED", "alice"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.withdraw(WF, V, request("bob")));
        assertEquals(403, e.getStatus());
    }

    // ---------- 已批准不可撤 409 ----------

    @Test
    void approvedOrPublishingVersionsRejectWithdrawWith409() {
        passThroughLock();
        for (String state : List.of("PUBLISHING", "PUBLISHED", "SUPERSEDED", "OFFLINE")) {
            givenRows(versionRow(state, "PUBLISHED", "alice"));
            ApiException e = assertThrows(ApiException.class,
                    () -> controller.withdraw(WF, V, request("alice")),
                    "状态 " + state + " 不可撤回");
            assertEquals(409, e.getStatus(), "状态 " + state + " 应回 409");
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void neverSubmittedDraftHasNothingToWithdrawIs409() {
        passThroughLock();
        givenRows(versionRow("DRAFT", "DRAFT", "alice"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.withdraw(WF, V, request("alice")));
        assertEquals(409, e.getStatus(), "没提交过审批就没有待审批单可撤");
    }

    @Test
    void concurrentApproveWinsRaceStillGets409() {
        // 锁外读到待审批、锁内认领时 update 命中 0 行（审批人抢先）→ 必须 409，不能假成功
        passThroughLock();
        givenRows(versionRow("PENDING_APPROVAL", "DRAFT", "alice"));
        givenUpdateResult(0);

        ApiException e = assertThrows(ApiException.class,
                () -> controller.withdraw(WF, V, request("alice")));
        assertEquals(409, e.getStatus());
    }

    @Test
    void missingWorkflowOrVersionIsNotFound() {
        passThroughLock();
        givenRows(null);
        ApiException e = assertThrows(ApiException.class,
                () -> controller.withdraw(WF, V, request("alice")));
        assertEquals(404, e.getStatus(), "版本不存在 → 404，不能静默返回");
    }

    // ---------- 权限声明（D31 的「403 接口未授权」死路回归） ----------

    @Test
    void withdrawEndpointDeclaresSubmitterAtom() throws NoSuchMethodException {
        // 拦截器对「无权限声明的非 GET 接口」默认拒绝（AuthInterceptor:214-218）。
        // 撤回口声明的点必须与提交审批同点（workflow:publish）：提交人必然持有，撤得掉自己单；
        // 把注解删掉 → 本用例必红 → 线上表现就是 D31 式 403 死路。
        RequireAtom atom = WorkflowController.class
                .getMethod("withdraw", long.class, int.class, jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(RequireAtom.class);
        assertNotNull(atom, "withdraw 必须声明 @RequireAtom，否则拦截器默认拒绝");
        assertEquals(List.of("workflow:publish"), List.of(atom.value()),
                "撤回权限点必须与提交审批一致（workflow:publish）");
    }

    // ---------- 纯判定函数：口径与 approve 双人闸门一致 ----------

    @Test
    void decisionMatrix() {
        assertNull(WorkflowController.withdrawDecision("PENDING_APPROVAL", "alice", "alice"));
        assertEquals(403, WorkflowController.withdrawDecision("PENDING_APPROVAL", "bob", "alice"));
        assertEquals(403, WorkflowController.withdrawDecision("PENDING_APPROVAL", null, "alice"));
        assertEquals(403, WorkflowController.withdrawDecision("PUBLISHED", "bob", "alice"));
        assertEquals(409, WorkflowController.withdrawDecision("PUBLISHED", "alice", "alice"));
        assertEquals(409, WorkflowController.withdrawDecision("PUBLISHING", "alice", "alice"));
        assertEquals(409, WorkflowController.withdrawDecision("CANCELLED", "alice", "alice"));
        assertEquals(409, WorkflowController.withdrawDecision("DRAFT", "alice", "alice"));
    }

    @Test
    void effectiveProdStatusMatchesApproveGateSemantics() {
        // 与 prodStatus()/approve 同一份口径：prod_status 空才回退老单列
        Map<String, Object> legacy = versionRow(null, "PENDING_APPROVAL", "alice");
        assertEquals("PENDING_APPROVAL", WorkflowController.effectiveProdStatus(legacy));
        Map<String, Object> blank = versionRow("  ", "PENDING_APPROVAL", "alice");
        assertEquals("PENDING_APPROVAL", WorkflowController.effectiveProdStatus(blank));
        Map<String, Object> cancelled = versionRow("CANCELLED", "PENDING_APPROVAL", "alice");
        assertEquals("CANCELLED", WorkflowController.effectiveProdStatus(cancelled),
                "prod_status 非空时单列 status 不得覆盖环境状态");
        Map<String, Object> draft = versionRow(null, "DRAFT", "alice");
        assertEquals("DRAFT", WorkflowController.effectiveProdStatus(draft));
    }

    /** 撤回出口存在的同时，approve 的双人闸门不得被顺手放开（撤 ≠ 批）。 */
    @Test
    void withdrawDoesNotTouchApprovalClaimSql() {
        passThroughLock();
        givenRows(versionRow("PENDING_APPROVAL", "DRAFT", "alice"));
        givenUpdateResult(1);
        controller.withdraw(WF, V, request("alice"));
        String sql = lastUpdateSql();
        // 撤回只能自己撤（created_by 绑定）；approve 的「不能批自己」是另一道闸门，
        // 这里断言撤回 SQL 没有直接写 PUBLISHED（防止被改成「撤 = 自批」的后门）
        assertTrue(sql.contains("'CANCELLED'"), "撤回落库值只能是 CANCELLED");
        assertTrue(!sql.contains("PUBLISHED"), "撤回不得把版本直接置为已上线");
    }
}
