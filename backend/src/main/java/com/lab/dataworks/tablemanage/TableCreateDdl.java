package com.lab.dataworks.tablemanage;

import com.lab.dataworks.common.ApiException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 建表 DDL 生成与校验（第一批：分区 + AGGREGATE 模型；第二批：列级默认值 + 7 条预检）。
 *
 * <p>{@code POST /api/tables} 与 {@code POST /api/tables/preview} 共用本类，
 * 保证预览出来的 DDL 与真正执行的完全一致。</p>
 *
 * <p>本类只做纯字符串生成与参数校验，不接触数据库；所有校验失败都抛 400，
 * 文案直接指出具体字段/参数，便于前端原样展示。唯一的外部输入是存活 BE 数，
 * 由 controller 查询后传入，查询失败传 null 跳过副本数预检。</p>
 */
public final class TableCreateDdl {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    private static final Pattern TYPE = Pattern.compile(
            "(?i)^(BOOLEAN|TINYINT|SMALLINT|INT|BIGINT|LARGEINT|FLOAT|DOUBLE|DATE|"
                    + "DATETIME|DATETIME\\([0-6]\\)|STRING|TEXT|"
                    + "VARCHAR(?:\\(\\d{1,5}\\))?|DECIMAL\\(\\d{1,2},\\d{1,2}\\)|JSON)$");
    private static final Pattern VARCHAR = Pattern.compile("(?i)^VARCHAR(?:\\((\\d{1,5})\\))?$");
    private static final Pattern DECIMAL = Pattern.compile(
            "(?i)^DECIMAL\\((\\d{1,2}),(\\d{1,2})\\)$");
    private static final Pattern INTEGER_VALUE = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern DECIMAL_VALUE = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$");
    private static final Pattern FLOAT_VALUE = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$");
    private static final Pattern PREFIX = Pattern.compile("^[a-zA-Z0-9_]{1,32}$");

    private static final Set<String> MODELS = Set.of("DUPLICATE", "UNIQUE", "AGGREGATE");
    private static final Set<String> AGG_TYPES = Set.of("REPLACE", "REPLACE_IF_NOT_NULL", "SUM",
            "MIN", "MAX", "HLL_UNION", "BITMAP_UNION", "QUANTILE_UNION");
    private static final Set<String> MODES = Set.of("NONE", "RANGE", "DYNAMIC", "LIST");
    private static final Set<String> TIME_UNITS = Set.of("HOUR", "DAY", "WEEK", "MONTH", "YEAR");
    private static final Set<String> INTEGER_TYPES = Set.of("TINYINT", "SMALLINT", "INT", "BIGINT",
            "LARGEINT");
    private static final Set<String> FLOAT_TYPES = Set.of("FLOAT", "DOUBLE");
    /** 冷热分层：SSD=热、HDD=冷；缺省 SSD。 */
    private static final Set<String> STORAGE_MEDIA = Set.of("SSD", "HDD");
    /**
     * REQ-DW-6：建表二级索引类型白名单。BloomFilter 不在其中——它不是
     * {@code CREATE TABLE INDEX} 的索引类型，而是表属性 {@code bloom_filter_columns}，
     * 混在一起会让用户以为建了索引实际却没有。
     */
    private static final Set<String> INDEX_TYPES = Set.of("BITMAP", "INVERTED", "NGRAM_BF");
    private static final Pattern STORAGE_COOLDOWN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");

    /**
     * 常用 Doris/MySQL 保留字。标识符虽然会加反引号，但用户直接写这些名字时
     * 在 SQL / 外部工具链里容易被误解析，这里统一拦下并提示改名。
     */
    private static final Set<String> RESERVED = Set.of(
            "select", "from", "where", "group", "having", "order", "by", "limit", "offset",
            "join", "inner", "outer", "left", "right", "full", "cross", "on", "using",
            "union", "all", "distinct", "as", "and", "or", "xor", "not", "null", "is", "in",
            "like", "rlike", "between", "exists", "case", "when", "then", "else", "end",
            "asc", "desc", "insert", "update", "delete", "create", "drop", "alter", "truncate",
            "into", "values", "set", "show", "describe", "explain", "use", "table", "database",
            "schema", "column", "index", "primary", "key", "foreign", "references", "unique",
            "constraint", "check", "default", "partition", "partitions", "temporary", "view",
            "grant", "revoke", "lock", "unlock");

