package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.UserService;
import com.lab.dataworks.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-DW-7 ②：{@code CUSTOM_SQL} 规则创建时就必须有 sql/sql_b64。
 *
 * <p>原行为：不带 SQL 也 200 静默入库（live 回归 dw7_1793098369 实测），
 * 要等试跑才报「CUSTOM_SQL 规则缺少 sql/sql_b64」——违反 acceptance-rules.md
 * 第 4 条「不许静默」，且直接毁掉本卡判据「建完即可试跑成功」。</p>
 *
 * <p>顺序口径（同 {@code QualityRuleCodeImmutableTest}）：validate 在任何查库之前，
 * 缺 SQL 的 400 不允许碰数据库；带 SQL 的请求穿透 validate、停在
 * requireProject（mock jdbc 未打桩 → 404），以此证明新校验不误伤合法请求。</p>
 */
class QualityCustomSqlRequiredTest {

    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        QualityService quality = mock(QualityService.class);
        when(quality.bindTarget(anyLong(), anyString(), any(), any()))
                .thenReturn(new QualityService.RuleTarget("DORIS", null, "demo", null));
        QualityController controller = new QualityController(jdbc,
                quality, new ObjectMapper(), mock(SessionService.class),
                mock(UserService.class), mock(PermissionResolver.class),
                mock(InternalTokenService.class),
                mock(com.lab.dataworks.project.ProjectEnvironmentService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body(String configJson) {
        return """
                {"code":"dw7_custom_sql_1","name":"自定义SQL校验","ruleType":"CUSTOM_SQL",
                 "tableName":"demo.t","config":%s,"enabled":true}
                """.formatted(configJson);
    }

    private void postRejected(String configJson) throws Exception {
        mvc.perform(post("/api/projects/1/quality/rules")
                        .contentType("application/json")
                        .content(body(configJson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("config.sql")));
    }

    @Test
    void missingSqlIs400NotSilentlyCreated() throws Exception {
        postRejected("{}");
        // 被拒的 POST 一条库都不许碰（validate 在 requireProject/insert 之前）
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void blankSqlIs400() throws Exception {
        postRejected("{\"sql\":\"   \"}");
    }

    @Test
    void emptyConfigWithoutSqlIs400() throws Exception {
        postRejected("null");
    }

    @Test
    void plainSqlPassesValidate() throws Exception {
        // 合法 SQL 穿透 validate，停在 requireProject（mock 未打桩 → 404），不是 400
        mvc.perform(post("/api/projects/1/quality/rules")
                        .contentType("application/json")
                        .content(body("{\"sql\":\"select count(*) from demo.t\",\"expect\":0}")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sqlB64AlsoPassesValidate() throws Exception {
        mvc.perform(post("/api/projects/1/quality/rules")
                        .contentType("application/json")
                        .content(body("{\"sql_b64\":\"c2VsZWN0IDE=\"}")))
                .andExpect(status().isNotFound());
    }
}
