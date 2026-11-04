package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * TC7-G1：波动率/正则/枚举/离散值四类新规则的执行口径
 * （验收契约 docs/测试-TC7-G1.md §五；口径说明
 * docs/质量规则新类型-波动率正则枚举离散值-20261027.md）。
 *
 * <p>与 {@code QualityServiceTest} 同一套 mock JdbcTemplate 打法：
 * 规则加载按 SQL 含 quality_rule 匹配、波动率基线按 quality_result 匹配，
 * PG 取数按 queryForObject(String, Object.class) 匹配。</p>
 */
class QualityNewRuleTypesTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private QualityService service() {
        return new QualityService(jdbc, mapper, new RestTemplateBuilder(),
                "http://mcp", "", mock(SecretCipher.class), mock(DorisTargetService.class),
                mock(WarehouseResolver.class),
                mock(com.lab.dataworks.project.ProjectEnvironmentService.class));
    }

    private static Map<String, Object> rule(String type, String column, String config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", 1L);
        map.put("code", "q_" + type.toLowerCase());
        map.put("name", "rule");
        map.put("rule_type", type);
        map.put("table_name", "t_demo");
        map.put("column_name", column);
        map.put("config", config);
        map.put("datasource", "PG");
        map.put("datasource_id", null);
        map.put("project_id", null);
        return map;
    }

    private void givenRule(Map<String, Object> row) {
        when(jdbc.queryForList(contains("quality_rule"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private void givenNoBaseline() {
        when(jdbc.queryForList(contains("quality_result"), any(Object[].class)))
                .thenReturn(List.of());
    }

    private void givenBaselineDetail(String detail) {
        when(jdbc.queryForList(contains("quality_result"), any(Object[].class)))
                .thenReturn(List.of(Map.of("detail", (Object) detail)));
    }

    private void givenScalar(Object value) {
        when(jdbc.queryForObject(anyString(), eq(Object.class))).thenReturn(value);
    }

    private String capturedScalarSql() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForObject(captor.capture(), eq(Object.class));
        return captor.getValue();
    }

    // ------------------------------------------------------------- VOLATILITY

    @Test
    void volatilityFirstRunWithoutBaselinePassesAndRecordsRowCount() {
        givenRule(rule("VOLATILITY", null, "{\"windows\":[1,7,30],\"maxChangeRate\":0.3}"));
        givenNoBaseline();
        givenScalar(9);

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals("OK", out.get("status"));
        assertEquals(true, out.get("passed"), "缺基线首轮不判定（口径文档第一节）");
        String detail = String.valueOf(out.get("detail"));
        assertTrue(detail.contains("row_count=9"), "明细必须带 row_count= 供后续窗口取基线");
        assertTrue(detail.contains("基线缺失"), detail);
    }

    @Test
    void volatilityAboveMaxChangeRateFailsButExecutesOk() {
        givenRule(rule("VOLATILITY", null, "{\"windows\":[7],\"maxChangeRate\":0.3}"));
        givenBaselineDetail("row_count=6, min=null, max=null");
        givenScalar(9);   // (9-6)/6 = 0.5 > 0.3

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals("OK", out.get("status"));
        assertEquals(false, out.get("passed"));
        assertTrue(String.valueOf(out.get("detail")).contains("超限"));
    }

    @Test
    void volatilityWithinTolerancePasses() {
        givenRule(rule("VOLATILITY", null, "{\"windows\":[7],\"maxChangeRate\":0.3}"));
        givenBaselineDetail("row_count=7, min=null");
        givenScalar(9);   // 2/7 ≈ 0.286 ≤ 0.3

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals(true, out.get("passed"));
        assertTrue(String.valueOf(out.get("detail")).contains("达标"));
    }

    @Test
    void volatilityZeroBaselineToPositiveRowsIsOverLimit() {
        givenRule(rule("VOLATILITY", null, "{\"windows\":[1],\"maxChangeRate\":3.0}"));
        givenBaselineDetail("row_count=0, min=0");
        givenScalar(9);   // 0→9：变化率无定义，按超限处理

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals(false, out.get("passed"));
        assertTrue(String.valueOf(out.get("detail")).contains("0→9 超限"));
    }

    @Test
    void volatilityWithoutMaxChangeRateRejected() {
        givenRule(rule("VOLATILITY", null, "{\"windows\":[1]}"));
        givenNoBaseline();
        givenScalar(9);

        ApiException e = assertThrows(ApiException.class,
                () -> service().evaluate(1L, LocalDate.of(2026, 10, 27)));
        assertTrue(e.getMessage().contains("maxChangeRate"));
    }

    // ------------------------------------------------------------------ REGEX

    @Test
    void regexPgDialectCountsNonMatchingRowsAndEscapesQuotes() {
        givenRule(rule("REGEX", "code", "{\"pattern\":\"^it's$\",\"maxBad\":0}"));
        givenScalar(0);

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals(true, out.get("passed"));
        String sql = capturedScalarSql();
        assertTrue(sql.contains("code is not null"), "NULL 不计入：" + sql);
        assertTrue(sql.contains("code !~ '^it''s$'"), "PG 方言 + 单引号转义：" + sql);
    }

    @Test
    void regexMaxBadToleranceIsHonored() {
        givenRule(rule("REGEX", "phone", "{\"pattern\":\"^\\\\d{11}$\",\"maxBad\":5}"));
        givenScalar(3);

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals(true, out.get("passed"), "3 行不匹配 ≤ maxBad=5");
        assertTrue(String.valueOf(out.get("detail")).startsWith("regex_bad=3"));
    }

    @Test
    void regexWithoutColumnRejected() {
        givenRule(rule("REGEX", null, "{\"pattern\":\"^a$\"}"));
        ApiException e = assertThrows(ApiException.class,
                () -> service().evaluate(1L, LocalDate.of(2026, 10, 27)));
        assertTrue(e.getMessage().contains("缺少字段"));
    }

    @Test
    void regexDorisDialectUsesRegexpOperator() {
        assertEquals("select count(*) from t where c is not null and not (c regexp '^x$')",
                QualityService.regexBadSql("REGEXP", "t", "c", "^x$"));
        assertEquals("select count(*) from t where c is not null and c !~ '^x$'",
                QualityService.regexBadSql("PG", "t", "c", "^x$"));
        assertEquals("PG", QualityService.sqlDialect("PG", null));
        assertEquals("REGEXP", QualityService.sqlDialect("DORIS", null));
    }

    // ------------------------------------------------------------------- ENUM

    @Test
    void enumCountsRowsOutsideWhitelistExcludingNulls() {
        givenRule(rule("ENUM", "env", "{\"allowed\":[\"DEV\",\"PROD\",\"O'Neil\"],\"maxBad\":0}"));
        givenScalar(0);

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals(true, out.get("passed"));
        String sql = capturedScalarSql();
        assertTrue(sql.contains("env is not null"), sql);
        assertTrue(sql.contains("not in ('DEV', 'PROD', 'O''Neil')"), "白名单转义：" + sql);
    }

    @Test
    void enumWithEmptyAllowedRejected() {
        givenRule(rule("ENUM", "status", "{\"allowed\":[]}"));
        ApiException e = assertThrows(ApiException.class,
                () -> service().evaluate(1L, LocalDate.of(2026, 10, 27)));
        assertTrue(e.getMessage().contains("allowed"));
    }

    // --------------------------------------------------------------- DISCRETE

    @Test
    void discreteDistinctCountWithinBounds() {
        givenRule(rule("DISCRETE", "env", "{\"min\":1,\"max\":100}"));
        givenScalar(2);

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals(true, out.get("passed"));
        assertTrue(capturedScalarSql().contains("count(distinct env)"));
        assertTrue(String.valueOf(out.get("detail")).startsWith("distinct_count=2"));
    }

    @Test
    void discreteAboveMaxFails() {
        givenRule(rule("DISCRETE", "status", "{\"min\":1,\"max\":10}"));
        givenScalar(150);

        Map<String, Object> out = service().evaluate(1L, LocalDate.of(2026, 10, 27));

        assertEquals(false, out.get("passed"));
        assertEquals("OK", out.get("status"), "数据不达标 ≠ 执行失败");
    }

    @Test
    void discreteWithoutAnyBoundRejected() {
        givenRule(rule("DISCRETE", "status", "{}"));
        ApiException e = assertThrows(ApiException.class,
                () -> service().evaluate(1L, LocalDate.of(2026, 10, 27)));
        assertTrue(e.getMessage().contains("min 或 max"));
    }

    // ------------------------------------------------- 白名单 / 创建期校验 / 模板

    @Test
    void whitelistNowCoversTenRuleTypes() {
        assertEquals(10, QualityController.RULE_TYPES.size());
        assertTrue(QualityController.RULE_TYPES.containsAll(List.of(
                "ROW_COUNT", "NULL_RATE", "UNIQUE", "RANGE", "FRESHNESS", "CUSTOM_SQL",
                "VOLATILITY", "REGEX", "ENUM", "DISCRETE")));
    }

    @Test
    void validateConfigAcceptsContractConfigsAndRejectsBrokenOnes() throws Exception {
        // docs/测试-TC7-G1.md §五契约里的四份 config 原样过创建期校验
        QualityService.validateConfig("VOLATILITY",
                mapper.readTree("{\"windows\":[1,7,30],\"maxChangeRate\":0.3}"));
        QualityService.validateConfig("REGEX",
                mapper.readTree("{\"pattern\":\"^[a-z][a-z0-9_]*$\",\"maxBad\":0}"));
        QualityService.validateConfig("ENUM",
                mapper.readTree("{\"allowed\":[\"DEV\",\"PROD\"],\"maxBad\":0}"));
        QualityService.validateConfig("DISCRETE", mapper.readTree("{\"min\":1,\"max\":100}"));
        // 存量六类不因新校验被拦（契约「不动老行为」）
        QualityService.validateConfig("CUSTOM_SQL", mapper.readTree("{}"));
        // 坏配置必须 400，不许静默收单
        assertThrows(ApiException.class,
                () -> QualityService.validateConfig("REGEX", mapper.readTree("{\"pattern\":\"[\"}")));
        assertThrows(ApiException.class,
                () -> QualityService.validateConfig("VOLATILITY", mapper.readTree("{\"windows\":[]}")));
    }

    @Test
    void templatesAreNonEmptyWellFormedAndPassTheirOwnValidation() throws Exception {
        List<Map<String, Object>> templates = QualityRuleTemplates.list();
        assertFalse(templates.isEmpty());
        for (Map<String, Object> t : templates) {
            String name = String.valueOf(t.get("name"));
            String type = String.valueOf(t.get("ruleType"));
            assertFalse(name.isBlank(), "模板必须有 name");
            assertTrue(QualityController.RULE_TYPES.contains(type), "模板类型必须在白名单: " + type);
            QualityService.validateConfig(type, mapper.valueToTree(t.get("config")));
        }
        // 契约四类都要有模板可用（客户「按模板批量下发」场景）
        for (String type : List.of("VOLATILITY", "REGEX", "ENUM", "DISCRETE")) {
            assertTrue(templates.stream().anyMatch(t -> type.equals(t.get("ruleType"))),
                    "缺 " + type + " 的内置模板");
        }
    }

    @Test
    void parseRowCountHandlesBothOldAndNewDetailFormats() {
        assertEquals(9L, QualityService.parseRowCount("row_count=9, min=null, max=null"));
        assertEquals(0L, QualityService.parseRowCount("row_count=0, min=0"));
        assertEquals(null, QualityService.parseRowCount("null_rate=0.1, max=0.5"), "无 row_count 约定 = 缺基线");
        assertEquals(null, QualityService.parseRowCount(null));
    }
}
