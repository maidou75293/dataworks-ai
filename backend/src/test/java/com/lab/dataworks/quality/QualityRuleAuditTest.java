package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S12（质量规则落点）：规则建/改/删原来零审计——质量门禁等于没有管控留痕。
 * 现在：
 * <ul>
 *   <li>建：先记 PENDING（带编码/类型/表/列），成功回填 id，重复编码 409 回填 FAILED；</li>
 *   <li>改：detail 带「旧值→新值」（门禁被谁改松了可直接对账）；</li>
 *   <li>删：detail 带删前规则内容。</li>
 * </ul>
 */
class QualityRuleAuditTest {

    private JdbcTemplate biz;
    private JdbcTemplate auditJdbc;
    private QualityService quality;
    private QualityController controller;

    @BeforeEach
    void setUp() {
        biz = mock(JdbcTemplate.class);
        auditJdbc = mock(JdbcTemplate.class);
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(biz.queryForObject(contains("platform.project"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        // D56：数据源归属改由 QualityService 解析（本类只管审计内容），这里固定返回「PG，按项目路由」
        quality = mock(QualityService.class);
        when(quality.bindTarget(anyLong(), anyString(), any(), any()))
                .thenReturn(new QualityService.RuleTarget("PG", null, null, null));
        controller = new QualityController(biz, quality, new ObjectMapper(),
                sessions, mock(UserService.class), mock(PermissionResolver.class),
                mock(InternalTokenService.class),
                mock(com.lab.dataworks.project.ProjectEnvironmentService.class));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "dq_admin");
        http.setRemoteAddr("10.99.99.15");
        return http;
    }

    private static QualityController.RuleRequest rule(String name, boolean enabled) {
        return new QualityController.RuleRequest("r_null_rate", name, "NULL_RATE",
                "t_order", "phone", null, enabled, null, null, null);
    }

    private static Map<String, Object> ruleRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9L);
        row.put("project_id", 5L);
        row.put("code", "r_null_rate");
        row.put("name", "旧名称");
        row.put("rule_type", "NULL_RATE");
        row.put("table_name", "t_order");
        row.put("column_name", "phone");
        row.put("enabled", true);
        row.put("datasource", "PG");
        row.put("datasource_id", null);
        return row;
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
    void createRuleAuditsContentAndId() {
        when(biz.queryForObject(contains("quality_rule"), eq(Long.class), any(Object[].class)))
                .thenReturn(77L);

        controller.create(5, rule("空值率检查", true), request());

        List<Object> pending = pendingArgs();
        assertEquals("dq_admin", pending.get(0));
        assertEquals("QUALITY_RULE_CREATE", pending.get(1));
        assertEquals("r_null_rate", pending.get(2));
        String detail = String.valueOf(pending.get(3));
        assertTrue(detail.contains("type=NULL_RATE") && detail.contains("table=t_order")
                && detail.contains("column=phone"), "S12：建规则内容要进审计: " + detail);
        assertEquals("PENDING", pending.get(4));
        assertEquals("default", pending.get(6), "S12：tenant_id 不能缺");
        assertTrue(String.valueOf(settledArgs().get(1)).contains("id=77"));
    }

    /** S13：重复编码被 409 拒绝也留下 FAILED——「谁试过建什么」要可对账。 */
    @Test
    void createDuplicateCodeAuditsFailed() {
        when(biz.queryForObject(contains("quality_rule"), eq(Long.class), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("uq_quality_rule_code"));

        assertThrows(RuntimeException.class,
                () -> controller.create(5, rule("空值率检查", true), request()));

        pendingArgs(); // 先记必须已落
        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("规则编码已存在"),
                String.valueOf(settled.get(1)));
    }

    /**
     * D56：Doris 表的规则必须把解析结果落到行上（datasource='DORIS' + datasource_id 指向 Doris），
     * 审计里也要留「落到哪」这句话——否则事后对不上「这条门禁当初连的是哪个库」。
     */
    @Test
    void createRulePersistsResolvedDorisDatasource() {
        when(quality.bindTarget(5, "demo_retail.ods_order", null, null))
                .thenReturn(new QualityService.RuleTarget("DORIS", 19L, "demo_retail", "内置 Doris"));
        when(biz.queryForObject(contains("quality_rule"), eq(Long.class), any(Object[].class)))
                .thenReturn(88L);

        controller.create(5, new QualityController.RuleRequest("d56_gate", "门禁", "ROW_COUNT",
                "demo_retail.ods_order", null, Map.of("min", 1), true, null, null, null), request());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(biz).queryForObject(contains("insert into platform.quality_rule"),
                eq(Long.class), args.capture());
        // 列名可以为空，List.of 不接受 null，这里按位置取参数
        List<Object> values = java.util.Arrays.asList(args.getValue());
        assertEquals("DORIS", values.get(8), "datasource 必须写解析结果，不能再默认 PG");
        assertEquals(19L, values.get(9), "datasource_id 必须指向 Doris 数据源");
        assertTrue(String.valueOf(pendingArgs().get(3)).contains("datasource=DORIS#19(内置 Doris)"),
                "审计要能看出这条规则当初落到哪个连接: " + pendingArgs().get(3));
    }

    /** D56：改规则时重新解析的数据源，审计里要带「旧值→新值」（改错数据源要能追责）。 */
    @Test
    void updateRuleAuditsDatasourceChange() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(ruleRow()));
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);
        when(quality.bindTarget(5, "t_order", null, null))
                .thenReturn(new QualityService.RuleTarget("DORIS", 19L, null, "内置 Doris"));

        controller.update(9, rule("新名称", true), request());

        assertTrue(String.valueOf(pendingArgs().get(3)).contains("datasource：PG→DORIS#19(内置 Doris)"),
                String.valueOf(pendingArgs().get(3)));
    }

    @Test
    void updateRuleAuditsOldToNewValues() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(ruleRow()));
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        controller.update(9, rule("新名称", false), request());

        String detail = String.valueOf(pendingArgs().get(3));
        assertEquals("QUALITY_RULE_UPDATE", pendingArgs().get(1));
        assertTrue(detail.contains("name：旧名称→新名称"), "旧值→新值: " + detail);
        assertTrue(detail.contains("enabled：true→false"),
                "门禁开关变化必须能从审计直接看出: " + detail);
        assertEquals("SUCCESS", settledArgs().get(0));
    }

    @Test
    void deleteRuleAuditsDeletedContent() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(ruleRow()));
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        controller.delete(9, request());

        List<Object> pending = pendingArgs();
        assertEquals("QUALITY_RULE_DELETE", pending.get(1));
        assertEquals("r_null_rate", pending.get(2));
        String detail = String.valueOf(pending.get(3));
        assertTrue(detail.contains("name=旧名称") && detail.contains("table=t_order")
                && detail.contains("enabled=true"), "S12：删前要留规则内容: " + detail);
        assertEquals("SUCCESS", settledArgs().get(0));
    }
}