    private TableCreateDdl() {
    }

    // ---------------------------------------------------------------- 请求体结构

    /** 单个静态 RANGE 分区：lessThan 生成 VALUES LESS THAN ('...')。 */
    public record RangeDef(String name, String lessThan) {
    }

    /** 单个 LIST 分区：values 生成 VALUES IN (...)。 */
    public record ListDef(String name, List<String> values) {
    }

    /** 动态分区参数：Doris 走 RANGE + 空分区体 + properties。 */
    public record DynamicDef(String timeUnit, String start, String end, String prefix,
                             Integer buckets, Boolean createHistory) {
    }

    /**
     * 分区定义。字段缺省（partition 为 null 或 mode 为空/NONE）= 不分区，
     * 与老请求体行为保持一致。
     */
    public record PartitionDef(String mode, List<String> columns, List<RangeDef> ranges,
                               List<ListDef> lists, DynamicDef dynamic) {
    }

    /** 生成结果：ddl 给预览/执行，warnings 给前端提示（不阻断建表）。 */
    public record Result(String ddl, List<String> warnings, List<String> keys,
                         String model, int columnCount) {
    }

    // ---------------------------------------------------------------- 生成入口

    /** 向后兼容入口：不带分桶列 / 存活 BE 数 / 冷热存储（副本数预检自动跳过）。 */
    public static Result generate(String database, String table, String comment,
                                  List<TableController.ColumnRequest> columns, List<String> keys,
                                  Integer buckets, Integer replication, String model,
                                  PartitionDef partition) {
        return generate(database, table, comment, columns, keys, buckets, replication, model,
                partition, null, null, null, null);
    }

    /** 向后兼容入口：不带冷热存储（storageMedium 缺省 SSD）。 */
    public static Result generate(String database, String table, String comment,
                                  List<TableController.ColumnRequest> columns, List<String> keys,
                                  Integer buckets, Integer replication, String model,
                                  PartitionDef partition, List<String> distributionColumns,
                                  Integer aliveBackends) {
        return generate(database, table, comment, columns, keys, buckets, replication, model,
                partition, distributionColumns, aliveBackends, null, null);
    }

    /**
     * 完整入口（第二批：列级默认值 + 7 条预检；V98：表级冷热存储）。
     *
     * <p>存储属性写在表级 PROPERTIES 上，静态/动态分区的分区属性会自动继承表级设置。</p>
     *
     * @param distributionColumns 分桶列，缺省 = 第一个 key 列
     * @param aliveBackends       当前 Doris 存活 BE 数；null 表示监控查询失败，跳过副本数预检
     * @param storageMedium       SSD / HDD，缺省 SSD
     * @param storageCooldownTime 可选，格式 yyyy-MM-dd HH:mm:ss；为空则不写该属性
     */
    public static Result generate(String database, String table, String comment,
                                  List<TableController.ColumnRequest> columns, List<String> keys,
                                  Integer buckets, Integer replication, String model,
                                  PartitionDef partition, List<String> distributionColumns,
                                  Integer aliveBackends, String storageMedium,
                                  String storageCooldownTime) {
        return generate(database, table, comment, columns, keys, buckets, replication, model,
                partition, distributionColumns, aliveBackends, storageMedium,
                storageCooldownTime, null);
    }

    /**
     * 025：多一个 coldStorageAvailable（所选数仓数据源是否配置了冷存储）。
     * false 时禁止 HDD；null 表示未知（旧调用），不做拦截。
     */
    public static Result generate(String database, String table, String comment,
                                  List<TableController.ColumnRequest> columns, List<String> keys,
                                  Integer buckets, Integer replication, String model,
                                  PartitionDef partition, List<String> distributionColumns,
                                  Integer aliveBackends, String storageMedium,
                                  String storageCooldownTime, Boolean coldStorageAvailable) {
        return generate(database, table, comment, columns, keys, buckets, replication, model,
                partition, distributionColumns, aliveBackends, storageMedium,
                storageCooldownTime, coldStorageAvailable, null);
    }

