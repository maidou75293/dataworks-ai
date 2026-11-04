package com.lab.dataworks.query;

import com.lab.dataworks.datasource.DatasourceEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单引擎（Doris）查询的前置校验。
 *
 * <p>平台只有 Doris 一条执行链路：SQL 里出现 Kafka / 文件类数据源时，不能靠「换个引擎执行」，
 * 只能先入湖或导入。这里按 SQL 解析出来的表引用（{@code schema.table} 或 {@code table}）
 * 匹配数据源的库名 / 数据源名：</p>
 *
 * <ul>
 *   <li>命中 {@code queryable=false} 的数据源 → 拒绝（400），提示先入湖/导入；</li>
 *   <li>命中 JDBC 数据源但有多个 / 非 Doris → 放行，并给出「将使用 Doris 执行」的提示；</li>
 *   <li>解析失败（表引用为空）或没有任何命中 → 放行，照常走 Doris（fail-open）。</li>
 * </ul>
 *
 * <p>匹配口径刻意保持宽松：大小写不敏感；库名同时接受数据源名、名字规范化后的下划线形式
 * （例如 {@code Kafka Demo → kafka_demo}）、config_json 里的 database/schema、以及 JDBC URL
 * 末尾的库名。与 {@code DataGuardService} 的表解析结果一一对应。</p>
 *
 * <p>本类是纯函数，不访问数据库，便于单测锁住口径。</p>
 */
public final class QueryEngineGuard {

    private QueryEngineGuard() {
    }

    /** 前置校验需要的最小数据源信息；{@code databases} 是全部可用于匹配的别名。 */
    public record Datasource(long id, long projectId, String name, String dsType,
                             String engine, boolean queryable, List<String> databases) {
        public Datasource {
            databases = databases == null ? List.of() : List.copyOf(databases);
        }
    }

    /**
     * @param blocked            是否拒绝执行
     * @param message            拒绝原因（blocked=true 时非空）
     * @param blockedDatasources 命中的不可查数据源名
     * @param involved           本次 SQL 涉及的（可查）数据源名，去重保序
     * @param engines            涉及数据源的引擎分类，去重保序
     * @param hint               「涉及 N 个数据源，将使用 Doris 执行」的提示；无命中时为 null
     */
    public record Decision(boolean blocked, String message, List<String> blockedDatasources,
                           List<String> involved, List<String> engines, String hint) {

        static Decision pass(List<String> involved, List<String> engines, String hint) {
            return new Decision(false, null, List.of(), involved, engines, hint);
        }
    }

    /**
     * @param sources 当前租户（或项目）可见的数据源
     * @param tables  DataGuardService 解析出的表引用，元素形如 {@code db.table} 或 {@code table}
     */
    public static Decision evaluate(List<Datasource> sources, List<String> tables) {
        List<Datasource> candidates = sources == null ? List.of() : sources;
        List<String> refs = tables == null ? List.of() : tables;

        // 同一数据源只算一次：join 两张表不代表涉及两个数据源
        Map<Long, Datasource> matched = new LinkedHashMap<>();
        List<String> blockedNames = new ArrayList<>();
        List<String> blockedMessages = new ArrayList<>();

        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            String text = ref.trim();
            String[] parts = text.split("\\.");
            String catalog = parts.length >= 3 ? unquote(parts[parts.length - 3]) : null;
            String schema = parts.length >= 2 ? unquote(parts[parts.length - 2]) : null;
            String table = unquote(parts[parts.length - 1]);
            if (table.isBlank()) {
                continue;
            }
            for (Datasource ds : candidates) {
                if (!matches(ds, catalog, schema, table)) {
                    continue;
                }
                matched.putIfAbsent(ds.id(), ds);
                if (!ds.queryable()) {
                    String engine = ds.engine() == null ? "" : ds.engine();
                    String message = DatasourceEngine.unsupportedMessage(ds.name(), engine);
                    if (!blockedMessages.contains(message)) {
                        blockedMessages.add(message);
                    }
                    if (ds.name() != null && !blockedNames.contains(ds.name())) {
                        blockedNames.add(ds.name());
                    }
                }
            }
        }

        List<String> involved = new ArrayList<>();
        List<String> engines = new ArrayList<>();
        for (Datasource ds : matched.values()) {
            involved.add(ds.name());
            String engine = ds.engine() == null || ds.engine().isBlank()
                    ? DatasourceEngine.engineOf(ds.dsType())
                    : ds.engine();
            if (!engines.contains(engine)) {
                engines.add(engine);
            }
        }

        if (!blockedMessages.isEmpty()) {
            return new Decision(true, blockedMessages.get(0), blockedNames, involved, engines, null);
        }

        String hint = hintOf(involved, engines);
        return Decision.pass(involved, engines, hint);
    }

    /** 「涉及 N 个数据源（a、b），将使用 Doris 执行」；没有命中任何数据源时返回 null。 */
    static String hintOf(List<String> involved, List<String> engines) {
        if (involved == null || involved.isEmpty()) {
            return null;
        }
        String names = involved.size() == 1
                ? "（" + involved.get(0) + "）"
                : "（" + String.join("、", involved) + "）";
        return "涉及 " + involved.size() + " 个数据源" + names + "，将使用 Doris 执行";
    }

    /**
     * V100：三段式 {@code catalog.schema.table} 先按 Catalog 名匹配湖数据源；
     * 内表（internal / doris）与两段式仍按库名/数据源名匹配。
     */
    private static boolean matches(Datasource ds, String catalog, String schema, String table) {
        if (catalog != null && !catalog.isBlank() && !isInternalCatalog(catalog)) {
            for (String alias : ds.databases()) {
                if (alias != null && alias.equalsIgnoreCase(catalog)) {
                    return true;
                }
            }
            return false;
        }
        if (schema != null && !schema.isBlank()) {
            for (String alias : ds.databases()) {
                if (alias != null && alias.equalsIgnoreCase(schema)) {
                    return true;
                }
            }
            return false;
        }
        // 没有库前缀时只按数据源名匹配，避免把普通表名误判成数据源
        return ds.name() != null && ds.name().equalsIgnoreCase(table);
    }

    private static boolean isInternalCatalog(String catalog) {
        return "internal".equalsIgnoreCase(catalog) || "doris".equalsIgnoreCase(catalog)
                || "pg".equalsIgnoreCase(catalog);
    }

    private static String unquote(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2
                && ((text.startsWith("`") && text.endsWith("`"))
                    || (text.startsWith("\"") && text.endsWith("\"")))) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 供补全过滤：不可查数据源的名字/库别名（小写）。 */
    public static Set<String> notQueryableAliases(List<Datasource> sources) {
        Set<String> out = new LinkedHashSet<>();
        if (sources == null) {
            return out;
        }
        for (Datasource ds : sources) {
            if (ds.queryable()) {
                continue;
            }
            for (String alias : ds.databases()) {
                if (alias != null && !alias.isBlank()) {
                    out.add(alias.toLowerCase(Locale.ROOT));
                }
            }
            if (ds.name() != null && !ds.name().isBlank()) {
                out.add(ds.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
