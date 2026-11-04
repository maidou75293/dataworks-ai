package com.lab.dataworks.lineage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static com.lab.dataworks.lineage.SparkPublishLineageTest.SPARK_OVERWRITE;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.Row;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.edgeRows;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.jdbcOk;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.model;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.planWith;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.service;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.task;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-LINEAGE-SPARK-1 步骤 2：Spark 计划确认层（{@code EXPLAIN} → 表级边升 measured）。
 *
 * <p>计划文本样例取自本机 {@code dw-spark/spark-thrift} 真实输出（2026-10-29，
 * beeline 对 {@code tmp_lineage_m2.src → dst} 跑 {@code EXPLAIN}），不是照着文档猜的形状。</p>
 */
class SparkPlanLineageTest {

    /** 真实 INSERT 计划：扫描节点写 {@code Scan hive <catalog>.<db>.<table>}，写入目标在上一行。 */
    private static final List<List<String>> REAL_INSERT_PLAN = List.of(
            List.of("== Physical Plan =="),
            List.of("Execute InsertIntoHiveTable `spark_catalog`.`tmp_lineage_m2`.`dst`, "
                    + "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe, false, false, [id, amount]"),
            List.of("+- WriteFiles"),
            List.of("   +- Scan hive spark_catalog.tmp_lineage_m2.src [id#611, amount#612], "
                    + "HiveTableRelation [`spark_catalog`.`tmp_lineage_m2`.`src`, "
                    + "Data Cols: [id#611, amount#612], Partition Cols: []]"));

    /** 真实 CTAS 计划：来源表出现在 SubqueryAlias / HiveTableRelation 两处。 */
    private static final List<List<String>> REAL_CTAS_PLAN = List.of(
            List.of("== Physical Plan =="),
            List.of("Execute CreateDataSourceTableAsSelectCommand"),
            List.of("   +- CreateDataSourceTableAsSelectCommand "
                    + "`spark_catalog`.`tmp_lineage_m2`.`dst2`, ErrorIfExists, [id, amount]"),
            List.of("      +- Project [id#632, amount#633]"),
            List.of("         +- SubqueryAlias spark_catalog.tmp_lineage_m2.src"),
            List.of("            +- HiveTableRelation [`spark_catalog`.`tmp_lineage_m2`.`src`, "
                    + "Data Cols: [id#632, amount#633], Partition Cols: []]"));

    /** 真实失败计划（表不存在）：Spark 把 AnalysisException 当计划文本返回，不是抛异常。 */
    private static final List<List<String>> REAL_FAILED_PLAN = List.of(
            List.of("== Physical Plan =="),
            List.of("org.apache.spark.sql.AnalysisException: [TABLE_OR_VIEW_NOT_FOUND] "
                    + "The table or view `tmp_lineage_m2`.`dst` cannot be found."),
            List.of("'InsertIntoStatement 'UnresolvedRelation [tmp_lineage_m2, dst], []"));

    // ------------------------------------------------------------ 计划文本解析

    @Test
    void realInsertPlanYieldsScannedSourceTable() {
        Set<String> tables = SparkPlanLineage.tablesFromPlan(REAL_INSERT_PLAN);
        assertTrue(tables.contains("spark_catalog.tmp_lineage_m2.src"),
                "要认得 Scan hive 的三段名: " + tables);
        assertTrue(tables.contains("src"), "同时给裸表名，匹配 markMeasured 的叶子名口径: " + tables);
        assertFalse(tables.contains("dst"), "写入目标不是扫描表，不该从计划里确认出来: " + tables);
    }

    @Test
    void realCtasPlanYieldsScannedSourceTable() {
        Set<String> tables = SparkPlanLineage.tablesFromPlan(REAL_CTAS_PLAN);
        assertTrue(tables.contains("spark_catalog.tmp_lineage_m2.src"), "CTAS 的来源要确认到: " + tables);
        assertFalse(tables.contains("dst2"), "CTAS 的目标不该被当成扫描表: " + tables);
    }

    /** 失败计划里的 UnresolvedRelation 也带表名，但它不是「引擎真的扫过」，误确认比不确认更坏。 */
    @Test
    void failedPlanConfirmsNothing() {
        assertTrue(SparkPlanLineage.tablesFromPlan(REAL_FAILED_PLAN).isEmpty(),
                "AnalysisException 文本不能确认出表");
    }

    @Test
    void explainBudgetIsThreeSeconds() {
        assertEquals(3, SparkPlanLineage.EXPLAIN_BUDGET_SECONDS,
                "发布是同步链路，计划确认最多花 3 秒（与 FlinkPlanLineage 连接超时同口径）");
    }

    // ------------------------------------------------------------ 降级不抛