    /**
     * REQ-DW-6 完整入口：多一个 {@code indexes}（字段列表内的二级索引）。
     * null / 空数组 = 不输出任何 index 行，老请求体行为完全不变。
     */
    public static Result generate(String database, String table, String comment,
                                  List<TableController.ColumnRequest> columns, List<String> keys,
                                  Integer buckets, Integer replication, String model,
                                  PartitionDef partition, List<String> distributionColumns,
                                  Integer aliveBackends, String storageMedium,
                                  String storageCooldownTime, Boolean coldStorageAvailable,
                                  List<TableController.IndexRequest> indexes) {
        List<String> warnings = new ArrayList<>();
        if (columns == null || columns.isEmpty()) {
            throw ApiException.badRequest("至少需要一个字段");
        }
        if (columns.size() > 300) {
            throw ApiException.badRequest("字段数超过 300");
        }
        // 表名同样要做保留字检查（列名检查在下面的循环里）
        String tableName = requireIdentifier(table, "表名");

        // 数据模型：DUPLICATE / UNIQUE / AGGREGATE（AGGREGATE 的列级 aggType 可选，
        // value 列缺省 REPLACE 并给 warning，key 列不写聚合类型）
        String normalizedModel = model == null || model.isBlank()
                ? "DUPLICATE" : model.trim().toUpperCase();
        if (!MODELS.contains(normalizedModel)) {
            throw ApiException.badRequest("数据模型只支持 DUPLICATE / UNIQUE / AGGREGATE");
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (TableController.ColumnRequest column : columns) {
            if (column == null) {
                throw ApiException.badRequest("字段不能为空");
            }
            String name = requireIdentifier(column.name(), "字段名");
            if (!names.add(name)) {
                throw ApiException.badRequest("字段名重复: " + name);
            }
            checkType(column.type(), name);
            if (column.aggType() != null && !column.aggType().isBlank()
                    && !AGG_TYPES.contains(column.aggType().trim().toUpperCase())) {
                throw ApiException.badRequest("不支持的聚合类型: " + column.aggType());
            }
        }

        List<String> normalizedKeys = keys == null ? List.of() : keys;
        if (normalizedKeys.isEmpty()) {
            normalizedKeys = List.of(columns.get(0).name());
        }
        for (String key : normalizedKeys) {
            if (!names.contains(key)) {
                throw ApiException.badRequest("主键字段不存在: " + key);
            }
        }

        // REQ-DW-6：二级索引预检（索引名合法且不重复、目标列必须存在、类型白名单）
        List<TableController.IndexRequest> normalizedIndexes = normalizeIndexes(indexes, names);

        // 预检 2：分桶列必须在 key 列里；不传时默认第一个 key 列（第一批行为）
        List<String> normalizedDistribution = normalizeDistribution(distributionColumns,
                normalizedKeys, names);

        int normalizedBuckets = buckets == null ? 1 : buckets;
        if (normalizedBuckets < 1 || normalizedBuckets > 64) {
            throw ApiException.badRequest("分桶数需在 1-64");
        }
        int normalizedReplication = replication == null ? 1 : replication;
        // 硬上限放到 10，真正的约束是「≤ 存活 BE 数」（预检 1）。
        // 以前写死 1-3，BE 有 3 台时永远触发不到「副本数超过 BE」这条预检。
        if (normalizedReplication < 1 || normalizedReplication > 10) {
            throw ApiException.badRequest("副本数需在 1-10");
        }
        // 预检 1：副本数不能超过当前存活 BE 数；aliveBackends 为 null 表示监控查询失败，跳过
        if (aliveBackends != null && normalizedReplication > aliveBackends) {
            throw ApiException.badRequest("副本数 " + normalizedReplication
                    + " 超过当前 Doris 存活 BE 数 " + aliveBackends
                    + "，请调低副本数或先恢复 BE 节点");
        }
        // 冷热分层：storage_medium 仅在显式指定时下发；不指定则跟随 Doris 集群磁盘默认。
        // 本环境 BE 磁盘是 HDD，显式 SSD 会被 Doris 拒绝（Failed to find enough backend）。
        String normalizedMedium = normalizeMedium(storageMedium);
        if ("HDD".equals(normalizedMedium) && Boolean.FALSE.equals(coldStorageAvailable)) {
            throw ApiException.badRequest(
                    "所选数仓数据源未配置冷存储（S3/HDFS），不能选择 HDD；请先在数据源中配置默认存储");
        }
        String normalizedCooldown = normalizeCooldown(storageCooldownTime);

        // 列默认值：key 列不允许（Doris sort key 不能带默认值）；
        // 数值/布尔按字面量写，字符串与日期加单引号并转义
        Map<String, String> defaultLiterals = new LinkedHashMap<>();
        for (TableController.ColumnRequest column : columns) {
            String literal = defaultLiteral(column, normalizedKeys.contains(column.name()));
            if (literal != null) {
                defaultLiterals.put(column.name(), literal);
            }
        }

        // Doris 要求 key 列在最前面且非空；按用户选择重排，避免建表失败
        List<TableController.ColumnRequest> ordered = new ArrayList<>();
        for (String key : normalizedKeys) {
            ordered.add(findColumn(columns, key));
        }
        for (TableController.ColumnRequest column : columns) {
            if (!normalizedKeys.contains(column.name())) {
                ordered.add(column);
            }
        }

        // 分区子句 + 动态分区 properties；返回 null 表示不分区
        PartitionPlan partitionPlan = buildPartition(columns, names, normalizedKeys, partition,
                warnings);

        StringBuilder ddl = new StringBuilder();
        ddl.append("create table `").append(database).append("`.`").append(tableName).append("` (\n");
        for (int i = 0; i < ordered.size(); i++) {
            TableController.ColumnRequest column = ordered.get(i);
            boolean isKey = normalizedKeys.contains(column.name());
            ddl.append("  `").append(column.name()).append("` ").append(column.type().trim());
            if ("AGGREGATE".equals(normalizedModel) && !isKey) {
                // AGGREGATE 表：key 列不能带聚合类型，value 列缺省 REPLACE（上面已记 warning）
                String aggType = column.aggType() == null || column.aggType().isBlank()
                        ? "REPLACE" : column.aggType().trim().toUpperCase();
                ddl.append(" ").append(aggType);
            } else if (!isKey && column.aggType() != null && !column.aggType().isBlank()) {
                warnings.add("字段 `" + column.name()
                        + "` 的聚合类型仅在 AGGREGATE 模型生效，当前模型已忽略");
            } else if (isKey && column.aggType() != null && !column.aggType().isBlank()) {
                // 预检 3：key 列不能写聚合类型
                warnings.add("字段 `" + column.name() + "` 是 key 列，聚合类型已忽略");
            }
            // 可空性必须显式落到 DDL；key 列 Doris 强制非空
            if (isKey || Boolean.FALSE.equals(column.nullable())) {
                ddl.append(" not null");
            } else {
                ddl.append(" null");
            }
            String defaultLiteral = defaultLiterals.get(column.name());
            if (defaultLiteral != null) {
                ddl.append(" default ").append(defaultLiteral);
            }
            if (column.comment() != null && !column.comment().isBlank()) {
                ddl.append(" comment '").append(escape(column.comment())).append("'");
            }
            if (i < ordered.size() - 1 || !normalizedIndexes.isEmpty()) {
                ddl.append(",");
            }
            ddl.append("\n");
        }
        for (int i = 0; i < normalizedIndexes.size(); i++) {
            TableController.IndexRequest index = normalizedIndexes.get(i);
            ddl.append("  index `").append(index.name()).append("` (`")
                    .append(index.column()).append("`) using ")
                    .append(index.type().toLowerCase(Locale.ROOT));
            if (index.comment() != null && !index.comment().isBlank()) {
                ddl.append(" comment '").append(escape(index.comment())).append("'");
            }
            if (i < normalizedIndexes.size() - 1) {
                ddl.append(",");
            }
            ddl.append("\n");
        }
        ddl.append(") engine=olap\n");
        ddl.append(normalizedModel.toLowerCase()).append(" key(")
                .append(normalizedKeys.stream().map(k -> "`" + k + "`")
                        .reduce((a, b) -> a + ", " + b).orElse("")).append(")\n");
        if (comment != null && !comment.isBlank()) {
            ddl.append("comment '").append(escape(comment)).append("'\n");
        }
        if (partitionPlan.clause() != null) {
            ddl.append(partitionPlan.clause());
        }
        ddl.append("distributed by hash(")
                .append(normalizedDistribution.stream().map(c -> "`" + c + "`")
                        .reduce((a, b) -> a + ", " + b).orElse(""))
                .append(") buckets ").append(normalizedBuckets).append("\n");

        List<String> properties = new ArrayList<>();
        properties.add("\"replication_allocation\"=\"tag.location.default: "
                + normalizedReplication + "\"");
        // 表级存储属性：所有分区（含动态分区）继承这两个设置
        if (normalizedMedium != null) {
            properties.add("\"storage_medium\"=\"" + normalizedMedium + "\"");
        }
        if (normalizedCooldown != null && normalizedMedium != null) {
            properties.add("\"storage_cooldown_time\"=\"" + normalizedCooldown + "\"");
        }
        properties.addAll(partitionPlan.properties());
        ddl.append("properties (").append(String.join(", ", properties)).append(")");

        if ("AGGREGATE".equals(normalizedModel)) {
            List<String> missing = new ArrayList<>();
            for (TableController.ColumnRequest column : columns) {
                // key 列本来就不允许写聚合类型，只对 value 列提示
                if (!normalizedKeys.contains(column.name())
                        && (column.aggType() == null || column.aggType().isBlank())) {
                    missing.add("`" + column.name() + "`");
                }
            }
            if (!missing.isEmpty()) {
                warnings.add("AGGREGATE 模型下字段 " + String.join(", ", missing)
                        + " 未指定聚合类型，已默认 REPLACE");
            }
        }

        return new Result(ddl.toString(), warnings, normalizedKeys, normalizedModel,
                ordered.size());
    }

    // ---------------------------------------------------------------- 分区

    private record PartitionPlan(String clause, List<String> properties) {
    }

    private static PartitionPlan buildPartition(List<TableController.ColumnRequest> columns,
                                                Set<String> names, List<String> keys,
                                                PartitionDef partition, List<String> warnings) {
        String mode = normalizeMode(partition == null ? null : partition.mode());
        if ("NONE".equals(mode)) {
            return new PartitionPlan(null, List.of());
        }
        List<String> partitionColumns = partition.columns();
        if (partitionColumns == null || partitionColumns.isEmpty()) {
            throw ApiException.badRequest("分区模式 " + mode + " 需要指定分区列");
        }
        if (partitionColumns.size() > 1) {
            throw ApiException.badRequest("LIST".equals(mode)
                    ? "LIST 分区只支持单列"
                    : "本批 RANGE / DYNAMIC 分区只支持单列");
        }
        String partitionColumn = partitionColumns.get(0);
        if (partitionColumn == null || !NAME.matcher(partitionColumn).matches()) {
            throw ApiException.badRequest("分区列名不合法: " + partitionColumn);
        }
        if (!names.contains(partitionColumn)) {
            throw ApiException.badRequest("分区列不存在: " + partitionColumn);
        }
        // Doris 要求分区列必须是 key 列
        if (!keys.contains(partitionColumn)) {
            throw ApiException.badRequest("分区列必须是 key 列: " + partitionColumn
                    + "（请把该列加入主键）");
        }

        return switch (mode) {
            case "RANGE" -> new PartitionPlan(staticRange(partitionColumn, partition.ranges()),
                    List.of());
            case "LIST" -> new PartitionPlan(staticList(partitionColumn, partition.lists()),
                    List.of());
            case "DYNAMIC" -> dynamic(columns, partitionColumn, partition.dynamic(), warnings);
            default -> throw ApiException.badRequest("分区模式只支持 NONE / RANGE / DYNAMIC / LIST");
        };
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NONE";
        }
        String mode = raw.trim().toUpperCase();
        if (!MODES.contains(mode)) {
            throw ApiException.badRequest("分区模式只支持 NONE / RANGE / DYNAMIC / LIST: " + raw);
        }
        return mode;
    }

