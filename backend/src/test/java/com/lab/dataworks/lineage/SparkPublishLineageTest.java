package com.lab.dataworks.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-LINEAGE-SPARK-1 步骤 1：SPARK 任务的发布血缘走静态解析。
 *
 * <p>钉三件事：① SPARK 引擎节点与 DORIS 同口径产表级+列级边，confidence=inferred，
 * 节点 ID 是 catalog.db.table 三段式；② 单个任务解析/入库失败只跳过这一条（WARN 台账），
 * 同版本其它任务照产边、发布不红；③ 模型整体读不出来按「无血缘」返回而不是抛回发布链路。</p>
 */
class SparkPublishLineageTest {

    /** SparkSQL 方言：INSERT OVERWRITE TABLE（jsqlparser 不认，落正则兜底，仍要出边）。 */
    static final String SPARK_OVERWRITE =
            "INSERT OVERWRITE TABLE dwd.order_detail SELECT id, amount FROM ods.orders";

    // -------------------------------------------------------------- ① SPARK 产边

    @Test
    void sparkTaskProducesInferredTableEdgeWithThreePartNode() {
        JdbcTemplate jdbc = jdbcOk();
        int edges = service(jdbc).record(7L, 3, "DEV",
                model(task("spark_sync", "SPARK", SPARK_OVERWRITE)));

        List<Row> rows = edgeRows(jdbc);
        assertFalse(rows.isEmpty(), "SPARK 任务必须产血缘边");
        assertEquals(rows.size(), edges, "返回条数要等于入库条数");
        Row table = findTable(rows, "ods.orders");
        assertEquals("doris.dwd.order_detail", table.targetNode(),
                "两段表名补默认 catalog，节点 ID 口径与 DORIS 一致");
        assertEquals("doris.ods.orders", table.sourceNode());
        assertEquals("inferred", table.confidence(), "静态解析只能标 inferred");
        assertEquals("PUBLISH", table.origin());
        assertEquals("DEV", table.env());
        assertEquals("spark_sync", table.taskCode());
    }

    @Test
    void sparkTaskAlsoProducesColumnEdgesLikeDoris() {
        JdbcTemplate jdbc = jdbcOk();
        service(jdbc)
                .record(7L, 1, "DEV", model(task("spark_cols", "SPARK",
                        "insert into dwd.order_detail (id, amount) select id, amount from ods.orders")));

        List<Row> rows = edgeRows(jdbc);
        assertTrue(rows.stream().anyMatch(r -> "TABLE".equals(r.edgeType())), "要有表级边: " + rows);
        Row column = rows.stream().filter(r -> "COLUMN".equals(r.edgeType()))
                .findFirst().orElse(null);
        assertTrue(column != null, "带显式列清单时要有列级边（口径与 DORIS 一致）: " + rows);
        assertEquals("inferred", column.confidence());
        assertFalse(column.sourceColumn().isBlank(), "列级边要带来源列");
        assertFalse(column.targetColumn().isBlank(), "列级边要带目标列");
    }

    @Test
    void legacyTaskWithoutEngineFieldStillTreatedAsDoris() {
        JdbcTemplate jdbc = jdbcOk();
        // 老模型没有 engine 字段：不能因为「不是 SPARK」就不产边
        service(jdbc)
                .record(7L, 1, "DEV", model("{\"code\":\"old\",\"type\":\"SQL\","
                        + "\"script\":\"insert into dwd.t select id from ods.s\"}"));
        assertFalse(edgeRows(jdbc).isEmpty(), "老任务仍按 DORIS 产边");
    }

    @Test
    void nonSqlTasksAreStillSkipped() {
        JdbcTemplate jdbc = jdbcOk();
        service(jdbc)
                .record(7L, 1, "DEV", model("{\"code\":\"sh\",\"type\":\"SHELL\",\"script\":\"ls\"}"));
        assertTrue(edgeRows(jdbc).isEmpty(), "非 SQL 节点不产血缘");
    }

    // ---------------------------------------------------- ② 单任务失败隔离（WARN 台账）

