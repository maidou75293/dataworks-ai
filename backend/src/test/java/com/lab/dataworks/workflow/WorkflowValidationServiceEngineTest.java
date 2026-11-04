package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.flink.FlinkSqlService;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.udf.UdfService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 发布校验按引擎分流：Doris 探测、Flink EXPLAIN、SPARK 走 Thrift，冲突先给可行动错误。 */
class WorkflowValidationServiceEngineTest {

    private final DorisQueryService doris = mock(DorisQueryService.class);
    private final UdfService udf = mock(UdfService.class);
    private final PrefectFlowRenderer renderer = mock(PrefectFlowRenderer.class);
    private final FlinkSqlService flink = mock(FlinkSqlService.class);

    private final WorkflowValidationService service = new WorkflowValidationService(
            new ObjectMapper(), doris, udf, renderer, flink,
            SparkThriftSettings.DEFAULT_URL, "spark", "", 30);

    private static String model(String taskJson) {
        return "{\"tasks\":[" + taskJson + "],\"edges\":[]}";
    }

    @Test
    void legacyDorisTaskStillProbesDoris() {
        when(doris.query(eq(1L), anyString(), eq(0))).thenReturn(
                new DorisQueryService.Result(List.of("v"), List.of(), 0, 0, 3L, "doris"));

        WorkflowValidationService.Outcome outcome = service.validate(7L, 1L, "demo",
                "wf", "wf", "DEV", model("{\"code\":\"t1\",\"type\":\"SQL\","
                        + "\"script\":\"select 1\"}"));

        assertTrue(outcome.ok());
        verify(doris).query(1L, "select * from (select 1) _dw_validate limit 0", 0);
        verify(flink, never()).validate(anyString());
    }

    @Test
    void dorisTaskWithFlinkSyntaxFailsBeforeTouchingDoris() {
        WorkflowValidationService.Outcome outcome = service.validate(7L, 1L, "demo",
                "wf", "wf", "DEV", model("{\"code\":\"t1\",\"type\":\"SQL\","
                        + "\"engine\":\"DORIS\",\"script\":"
                        + "\"create table t(id int) with ('connector'='kafka')\"}"));

        assertFalse(outcome.ok());
        WorkflowValidationService.TaskCheck check = outcome.tasks().get(0);
        assertEquals("failed", check.status());
        assertTrue(check.message().contains("FLINK"));
        assertTrue(check.message().contains("connector"));
        verify(doris, never()).query(anyLong(), anyString(), anyInt());
    }

    @Test
    void flinkTaskUsesGatewayAndSkipsDoris() {
        when(flink.validate(anyString())).thenReturn(Map.of("ok", true, "statements", 1));
        String sql = "create table t(id int) with ('connector'='datagen');"
                + "create table o(id int) with ('connector'='print');insert into o select id from t";

        WorkflowValidationService.Outcome outcome = service.validate(7L, 1L, "demo",
                "wf", "wf", "DEV", model("{\"code\":\"t1\",\"type\":\"SQL\","
                        + "\"engine\":\"FLINK\",\"script\":\"" + sql + "\"}"));

        assertTrue(outcome.ok());
        verify(flink).validate(sql);
        verify(doris, never()).query(anyLong(), anyString(), anyInt());
        verify(udf, never()).checkUsage(anyLong(), anyString());
    }

    @Test
    void flinkDatasourceBindingFailsWithReason() {
        WorkflowValidationService.Outcome outcome = service.validate(7L, 1L, "demo",
                "wf", "wf", "DEV", model("{\"code\":\"t1\",\"type\":\"SQL\","
                        + "\"engine\":\"FLINK\",\"datasourceId\":9,\"script\":\"select 1\"}"));

        assertFalse(outcome.ok());
        assertTrue(outcome.tasks().get(0).message().contains("不支持绑定数据源"));
        verify(flink, never()).validate(anyString());
    }

    @Test
    void sparkTaskWithDorisDdlFailsActionably() {
        WorkflowValidationService.Outcome outcome = service.validate(7L, 1L, "demo",
                "wf", "wf", "DEV", model("{\"code\":\"t1\",\"type\":\"SQL\","
                        + "\"engine\":\"SPARK\",\"script\":"
                        + "\"create table t(id int) distributed by hash(id)\"}"));

        assertFalse(outcome.ok());
        WorkflowValidationService.TaskCheck check = outcome.tasks().get(0);
        assertTrue(check.message().contains("SPARK"));
        assertTrue(check.message().contains("DORIS"));
        assertTrue(check.message().contains("DISTRIBUTED BY"));
    }

    @Test
    void sparkStaysExecutableInsteadOfReportingEngineMissing() {
        // 写语句在发布前不做执行探测；关键是它不被当成「引擎未部署」拦下
        WorkflowValidationService.Outcome outcome = service.validate(7L, 1L, "demo",
                "wf", "wf", "DEV", model("{\"code\":\"t1\",\"type\":\"SQL\","
                        + "\"engine\":\"SPARK\",\"script\":\"insert into t select 1\"}"));

        assertTrue(outcome.ok());
        WorkflowValidationService.TaskCheck check = outcome.tasks().get(0);
        assertEquals("skipped", check.status());
        assertFalse(check.message().contains("未部署"));
    }

    @Test
    void nonSqlNodesAreSkipped() {
        WorkflowValidationService.Outcome outcome = service.validate(7L, 1L, "demo",
                "wf", "wf", "DEV", model("{\"code\":\"t1\",\"type\":\"SHELL\","
                        + "\"script\":\"echo hi\"}"));

        assertTrue(outcome.ok());
        assertEquals("skipped", outcome.tasks().get(0).status());
    }
}
