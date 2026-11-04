package com.lab.dataworks.lake;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Iceberg 湖表 DDL 生成（V100）。
 *
 * <p>Doris 把 Iceberg 分区映射为 {@code PARTITION BY LIST (<transform...>) ()}：
 * identity 直接写列名，day 写 {@code day(col)}，bucket 写 {@code bucket(N, col)}；
 * 分区体必须是空括号 {@code ()}，由 Doris 在写入时自动创建 Iceberg 分区。</p>
 *
 * <p><b>刻意不生成</b> {@code DISTRIBUTED BY} / {@code BUCKETS} / {@code replication}：
 * 这些是 Doris 内表的副本与分桶概念，Iceberg 表不支持，带上会被 Doris 拒绝。</p>
 */
public final class LakeDdl {

    /**
     * 允许的列类型（大小写不敏感）。不接受任意 SQL 片段。
     *
     * <p>Doris 4.1.4 的 Iceberg 建表解析器只认 {@code DATETIME}，不认
     * {@code TIMESTAMP}（实测报 {@code mismatched input 'TIMESTAMP' expecting ... DATETIME}）。
     * 对外仍接受 Iceberg 惯用的 {@code TIMESTAMP}，生成 DDL 时映射成 {@code DATETIME}。</p>
     */
    private static final Pattern TYPE = Pattern.compile(
            "^(BOOLEAN|TINYINT|SMALLINT|INT|INTEGER|BIGINT|FLOAT|DOUBLE"
            + "|DECIMAL\\(\\d{1,2},\\s*\\d{1,2}\\)"
            + "|DATE|TIMESTAMP(\\([0-6]\\))?|DATETIME(\\([0-6]\\))?|STRING"
            + "|VARCHAR\\(\\d{1,5}\\)|CHAR\\(\\d{1,5}\\)"
            + "|BINARY|UUID)$",
            Pattern.CASE_INSENSITIVE);

    private LakeDdl() {
    }

    public record Column(String name, String type, Boolean nullable, String comment) {
    }

    /**
     * @param kind    NONE / IDENTITY / BUCKET / DAY
     * @param column  分区列（kind=NONE 时忽略）
     * @param buckets 仅 BUCKET 使用，1–128
     */
    public record Partition(String kind, String column, Integer buckets) {
    }

    public record Result(String ddl, int columnCount, String partitionKind,
                         List<String> warnings) {
    }

