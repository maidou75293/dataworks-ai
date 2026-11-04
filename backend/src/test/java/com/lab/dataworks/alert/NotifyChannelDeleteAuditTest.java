package com.lab.dataworks.alert;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
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
 * D34（2026-10-26）：删通知渠道原先零审计——渠道被谁在什么时候删掉事后查不到，
 * 而渠道一没了对应告警就静默不发，属于「断监控」级别的动作。
 *
 * <p>现在沿用 S13 的破坏性动作口径：先记 PENDING 再执行，成功回填 SUCCESS（detail 带
 * 删前读到的渠道名/类型/归属，删完就查不到了），不存在 404 与「读到行但删除影响 0 行」
 * 都回填 FAILED + 原因，异常原样上抛（状态码口径不变、仍是物理删除）。</p>
 */
class NotifyChannelDeleteAuditTest {

    private JdbcTemplate biz;
    private JdbcTemplate auditJdbc;
    private NotifyController controller;

    @BeforeEach
    void setUp() {
        biz = mock(JdbcTemplate.class);
        auditJdbc = mock(JdbcTemplate.class);
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        controller = new NotifyController(biz, mock(NotifyService.class),
                mock(SecretCipher.class), sessions);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "notify_admin");
        http.setRemoteAddr("10.99.99.17");
        return http;
    }

    private static Map<String, Object> channelRow(Long projectId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "值班群钉钉");
        row.put("type", "DINGTALK");
        row.put("projectId", projectId);
        return row;
    }

    /** 归属查询（ownedChannel）走这条 queryForList；影响行数走 update，单独桩。 */
    private void stubChannelFound(Map<String, Object> row, int deletedRows) {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        when(biz.update(anyString(), any(Object[].class))).thenReturn(deletedRows);
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
     * 打到 stdout 进 logs，随绿日志一起留档，避免只看断言名不知道实际写了什么。
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
    void deleteChannelAuditsPendingThenSuccessWithChannelIdentity() {
        stubChannelFound(channelRow(3L), 1);

        Map<String, Object> out = controller.delete(12, request());

        List<Object> pending = pendingArgs();
        assertEquals("notify_admin", pending.get(0), "D34：操作人必须进审计");
        assertEquals("NOTIFY_CHANNEL_DELETE", pending.get(1));
        assertEquals("n12", pending.get(2), "D34：目标口径 n<id>");
        assertEquals("PENDING", pending.get(4), "破坏性动作要先记后做");
        assertEquals("default", pending.get(6), "D34：tenant_id 不能缺");
        assertEquals("10.99.99.17", pending.get(5), "IP 要能定位来源");

        List<Object> settled = settledArgs();
        assertEquals("SUCCESS", settled.get(0));
        String detail = String.valueOf(settled.get(1));
        assertTrue(detail.contains("值班群钉钉") && detail.contains("DINGTALK")
                        && detail.contains("项目 3"),
                "D34：渠道名/类型/归属必须留在审计里（删完就查不到了）: " + detail);
        // 业务语义不变：仍是物理删除，响应体形状不变
        verify(biz).update(contains("delete from platform.notify_channel"), any(Object[].class));
        assertEquals(Map.of("ok", true, "id", 12L), out);

        evidence("NOTIFY_DELETE_SUCCESS", "DELETE /api/notify-channels/12（notify_admin，notify:manage）",
                pending, settled, out.toString(),
                "result=SUCCESS，detail 含渠道名「值班群钉钉」/类型 DINGTALK/归属 项目 3");
    }

    /** 平台级渠道（project_id 为空）也要能从审计看出删的是哪一档。 */
    @Test
    void deletePlatformChannelAuditsPlatformScope() {
        stubChannelFound(channelRow(null), 1);

        controller.delete(7, request());

        assertTrue(String.valueOf(settledArgs().get(1)).contains("平台级"),
                String.valueOf(settledArgs().get(1)));
    }

    @Test
    void deleteMissingChannelAuditsFailedAndKeeps404() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class, () -> controller.delete(99, request()));
        assertEquals(404, e.getStatus(), "状态码口径不变");

        List<Object> pending = pendingArgs();  // 试删不存在的渠道也必须留痕
        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("渠道不存在: 99"),
                String.valueOf(settled.get(1)));

        evidence("NOTIFY_DELETE_404", "DELETE /api/notify-channels/99（notify_admin，notify:manage）",
                pending, settled, "HTTP 404 ApiException: " + e.getMessage(),
                "result=FAILED，detail 含失败原因「渠道不存在: 99」，状态码仍 404");
    }

    /** 读到行但删除影响 0 行（并发被别的会话删了）同样是失败尝试，要留 FAILED。 */
    @Test
    void deleteRacingToZeroRowsAuditsFailed() {
        stubChannelFound(channelRow(3L), 0);

        ApiException e = assertThrows(ApiException.class, () -> controller.delete(12, request()));
        assertEquals(404, e.getStatus());

        assertEquals("FAILED", settledArgs().get(0));
        assertTrue(String.valueOf(settledArgs().get(1)).contains("渠道不存在"),
                String.valueOf(settledArgs().get(1)));
    }

    /** 归属判定从 void 改成返回行数据后，启停通道的 404 口径必须原样不变。 */
    @Test
    void toggleStillRejectsMissingChannelWith404() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class,
                () -> controller.toggle(99, new NotifyController.EnabledRequest(false)));
        assertEquals(404, e.getStatus());
        verify(biz, never()).update(contains("set enabled"), any(Object[].class));
    }
}
