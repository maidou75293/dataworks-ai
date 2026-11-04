package com.lab.dataworks.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.lab.dataworks.datasource.DataSourceController;
import com.lab.dataworks.quality.QualityController;
import com.lab.dataworks.udf.UdfService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * BUG-TC4-6：不接受的字段必须显式拒绝（acceptance-rules 第 4 条「不许静默」）。
 *
 * <p>TC-4 实测：QA-05 / UD-04 / DS-02 三条请求体里带 {@code "env":"PROD"}（DS-02 还是非法值
 * STAGING）全部 200 收单再丢弃，用户以为指定了生产环境；DS-03 的 {@code ?env=PROD}
 * 被整参数忽略（返回全量 11 条，带 env 的 0 条）。对照组是试跑那条链，它有显式枚举校验会 400。</p>
 */
class StrictRequestFieldsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 数据质量 / 函数管理的 record 仍必须开启「未知字段失败」。
     *
     * <p>REQ-TC4-1 后 {@code DataSourceController.DataSourceRequest} 的 {@code env} 已转正
     * （V116 建列），不再走「未知字段」，改成枚举校验——见
     * {@link com.lab.dataworks.datasource.DatasourceEnvTest}。</p>
     */
    @Test
    void envFieldIsUnknownToTheseTwoRequests() {
        for (Class<?> type : new Class<?>[]{QualityController.RuleRequest.class,
                UdfService.FunctionRequest.class}) {
            UnrecognizedPropertyException error = assertThrows(UnrecognizedPropertyException.class,
                    () -> mapper.readValue("{\"env\":\"PROD\"}", type),
                    type.getSimpleName() + " 仍在静默忽略未知字段 env");
            assertEquals("env", error.getPropertyName());
        }
    }

    /** 数据源请求的 env 已转正：能解析出来，交给 Controller 做枚举校验。 */
    @Test
    void datasourceRequestAcceptsEnvButControllerValidatesIt() throws Exception {
        DataSourceController.DataSourceRequest parsed = mapper.readValue(
                "{\"name\":\"x\",\"type\":\"MYSQL\",\"env\":\"PROD\"}",
                DataSourceController.DataSourceRequest.class);
        assertEquals("PROD", parsed.env());

        ApiException error = assertThrows(ApiException.class,
                () -> DataSourceController.normalizeEnv("STAGING"));
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("DEV 或 PROD"), error.getMessage());
    }

    @Test
    void environmentAliasIsRejectedToo() {
        assertThrows(UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"environment\":\"PROD\"}",
                        QualityController.RuleRequest.class));
    }

    /** 已知字段不能被误伤（否则是收紧过头）。 */
    @Test
    void knownFieldsStillParse() throws Exception {
        QualityController.RuleRequest rule = mapper.readValue(
                "{\"code\":\"c_one\",\"name\":\"规则\",\"ruleType\":\"ROW_COUNT\","
                        + "\"tableName\":\"demo_retail.ods_order\"}",
                QualityController.RuleRequest.class);
        assertEquals("c_one", rule.code());
        assertEquals("demo_retail.ods_order", rule.tableName());
    }

    @Test
    void handlerSaysWhichFieldIsUnsupportedAndWhy() {
        HttpInputMessage input = new MockHttpInputMessage("{}".getBytes());
        Exception boom = null;
        try {
            mapper.readValue("{\"env\":\"PROD\"}", QualityController.RuleRequest.class);
        } catch (Exception e) {
            boom = e;
        }
        org.springframework.http.converter.HttpMessageNotReadableException unreadable =
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error", boom, input);

        ResponseEntity<?> handled = new GlobalExceptionHandler().handleUnreadable(
                unreadable, new MockHttpServletRequest());

        assertEquals(400, handled.getStatusCode().value());
        String body = String.valueOf(handled.getBody());
        assertTrue(body.contains("不支持的请求字段: env"), body);
        assertTrue(body.contains("不接受调用方指定"), "要说明环境是谁定的: " + body);
        assertTrue(!body.contains("不是合法的 JSON"), "不能混成语法错误: " + body);
    }

    /**
     * DS-03：数据源列表的 env 参数以前整参数忽略（返回全量、带 env 的 0 条），
     * 后来改成一律 400；REQ-TC4-1 起它是真维度：合法值过滤，非法值照旧 400。
     */
    @Test
    void datasourceListFiltersByEnvAndRejectsIllegalValues() {
        DataSourceController controller = new DataSourceController(
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(com.lab.dataworks.auth.SessionService.class),
                mock(com.lab.dataworks.secret.SecretCipher.class), mapper,
                mock(com.lab.dataworks.integration.ConnectorCatalog.class),
                mock(com.lab.dataworks.project.WarehouseResolver.class),
                mock(com.lab.dataworks.lake.LakeCatalogService.class),
                mock(com.lab.dataworks.datasource.GravitinoCatalogSync.class),
                mock(com.lab.dataworks.datasource.GravitinoCatalogAdmin.class),
                mock(com.lab.dataworks.metadata.MetadataReader.class), "dataworks", "");

        ApiException error = assertThrows(ApiException.class, () -> controller.all(null, "STAGING"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("DEV 或 PROD"), error.getMessage());
    }
}
