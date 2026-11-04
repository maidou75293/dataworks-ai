package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.UserService;
import com.lab.dataworks.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-TC1-1：{@code PUT /api/quality/rules/{id}} 把 {@code code} 改成另一个合法且未占用的编码时，
 * 接口曾经返回 {@code 200 {"ok":true}} 但 UPDATE 的 set 列表不含 code——改动被静默丢弃
 * （违反 acceptance-rules.md 第 4 条「不许静默」）。
 *
 * 引用规则的外部标识，改成<b>禁止修改</b>——请求里的 code 与库中不一致直接 400，
 * 一致则照常更新其余字段。</p>
 */
class QualityRuleCodeImmutableTest {

    private JdbcTemplate jdbc;
    private QualityService quality;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        quality = mock(QualityService.class);
        // D56：update 会重新解析落点并写审计，桩一个 DORIS 按项目数仓路由的目标
        when(quality.bindTarget(anyLong(), anyString(), any(), anyString()))
                .thenReturn(new QualityService.RuleTarget("DORIS", null, "demo", null));
        // S12/S13 起 update 外层包了 sessions.auditAround：裸 mock 返回 null 会让响应体为空
        // （$.ok 找不到，TC7-G1 / REQ-DW-7 两泳道复跑各自暴露）。桩成「直接执行 supplier」
        // 与线上行为一致，本测试只关心 code 不可改语义。
        SessionService sessions = mock(SessionService.class);
        when(sessions.auditAround(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(5)).get());
        QualityController controller = new QualityController(jdbc,
                quality, new ObjectMapper(), sessions,
                mock(UserService.class), mock(PermissionResolver.class),
                mock(InternalTokenService.class),
                mock(com.lab.dataworks.project.ProjectEnvironmentService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** owned(id) 的库中现状：编码 bug_a_qc_1。 */
    private void stubRule(String code) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 17L);
        row.put("project_id", 1L);
        row.put("code", code);
        when(jdbc.queryForList(startsWith("select q.*"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    private static String body(String code) {
        return """
                {"code":"%s","name":"行数校验","ruleType":"ROW_COUNT",
                 "tableName":"demo.t","config":{},"enabled":true,"datasource":"DORIS"}
                """.formatted(code);
    }

    @Test
    void changingCodeIsRejectedWith400NotSilentlyDropped() throws Exception {
        stubRule("bug_a_qc_1");

        mvc.perform(put("/api/quality/rules/17")
                        .contentType("application/json")
                        .content(body("bug_a_qcx_1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("规则编码不可修改")));

        // 关键反向断言：被拒的 PUT 一条 UPDATE 都不许发（曾经的缺陷就是 200+不发 code 的 UPDATE）
        verify(jdbc, never()).update(startsWith("update platform.quality_rule"), any(Object[].class));
    }

    @Test
    void sameCodeStillUpdatesOtherFields() throws Exception {
        stubRule("bug_a_qc_1");

        mvc.perform(put("/api/quality/rules/17")
                        .contentType("application/json")
                        .content(body("bug_a_qc_1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        String update = sql.getValue();
        assertTrue(update.contains("set name = ?"), update);
        // 口径备案：UPDATE 的 set 列表仍然不含 code（禁改），这里钉住现状防止反向漂移
        assertTrue(!update.contains("set code"), "编码不可修改：set 列表不应出现 code: " + update);
    }

    @Test
    void missingRuleIs404BeforeCodeCheck() throws Exception {
        when(jdbc.queryForList(startsWith("select q.*"), any(Object[].class)))
                .thenReturn(List.of());

        mvc.perform(put("/api/quality/rules/99")
                        .contentType("application/json")
                        .content(body("whatever_1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidCodeFormatStillRejectedByValidate() throws Exception {
        // 400 顺序口径：编码格式非法在查库前就该拒绝（validate 在 owned 之前）
        mvc.perform(put("/api/quality/rules/17")
                        .contentType("application/json")
                        .content(body("1-bad")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("规则编码不合法")));
        verify(jdbc, never()).queryForList(contains("select q.*"), any(Object[].class));
    }
}
