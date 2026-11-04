package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.flink.FlinkSqlService;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.secret.SecretCipher;
import com.lab.dataworks.udf.UdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D38（2026-10-26）：单节点试跑原先没有环境概念——Doris/Spark 直接落到项目数仓（＝生产库），
 * Flink 的 INSERT 还会在真实集群提交作业并写数据。现在：
 * <ul>
 *   <li>{@code env} 默认 DEV，写错 400（不静默按某一档跑）；</li>
 *   <li>DEV 解析项目 {@code dev_database}，缺库 400 且**不回落生产库**；
 *       SQL 里显式引用本项目生产库也 400；</li>
 *   <li>PROD 需要权限点 {@code ops:run:prod} + {@code confirmProd=true}；</li>
 *   <li>Flink INSERT 类在试跑里直接 400（改走「流开发」提交到 DEV）；</li>
 *   <li>响应回显 {@code env} 与实际库名，便于验收核对。</li>
 * </ul>
 */
class WorkflowTaskRunEnvTest {

    private static final String MODEL = """
            {"tasks":[{"code":"t1","name":"示例节点","type":"SQL","script":"select 1"}]}""";
    private static final String MODEL_FLINK_INSERT = """
            {"tasks":[{"code":"t1","name":"写数据","type":"SQL","engine":"FLINK",
            "script":"INSERT INTO sink SELECT * FROM src"}]}""";
    private static final String MODEL_FLINK_SELECT = """
            {"tasks":[{"code":"t1","name":"查一下","type":"SQL","engine":"FLINK",
            "script":"SELECT 1"}]}""";

