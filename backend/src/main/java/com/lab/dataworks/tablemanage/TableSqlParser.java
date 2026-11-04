package com.lab.dataworks.tablemanage;

import com.lab.dataworks.common.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 建表 SQL 模式：把一条 {@code CREATE TABLE} 解析成结构化模型（供表单回填），并做保守校验。
 *
 * <p>设计原则（第三批）：</p>
 * <ul>
 *   <li><b>只允许单条 CREATE TABLE</b>：多语句 / SET / INSERT / DROP / ALTER / CTAS 一律 400。</li>
 *   <li><b>保守解析</b>：拿不准的部分不猜，统一进 {@code unsupported}，前端提示「切回表单会丢失」；</li>
 *   <li><b>不做 DDL 改写</b>：返回的 {@code ddl} 就是去掉注释与末尾分号后的原文，执行时原样下发。</li>
 *   <li><b>表名必须带当前库</b>：Doris 连接没有默认库，不带库名会建错地方/报错，
 *       同时保证跨租户不能借 SQL 模式建到别人的库。</li>
 * </ul>
 *
 * <p>本类只做字符串解析与校验，不接触数据库、不执行任何 SQL。</p>
 */
public final class TableSqlParser {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    private static final Set<String> AGG_TYPES = Set.of("REPLACE", "REPLACE_IF_NOT_NULL", "SUM",
            "MIN", "MAX", "HLL_UNION", "BITMAP_UNION", "QUANTILE_UNION");
    private static final Set<String> TIME_UNITS = Set.of("HOUR", "DAY", "WEEK", "MONTH", "YEAR");
    /** DEFAULT 表达式遇到这些关键字就结束（属于下一个修饰）。 */
    private static final Set<String> DEFAULT_STOP;
    /** 表单能表达的动态分区参数，其余 dynamic_partition.* 一律进 unsupported。 */
    private static final Set<String> DYNAMIC_KEYS = Set.of("enable", "time_unit", "start", "end",
            "prefix", "buckets", "create_history_partition");

    /**
     * D14：分桶数合法范围。Doris 要求 buckets 为正整数；上限 4096 是工程值——
     * Doris 官方建议单个 tablet（分桶 × 副本）保持在 GB 量级、避免海量小分桶撑爆 FE 元数据，
     * AUTO BUCKETS 自动估算正常也到不了千级，超过 4096 基本是把行数误写成桶数。
     * 主流管理平台建表入口普遍设千级硬上限，这里取 4096；表单建表仍限 1-64（见 parse 的交叉校验），
     * SQL 模式放宽到 1-4096，再大或溢出（2147483648 / 99999999999999999999）一律 400。
     */
    static final int MIN_BUCKETS = 1;
    static final int MAX_BUCKETS = 4096;
    /**
     * D14：副本数合法范围。Doris 副本数不能超过存活 BE 数（预检见 {@code TableController.aliveBackends}），
     * 实际集群 BE 数远小于 99；工程上限取 99，表单建表限 1-10（11-99 仍走 unsupported + 夹紧的旧口径），
     * 越界/溢出 400。
     */
    static final int MIN_REPLICATION = 1;
    static final int MAX_REPLICATION = 99;

    static {
        Set<String> stop = new LinkedHashSet<>(AGG_TYPES);
        stop.add("NULL");
        stop.add("NOT");
        stop.add("COMMENT");
        DEFAULT_STOP = Set.copyOf(stop);
    }

    private static final Pattern CREATE_HEAD = Pattern.compile(
            "(?is)^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?");
    private static final Pattern IDENT = Pattern.compile(
            "\\s*(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))");
    private static final Pattern KEY_CLAUSE = Pattern.compile(
            "(?i)\\b(unique|duplicate|aggregate|primary)\\s+key\\s*\\(");
    /** DISTRIBUTED BY HASH(...) 与 DISTRIBUTED BY RANDOM（无括号）两种写法都要认。 */
    private static final Pattern DISTRIBUTED = Pattern.compile(
            "(?i)\\bdistributed\\s+by\\s+(hash|random)\\b");
    /** D14：符号也要匹配进来，交给 {@link #parseBoundedInt} 判定越界并报 400，不再当「未写 BUCKETS」。 */
    private static final Pattern BUCKETS = Pattern.compile("(?i)\\s*buckets\\s+([+-]?\\d+)");
    private static final Pattern PARTITION = Pattern.compile(
            "(?i)\\bpartition\\s+by\\s+([A-Za-z_]+)\\s*\\(");
    private static final Pattern PROPERTIES = Pattern.compile("(?i)\\bproperties\\s*\\(");
    private static final Pattern ENGINE = Pattern.compile("(?i)\\bengine\\s*=\\s*([A-Za-z_]+)");
    // 只匹配关键字本身：尾部 \s* 在「字符串已被抹成空格」的 masked 文本上会一路吞到
    // 下一个关键字（如 comment '表注释' partition ... 会落到 partition）；空白交给 skipWs。
    private static final Pattern COMMENT_HEAD = Pattern.compile("(?i)\\bcomment\\b");
    /**
     * 字段列表里的索引/约束定义。REQ-DW-6：命名单列 {@code INDEX x (c) USING bitmap}
     * 这一种形态先由 {@link #INDEX_CLAUSE} 抽出进 model.indexes，
     * 其余（key/constraint/foreign/check、未命名、多列、带额外参数）仍整条进 unsupported。
     */
    private static final Pattern INDEX_HEAD = Pattern.compile(
            "(?i)^\\s*(?:(?:index|key|constraint|foreign|check)\\b|(?:primary|unique)\\s+key\\s*\\()");
    /**
     * REQ-DW-6：表单能表达的<b>唯一</b>索引形态，与 {@code TableCreateDdl} 输出一一对应：
     * {@code INDEX `name` (`col`) USING BITMAP|INVERTED|NGRAM_BF [COMMENT '...']}。
     * 拿不准的一律不认（保守解析原则），交给 INDEX_HEAD 的兜底分支进 unsupported。
     */
    private static final Pattern INDEX_CLAUSE = Pattern.compile(
            "(?is)^\\s*index\\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\\s*\\(\\s*"
                    + "(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\\s*\\)"
                    + "\\s*using\\s+(bitmap|inverted|ngram_bf)\\b"
                    + "(?:\\s*comment\\s+('(?:[^']|'')*'))?\\s*$");
    private static final Pattern COLUMN_PREFIX = Pattern.compile(
            "^\\s*(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z][A-Za-z0-9_]*)"
                    + "(\\s*\\([^()]*\\))?(.*)$", Pattern.DOTALL);
    private static final Pattern SINGLE_IDENT = Pattern.compile(
            "^\\s*(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\\s*$");
    private static final Pattern REPLICATION = Pattern.compile(
            "(?i)^\\s*tag\\.location\\.default\\s*:\\s*(\\d+)\\s*$");

