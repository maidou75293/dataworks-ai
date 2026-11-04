package com.lab.dataworks.alert;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * BUG-TC6-10（2026-10-27）：告警处置的闸门与状态机。
 *
 * <p>原状三件事：① 关闭不接收任何 body，事后查不到「为什么关」；② CLOSED 没有退路，
 * 误点一次这条告警在界面上永久消失；③ 对 OPEN 事件可以直接 close，「确认」不承重。
 *
 * <p>本用例钉住修复后的状态机：<b>OPEN →（确认）→ ACKED →（关闭 + ≥5 字处置说明）→ CLOSED
 * →（重开）→ ACKED</b>。每一步越级都必须 400，且错误文案要告诉调用方下一步该点哪个按钮。
 * 理由写进 {@code close_reason}（V117）并进 ALERT_CLOSE 审计；重开清空当前行的关闭痕迹，
 * 上一次的理由留在 ALERT_REOPEN 审计里。</p>
 */
class AlertGateStateMachineTest {

    private JdbcTemplate jdbc;
    private SessionService sessions;
    private AlertController controller;

    private static final long EVENT_ID = 42L;
    private static final String REASON = "机器狗误报，确认是偶发";

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        sessions = mock(SessionService.class);
        controller = new AlertController(jdbc, sessions, mock(AlertService.class));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "ops_lead");
        http.setRemoteAddr("10.99.99.19");
        return http;
    }

    /** 归属校验（requireEvent）通过。 */
    private void eventExists() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    }

    /** 更新影响行数（close / reopen 的状态机谓词）。 */
    private void updateAffects(int rows) {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(rows);
    }

    /** 状态不符时控制器会回查当前状态决定文案。 */
    private void currentStatus(String status) {
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(status);
    }

    // ------------------------------------------------------------ ① 处置说明必填

    @Test
    void closeWithoutBodyIs400BeforeAnyUpdate() {
        eventExists();
        ApiException e = assertThrows(ApiException.class,
                () -> controller.close(EVENT_ID, null, request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("至少 5 个字"), e.getMessage());
        // 没填理由就不该碰事件表（不能先改了状态再报错）
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void blankReasonIs400EvenIfLongEnough() {
        eventExists();
        ApiException e = assertThrows(ApiException.class,
                () -> controller.close(EVENT_ID, new AlertController.CloseRequest("        "), request()));
        assertEquals(400, e.getStatus());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reasonShorterThanFiveCharsIs400() {
        eventExists();
        // 4 个字（中文按字符算）：边界上必须拒绝，否则「ok」这种也能当处置说明
        ApiException e = assertThrows(ApiException.class,
                () -> controller.close(EVENT_ID, new AlertController.CloseRequest("误关闭了"), request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("至少 5 个字"), e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reasonExactlyFiveCharsIsAccepted() {
        eventExists();
        updateAffects(1);
        Map<String, Object> out = controller.close(EVENT_ID,
                new AlertController.CloseRequest("误报五个字"), request());
        assertEquals("CLOSED", out.get("status"));
    }

    @Test
    void overlongReasonIs400() {
        eventExists();
        ApiException e = assertThrows(ApiException.class,
                () -> controller.close(EVENT_ID,
                        new AlertController.CloseRequest("x".repeat(AlertController.MAX_CLOSE_REASON + 1)),
                        request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("不能超过"), e.getMessage());
    }

    // ------------------------------------------------------------ ② 状态机：先确认再关闭

    @Test
    void closeOpenEventIs400AndTellsYouToAckFirst() {
        eventExists();
        updateAffects(0);
        currentStatus("OPEN");
        ApiException e = assertThrows(ApiException.class,
                () -> controller.close(EVENT_ID, new AlertController.CloseRequest(REASON), request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("先确认"), e.getMessage());
    }

    @Test
    void closeAlreadyClosedEventIs400AndPointsToReopen() {
        eventExists();
        updateAffects(0);
        currentStatus("CLOSED");
        ApiException e = assertThrows(ApiException.class,
                () -> controller.close(EVENT_ID, new AlertController.CloseRequest(REASON), request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("重开"), e.getMessage());
    }

    @Test
    void closeOnlyTargetsAckedRowsAndPersistsTrimmedReason() {
        eventExists();
        updateAffects(1);
        controller.close(EVENT_ID, new AlertController.CloseRequest("  " + REASON + "  "), request());

        ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("status = 'ACKED'"), arg.capture(), arg.capture(),
                arg.capture(), arg.capture(), arg.capture());
        List<Object> args = arg.getAllValues();
        // 谓词必须是 status = 'ACKED'（不是 <> 'CLOSED'）：闸门在 SQL 里也成立，不只靠前端
        assertEquals("ops_lead", args.get(0));
        assertEquals(REASON, args.get(1), "处置说明要 trim 后落库");
        assertEquals(EVENT_ID, args.get(2));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("ops_lead"), eq("ALERT_CLOSE"), eq("alert " + EVENT_ID),
                detail.capture(), eq("SUCCESS"), any());
        assertTrue(detail.getValue().contains(REASON), "审计里要能看到处置说明");
    }

    @Test
    void closeSqlWritesReasonColumn() {
        eventExists();
        updateAffects(1);
        controller.close(EVENT_ID, new AlertController.CloseRequest(REASON), request());
        // 理由必须真的落列（V117 close_reason），不能只进审计——列表要能直接读出来
        verify(jdbc).update(contains("close_reason = ?"), any(Object[].class));
    }

    // ------------------------------------------------------------ ② 重开：CLOSED 不再是终态

    @Test
    void reopenNonClosedEventIs400() {
        eventExists();
        currentStatus("ACKED");
        ApiException e = assertThrows(ApiException.class,
                () -> controller.reopen(EVENT_ID, request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("只有已关闭"), e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reopenClosedEventGoesBackToAckedAndClearsCloseTrail() {
        eventExists();
        currentStatus("CLOSED");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("close_reason", REASON)));
        updateAffects(1);

        Map<String, Object> out = controller.reopen(EVENT_ID, request());
        assertEquals("ACKED", out.get("status"));

        ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("status = 'ACKED'"), arg.capture(), arg.capture(),
                arg.capture(), arg.capture());
        List<Object> args = arg.getAllValues();
        assertEquals("ops_lead", args.get(0), "重开人记进 acked_by（coalesce 不覆盖原确认人）");
        assertEquals(EVENT_ID, args.get(1));

        verify(jdbc).update(contains("close_reason = null"), any(Object[].class));

        // 上一次的处置说明不能丢：审计里要留原话
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("ops_lead"), eq("ALERT_REOPEN"), eq("alert " + EVENT_ID),
                detail.capture(), eq("SUCCESS"), any());
        assertTrue(detail.getValue().contains(REASON), detail.getValue());
    }

    @Test
    void reopenRaceIs400() {
        eventExists();
        currentStatus("CLOSED");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("close_reason", REASON)));
        updateAffects(0); // 查到 CLOSED，但更新前被别人改走了
        ApiException e = assertThrows(ApiException.class,
                () -> controller.reopen(EVENT_ID, request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("刷新"), e.getMessage());
    }

    @Test
    void listExposesCloseReason() {
        // 列表 SELECT 必须带 close_reason as "closeReason"，否则前端拿不到处置说明
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        controller.events(null, null, null, null, null, null, null, null, null, 200,
                mock(jakarta.servlet.http.HttpServletResponse.class));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("close_reason as \"closeReason\""), sql.getValue());
        assertFalse(sql.getValue().contains("close_reason as \"close_reason\""));
    }
}