    private JdbcTemplate jdbc;
    private DorisQueryService doris;
    private FlinkSqlService flink;
    private WorkflowTaskRunController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        doris = mock(DorisQueryService.class);
        flink = mock(FlinkSqlService.class);
        controller = new WorkflowTaskRunController(jdbc, new ObjectMapper(), doris,
                mock(UdfService.class), mock(SecretCipher.class), flink,
                "jdbc:hive2://spark-thrift:10000/", "spark", "", 60);
        when(doris.query(any(), anyString(), anyInt())).thenReturn(new DorisQueryService.Result(
                List.of("c"), List.of(List.of("1")), 1, 100, 5L, "项目 Doris"));
        when(flink.validate(anyString())).thenReturn(Map.of("statements", 1));
    }

    /** 工作流归属查询（findTask）与项目库查询（projectDatabases）分别桩掉。 */
    private void stubWorkflow(String model) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", model);
        row.put("project_id", 7L);
        when(jdbc.queryForList(contains("platform.workflow"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private void stubDatabases(String dev, String prod) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("devDatabase", dev);
        row.put("prodDatabase", prod);
        when(jdbc.queryForList(contains("dev_database"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private static MockHttpServletRequest request(String... atoms) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "dev_user");
        http.setAttribute("atoms", java.util.Set.of(atoms));
        return http;
    }

    private static WorkflowTaskRunController.RunRequest req(String sql, String env, Boolean confirm) {
        return new WorkflowTaskRunController.RunRequest(sql, null, null, env, confirm);
    }

    // ------------------------------------------------------ DEV（默认档）

    /** AUDIT-1 证据三件套：请求原文 / 响应（或异常）原文 / 期望。 */
    private static void evidence(String label, String request, String response, String expect) {
        System.out.println("D38-EVID[" + label + "] 请求原文: POST /api/workflows/1/tasks/t1/run "
                + request);
        System.out.println("D38-EVID[" + label + "] 响应原文: " + response);
        System.out.println("D38-EVID[" + label + "] 期望: " + expect);
    }

    @Test
    void defaultEnvResolvesProjectDevDatabase() {
        stubWorkflow(MODEL);
        stubDatabases("demo_dev", "demo_prod");

        Map<String, Object> out = controller.run(1, "t1", req(null, null, null), request("ops:run"));

        assertEquals("DEV", out.get("env"), "D38：不传 env 默认 DEV");
        assertEquals("demo_dev", out.get("database"), "D38：响应要回显实际用的库");
        verify(doris).query(eq(7L), eq("select 1"), anyInt());
        evidence("DEV_默认解析开发库", "{}（无 env 字段）",
                "200 " + out, "env=DEV 且 database=demo_dev（不是 demo_prod）");
    }

    /** 缺开发库必须 400，且**一条查询都不能发出去**（静默回落生产库就是本卡的口子）。 */
    @Test
    void missingDevDatabaseRejectsWithoutFallingBack() {
        stubWorkflow(MODEL);
        stubDatabases(null, "demo_prod");

        ApiException e = assertThrows(ApiException.class,
                () -> controller.run(1, "t1", req(null, "DEV", null), request("ops:run")));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("没有配置开发库"), String.valueOf(e.getMessage()));
        assertTrue(e.getMessage().contains("demo_prod"), "要点名是哪档库: " + e.getMessage());
        verify(doris, never()).query(any(), anyString(), anyInt());
        evidence("缺开发库_不回落", "{\"env\":\"DEV\"}",
                "400 " + e.getMessage(), "400 且一次查询都不发（不静默落到 demo_prod）");
    }

    @Test
    void devRunRejectsExplicitReferenceToOwnProdDatabase() {
        stubWorkflow(MODEL);
        stubDatabases("demo_dev", "demo_prod");

        ApiException e = assertThrows(ApiException.class, () -> controller.run(1, "t1",
                req("select * from demo_prod.t_order", "DEV", null), request("ops:run")));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("demo_prod"), "提示要点名生产库: " + e.getMessage());
        verify(doris, never()).query(any(), anyString(), anyInt());
    }

    /** 别项目的库不在本卡范围内（DEV 试跑只挡「引用自己项目的生产库」）。 */
    @Test
    void devRunAllowsOtherDatabaseReferences() {
        stubWorkflow(MODEL);
        stubDatabases("demo_dev", "demo_prod");

        Map<String, Object> out = controller.run(1, "t1",
                req("select * from other_dev.t_x", "DEV", null), request("ops:run"));

        assertEquals("demo_dev", out.get("database"));
        verify(doris).query(eq(7L), eq("select * from other_dev.t_x"), anyInt());
    }


    /**
     * 现网三个项目（demo/permproj/xproj920193）dev/prod 两档都是 NULL。
     * 这种情况谈不上「回落到生产库」——按既有项目数仓路由放行，响应 database=null；
     * 否则本卡会把所有存量试跑直接打死（六个 e2e 套件全红）。
     */
    @Test
    void projectWithoutAnyDatabaseSplitStillRunsAndReportsNullDatabase() {
        stubWorkflow(MODEL);
        stubDatabases(null, null);

        Map<String, Object> out = controller.run(1, "t1", req(null, null, null), request("ops:run"));

        assertEquals("DEV", out.get("env"));
        assertNull(out.get("database"), "两档都没配时不许假装解析出了某个库");
        verify(doris).query(eq(7L), eq("select 1"), anyInt());
    }

    @Test
    void databaseForEnvSemantics() {
        assertEquals("demo_dev", WorkflowTaskRunController.databaseForEnv(
                "demo_dev", "demo_prod", WorkflowTaskRunController.Env.DEV));
        assertEquals("demo_prod", WorkflowTaskRunController.databaseForEnv(
                "demo_dev", "demo_prod", WorkflowTaskRunController.Env.PROD));
        assertNull(WorkflowTaskRunController.databaseForEnv(
                null, null, WorkflowTaskRunController.Env.DEV), "无 dev/prod 分档 → 不解析");
        assertThrows(ApiException.class, () -> WorkflowTaskRunController.databaseForEnv(
                null, "demo_prod", WorkflowTaskRunController.Env.DEV));
        assertThrows(ApiException.class, () -> WorkflowTaskRunController.databaseForEnv(
                "  ", "demo_prod", WorkflowTaskRunController.Env.DEV));
    }

    // ------------------------------------------------------ PROD（要权限 + 确认）

    @Test
    void prodEnvRequiresPermissionAtom() {
        stubWorkflow(MODEL);
        stubDatabases("demo_dev", "demo_prod");

        ApiException e = assertThrows(ApiException.class, () -> controller.run(1, "t1",
                req(null, "PROD", true), request("ops:run")));
        assertEquals(403, e.getStatus());
        assertTrue(e.getMessage().contains("ops:run:prod"), String.valueOf(e.getMessage()));
        evidence("PROD_缺权限", "{\"env\":\"PROD\",\"confirmProd\":true}（atoms 只有 ops:run）",
                "403 " + e.getMessage(), "403 且不发查询");
        verify(doris, never()).query(any(), anyString(), anyInt());
    }

    @Test
    void prodEnvRequiresExplicitConfirmation() {
        stubWorkflow(MODEL);
        stubDatabases("demo_dev", "demo_prod");

        ApiException e = assertThrows(ApiException.class, () -> controller.run(1, "t1",
                req(null, "PROD", null), request("ops:run", "ops:run:prod")));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("confirmProd"), String.valueOf(e.getMessage()));
    }

    @Test
    void prodEnvWithPermissionAndConfirmUsesProdDatabase() {
        stubWorkflow(MODEL);
        stubDatabases("demo_dev", "demo_prod");

        Map<String, Object> out = controller.run(1, "t1", req(null, "PROD", true),
                request("ops:run", "ops:run:prod"));

        assertEquals("PROD", out.get("env"));
        assertEquals("demo_prod", out.get("database"));
        evidence("PROD_有权限有确认", "{\"env\":\"PROD\",\"confirmProd\":true}（atoms 含 ops:run:prod）",
                "200 " + out, "env=PROD 且 database=demo_prod");
    }

    @Test
    void unknownEnvIsRejectedNotSilentlyDowngraded() {
        stubWorkflow(MODEL);
        stubDatabases("demo_dev", "demo_prod");

        ApiException e = assertThrows(ApiException.class,
                () -> controller.run(1, "t1", req(null, "staging", null), request("ops:run")));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("env 只能是 DEV 或 PROD"), String.valueOf(e.getMessage()));
    }

    // ------------------------------------------------------ FLINK

    /** INSERT 类不再在试跑里提交作业：400，且一次 submitAndObserve/validate 都不许调。 */
    @Test
    void flinkInsertRejectedInTrialRun() {
        stubWorkflow(MODEL_FLINK_INSERT);
        stubDatabases("demo_dev", "demo_prod");

        ApiException e = assertThrows(ApiException.class,
                () -> controller.run(1, "t1", req(null, null, null), request("ops:run")));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("不能在试跑里提交"), String.valueOf(e.getMessage()));
        evidence("Flink_INSERT_拒绝提交作业", "{}（FLINK 节点脚本含 INSERT INTO）",
                "400 " + e.getMessage(), "400，不调 submitAndObserve/validate（不在真实集群起作业）");
        verify(flink, never()).submitAndObserve(anyString());
        verify(flink, never()).validate(anyString());
    }

    @Test
    void flinkSelectStillValidatesAndReportsEnv() {
        stubWorkflow(MODEL_FLINK_SELECT);
        stubDatabases("demo_dev", "demo_prod");

        Map<String, Object> out = controller.run(1, "t1", req(null, null, null), request("ops:run"));

        assertEquals("DEV", out.get("env"));
        assertNull(out.get("database"), "Flink 的库由 SQL 里的 catalog 决定，不假装是项目库");
        verify(flink).validate("SELECT 1");
    }

    // ------------------------------------------------------ 纯函数口径

    @Test
    void envOfDefaultsAndRejectsUnknown() {
        assertEquals(WorkflowTaskRunController.Env.DEV,
                WorkflowTaskRunController.resolveEnv(null, false, false));
        assertEquals(WorkflowTaskRunController.Env.DEV,
                WorkflowTaskRunController.resolveEnv("  dev ", false, false));
        assertEquals(WorkflowTaskRunController.Env.PROD,
                WorkflowTaskRunController.resolveEnv("prod", true, true));
        assertThrows(ApiException.class,
                () -> WorkflowTaskRunController.resolveEnv("PROD", true, false));
        assertThrows(ApiException.class,
                () -> WorkflowTaskRunController.resolveEnv("PROD", false, true));
        assertThrows(ApiException.class,
                () -> WorkflowTaskRunController.resolveEnv("TEST", true, true));
    }

    /** 注释、字符串字面量、反引号、大小写都不能成为绕过判定的口子。 */
    @Test
    void prodReferenceIgnoresCommentsAndLiteralsButCatchesQuotes() {
        assertNull(WorkflowTaskRunController.prodReference("-- demo_prod.x\nselect 1", "demo_prod"));
        assertNull(WorkflowTaskRunController.prodReference("select 'from demo_prod.t'", "demo_prod"));
        assertNull(WorkflowTaskRunController.prodReference("select /* demo_prod.t */ 1", "demo_prod"));
        assertEquals("DEMO_PROD", WorkflowTaskRunController.prodReference(
                "select * from DEMO_PROD.t", "demo_prod"));
        assertEquals("demo_prod", WorkflowTaskRunController.prodReference(
                "select * from `demo_prod`.t_order", "demo_prod"));
    }

    /** maskSql 抽取后 hasInsert 行为必须不变（正名重构不能改判定）。 */
    @Test
    void hasInsertStillIgnoresCommentsAndLiterals() {
        assertTrue(WorkflowTaskRunController.hasInsert("insert into t values (1)"));
        assertTrue(WorkflowTaskRunController.hasInsert("INSERT OVERWRITE t SELECT 1"));
        assertFalse(WorkflowTaskRunController.hasInsert("-- insert into t\nselect 1"));
        assertFalse(WorkflowTaskRunController.hasInsert("/* insert into t */ select 1"));
        assertFalse(WorkflowTaskRunController.hasInsert("select 'insert into t' as s"));
        assertFalse(WorkflowTaskRunController.hasInsert("select 1"));
    }
}
