package com.lab.dataworks.tablemanage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D27：驳回不是审批。REJECTED 的 {@code approved_at}/{@code approver} 必须保持为空，
 * 驳回时刻只进 {@code finished_at}——口径与 CANCELLED 分支一致。
 *
 * <p>缺陷现场：{@code POST /api/tables/ddl-jobs/30/reject} 之后工单详情同时出现
 * {@code approvedAt=驳回时刻} 与 {@code approver=驳回人}，列表把「审批时间」显示成
 * 驳回时间，审计无法区分通过/驳回；而 CANCELLED 分支从来不写这两列。</p>
 *
 * <p>驳回人身份不能丢：本用例同时钉住 ①工单事件 REJECT（actor=驳回人）
 * ②审计 {@code TABLE_DDL_REJECT}（actor=驳回人）。</p>
 *
 * <p>测试口径：真实 {@link TableDdlService} + mock 的 JdbcTemplate，捕获真正发给
 * PG 的 SQL——只看字符串即可判定 approved_at/approver 有没有被写。</p>
 */
class TableDdlRejectSemanticsTest {

    private JdbcTemplate jdbc;
    private SessionService sessions;
    private TableDdlService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        sessions = mock(SessionService.class);
        service = new TableDdlService(jdbc, mock(DorisAdminService.class), sessions,
                mock(TenantDataScope.class), new ObjectMapper(), mock(WarehouseResolver.class),
                mock(com.lab.dataworks.metadata.MetadataReader.class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    /** 造一条待审批（或指定状态）的工单行；租户未设置 → TenantScope 默认 default。 */
    private void stubJob(long id, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("tenant_id", "default");
        row.put("database_name", "demo");
        row.put("table_name", "t");
        row.put("status", status);
        row.put("requested_by", "requester");
        row.put("ddl_sql", "alter table demo.t add column c int null");
        row.put("ddl_hash", "hash");
        row.put("operation", "ALTER_ADD_COLUMN");
        row.put("connection_key", "builtin");
        when(jdbc.queryForList(startsWith("select *"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    /** 带 system:manage 的平台管理员请求（默认租户），跳过负责人校验。 */
    private MockHttpServletRequest adminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("username", "admin");
        request.setAttribute("atoms", Set.of("system:manage"));
        return request;
    }

    @Test
    void rejectKeepsApprovedAtNullAndRecordsFinishTime() {
        stubJob(30, "PENDING_APPROVAL");

        service.reject(30, "reject probe", adminRequest());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("reject probe"), eq(30L), eq("default"));
        String update = sql.getValue();
        assertTrue(update.contains("status = 'REJECTED'"), update);
        assertTrue(update.contains("finished_at = now()"),
                "驳回时刻仍要落 finished_at: " + update);
        assertFalse(update.contains("approved_at"),
                "D27：驳回不能写审批时间，实际 SQL: " + update);
        assertFalse(update.contains("approver"),
                "D27：驳回不能把驳回人写成审批人，实际 SQL: " + update);
    }

    @Test
    void rejectStillRecordsActorInEventAndAudit() {
        stubJob(30, "PENDING_APPROVAL");

        service.reject(30, "reject probe", adminRequest());

        // ① 工单事件：REJECT，actor=驳回人
        verify(jdbc).update(startsWith("insert into platform.table_ddl_job_event"),
                eq(30L), eq("default"), eq("REJECT"), eq("admin"), eq("reject probe"));
        // ② 审计：TABLE_DDL_REJECT 带驳回人与目标表，身份可回查
        verify(sessions).audit(eq("admin"), eq("TABLE_DDL_REJECT"), eq("ddl_job:30"),
                contains("demo.t"), eq("SUCCESS"), any());
    }

    @Test
    void approveStillWritesApprovedAtAndApprover() {
        // 对照：这次改动只收紧 REJECTED；PENDING→审批的 SQL 口径不许误伤
        stubJob(31, "PENDING_APPROVAL");

        service.approve(31, "ok", adminRequest());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("admin"), eq("ok"), eq(31L), eq("default"));
        String update = sql.getValue();
        assertTrue(update.contains("status = 'PENDING'"), update);
        assertTrue(update.contains("approver = ?"), update);
        assertTrue(update.contains("approved_at = now()"), update);
    }

    @Test
    void cancelSemanticsAreTheBaselineForReject() {
        // 与 CANCELLED 分支口径一致：只写 finished_at，不写 approved_at / approver
        stubJob(32, "PENDING");

        service.cancel(32, "no longer needed", adminRequest());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("no longer needed"), eq(32L), eq("default"));
        String update = sql.getValue();
        assertTrue(update.contains("status = 'CANCELLED'"), update);
        assertTrue(update.contains("finished_at = now()"), update);
        assertFalse(update.contains("approved_at"), update);
        assertFalse(update.contains("approver"), update);
    }
}
