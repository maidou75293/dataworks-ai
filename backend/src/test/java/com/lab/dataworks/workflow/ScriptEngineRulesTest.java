package com.lab.dataworks.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引擎与语法冲突：必须识别跨引擎专有语法，且不能误伤普通查询。 */
class ScriptEngineRulesTest {

    @Test
    void datasourceFilterFollowsEngineRules() {
        // DORIS：可执行 SQL 的 JDBC / Doris 源可以绑定
        assertNull(ScriptEngineRules.datasourceBindingError(
                ScriptEngine.DORIS, "MYSQL", true, null));
        assertNull(ScriptEngineRules.datasourceBindingError(
                ScriptEngine.DORIS, "DORIS", true, null));
        // DORIS：Kafka / 文件类不可查，拒绝并说清下一步
        String kafka = ScriptEngineRules.datasourceBindingError(
                ScriptEngine.DORIS, "KAFKA", false, null);
        assertNotNull(kafka);
        assertTrue(kafka.contains("DORIS"));
        assertTrue(kafka.contains("KAFKA"));
        assertTrue(kafka.contains("入湖"));

        // SPARK：只允许 ds_type=SPARK
        assertNull(ScriptEngineRules.datasourceBindingError(
                ScriptEngine.SPARK, "SPARK", true, null));
        String mysql = ScriptEngineRules.datasourceBindingError(
                ScriptEngine.SPARK, "MYSQL", true, null);
        assertNotNull(mysql);
        assertTrue(mysql.contains("SPARK"));
        assertTrue(mysql.contains("MYSQL"));
        assertTrue(mysql.contains(SparkThriftSettings.DEFAULT_URL));

        // FLINK：任何数据源都不允许绑定，且必须说明原因
        String flink = ScriptEngineRules.datasourceBindingError(
                ScriptEngine.FLINK, "SPARK", true, null);
        assertNotNull(flink);
        assertTrue(flink.contains("FLINK"));
        assertTrue(flink.contains("CREATE TABLE"));
        assertTrue(flink.contains("connector"));
    }

    @Test
    void dorisWithFlinkConnectorGetsActionableError() {
        String sql = "create table kafka_src(id int) with ('connector' = 'kafka',"
                + " 'topic' = 'demo_orders', 'properties.bootstrap.servers' = 'kafka:9092')";

        String message = ScriptEngineRules.crossEngineSyntax(ScriptEngine.DORIS, sql);

        assertNotNull(message);
        assertTrue(message.contains("DORIS"));
        assertTrue(message.contains("FLINK"));
        assertTrue(message.contains("connector"));
        // 说清下一步：新建脚本（引擎创建后不可改）
        assertTrue(message.contains("新建"));
        assertTrue(message.contains("不可修改"));
    }

    @Test
    void dorisWithWatermarkOrWindowIsRejected() {
        assertNotNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.DORIS,
                "create table t(ts timestamp(3), watermark for ts as ts - interval '5' second)"));
        assertNotNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.DORIS,
                "select * from table(tumble(table src, descriptor(ts), interval '1' minute))"));
        assertNotNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.DORIS,
                "select proctime() as p"));
    }

    @Test
    void normalQueriesAreNotFlagged() {
        assertNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.DORIS,
                "select id, sum(amount) from demo_retail.orders group by id"));
        // CTE 的 WITH 不是 Flink 连接器：不能误伤
        assertNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.DORIS,
                "with recent as (select * from orders) select * from recent"));
        assertNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.FLINK,
                "create table t(id int) with ('connector'='datagen')"));
        assertNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.SPARK,
                "select id from demo_retail.orders"));
        assertNull(ScriptEngineRules.crossEngineSyntax(null, "select 1"));
        assertNull(ScriptEngineRules.crossEngineSyntax(ScriptEngine.DORIS, "  "));
    }

    @Test
    void flinkWithDorisDdlGetsActionableError() {
        String message = ScriptEngineRules.crossEngineSyntax(ScriptEngine.FLINK,
                "create table t(id int) duplicate key(id) distributed by hash(id) buckets 1"
                        + " properties (\"replication_num\" = \"1\")");

        assertNotNull(message);
        assertTrue(message.contains("FLINK"));
        assertTrue(message.contains("DORIS"));
        assertTrue(message.contains("DISTRIBUTED BY"));
        assertTrue(message.contains("新建"));
    }

    @Test
    void sparkWithEitherEngineSpecificSyntaxIsRejected() {
        String flink = ScriptEngineRules.crossEngineSyntax(ScriptEngine.SPARK,
                "create table t(id int) with ('connector'='print')");
        assertNotNull(flink);
        assertTrue(flink.contains("SPARK"));
        assertTrue(flink.contains("FLINK"));

        String doris = ScriptEngineRules.crossEngineSyntax(ScriptEngine.SPARK,
                "create table t(id int) distributed by hash(id) buckets 1");
        assertNotNull(doris);
        assertTrue(doris.contains("SPARK"));
        assertTrue(doris.contains("DORIS"));
    }

    @Test
    void hintExtractionIsStable() {
        assertEquals("CREATE TABLE ... WITH ('connector'=...)",
                ScriptEngineRules.flinkHint("CREATE TABLE t(i int) WITH (\n 'connector' = 'kafka')"));
        assertEquals("DISTRIBUTED BY",
                ScriptEngineRules.dorisHint("CREATE TABLE t(i int) DISTRIBUTED BY HASH(i)"));
    }
}
