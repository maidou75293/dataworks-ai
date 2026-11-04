package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.flink.FlinkSqlService;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.secret.SecretCipher;
import com.lab.dataworks.udf.UdfService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 节点试跑按 engine 分派：默认 DORIS、SPARK 只读、FLINK 异步提交。 */
class WorkflowTaskRunControllerEngineTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DorisQueryService doris = mock(DorisQueryService.class);
    private final UdfService udf = mock(UdfService.class);
    private final SecretCipher cipher = mock(SecretCipher.class);
    private final FlinkSqlService flink = mock(FlinkSqlService.class);

    private final WorkflowTaskRunController controller = new WorkflowTaskRunController(
            jdbc, new ObjectMapper(), doris, udf, cipher, flink,
            SparkThriftSettings.DEFAULT_URL, "spark", "", 30);

    /** D38：PROD 试跑要读 request 上的权限点；本类用例一律按「只有 ops:run」构造。 */
    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "dev_user");
        http.setAttribute("atoms", java.util.Set.of("ops:run"));
        return http;
    }

    private static String model(String taskJson) {
        return "{\"tasks\":[" + taskJson + "],\"edges\":[]}";
    }

    private void givenTask(String taskJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", model(taskJson));
        row.put("project_id", 1L);
        // D38：projectDatabases 与 findTask 共用这条桩，缺开发库会被 DEV 闸门挡住
        row.put("devDatabase", "demo_dev");
        row.put("prodDatabase", "demo_prod");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    @Test
    void oldModelWithoutEngineRunsOnProjectDoris() {
        givenTask("{\"code\":\"t1\",\"name\":\"订单\",\"type\":\"SQL\","
                + "\"script\":\"select 1\",\"datasource\":\"DORIS\"}");
        when(doris.query(eq(1L), anyString(), eq(100))).thenReturn(
                new DorisQueryService.Result(List.of("v"), List.of(List.of("1")),
                        1, 100, 3L, "doris_dev"));

        Map<String, Object> out = controller.run(7L, "t1", null, request());

        assertEquals("DORIS", out.get("engine"));
        assertEquals(1, out.get("rowCount"));
        verify(doris).query(1L, "select 1", 100);
    }

    @Test
    void illegalEngineReturns400() {
        givenTask("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"script\":\"select 1\",\"engine\":\"PRESTO\"}");

        ApiException error = assertThrows(ApiException.class,
                () -> controller.run(7L, "t1", null, request()));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("不支持的执行引擎"));
        verify(doris, never()).query(anyLong(), anyString(), anyInt());
    }

    @Test
    void sparkRejectsWriteSqlBeforeConnecting() {
        givenTask("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"script\":\"insert into t select 1\",\"engine\":\"SPARK\"}");

        ApiException error = assertThrows(ApiException.class,
                () -> controller.run(7L, "t1", null, request()));

        assertEquals(400, error.getStatus());
        // 文案要带引擎名 + 原因，且必须在连 Spark 之前拦下
        assertTrue(error.getMessage().contains("SPARK"));
        assertTrue(error.getMessage().contains("只允许"));
    }

    @Test
    void sparkRejectsNonSparkDatasourceBinding() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", model("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"script\":\"select 1\",\"engine\":\"SPARK\",\"datasourceId\":9}"));
        row.put("project_id", 1L);
        row.put("devDatabase", "demo_dev");
        row.put("prodDatabase", "demo_prod");
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("id", 9L);
        ds.put("name", "mysql-订单库");
        ds.put("ds_type", "MYSQL");
        ds.put("jdbc_url", "jdbc:mysql://mysql:3306/x");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row), List.of(ds));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.run(7L, "t1", null, request()));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("只能绑定 SPARK 类型的数据源"));
    }

    @Test
    void dorisRejectsNonQueryableDatasourceBinding() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", model("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"script\":\"select 1\",\"engine\":\"DORIS\",\"datasourceId\":9}"));
        row.put("project_id", 1L);
        row.put("devDatabase", "demo_dev");
        row.put("prodDatabase", "demo_prod");
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("id", 9L);
        ds.put("name", "kafka-订单");
        ds.put("ds_type", "KAFKA");
        ds.put("jdbc_url", "jdbc:mysql://kafka:9092/x");
        ds.put("queryable", false);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row), List.of(ds));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.run(7L, "t1", null, request()));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("DORIS"));
        assertTrue(error.getMessage().contains("KAFKA"));
        assertTrue(error.getMessage().contains("入湖"));
    }

    @Test
    void sparkConnectionFailureBecomesActionable503() {
        RuntimeException failure = new RuntimeException("SPARK 试跑失败："
                + "Could not open client transport with JDBC Uri: "
                + SparkThriftSettings.DEFAULT_URL,
                new java.net.ConnectException("Connection refused: connect"));

        RuntimeException mapped = WorkflowTaskRunController.sparkFailure(
                SparkThriftSettings.DEFAULT_URL, failure);

        assertTrue(mapped instanceof ApiException);
        assertEquals(503, ((ApiException) mapped).getStatus());
        assertTrue(mapped.getMessage().contains("Spark Thrift 未就绪"));
        assertTrue(mapped.getMessage().contains(SparkThriftSettings.DEFAULT_URL));
        assertTrue(mapped.getMessage().contains("Connection refused"));
    }

    @Test
    void sparkBusinessErrorIsNotTurnedIntoUnavailable() {
        RuntimeException failure = new RuntimeException("SPARK 试跑失败：Error 1064: syntax error");

        RuntimeException mapped = WorkflowTaskRunController.sparkFailure(
                SparkThriftSettings.DEFAULT_URL, failure);

        assertEquals(failure, mapped);
    }

    @Test
    void flinkSelectReturnsAsyncNoticeInsteadOfHanging() {
        givenTask("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"script\":\"select 1\",\"engine\":\"FLINK\"}");
        when(flink.validate("select 1")).thenReturn(Map.of("ok", true, "statements", 1,
                "planRows", 0));

        Map<String, Object> out = controller.run(7L, "t1", null, request());

        assertEquals("FLINK", out.get("engine"));
        assertTrue(String.valueOf(out.get("message")).contains("异步提交"));
        assertEquals(0, out.get("rowCount"));
        verify(flink, never()).submitAndObserve(anyString());
    }

    /**
     * D38（2026-10-26）：Flink 的 INSERT 类脚本**不再在试跑里提交作业**。
     *
     * <p>原行为（本用例旧版）是「提交 submitAndObserve（原名 smokeTest）→ 返回 jobId/状态 → 自动取消」，等于在真实集群
     * 起一个会写目标表的作业，和「试跑只验证 SQL」的预期相反。现在必须 400，
     * 且 submitAndObserve / validate 一次都不许调。</p>
     */
    @Test
    void flinkInsertIsRejectedInsteadOfSubmittingARealJob() {
        givenTask("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"engine\":\"FLINK\",\"script\":"
                + "\"create table s(id int) with ('connector'='datagen');"
                + "create table t(id int) with ('connector'='print');"
                + "insert into t select id from s\"}");
        when(udf.flinkPreamble(anyLong(), anyString())).thenReturn("");
        when(flink.submitAndObserve(anyString())).thenReturn(Map.of("ok", true,
                "jobId", "0123456789abcdef0123456789abcdef", "state", "RUNNING"));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.run(7L, "t1", null, request()));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("不能在试跑里提交"), error.getMessage());
        assertTrue(error.getMessage().contains("流开发"), "要给出下一步去哪跑: " + error.getMessage());
        verify(flink, never()).submitAndObserve(anyString());
        verify(flink, never()).validate(anyString());
    }

    @Test
    void flinkRejectsDatasourceBinding() {
        givenTask("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"script\":\"select 1\",\"engine\":\"FLINK\",\"datasourceId\":9}");

        ApiException error = assertThrows(ApiException.class,
                () -> controller.run(7L, "t1", null, request()));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("不支持绑定数据源"));
    }

    @Test
    void dorisTaskWithFlinkSyntaxIsRejectedBeforeExecuting() {
        givenTask("{\"code\":\"t1\",\"name\":\"x\",\"type\":\"SQL\","
                + "\"engine\":\"DORIS\",\"script\":\"create table t(id int) "
                + "with ('connector'='kafka')\"}");

        ApiException error = assertThrows(ApiException.class,
                () -> controller.run(7L, "t1", null, request()));

        assertEquals(400, error.getStatus());
        // 提示要指向正确的下一步：换 FLINK 引擎（新建脚本），而不是只报 1064
        assertTrue(error.getMessage().contains("FLINK"));
        assertTrue(error.getMessage().contains("connector"));
        verify(doris, never()).query(anyLong(), anyString(), anyInt());
    }

    @Test
    void insertDetectionIgnoresCommentsAndStrings() {
        assertEquals(false, WorkflowTaskRunController.hasInsert(
                "select 'insert into t' as s -- insert into t\n"));
        assertEquals(true, WorkflowTaskRunController.hasInsert(
                "/* insert into t */ INSERT OVERWRITE t select 1"));
    }
}
