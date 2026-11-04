package com.lab.dataworks.integration;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * D34（2026-10-26）：撤回记忆原先零审计——归档语义本来是对的（内核只追加、不物理删），
 * 但谁撤回了哪条记忆、属于哪个 scope/创建者，事后在安全中心查不到；被拒（403）与
 * 不存在（404）的尝试同样什么都不留，合规上等于「处理个人数据无记录」。
 *
 * <p>现在按 S13 口径：先记 {@code AGENT_MEMORY_FORGET}（目标 {@code m<id>}）的 PENDING，
 * 归档前读到的 scope/owner/记忆名/创建者进 detail，成功回填 SUCCESS，
 * 403/404 回填 FAILED + 原因；业务语义不变——仍是归档而非物理删除。</p>
 */
class AgentMemoryForgetAuditTest {

    private JdbcTemplate biz;
    private JdbcTemplate auditJdbc;
    private AgentMemoryController controller;

    @BeforeEach
    void setUp() {
        biz = mock(JdbcTemplate.class);
        auditJdbc = mock(JdbcTemplate.class);
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        // tenantOf(user) 走 queryForList(sql, String.class, user) 这一档重载
        when(biz.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("default"));
        controller = new AgentMemoryController(biz, sessions);
    }

    private static MockHttpServletRequest request(String username) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", username);
        http.setRemoteAddr("10.99.99.18");
        return http;
    }

    private static Map<String, Object> memoryRow(String scope, String owner, String createdBy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scope", scope);
        row.put("owner", owner);
        row.put("key", "dws 金额口径");
        row.put("createdBy", createdBy);
        return row;
    }

    private void stubMemory(Map<String, Object> row) {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);
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

    /**
     * AUDIT-1 证据三件套：请求原文 / 审计行原文（先记 + 终态）/ 期望 vs 实际。
     * 打到 stdout 进 logs，随绿日志一起留档。
     */
    private static void evidence(String label, String request, List<Object> pending,
                                 List<Object> settled, String response, String expect) {
        System.out.println("D34-EVID[" + label + "] 请求原文: " + request);
        System.out.println("D34-EVID[" + label + "] 审计先记: username=" + pending.get(0)
                + " action=" + pending.get(1) + " target=" + pending.get(2)
                + " detail=" + pending.get(3) + " result=" + pending.get(4)
                + " ip=" + pending.get(5) + " tenant=" + pending.get(6));
        System.out.println("D34-EVID[" + label + "] 审计终态: result=" + settled.get(0)
                + " detail=" + settled.get(1));
        System.out.println("D34-EVID[" + label + "] 响应原文: " + response);
        System.out.println("D34-EVID[" + label + "] 期望: " + expect);
    }

    @Test
    void forgetAuditsPendingThenSuccessWithMemoryIdentity() {
        stubMemory(memoryRow("user", "alice", "alice"));

        Map<String, Object> out = controller.forget(41, request("alice"));

        List<Object> pending = pendingArgs();
        assertEquals("alice", pending.get(0), "D34：操作人必须进审计");
        assertEquals("AGENT_MEMORY_FORGET", pending.get(1));
        assertEquals("m41", pending.get(2), "D34：目标口径 m<id>");
        assertEquals("PENDING", pending.get(4), "撤回要「先记后做」");
        assertEquals("default", pending.get(6), "D34：tenant_id 不能缺");
        assertEquals("10.99.99.18", pending.get(5), "IP 要能定位来源");

        List<Object> settled = settledArgs();
        assertEquals("SUCCESS", settled.get(0));
        String detail = String.valueOf(settled.get(1));
        assertTrue(detail.contains("scope=user") && detail.contains("owner=alice")
                        && detail.contains("key=dws 金额口径") && detail.contains("创建者=alice"),
                "D34：归档前读到的 scope/owner/记忆名/创建者必须留痕: " + detail);
        // 业务语义不变：归档而非物理删，响应体形状不变
        verify(biz).update(contains("set status = 'archived'"), any(Object[].class));
        verify(biz, never()).update(contains("delete from platform.agent_memory"),
                any(Object[].class));
        assertEquals(Map.of("ok", true, "archived", true), out);

        evidence("MEMORY_FORGET_SUCCESS", "DELETE /api/agent/memories/41（alice，agent:chat）",
                pending, settled, out.toString(),
                "result=SUCCESS，detail 含归档前读到的 scope=user/owner=alice/"
                        + "key=dws 金额口径/创建者=alice");
    }

    @Test
    void forgetMissingMemoryAuditsFailedAndKeeps404() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class,
                () -> controller.forget(99, request("alice")));
        assertEquals(404, e.getStatus(), "状态码口径不变");

        List<Object> pending = pendingArgs(); // 试撤不存在的记忆也要留痕
        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("记忆不存在: 99"),
                String.valueOf(settled.get(1)));

        evidence("MEMORY_FORGET_404", "DELETE /api/agent/memories/99（alice，agent:chat）",
                pending, settled, "HTTP 404 ApiException: " + e.getMessage(),
                "result=FAILED，detail 含失败原因「记忆不存在: 99」，状态码仍 404");
    }

    /** 别人试图撤回我的记忆被拒：403 也必须留下 FAILED（谁试过撤它）。 */
    @Test
    void forgetByOtherUserAuditsFailed403() {
        stubMemory(memoryRow("user", "bob", "bob"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.forget(41, request("alice")));
        assertEquals(403, e.getStatus());

        List<Object> pending = pendingArgs();
        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("只有创建者、项目负责人或管理员"),
                String.valueOf(settled.get(1)));
        verify(biz, never()).update(contains("set status = 'archived'"), any(Object[].class));

        evidence("MEMORY_FORGET_403", "DELETE /api/agent/memories/41（alice 撤 bob 的记忆）",
                pending, settled, "HTTP 403 ApiException: " + e.getMessage(),
                "result=FAILED，detail 含拒绝原因，且不落归档");
    }

    /**
     * 项目级记忆被非负责人撤回：403 也要留 FAILED，且 target 能定位是哪条记忆。
     *
     * <p>口径说明：{@code SessionService.auditAround} 在失败时只把「失败原因」追加进 detail
     * （S13 契约），删前/归档前读到的 scope/key 只在 SUCCESS 里出现。被拒的尝试靠
     * target {@code m<id>} + 操作人 + 租户定位，与既有 DATASOURCE_DELETE / PROJECT_DELETE
     * 等破坏性动作审计一致，本卡不另立口径。</p>
     */
    @Test
    void forgetProjectMemoryByStrangerAuditsFailed() {
        when(biz.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(memoryRow("project", "5", "bob")));
        when(biz.queryForObject(contains("platform.project"), eq(Integer.class),
                any(Object[].class))).thenReturn(0);

        ApiException e = assertThrows(ApiException.class,
                () -> controller.forget(41, request("alice")));
        assertEquals(403, e.getStatus());

        assertEquals("m41", pendingArgs().get(2), "被拒的尝试也要能定位是哪条记忆");
        assertEquals("FAILED", settledArgs().get(0));
        assertTrue(String.valueOf(settledArgs().get(1)).contains("只有创建者、项目负责人或管理员"),
                String.valueOf(settledArgs().get(1)));
        verify(biz, never()).update(contains("set status = 'archived'"), any(Object[].class));
    }
}
