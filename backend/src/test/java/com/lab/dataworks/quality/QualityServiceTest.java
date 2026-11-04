package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisTargetService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 质量结果可信度：执行失败要标 ERROR，RANGE 缺省边界不能默认 0。 */
class QualityServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private QualityService service() {
        return new QualityService(jdbc, new ObjectMapper(), new RestTemplateBuilder(),
                "http://mcp", "", mock(SecretCipher.class), mock(DorisTargetService.class),
                mock(com.lab.dataworks.project.WarehouseResolver.class),
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
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
    }

    @Test
    void executionFailureIsMarkedErrorNotDataFailure() {
        givenRule(rule("ROW_COUNT", null, "{}"));
        when(jdbc.queryForObject(anyString(), eq(Object.class)))
                .thenThrow(new RuntimeException("connection refused"));

        Map<String, Object> result = service().evaluate(1L, LocalDate.now());

        assertEquals("ERROR", result.get("status"));
        assertFalse((Boolean) result.get("passed"));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(anyString(), args.capture());
        Object[] values = args.getValue();
        assertEquals("ERROR", values[4], "INSERT 必须写 exec_status=ERROR");
    }

    @Test
    void rangeWithoutMinDoesNotTreatZeroAsLowerBound() {
        givenRule(rule("RANGE", "amount", "{\"max\":100}"));
        when(jdbc.queryForObject(anyString(), eq(Object.class))).thenReturn(0);

        Map<String, Object> result = service().evaluate(1L, LocalDate.now());

        assertEquals("OK", result.get("status"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForObject(sql.capture(), eq(Object.class));
        String generated = sql.getValue();
        assertTrue(generated.contains("amount > 100"), generated);
        assertFalse(generated.contains("amount < "), generated);
    }

    @Test
    void rangeWithoutAnyBoundIsRejected() {
        givenRule(rule("RANGE", "amount", "{}"));

        ApiException error = assertThrows(ApiException.class,
                () -> service().evaluate(1L, LocalDate.now()));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("min") || error.getMessage().contains("max"));
    }
}
