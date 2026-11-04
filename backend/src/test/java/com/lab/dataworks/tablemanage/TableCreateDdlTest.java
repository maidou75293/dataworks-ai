package com.lab.dataworks.tablemanage;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表 DDL 生成：分区（RANGE / DYNAMIC / LIST）与 AGGREGATE 模型的单元测试。
 * 预览与建表共用 {@link TableCreateDdl#generate}，这些用例同时约束两边行为。
 */
class TableCreateDdlTest {

    private static TableController.ColumnRequest col(String name, String type) {
        return new TableController.ColumnRequest(name, type, null, null, null, null);
    }

    private static TableController.ColumnRequest col(String name, String type, String aggType) {
        return new TableController.ColumnRequest(name, type, null, null, aggType, null);
    }

    /** 带可空性 / 默认值的列，用于第二批测试。 */
    private static TableController.ColumnRequest col(String name, String type, Boolean nullable,
                                                     String aggType, String defaultValue) {
        return new TableController.ColumnRequest(name, type, null, nullable, aggType, defaultValue);
    }

    private static TableCreateDdl.Result generate(List<TableController.ColumnRequest> columns,
                                                  List<String> keys, String model,
                                                  TableCreateDdl.PartitionDef partition) {
        return TableCreateDdl.generate("db1", "t1", null, columns, keys, 1, 1, model, partition);
    }

    /** 带分桶列 / 存活 BE 数（第二批预检）的入口。 */
    private static TableCreateDdl.Result generate(List<TableController.ColumnRequest> columns,
                                                  List<String> keys, String model,
                                                  TableCreateDdl.PartitionDef partition,
                                                  List<String> distributionColumns,
                                                  Integer aliveBackends) {
        return TableCreateDdl.generate("db1", "t1", null, columns, keys, 1, 1, model, partition,
                distributionColumns, aliveBackends);
    }

    private static TableCreateDdl.PartitionDef part(String mode, List<String> columns,
                                                    List<TableCreateDdl.RangeDef> ranges,
                                                    List<TableCreateDdl.ListDef> lists,
                                                    TableCreateDdl.DynamicDef dynamic) {
        return new TableCreateDdl.PartitionDef(mode, columns, ranges, lists, dynamic);
    }

    private static ApiException badRequest(Runnable action) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(400, e.getStatus(), e.getMessage());
        return e;
    }

    // ------------------------------------------------------------ 向后兼容

    // ------------------------------------------------------------ V98 冷热存储

    private static TableCreateDdl.Result generateStorage(List<TableController.ColumnRequest> columns,
                                                         List<String> keys,
                                                         TableCreateDdl.PartitionDef partition,
                                                         String medium, String cooldown) {
        return TableCreateDdl.generate("db1", "t1", null, columns, keys, 1, 1, "DUPLICATE",
                partition, null, null, medium, cooldown);
    }

    @Test
    void storageMediumDefaultsToClusterDefaultAndAppliesToPartitionedTables() {
        TableCreateDdl.PartitionDef range = part("RANGE", List.of("dt"),
                List.of(new TableCreateDdl.RangeDef("p1", "2026-01-02")), null, null);
        String ddl = generateStorage(
                List.of(col("dt", "DATE"), col("v", "INT")), List.of("dt"), range, null, null).ddl();
        // 未指定介质：不下发 storage_medium，跟随集群磁盘默认
        // （本环境 BE 是 HDD 盘，显式 SSD 会被 Doris 拒绝）
        assertFalse(ddl.contains("storage_medium"), ddl);
        assertFalse(ddl.contains("storage_cooldown_time"), ddl);
    }

    @Test
    void explicitHddAndCooldownAreRendered() {
        String ddl = generateStorage(
                List.of(col("id", "BIGINT")), List.of("id"), null,
                "hdd", "2026-12-31 00:00:00").ddl();
        assertTrue(ddl.contains("\"storage_medium\"=\"HDD\""), ddl);
        assertTrue(ddl.contains("\"storage_cooldown_time\"=\"2026-12-31 00:00:00\""), ddl);
    }

    @Test
    void invalidStorageMediumIsBadRequest() {
        badRequest(() -> generateStorage(
                List.of(col("id", "BIGINT")), List.of("id"), null, "NVME", null));
    }

    @Test
    void noPartitionKeepsLegacyDdl() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT"), col("name", "VARCHAR(64)")),
                List.of("id"), null, null);
        String ddl = result.ddl();
        assertEquals("DUPLICATE", result.model());
        assertEquals(List.of("id"), result.keys());
        assertTrue(ddl.contains("duplicate key(`id`)"), ddl);
        assertFalse(ddl.contains("partition by"), ddl);
        assertTrue(ddl.contains("distributed by hash(`id`) buckets 1"), ddl);
        assertTrue(ddl.contains("\"replication_allocation\"=\"tag.location.default: 1\""), ddl);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void partitionModeNoneAlsoMeansNoPartition() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT")), List.of("id"), null,
                part("NONE", null, null, null, null));
        assertFalse(result.ddl().contains("partition by"), result.ddl());
    }

    // ------------------------------------------------------------ 静态 RANGE

    @Test
    void staticRangeGeneratesLessThanClause() {
        TableCreateDdl.Result result = generate(
                List.of(col("dt", "DATE"), col("id", "BIGINT")),
                List.of("dt", "id"), null,
                part("RANGE", List.of("dt"), List.of(
                        new TableCreateDdl.RangeDef("p20260101", "2026-01-02"),
                        new TableCreateDdl.RangeDef("p20260102", "2026-01-03")),
                        null, null));
        String ddl = result.ddl();
        assertTrue(ddl.contains("partition by range(`dt`) ("), ddl);
        assertTrue(ddl.contains("partition `p20260101` values less than ('2026-01-02')"), ddl);
        assertTrue(ddl.contains("partition `p20260102` values less than ('2026-01-03')"), ddl);
        // 分区子句必须在 distributed by 之前
        assertTrue(ddl.indexOf("partition by") < ddl.indexOf("distributed by"), ddl);
    }

    @Test
    void rangeRequiresAtLeastOneDefinitionAndLessThan() {
        ApiException empty = badRequest(() -> generate(
                List.of(col("dt", "DATE")), List.of("dt"), null,
                part("RANGE", List.of("dt"), List.of(), null, null)));
        assertTrue(empty.getMessage().contains("至少需要 1 个"), empty.getMessage());

        ApiException missing = badRequest(() -> generate(
                List.of(col("dt", "DATE")), List.of("dt"), null,
                part("RANGE", List.of("dt"),
                        List.of(new TableCreateDdl.RangeDef("p1", " ")), null, null)));
        assertTrue(missing.getMessage().contains("lessThan"), missing.getMessage());
    }

    @Test
    void duplicatePartitionNamesRejected() {
        ApiException e = badRequest(() -> generate(
                List.of(col("dt", "DATE")), List.of("dt"), null,
                part("RANGE", List.of("dt"), List.of(
                        new TableCreateDdl.RangeDef("p1", "2026-01-02"),
                        new TableCreateDdl.RangeDef("p1", "2026-01-03")), null, null)));
        assertTrue(e.getMessage().contains("分区名重复"), e.getMessage());
    }

    // ------------------------------------------------------------ LIST

    @Test
    void listGeneratesValuesInClause() {
        TableCreateDdl.Result result = generate(
                List.of(col("region", "VARCHAR(32)"), col("id", "BIGINT")),
                List.of("region", "id"), null,
                part("LIST", List.of("region"), null, List.of(
                        new TableCreateDdl.ListDef("p_east", List.of("east", "north")),
                        new TableCreateDdl.ListDef("p_west", List.of("west"))), null));
        String ddl = result.ddl();
        assertTrue(ddl.contains("partition by list(`region`) ("), ddl);
        assertTrue(ddl.contains("partition `p_east` values in ('east', 'north')"), ddl);
    }

    @Test
    void listOnlySupportsSingleColumn() {
        ApiException e = badRequest(() -> generate(
                List.of(col("dt", "DATE"), col("region", "VARCHAR(32)")),
                List.of("dt", "region"), null,
                part("LIST", List.of("dt", "region"), null,
                        List.of(new TableCreateDdl.ListDef("p1", List.of("a"))), null)));
        assertTrue(e.getMessage().contains("单列"), e.getMessage());
    }

    @Test
    void listRequiresAtLeastOneDefinitionAndValues() {
        badRequest(() -> generate(List.of(col("region", "VARCHAR(32)")), List.of("region"),
                null, part("LIST", List.of("region"), null, List.of(), null)));

        ApiException empty = badRequest(() -> generate(
                List.of(col("region", "VARCHAR(32)")), List.of("region"), null,
                part("LIST", List.of("region"), null,
                        List.of(new TableCreateDdl.ListDef("p1", List.of())), null)));
        assertTrue(empty.getMessage().contains("values"), empty.getMessage());
    }

    // ------------------------------------------------------------ 动态分区

    @Test
    void dynamicGeneratesEmptyRangeAndProperties() {
        TableCreateDdl.Result result = generate(
                List.of(col("dt", "DATE"), col("id", "BIGINT")),
                List.of("dt", "id"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("day", "-7", "3", "p", 2, true)));
        String ddl = result.ddl();
        assertTrue(ddl.contains("partition by range(`dt`) ()"), ddl);
        assertTrue(ddl.contains("\"dynamic_partition.enable\"=\"true\""), ddl);
        assertTrue(ddl.contains("\"dynamic_partition.time_unit\"=\"DAY\""), ddl);
        assertTrue(ddl.contains("\"dynamic_partition.start\"=\"-7\""), ddl);
        assertTrue(ddl.contains("\"dynamic_partition.end\"=\"3\""), ddl);
        assertTrue(ddl.contains("\"dynamic_partition.prefix\"=\"p\""), ddl);
        assertTrue(ddl.contains("\"dynamic_partition.buckets\"=\"2\""), ddl);
        assertTrue(ddl.contains("\"dynamic_partition.create_history_partition\"=\"true\""), ddl);
        // 保留副本数属性
        assertTrue(ddl.contains("\"replication_allocation\""), ddl);
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void dynamicBucketsOmittedWhenNotProvided() {
        TableCreateDdl.Result result = generate(
                List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("DAY", "-1", "1", "p", null, null)));
        assertFalse(result.ddl().contains("dynamic_partition.buckets"), result.ddl());
    }

    @Test
    void dynamicParameterValidation() {
        TableCreateDdl.DynamicDef ok = new TableCreateDdl.DynamicDef(
                "DAY", "-7", "3", "p", null, null);

        assertTrue(badRequest(() -> generate(List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null, null)))
                .getMessage().contains("dynamic"));

        assertTrue(badRequest(() -> generate(List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("MINUTE", "-7", "3", "p", null, null))))
                .getMessage().contains("timeUnit"));

        assertTrue(badRequest(() -> generate(List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("DAY", "1", "3", "p", null, null))))
                .getMessage().contains("start"));

        assertTrue(badRequest(() -> generate(List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("DAY", "-7", "-3", "p", null, null))))
                .getMessage().contains("end"));

        assertTrue(badRequest(() -> generate(List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("DAY", "-7", "3", " ", null, null))))
                .getMessage().contains("prefix"));

        assertTrue(badRequest(() -> generate(List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("DAY", "abc", "3", "p", null, null))))
                .getMessage().contains("整数"));

        // 引用 ok 避免 IDE 提示未使用
        assertEquals("DAY", ok.timeUnit());
    }

    @Test
    void dynamicOnNonDateColumnOnlyWarns() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT")), List.of("id"), null,
                part("DYNAMIC", List.of("id"), null, null,
                        new TableCreateDdl.DynamicDef("DAY", "-7", "3", "p", null, null)));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("DATE")),
                result.warnings().toString());
    }

    // ------------------------------------------------------------ 公共校验

    @Test
    void partitionColumnMustBeKeyColumn() {
        ApiException e = badRequest(() -> generate(
                List.of(col("dt", "DATE"), col("id", "BIGINT")), List.of("id"), null,
                part("RANGE", List.of("dt"), List.of(
                        new TableCreateDdl.RangeDef("p1", "2026-01-02")), null, null)));
        assertTrue(e.getMessage().contains("key 列"), e.getMessage());
    }

    @Test
    void partitionColumnMustExist() {
        ApiException e = badRequest(() -> generate(
                List.of(col("id", "BIGINT")), List.of("id"), null,
                part("RANGE", List.of("dt"), List.of(
                        new TableCreateDdl.RangeDef("p1", "2026-01-02")), null, null)));
        assertTrue(e.getMessage().contains("分区列不存在"), e.getMessage());
    }

    @Test
    void partitionRequiresColumns() {
        ApiException e = badRequest(() -> generate(
                List.of(col("id", "BIGINT")), List.of("id"), null,
                part("RANGE", List.of(), List.of(
                        new TableCreateDdl.RangeDef("p1", "2026-01-02")), null, null)));
        assertTrue(e.getMessage().contains("分区列"), e.getMessage());
    }

    @Test
    void unknownPartitionModeRejected() {
        badRequest(() -> generate(List.of(col("id", "BIGINT")), List.of("id"), null,
                part("HASH", List.of("id"), null, null, null)));
    }

    // ------------------------------------------------------------ AGGREGATE

    @Test
    void aggregateModelAcceptedWithDefaultReplace() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT"), col("amount", "BIGINT"), col("cnt", "BIGINT", "SUM")),
                List.of("id"), "aggregate", null);
        String ddl = result.ddl();
        assertEquals("AGGREGATE", result.model());
        assertTrue(ddl.contains("aggregate key(`id`)"), ddl);
        // key 列不允许写聚合类型
        assertFalse(ddl.contains("`id` BIGINT REPLACE"), ddl);
        assertTrue(ddl.contains("`amount` BIGINT REPLACE"), ddl);
        assertTrue(ddl.contains("`cnt` BIGINT SUM"), ddl);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("amount")
                && w.contains("REPLACE")), result.warnings().toString());
    }

    @Test
    void aggregateRejectsUnknownAggType() {
        ApiException e = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("v", "BIGINT", "AVG")), List.of("id"),
                "AGGREGATE", null));
        assertTrue(e.getMessage().contains("聚合类型"), e.getMessage());
    }

    @Test
    void aggTypeIgnoredForNonAggregateModelWithWarning() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT"), col("v", "BIGINT", "SUM")), List.of("id"),
                "UNIQUE", null);
        assertFalse(result.ddl().contains("SUM"), result.ddl());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("忽略")),
                result.warnings().toString());
    }

    @Test
    void unknownModelRejected() {
        badRequest(() -> generate(List.of(col("id", "BIGINT")), List.of("id"), "MERGE", null));
    }

    @Test
    void keyColumnsAreReorderedFirst() {
        TableCreateDdl.Result result = generate(
                List.of(col("name", "VARCHAR(64)"), col("id", "BIGINT")), List.of("id"),
                null, null);
        assertTrue(result.ddl().indexOf("`id` BIGINT") < result.ddl().indexOf("`name` VARCHAR(64)"),
                result.ddl());
    }

    // ------------------------------------------------------------ 列级属性（第二批）

    @Test
    void nullabilityIsExplicitInDdl() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT", true, null, null),
                        col("note", "VARCHAR(64)", true, null, null),
                        col("fixed", "VARCHAR(64)", false, null, null)),
                List.of("id"), null, null);
        String ddl = result.ddl();
        // key 列 Doris 强制非空
        assertTrue(ddl.contains("`id` BIGINT not null"), ddl);
        assertTrue(ddl.contains("`note` VARCHAR(64) null"), ddl);
        assertTrue(ddl.contains("`fixed` VARCHAR(64) not null"), ddl);

        // 老请求不带 nullable：仍按可空处理，行为不变
        TableCreateDdl.Result legacy = generate(
                List.of(col("id", "BIGINT"), col("name", "VARCHAR(64)")), List.of("id"),
                null, null);
        assertTrue(legacy.ddl().contains("`name` VARCHAR(64) null"), legacy.ddl());
    }

    @Test
    void numericAndBooleanDefaultsWrittenWithoutQuotes() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT"),
                        col("amount", "BIGINT", true, "SUM", "100"),
                        col("rate", "DECIMAL(10,2)", true, null, "1.25"),
                        col("ratio", "DOUBLE", true, null, "1e3"),
                        col("flag", "BOOLEAN", true, null, "TRUE")),
                List.of("id"), "AGGREGATE", null);
        String ddl = result.ddl();
        assertTrue(ddl.contains("`amount` BIGINT SUM null default 100"), ddl);
        // AGGREGATE 模型下未指定聚合类型的 value 列默认 REPLACE
        assertTrue(ddl.contains("`rate` DECIMAL(10,2) REPLACE null default 1.25"), ddl);
        assertTrue(ddl.contains("`ratio` DOUBLE REPLACE null default 1e3"), ddl);
        assertTrue(ddl.contains("`flag` BOOLEAN REPLACE null default true"), ddl);
    }

    @Test
    void stringAndDateDefaultsQuotedAndEscaped() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT"),
                        col("name", "VARCHAR(64)", true, null, "O'Brien"),
                        col("dt", "DATE", true, null, "2026-01-01")),
                List.of("id"), null, null);
        String ddl = result.ddl();
        assertTrue(ddl.contains("`name` VARCHAR(64) null default 'O''Brien'"), ddl);
        assertTrue(ddl.contains("`dt` DATE null default '2026-01-01'"), ddl);
    }

    @Test
    void defaultValueRejectedOnKeyColumn() {
        ApiException e = badRequest(() -> generate(
                List.of(col("id", "BIGINT", true, null, "1")), List.of("id"), null, null));
        assertTrue(e.getMessage().contains("key 列"), e.getMessage());
        assertTrue(e.getMessage().contains("默认值"), e.getMessage());
    }

    @Test
    void invalidDefaultLiteralRejected() {
        ApiException numeric = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("amount", "BIGINT", true, null, "abc")),
                List.of("id"), null, null));
        assertTrue(numeric.getMessage().contains("整数"), numeric.getMessage());

        ApiException bool = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("flag", "BOOLEAN", true, null, "1")),
                List.of("id"), null, null));
        assertTrue(bool.getMessage().contains("true/false"), bool.getMessage());
    }

    // ------------------------------------------------------------ 预检 4：类型白名单

    @Test
    void varcharLengthBounds() {
        // 边界内 + 裸 VARCHAR（缺省长度）都放行
        generate(List.of(col("id", "BIGINT"), col("v", "VARCHAR(65533)")), List.of("id"),
                null, null);
        generate(List.of(col("id", "BIGINT"), col("v", "VARCHAR")), List.of("id"), null, null);

        ApiException zero = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("v", "VARCHAR(0)")), List.of("id"), null, null));
        assertTrue(zero.getMessage().contains("65533"), zero.getMessage());

        ApiException tooLong = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("v", "VARCHAR(65534)")), List.of("id"),
                null, null));
        assertTrue(tooLong.getMessage().contains("65533"), tooLong.getMessage());

        ApiException unknown = badRequest(() -> generate(
                List.of(col("id", "BLOB")), List.of("id"), null, null));
        assertTrue(unknown.getMessage().contains("不支持的字段类型"), unknown.getMessage());
    }

    @Test
    void decimalPrecisionAndScaleValidated() {
        // 上限 38 位放行
        generate(List.of(col("id", "BIGINT"), col("v", "DECIMAL(38,10)")), List.of("id"),
                null, null);

        ApiException precision = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("v", "DECIMAL(39,2)")), List.of("id"),
                null, null));
        assertTrue(precision.getMessage().contains("精度"), precision.getMessage());

        ApiException scale = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("v", "DECIMAL(5,6)")), List.of("id"),
                null, null));
        assertTrue(scale.getMessage().contains("小数位"), scale.getMessage());
    }

    // ------------------------------------------------------------ 预检 6：保留字

    @Test
    void reservedWordsRejected() {
        ApiException table = badRequest(() -> TableCreateDdl.generate("db1", "select", null,
                List.of(col("id", "BIGINT")), List.of("id"), 1, 1, null, null));
        assertTrue(table.getMessage().contains("表名"), table.getMessage());
        assertTrue(table.getMessage().contains("保留字"), table.getMessage());

        ApiException column = badRequest(() -> generate(
                List.of(col("id", "BIGINT"), col("order", "INT")), List.of("id"), null, null));
        assertTrue(column.getMessage().contains("字段名"), column.getMessage());
        assertTrue(column.getMessage().contains("保留字"), column.getMessage());

        // 加下划线后放行
        generate(List.of(col("id", "BIGINT"), col("order_id", "INT")), List.of("id"), null, null);
    }

    // ------------------------------------------------------------ 预检 2：分桶列

    @Test
    void distributionColumnsDefaultToFirstKey() {
        TableCreateDdl.Result result = generate(
                List.of(col("dt", "DATE"), col("id", "BIGINT")), List.of("dt", "id"), null, null);
        assertTrue(result.ddl().contains("distributed by hash(`dt`) buckets 1"), result.ddl());
    }

    @Test
    void distributionColumnsMustBeKeyColumns() {
        List<TableController.ColumnRequest> columns = List.of(
                col("dt", "DATE"), col("id", "BIGINT"), col("amount", "BIGINT"));
        List<String> keys = List.of("dt", "id");

        TableCreateDdl.Result single = generate(columns, keys, null, null, List.of("id"), null);
        assertTrue(single.ddl().contains("distributed by hash(`id`) buckets 1"), single.ddl());

        TableCreateDdl.Result multi = generate(columns, keys, null, null,
                List.of("dt", "id"), null);
        assertTrue(multi.ddl().contains("distributed by hash(`dt`, `id`) buckets 1"),
                multi.ddl());

        ApiException notKey = badRequest(() -> generate(columns, keys, null, null,
                List.of("amount"), null));
        assertTrue(notKey.getMessage().contains("必须是 key 列"), notKey.getMessage());

        ApiException missing = badRequest(() -> generate(columns, keys, null, null,
                List.of("nope"), null));
        assertTrue(missing.getMessage().contains("分桶列不存在"), missing.getMessage());

        ApiException duplicate = badRequest(() -> generate(columns, keys, null, null,
                List.of("dt", "dt"), null));
        assertTrue(duplicate.getMessage().contains("分桶列重复"), duplicate.getMessage());
    }

    // ------------------------------------------------------------ 预检 1：副本数 vs BE

    @Test
    void replicationCannotExceedAliveBackends() {
        List<TableController.ColumnRequest> columns = List.of(col("id", "BIGINT"));

        // 副本数 = 存活 BE 数：放行
        TableCreateDdl.Result ok = TableCreateDdl.generate("db1", "t1", null, columns,
                List.of("id"), 1, 1, null, null, null, 1);
        assertTrue(ok.ddl().contains("tag.location.default: 1"), ok.ddl());

        // 副本数 > 存活 BE 数：400
        ApiException e = badRequest(() -> TableCreateDdl.generate("db1", "t1", null, columns,
                List.of("id"), 1, 3, null, null, null, 1));
        assertTrue(e.getMessage().contains("存活 BE 数"), e.getMessage());

        // 监控查询失败（aliveBackends = null）：跳过检查，不阻断建表
        TableCreateDdl.Result skipped = TableCreateDdl.generate("db1", "t1", null, columns,
                List.of("id"), 1, 3, null, null, null, null);
        assertTrue(skipped.ddl().contains("tag.location.default: 3"), skipped.ddl());
    }

    // ------------------------------------------------------------ 预检 3 / 7 补充

    @Test
    void aggregateKeyColumnAggTypeOnlyWarns() {
        TableCreateDdl.Result result = generate(
                List.of(col("id", "BIGINT", true, "SUM", null),
                        col("v", "BIGINT", true, null, null)),
                List.of("id"), "AGGREGATE", null);
        assertFalse(result.ddl().contains("`id` BIGINT SUM"), result.ddl());
        assertTrue(result.ddl().contains("`v` BIGINT REPLACE null"), result.ddl());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("key 列")),
                result.warnings().toString());
    }

    @Test
    void dynamicParametersMustBeProvidedTogether() {
        ApiException e = badRequest(() -> generate(
                List.of(col("dt", "DATE")), List.of("dt"), null,
                part("DYNAMIC", List.of("dt"), null, null,
                        new TableCreateDdl.DynamicDef("DAY", null, "3", "p", null, null))));
        assertTrue(e.getMessage().contains("start"), e.getMessage());
    }

    // ------------------------------------------------------------ REQ-DW-6 二级索引

    /** 15 参完整入口只测 indexes 这一段，其余参数一律缺省。 */
    private static TableCreateDdl.Result withIndexes(List<TableController.IndexRequest> indexes) {
        return TableCreateDdl.generate("db1", "t1", null,
                List.of(col("id", "BIGINT"), col("tag", "VARCHAR(32)")),
                List.of("id"), 1, 1, "DUPLICATE", null, null, null, null, null, null, indexes);
    }

    private static TableController.IndexRequest idx(String name, String column, String type) {
        return new TableController.IndexRequest(name, column, type, null);
    }

    @Test
    void secondaryIndexesEmitDorisIndexRows() {
        TableCreateDdl.Result result = withIndexes(List.of(
                idx("idx_tag", "tag", null),   // 类型留空 = BITMAP
                new TableController.IndexRequest("idx_inv", "tag", "INVERTED", "倒排检索")));
        String ddl = result.ddl();
        // 索引行之间用逗号分隔，最后一行不带逗号（与字段行为一致）
        assertTrue(ddl.contains("  index `idx_tag` (`tag`) using bitmap,\n"), ddl);
        assertTrue(ddl.contains("index `idx_inv` (`tag`) using inverted comment '倒排检索'\n) engine=olap"), ddl);
        // 索引行在最后一个字段之后、) engine=olap 之前；最后一个字段要带逗号收尾
        assertTrue(ddl.indexOf("index `idx_tag`") > ddl.indexOf("`tag` VARCHAR(32)"), ddl);
        assertTrue(ddl.indexOf("index `idx_tag`") < ddl.indexOf(") engine=olap"), ddl);
        assertTrue(ddl.contains("`tag` VARCHAR(32) null,\n  index"), ddl);
    }

    @Test
    void emptyIndexListKeepsLegacyDdl() {
        TableCreateDdl.Result result = withIndexes(List.of());
        assertFalse(result.ddl().contains("index"), result.ddl());
        // 无索引时最后一列不带尾逗号（老行为不能被索引改造碰坏）
        assertTrue(result.ddl().contains("`tag` VARCHAR(32) null\n) engine=olap"), result.ddl());
    }

    @Test
    void indexTargetColumnMustExist() {
        ApiException e = badRequest(() -> withIndexes(List.of(idx("idx_x", "nope", "BITMAP"))));
        assertTrue(e.getMessage().contains("目标列不存在"), e.getMessage());
    }

    @Test
    void bloomFilterIsNotATableIndex() {
        // BloomFilter 是表属性（bloom_filter_columns），不是 CREATE INDEX 形态——文案要讲清去向
        ApiException e = badRequest(() -> withIndexes(List.of(idx("idx_b", "tag", "BLOOMFILTER"))));
        assertTrue(e.getMessage().contains("bloom_filter_columns"), e.getMessage());
    }

    @Test
    void unknownIndexTypeRejected() {
        ApiException e = badRequest(() -> withIndexes(List.of(idx("idx_f", "tag", "FULLTEXT"))));
        assertTrue(e.getMessage().contains("NGRAM_BF"), e.getMessage());
    }

    @Test
    void duplicateIndexNameRejected() {
        ApiException e = badRequest(() -> withIndexes(
                List.of(idx("idx_tag", "tag", "BITMAP"), idx("idx_tag", "id", "INVERTED"))));
        assertTrue(e.getMessage().contains("索引名重复"), e.getMessage());
    }
}
