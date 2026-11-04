package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F-A（E2E-TRIAGE N2）：Doris 汇「CREATE_SCHEMA_WHEN_NOT_EXIST」自动建表的纯逻辑件。
 *
 * <p>背景：sink 表不存在时按缺省 {@code CREATE_SCHEMA_WHEN_NOT_EXIST} 放给连接器自建，
 * 建出来的是无主键表（Doris 落 DUPLICATE 模型），首跑必败
 * {@code The table ... has no primary keys}；DL-1 链1 靠预建表绕开，真路径首次执行才暴露。
 * 修复口径＝平台在提交作业前自己建表：列取源查询结果集元数据，键按源表主键
 * （或汇显式声明的 primary_keys）生成 {@code unique key}，与
 * {@code TableCreateDdl}（表管理页建表）同一套 Doris 语法口径。</p>
 *
 * <p>本类只做字符串/映射计算，不连库；连库与编排留在
 * {@link SyncTaskService#ensureSinkSchema(Map)}，便于离线单测。</p>
 */
final class DorisSinkAutoSchema {

    private DorisSinkAutoSchema() {
    }

    static final String CREATE_WHEN_NOT_EXIST = "CREATE_SCHEMA_WHEN_NOT_EXIST";

    /** 源结果集一列的元数据（从 ResultSetMetaData 读取，不依赖具体方言）。 */
    record Column(String name, int sqlType, int precision, int scale, String className) {
    }

    /**
     * 哪些任务走平台侧自动建表：
     * <ul>
     *   <li>Doris 汇（SeaTunnel Doris sink 根本不会建表，表不存在时作业直接失败）；</li>
     *   <li>Jdbc 汇但数据源是 DORIS（{@code withDatasource} 运行时打的
     *       {@code sinkEngineType} 标记）——连接器会自建无主键表，必须抢在前面建对。</li>
     * </ul>
     * 仅当 schema_save_mode 缺省或 CREATE_SCHEMA_WHEN_NOT_EXIST 时生效；
     * 其它取值（ERROR_WHEN_* 等）尊重用户语义，不动。缺省值与
     * {@code SyncTaskService.renderJdbc} :612、{@code SyncRunSummaryService.schemaMode} 同源。
     */
    static boolean shouldAutoCreate(String canonicalSinkType, Map<String, Object> sink) {
        boolean targetDoris = "Doris".equals(canonicalSinkType)
                || ("Jdbc".equals(canonicalSinkType) && isDorisEngine(sink));
        return targetDoris && CREATE_WHEN_NOT_EXIST.equalsIgnoreCase(effectiveSchemaSaveMode(sink));
    }

    private static boolean isDorisEngine(Map<String, Object> sink) {
        Object engine = sink.get("sinkEngineType");
        return engine != null && "DORIS".equalsIgnoreCase(String.valueOf(engine).trim());
    }

    /** schema_save_mode / schemaSaveMode 兼容读取；空/缺省 = CREATE_SCHEMA_WHEN_NOT_EXIST。 */
    static String effectiveSchemaSaveMode(Map<String, Object> sink) {
        Object mode = sink.get("schema_save_mode");
        if (mode == null || String.valueOf(mode).isBlank()) {
            mode = sink.get("schemaSaveMode");
        }
        return mode == null || String.valueOf(mode).isBlank()
                ? CREATE_WHEN_NOT_EXIST : String.valueOf(mode).trim();
    }

    /**
     * 连 Doris 执行 FE 侧 DDL 用的 JDBC URL：
     * 优先运行时展开数据源带来的 {@code jdbcUrl}；没有（旧任务/手写配置）就从
     * {@code fenodes} 取第一个 FE 主机、按 MySQL 协议口 9030 重建
     * （fenodesFor 把 {@code :9030} 改写成了 {@code :8030}，这里还原）。
     */
    static String sinkJdbcUrl(String canonicalSinkType, Map<String, Object> sink) {
        String url = text(sink.get("jdbcUrl"));
        if (url == null && "Jdbc".equals(canonicalSinkType)) {
            url = text(sink.get("url"));
        }
        if (url != null) {
            return url;
        }
        String fenodes = text(sink.get("fenodes"));
        if (fenodes == null) {
            throw ApiException.badRequest("Doris 目标缺少 fenodes/jdbcUrl，无法自动建表");
        }
        String first = fenodes.split(",")[0].trim();
        int colon = first.lastIndexOf(':');
        String host = colon > 0 && first.indexOf(']') < colon
                ? first.substring(0, colon) : first;
        return "jdbc:mysql://" + host + ":9030";
    }

    /**
     * 拆分目标表：带库前缀的 target（{@code db.tbl}）优先于 sink.database。
     * 标识符按 Doris 可接受的安全子集校验（拒绝反引号/引号/空格，避免拼进 DDL 时注入）。
     */
    static String[] splitQualified(String database, String target) {
        String t = target == null ? "" : target.trim();
        String db = database == null ? "" : database.trim();
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            db = t.substring(0, dot);
            t = t.substring(dot + 1);
        }
        if (db.isEmpty()) {
            throw ApiException.badRequest("缺少 sinkConfig.database，无法定位目标表: " + target);
        }
        return new String[] {requireDorisIdent(db, "目标库名"), requireDorisIdent(t, "目标表名")};
    }

    static String requireDorisIdent(String value, String field) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw ApiException.badRequest(field + " 不是合法 Doris 标识符: " + value);
        }
        return value;
    }

    /** 汇侧显式声明的主键（SeaTunnel Jdbc sink 的 primary_keys 列表/逗号串）；没有返回空表。 */
    static List<String> declaredPrimaryKeys(Map<String, Object> sink) {
        Object value = sink.get("primary_keys");
        if (value == null) {
            value = sink.get("primaryKeys");
        }
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String name = text(item);
                if (name != null) {
                    out.add(name);
                }
            }
        } else if (value != null && !String.valueOf(value).isBlank()) {
            for (String part : String.valueOf(value).split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /**
     * 生成 Doris 建表 DDL（表管理页 {@code TableCreateDdl} 同口径：小写关键字、
     * {@code engine=olap}、{@code distributed by hash(...) buckets 1}、
     * {@code replication_allocation tag.location.default: 1}）。
     *
     * <ul>
     *   <li>有主键 → {@code unique key(pk...)}，并按 Doris「key 列必须是最前缀」重排列序；
     *       主键列里结果集不存在的（被 mapping 裁掉/改名）逐个丢弃，全丢则退化为无主键；</li>
     *   <li>无主键 → {@code duplicate key(首列)}（追加写语义，能建表但不支持按键合并）；</li>
     *   <li>分桶列取第一个 key 列，桶数 1、副本 1，与平台建表默认一致。</li>
     * </ul>
     */
    static String buildCreateTableDdl(String database, String table,
                                      List<Column> columns, List<String> primaryKeys) {
        if (columns == null || columns.isEmpty()) {
            throw ApiException.badRequest("源结果集没有字段，无法自动生成建表 DDL");
        }
        String db = requireDorisIdent(database, "目标库名");
        String tbl = requireDorisIdent(table, "目标表名");

        Set<String> names = new LinkedHashSet<>();
        for (Column column : columns) {
            requireDorisIdent(column.name(), "字段名");
            if (!names.add(column.name().toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("源结果集字段名重复: " + column.name());
            }
        }
        // 主键按给定顺序保留（getPrimaryKeys 已按 KEY_SEQ 排好）；大小写不敏感对齐列名
        List<String> keys = new ArrayList<>();
        for (String pk : primaryKeys == null ? List.<String>of() : primaryKeys) {
            for (Column column : columns) {
                if (column.name().equalsIgnoreCase(pk) && !containsIgnoreCase(keys, column.name())) {
                    keys.add(column.name());
                }
            }
        }
        boolean unique = !keys.isEmpty();
        if (!unique) {
            keys.add(columns.get(0).name());
        }
        List<Column> ordered = new ArrayList<>();
        for (String key : keys) {
            for (Column column : columns) {
                if (column.name().equalsIgnoreCase(key)) {
                    ordered.add(column);
                }
            }
        }
        for (Column column : columns) {
            if (!containsIgnoreCase(keys, column.name())) {
                ordered.add(column);
            }
        }

        StringBuilder ddl = new StringBuilder();
        ddl.append("create table if not exists `").append(db).append("`.`").append(tbl).append("` (\n");
        for (int i = 0; i < ordered.size(); i++) {
            Column column = ordered.get(i);
            boolean isKey = containsIgnoreCase(keys, column.name());
            ddl.append("  `").append(column.name()).append("` ").append(dorisType(column))
                    .append(isKey ? " not null" : " null");
            ddl.append(i < ordered.size() - 1 ? ",\n" : "\n");
        }
        ddl.append(") engine=olap\n");
        ddl.append(unique ? "unique key(" : "duplicate key(")
                .append(joinQuoted(keys)).append(")\n");
        ddl.append("distributed by hash(`").append(keys.get(0)).append("`) buckets 1\n");
        ddl.append("properties (\"replication_allocation\"=\"tag.location.default: 1\")");
        return ddl.toString();
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String item : list) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static String joinQuoted(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('`').append(values.get(i)).append('`');
        }
        return sb.toString();
    }

    /**
     * java.sql.Types → Doris 列类型。方言无关的结果集类型码优先，
     * {@code OTHER}（PG text/uuid/json 等）按列类名兜底；二进制/时间等 Doris
     * 无对应类型的落 {@code string}（追加语义可存，值以文本呈现）。
     */
    static String dorisType(Column column) {
        return switch (column.sqlType()) {
            case Types.TINYINT -> "tinyint";
            case Types.SMALLINT -> "smallint";
            case Types.INTEGER -> "int";
            case Types.BIGINT -> "bigint";
            case Types.FLOAT, Types.REAL -> "float";
            case Types.DOUBLE -> "double";
            case Types.NUMERIC, Types.DECIMAL -> decimalType(column.precision(), column.scale());
            case Types.CHAR, Types.NCHAR -> "char(" + clampSize(column.precision(), 1, 255, 1) + ")";
            case Types.VARCHAR, Types.NVARCHAR -> varcharType(column.precision());
            case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB -> "string";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.DATE -> "date";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "datetime";
            // Doris 没有 time / binary 类型；落 string 不丢数据可读性
            case Types.TIME, Types.TIME_WITH_TIMEZONE,
                 Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "string";
            default -> typeByClassName(column.className());
        };
    }

    static String decimalType(int precision, int scale) {
        if (precision <= 0) {
            // 无界 numeric（PG numeric）：按 Doris 上限给宽裕默认
            return "decimal(38, 10)";
        }
        int dp = Math.min(precision, 38);
        int ds = Math.max(0, Math.min(scale, dp));
        return "decimal(" + dp + ", " + ds + ")";
    }

    static String varcharType(int size) {
        if (size <= 0 || size > 65533) {
            return "string";
        }
        return "varchar(" + size + ")";
    }

    private static int clampSize(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }

    private static String typeByClassName(String className) {
        String name = className == null ? "" : className;
        if (name.contains("Boolean")) {
            return "boolean";
        }
        if (name.contains("Integer") || name.contains("Short") || name.contains("Byte")) {
            return "int";
        }
        if (name.contains("Long")) {
            return "bigint";
        }
        if (name.contains("BigDecimal")) {
            return "decimal(38, 10)";
        }
        if (name.contains("Double") || name.contains("Float")) {
            return "double";
        }
        if (name.contains("Timestamp")) {
            return "datetime";
        }
        if (name.contains("LocalDate") || name.contains("Date")) {
            return "date";
        }
        return "string";
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}