    public static Result generate(String catalog, String database, String table, String comment,
                                  List<Column> columns, Partition partition,
                                  Integer formatVersion) {
        String cat = LakeCatalogTemplate.requireIdentifier(catalog, "catalog");
        String db = LakeCatalogTemplate.requireIdentifier(database, "库名");
        String tbl = LakeCatalogTemplate.requireIdentifier(table, "表名");
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个字段");
        }
        Set<String> names = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        boolean timestampMapped = false;
        for (Column column : columns) {
            if (column == null) {
                continue;
            }
            String name = LakeCatalogTemplate.requireIdentifier(column.name(), "字段名");
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("字段名重复: " + name);
            }
            String type = typeOf(column.type());
            if (type.startsWith("DATETIME") && column.type() != null
                    && column.type().trim().toUpperCase(Locale.ROOT).startsWith("TIMESTAMP")) {
                timestampMapped = true;
            }
            boolean nullable = column.nullable == null || column.nullable();
            if (body.length() > 0) {
                body.append(",\n");
            }
            body.append("  ").append(quote(name)).append(' ').append(type);
            if (!nullable) {
                body.append(" NOT NULL");
            }
            String colComment = literal(column.comment());
            if (!colComment.isBlank()) {
                body.append(" COMMENT '").append(colComment).append('\'');
            }
        }

        if (timestampMapped) {
            warnings.add("TIMESTAMP 已按 Doris 4.x 要求映射为 DATETIME 写入 DDL");
        }
        Partition normalized = normalizePartition(partition, names, columns, warnings);
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(quote(cat)).append('.').append(quote(db)).append('.')
                .append(quote(tbl)).append(" (\n").append(body).append("\n)");
        if (normalized != null && !"NONE".equals(normalized.kind())) {
            ddl.append("\nPARTITION BY LIST (").append(transformOf(normalized)).append(") ()");
        }
        int version = formatVersion == null ? 2 : formatVersion;
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("formatVersion 只能是 1 或 2");
        }
        ddl.append("\nPROPERTIES ('format-version' = '").append(version)
                .append("', 'write-format' = 'parquet')");
        // 表注释只回显，不进 DDL：不同 Doris 版本对 Iceberg CREATE TABLE 的 COMMENT
        // 支持不一致，写进去会让整条 DDL 失败；注释仍由平台侧保存/展示。
        if (comment != null && !comment.isBlank()) {
            warnings.add("表注释不写入 Iceberg DDL（避免 Doris 版本差异导致建表失败），仅随接口返回");
        }
        return new Result(ddl.toString(), names.size(),
                normalized == null ? "NONE" : normalized.kind(), warnings);
    }

    /** 在湖 Catalog 里建库（Iceberg 建表前必须已有命名空间）。 */
    public static String createDatabase(String catalog, String database, String comment) {
        String cat = LakeCatalogTemplate.requireIdentifier(catalog, "catalog");
        String db = LakeCatalogTemplate.requireIdentifier(database, "库名");
        StringBuilder ddl = new StringBuilder("CREATE DATABASE IF NOT EXISTS ")
                .append(quote(cat)).append('.').append(quote(db));
        String text = literal(comment);
        if (!text.isBlank()) {
            ddl.append(" COMMENT '").append(text).append('\'');
        }
        return ddl.toString();
    }

    public static String dropTable(String catalog, String database, String table) {
        return "DROP TABLE " + quote(LakeCatalogTemplate.requireIdentifier(catalog, "catalog"))
                + "." + quote(LakeCatalogTemplate.requireIdentifier(database, "库名"))
                + "." + quote(LakeCatalogTemplate.requireIdentifier(table, "表名"));
    }

    // ---------------------------------------------------------------- 内部

    private static Partition normalizePartition(Partition partition, Set<String> names,
                                                 List<Column> columns, List<String> warnings) {
        if (partition == null || partition.kind() == null || partition.kind().isBlank()
                || "NONE".equalsIgnoreCase(partition.kind())) {
            return null;
        }
        String kind = partition.kind().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("IDENTITY", "BUCKET", "DAY").contains(kind)) {
            throw new IllegalArgumentException("partition.kind 只能是 NONE / IDENTITY / BUCKET / DAY");
        }
        String column = partition.column() == null ? "" : partition.column().trim();
        if (!names.contains(column.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("分区列必须是已定义的字段: " + column);
        }
        String type = columns.stream()
                .filter(c -> c != null && c.name() != null
                        && c.name().equalsIgnoreCase(column))
                .findFirst().map(Column::type).orElse("").trim().toUpperCase(Locale.ROOT);
        if ("DAY".equals(kind)
                && !(type.startsWith("DATE") || type.startsWith("DATETIME")
                     || type.startsWith("TIMESTAMP"))) {
            throw new IllegalArgumentException("DAY 分区要求 DATE / DATETIME / TIMESTAMP 字段: "
                    + column + " 是 " + type);
        }
        Integer buckets = partition.buckets();
        if ("BUCKET".equals(kind)) {
            if (buckets == null) {
                buckets = 8;
            }
            if (buckets < 1 || buckets > 128) {
                throw new IllegalArgumentException("BUCKET 分桶数必须在 1–128 之间");
            }
            if (buckets < 4) {
                warnings.add("Iceberg bucket 分桶数 " + buckets + " 偏小，建议 >= 4");
            }
        } else {
            buckets = null;
        }
        return new Partition(kind, column, buckets);
    }

    private static String transformOf(Partition partition) {
        String column = partition.column();
        return switch (partition.kind()) {
            case "DAY" -> "day(" + quote(column) + ")";
            case "BUCKET" -> "bucket(" + partition.buckets() + ", " + quote(column) + ")";
            default -> quote(column);
        };
    }

    private static String typeOf(String raw) {
        String type = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("不支持的字段类型: " + raw);
        }
        String normalized = type.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        // Doris 4.1.4 建 Iceberg 表不接受 TIMESTAMP 关键字，统一映射为 DATETIME。
        if (normalized.startsWith("TIMESTAMP")) {
            return "DATETIME" + normalized.substring("TIMESTAMP".length());
        }
        return normalized;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /** SQL 字符串字面量：禁止换行/控制字符，单引号翻倍。 */
    private static String literal(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        // 先转义反斜杠，再翻倍单引号：否则结尾的 \ 会吃掉结束引号，拼出第二条语句
        return text.replace("\\", "\\\\").replace("'", "''");
    }
}
