package com.lab.dataworks.tablemanage;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-TC1-3：删库入口缺失 + 保留段误落删表。
 *
 * <p>缺陷现场（TC-1 泳道取证，tasks/BUG-TC1-3.md）：接口层只有建库没有删库，
 * {@code DELETE /api/tables/databases/<db>} 被匹配成删表（database="databases"），
 * 执行 {@code drop table `databases`.`<db>`} → Doris 原文 {@code Unknown database 'databases'}
 * 以 <b>502</b> 透传；测试残留空库（bug_a_db_*）无法自建自清。</p>
 *
 * <p>修复口径：① 正式删库入口（confirm=库名、只允许空库、{@code TABLE_DROP_DATABASE}
 * 成功/失败都落审计、成功后失效元数据缓存）；② 大小写变体仍落删表时以 405 显式拒绝，
 * 不再让 Doris 原文经 502 漏出。</p>
 */
class TableDropDatabaseTest {

    private DorisAdminService doris;
    private SessionService sessions;
    private MetadataReader metadata;
    private TenantDataScope scope;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        metadata = mock(MetadataReader.class);
        scope = mock(TenantDataScope.class);
        // CONFLICT-WM1：合流后 TableController 多了第 9 个依赖（BUG-TC4-1 生产闸）。
        // 本卡只打 /databases 删库入口（自带 _prd 判定），不触达建表闸，mock 空实例即可。
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class), sessions,
                scope, mock(WarehouseResolver.class), metadata, mock(DataGuardService.class),
                mock(TableDdlService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Map<String, Object> tableRow(String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Tables_in_demo", name);
        return row;
    }

    private void stubTables(String... names) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String n : names) {
            rows.add(tableRow(n));
        }
        when(doris.queryAs(any(), contains("show tables from"))).thenReturn(rows);
    }

    @Test
    void emptyDatabaseDropsWithAuditAndInvalidation() throws Exception {
        stubTables();

        mvc.perform(delete("/api/tables/databases/bug_a_db_1?confirm=bug_a_db_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 走的是删库 DDL，不是删表
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(doris).exec(any(), sql.capture());
        assertEquals("drop database `bug_a_db_1`", sql.getValue());
        verify(doris, never()).exec(any(), contains("drop table"));
        // 审计 + 缓存失效（与建库/删表同一口径）
        verify(sessions).audit(eq("unknown"), eq("TABLE_DROP_DATABASE"), eq("bug_a_db_1"),
                contains("database=bug_a_db_1"), eq("SUCCESS"), any());
        verify(metadata).invalidateProject(null);
    }

    @Test
    void nonEmptyDatabaseIsRejectedWithTableCount() throws Exception {
        stubTables("orders", "users");

        mvc.perform(delete("/api/tables/databases/demo_db?confirm=demo_db"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("库非空（2 张表）")));

        verify(doris, never()).exec(any(), contains("drop database"));
        verify(metadata, never()).invalidateProject(any());
    }

    @Test
    void confirmMustEqualDatabaseName() throws Exception {
        stubTables();

        mvc.perform(delete("/api/tables/databases/demo_db?confirm=demo_dbx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("删除确认需要与库名一致")));

        verify(doris, never()).exec(any(), contains("drop database"));
    }

    @Test
    void systemDatabaseCannotBeDropped() throws Exception {
        stubTables();

        mvc.perform(delete("/api/tables/databases/information_schema?confirm=information_schema"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("系统库不可删除")));

        verify(doris, never()).exec(any(), contains("drop database"));
    }

    @Test
    void foreignDatabaseIs404LikeDropTable() throws Exception {
        // 归属校验口径与删表一致：scope.requireDatabase 对非本租户库抛 404（不暴露存在性）
        org.mockito.Mockito.doThrow(ApiException.notFound("库不存在: other_db"))
                .when(scope).requireDatabase("other_db");

        mvc.perform(delete("/api/tables/databases/other_db?confirm=other_db"))
                .andExpect(status().isNotFound());
    }

    @Test
    void failureStillWritesAuditWithReason() throws Exception {
        stubTables();
        doThrow(new RuntimeException("Doris 执行失败: errCode = 2, detailMessage = boom"))
                .when(doris).exec(any(), contains("drop database"));

        mvc.perform(delete("/api/tables/databases/demo_db?confirm=demo_db"))
                .andExpect(status().is5xxServerError());

        // 失败也要落审计（否则「谁在什么时候试图删什么、删没删掉」对不上账）
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("unknown"), eq("TABLE_DROP_DATABASE"), eq("demo_db"),
                detail.capture(), eq("FAILED"), any());
        assertTrue(detail.getValue().contains("dropError="), detail.getValue());
        verify(metadata, never()).invalidateProject(any());
    }

    @Test
    void uppercaseReservedSegmentIs405NotDorisRawError() throws Exception {
        // 大小写变体绕过专用删库路由（路径匹配区分大小写）落到删表逻辑：
        // 必须 405 + 指向正确入口，不能再走到 drop table → Doris 原文 502
        mvc.perform(delete("/api/tables/DATABASES/bug_a_db_1?confirm=bug_a_db_1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("删库请用")));

        verify(doris, never()).exec(any(), contains("drop table"));
    }
}
