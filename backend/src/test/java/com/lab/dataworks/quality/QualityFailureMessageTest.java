package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.UserService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisTargetService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D56：执行失败要说人话。
 *
 * <p>原来门禁把数据库原文（含 SQL、连接信息）包成 502 回给调用方：既暴露内部信息，
 * 也不告诉用户「该配哪个数据源」。现在存量错绑在执行前就 400，真跑挂了也是 422 + 可行动说明。</p>
 */
class QualityFailureMessageTest {

    /** PG 对「表不存在」的原始报错，D56 之前会被原样回显。 */
    private static final String RAW_PG_ERROR =
            "StatementCallback; bad SQL grammar [select count(*) from fm_orders_911144.orders]; "
                    + "nested exception is org.postgresql.util.PSQLException: ERROR: relation \"fm_orders_911144.orders\" does not exist";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DorisTargetService targets = mock(DorisTargetService.class);
    private final WarehouseResolver warehouses = mock(WarehouseResolver.class);

    private QualityService service() {
        return new QualityService(jdbc, new ObjectMapper(), new RestTemplateBuilder(),
                "http://mcp", "", mock(SecretCipher.class), targets, warehouses, mock(com.lab.dataworks.project.ProjectEnvironmentService.class));
    }

    private static Map<String, Object> ruleRow(String table, String datasource, Long datasourceId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 85L);
        row.put("code", "bug_a_gate");
        row.put("name", "门禁复现");
        row.put("rule_type", "ROW_COUNT");
        row.put("table_name", table);
        row.put("column_name", null);
        row.put("config", "{\"min\":1}");
        row.put("datasource", datasource);
        row.put("datasource_id", datasourceId);
        row.put("project_id", 1L);
        return row;
    }

    private void givenRule(Map<String, Object> row) {
        when(jdbc.queryForList(contains("platform.quality_rule where id = ?"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private void givenPgSchemaExists(boolean exists) {
        when(jdbc.queryForObject(contains("information_schema.schemata"), eq(Integer.class),
                any(Object[].class))).thenReturn(exists ? 1 : 0);
    }

    private void givenDorisProjectOwner() {
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("id", 77L);
        owner.put("name", "订单项目");
        owner.put("warehouse_datasource_id", 19L);
        owner.put("builtin", "true");
        when(jdbc.queryForList(contains("p.tenant_id = ?"), any(Object[].class))).thenReturn(List.of(owner));
    }

    // ------------------------------------------------- explainFailure：不泄漏原文

    @Test
    void missingRelationBecomesActionableWithoutRawSql() {
        String detail = QualityService.explainFailure(
                new RuntimeException(RAW_PG_ERROR), "平台库(PG)", "fm_orders_911144.orders");

        assertTrue(detail.contains("找不到表"), detail);
        assertTrue(detail.contains("fm_orders_911144.orders"), detail);
        assertFalse(detail.contains("select count(*)"), "不能回显 SQL: " + detail);
        assertFalse(detail.contains("StatementCallback"), "不能回显驱动原文: " + detail);
        assertFalse(detail.contains("PSQLException"), "不能回显驱动类名: " + detail);
    }

    @Test
    void unreachableDatasourcePointsToDatasourcePage() {
        String detail = QualityService.explainFailure(
                new RuntimeException("数据源 订单库 质量查询失败: "
                        + "com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure"),
                "数据源「订单库」(DORIS)", "demo_retail.ods_order");

        assertTrue(detail.contains("连不上"), detail);
        assertTrue(detail.contains("数据源管理"), detail);
        assertFalse(detail.contains("CommunicationsException"), detail);
    }

    @Test
    void permissionFailureNamesTheMissingGrant() {
        String detail = QualityService.explainFailure(
                new RuntimeException("ERROR 1044: Access denied for user 'reader'@'%' to database 'demo_retail'"),
                "数据源「订单库」(DORIS)", "demo_retail.ods_order");

        assertTrue(detail.contains("权限"), detail);
        assertFalse(detail.contains("reader"), "不要把账号名回显出去: " + detail);
    }

    @Test
    void timeoutFailureIsToldApartFromMissingTable() {
        String detail = QualityService.explainFailure(
                new RuntimeException("StatementCallback; SQL [...]; ERROR: canceling statement due to statement timeout"),
                "内置 Doris（跟随项目）", "demo_retail.ods_order");

        assertTrue(detail.contains("超时"), detail);
        assertFalse(detail.contains("找不到表"), detail);
    }

    @Test
    void unknownFailureKeepsOnlyExceptionType() {
        String detail = QualityService.explainFailure(
                new IllegalStateException("host=db.internal user=secret token=abc123"),
                "平台库(PG)", "t");

        assertTrue(detail.contains("IllegalStateException"), detail);
        assertFalse(detail.contains("secret"), "不能把异常消息里的凭据片段带出去: " + detail);
    }

    // ------------------------------------------- 存量错绑规则：执行前 400，不是 502

    @Test
    void legacyPgRuleOnDorisTableFailsBeforeTouchingPg() {
        givenRule(ruleRow("fm_orders_911144.orders", "PG", null));
        givenPgSchemaExists(false);
        givenDorisProjectOwner();

        ApiException error = assertThrows(ApiException.class,
                () -> service().evaluate(85L, LocalDate.of(2026, 10, 26)));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("bug_a_gate"), error.getMessage());
        assertTrue(error.getMessage().contains("Doris"), error.getMessage());
        assertFalse(error.getMessage().contains("PSQLException"), error.getMessage());
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    /** 平台库自己确实有这个模式时，不能误判成错绑（历史 PG 规则要照常能跑）。 */
    @Test
    void platformSchemaRuleStillRunsOnPg() {
        givenRule(ruleRow("platform.workflow", "PG", null));
        givenPgSchemaExists(true);
        when(jdbc.queryForObject(anyString(), eq(Object.class))).thenReturn(3);

        Map<String, Object> result = service().evaluate(85L, LocalDate.of(2026, 10, 26));

        assertEquals("OK", result.get("status"));
        assertEquals(true, result.get("passed"));
    }

    /** 真跑挂了（比如表被删）也要落一条清洗过的明细，而不是把原文写进质量结果。 */
    @Test
    void executionFailureWritesSanitizedDetailOnly() {
        givenRule(ruleRow("platform.demo_metric", "PG", null));
        givenPgSchemaExists(true);
        when(jdbc.queryForObject(anyString(), eq(Object.class)))
                .thenThrow(new RuntimeException(RAW_PG_ERROR));

        Map<String, Object> result = service().evaluate(85L, LocalDate.of(2026, 10, 26));

        assertEquals("ERROR", result.get("status"));
        assertEquals(false, result.get("passed"));
        assertFalse(String.valueOf(result.get("detail")).contains("StatementCallback"),
                String.valueOf(result.get("detail")));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(anyString(), args.capture());
        String stored = String.valueOf(args.getValue()[3]);
        assertFalse(stored.contains("[select count(*)"), "结果明细不能带 SQL 原文: " + stored);
        assertTrue(stored.contains("找不到表"), stored);
    }

    // ------------------------------------------------------------ 门禁状态码 422

    @Test
    void gateReportsUnexecutableRuleAs422Not502() {
        QualityService quality = mock(QualityService.class);
        SessionService sessions = mock(SessionService.class);
        InternalTokenService tokens = mock(InternalTokenService.class);
        when(tokens.configured()).thenReturn(true);
        when(tokens.matches(any())).thenReturn(true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleCode", "bug_a_gate_1793034540");
        result.put("status", "ERROR");
        result.put("passed", false);
        result.put("datasource", "DORIS");
        result.put("datasourceId", null);
        result.put("detail", "在 平台库(PG) 里找不到表 fm_orders_911144.orders；请确认库名/表名，"
                + "或把规则绑到这张表实际所在的数据源");
        when(quality.evaluateByCode(anyString(), any(), any())).thenReturn(result);
        QualityController controller = new QualityController(jdbc, quality, new ObjectMapper(),
                sessions, mock(UserService.class), mock(PermissionResolver.class), tokens,
                mock(com.lab.dataworks.project.ProjectEnvironmentService.class));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.gate(new QualityController.GateRequest("bug_a_gate_1793034540", null),
                        "internal-token", null));

        assertEquals(422, error.getStatus());
        assertTrue(error.getMessage().contains("找不到表"), error.getMessage());
        assertTrue(error.getMessage().contains("fm_orders_911144.orders"), error.getMessage());
        assertFalse(error.getMessage().contains("StatementCallback"), error.getMessage());
    }

    /** 数据不达标仍然是 409，不能被 422 混掉（闸门语义不能改）。 */
    @Test
    void gateStillUses409ForDataFailures() {
        QualityService quality = mock(QualityService.class);
        InternalTokenService tokens = mock(InternalTokenService.class);
        when(tokens.configured()).thenReturn(true);
        when(tokens.matches(any())).thenReturn(true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleCode", "qa_gate");
        result.put("status", "OK");
        result.put("passed", false);
        result.put("datasource", "DORIS");
        result.put("datasourceId", 19L);
        result.put("detail", "row_count=0, min=1, max=null");
        when(quality.evaluateByCode(anyString(), any(), any())).thenReturn(result);
        QualityController controller = new QualityController(jdbc, quality, new ObjectMapper(),
                mock(SessionService.class), mock(UserService.class), mock(PermissionResolver.class), tokens,
                mock(com.lab.dataworks.project.ProjectEnvironmentService.class));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.gate(new QualityController.GateRequest("qa_gate", null),
                        "internal-token", null));

        assertEquals(409, error.getStatus());
        assertTrue(error.getMessage().contains("质量闸门未通过"), error.getMessage());
    }
}