    private TableSqlParser() {
    }

    // ---------------------------------------------------------------- 对外结构

    /**
     * 解析出的结构化模型。字段名与 {@code POST /api/tables} 请求体对齐，
     * 前端可以直接 setFieldsValue 回填表单。
     *
     * <p>REQ-DW-6：{@code indexes} = SQL 里能完整表达的二级索引（见
     * {@link #INDEX_CLAUSE}）；表达不了的已在 parseColumns 阶段进 unsupported。</p>
     */
    public record TableModel(String database, String table, String comment,
                             List<TableController.ColumnRequest> columns, List<String> keys,
                             String model, Integer buckets, Integer replication,
                             List<String> distributionColumns,
                             TableCreateDdl.PartitionDef partition,
                             List<TableController.IndexRequest> indexes) {
    }

    /** ddl = 规范化后的单条 SQL；model = null 表示无法转表单（LIKE 等）。 */
    public record Result(String ddl, String table, TableModel model, List<String> unsupported,
                         List<String> warnings) {
    }

    // ---------------------------------------------------------------- 解析入口

    /**
     * 解析一条建表 SQL。
     *
     * @param database    当前库（必须与 SQL 中的库名一致）
     * @param sql         用户输入的 SQL
     * @param targetTable 表单里的表名（可选，仅用于提示不一致，以 SQL 为准）
     */
    public static Result parse(String database, String sql, String targetTable) {
        if (database == null || !NAME.matcher(database).matches()) {
            throw ApiException.badRequest("库名不合法: " + database);
        }
        if (sql == null || sql.isBlank()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        // MySQL 可执行注释会绕过「只允许单条语句」的检查，直接拒绝
        if (sql.contains("/*!")) {
            throw ApiException.badRequest("不支持 MySQL 可执行注释 /*! ... */，请改用普通注释或去掉注释");
        }

        List<String> statements = splitStatements(stripComments(sql));
        if (statements.isEmpty()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        if (statements.size() > 1) {
            throw ApiException.badRequest("只允许单条语句（检测到 " + statements.size()
                    + " 条；SQL 模式一次只能执行一条 CREATE TABLE）");
        }
        String stmt = statements.get(0).trim();
        String masked = maskLiterals(stmt);

        // 1) 语句类型：只允许 CREATE TABLE
        List<String> words = leadingWords(masked, 2);
        String first = words.isEmpty() ? "" : words.get(0);
        String second = words.size() > 1 ? words.get(1) : "";
        if (!"CREATE".equals(first)) {
            throw ApiException.badRequest("只允许 CREATE TABLE 语句（当前是 "
                    + (first.isEmpty() ? "空语句" : first) + "）");
        }
        if ("TEMPORARY".equals(second)) {
            throw ApiException.badRequest("不支持 CREATE TEMPORARY TABLE，请使用普通 CREATE TABLE");
        }
        if (!"TABLE".equals(second)) {
            throw ApiException.badRequest("只允许 CREATE TABLE 语句（当前是 CREATE "
                    + (second.isEmpty() ? "?" : second) + "）");
        }
        // 头部匹配必须保留反引号标识符：maskLiterals 会把 `db`.`t` 抹成空格，
        // CREATE_HEAD 里的 \s+ 会一路吃到 "."，head.end() 就落在表名之后。
        Matcher head = CREATE_HEAD.matcher(maskStrings(stmt));
        if (!head.find()) {
            throw ApiException.badRequest("只允许 CREATE TABLE 语句");
        }

        // 2) 表名：必须写成 `库`.`表`（Doris 连接没有默认库，同时防止跨库建表）
        Qualified target = readQualified(stmt, head.end());
        if (target == null) {
            throw ApiException.badRequest("无法解析 CREATE TABLE 后的表名");
        }
        if (target.database() == null) {
            throw ApiException.badRequest("SQL 模式要求表名带库名（当前库 `" + database
                    + "`），例如 CREATE TABLE `" + database + "`.`t` (...)，避免建到默认库");
        }
        if (!target.database().equals(database)) {
            throw ApiException.badRequest("SQL 中的库名 `" + target.database() + "` 与当前库 `"
                    + database + "` 不一致（不支持跨库建表）");
        }
        String table = target.table();

        List<String> unsupported = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (targetTable != null && !targetTable.isBlank() && !targetTable.trim().equals(table)) {
            warnings.add("SQL 中的表名 `" + table + "` 与表单表名 `" + targetTable.trim()
                    + "` 不一致，已以 SQL 为准");
        }

        // 3) LIKE / AS SELECT / 字段定义
        int bodyStart = skipWs(stmt, target.end());
        String next = wordAt(masked, bodyStart);
        if ("LIKE".equals(next)) {
            int afterLike = skipWs(stmt, bodyStart + next.length());
            Qualified source = readQualified(stmt, afterLike);
            if (source == null) {
                throw ApiException.badRequest("CREATE TABLE ... LIKE 缺少源表名");
            }
            if (source.database() != null && !source.database().equals(database)) {
                throw ApiException.badRequest("LIKE 源表 `" + source.database() + "`."
                        + source.table() + "` 不在当前库，不支持跨库建表");
            }
            if (source.database() == null) {
                warnings.add("LIKE 源表 `" + source.table()
                        + "` 未带库名，执行时按连接默认库解析，可能报 No database selected");
            }
            unsupported.add("LIKE 形式不支持转表单（保留 SQL 模式提交即可）");
            return new Result(stmt, table, null, unsupported, warnings);
        }
        if ("AS".equals(next)) {
            throw ApiException.badRequest("不支持 CREATE TABLE ... AS SELECT ..."
                    + "（无法确认读取范围，可能跨租户），请先建表再写入数据");
        }
        if (bodyStart >= stmt.length() || stmt.charAt(bodyStart) != '(') {
            throw ApiException.badRequest("CREATE TABLE 语句缺少字段定义（表名后应为 `(`）");
        }
        int close = findMatchingParen(masked, bodyStart);
        if (close < 0) {
            throw ApiException.badRequest("CREATE TABLE 的字段定义括号不匹配");
        }
        String body = stmt.substring(bodyStart + 1, close);
        String tail = stmt.substring(close + 1);

        List<TableController.IndexRequest> rawIndexes = new ArrayList<>();
        List<TableController.ColumnRequest> columns = parseColumns(body, unsupported, warnings,
                rawIndexes);
        if (columns.isEmpty()) {
            throw ApiException.badRequest("CREATE TABLE 至少需要一个字段");
        }

        Draft draft = parseTail(tail, unsupported, warnings);

        // 4) keys / 默认值 / 分桶列 / 分桶数 / 副本数 交叉校验
        List<String> keys = new ArrayList<>();
        if (draft.keys.isEmpty()) {
            keys.add(columns.get(0).name());
        } else {
            for (String key : draft.keys) {
                boolean exists = columns.stream().anyMatch(c -> c.name().equals(key));
                if (exists) {
                    keys.add(key);
                } else {
                    unsupported.add("KEY 子句中的字段 `" + key + "` 不在字段列表里，表单不支持");
                }
            }
            if (keys.isEmpty()) {
                keys.add(columns.get(0).name());
            }
        }

        List<TableController.ColumnRequest> fixed = new ArrayList<>();
        for (TableController.ColumnRequest column : columns) {
            if (column.defaultValue() == null) {
                fixed.add(column);
                continue;
            }
            String problem = TableCreateDdl.defaultProblem(column, keys.contains(column.name()));
            if (problem == null) {
                fixed.add(column);
                continue;
            }
            unsupported.add("字段 `" + column.name() + "` 的默认值表单不支持：" + problem);
            fixed.add(new TableController.ColumnRequest(column.name(), column.type(),
                    column.comment(), column.nullable(), column.aggType(), null));
        }
        columns = fixed;

        List<String> distribution = new ArrayList<>();
        for (String column : draft.distribution) {
            if (keys.contains(column)) {
                distribution.add(column);
            } else {
                unsupported.add("分桶列 `" + column + "` 不是 key 列，表单建表会拒绝");
            }
        }

        // REQ-DW-6：索引与分桶列同口径交叉校验——目标列不在字段列表里就进 unsupported，
        // 不能带着坏索引回表单（表单提交会被 TableCreateDdl 400，等于「能切回表单」的承诺破产）
        List<TableController.IndexRequest> indexes = new ArrayList<>();
        for (TableController.IndexRequest index : rawIndexes) {
            boolean columnExists = index.column() != null && columns.stream()
                    .anyMatch(c -> c.name().equals(index.column()));
            if (columnExists) {
                indexes.add(index);
            } else {
                unsupported.add("索引 `" + index.name() + "` 的目标列 `" + index.column()
                        + "` 不在字段列表里，表单不支持");
            }
        }

        TableCreateDdl.PartitionDef partition = draft.partition;
        if (partition != null) {
            List<String> partitionColumns = partition.columns();
            String partitionColumn = partitionColumns == null || partitionColumns.isEmpty()
                    ? null : partitionColumns.get(0);
            if (partitionColumn == null || !keys.contains(partitionColumn)) {
                unsupported.add("分区列 `" + partitionColumn + "` 不是 key 列，表单建表会拒绝");
                partition = null;
            }
        }

        Integer buckets = draft.buckets;
        if (buckets != null && (buckets < 1 || buckets > 64)) {
            unsupported.add("分桶数 " + buckets + " 超出表单范围 1-64");
            buckets = Math.max(1, Math.min(64, buckets));
        }
        Integer replication = draft.replication;
        if (replication != null && (replication < 1 || replication > 10)) {
            unsupported.add("副本数 " + replication + " 超出表单范围 1-10");
            replication = Math.max(1, Math.min(10, replication));
        }

        if (!NAME.matcher(table).matches()) {
            unsupported.add("表名 `" + table + "` 不符合表单命名规则"
                    + "（字母或下划线开头，只含字母/数字/下划线）");
        } else if (TableCreateDdl.isReservedName(table)) {
            unsupported.add("表名 `" + table + "` 是 Doris/MySQL 保留字，表单建表会拒绝");
        }

        TableModel model = new TableModel(database, table, draft.comment, columns, keys,
                draft.model, buckets, replication, distribution, partition, indexes);

        // 兜底：用表单同一套校验跑一遍，解析漏掉的限制也会进 unsupported，
        // 保证「能切回表单」的承诺成立（拿不准就提示，不静默放过）。
        try {
            TableCreateDdl.generate(database, table, draft.comment, columns, keys, buckets,
                    replication, draft.model, partition,
                    distribution.isEmpty() ? null : distribution, null,
                    null, null, null, indexes);
        } catch (ApiException e) {
            String message = "表单建表会拒绝该结构：" + e.getMessage();
            if (!unsupported.contains(message)) {
                unsupported.add(message);
            }
        }

        return new Result(stmt, table, model, unsupported, warnings);
    }

    // ---------------------------------------------------------------- 尾部子句

    /** 尾部子句解析出来、但还没交叉校验的草稿。 */
    private static final class Draft {
        String model = "DUPLICATE";
        List<String> keys = new ArrayList<>();
        List<String> distribution = new ArrayList<>();
        Integer buckets;
        Integer replication;
        String comment;
        TableCreateDdl.PartitionDef partition;
    }

    private static Draft parseTail(String tail, List<String> unsupported,
                                   List<String> warnings) {
        Draft draft = new Draft();
        Cover cover = new Cover(tail);
        String masked = cover.masked;

        // KEY 子句
        Matcher km = KEY_CLAUSE.matcher(masked);
        if (km.find()) {
            int open = masked.indexOf('(', km.start());
            int close = open < 0 ? -1 : findMatchingParen(masked, open);
            if (close < 0) {
                throw ApiException.badRequest("KEY 子句括号不匹配");
            }
            String kind = km.group(1).toLowerCase(Locale.ROOT);
            draft.keys = identList(tail.substring(open + 1, close));
            draft.model = switch (kind) {
                case "aggregate" -> "AGGREGATE";
                case "duplicate" -> "DUPLICATE";
                default -> "UNIQUE";
            };
            if ("primary".equals(kind)) {
                warnings.add("PRIMARY KEY 按 UNIQUE 模型处理（Doris 两者语义一致）");
            }
            cover.mark(km.start(), close + 1);
        } else {
            warnings.add("未找到 KEY 子句，表单将按 DUPLICATE KEY(第一个字段) 处理");
        }

        // DISTRIBUTED BY
        Matcher dm = DISTRIBUTED.matcher(masked);
        if (dm.find()) {
            int cursor = skipWs(masked, dm.end());
            List<String> distribution = new ArrayList<>();
            // HASH(col, ...) 带括号；RANDOM 不带括号
            if (cursor < masked.length() && masked.charAt(cursor) == '(') {
                int close = findMatchingParen(masked, cursor);
                if (close < 0) {
                    throw ApiException.badRequest("DISTRIBUTED BY 括号不匹配");
                }
                distribution = identList(tail.substring(cursor + 1, close));
                cursor = close + 1;
            }
            draft.distribution = distribution;
            Matcher bm = BUCKETS.matcher(masked);
            bm.region(cursor, masked.length());
            if (bm.lookingAt()) {
                // D14：溢出/越界 400（原来 Integer.parseInt 直接抛 NFE → 500）
                draft.buckets = parseBoundedInt(bm.group(1), MIN_BUCKETS, MAX_BUCKETS,
                        "分桶数 buckets");
                cursor = bm.end();
            } else {
                draft.buckets = 1;
                unsupported.add("DISTRIBUTED BY 未写 BUCKETS，表单会显式写成 1"
                        + "（Doris 省略时按数据量自动估算），切回表单语义会变");
            }
            if ("random".equalsIgnoreCase(dm.group(1))) {
                unsupported.add("DISTRIBUTED BY RANDOM 表单不支持（表单只生成 HASH 分桶）");
            }
            cover.mark(dm.start(), cursor);
        } else {
            draft.buckets = 1;
            unsupported.add("未写 DISTRIBUTED BY（Doris 要求必填），"
                    + "表单会补 HASH(第一个主键列) BUCKETS 1");
        }

        // ENGINE
        Matcher em = ENGINE.matcher(masked);
        if (em.find()) {
            cover.mark(em.start(), em.end());
            if (!"olap".equalsIgnoreCase(em.group(1))) {
                unsupported.add("ENGINE=" + em.group(1) + " 表单不支持（只生成 OLAP 引擎）");
            }
        }

        // 表注释
        Matcher cmt = COMMENT_HEAD.matcher(masked);
        if (cmt.find()) {
            int pos = skipWs(tail, cmt.end());
            Str str = readString(tail, pos);
            if (str != null) {
                draft.comment = str.value();
                cover.mark(cmt.start(), str.end());
            } else {
                unsupported.add("表注释 `" + snippet(tail.substring(cmt.start())) + "` 无法解析");
                cover.mark(cmt.start(), cmt.end());
            }
        }

        // PROPERTIES（先解析，动态分区参数后面还要用）
        Map<String, String> dynamicProps = new LinkedHashMap<>();
        Matcher prm = PROPERTIES.matcher(masked);
        if (prm.find()) {
            int open = masked.indexOf('(', prm.start());
            int close = open < 0 ? -1 : findMatchingParen(masked, open);
            if (close < 0) {
                throw ApiException.badRequest("PROPERTIES 括号不匹配");
            }
            cover.mark(prm.start(), close + 1);
            applyProperties(tail.substring(open + 1, close), draft, dynamicProps, unsupported);
        }
        if (draft.replication == null) {
            // Doris 默认副本数是 3；表单只写显式值，语义会变 → 进 unsupported 而不是静默取 1
            draft.replication = 1;
            unsupported.add("未写 replication_allocation，表单会显式写成 1 个副本"
                    + "（Doris 默认 3），切回表单语义会变");
        }

        // PARTITION BY
        Matcher pm = PARTITION.matcher(masked);
        if (pm.find()) {
            int open = masked.indexOf('(', pm.start());
            int close = open < 0 ? -1 : findMatchingParen(masked, open);
            if (close < 0) {
                throw ApiException.badRequest("PARTITION BY 括号不匹配");
            }
            String kind = pm.group(1).toLowerCase(Locale.ROOT);
            String expression = tail.substring(open + 1, close).trim();
            int bodyOpen = skipWs(masked, close + 1);
            String bodyContent = null;
            int end = close + 1;
            if (bodyOpen < masked.length() && masked.charAt(bodyOpen) == '(') {
                int bodyClose = findMatchingParen(masked, bodyOpen);
                if (bodyClose < 0) {
                    throw ApiException.badRequest("分区定义括号不匹配");
                }
                bodyContent = tail.substring(bodyOpen + 1, bodyClose);
                end = bodyClose + 1;
            }
            cover.mark(pm.start(), end);

            String column = singleIdent(expression);
            if (!"range".equals(kind) && !"list".equals(kind)) {
                unsupported.add("PARTITION BY " + pm.group(1)
                        + " 表单不支持（只支持 RANGE / LIST）");
            } else if (column == null) {
                unsupported.add("分区表达式 `" + snippet(expression)
                        + "` 表单不支持（只支持单列分区）");
            } else {
                draft.partition = buildPartition(kind, column, bodyContent, dynamicProps,
                        unsupported, warnings);
            }
        }

        // 剩余没识别的内容：宁可报出来，也不要静默忽略
        String leftover = cover.leftover();
        if (!leftover.isEmpty()) {
            unsupported.add("无法识别、表单不支持的片段 `" + snippet(leftover) + "`");
        }
        return draft;
    }

    private static TableCreateDdl.PartitionDef buildPartition(String kind, String column,
                                                              String bodyContent,
                                                              Map<String, String> dynamicProps,
                                                              List<String> unsupported,
                                                              List<String> warnings) {
        boolean emptyBody = bodyContent == null || bodyContent.isBlank();
        boolean dynamicEnabled = "true".equalsIgnoreCase(
                dynamicProps.getOrDefault("enable", "").trim());

        if ("range".equals(kind) && emptyBody) {
            // 空分区体 = 动态分区（分区由 Doris 调度创建）
            if (!dynamicEnabled) {
                unsupported.add("空分区体的 RANGE 分区（未启用动态分区）表单不支持");
                return null;
            }
            String unit = trimToNull(dynamicProps.get("time_unit"));
            String end = trimToNull(dynamicProps.get("end"));
            if (unit == null || end == null) {
                unsupported.add("动态分区缺少 " + (unit == null ? "dynamic_partition.time_unit"
                        : "dynamic_partition.end") + "，无法转表单");
                return null;
            }
            String upperUnit = unit.toUpperCase(Locale.ROOT);
            if (!TIME_UNITS.contains(upperUnit)) {
                unsupported.add("动态分区 time_unit `" + unit
                        + "` 表单不支持（HOUR/DAY/WEEK/MONTH/YEAR）");
                return null;
            }
            String start = trimToNull(dynamicProps.get("start"));
            if (start == null) {
                start = "0";
                warnings.add("动态分区未写 dynamic_partition.start，表单按 0 处理");
            }
            String prefix = trimToNull(dynamicProps.get("prefix"));
            if (prefix == null) {
                prefix = "p";
                warnings.add("动态分区未写 dynamic_partition.prefix，表单按 p 处理");
            }
            Integer buckets = parseInt(dynamicProps.get("buckets"));
            Boolean createHistory = "true".equalsIgnoreCase(
                    dynamicProps.getOrDefault("create_history_partition", "").trim());
            return new TableCreateDdl.PartitionDef("DYNAMIC", List.of(column), null, null,
                    new TableCreateDdl.DynamicDef(upperUnit, start, end, prefix, buckets,
                            createHistory));
        }

        if ("range".equals(kind)) {
            List<TableCreateDdl.RangeDef> ranges = parseRanges(bodyContent, unsupported);
            if (ranges.isEmpty()) {
                unsupported.add("RANGE 分区没有任何可用分区定义，表单不支持");
                return null;
            }
            if (dynamicEnabled) {
                unsupported.add("静态 RANGE 分区同时写了 dynamic_partition.enable=true，"
                        + "表单无法同时表达");
            }
            return new TableCreateDdl.PartitionDef("RANGE", List.of(column), ranges, null, null);
        }

        List<TableCreateDdl.ListDef> lists = parseLists(bodyContent, unsupported);
        if (lists.isEmpty()) {
            unsupported.add("LIST 分区没有任何可用分区定义，表单不支持");
            return null;
        }
        return new TableCreateDdl.PartitionDef("LIST", List.of(column), null, lists, null);
    }

    private static List<TableCreateDdl.RangeDef> parseRanges(String body,
                                                             List<String> unsupported) {
        List<TableCreateDdl.RangeDef> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (String item : splitTopLevel(body, ',')) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            // 这里只抹字符串、保留反引号：分区名要能被下面的正则读出来
            String mask = maskStrings(text);
            Matcher m = Pattern.compile(
                    "(?is)^\\s*partition\\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\\s+"
                            + "values\\s+less\\s+than\\s*\\(").matcher(mask);
            if (!m.find()) {
                unsupported.add("分区定义 `" + snippet(text) + "` 无法解析，表单不支持");
                continue;
            }
            int open = mask.indexOf('(', m.start());
            int close = findMatchingParen(mask, open);
            if (open < 0 || close < 0) {
                unsupported.add("分区定义 `" + snippet(text) + "` 括号不匹配");
                continue;
            }
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            List<String> values = splitTopLevel(text.substring(open + 1, close), ',');
            if (values.size() != 1) {
                unsupported.add("分区 `" + name + "` 的上界有 " + values.size()
                        + " 个值（多列分区），表单不支持");
                continue;
            }
            String value = values.get(0).trim();
            if ("MAXVALUE".equalsIgnoreCase(value)) {
                unsupported.add("分区 `" + name
                        + "` 的上界是 MAXVALUE，表单会给值加引号（语义变化），不支持");
                continue;
            }
            Str str = readString(value, 0);
            if (str == null || skipWs(value, str.end()) != value.length()) {
                unsupported.add("分区 `" + name + "` 的上界 `" + snippet(value)
                        + "` 不是字符串常量，表单不支持");
                continue;
            }
            out.add(new TableCreateDdl.RangeDef(name, str.value()));
        }
        return out;
    }

    private static List<TableCreateDdl.ListDef> parseLists(String body, List<String> unsupported) {
        List<TableCreateDdl.ListDef> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (String item : splitTopLevel(body, ',')) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            String mask = maskStrings(text);
            Matcher m = Pattern.compile(
                    "(?is)^\\s*partition\\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\\s+"
                            + "values\\s+in\\s*\\(").matcher(mask);
            if (!m.find()) {
                unsupported.add("分区定义 `" + snippet(text) + "` 无法解析，表单不支持");
                continue;
            }
            int open = mask.indexOf('(', m.start());
            int close = findMatchingParen(mask, open);
            if (open < 0 || close < 0) {
                unsupported.add("分区定义 `" + snippet(text) + "` 括号不匹配");
                continue;
            }
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            List<String> values = new ArrayList<>();
            boolean ok = true;
            for (String part : splitTopLevel(text.substring(open + 1, close), ',')) {
                String value = part.trim();
                if (value.isEmpty()) {
                    continue;
                }
                Str str = readString(value, 0);
                if (str == null || skipWs(value, str.end()) != value.length()) {
                    unsupported.add("LIST 分区 `" + name + "` 的值 `" + snippet(value)
                            + "` 不是字符串常量，表单不支持");
                    ok = false;
                    break;
                }
                values.add(str.value());
            }
            if (ok && !values.isEmpty()) {
                out.add(new TableCreateDdl.ListDef(name, values));
            }
        }
        return out;
    }

