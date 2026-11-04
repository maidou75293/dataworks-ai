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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D50（2026-10-26）：告警只有确认/关闭，没有静默期也没有升级策略，真实使用会退化成告警疲劳。
 *
 * <p>本用例钉住四件事：</p>
 * <ul>
 *   <li>四类评估 SQL 都必须带静默条件（少一处＝那一类会在维护窗口里继续刷屏）；</li>
 *   <li>静默期内命中的规则要留 {@code ALERT_SILENCED} 审计（否则事后查不到为什么没告警）；</li>
 *   <li>超时未确认的事件生成 ESCALATED 事件，且源事件打 {@code escalated_at}
 *       ——「同一事件只升级一次」靠这个标记 + (rule_id, event_key) 唯一键；</li>
 *   <li>规则的静默/升级入参在入口校验：非法时间、越界分钟数、静默时间在过去都 400。</li>
 * </ul>
 */
class AlertSilenceEscalationTest {

    private JdbcTemplate jdbc;
    private SessionService sessions;
    private AlertService alerts;
    private AlertController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        sessions = mock(SessionService.class);
        alerts = new AlertService(jdbc, sessions);
        controller = new AlertController(jdbc, sessions, alerts);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "ops_lead");
        http.setRemoteAddr("10.99.99.19");
        return http;
    }

    private static AlertController.RuleRequest rule(String silenceUntil, Integer escalateAfter,
                                                    String escalateTo) {
        return new AlertController.RuleRequest(7L, "夜间维护窗口", "WORKFLOW_FAILED", null, null,
                null, "CRITICAL", true, silenceUntil, escalateAfter, escalateTo);
    }

    /** 建规则要过的两道查询：项目归属 + insert returning id。 */
    private void stubProjectAndInsert(long newId) {
        when(jdbc.queryForObject(contains("from platform.project"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("insert into platform.alert_rule"), eq(Long.class),
                any(Object[].class))).thenReturn(newId);
    }

    // ---------------------------------------------------------- 静默：评估侧

    /** 四类判定都必须带静默条件，漏一类就等于那类规则没有维护窗口。 */
    @Test
    void everyEvaluationHonoursSilenceWindow() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        alerts.evaluate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(5)).update(sql.capture());
        // 第 5 条是升级扫描，这里只看四类「生成事件」的 INSERT
        List<String> statements = sql.getAllValues().stream()
                .filter(one -> one.contains("insert into platform.alert_event")
                        && !one.contains(":escalated"))
                .toList();
        assertEquals(4, statements.size(), "四类评估（运行失败/质量/SLA/采集）");
        for (String one : statements) {
            String head = one.substring(0, Math.min(60, one.length())).replaceAll("\\s+", " ");
            assertTrue(one.contains("r.silence_until is null or r.silence_until <= now()"),
                    "评估 SQL 缺静默条件: " + head);
        }
    }

    /** 静默期内命中的规则要留审计，且审计里能看出压掉了多少次、静默到什么时候。 */
    @Test
    void silencedHitsAreAudited() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
                "ruleId", 7L, "ruleName", "夜间维护窗口", "ruleType", "WORKFLOW_FAILED",
                "hits", 3L, "silenceUntil", "2026-10-27 08:00:00+08")));
        when(jdbc.update(anyString())).thenReturn(0);

        Map<String, Object> out = alerts.evaluate();

        assertEquals(1, out.get("silencedRules"));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq(AlertService.SYSTEM_ACTOR), eq("ALERT_SILENCED"),
                eq("rule 7"), detail.capture(), eq("SUCCESS"), any());
        assertTrue(detail.getValue().contains("命中 3 次"), detail.getValue());
        assertTrue(detail.getValue().contains("未生成事件"), detail.getValue());
        assertTrue(detail.getValue().contains("静默到 2026-10-27"), detail.getValue());
    }

    // ---------------------------------------------------------- 升级

    /** 升级一条 SQL 做完三件事：挑到期源事件 → 插 ESCALATED 事件 → 给源事件打标记。 */
    @Test
    void escalationMarksSourceOnceAndCreatesEscalatedEvent() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.update(anyString())).thenReturn(2);

        assertEquals(2, alerts.escalateOverdue(), "返回被升级的源事件数");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture());
        String escalate = sql.getValue();
        assertTrue(escalate.contains("e.escalated_at is null"), "只挑没升级过的源事件");
        assertTrue(escalate.contains("e.acked_at is null"), "已确认的不该升级");
        assertTrue(escalate.contains("escalate_after_minutes"), "阈值取规则上的分钟数");
        assertTrue(escalate.contains("d.event_key || ':escalated'"),
                "升级事件用后缀 key，天然被 (rule_id, event_key) 唯一键去重");
        assertTrue(escalate.contains("on conflict (rule_id, event_key) do nothing"),
                "并发/重跑都不能重复升级");
        assertTrue(escalate.contains("set escalated_at = now()"), "源事件必须打标记＝只升一次");
        // 升级事件自身也置位，否则下一轮会把它再升一遍
        assertTrue(escalate.contains("d.quality_result_id, now(), d.escalate_to"),
                "新事件插入时 escalated_at 就要置位");
    }

    @Test
    void escalationSweepIsAlsoCallableDirectly() {
        when(jdbc.update(anyString())).thenReturn(1);
        assertEquals(1, alerts.escalateOverdue());
    }

    // ---------------------------------------------------------- 入参校验

    @Test
    void silenceUntilAcceptsIsoLocalAndDateOnly() {
        assertNotNull(AlertController.parseSilenceUntil("2026-10-27T10:00:00+08:00"));
        assertNotNull(AlertController.parseSilenceUntil("2026-10-27 10:00:00"));
        assertNotNull(AlertController.parseSilenceUntil("2026-10-27"));
        assertNull(AlertController.parseSilenceUntil("  "));
        assertNull(AlertController.parseSilenceUntil(null));
    }

    @Test
    void silenceUntilRejectsGarbageWith400() {
        ApiException e = assertThrows(ApiException.class,
                () -> AlertController.parseSilenceUntil("明天上午"));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("静默时间格式不合法"), e.getMessage());
        assertTrue(e.getMessage().contains("示例"), "报错要给能照抄的格式: " + e.getMessage());
    }

    @Test
    void createRulePersistsSilenceAndEscalationPolicy() {
        stubProjectAndInsert(11L);

        controller.createRule(rule("2026-12-01T08:00:00+08:00", 15, "ops_lead"), request());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("insert into platform.alert_rule"),
                eq(Long.class), args.capture());
        List<Object> values = java.util.Arrays.asList(args.getValue());
        assertInstanceOf(java.sql.Timestamp.class, values.get(10), "silence_until 要按时间戳入库");
        assertEquals(15, values.get(11), "escalate_after_minutes");
        assertEquals("ops_lead", values.get(12), "escalate_to");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("ops_lead"), eq("ALERT_RULE_CREATE"), eq("rule 11"),
                detail.capture(), eq("SUCCESS"), any());
        assertTrue(detail.getValue().contains("静默到"), detail.getValue());
        assertTrue(detail.getValue().contains("15 分钟→ops_lead"), detail.getValue());
    }

    @Test
    void createRuleRejectsOutOfRangeEscalation() {
        stubProjectAndInsert(11L);
        ApiException low = assertThrows(ApiException.class,
                () -> controller.createRule(rule(null, -1, null), request()));
        assertEquals(400, low.getStatus());
        assertTrue(low.getMessage().contains("升级等待分钟数"), low.getMessage());

        ApiException high = assertThrows(ApiException.class,
                () -> controller.createRule(rule(null, 10081, null), request()));
        assertEquals(400, high.getStatus());
    }

    @Test
    void createRuleRejectsSilenceTimeInThePast() {
        stubProjectAndInsert(11L);
        ApiException e = assertThrows(ApiException.class,
                () -> controller.createRule(rule("2020-01-01T10:00:00+08:00", 30, null), request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("晚于当前时间"), e.getMessage());
    }

    @Test
    void escalateAfterMinutesDefaultsToThirtyWhenOmitted() {
        stubProjectAndInsert(11L);

        controller.createRule(rule(null, null, null), request());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("insert into platform.alert_rule"),
                eq(Long.class), args.capture());
        assertEquals(AlertController.DEFAULT_ESCALATE_AFTER_MINUTES, args.getValue()[11]);
        assertNull(args.getValue()[10], "不传静默时间＝不静默");
    }

    private static void assertInstanceOf(Class<?> type, Object value, String message) {
        assertTrue(type.isInstance(value), message + "（实际 " + value + "）");
    }
}
