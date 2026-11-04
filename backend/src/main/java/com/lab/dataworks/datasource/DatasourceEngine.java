package com.lab.dataworks.datasource;

import java.util.Locale;

/**
 * {@code ds_type} → {@code engine} / {@code queryable} 的唯一口径（V99）。
 *
 * <p>平台只有一条 SQL 执行链路（Doris）。Kafka 与文件类数据源是「入湖/导入的来源」，
 * 不能直接写进 SQL；JDBC 源（MySQL / PostgreSQL / ClickHouse）虽然本身能执行 SQL，
 * 但在本平台里也是进 Doris 之后再查，所以 {@code queryable=true}（可被查询前置校验放行），
 * 只在结果里给出「将使用 Doris 执行」的提示。</p>
 */
public final class DatasourceEngine {

    public static final String DORIS = "doris";
    public static final String KAFKA = "kafka";
    public static final String FILESET = "fileset";
    public static final String JDBC = "jdbc";
    /** V100：Iceberg 外部 Catalog；执行仍在 Doris（catalog.db.table）。 */
    public static final String LAKE = "lake";

    /** 平台的 SQL 控制台固定使用的引擎。 */
    public static final String PLATFORM_ENGINE = DORIS;

    private DatasourceEngine() {
    }

    /** 按数据源类型推导引擎分类（未知类型按 JDBC 处理，保持与历史行为一致）。 */
    public static String engineOf(String dsType) {
        String type = dsType == null ? "" : dsType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "DORIS" -> DORIS;
            case "KAFKA" -> KAFKA;
            case "ICEBERG" -> LAKE;
            case "S3FILE", "LOCALFILE", "LOCAL_FILE", "FILESET", "FILE", "SFTPFILE", "S3" -> FILESET;
            default -> JDBC;
        };
    }

    /** 是否支持 SQL 查询：Kafka 与文件类不支持，其余放行。 */
    public static boolean queryable(String dsType) {
        String engine = engineOf(dsType);
        return !KAFKA.equals(engine) && !FILESET.equals(engine);
    }

    /** 命中不可查数据源时的统一提示；Kafka / 文件类给出可执行的下一步。 */
    public static String unsupportedMessage(String name, String engine) {
        return switch (engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT)) {
            case KAFKA -> "Kafka 不支持 SQL 查询，请先入湖或导入";
            case FILESET -> "文件数据集不支持 SQL 查询，请先入湖或导入";
            default -> (name == null || name.isBlank() ? "该数据源" : name)
                    + " 不支持 SQL 查询，请先入湖或导入";
        };
    }
}