    private static void applyProperties(String body, Draft draft,
                                        Map<String, String> dynamicProps,
                                        List<String> unsupported) {
        for (String item : splitTopLevel(body, ',')) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            List<Tok> toks = tokenize(text);
            if (toks.size() != 3 || !"=".equals(toks.get(1).raw())) {
                unsupported.add("表属性 `" + snippet(text) + "` 无法解析，表单不支持");
                continue;
            }
            String key = toks.get(0).value();
            String value = toks.get(2).value();
            String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if ("replication_allocation".equals(lower)) {
                Matcher m = REPLICATION.matcher(value == null ? "" : value);
                if (m.matches()) {
                    // D14：tag.location.default 的数值溢出/越界 400（原来 NFE → 500）
                    draft.replication = parseBoundedInt(m.group(1), MIN_REPLICATION,
                            MAX_REPLICATION, "副本数 replication_allocation");
                } else {
                    unsupported.add("表属性 `" + key + "`=\"" + value
                            + "\" 表单不支持（只支持 tag.location.default: N）");
                }
            } else if ("replication_num".equals(lower)) {
                String num = value == null ? "" : value.trim();
                if (num.matches("[+-]?\\d+")) {
                    // D14：与 replication_allocation 同口径——纯数字但越界/溢出要 400，
                    // 不再静默报「不是整数」进 unsupported
                    draft.replication = parseBoundedInt(num, MIN_REPLICATION,
                            MAX_REPLICATION, "副本数 replication_num");
                } else {
                    unsupported.add("表属性 `" + key + "`=\"" + value + "\" 不是整数");
                }
            } else if (lower.startsWith("dynamic_partition.")) {
                String sub = lower.substring("dynamic_partition.".length());
                if (DYNAMIC_KEYS.contains(sub)) {
                    dynamicProps.put(sub, value == null ? "" : value.trim());
                } else {
                    unsupported.add("动态分区参数 `" + key + "` 表单不支持（切回表单会丢失）");
                }
            } else {
                unsupported.add("表属性 `" + key + "` 表单不支持（切回表单会丢失）");
            }
        }
    }

    // ---------------------------------------------------------------- 字段定义

    private static List<TableController.ColumnRequest> parseColumns(String body,
                                                                    List<String> unsupported,
                                                                    List<String> warnings,
                                                                    List<TableController.IndexRequest> indexes) {
        List<TableController.ColumnRequest> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : splitTopLevel(body, ',')) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            String mask = maskLiterals(text);
            if (INDEX_HEAD.matcher(mask).find()) {
                // REQ-DW-6：只认 INDEX_CLAUSE 那一种形态（命名单列 USING），其余照旧进 unsupported
                Matcher im = INDEX_CLAUSE.matcher(text);
                if (im.matches()) {
                    String comment = im.group(6) == null ? null
                            : im.group(6).substring(1, im.group(6).length() - 1)
                                    .replace("''", "'");
                    indexes.add(new TableController.IndexRequest(
                            im.group(1) != null ? im.group(1) : im.group(2),
                            im.group(3) != null ? im.group(3) : im.group(4),
                            im.group(5).toUpperCase(Locale.ROOT), comment));
                    continue;
                }
                unsupported.add("索引/约束定义 `" + snippet(text)
                        + "` 表单不支持（切回表单会丢失）");
                continue;
            }
            Matcher cm = COLUMN_PREFIX.matcher(text);
            if (!cm.matches()) {
                unsupported.add("字段定义 `" + snippet(text) + "` 无法解析");
                continue;
            }
            String name = cleanIdent(cm.group(1));
            String typeWord = cm.group(2);
            String params = cm.group(3) == null ? "" : cm.group(3).replaceAll("\\s+", "");
            String rest = cm.group(4) == null ? "" : cm.group(4);

            String type = typeWord + params;
            String inline = rest.trim();
            if (inline.startsWith("<")) {
                // ARRAY<T> / MAP<K,V> / STRUCT<...> 等嵌套类型
                int gt = inline.indexOf('>');
                type = typeWord + params + (gt >= 0 ? inline.substring(0, gt + 1) : inline);
                rest = gt >= 0 ? inline.substring(gt + 1) : "";
            }
            type = type.toUpperCase(Locale.ROOT);

            if (!seen.add(name)) {
                unsupported.add("字段名 `" + name + "` 重复，表单建表会拒绝");
                continue;
            }
            String typeIssue = TableCreateDdl.typeProblem(type, name);
            if (typeIssue != null) {
                unsupported.add("字段 `" + name + "` 的类型表单不支持：" + typeIssue);
            }
            if (!NAME.matcher(name).matches()) {
                unsupported.add("字段名 `" + name + "` 不符合表单命名规则");
            } else if (TableCreateDdl.isReservedName(name)) {
                unsupported.add("字段名 `" + name + "` 是 Doris/MySQL 保留字，表单建表会拒绝");
            }

            Boolean nullable = null;
            String comment = null;
            String defaultValue = null;
            String aggType = null;
            List<String> unknown = new ArrayList<>();
            List<Tok> toks = tokenize(rest);
            int i = 0;
            while (i < toks.size()) {
                Tok tok = toks.get(i);
                String word = tok.upper();
                if ("NOT".equals(word) && i + 1 < toks.size()
                        && "NULL".equals(toks.get(i + 1).upper())) {
                    nullable = Boolean.FALSE;
                    i += 2;
                    continue;
                }
                if ("NULL".equals(word)) {
                    nullable = Boolean.TRUE;
                    i++;
                    continue;
                }
                if ("DEFAULT".equals(word)) {
                    if (i + 1 < toks.size() && "NULL".equals(toks.get(i + 1).upper())) {
                        warnings.add("字段 `" + name + "` 的 DEFAULT NULL 等价于无默认值，已省略");
                        i += 2;
                        continue;
                    }
                    int j = i + 1;
                    int depth = 0;
                    int startPos = -1;
                    int endPos = -1;
                    while (j < toks.size()) {
                        Tok cur = toks.get(j);
                        if (depth == 0 && cur.type() == TokType.WORD
                                && DEFAULT_STOP.contains(cur.upper())) {
                            break;
                        }
                        if (cur.type() == TokType.SYMBOL && "(".equals(cur.raw())) {
                            depth++;
                        } else if (cur.type() == TokType.SYMBOL && ")".equals(cur.raw())) {
                            depth--;
                        }
                        if (startPos < 0) {
                            startPos = cur.start();
                        }
                        endPos = cur.end();
                        j++;
                    }
                    if (startPos < 0) {
                        unsupported.add("字段 `" + name + "` 的 DEFAULT 缺少值");
                        i = j;
                        continue;
                    }
                    String raw = rest.substring(startPos, endPos).trim();
                    defaultValue = defaultLiteralOf(raw, toks.subList(i + 1, j), name, unsupported);
                    i = j;
                    continue;
                }
                if ("COMMENT".equals(word)) {
                    if (i + 1 < toks.size() && toks.get(i + 1).type() == TokType.STRING) {
                        comment = toks.get(i + 1).value();
                        i += 2;
                    } else {
                        unsupported.add("字段 `" + name + "` 的 COMMENT 不是字符串常量");
                        i++;
                    }
                    continue;
                }
                if (tok.type() == TokType.WORD && AGG_TYPES.contains(word)) {
                    aggType = word;
                    i++;
                    continue;
                }
                unknown.add(tok.raw());
                i++;
            }
            if (!unknown.isEmpty()) {
                unsupported.add("字段 `" + name + "` 的修饰 `" + String.join(" ", unknown)
                        + "` 表单不支持（切回表单会丢失）");
            }

            out.add(new TableController.ColumnRequest(name, type, comment,
                    nullable == null ? Boolean.TRUE : nullable, aggType, defaultValue));
        }
        return out;
    }

    /** DEFAULT 只接受常量字面量；表达式（CURRENT_TIMESTAMP 等）进 unsupported。 */
    private static String defaultLiteralOf(String raw, List<Tok> toks, String columnName,
                                           List<String> unsupported) {
        if (toks.size() == 1) {
            Tok only = toks.get(0);
            if (only.type() == TokType.STRING) {
                if (only.value() == null || only.value().isEmpty()) {
                    unsupported.add("字段 `" + columnName
                            + "` 的默认值 `''`（空字符串）表单不支持");
                    return null;
                }
                return only.value();
            }
            if (only.type() == TokType.NUMBER) {
                return only.raw();
            }
            if (only.type() == TokType.WORD
                    && ("TRUE".equals(only.upper()) || "FALSE".equals(only.upper()))) {
                return only.upper().toLowerCase(Locale.ROOT);
            }
        }
        if (toks.size() == 2 && toks.get(0).type() == TokType.SYMBOL
                && ("-".equals(toks.get(0).raw()) || "+".equals(toks.get(0).raw()))
                && toks.get(1).type() == TokType.NUMBER) {
            return ("-".equals(toks.get(0).raw()) ? "-" : "") + toks.get(1).raw();
        }
        unsupported.add("字段 `" + columnName + "` 的默认值 `" + snippet(raw)
                + "` 表单只支持常量（数字 / 字符串 / true / false）");
        return null;
    }

    // ---------------------------------------------------------------- 文本工具

    private record Ident(String name, int end) {
    }

    private record Qualified(String database, String table, int end) {
    }

    private record Str(String value, int end) {
    }

    private enum TokType { WORD, STRING, BACKTICK, NUMBER, SYMBOL }

    private record Tok(TokType type, String raw, String value, int start, int end) {
        String upper() {
            return type == TokType.WORD ? raw.toUpperCase(Locale.ROOT) : "";
        }
    }

    /** 记录哪些尾部片段已经被解析过，剩下的报进 unsupported。 */
    private static final class Cover {
        final String raw;
        final String masked;
        final boolean[] consumed;

        Cover(String raw) {
            this.raw = raw;
            this.masked = maskLiterals(raw);
            this.consumed = new boolean[raw.length()];
        }

        void mark(int from, int to) {
            int end = Math.min(consumed.length, Math.max(0, to));
            for (int i = Math.max(0, from); i < end; i++) {
                consumed[i] = true;
            }
        }

        String leftover() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (consumed[i] || Character.isWhitespace(c) || c == ',' || c == ';') {
                    continue;
                }
                sb.append(c);
            }
            return sb.toString().trim();
        }
    }

    private static List<String> splitStatements(String sql) {
        String mask = maskLiterals(sql);
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < mask.length(); i++) {
            if (mask.charAt(i) == ';') {
                String part = sql.substring(start, i).trim();
                if (!part.isEmpty()) {
                    out.add(part);
                }
                start = i + 1;
            }
        }
        String last = sql.substring(start).trim();
        if (!last.isEmpty()) {
            out.add(last);
        }
        return out;
    }

    /** 去掉 -- / # 行注释与块注释（字符串、反引号内的内容原样保留）。 */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = endOfLiteral(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            if (c == '#') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 把字符串/反引号内容替换成空格（长度不变），用于关键字扫描与括号匹配。 */
    private static String maskLiterals(String s) {
        char[] out = s.toCharArray();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = endOfLiteral(s, i);
                for (int k = i; k < Math.min(end, s.length()); k++) {
                    out[k] = ' ';
                }
                i = end;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /** 只把 '...' / "..." 的内容替换成空格，反引号标识符保留（分区名要靠它匹配）。 */
    private static String maskStrings(String s) {
        char[] out = s.toCharArray();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int end = endOfLiteral(s, i);
                for (int k = i; k < Math.min(end, s.length()); k++) {
                    out[k] = ' ';
                }
                i = end;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /** 返回字面量结束后的下标（含闭合引号）；未闭合返回 length。 */
    private static int endOfLiteral(String s, int start) {
        char quote = s.charAt(start);
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (quote != '`' && i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    private static String unescapeLiteral(String raw) {
        if (raw.length() < 2) {
            return "";
        }
        char quote = raw.charAt(0);
        if (raw.charAt(raw.length() - 1) != quote) {
            return raw;
        }
        String body = raw.substring(1, raw.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '0' -> out.append('\0');
                    case '\\' -> out.append('\\');
                    case '\'' -> out.append('\'');
                    case '"' -> out.append('"');
                    default -> out.append('\\').append(next);
                }
                i++;
                continue;
            }
            if (c == quote && i + 1 < body.length() && body.charAt(i + 1) == quote) {
                out.append(quote);
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static Str readString(String s, int pos) {
        int i = skipWs(s, pos);
        if (i >= s.length()) {
            return null;
        }
        char quote = s.charAt(i);
        if (quote != '\'' && quote != '"') {
            return null;
        }
        int end = endOfLiteral(s, i);
        if (end - i < 2 || s.charAt(end - 1) != quote) {
            return null;
        }
        return new Str(unescapeLiteral(s.substring(i, end)), end);
    }

    private static Qualified readQualified(String s, int pos) {
        Ident first = readIdent(s, pos);
        if (first == null) {
            return null;
        }
        int dot = skipWs(s, first.end());
        if (dot < s.length() && s.charAt(dot) == '.') {
            Ident second = readIdent(s, skipWs(s, dot + 1));
            if (second == null) {
                return null;
            }
            return new Qualified(first.name(), second.name(), second.end());
        }
        return new Qualified(null, first.name(), first.end());
    }

    private static Ident readIdent(String s, int pos) {
        Matcher m = IDENT.matcher(s);
        m.region(Math.min(Math.max(pos, 0), s.length()), s.length());
        if (!m.lookingAt()) {
            return null;
        }
        String name = m.group(1) != null ? m.group(1) : m.group(2);
        return new Ident(name, m.end());
    }

    private static int findMatchingParen(String masked, int open) {
        if (open < 0 || open >= masked.length() || masked.charAt(open) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String text, char sep) {
        String mask = maskLiterals(text);
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == sep && depth == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    private static List<String> identList(String content) {
        List<String> out = new ArrayList<>();
        for (String item : splitTopLevel(content, ',')) {
            String name = cleanIdent(item);
            if (!name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static String cleanIdent(String s) {
        String value = s == null ? "" : s.trim();
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private static String singleIdent(String expression) {
        if (expression == null) {
            return null;
        }
        Matcher m = SINGLE_IDENT.matcher(expression);
        if (!m.matches()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static List<String> leadingWords(String masked, int count) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(masked);
        while (m.find() && out.size() < count) {
            out.add(m.group().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static String wordAt(String masked, int pos) {
        int i = skipWs(masked, pos);
        if (i >= masked.length()) {
            return "";
        }
        char start = masked.charAt(i);
        if (!Character.isLetter(start) && start != '_') {
            return "";
        }
        int j = i;
        while (j < masked.length()
                && (Character.isLetterOrDigit(masked.charAt(j)) || masked.charAt(j) == '_')) {
            j++;
        }
        return masked.substring(i, j).toUpperCase(Locale.ROOT);
    }

    private static int skipWs(String s, int pos) {
        int i = Math.max(0, pos);
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = endOfLiteral(s, i);
                String raw = s.substring(i, end);
                String value = raw.length() >= 2 && raw.charAt(raw.length() - 1) == c
                        ? unescapeLiteral(raw) : raw;
                out.add(new Tok(TokType.STRING, raw, value, i, end));
                i = end;
                continue;
            }
            if (c == '`') {
                int end = endOfLiteral(s, i);
                String raw = s.substring(i, end);
                String value = raw.length() >= 2 && raw.charAt(raw.length() - 1) == c
                        ? raw.substring(1, raw.length() - 1) : raw;
                out.add(new Tok(TokType.BACKTICK, raw, value, i, end));
                i = end;
                continue;
            }
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Tok(TokType.NUMBER, s.substring(i, j), s.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j))
                        || s.charAt(j) == '_' || s.charAt(j) == '$')) {
                    j++;
                }
                out.add(new Tok(TokType.WORD, s.substring(i, j), s.substring(i, j), i, j));
                i = j;
                continue;
            }
            out.add(new Tok(TokType.SYMBOL, String.valueOf(c), String.valueOf(c), i, i + 1));
            i++;
        }
        return out;
    }

    /**
     * D14：把数字文本解析成 [min,max] 内的整数，越界/溢出/非数字一律 400，
     * 文案带合法范围与收到的原值。
     *
     * <p>缺陷现场：{@code buckets 2147483648}、{@code tag.location.default: 99999999999999999999}
     * 走 {@code Integer.parseInt} 抛未捕获 {@code NumberFormatException}，
     * {@code /api/tables/sql/preview} 与 {@code /api/tables/sql} 都落全局 500。
     * 用户输入的越界数值是请求问题，不是服务端故障；用 BigInteger 解析是为了
     * 让超出 long 范围的值也能给出「越界 400」而不是「解析失败 500」。</p>
     */
    private static int parseBoundedInt(String raw, int min, int max, String label) {
        java.math.BigInteger value;
        try {
            value = new java.math.BigInteger(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(label + " 必须是 " + min + "-" + max
                    + " 的正整数，收到: " + raw.trim());
        }
        if (value.compareTo(java.math.BigInteger.valueOf(min)) < 0
                || value.compareTo(java.math.BigInteger.valueOf(max)) > 0) {
            throw ApiException.badRequest(label + " 必须是 " + min + "-" + max
                    + " 的正整数，收到: " + value);
        }
        return value.intValue();
    }

    private static Integer parseInt(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (!value.matches("[+-]?\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String snippet(String s) {
        String value = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }
}
