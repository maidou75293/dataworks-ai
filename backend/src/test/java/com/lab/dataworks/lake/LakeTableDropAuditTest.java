package com.lab.dataworks.lake;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.security.DataGuardService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S12 + S13（湖表删除落点）：{@code LAKE_TABLE_DROP} 原来先做后记且 detail 只有一个
 * {@code tenant=}——confirm 不匹配 400、未纳管 409、DDL 失败时一条审计都不留。
 * 现在先记 PENDING（带全表归属：租户 + Catalog + 库 + 表）再执行，
 * 失败回填 FAILED 且异常原样上抛。
 */
class LakeTableDropAuditTest {

    private LakeCatalogService lake;
    private JdbcTemplate auditJdbc;
    private LakeController controller;

    @BeforeEach
    void setUp() {
        lake = mock(LakeCatalogService.class);
        auditJdbc = mock(JdbcTemplate.class);
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        controller = new LakeController(lake, mock(DataGuardService.class), sessions);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "lake_admin");
        http.setRemoteAddr("10.99.99.13");
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

    @Test
    void dropLakeTableRecordsPendingWithOwnershipThenSuccess() {
        controller.dropTable("iceberg_prod", "ods", "demo_orders", "demo_orders", request());

        List<Object> pending = pendingArgs();
        assertEquals("lake_admin", pending.get(0));
        assertEquals("LAKE_TABLE_DROP", pending.get(1));
        assertEquals("iceberg_prod.ods.demo_orders", pending.get(2));
        String detail = String.valueOf(pending.get(3));
        assertTrue(detail.contains("catalog=iceberg_prod") && detail.contains("database=ods")
                && detail.contains("table=demo_orders"), "S12：detail 要带全表归属: " + detail);
        assertEquals("PENDING", pending.get(4));
        assertEquals("10.99.99.13", pending.get(5));
        assertEquals("default", pending.get(6), "S12：tenant_id 不能缺");
        assertEquals("SUCCESS", settledArgs().get(0));
    }

    /** S13 的核心：底层 DDL 抛异常时不能无痕，要留下 FAILED 与原因。 */
    @Test
    void dropFailureStillAuditsFailedAndPropagates() {
        doThrow(new IllegalStateException("drop DDL 失败: table not found"))
                .when(lake).dropTable("iceberg_prod", "ods", "ghost", "ghost");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> controller.dropTable("iceberg_prod", "ods", "ghost", "ghost", request()));
        assertTrue(e.getMessage().contains("table not found"), "底层错误不能被改掉");

        pendingArgs(); // 先记那条必须存在
        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("drop DDL 失败"),
                "S13：失败原因要进审计 detail");
    }
}
