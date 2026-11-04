package com.lab.dataworks.tablemanage;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.security.DataGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S12（删表落点）：{@code DELETE /api/tables/{db}/{table}} 的 TABLE_DROP 审计
 * 不能再 {@code detail=null}——必须带可定位内容（库表全名、projectId、删前
 * SHOW CREATE TABLE 快照；快照取不到就记原因，不许静默），并且删表失败也要留一条
 * {@code result=FAILED} 的审计（try/catch 补记，异常照常上抛，状态码口径不变）。
 *
 * <p>缺陷现场（安全扫描实测）：审计行 target 有、detail 没有任何内容，
 * 事后仅凭审计无法判断「删掉的是什么」。</p>
 */
class TableDropAuditTest {

    private DorisAdminService doris;
    private SessionService sessions;
    private MetadataReader metadata;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        metadata = mock(MetadataReader.class);
        // BUG-TC4-1：删表现在先问生产库闸门；这些用例只验审计/错误映射，一律按 DEV 落点放行
        TableDdlService ddlGate = mock(TableDdlService.class);
        when(ddlGate.gateProductionDdl(anyString(), any(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(new TableDdlService.ProductionGate("DEV", null));
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class), sessions,
                mock(TenantDataScope.class), mock(WarehouseResolver.class), metadata,
                mock(DataGuardService.class), ddlGate);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Map<String, Object> showCreateRow(String ddl) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Table", "bug_t1");
        row.put("Create Table", ddl);
        return row;
    }

    /** SHOW CREATE TABLE 返回的快照（压成一行后原样进 detail）。 */
    private static final String DDL = "CREATE TABLE `bug_t1` (\n"
            + "  `id` int NOT NULL\n"
            + ") ENGINE=OLAP\n"
            + "DUPLICATE KEY(`id`)\n"
            + "DISTRIBUTED BY HASH(`id`) BUCKETS 1";

    private String capturedDetail(String result) {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("unknown"), eq("TABLE_DROP"), eq("demo.bug_t1"),
                detail.capture(), eq(result), any());
        return detail.getValue();
    }

    @Test
    void successAuditCarriesFullNameProjectIdAndDdlSnapshot() throws Exception {
        when(doris.queryAs(any(), startsWith("show create table")))
                .thenReturn(List.of(showCreateRow(DDL)));

        mvc.perform(delete("/api/tables/demo/bug_t1").param("confirm", "bug_t1")
                        .param("projectId", "1"))
                .andExpect(status().isOk());

        String detail = capturedDetail("SUCCESS");
        assertTrue(detail.contains("demo.bug_t1"), "库表全名要能定位: " + detail);
        assertTrue(detail.contains("projectId=1"), "projectId 要能定位: " + detail);
        assertTrue(detail.contains("ddlSnapshot=CREATE TABLE"),
                "删前 DDL 快照要进审计: " + detail);
        assertTrue(detail.contains("DUPLICATE KEY"), "快照内容完整（压成一行）: " + detail);
        // 删表成功后仍要失效元数据缓存（原行为不许丢）
        verify(metadata).invalidateProject(1L);
        verify(doris).exec(any(), eq("drop table `demo`.`bug_t1`"));
    }

    @Test
    void successWithoutProjectIdStillAuditsLocatableDetail() throws Exception {
        when(doris.queryAs(any(), startsWith("show create table")))
                .thenReturn(List.of(showCreateRow(DDL)));

        mvc.perform(delete("/api/tables/demo/bug_t1").param("confirm", "bug_t1"))
                .andExpect(status().isOk());

        String detail = capturedDetail("SUCCESS");
        assertTrue(detail.contains("projectId=-"), "未传 projectId 也要写明: " + detail);
        assertTrue(detail.contains("table=demo.bug_t1"), detail);
    }

    @Test
    void missingSnapshotRecordsReasonNotSilence() throws Exception {
        // 快照取不到：不许静默留空，要把原因写进 detail
        when(doris.queryAs(any(), startsWith("show create table")))
                .thenThrow(new RuntimeException("Unknown table demo.bug_t1"));

        mvc.perform(delete("/api/tables/demo/bug_t1").param("confirm", "bug_t1"))
                .andExpect(status().isOk());

        String detail = capturedDetail("SUCCESS");
        assertTrue(detail.contains("ddlSnapshot=unavailable("),
                "取不到快照必须写明原因: " + detail);
        assertTrue(detail.contains("Unknown table"), "原因要可诊断: " + detail);
    }

    @Test
    void failedDropStillWritesFailedAuditAndSkipsCacheInvalidation() throws Exception {
        when(doris.queryAs(any(), startsWith("show create table")))
                .thenReturn(List.of(showCreateRow(DDL)));
        doThrow(new RuntimeException("Doris connection refused"))
                .when(doris).exec(any(), anyString());

        // 异常照常上抛，S12 只补审计。
        // 状态码口径更新（D63，2026-10-27）：连接类失败不再落到全局 500「服务器内部错误」，
        // 而是过 TableController.dorisFailure 映射成 **502**（原文只进日志，响应已消毒）。
        // 「审计照常写」这件事本身没变，所以本用例仍是 S12 的守卫。
        mvc.perform(delete("/api/tables/demo/bug_t1").param("confirm", "bug_t1"))
                .andExpect(status().isBadGateway());

        String detail = capturedDetail("FAILED");
        assertTrue(detail.contains("table=demo.bug_t1"), detail);
        assertTrue(detail.contains("ddlSnapshot=CREATE TABLE"),
                "失败审计也要带删前快照（表可能还在，快照就是恢复依据）: " + detail);
        assertTrue(detail.contains("dropError=Doris connection refused"),
                "失败原因要落审计: " + detail);
        verify(metadata, never()).invalidateProject(any());
    }

    @Test
    void confirmMismatchIsRejectedBeforeAnyAudit() throws Exception {
        // 确认词不符 400：此时还没有执行删除，也不该产生 TABLE_DROP 审计
        mvc.perform(delete("/api/tables/demo/bug_t1").param("confirm", "other"))
                .andExpect(status().isBadRequest());
        verify(sessions, never()).audit(any(), eq("TABLE_DROP"), any(), any(), any(), any());
    }

}

