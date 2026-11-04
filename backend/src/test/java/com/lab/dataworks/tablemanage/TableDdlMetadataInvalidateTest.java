package com.lab.dataworks.tablemanage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-TC1-2：alter / rename / truncate 工单进终态后必须同步失效 {@link MetadataReader} 缓存，
 * 保证「工单 FINISHED ⇒ GET /api/tables/{db}/{t} 回读一致」。
 *
 * <p>缺陷现场（TC-1 泳道特征化，tasks/BUG-TC1-2.md）：DROP_COLUMN 工单 FINISHED 后，
 * 表详情在 45 秒 TTL 内仍返回已删除的列（metadataCached=true）；TableController 的
 * 建库/建表/SQL 建表/删表四处都调了 invalidateProject，本 Service 一处都没调。</p>
 *
 * <p>覆盖四条终态路径：同步执行成功（FINISHED）、同步执行失败（FAILED）、
 * 轮询发现 FINISHED、轮询发现 CANCELLED/FAILED；并对照「未进终态（还有异步作业在跑）」
 * 不失效。</p>
 */
class TableDdlMetadataInvalidateTest {

    private JdbcTemplate jdbc;
    private DorisAdminService doris;
    private MetadataReader metadata;
    private TableDdlService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        doris = mock(DorisAdminService.class);
        metadata = mock(MetadataReader.class);
        service = new TableDdlService(jdbc, doris, mock(SessionService.class),
                mock(TenantDataScope.class), new ObjectMapper(), mock(WarehouseResolver.class),
                metadata);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(5L);
    }

    private Map<String, Object> jobRow(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 48L);
        row.put("tenant_id", "default");
        row.put("project_id", 7L);
        row.put("database_name", "demo");
        row.put("table_name", "bug_t2");
        row.put("status", status);
        row.put("requested_by", "requester");
        row.put("ddl_sql", "alter table demo.bug_t2 drop column age");
        row.put("ddl_hash", "hash");
        row.put("operation", "ALTER_DROP_COLUMN");
        row.put("connection_key", "builtin");
        return row;
    }

    private MockHttpServletRequest adminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("username", "admin");
        request.setAttribute("atoms", Set.of("system:manage"));
        return request;
    }

    /** SHOW ALTER TABLE COLUMN 返回指定状态的 schema change 作业。 */
    private void stubAlterState(String state) {
        Map<String, Object> alter = new LinkedHashMap<>();
        alter.put("TableName", "bug_t2");
        alter.put("JobId", "9001");
        alter.put("State", state);
        alter.put("Progress", "100%");
        when(doris.queryAs(any(), contains("show alter table column")))
                .thenReturn(List.of(alter));
    }

    private void stubJobLookup(Map<String, Object> row) {
        when(jdbc.queryForList(startsWith("select *"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    /** 轮询器入口用的是无参重载 queryForList(String)，单独 stub（SQL 是 text block，按片段匹配）。 */
    private void stubPollLookup(Map<String, Object> row) {
        when(jdbc.queryForList(contains("doris_job_id is not null")))
                .thenReturn(List.of(row));
    }

    @Test
    void syncFinishInvalidatesProjectMetadataCache() {
        stubJobLookup(jobRow("PENDING_APPROVAL"));
        // 无 schema change 作业记录 → 轻量变更按完成处理
        when(doris.queryAs(any(), anyString())).thenReturn(List.of());

        service.approve(48L, "ok", adminRequest());

        verify(metadata).invalidateProject(7L);
    }

    @Test
    void asyncFinishViaPollerInvalidates() {
        Map<String, Object> row = jobRow("RUNNING");
        row.put("doris_job_id", "9001");
        stubPollLookup(row);
        stubAlterState("FINISHED");
        when(doris.queryAs(any(), contains("show create table"))).thenReturn(List.of());

        service.pollRunningJobs();

        verify(metadata).invalidateProject(7L);
    }

    @Test
    void syncFailureInvalidatesToo() {
        stubJobLookup(jobRow("PENDING_APPROVAL"));
        // DDL 执行可能超时但 Doris 已收下：终态缓存不可信，失败也要失效
        org.mockito.Mockito.doThrow(new RuntimeException("errCode = 2, detailMessage = boom"))
                .when(doris).exec(any(), anyString());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.lab.dataworks.common.ApiException.class,
                () -> service.approve(48L, "ok", adminRequest()));

        verify(metadata).invalidateProject(7L);
    }

    @Test
    void pollerCancelledInvalidates() {
        Map<String, Object> row = jobRow("RUNNING");
        row.put("doris_job_id", "9001");
        stubPollLookup(row);
        stubAlterState("CANCELLED");

        service.pollRunningJobs();

        verify(metadata).invalidateProject(7L);
    }

    @Test
    void stillRunningDoesNotInvalidateYet() {
        // 反向锁：作业还在跑（State=RUNNING 的 schema change），工单留在 RUNNING，
        // 此时结构未定，不该把「未终态」当成失效信号——同时证明上面四条不是恒真断言
        stubJobLookup(jobRow("PENDING_APPROVAL"));
        stubAlterState("RUNNING");

        service.approve(48L, "ok", adminRequest());

        verify(metadata, never()).invalidateProject(any());
    }
}