    @Test
    void oneFailingTaskDoesNotSwallowTheOtherTasks() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 只统计「真的写进去」的边：Mockito 的参数捕获连抛异常的那次调用也会记下，
        // 用它做断言会把失败的半条边算成功，所以这里用成功回调自己收口。
        List<Object[]> succeeded = new ArrayList<>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] args = varargsOf(invocation);
            if (!sql.contains("insert into platform.lineage_edge")) {
                return 1;
            }
            if (cells(args).stream().anyMatch(s -> s.contains("boom_tbl"))) {
                throw new DataAccessResourceFailureException("spark thrift rejected the table");
            }
            succeeded.add(args);
            return 1;
        });
        LineagePublishIsolationPgTest.stubExecute(jdbc,
                new LineagePublishIsolationPgTest.PgTx().connection());
        int edges = service(jdbc)
                .record(9L, 2, "PROD", model(
                        task("bad_spark", "SPARK", "insert into boom_tbl select id from ods.src"),
                        task("good_spark", "SPARK", SPARK_OVERWRITE)));

        List<Row> rows = succeeded.stream().map(SparkPublishLineageTest::toRow).toList();
        assertFalse(rows.isEmpty(), "坏任务不能吞掉同版本其它任务的边");
        assertTrue(rows.stream().allMatch(r -> "PROD".equals(r.env())), "环境要照传");
        assertTrue(rows.stream().allMatch(r -> "PUBLISH".equals(r.origin())), "发布血缘 origin=PUBLISH");
        assertTrue(rows.stream().noneMatch(r -> "bad_spark".equals(r.taskCode())),
                "坏任务不留半条边: " + rows);
        assertTrue(rows.stream().anyMatch(r -> "good_spark".equals(r.taskCode())),
                "好的 Spark 任务必须留下边: " + rows);
        assertEquals(rows.size(), edges, "返回条数只计成功的");
    }

    @Test
    void unreadableModelReturnsZeroInsteadOfThrowing() {
        JdbcTemplate jdbc = jdbcOk();
        LineageService svc = service(jdbc);
        // 发布链路是 best-effort：模型 JSON 读不出来只能记 WARN，不能把 500 抛回发布
        int edges = assertDoesNotThrow(() -> svc.record(1L, 1, "DEV", "{ not-json"));
        assertEquals(0, edges);
        assertTrue(edgeRows(jdbc).isEmpty(), "读不出模型就不该有边");
    }

    // -------------------------------------------------------------- 引擎读取口径

    @Test
    void engineReaderIsCaseInsensitiveAndTolerant() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("SPARK", LineageService.engineOf(mapper.readTree("{\"engine\":\"spark\"}")));
        assertEquals("", LineageService.engineOf(mapper.readTree("{}")), "缺字段＝老任务");
        assertEquals("", LineageService.engineOf(mapper.readTree("{\"engine\":null}")),
                "null 不能读成字符串 \"null\"");
        assertEquals("", LineageService.engineOf(null));
        // 非法值不抛异常：由发布校验报错，血缘按「非 SPARK」继续
        assertEquals("MYSQL", LineageService.engineOf(mapper.readTree("{\"engine\":\"mysql\"}")));
    }

    // ---------------------------------------------------------------- 测试辅助

    /** 被测服务：计划层默认「拿不到计划」（空集合），所以静态边一律停在 inferred。 */
    static LineageService service(JdbcTemplate jdbc) {
        return service(jdbc, emptyPlan());
    }

    static LineageService service(JdbcTemplate jdbc, SparkPlanLineage spark) {
        return new LineageService(jdbc, mock(DorisPlanLineage.class),
                mock(FlinkPlanLineage.class), spark);
    }

    /** 计划层替身：永远给不出计划，等价于 Spark Thrift 未就绪 / 方言不认。 */
    static SparkPlanLineage emptyPlan() {
        SparkPlanLineage spark = mock(SparkPlanLineage.class);
        when(spark.tables(anyString())).thenReturn(java.util.Set.of());
        return spark;
    }

    /** 计划层替身：确认到指定表名。 */
    static SparkPlanLineage planWith(String... tables) {
        SparkPlanLineage spark = mock(SparkPlanLineage.class);
        when(spark.tables(anyString())).thenReturn(new java.util.LinkedHashSet<>(
                java.util.Arrays.asList(tables)));
        return spark;
    }

    static JdbcTemplate jdbcOk() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        // BUG-VER-V572-1 起发布写入走「保存点包裹」：execute(ConnectionCallback) 必须真执行回调
        LineagePublishIsolationPgTest.stubExecute(jdbc,
                new LineagePublishIsolationPgTest.PgTx().connection());
        return jdbc;
    }

    static String task(String code, String engine, String script) {
        return "{\"code\":\"" + code + "\",\"type\":\"SQL\",\"engine\":\"" + engine
                + "\",\"script\":" + quote(script) + "}";
    }

    /** 用 Jackson 生成字符串字面量，避免脚本里的引号把 JSON 拼坏。 */
    static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String model(String... tasks) {
        return "{\"tasks\":[" + String.join(",", tasks) + "]}";
    }

    /** Mockito 变长参数在 getArguments() 里可能被摊平、也可能带一个 Object[]，两种形状都收口。 */
    static Object[] varargsOf(org.mockito.invocation.InvocationOnMock invocation) {
        Object[] raw = invocation.getArguments();
        if (raw.length == 2 && raw[1] instanceof Object[] nested) {
            return nested;
        }
        return Arrays.copyOfRange(raw, 1, raw.length);
    }

    /** 一条 INSERT 的所有绑定值（含 SQL 里的可变部分），用来判定「这条边是不是被拒的那条」。 */
    static List<String> cells(Object[] args) {
        List<String> out = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Object[] nested) {
                for (Object value : nested) {
                    out.add(String.valueOf(value));
                }
            } else {
                out.add(String.valueOf(arg));
            }
        }
        return out;
    }

    /** 捕获真正写 lineage_edge 的那几次调用（删旧边的 UPDATE 不算）。 */
    static List<Row> edgeRows(JdbcTemplate jdbc) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), args.capture());
        List<Row> rows = new ArrayList<>();
        List<String> sqls = sql.getAllValues();
        List<Object[]> all = args.getAllValues();
        for (int i = 0; i < sqls.size(); i++) {
            if (!sqls.get(i).contains("insert into platform.lineage_edge")) {
                continue;
            }
            rows.add(toRow(all.get(i)));
        }
        return rows;
    }

    /**
     * 把一条 INSERT 的绑定值读成测试视图。列序与
     * {@code LineageService.insert} 的 SQL 占位符一一对应：
     * 0=workflowId 1=versionNo 2=taskCode 3..6=源四段 7..10=目标四段
     * 11=edgeType 12=confidence 13=origin 14=env 15=runId。
     */
    static Row toRow(Object[] a) {
        return new Row(String.valueOf(a[2]), String.valueOf(a[5]), String.valueOf(a[6]),
                String.valueOf(a[9]), String.valueOf(a[10]), String.valueOf(a[11]),
                String.valueOf(a[12]), String.valueOf(a[13]), String.valueOf(a[14]));
    }

    static Row findTable(List<Row> rows, String sourceSuffix) {
        return rows.stream()
                .filter(r -> "TABLE".equals(r.edgeType()))
                .filter(r -> r.sourceNode().endsWith(sourceSuffix))
                .findFirst().orElseThrow(() -> new AssertionError("没有 " + sourceSuffix + " 的表级边: " + rows));
    }

    /** 一行入库记录：任务 / 源节点 / 源列 / 目标节点 / 目标列 / 边类型 / 置信度 / 来源 / 环境。 */
    record Row(String taskCode, String sourceNode, String sourceColumn, String targetNode,
                       String targetColumn, String edgeType, String confidence, String origin,
                       String env) {
        @Override
        public String toString() {
            return Arrays.asList(taskCode, edgeType, confidence, origin, env,
                    sourceNode + (sourceColumn.isBlank() ? "" : "." + sourceColumn),
                    targetNode + (targetColumn.isBlank() ? "" : "." + targetColumn)).toString();
        }
    }
}
