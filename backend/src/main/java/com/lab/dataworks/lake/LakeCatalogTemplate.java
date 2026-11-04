package com.lab.dataworks.lake;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Iceberg 湖数据源的配置模板与错误码（V100）。
 *
 * <p>这个类只放「口径」：前端用来渲染配置项、后端用来校验，
 * 错误码同时作为接口文案前缀（例如 {@code LAKE_CATALOG_CREATE_FAILED: ...}），
 * 便于验收脚本与运维按码定位，不依赖某一条中文措辞。</p>
 */
public final class LakeCatalogTemplate {

    /** 数据源类型（platform.datasource.ds_type）。 */
    public static final String DS_TYPE = "ICEBERG";
    public static final String ENGINE = "lake";

    /** Catalog 名/库名/表名：与 Doris 标识符一致，禁止拼接出 SQL 片段。 */
    public static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** 支持的 Iceberg catalog 类型（Doris 属性 iceberg.catalog.type）。 */
    public static final List<String> CATALOG_TYPES = List.of("hadoop", "hive", "rest", "glue");

    public static final Set<String> STATUSES = Set.of("unknown", "available", "error");

    // ---------------------------------------------------------------- 错误码

    public static final String CONFIG_INVALID = "LAKE_CONFIG_INVALID";
    public static final String CATALOG_CREATE_FAILED = "LAKE_CATALOG_CREATE_FAILED";
    public static final String CATALOG_ALTER_FAILED = "LAKE_CATALOG_ALTER_FAILED";
    public static final String CATALOG_REFRESH_FAILED = "LAKE_CATALOG_REFRESH_FAILED";
    public static final String CATALOG_NOT_FOUND = "LAKE_CATALOG_NOT_FOUND";
    public static final String CATALOG_FORBIDDEN = "LAKE_CATALOG_FORBIDDEN";
    public static final String CATALOG_UNAVAILABLE = "LAKE_CATALOG_UNAVAILABLE";
    public static final String STORAGE_UNREACHABLE = "LAKE_STORAGE_UNREACHABLE";
    public static final String GRANT_FAILED = "LAKE_GRANT_FAILED";
    public static final String DDL_INVALID = "LAKE_DDL_INVALID";
    public static final String DDL_FAILED = "LAKE_DDL_FAILED";
    public static final String TABLE_NOT_FOUND = "LAKE_TABLE_NOT_FOUND";
    public static final String CATALOG_IN_USE = "LAKE_CATALOG_IN_USE";

    private LakeCatalogTemplate() {
    }

    /** GET /api/lake/template：前端表单 + 错误码文案（全部进仓库，不散落在页面里）。 */
    public static Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dsType", DS_TYPE);
        out.put("engine", ENGINE);
        out.put("catalogTypes", CATALOG_TYPES.stream().map(type -> Map.of(
                "value", type,
                "label", switch (type) {
                    case "hadoop" -> "Hadoop（文件系统元数据，S3/MinIO）";
                    case "hive" -> "Hive Metastore";
                    case "rest" -> "Iceberg REST";
                    default -> "AWS Glue";
                },
                "requiredConfig", switch (type) {
                    case "hive", "rest" -> List.of("uri");
                    default -> List.of();
                })).toList());
        out.put("fields", List.of(
                field("catalog", "Catalog 名称", true,
                        "iceberg_lake", "字母或下划线开头，只含字母/数字/下划线"),
                field("catalogType", "Catalog 类型", true, "hadoop", "默认 hadoop"),
                field("warehouse", "Warehouse", true, "s3://lake/warehouse",
                        "Iceberg 表数据的 S3/MinIO 路径"),
                field("endpoint", "S3 Endpoint", true,
                        "http://minio.dw-dev.svc.cluster.local:9000", "MinIO 一般需要 path style"),
                field("region", "Region", false, "us-east-1", "AWS S3 或 MinIO 可留空"),
                field("uri", "Metastore / REST URI", false,
                        "thrift://hive-metastore:9083", "hive / rest 类型必填"),
                field("usePathStyle", "Path Style", false, "true", "MinIO 建议 true")));
        out.put("partitionKinds", List.of(
                Map.of("value", "NONE", "label", "不分区"),
                Map.of("value", "IDENTITY", "label", "identity（按列值）"),
                Map.of("value", "BUCKET", "label", "bucket（哈希分桶）"),
                Map.of("value", "DAY", "label", "day（按天）")));
        out.put("errorCodes", List.of(
                Map.of("code", CONFIG_INVALID, "message", "配置不完整或不合法"),
                Map.of("code", CATALOG_CREATE_FAILED, "message", "Doris 创建 Catalog 失败"),
                Map.of("code", CATALOG_ALTER_FAILED, "message", "Doris 更新 Catalog 属性失败"),
                Map.of("code", CATALOG_REFRESH_FAILED, "message", "Doris 刷新 Catalog 失败"),
                Map.of("code", CATALOG_NOT_FOUND, "message", "Catalog 不存在或未纳入当前租户"),
                Map.of("code", CATALOG_FORBIDDEN, "message", "当前租户无权访问该 Catalog"),
                Map.of("code", CATALOG_UNAVAILABLE, "message", "Catalog 状态异常，请先修复并刷新"),
                Map.of("code", STORAGE_UNREACHABLE, "message", "无法访问 Warehouse / S3 端点"),
                Map.of("code", GRANT_FAILED, "message", "授权给平台业务账号失败"),
                Map.of("code", DDL_INVALID, "message", "建表参数不合法"),
                Map.of("code", DDL_FAILED, "message", "Doris 执行湖表 DDL 失败"),
                Map.of("code", TABLE_NOT_FOUND, "message", "湖表不存在"),
                Map.of("code", CATALOG_IN_USE, "message", "Catalog 仍被使用，不能删除")));
        return out;
    }

    private static Map<String, Object> field(String key, String label, boolean required,
                                             String placeholder, String hint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("required", required);
        item.put("placeholder", placeholder);
        item.put("hint", hint);
        return item;
    }

    /** 统一错误文案：{@code CODE: 可操作说明（原因: Doris 原始信息）}。 */
    public static String message(String code, String hint, String detail) {
        String text = code + ": " + hint;
        if (detail != null && !detail.isBlank()) {
            String trimmed = detail.trim();
            if (trimmed.length() > 500) {
                trimmed = trimmed.substring(0, 500) + "…";
            }
            text += "（原因: " + trimmed + "）";
        }
        return text;
    }

    public static boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value.trim()).matches();
    }

    public static String requireIdentifier(String value, String label) {
        if (!validIdentifier(value)) {
            throw new IllegalArgumentException(label + " 不合法（字母或下划线开头，只含字母/数字/下划线）: "
                    + value);
        }
        return value.trim();
    }

    /** catalogType 规范化；未知类型直接拒绝。 */
    public static String normalizeCatalogType(String raw) {
        String type = raw == null || raw.isBlank()
                ? "hadoop" : raw.trim().toLowerCase(Locale.ROOT);
        if (!CATALOG_TYPES.contains(type)) {
            throw new IllegalArgumentException("catalogType 只能是 "
                    + String.join(" / ", CATALOG_TYPES) + ": " + raw);
        }
        return type;
    }
}