    @Test
    void unreachableThriftDegradesToEmptyWithoutThrowing() {
        // 地址不可达（或被出网守卫拒）都必须按「拿不到计划」返回空集合，绝不把异常抛回发布
        SparkPlanLineage plan = new SparkPlanLineage(
                "jdbc:hive2://127.0.0.1:1/", "spark", "");
        long start = System.currentTimeMillis();
        Set<String> tables = assertDoesNotThrow(() -> plan.tables(SPARK_OVERWRITE));
        assertTrue(tables.isEmpty(), "连不上就是空集合: " + tables);
        assertTrue(System.currentTimeMillis() - start < 30_000, "不能无限等引擎");
    }

    /**
     * 出网守卫放行、但主机解析不到的地址（开发机上跑不到集群内 Service）：
     * 走的是真正的 JDBC 连接路径，必须在登录预算内失败并按空集合降级。
     */
    @Test
    void unresolvableAllowedHostDegradesInsideTheBudget() {
        SparkPlanLineage plan = new SparkPlanLineage(
                "jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/", "spark", "");
        long start = System.currentTimeMillis();
        Set<String> tables = assertDoesNotThrow(() -> plan.tables(SPARK_OVERWRITE));
        long cost = System.currentTimeMillis() - start;
        assertTrue(tables.isEmpty(), "解析不到主机也要返回空集合: " + tables);
        assertTrue(cost < 20_000, "连接路径也不能把发布拖住, 实际耗时 " + cost + "ms");
    }

    @Test
    void blankAndMultiStatementScriptsAreNotExplained() {
        SparkPlanLineage plan = new SparkPlanLineage("jdbc:hive2://127.0.0.1:1/", "spark", "");
        assertTrue(plan.tables(null).isEmpty());
        assertTrue(plan.tables("  ").isEmpty());
        // 多语句脚本 EXPLAIN 会把后一条也交给引擎，语义不再是「只取计划」→ 不确认
        assertTrue(plan.tables("insert into a select 1 from b; drop table c").isEmpty());
    }

    // -------------------------------------------------- 接入：发布期升 measured

    @Test
    void sparkTaskTableEdgesGetUpgradedToMeasuredAtPublish() {
        JdbcTemplate jdbc = jdbcOk();
        int edges = service(jdbc, planWith("spark_catalog.ods.orders", "orders"))
                .record(7L, 3, "DEV", model(task("spark_sync", "SPARK", SPARK_OVERWRITE)));

        List<Row> rows = edgeRows(jdbc);
        assertEquals(rows.size(), edges);
        Row table = rows.stream().filter(r -> "TABLE".equals(r.edgeType())).findFirst().orElseThrow();
        assertEquals("measured", table.confidence(), "计划确认到的表级边要升 measured");
    }

    @Test
    void columnEdgesStayInferredAfterSparkPlanConfirm() {
        JdbcTemplate jdbc = jdbcOk();
        service(jdbc, planWith("dwd.order_detail", "order_detail", "ods.orders", "orders"))
                .record(7L, 1, "DEV", model(task("spark_cols", "SPARK",
                        "insert into dwd.order_detail (id, amount) select id, amount from ods.orders")));

        List<Row> rows = edgeRows(jdbc);
        assertTrue(rows.stream().anyMatch(r -> "TABLE".equals(r.edgeType())
                        && "measured".equals(r.confidence())), "表级边应升 measured: " + rows);
        assertTrue(rows.stream().filter(r -> "COLUMN".equals(r.edgeType()))
                        .allMatch(r -> "inferred".equals(r.confidence())),
                "Spark EXPLAIN 不给列级映射，列级边保持 inferred: " + rows);
    }

    /** 拿不到计划（返回空集合）→ 全部保持 inferred，且不影响其它任务产边。 */
    @Test
    void missingPlanKeepsEverythingInferred() {
        JdbcTemplate jdbc = jdbcOk();
        int edges = service(jdbc, SparkPublishLineageTest.emptyPlan())
                .record(7L, 1, "DEV", model(task("spark_sync", "SPARK", SPARK_OVERWRITE)));
        assertTrue(edges > 0, "确认失败也要有静态边");
        assertTrue(edgeRows(jdbc).stream().allMatch(r -> "inferred".equals(r.confidence())),
                "没有计划就不能标 measured");
    }

    /** 绑了独立数据源的 SPARK 节点：平台 Thrift 里没有这些表，去确认会得到假 measured。 */
    @Test
    void boundDatasourceSparkTaskIsNotExplainedAgainstPlatformThrift() {
        JdbcTemplate jdbc = jdbcOk();
        SparkPlanLineage spark = planWith("ods.orders", "orders");
        service(jdbc, spark).record(7L, 1, "DEV", model(
                "{\"code\":\"bound\",\"type\":\"SQL\",\"engine\":\"SPARK\",\"datasourceId\":12,"
                        + "\"script\":" + quote(SPARK_OVERWRITE) + "}"));

        verify(spark, never()).tables(anyString());
        assertTrue(edgeRows(jdbc).stream().allMatch(r -> "inferred".equals(r.confidence())),
                "绑定数据源时停在 inferred");
    }

