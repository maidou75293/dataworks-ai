package com.lab.dataworks.alert;

import com.lab.dataworks.auth.AuthInterceptor;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-TC6-8（2026-10-27）：告警投递可见性 —— 重投接口 + 投递记录暴露事件标题。
 *
 * <p>卡面事实：投递失败自动重试最多 5 次（{@code NotifyService.deliverPending()} 按
 * {@code attempts >= 5} 排除），耗尽后该事件对该渠道静默永久丢失；而
 * {@code GET /api/alert-deliveries} 前端零引用，值班员在 UI 上完全看不到
 * 「发没发出去、失败在哪一步、为什么失败」。本测试钉住修复后的三条契约：</p>
 * <ol>
 *   <li>{@code POST /api/alert-deliveries/{id}/retry}：FAILED 记录 attempts 归零 +
 *       next_retry_at=now()，下一轮扫描重发；</li>
 *   <li>仅 FAILED 可重投（SENT 400），不存在/跨租户 404，无管理权限 403
 *       （口径与响应体遮罩一致：notify:manage / alert:manage）；</li>
 *   <li>投递记录查询带上事件标题（UI 投递表「事件」列的数据源）。</li>
 * </ol>
 */
class NotifyDeliveryRetryTest {

    private JdbcTemplate biz;
    private NotifyController controller;

    @BeforeEach
    void setUp() {
        biz = mock(JdbcTemplate.class);
        controller = new NotifyController(biz, mock(NotifyService.class),
                mock(SecretCipher.class), mock(com.lab.dataworks.auth.SessionService.class));
    }

    private static MockHttpServletRequest request(String... atoms) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "oncall");
        http.setRemoteAddr("10.99.99.17");
        http.setAttribute(AuthInterceptor.ATOMS_ATTR, Set.of(atoms));
        return http;
    }

    private void stubDeliveryFound(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", status);
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
    }

    /** R-1 证据三件套打印：请求 / 落库 SQL 与参数 / 响应。 */
    private static void evidence(String label, String request, String sqlFragment,
                                 Object[] args, String response, String expect) {
        System.out.println("BUG-TC6-8-EVID[" + label + "] 请求原文: " + request);
        System.out.println("BUG-TC6-8-EVID[" + label + "] 落库 SQL 片段: " + sqlFragment
                + " 参数=" + List.of(args));
        System.out.println("BUG-TC6-8-EVID[" + label + "] 响应原文: " + response);
        System.out.println("BUG-TC6-8-EVID[" + label + "] 期望: " + expect);
    }

    @Test
    void retryFailedDeliveryResetsAttemptsAndSchedulesImmediately() {
        stubDeliveryFound("FAILED");
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> out = controller.retryDelivery(42, request("alert:manage"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(biz).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("attempts = 0"),
                "重投必须把重试计数归零，否则仍被 attempts>=5 排除: " + sql.getValue());
        assertTrue(sql.getValue().contains("next_retry_at = now()"),
                "重投必须立刻排入（下一轮 30 秒扫描即重发）: " + sql.getValue());
        assertEquals(42L, args.getValue()[0], "只改被点的那条投递记录");
        assertEquals(true, out.get("ok"));
        assertEquals(42L, out.get("id"));
        assertTrue(String.valueOf(out.get("message")).contains("重投"),
                "响应要告诉值班员接下来会发生什么: " + out.get("message"));

        evidence("RETRY_FAILED", "POST /api/alert-deliveries/42/retry（oncall，alert:manage）",
                sql.getValue(), args.getValue(), out.toString(),
                "attempts=0 + next_retry_at=now()，响应 ok=true");
    }

    /** notify:manage（数据管家）与 alert:manage（值班主任）同权，与响应体可见口径一致。 */
    @Test
    void notifyManageHolderCanRetryToo() {
        stubDeliveryFound("FAILED");
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> out = controller.retryDelivery(7, request("notify:manage"));

        assertEquals(true, out.get("ok"));
    }

    @Test
    void retryWithoutManageAtomIs403() {
        // 只有 alert:read 的人能看到投递记录（遮罩后），但不能触发重投
        ApiException e = assertThrows(ApiException.class,
                () -> controller.retryDelivery(42, request("alert:read")));

        assertEquals(403, e.getStatus(), "重投是写操作，判定口径 notify:manage / alert:manage");
        verify(biz, never()).update(anyString(), any(Object[].class));
    }

    /** t5 守卫的 C4 探针：id 不存在 → 404，body 必须是业务文案「投递记录不存在」，不能是框架 404。 */
    @Test
    void retryMissingOrForeignTenantDeliveryIs404() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class,
                () -> controller.retryDelivery(1, request("alert:manage")));

        assertEquals(404, e.getStatus());
        assertTrue(e.getMessage().contains("投递记录不存在"), e.getMessage());
        // 租户条件必须留在 SQL 里：跨租户的 id 同样 404（by-id 租户隔离口径）
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(biz).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("tenant_id = ?"),
                "重投前的归属查询必须带租户条件: " + sql.getValue());
    }

    @Test
    void retrySentDeliveryIs400NotSilentlyReposted() {
        stubDeliveryFound("SENT");

        ApiException e = assertThrows(ApiException.class,
                () -> controller.retryDelivery(42, request("alert:manage")));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("只有投递失败"), e.getMessage());
        verify(biz, never()).update(anyString(), any(Object[].class));
    }

    /** 投递记录查询要带事件标题：UI「投递记录」页签的事件列数据源（卡面 ①）。 */
    @Test
    void deliveriesExposeEventTitle() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9);
        row.put("eventTitle", "质量规则未通过：日报表行数");
        row.put("status", "FAILED");
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        Map<String, Object> out = controller.deliveries(null, request("alert:read"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(biz).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("e.title as \"eventTitle\""),
                "查询必须返回事件标题: " + sql.getValue());
        assertTrue(sql.getValue().contains("left join platform.alert_event"),
                "事件被删除后投递记录仍要能列出，必须 left join: " + sql.getValue());
        assertEquals(1, out.get("count"));
        // alert:read 无管理权限 → 失败原因原文遮罩（S4 口径不变）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("deliveries");
        assertEquals(null, rows.get(0).get("response"), "非管理角色不得看到响应体原文");
    }
}
