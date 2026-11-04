package com.lab.dataworks.project;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D48 回归：数仓连接删除前的引用检查。
 *
 * <p>立案症状：后端只有 {@code DELETE /api/warehouse-connections/{id}} 且直接删，
 * 界面也没有入口（{@code grep -r warehouse-connections web/src} = 0）。
 * 直接删的后果是项目侧 {@code doris_connection_id} 悬空，
 * 要到下一次查询/DDL 才炸出「连接不存在」，用户不知道是删连接引起的。</p>
 *
 * <p>本测试钉：① 连接仍被项目引用 → 400 且响应里列出项目名，**不执行删除**；
 * ② 没被引用 → 正常删除并走审计包装。</p>
 */
class WarehouseConnectionDeleteTest {

    private static final long CONNECTION_ID = 12L;
    private static final String TENANT = "tenant_a";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private WarehouseConnectionController controller;

    @BeforeEach
    void setUp() {
        TenantScope.set(TENANT);
        when(http.getAttribute("username")).thenReturn("root");
        // 审计包装：直接把业务回调跑掉，测试关心的是「删没删」。
        // delete() 走的是 6 参重载（无 outcomeDetail），两个重载都要桩，
        // 否则 mock 对未桩的那个返回 null，NPE 会伪装成「业务代码有 bug」。
        when(sessions.auditAround(anyString(), anyString(), anyString(), anyString(), any(),
                any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(5)).get());
        when(sessions.auditAround(anyString(), anyString(), anyString(), anyString(), any(),
                any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(5)).get());
        controller = new WarehouseConnectionController(jdbc, mock(SecretCipher.class), sessions);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.project")) {
                return List.of();                       // 默认无人引用
            }
            java.util.LinkedHashMap<String, Object> conn = new java.util.LinkedHashMap<>();
            conn.put("name", "销售域");
            conn.put("type", "doris");
            conn.put("provider", null);
            conn.put("endpoint", "10.0.0.1:9030");
            return List.of(conn);
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    @Test
    void deleteWhileReferencedByProjects_is400AndNothingDeleted() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.project")) {
                return List.of(Map.of("id", 7L, "name", "销售域"),
                        Map.of("id", 8L, "name", "供应链"));
            }
            java.util.LinkedHashMap<String, Object> conn = new java.util.LinkedHashMap<>();
            conn.put("name", "销售域");
            conn.put("type", "doris");
            conn.put("provider", null);
            conn.put("endpoint", "10.0.0.1:9030");
            return List.of(conn);
        });

        ApiException e = assertThrows(ApiException.class,
                () -> controller.delete(CONNECTION_ID, http));

        assertEquals(400, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains("销售域") && e.getMessage().contains("供应链"),
                e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void deleteWhenUnreferenced_removesRowAndAudits() {
        Map<String, Object> out = controller.delete(CONNECTION_ID, http);

        assertEquals(Boolean.TRUE, out.get("ok"));
        verify(jdbc).update(anyString(), any(Object[].class));
        verify(sessions).auditAround(anyString(), anyString(), anyString(), anyString(),
                any(), any());
    }

    @Test
    void referenceLookupIsTenantScoped() {
        controller.delete(CONNECTION_ID, http);
        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("from platform.project") && sql.contains("tenant_id = ?")),
                any(Object[].class));
    }
}