    /** 纯 SELECT（没有写入目标）不值得花 3 秒预算，与 Doris 侧 writesTable 同判据。 */
    @Test
    void readOnlySparkSqlIsNotExplained() {
        JdbcTemplate jdbc = jdbcOk();
        SparkPlanLineage spark = planWith("ods.orders", "orders");
        service(jdbc, spark).record(7L, 1, "DEV", model(
                task("spark_read", "SPARK", "select id from ods.orders")));
        verify(spark, never()).tables(anyString());
    }

    /** 一次发布最多花一个 3 秒：第一次拿不到计划，后面的 SPARK 任务不再逐个试。 */
    @Test
    void firstEmptyPlanClosesTheBudgetForTheRestOfThePublish() {
        JdbcTemplate jdbc = jdbcOk();
        SparkPlanLineage spark = mock(SparkPlanLineage.class);
        when(spark.tables(anyString())).thenReturn(Set.of());
        service(jdbc, spark).record(7L, 1, "DEV", model(
                task("a", "SPARK", SPARK_OVERWRITE),
                task("b", "SPARK", "insert into dwd.t2 select id from ods.s2"),
                task("c", "SPARK", "insert into dwd.t3 select id from ods.s3")));
        verify(spark, times(1)).tables(anyString());
        assertTrue(edgeRows(jdbc).size() >= 3, "降级不影响静态边数量");
    }

    // -------------------------------------------------- 接入：运行期按引擎分派

    @Test
    void runtimeUsesSparkPlanForSparkTaskAndNeverDoris() {
        JdbcTemplate jdbc = jdbcOk();
        DorisPlanLineage doris = mock(DorisPlanLineage.class);
        when(jdbc.queryForObject(contains("platform.workflow_version"), eq(Object.class), any(Object[].class)))
                .thenReturn(model(task("spark_sync", "SPARK", SPARK_OVERWRITE)));
        LineageService service = new LineageService(jdbc, doris,
                mock(FlinkPlanLineage.class), planWith("ods.orders", "orders"));

        service.recordRuntime(7L, 3, "DEV", "spark_sync", SPARK_OVERWRITE, "run-1");

        verify(doris, never()).tables(anyLong(), anyString());
        List<Row> rows = edgeRows(jdbc);
        assertTrue(rows.stream().anyMatch(r -> "measured".equals(r.confidence())),
                "Spark 计划确认到的边要升 measured: " + rows);
    }

    /** 老任务没有 engine 字段：运行期继续按 Doris 口径确认，行为不变。 */
    @Test
    void runtimeKeepsDorisPathForLegacyTask() {
        JdbcTemplate jdbc = jdbcOk();
        DorisPlanLineage doris = mock(DorisPlanLineage.class);
        when(doris.tables(anyLong(), anyString())).thenReturn(Set.of("ods.orders", "orders"));
        when(jdbc.queryForObject(contains("platform.workflow_version"), eq(Object.class), any(Object[].class)))
                .thenReturn("{\"tasks\":[{\"code\":\"old\",\"type\":\"SQL\"}]}");
        when(jdbc.queryForObject(contains("platform.workflow where"), eq(Long.class), any(Object[].class)))
                .thenReturn(5L);
        SparkPlanLineage spark = planWith("nothing");
        LineageService service = new LineageService(jdbc, doris,
                mock(FlinkPlanLineage.class), spark);

        service.recordRuntime(7L, 3, "DEV", "old", "insert into dwd.t select id from ods.orders", "r");

        verify(doris).tables(5L, "insert into dwd.t select id from ods.orders");
        verify(spark, never()).tables(anyString());
    }

    /** 版本模型读不出来时不炸上报，按默认（DORIS）口径继续。 */
    @Test
    void runtimeFallsBackToDorisWhenModelUnreadable() {
        JdbcTemplate jdbc = jdbcOk();
        when(jdbc.queryForObject(contains("platform.workflow_version"), eq(Object.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("no such version"));
        DorisPlanLineage doris = mock(DorisPlanLineage.class);
        when(doris.tables(anyLong(), anyString())).thenReturn(Set.of());
        when(jdbc.queryForObject(contains("platform.workflow where"), eq(Long.class), any(Object[].class)))
                .thenReturn(5L);
        SparkPlanLineage spark = planWith("ods.orders");
        LineageService service = new LineageService(jdbc, doris,
                mock(FlinkPlanLineage.class), spark);

        int edges = assertDoesNotThrow(() -> service.recordRuntime(
                7L, 9, "PROD", "t1", SPARK_OVERWRITE, "run-2"));
        assertTrue(edges > 0);
        verify(spark, never()).tables(anyString());
        verify(doris).tables(anyLong(), anyString());
    }

    private static String quote(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
