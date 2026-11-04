package com.lab.dataworks.tablemanage;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 模式解析（第三批）：单条语句限制、只允许 CREATE TABLE / LIKE、结构化解析、
 * unsupported 收集。前端「SQL → 表单」的承诺是「拿不准就进 unsupported」，
 * 所以这里既测正常解析，也测保守分支。
 */
class TableSqlParserTest {

    private static TableSqlParser.Result parse(String sql) {
        return TableSqlParser.parse("db1", sql, null);
    }

    private static ApiException badRequest(Runnable action) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(400, e.getStatus(), e.getMessage());
        return e;
    }

    private static boolean has(List<String> list, String keyword) {
        return list.stream().anyMatch((s) -> s.contains(keyword));
    }

    // ------------------------------------------------------------ 单条 / 语句类型

    @Test
    void rejectsMultipleStatements() {
        ApiException e = badRequest(() -> parse(
                "create table `db1`.`a` (`id` int) engine=olap; "
                        + "create table `db1`.`b` (`id` int)"));
        assertTrue(e.getMessage().contains("只允许单条语句"), e.getMessage());
    }

    @Test
    void semicolonInsideStringIsNotSeparator() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `note` varchar(32) null comment 'a;b'\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\");");
        assertEquals("t", result.table());
        assertEquals("a;b", result.model().columns().get(1).comment());
    }

    @Test
    void trailingSemicolonAndLineCommentAreFine() {
        TableSqlParser.Result result = parse("-- 建表\n"
                + "create table `db1`.`t` (`id` bigint not null) engine=olap "
                + "duplicate key(`id`) distributed by hash(`id`) buckets 1 "
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\");\n");
        assertEquals("t", result.table());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    @Test
    void rejectsNonCreateTableStatements() {
        assertTrue(badRequest(() -> parse("insert into `db1`.`t` values (1)"))
                .getMessage().contains("只允许 CREATE TABLE"));
        assertTrue(badRequest(() -> parse("set x = 1")).getMessage().contains("只允许 CREATE TABLE"));
        assertTrue(badRequest(() -> parse("drop table `db1`.`t`"))
                .getMessage().contains("只允许 CREATE TABLE"));
        assertTrue(badRequest(() -> parse("alter table `db1`.`t` add column c int"))
                .getMessage().contains("只允许 CREATE TABLE"));
        assertTrue(badRequest(() -> parse("create database db2"))
                .getMessage().contains("CREATE DATABASE"));
        assertTrue(badRequest(() -> parse("create temporary table `db1`.`t` (`id` int)"))
                .getMessage().contains("TEMPORARY"));
    }

    @Test
    void rejectsExecutableCommentAndCtas() {
        assertTrue(badRequest(() -> parse("/*!40101 create table `db1`.`t` (`id` int) */"))
                .getMessage().contains("可执行注释"));
        assertTrue(badRequest(() -> parse(
                "create table `db1`.`t` as select * from `db1`.`src`"))
                .getMessage().contains("AS SELECT"));
    }

    @Test
    void ifNotExistsIsAccepted() {
        TableSqlParser.Result result = parse("create table if not exists `db1`.`t` (\n"
                + "  `id` bigint not null\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertEquals("t", result.table());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    @Test
    void requiresQualifiedTableInCurrentDatabase() {
        assertTrue(badRequest(() -> parse("create table t (`id` int)"))
                .getMessage().contains("表名带库名"));
        assertTrue(badRequest(() -> parse("create table `other`.`t` (`id` int)"))
                .getMessage().contains("不一致"));
    }

    @Test
    void likeFormPassesWithoutModel() {
        TableSqlParser.Result result = parse(
                "create table `db1`.`copy_t` like `db1`.`src_t`");
        assertEquals("copy_t", result.table());
        assertNull(result.model());
        assertTrue(has(result.unsupported(), "LIKE"), result.unsupported().toString());
        assertEquals("create table `db1`.`copy_t` like `db1`.`src_t`", result.ddl());
    }

    @Test
    void likeFormRejectsCrossDatabaseSource() {
        assertTrue(badRequest(() -> parse(
                "create table `db1`.`copy_t` like `other`.`src_t`"))
                .getMessage().contains("跨库"));
    }

    // ------------------------------------------------------------ 结构化解析

    @Test
    void parsesUniqueKeyBucketsReplicationAndColumns() {
        TableSqlParser.Result result = parse("create table `db1`.`t1` (\n"
                + "  `id` bigint not null comment '主键',\n"
                + "  `name` varchar(64) null comment '名字',\n"
                + "  `amt` decimal(12, 2) null default 0\n"
                + ") engine=olap\n"
                + "unique key(`id`)\n"
                + "distributed by hash(`id`) buckets 8\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 2\")");
        TableSqlParser.TableModel model = result.model();
        assertNotNull(model);
        assertEquals("db1", model.database());
        assertEquals("t1", model.table());
        assertEquals("UNIQUE", model.model());
        assertEquals(List.of("id"), model.keys());
        assertEquals(Integer.valueOf(8), model.buckets());
        assertEquals(Integer.valueOf(2), model.replication());
        assertEquals(List.of("id"), model.distributionColumns());

        assertEquals(3, model.columns().size());
        assertEquals("id", model.columns().get(0).name());
        assertEquals("BIGINT", model.columns().get(0).type());
        assertEquals(Boolean.FALSE, model.columns().get(0).nullable());
        assertEquals("主键", model.columns().get(0).comment());
        assertEquals("VARCHAR(64)", model.columns().get(1).type());
        assertEquals(Boolean.TRUE, model.columns().get(1).nullable());
        // DECIMAL(12, 2) 的空格会被规范化，默认值按常量保留
        assertEquals("DECIMAL(12,2)", model.columns().get(2).type());
        assertEquals("0", model.columns().get(2).defaultValue());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    @Test
    void parsesAggregateModelWithAggTypes() {
        TableSqlParser.Result result = parse("create table `db1`.`agg` (\n"
                + "  `k1` varchar(32) not null,\n"
                + "  `cnt` bigint sum comment '次数',\n"
                + "  `last` varchar(32) replace_if_not_null\n"
                + ") engine=olap\n"
                + "aggregate key(`k1`)\n"
                + "distributed by hash(`k1`) buckets 4\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        TableSqlParser.TableModel model = result.model();
        assertEquals("AGGREGATE", model.model());
        assertEquals("SUM", model.columns().get(1).aggType());
        assertEquals("REPLACE_IF_NOT_NULL", model.columns().get(2).aggType());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    @Test
    void primaryKeyMapsToUniqueModel() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `v` int null\n"
                + ") engine=olap primary key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertEquals("UNIQUE", result.model().model());
        assertTrue(has(result.warnings(), "PRIMARY KEY"), result.warnings().toString());
    }

    @Test
    void parsesStaticRangePartition() {
        TableSqlParser.Result result = parse("create table `db1`.`t_range` (\n"
                + "  `dt` date not null,\n"
                + "  `v` int null\n"
                + ") engine=olap\n"
                + "duplicate key(`dt`)\n"
                + "partition by range(`dt`) (\n"
                + "  partition `p20260101` values less than ('2026-01-02'),\n"
                + "  partition `p20260102` values less than ('2026-01-03')\n"
                + ")\n"
                + "distributed by hash(`dt`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        TableCreateDdl.PartitionDef partition = result.model().partition();
        assertNotNull(partition);
        assertEquals("RANGE", partition.mode());
        assertEquals(List.of("dt"), partition.columns());
        assertEquals(2, partition.ranges().size());
        assertEquals("p20260101", partition.ranges().get(0).name());
        assertEquals("2026-01-02", partition.ranges().get(0).lessThan());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    @Test
    void parsesDynamicPartitionFromProperties() {
        TableSqlParser.Result result = parse("create table `db1`.`ods_log` (\n"
                + "  `log_date` date not null comment '日期',\n"
                + "  `app` varchar(64) null,\n"
                + "  `cnt` bigint sum comment '次数'\n"
                + ") engine=olap\n"
                + "aggregate key(`log_date`, `app`)\n"
                + "comment '应用日志'\n"
                + "partition by range(`log_date`) ()\n"
                + "distributed by hash(`app`) buckets 8\n"
                + "properties (\n"
                + "  \"replication_allocation\" = \"tag.location.default: 1\",\n"
                + "  \"dynamic_partition.enable\" = \"true\",\n"
                + "  \"dynamic_partition.time_unit\" = \"DAY\",\n"
                + "  \"dynamic_partition.start\" = \"-7\",\n"
                + "  \"dynamic_partition.end\" = \"3\",\n"
                + "  \"dynamic_partition.prefix\" = \"p\",\n"
                + "  \"dynamic_partition.buckets\" = \"8\"\n"
                + ")");
        TableSqlParser.TableModel model = result.model();
        assertEquals("应用日志", model.comment());
        assertEquals(List.of("log_date", "app"), model.keys());
        assertEquals("AGGREGATE", model.model());
        TableCreateDdl.PartitionDef partition = model.partition();
        assertNotNull(partition);
        assertEquals("DYNAMIC", partition.mode());
        assertEquals(List.of("log_date"), partition.columns());
        TableCreateDdl.DynamicDef dynamic = partition.dynamic();
        assertNotNull(dynamic);
        assertEquals("DAY", dynamic.timeUnit());
        assertEquals("-7", dynamic.start());
        assertEquals("3", dynamic.end());
        assertEquals("p", dynamic.prefix());
        assertEquals(Integer.valueOf(8), dynamic.buckets());
        assertFalse(dynamic.createHistory());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    @Test
    void parsesListPartition() {
        TableSqlParser.Result result = parse("create table `db1`.`t_list` (\n"
                + "  `region` varchar(32) not null,\n"
                + "  `v` int null\n"
                + ") engine=olap\n"
                + "duplicate key(`region`)\n"
                + "partition by list(`region`) (\n"
                + "  partition `p_east` values in ('east', 'north'),\n"
                + "  partition `p_west` values in ('west')\n"
                + ")\n"
                + "distributed by hash(`region`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        TableCreateDdl.PartitionDef partition = result.model().partition();
        assertNotNull(partition);
        assertEquals("LIST", partition.mode());
        assertEquals(2, partition.lists().size());
        assertEquals(List.of("east", "north"), partition.lists().get(0).values());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    @Test
    void parsesDefaultLiterals() {
        TableSqlParser.Result result = parse("create table `db1`.`t_def` (\n"
                + "  `id` bigint not null,\n"
                + "  `flag` boolean null default true,\n"
                + "  `amt` decimal(12,2) null default -1.5,\n"
                + "  `d` date null default '2026-01-01'\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        List<TableController.ColumnRequest> columns = result.model().columns();
        assertEquals("true", columns.get(1).defaultValue());
        assertEquals("-1.5", columns.get(2).defaultValue());
        assertEquals("2026-01-01", columns.get(3).defaultValue());
        assertTrue(result.unsupported().isEmpty(), result.unsupported().toString());
    }

    // ------------------------------------------------------------ unsupported 收集

    @Test
    void unknownPropertyGoesToUnsupported() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\n"
                + "  \"replication_allocation\"=\"tag.location.default: 1\",\n"
                + "  \"storage_medium\"=\"SSD\"\n"
                + ")");
        assertTrue(has(result.unsupported(), "storage_medium"), result.unsupported().toString());
    }

    /** REQ-DW-6：命名 + 单列 + USING 白名单类型是表单能表达的形态，进 model.indexes。 */
    @Test
    void namedSingleColumnIndexGoesToModel() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `tag` varchar(32) null,\n"
                + "  index idx_tag (`tag`) using bitmap comment '标签'\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertFalse(has(result.unsupported(), "索引"), result.unsupported().toString());
        assertEquals(2, result.model().columns().size());
        List<TableController.IndexRequest> indexes = result.model().indexes();
        assertEquals(1, indexes.size(), indexes.toString());
        assertEquals("idx_tag", indexes.get(0).name());
        assertEquals("tag", indexes.get(0).column());
        assertEquals("BITMAP", indexes.get(0).type());
        assertEquals("标签", indexes.get(0).comment());
    }

    /** 保守原则不破：未命名 / 多列的索引表达不了，仍整条进 unsupported，且不吃掉字段。 */
    @Test
    void unnamedOrMultiColumnIndexStaysUnsupported() {
        TableSqlParser.Result unnamed = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `tag` varchar(32) null,\n"
                + "  index (`tag`) using bitmap\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(unnamed.unsupported(), "索引"), unnamed.unsupported().toString());
        assertTrue(unnamed.model().indexes().isEmpty(), unnamed.model().indexes().toString());
        assertEquals(2, unnamed.model().columns().size());

        TableSqlParser.Result multi = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `tag` varchar(32) null,\n"
                + "  index idx_two (`id`, `tag`) using inverted\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(multi.unsupported(), "索引"), multi.unsupported().toString());
        assertTrue(multi.model().indexes().isEmpty(), multi.model().indexes().toString());
    }

    /** 目标列不在字段列表里的「合法形态」索引也不能带回表单（切回表单会 400）。 */
    @Test
    void indexOnMissingColumnGoesToUnsupported() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `index_holder` varchar(32) null,\n"
                + "  index idx_x (`nope`) using bitmap\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(result.unsupported(), "不在字段列表里"), result.unsupported().toString());
        assertTrue(result.model().indexes().isEmpty(), result.model().indexes().toString());
        assertEquals(2, result.model().columns().size());
    }

    @Test
    void unsupportedTypesAndExpressionsAreReported() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `arr` array<int> null,\n"
                + "  `ts` datetime null default current_timestamp()\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(result.unsupported(), "ARRAY<INT>"), result.unsupported().toString());
        assertTrue(has(result.unsupported(), "默认值"), result.unsupported().toString());
        // 字段本身保留，不能静默丢掉
        assertEquals(3, result.model().columns().size());
        assertNull(result.model().columns().get(2).defaultValue());
    }

    @Test
    void maxValueRangeGoesToUnsupported() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `dt` date not null\n"
                + ") engine=olap\n"
                + "duplicate key(`dt`)\n"
                + "partition by range(`dt`) (\n"
                + "  partition `p1` values less than ('2026-01-02'),\n"
                + "  partition `pmax` values less than (MAXVALUE)\n"
                + ")\n"
                + "distributed by hash(`dt`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(result.unsupported(), "MAXVALUE"), result.unsupported().toString());
        // MAXVALUE 那条不能进模型（否则切回表单会变成字符串 'MAXVALUE'）
        assertEquals(1, result.model().partition().ranges().size());
        assertEquals("p1", result.model().partition().ranges().get(0).name());
    }

    @Test
    void randomDistributionAndUnknownTailAreReported() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by random buckets 1\n"
                + "order by (`id`)\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(result.unsupported(), "RANDOM"), result.unsupported().toString());
        assertTrue(has(result.unsupported(), "无法识别"), result.unsupported().toString());
    }

    @Test
    void reservedAndDuplicateNamesAreReported() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null,\n"
                + "  `select` varchar(8) null,\n"
                + "  `id2` bigint null,\n"
                + "  `id2` bigint null\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(result.unsupported(), "保留字"), result.unsupported().toString());
        assertTrue(has(result.unsupported(), "重复"), result.unsupported().toString());
    }

    @Test
    void keyColumnDefaultIsDroppedWithNotice() {
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null default 0,\n"
                + "  `v` int null\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`) buckets 1\n"
                + "properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        assertTrue(has(result.unsupported(), "默认值"), result.unsupported().toString());
        assertNull(result.model().columns().get(0).defaultValue());
    }

    @Test
    void missingBucketsAndReplicationGoToUnsupported() {
        // 省略 BUCKETS / replication_allocation 时 Doris 用的是隐式默认，
        // 表单只能写显式值 → 语义会变，必须进 unsupported 而不是静默取 1
        TableSqlParser.Result result = parse("create table `db1`.`t` (\n"
                + "  `id` bigint not null\n"
                + ") engine=olap\n"
                + "duplicate key(`id`)\n"
                + "distributed by hash(`id`)");
        assertEquals(Integer.valueOf(1), result.model().buckets());
        assertEquals(Integer.valueOf(1), result.model().replication());
        assertTrue(has(result.unsupported(), "BUCKETS"), result.unsupported().toString());
        assertTrue(has(result.unsupported(), "replication_allocation"),
                result.unsupported().toString());
    }

    @Test
    void targetTableMismatchOnlyWarns() {
        TableSqlParser.Result result = TableSqlParser.parse("db1",
                "create table `db1`.`real_t` (`id` bigint not null) engine=olap "
                        + "duplicate key(`id`) distributed by hash(`id`) buckets 1 "
                        + "properties (\"replication_allocation\"=\"tag.location.default: 1\")",
                "form_t");
        assertEquals("real_t", result.table());
        assertTrue(has(result.warnings(), "不一致"), result.warnings().toString());
    }

    @Test
    void emptySqlIsRejected() {
        assertTrue(badRequest(() -> parse("   ")).getMessage().contains("不能为空"));
        assertTrue(badRequest(() -> parse(";;")).getMessage().contains("不能为空"));
    }
}
