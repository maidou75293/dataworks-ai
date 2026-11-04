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
 * BUG-TC6-13（2026-10-27）：必填项口径 + 告警评估范围参数。
 *
 * <p>钉两件事：</p>
 * <ul>
 *   <li>① severity 前端标必填，后端就必须拒绝缺省——原来空值被兜底成 CRITICAL，
 *       等于「前端一套口径、API 另一套口径」，也违反「不许 setDefault 掩盖必填」；</li>
 *   <li>④ {@code POST /api/alerts/evaluate} 接受可选 ruleId/projectId：不存在的 ruleId 要 404，
 *       不能静默降级成全局扫描；两个范围参数同传要 400；全局路径（不传）与定时任务口径不变。</li>
 * </ul>
 */
class AlertRequiredScopeTest {

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
        http.setAttribute("username", "dev_lead");
        http.setRemoteAddr("10.99.99.19");
        return http;
    }

    private static AlertController.RuleRequest rule(String severity) {
        return new AlertController.RuleRequest(7L, "夜间失败告警", "WORKFLOW_FAILED",
                null, null, null, severity, true, null, null, null);
    }

    // ---------------------------------------------------------- ① severity 必填

    /** 缺 severity 必须 400，且一条 SQL 都不许发出去（静默入库＝继续掩盖口径）。 */
    @Test
    void missingSeverityIsRejectedBeforeAnyWrite() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.createRule(rule(null), request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("告警级别"), e.getMessage());
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void blankSeverityIsRejectedToo() {
        assertThrows(ApiException.class, () -> controller.createRule(rule("   "), request()));
    }

    /** 非法枚举仍然点名（修复不能把「只能是 WARNING / CRITICAL」的旧口径一起弄丢）。 */
    @Test
    void unknownSeverityStillNamesOptions() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.createRule(rule("FATAL"), request()));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("WARNING / CRITICAL"), e.getMessage());
    }

    /** 合法小写值要归一成大写入库——收严必填不等于改枚举语义。 */
    @Test
    void lowercaseSeverityIsNormalisedAndPersisted() {
        when(jdbc.queryForObject(contains("from platform.project"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("insert into platform.alert_rule"), eq(Long.class),
                any(Object[].class))).thenReturn(12L);

        controller.createRule(rule("warning"), request());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("insert into platform.alert_rule"),
                eq(Long.class), args.capture());
        assertTrue(java.util.Arrays.asList(args.getValue()).contains("WARNING"),
                "severity 要大写归一后入库: " + java.util.Arrays.toString(args.getValue()));
    }

    // ---------------------------------------------------------- ④ evaluate 范围

    /** 不存在的 ruleId 必须 404；「点了检查、实际扫了全局」是最坏的静默降级。 */
    @Test
    void unknownRuleIdEvaluateGets404() {
        when(jdbc.queryForObject(contains("from platform.alert_rule"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);

        ApiException e = assertThrows(ApiException.class,
                () -> controller.evaluate(new AlertController.EvaluateRequest(999_999_999L, null)));
        assertEquals(404, e.getStatus());
        assertTrue(e.getMessage().contains("告警规则不存在"), e.getMessage());
    }

    /** ruleId 与 projectId 同传是「你想圈哪个」没说清，400 点名，不猜。 */
    @Test
    void twoScopeParamsAreAmbiguousAndRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.evaluate(new AlertController.EvaluateRequest(7L, 3L)));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("二选一"), e.getMessage());
    }

    /** 单规则评估：四类扫描 + 留痕 + 升级都要带上 r.id 条件，只圈这条规则。 */
    @Test
    void scopedEvaluateFiltersEverySweepByRule() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        Map<String, Object> out = alerts.evaluate(7L, null);

        assertEquals("RULE#7", out.get("scope"), "回执要能让调用方确认范围真的生效");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(5))
                .update(sql.capture(), args.capture());
        for (int i = 0; i < 5; i++) {
            assertTrue(sql.getAllValues().get(i).contains("and r.id = ?"),
                    "第 " + (i + 1) + " 条扫描缺范围条件: " + sql.getAllValues().get(i));
            assertEquals(List.of(7L), List.of(args.getAllValues().get(i)));
        }
        ArgumentCaptor<String> silenced = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(silenced.capture(), any(Object[].class));
        assertTrue(silenced.getValue().contains("and r.id = ?"), "静默留痕也要圈范围");
        assertTrue(silenced.getValue().indexOf("and r.id = ?")
                        < silenced.getValue().indexOf("group by"),
                "范围条件必须落在 where 尾、group by 之前");
    }

    /** 全局口径不许被顺手动到：不带范围时仍是老的单参形态、SQL 里没有范围条件。 */
    @Test
    void globalEvaluateKeepsLegacyCallShape() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.update(anyString())).thenReturn(0);

        Map<String, Object> out = alerts.evaluate();

        assertEquals("ALL", out.get("scope"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(5)).update(sql.capture());
        for (String one : sql.getAllValues()) {
            assertFalse(one.contains("and r.id = ?"), "全局评估不该带规则圈定: " + one);
            assertFalse(one.contains("and r.project_id = ?"), one);
        }
        verify(jdbc).queryForList(anyString());
    }

    /** projectId 圈范围走 r.project_id；与 ruleId 同一追加位置。 */
    @Test
    void projectScopedEvaluateFiltersByProject() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        Map<String, Object> out = alerts.evaluate(null, 3L);

        assertEquals("PROJECT#3", out.get("scope"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(5)).update(sql.capture(), any(Object[].class));
        for (String one : sql.getAllValues()) {
            assertTrue(one.contains("and r.project_id = ?"), one);
        }
    }
}
