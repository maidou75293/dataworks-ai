package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-A（E2E-TRIAGE N2）回归：Doris 汇 {@code CREATE_SCHEMA_WHEN_NOT_EXIST} 缺省路径的
 * 自动建表口径。旧行为＝无主键表（或直接建不出来），首跑必败
 * {@code COMMON-24 ... has no primary keys}；修复后＝按源主键生成 {@code unique key} DDL，
 * 与表管理页建表（TableCreateDdl）同一语法口径。
 *
 * <p>本测试只锁纯逻辑（{@link DorisSinkAutoSchema}）；连库编排由
 * {@code scripts/e2e/dl1/fa_check.py} 现场首跑复验。</p>
 */
class SyncSinkAutoSchemaTest {

    private static DorisSinkAutoSchema.Column col(String name, int type,
                                                  int precision, int scale, String clazz) {
        return new DorisSinkAutoSchema.Column(name, type, precision, scale, clazz);
    }

    private static Map<String, Object> sink(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------- 生效范围 ----------

    /** 缺省即自动建表（与 renderJdbc :612 / SyncRunSummaryService.schemaMode 同源缺省）。 */
    @Test
    void dorisSinkDefaultModeApplies() {
        assertTrue(DorisSinkAutoSchema.shouldAutoCreate("Doris", sink()));
        assertTrue(DorisSinkAutoSchema.shouldAutoCreate("Doris",
                sink("schema_save_mode", "CREATE_SCHEMA_WHEN_NOT_EXIST")));
        assertTrue(DorisSinkAutoSchema.shouldAutoCreate("Doris",
                sink("schemaSaveMode", "create_schema_when_not_exist")));
    }

    /** 显式其它 mode 尊重用户语义，平台不抢建。 */
    @Test
    void otherModesAreRespected() {
        assertFalse(DorisSinkAutoSchema.shouldAutoCreate("Doris",
                sink("schema_save_mode", "ERROR_WHEN_SCHEMA_NOT_EXIST")));
        assertFalse(DorisSinkAutoSchema.shouldAutoCreate("Doris",
                sink("schema_save_mode", "DROP_EXISTENCE_FIRST")));
    }

    /** 非 Doris 汇不生效；Jdbc 汇仅在数据源是 DORIS（运行时标记）时生效——连接器自建出来的是无主键表。 */
    @Test
    void onlyDorisTargets() {
        assertFalse(DorisSinkAutoSchema.shouldAutoCreate("Jdbc", sink()));
        assertFalse(DorisSinkAutoSchema.shouldAutoCreate("Jdbc",
                sink("sinkEngineType", "POSTGRESQL")));
        assertTrue(DorisSinkAutoSchema.shouldAutoCreate("Jdbc",
                sink("sinkEngineType", "DORIS")));
        assertFalse(DorisSinkAutoSchema.shouldAutoCreate("Typesense", sink()));
    }

    // ---------- 建表 DDL ----------

    /** 主键列不在首位（DL-1 现场列序 id,name,updated_at 之外再验 name,id 序）时按 Doris「key 最前缀」重排。 */
    @Test
    void uniqueKeyReordersPkToPrefix() {
        String ddl = DorisSinkAutoSchema.buildCreateTableDdl("platform_dev", "fa_sink",
                List.of(col("name", Types.VARCHAR, 255, 0, "java.lang.String"),
                        col("id", Types.INTEGER, 10, 0, "java.lang.Integer"),
                        col("updated_at", Types.TIMESTAMP, 0, 0, "java.sql.Timestamp")),
                List.of("id"));
        assertTrue(ddl.startsWith("create table if not exists `platform_dev`.`fa_sink`"), ddl);
        // key 列提前且强制非空
        int idPos = ddl.indexOf("`id` int not null");
        int namePos = ddl.indexOf("`name` varchar(255) null");
        assertTrue(idPos >= 0 && namePos > idPos, "主键列应重排到最前且 not null：" + ddl);
        assertTrue(ddl.contains("unique key(`id`)"), ddl);
        assertTrue(ddl.contains("distributed by hash(`id`) buckets 1"), ddl);
        assertTrue(ddl.contains("\"replication_allocation\"=\"tag.location.default: 1\""), ddl);
        assertTrue(ddl.contains("`updated_at` datetime"), ddl);
        // 与 TableCreateDdl 同口径的收尾（engine=olap / properties）
        assertTrue(ddl.contains(") engine=olap"), ddl);
    }

    /** 无主键（源是视图/复杂 SQL 推不出键）→ duplicate 模型，追加语义可跑，不再伪装成唯一表。 */
    @Test
    void noPkFallsBackToDuplicateModel() {
        String ddl = DorisSinkAutoSchema.buildCreateTableDdl("db1", "t1",
                List.of(col("name", Types.VARCHAR, 50, 0, "java.lang.String"),
                        col("qty", Types.INTEGER, 10, 0, "java.lang.Integer")),
                List.of());
        assertTrue(ddl.contains("duplicate key(`name`)"), ddl);
        assertTrue(ddl.contains("`name` varchar(50) not null"), ddl);
        assertTrue(ddl.contains("distributed by hash(`name`) buckets 1"), ddl);
        assertTrue(ddl.contains("`qty` int null"), ddl);
    }

    /** 复合主键顺序按 KEY_SEQ 保留；结果集里没有的键（被 mapping 裁掉）丢弃，全丢则退化 duplicate。 */
    @Test
    void compositePkOrderAndGhostKeys() {
        String ddl = DorisSinkAutoSchema.buildCreateTableDdl("db1", "t1",
                List.of(col("a", Types.INTEGER, 10, 0, "java.lang.Integer"),
                        col("b", Types.VARCHAR, 20, 0, "java.lang.String"),
                        col("c", Types.BIGINT, 19, 0, "java.lang.Long")),
                List.of("c", "a"));
        assertTrue(ddl.contains("unique key(`c`, `a`)"), ddl);
        int c = ddl.indexOf("`c` bigint");
        int a = ddl.indexOf("`a` int");
        int b = ddl.indexOf("`b` varchar(20)");
        assertTrue(c < a && a < b, "key 列应按主键顺序占最前缀：" + ddl);

        String ghost = DorisSinkAutoSchema.buildCreateTableDdl("db1", "t1",
                List.of(col("x", Types.INTEGER, 10, 0, "java.lang.Integer")),
                List.of("missing_col"));
        assertTrue(ghost.contains("duplicate key(`x`)"), ghost);
    }

    /** PG 目录主键大小写与结果集标签可能不一致，按不敏感对齐。 */
    @Test
    void pkMatchesCaseInsensitive() {
        String ddl = DorisSinkAutoSchema.buildCreateTableDdl("db1", "t1",
                List.of(col("Name", Types.VARCHAR, 20, 0, "java.lang.String"),
                        col("Id", Types.INTEGER, 10, 0, "java.lang.Integer")),
                List.of("ID"));
        assertTrue(ddl.contains("unique key(`Id`)"), ddl);
    }

    /** 列名/表名含反引号、空格、分号等一律拒绝——它们会被原样拼进 DDL。 */
    @Test
    void rejectsHostileIdentifiers() {
        assertThrows(ApiException.class, () -> DorisSinkAutoSchema.buildCreateTableDdl(
                "db1", "t1`; drop table users; --",
                List.of(col("a", Types.INTEGER, 10, 0, "x")), List.of()));
        assertThrows(ApiException.class, () -> DorisSinkAutoSchema.buildCreateTableDdl(
                "db1", "t1", List.of(col("a b", Types.INTEGER, 10, 0, "x")), List.of()));
    }

    /** 结果集为空不能建出「零列表」。 */
    @Test
    void rejectsEmptyColumnList() {
        assertThrows(ApiException.class, () -> DorisSinkAutoSchema.buildCreateTableDdl(
                "db1", "t1", List.of(), List.of()));
    }

    // ---------- 类型映射 ----------

    @Test
    void typeMatrix() {
        assertEquals("tinyint", DorisSinkAutoSchema.dorisType(col("a", Types.TINYINT, 0, 0, "x")));
        assertEquals("smallint", DorisSinkAutoSchema.dorisType(col("a", Types.SMALLINT, 0, 0, "x")));
        assertEquals("int", DorisSinkAutoSchema.dorisType(col("a", Types.INTEGER, 10, 0, "x")));
        assertEquals("bigint", DorisSinkAutoSchema.dorisType(col("a", Types.BIGINT, 19, 0, "x")));
        assertEquals("float", DorisSinkAutoSchema.dorisType(col("a", Types.REAL, 0, 0, "x")));
        assertEquals("double", DorisSinkAutoSchema.dorisType(col("a", Types.DOUBLE, 0, 0, "x")));
        assertEquals("boolean", DorisSinkAutoSchema.dorisType(col("a", Types.BOOLEAN, 0, 0, "x")));
        assertEquals("date", DorisSinkAutoSchema.dorisType(col("a", Types.DATE, 0, 0, "x")));
        assertEquals("datetime", DorisSinkAutoSchema.dorisType(col("a", Types.TIMESTAMP, 0, 0, "x")));
        assertEquals("datetime", DorisSinkAutoSchema.dorisType(
                col("a", Types.TIMESTAMP_WITH_TIMEZONE, 0, 0, "x")));
        // Doris 无 time/binary：落 string 不丢数据可读性
        assertEquals("string", DorisSinkAutoSchema.dorisType(col("a", Types.TIME, 0, 0, "x")));
        assertEquals("string", DorisSinkAutoSchema.dorisType(col("a", Types.BINARY, 0, 0, "x")));
        assertEquals("varchar(100)", DorisSinkAutoSchema.dorisType(col("a", Types.VARCHAR, 100, 0, "x")));
        // PG text 报超长 precision → string
        assertEquals("string", DorisSinkAutoSchema.dorisType(col("a", Types.VARCHAR, 2147483647, 0, "x")));
        assertEquals("string", DorisSinkAutoSchema.dorisType(col("a", Types.LONGVARCHAR, 0, 0, "x")));
        // decimal 越界钳制 + 无界 numeric 默认
        assertEquals("decimal(10, 2)", DorisSinkAutoSchema.dorisType(col("a", Types.DECIMAL, 10, 2, "x")));
        assertEquals("decimal(38, 10)", DorisSinkAutoSchema.dorisType(col("a", Types.NUMERIC, 0, 0, "x")));
        assertEquals("decimal(38, 8)", DorisSinkAutoSchema.dorisType(col("a", Types.DECIMAL, 100, 8, "x")));
        // OTHER（PG 方言类型）按类名兜底
        assertEquals("boolean", DorisSinkAutoSchema.dorisType(col("a", Types.OTHER, 0, 0, "java.lang.Boolean")));
        assertEquals("bigint", DorisSinkAutoSchema.dorisType(col("a", Types.OTHER, 0, 0, "java.lang.Long")));
        assertEquals("decimal(38, 10)", DorisSinkAutoSchema.dorisType(
                col("a", Types.OTHER, 0, 0, "java.math.BigDecimal")));
        assertEquals("string", DorisSinkAutoSchema.dorisType(
                col("a", Types.OTHER, 0, 0, "org.postgresql.util.PGobject")));
    }

    // ---------- 连接定位 / 目标拆分 ----------

    /** 数据源 jdbc_url 优先；没有则 fenodes(:8030) 还原成 FE MySQL 口 :9030（fenodesFor 的反向）。 */
    @Test
    void jdbcUrlFromDsFirstThenFenodes() {
        assertEquals("jdbc:mysql://fe:9030/db",
                DorisSinkAutoSchema.sinkJdbcUrl("Doris", sink(
                        "jdbcUrl", "jdbc:mysql://fe:9030/db", "fenodes", "fe:8030")));
        assertEquals("jdbc:mysql://fe-a:9030",
                DorisSinkAutoSchema.sinkJdbcUrl("Doris", sink("fenodes", "fe-a:8030,fe-b:8030")));
        assertEquals("jdbc:mysql://doris-fe.svc:9030",
                DorisSinkAutoSchema.sinkJdbcUrl("Doris", sink("fenodes", "doris-fe.svc")));
        assertThrows(ApiException.class, () -> DorisSinkAutoSchema.sinkJdbcUrl("Doris", sink()));
        // Jdbc 汇指向 Doris 时自带 url 直接用
        assertEquals("jdbc:mysql://x:9030/y",
                DorisSinkAutoSchema.sinkJdbcUrl("Jdbc", sink("url", "jdbc:mysql://x:9030/y")));
    }

    /** 带库前缀的 target 优先于 sink.database；裸名回退 sink.database；缺库拒绝。 */
    @Test
    void targetQualification() {
        assertEquals("other", DorisSinkAutoSchema.splitQualified("platform_dev", "other.t2")[0]);
        assertEquals("t2", DorisSinkAutoSchema.splitQualified("platform_dev", "other.t2")[1]);
        assertEquals("platform_dev", DorisSinkAutoSchema.splitQualified("platform_dev", "t1")[0]);
        assertEquals("t1", DorisSinkAutoSchema.splitQualified("platform_dev", "t1")[1]);
        assertThrows(ApiException.class,
                () -> DorisSinkAutoSchema.splitQualified(null, "t1"));
        assertThrows(ApiException.class,
                () -> DorisSinkAutoSchema.splitQualified("db1", "t1;drop"));
    }

    /** 汇侧显式 primary_keys（列表或逗号串）优先于源表探测。 */
    @Test
    void declaredKeysParsed() {
        assertEquals(List.of("id"), DorisSinkAutoSchema.declaredPrimaryKeys(
                sink("primary_keys", List.of("id"))));
        assertEquals(List.of("a", "b"), DorisSinkAutoSchema.declaredPrimaryKeys(
                sink("primaryKeys", "a, b")));
        assertEquals(List.of(), DorisSinkAutoSchema.declaredPrimaryKeys(sink()));
    }

    /** 生效模式读取：schema_save_mode 与 schemaSaveMode 两种拼写、空串按缺省。 */
    @Test
    void effectiveModeReadsBothSpellings() {
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST",
                DorisSinkAutoSchema.effectiveSchemaSaveMode(sink()));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST",
                DorisSinkAutoSchema.effectiveSchemaSaveMode(sink("schema_save_mode", "  ")));
        assertEquals("DROP_EXISTENCE_FIRST",
                DorisSinkAutoSchema.effectiveSchemaSaveMode(sink("schemaSaveMode", "DROP_EXISTENCE_FIRST")));
    }
}
