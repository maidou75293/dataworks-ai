package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.auth.UserService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisTargetService;
import com.lab.dataworks.project.ProjectEnvironmentService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-TC4-4：质量规则的运行落点要可见、可管。
 *
 * <p>立案事实（QA-01 / QA-02）：{@code tableName} 写成 {@code <x>_prd.<表>} 时创建 200、
 * 运行 200 {@code status=OK}——规则真的在生产库上跑，而响应里既没有 env 也没有 database，
 * 谁都无法核对这条门禁到底跑在哪个环境。</p>
 */
class QualityEnvLandingTest {

    private static final String PROD_RULE_SQL = "select id, code, rule_type, table_name, column_name, "
            + "config_json::text as config, datasource, datasource_id, project_id "
            + "from platform.quality_rule where id = ?";

    private JdbcTemplate jdbc;
    private ProjectEnvironmentService environments;
    private QualityService service;

    @BeforeEach
    void setUp() {
        TenantScope.set(TenantScope.DEFAULT);
        jdbc = mock(JdbcTemplate.class);
        environments = new ProjectEnvironmentService(jdbc,
                mock(com.lab.dataworks.project.DorisAdminService.class),
                mock(WarehouseResolver.class));
        service = new QualityService(jdbc, new ObjectMapper(), new RestTemplateBuilder(),
                "http://mcp", "", mock(SecretCipher.class), mock(DorisTargetService.class),
                mock(WarehouseResolver.class), environments);
        // 项目 1：dev=bug_a_t4_env_dev / prod=bug_a_t4_env_prd
        when(jdbc.queryForList(contains("from platform.project"), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("lower(coalesce(prod_database")) {
                        return List.of();
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", 1L);
                    row.put("code", "bugproj");
                    row.put("name", "缺陷项目");
                    row.put("dev_database", "bug_a_t4_env_dev");
                    row.put("prod_database", "bug_a_t4_env_prd");
                    return List.of(row);
                });
    }

    private void givenRule(String tableName, String datasource) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 149L);
        row.put("code", "bug_a_t4q_prd");
        row.put("name", "tc4 prod 落点");
        row.put("rule_type", "ROW_COUNT");
        row.put("table_name", tableName);
        row.put("column_name", null);
        row.put("config", "{\"min\":1}");
        row.put("datasource", datasource);
        row.put("datasource_id", null);
        row.put("project_id", 1L);
        when(jdbc.queryForList(contains("from platform.quality_rule where id = ?"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(jdbc.queryForList(contains("from platform.quality_rule where code = ?"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    /** 运行结果必须能看出落在哪个环境（响应 + 结果表都带 env/database）。 */
    @Test
    void runResultEchoesLandingEnvironment() {
        givenRule("bug_a_t4_env_dev.t1", "DORIS");
        when(jdbc.queryForObject(contains("select count(*) from bug_a_t4_env_dev"), eq(Object.class)))
                .thenReturn(5);

        Map<String, Object> out = service.evaluate(149L, LocalDate.of(2026, 10, 27));

        assertEquals("DEV", out.get("env"));
        assertEquals("bug_a_t4_env_dev", out.get("database"));
        assertEquals(false, out.get("releaseLine"));
        // 执行结果本身在这台机器上跑不通（没有真实 Doris），这里只锁「落点可核对」
        assertTrue(String.valueOf(out.get("detail"))
                .indexOf("select count") < 0, String.valueOf(out.get("detail")));
    }

    /** 生产库规则不能被页面上的「执行」触发（发布线除外）。 */
    @Test
    void interactiveRunOnProductionDatabaseIsRejected() {
        givenRule("bug_a_t4_env_prd.t1", "DORIS");

        ApiException error = assertThrows(ApiException.class,
                () -> service.evaluate(149L, LocalDate.of(2026, 10, 27)));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("bug_a_t4_env_prd"), error.getMessage());
        assertTrue(error.getMessage().contains("质量规则运行"), error.getMessage());
    }

    /** 发布线（内部凭证触发的 /api/quality/gate，tenant==null）仍可跑生产库。 */
    @Test
    void releaseLineMayStillRunOnProductionDatabase() {
        givenRule("bug_a_t4_env_prd.t1", "DORIS");
        when(jdbc.queryForObject(contains("select count(*) from bug_a_t4_env_prd"), eq(Object.class)))
                .thenReturn(3);

        Map<String, Object> out = service.evaluateByCode("bug_a_t4q_prd",
                LocalDate.of(2026, 10, 27), null);

        assertEquals(true, out.get("releaseLine"));
        assertEquals("PROD", out.get("env"));
        assertEquals("bug_a_t4_env_prd", out.get("database"));
        // 关键：生产库规则在发布线不被 400 挡（能跑到执行阶段才算放行）
        assertTrue(out.containsKey("status"));
    }

    /** 建规则时命中生产库要显式确认（BUG-TC4-4 第 3 条：别等运行期才炸）。 */
    @Test
    void creatingProdRuleRequiresExplicitAcknowledgement() {
        when(jdbc.queryForObject(contains("from platform.project where id = ?"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        QualityService quality = mock(QualityService.class);
        when(quality.bindTarget(eq(1L), anyString(), any(), any()))
                .thenReturn(new QualityService.RuleTarget("DORIS", null, "bug_a_t4_env_prd", null));
        QualityController controller = new QualityController(jdbc, quality,
                new ObjectMapper(), mock(SessionService.class), mock(UserService.class),
                mock(PermissionResolver.class), mock(InternalTokenService.class), environments);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "u_bug_t4");

        ApiException error = assertThrows(ApiException.class, () -> controller.create(1,
                new QualityController.RuleRequest("q_prod", "生产门禁", "ROW_COUNT",
                        "bug_a_t4_env_prd.t1", null, Map.of("min", 1), true, "DORIS", null, null),
                http));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("allowProd"), error.getMessage());
        assertTrue(error.getMessage().contains("发布线"), error.getMessage());
    }

    /** 同一张生产表带 allowProd=true 就能建（发布线门禁是合法需求）。 */
    @Test
    void acknowledgedProdRuleIsAccepted() {
        JdbcTemplate biz = mock(JdbcTemplate.class);
        when(biz.queryForObject(contains("from platform.project where id = ?"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        when(biz.queryForObject(contains("insert into platform.quality_rule"), eq(Long.class),
                any(Object[].class))).thenReturn(150L);
        when(jdbc.queryForObject(contains("from platform.project where id = ?"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        QualityService quality = mock(QualityService.class);
        when(quality.bindTarget(eq(1L), anyString(), any(), any()))
                .thenReturn(new QualityService.RuleTarget("DORIS", null, "bug_a_t4_env_prd", null));
        SessionService sessions = mock(SessionService.class);
        when(sessions.auditAround(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(5)).get());
        QualityController controller = new QualityController(biz, quality, new ObjectMapper(),
                sessions, mock(UserService.class), mock(PermissionResolver.class),
                mock(InternalTokenService.class), environments);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "u_bug_t4");

        Map<String, Object> out = controller.create(1, new QualityController.RuleRequest(
                "q_prod", "生产门禁", "ROW_COUNT", "bug_a_t4_env_prd.t1", null,
                Map.of("min", 1), true, "DORIS", null, true), http);

        assertEquals(150L, out.get("id"));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(biz).queryForObject(contains("insert into platform.quality_rule"),
                eq(Long.class), args.capture());
        assertEquals("DORIS", java.util.Arrays.asList(args.getValue()).get(8));
    }
}
