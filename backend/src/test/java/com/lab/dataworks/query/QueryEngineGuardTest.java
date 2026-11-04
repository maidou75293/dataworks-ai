package com.lab.dataworks.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V99 单引擎 Doris 查询前置校验的验收口径。
 *
 * <p>锁住四件事：Kafka 必须 400；Doris 放行；解析失败（表引用为空）放行；
 * 多个 JDBC 源 join 不拦截但给出「将使用 Doris 执行」的提示。</p>
 */
class QueryEngineGuardTest {

    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong();

    private static QueryEngineGuard.Datasource ds(String name, String dsType,
                                                  String engine, boolean queryable,
                                                  String... databases) {
        // 每个数据源独立 id：evaluate 按 id 去重，同 id 会被误合并
        return new QueryEngineGuard.Datasource(IDS.incrementAndGet(), 1, name, dsType,
                engine, queryable, List.of(databases));
    }

    @Test
    void kafkaIsRejectedWith400AndActionableMessage() {
        QueryEngineGuard.Datasource kafka = ds("kafka_demo", "KAFKA", "kafka", false, "kafka_demo");
        QueryEngineGuard.Decision decision = QueryEngineGuard.evaluate(
                List.of(kafka), List.of("kafka_demo.demo_orders"));

        assertTrue(decision.blocked());
        assertEquals("Kafka 不支持 SQL 查询，请先入湖或导入", decision.message());
        assertEquals(List.of("kafka_demo"), decision.blockedDatasources());

        // 控制器把 blocked 决策转成 400：不起 Spring 也能锁住状态码
        com.lab.dataworks.common.ApiException error = assertThrows(
                com.lab.dataworks.common.ApiException.class,
                () -> QueryController.rejectUnsupportedEngine(decision));
        assertEquals(400, error.getStatus());
        assertEquals("Kafka 不支持 SQL 查询，请先入湖或导入", error.getMessage());
    }

    @Test
    void kafkaIsAlsoMatchedByConfigDatabaseAlias() {
        QueryEngineGuard.Datasource kafka = ds("orders_stream", "KAFKA", "kafka", false,
                "orders_stream", "orders_db");
        QueryEngineGuard.Decision decision = QueryEngineGuard.evaluate(
                List.of(kafka), List.of("orders_db.demo_orders"));

        assertTrue(decision.blocked());
        assertEquals("Kafka 不支持 SQL 查询，请先入湖或导入", decision.message());
    }

    @Test
    void filesetIsRejected() {
        QueryEngineGuard.Datasource files = ds("corpus_s3", "S3FILE", "fileset", false, "corpus_s3");
        QueryEngineGuard.Decision decision = QueryEngineGuard.evaluate(
                List.of(files), List.of("corpus_s3.parquet_files"));

        assertTrue(decision.blocked());
        assertEquals("文件数据集不支持 SQL 查询，请先入湖或导入", decision.message());
    }

    @Test
    void dorisIsAllowedAndHinted() {
        QueryEngineGuard.Datasource doris = ds("内置 Doris", "DORIS", "doris", true, "demo_retail");
        QueryEngineGuard.Decision decision = QueryEngineGuard.evaluate(
                List.of(doris), List.of("demo_retail.orders"));

        assertFalse(decision.blocked());
        assertEquals(List.of("内置 Doris"), decision.involved());
        assertTrue(decision.hint().contains("涉及 1 个数据源"));
        assertTrue(decision.hint().contains("Doris 执行"));
    }

    @Test
    void parseFailureOrNoMatchPassesThrough() {
        QueryEngineGuard.Datasource kafka = ds("kafka_demo", "KAFKA", "kafka", false, "kafka_demo");

        // 解析失败：DataGuardService 返回空表引用（子查询/动态拼接）→ 放行
        QueryEngineGuard.Decision empty = QueryEngineGuard.evaluate(List.of(kafka), List.of());
        assertFalse(empty.blocked());
        assertNull(empty.hint());

        // select 1 这类没有 from 的语句同样无命中
        QueryEngineGuard.Decision noMatch = QueryEngineGuard.evaluate(
                List.of(kafka), List.of("demo_retail.orders"));
        assertFalse(noMatch.blocked());
        assertNull(noMatch.hint());
    }

    @Test
    void jdbcJoinIsAllowedWithMultiDatasourceHint() {
        QueryEngineGuard.Datasource mysql = ds("mysql_orders", "MYSQL", "jdbc", true, "db_orders");
        QueryEngineGuard.Datasource pg = ds("pg_users", "POSTGRESQL", "jdbc", true, "db_users");
        QueryEngineGuard.Decision decision = QueryEngineGuard.evaluate(
                List.of(mysql, pg), List.of("db_orders.t_order", "db_users.t_user"));

        assertFalse(decision.blocked());
        assertEquals(List.of("mysql_orders", "pg_users"), decision.involved());
        assertTrue(decision.hint().contains("涉及 2 个数据源"));
        assertTrue(decision.hint().contains("mysql_orders"));
        assertTrue(decision.hint().contains("Doris 执行"));
    }

    @Test
    void sameDatasourceCountsOnce() {
        QueryEngineGuard.Datasource doris = ds("doris_a", "DORIS", "doris", true, "db_a");
        QueryEngineGuard.Decision decision = QueryEngineGuard.evaluate(
                List.of(doris), List.of("db_a.t1", "db_a.t2", "db_a.t1"));

        assertEquals(List.of("doris_a"), decision.involved());
        assertTrue(decision.hint().contains("涉及 1 个数据源"));
    }

    @Test
    void aliasesCoverDataSourceNameSlugAndJdbcDatabase() {
        assertEquals(List.of("Kafka Demo", "kafka_demo"),
                QueryCapabilities.aliases("Kafka Demo", null, null));
        assertEquals(List.of("mysql_orders", "db_orders"),
                QueryCapabilities.aliases("mysql_orders",
                        "jdbc:mysql://host:3306/db_orders?useSSL=false", null));
    }

    @Test
    void engineMappingMatchesMigration() {
        assertEquals("doris", com.lab.dataworks.datasource.DatasourceEngine.engineOf("DORIS"));
        assertTrue(com.lab.dataworks.datasource.DatasourceEngine.queryable("DORIS"));
        assertEquals("kafka", com.lab.dataworks.datasource.DatasourceEngine.engineOf("KAFKA"));
        assertFalse(com.lab.dataworks.datasource.DatasourceEngine.queryable("KAFKA"));
        assertEquals("fileset", com.lab.dataworks.datasource.DatasourceEngine.engineOf("S3FILE"));
        assertFalse(com.lab.dataworks.datasource.DatasourceEngine.queryable("LOCALFILE"));
        assertEquals("jdbc", com.lab.dataworks.datasource.DatasourceEngine.engineOf("POSTGRESQL"));
        assertTrue(com.lab.dataworks.datasource.DatasourceEngine.queryable("MYSQL"));
    }
}