    private static String staticRange(String partitionColumn, List<RangeDef> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            throw ApiException.badRequest("RANGE 分区至少需要 1 个分区定义");
        }
        Set<String> seen = new HashSet<>();
        List<String> defs = new ArrayList<>();
        for (RangeDef range : ranges) {
            if (range == null) {
                throw ApiException.badRequest("RANGE 分区定义不能为空");
            }
            String name = requireName(range.name(), "分区名");
            if (!seen.add(name)) {
                throw ApiException.badRequest("分区名重复: " + name);
            }
            if (range.lessThan() == null || range.lessThan().isBlank()) {
                throw ApiException.badRequest("RANGE 分区 " + name + " 缺少 lessThan 上界");
            }
            defs.add("  partition `" + name + "` values less than ('"
                    + escape(range.lessThan().trim()) + "')");
        }
        return "partition by range(`" + partitionColumn + "`) (\n"
                + String.join(",\n", defs) + "\n)\n";
    }

    private static String staticList(String partitionColumn, List<ListDef> lists) {
        if (lists == null || lists.isEmpty()) {
            throw ApiException.badRequest("LIST 分区至少需要 1 个分区定义");
        }
        Set<String> seen = new HashSet<>();
        List<String> defs = new ArrayList<>();
        for (ListDef list : lists) {
            if (list == null) {
                throw ApiException.badRequest("LIST 分区定义不能为空");
            }
            String name = requireName(list.name(), "分区名");
            if (!seen.add(name)) {
                throw ApiException.badRequest("分区名重复: " + name);
            }
            if (list.values() == null || list.values().isEmpty()) {
                throw ApiException.badRequest("LIST 分区 " + name + " 缺少 values");
            }
            List<String> values = new ArrayList<>();
            for (String value : list.values()) {
                if (value == null || value.isBlank()) {
                    throw ApiException.badRequest("LIST 分区 " + name + " 的 values 不能有空值");
                }
                values.add("'" + escape(value.trim()) + "'");
            }
            defs.add("  partition `" + name + "` values in (" + String.join(", ", values) + ")");
        }
        return "partition by list(`" + partitionColumn + "`) (\n"
                + String.join(",\n", defs) + "\n)\n";
    }

    private static PartitionPlan dynamic(List<TableController.ColumnRequest> columns,
                                         String partitionColumn, DynamicDef dynamic,
                                         List<String> warnings) {
        if (dynamic == null) {
            throw ApiException.badRequest("动态分区需要提供 dynamic 参数");
        }
        String timeUnit = dynamic.timeUnit() == null ? null
                : dynamic.timeUnit().trim().toUpperCase();
        if (timeUnit == null || timeUnit.isEmpty()) {
            throw ApiException.badRequest("动态分区 timeUnit 不能为空（HOUR/DAY/WEEK/MONTH/YEAR）");
        }
        if (!TIME_UNITS.contains(timeUnit)) {
            throw ApiException.badRequest("动态分区 timeUnit 不支持: " + dynamic.timeUnit()
                    + "（可选 HOUR/DAY/WEEK/MONTH/YEAR）");
        }
        int start = requireInt(dynamic.start(), "动态分区 start");
        int end = requireInt(dynamic.end(), "动态分区 end");
        if (start > 0) {
            throw ApiException.badRequest("动态分区 start 需 <= 0: " + start);
        }
        if (end < 0) {
            throw ApiException.badRequest("动态分区 end 需 >= 0: " + end);
        }
        if (start > end) {
            throw ApiException.badRequest("动态分区 start 不能大于 end: " + start + " > " + end);
        }
        if (dynamic.prefix() == null || dynamic.prefix().isBlank()) {
            throw ApiException.badRequest("动态分区 prefix 不能为空");
        }
        String prefix = dynamic.prefix().trim();
        if (!PREFIX.matcher(prefix).matches()) {
            throw ApiException.badRequest("动态分区 prefix 只能包含字母/数字/下划线: " + prefix);
        }
        if (dynamic.buckets() != null && (dynamic.buckets() < 1 || dynamic.buckets() > 64)) {
            throw ApiException.badRequest("动态分区 buckets 需在 1-64");
        }

        // 动态分区的分区列通常必须是 DATE / DATETIME；不满足时 Doris 会拒绝，这里先提示
        String type = findColumn(columns, partitionColumn).type().trim().toUpperCase();
        if (!type.startsWith("DATE") && !type.startsWith("DATETIME")) {
            warnings.add("动态分区列 `" + partitionColumn
                    + "` 不是 DATE / DATETIME 类型，Doris 可能拒绝该分区定义");
        }

        List<String> properties = new ArrayList<>();
        properties.add("\"dynamic_partition.enable\"=\"true\"");
        properties.add("\"dynamic_partition.time_unit\"=\"" + timeUnit + "\"");
        properties.add("\"dynamic_partition.start\"=\"" + start + "\"");
        properties.add("\"dynamic_partition.end\"=\"" + end + "\"");
        properties.add("\"dynamic_partition.prefix\"=\"" + prefix + "\"");
        // buckets 可选：不写时 Doris 默认用表的分桶数
        if (dynamic.buckets() != null) {
            properties.add("\"dynamic_partition.buckets\"=\"" + dynamic.buckets() + "\"");
        }
        if (Boolean.TRUE.equals(dynamic.createHistory())) {
            properties.add("\"dynamic_partition.create_history_partition\"=\"true\"");
        }
        // Doris 动态分区 = RANGE + 空分区体，分区由调度自动创建
        return new PartitionPlan("partition by range(`" + partitionColumn + "`) ()\n",
                properties);
    }

    // ---------------------------------------------------------------- 辅助

    /** 未指定返回 null（不写 storage_medium，跟随集群磁盘默认）；显式才校验。 */
    private static String normalizeMedium(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String medium = raw.trim().toUpperCase(Locale.ROOT);
        if (!STORAGE_MEDIA.contains(medium)) {
            throw ApiException.badRequest("storageMedium 只能是 SSD / HDD: " + raw);
        }
        return medium;
    }

    private static String normalizeCooldown(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 允许前端 DatePicker 常见的 ISO 形式，统一成 Doris 需要的空格分隔
        String value = raw.trim().replace('T', ' ');
        if (!STORAGE_COOLDOWN.matcher(value).matches()) {
            throw ApiException.badRequest(
                    "storageCooldownTime 格式必须是 yyyy-MM-dd HH:mm:ss: " + raw);
        }
        return value;
    }

    private static int requireInt(String value, String label) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(label + " 不能为空");
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(label + " 必须为整数: " + value);
        }
    }

    private static TableController.ColumnRequest findColumn(
            List<TableController.ColumnRequest> columns, String name) {
        return columns.stream().filter(c -> c != null && name.equals(c.name())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("字段不存在: " + name));
    }

    private static String requireName(String value, String label) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw ApiException.badRequest(label + "不合法（字母或下划线开头，只含字母/数字/下划线）: "
                    + value);
        }
        return value;
    }

    /** 预检 6：标识符合法 + 不是保留字（表名/列名共用）。 */
    /**
     * REQ-DW-6：索引行校验。列名必须已在字段列表里（Doris 的 index 只能建在已有列上），
     * 类型只认 BITMAP / INVERTED / NGRAM_BF；BloomFilter 单独给出「不是建表索引」的提示，
     * 避免用户以为勾了就有、实际 DDL 里没有生效。
     */
    private static List<TableController.IndexRequest> normalizeIndexes(
            List<TableController.IndexRequest> indexes, Set<String> columnNames) {
        if (indexes == null || indexes.isEmpty()) {
            return List.of();
        }
        if (indexes.size() > 32) {
            throw ApiException.badRequest("索引最多 32 个");
        }
        List<TableController.IndexRequest> out = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (TableController.IndexRequest index : indexes) {
            if (index == null) {
                throw ApiException.badRequest("索引行不能为空");
            }
            String name = requireIdentifier(index.name(), "索引名");
            if (!usedNames.add(name)) {
                throw ApiException.badRequest("索引名重复: " + name);
            }
            String column = requireIdentifier(index.column(), "索引目标列");
            if (!columnNames.contains(column)) {
                throw ApiException.badRequest("索引 " + name + " 的目标列不存在: " + column);
            }
            String type = index.type() == null || index.type().isBlank()
                    ? "BITMAP" : index.type().trim().toUpperCase(Locale.ROOT);
            if ("BLOOMFILTER".equals(type) || "BLOOM_FILTER".equals(type)) {
                throw ApiException.badRequest("BloomFilter 不是建表索引，请在表属性 bloom_filter_columns 配置（表单暂不支持）");
            }
            if (!INDEX_TYPES.contains(type)) {
                throw ApiException.badRequest("不支持的索引类型: " + index.type()
                        + "，只支持 BITMAP / INVERTED / NGRAM_BF");
            }
            out.add(new TableController.IndexRequest(name, column, type, index.comment()));
        }
        return out;
    }

    private static String requireIdentifier(String value, String label) {
        String name = requireName(value, label);
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest(label + " `" + name
                    + "` 是 Doris/MySQL 保留字，请加下划线或换名（例如 `" + name + "_col`）");
        }
        return name;
    }

    /**
     * 预检 4 的可复用版本：返回问题描述，null 表示通过。
     *
     * <p>SQL 模式解析（{@link TableSqlParser}）要把「表单不支持的类型」收集到
     * unsupported 而不是直接 400，所以把判断抽成返回值形式，两边共用同一份规则。</p>
     */
    static String typeProblem(String rawType, String columnName) {
        String type = rawType == null ? null : rawType.trim();
        if (type == null || !TYPE.matcher(type).matches()) {
            return "不支持的字段类型: " + rawType;
        }
        Matcher varchar = VARCHAR.matcher(type);
        if (varchar.matches() && varchar.group(1) != null) {
            int length = Integer.parseInt(varchar.group(1));
            if (length < 1 || length > 65533) {
                return "字段 `" + columnName + "` 的 VARCHAR 长度需在 1-65533: " + length;
            }
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = Integer.parseInt(decimal.group(2));
            if (precision < 1 || precision > 38) {
                return "字段 `" + columnName + "` 的 DECIMAL 精度需在 1-38: " + precision;
            }
            if (scale > precision) {
                return "字段 `" + columnName + "` 的 DECIMAL 小数位不能大于精度: DECIMAL("
                        + precision + "," + scale + ")";
            }
        }
        return null;
    }

    /** 预检 4：类型白名单 + VARCHAR 长度 1..65533 + DECIMAL 精度/小数位。 */
    private static void checkType(String rawType, String columnName) {
        String problem = typeProblem(rawType, columnName);
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
    }

    /** SQL 模式解析复用：保留字判断（表单建表会 400，解析时要进 unsupported）。 */
    static boolean isReservedName(String name) {
        return name != null && RESERVED.contains(name.toLowerCase(Locale.ROOT));
    }

    /** SQL 模式解析复用：该列的默认值表单能不能接受；null = 可以。 */
    static String defaultProblem(TableController.ColumnRequest column, boolean isKey) {
        try {
            defaultLiteral(column, isKey);
            return null;
        } catch (ApiException e) {
            return e.getMessage();
        }
    }

    /** 预检 2：分桶列必须在 key 列里；为空时默认第一个 key 列。 */
    private static List<String> normalizeDistribution(List<String> distributionColumns,
                                                      List<String> keys, Set<String> names) {
        if (distributionColumns == null || distributionColumns.isEmpty()) {
            return List.of(keys.get(0));
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : distributionColumns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String column = raw.trim();
            if (!names.contains(column)) {
                throw ApiException.badRequest("分桶列不存在: " + column);
            }
            if (!keys.contains(column)) {
                throw ApiException.badRequest("分桶列必须是 key 列: " + column
                        + "（请先把该列加入主键）");
            }
            if (!out.add(column)) {
                throw ApiException.badRequest("分桶列重复: " + column);
            }
        }
        return out.isEmpty() ? List.of(keys.get(0)) : List.copyOf(out);
    }

    /**
     * 列默认值 → DDL 字面量。
     *
     * <p>key 列不允许默认值（Doris sort key 限制），直接 400；
     * 数值/布尔不带引号，字符串与日期加单引号并转义。</p>
     */
    private static String defaultLiteral(TableController.ColumnRequest column, boolean isKey) {
        String raw = column.defaultValue();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String name = column.name();
        if (isKey) {
            throw ApiException.badRequest("字段 `" + name
                    + "` 是 key 列，Doris 不允许 key 列设置默认值（请移出主键或删除默认值）");
        }
        String type = column.type().trim().toUpperCase(Locale.ROOT);
        if ("BOOLEAN".equals(type)) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!"true".equals(value) && !"false".equals(value)) {
                throw ApiException.badRequest("字段 `" + name
                        + "` 的默认值必须是 true/false: " + raw);
            }
            return value;
        }
        String value = raw.trim();
        if (INTEGER_TYPES.contains(type)) {
            if (!INTEGER_VALUE.matcher(value).matches()) {
                throw ApiException.badRequest("字段 `" + name + "`（" + type
                        + "）的默认值必须是整数: " + raw);
            }
            return value;
        }
        if (FLOAT_TYPES.contains(type)) {
            if (!FLOAT_VALUE.matcher(value).matches()) {
                throw ApiException.badRequest("字段 `" + name + "`（" + type
                        + "）的默认值必须是数字: " + raw);
            }
            return value;
        }
        if (type.startsWith("DECIMAL")) {
            if (!DECIMAL_VALUE.matcher(value).matches()) {
                throw ApiException.badRequest("字段 `" + name + "`（" + type
                        + "）的默认值必须是数字: " + raw);
            }
            return value;
        }
        // 字符串 / 日期时间 / JSON 等：单引号包裹并转义，避免注入与截断
        return "'" + escape(raw) + "'";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }
}
