package com.lab.dataworks.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.datasource.DatasourceEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据源「查询能力」读取（V99）：列表标签、查询前置校验、补全过滤共用同一份口径。
 *
 * <p>可见范围与数据源列表接口一致：当前租户的项目，加上所有租户都能看到的
 * {@code config_json.builtin = true} 内置 Doris。带 projectId 时只取「该项目 + 内置」，
 * 避免别的项目里同名的 Kafka 数据源把本项目的查询拦掉。</p>
 *
 * <p>不做缓存：数据源是低频变更的小表，每次查询多一条按 (tenant_id) 走索引的 select，
 * 换来的是「刚改完 queryable 立即生效」，比缓存几秒更符合预期。</p>
 */
@Component
public class QueryCapabilities {

    private static final List<String> CONFIG_DB_KEYS =
            List.of("database", "db", "schema", "namespace", "defaultDatabase", "catalog", "dbName");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public QueryCapabilities(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 当前租户（或指定项目）可见的数据源，带引擎分类与可匹配别名。 */
    public List<QueryEngineGuard.Datasource> load(Long projectId) {
        String tenant = TenantScope.current();
        StringBuilder sql = new StringBuilder("""
                select d.id, d.project_id, d.name, d.ds_type, d.jdbc_url, d.config_json,
                       d.engine, d.queryable, p.code as project_code
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where """);
        List<Object> args = new ArrayList<>();
        if (projectId == null) {
            sql.append("(p.tenant_id = ?"
                    + " or coalesce(d.config_json ->> 'builtin', 'false') = 'true')");
            args.add(tenant);
        } else {
            // 项目必须属于当前租户；内置 Doris 对所有租户可见
            sql.append("((p.tenant_id = ? and d.project_id = ?)"
                    + " or coalesce(d.config_json ->> 'builtin', 'false') = 'true')");
            args.add(tenant);
            args.add(projectId);
        }
        sql.append(" order by d.id");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<QueryEngineGuard.Datasource> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String dsType = text(row.get("ds_type"));
            String engine = text(row.get("engine"));
            if (engine.isBlank()) {
                engine = DatasourceEngine.engineOf(dsType);
            }
            Object rawQueryable = row.get("queryable");
            boolean queryable = rawQueryable == null
                    ? DatasourceEngine.queryable(dsType)
                    : parseBoolean(rawQueryable);
            Map<String, Object> config = configOf(row.get("config_json"));
            out.add(new QueryEngineGuard.Datasource(
                    number(row.get("id")), number(row.get("project_id")),
                    text(row.get("name")), dsType, engine, queryable,
                    aliases(text(row.get("name")), text(row.get("jdbc_url")), config)));
        }
        return out;
    }

    /**
     * GET /api/query/capabilities 的响应体。
     *
     * <p>D24：内置 Doris 对**所有租户可查**是设计（{@link #load} 故意带上 builtin 数据源，
     * 否则别的租户连平台数仓都查不了），但它的项目号与库名清单属于默认租户的基础设施信息，
     * 原样回给任意租户等于把「平台有哪些库、内置数据源挂在哪个项目」摊开做踩点
     * （可与 D01/D05 组合）。这里按「数据源所属项目是否在当前租户」判定：
     * 跨租户的只回**能力**（引擎、是否可查、数据源 id——前端提交前判定要带 datasourceId），
     * 不回归属信息（projectId=null、databases 置空、shared=true），库名前缀也不进补全清单。</p>
     */
    public Map<String, Object> describe(Long projectId) {
        List<QueryEngineGuard.Datasource> sources = load(projectId);
        Set<Long> ownProjects = ownProjectIds();
        List<Map<String, Object>> queryable = new ArrayList<>();
        List<Map<String, Object>> notQueryable = new ArrayList<>();
        Set<String> prefixes = new LinkedHashSet<>();
        Set<String> blockedPrefixes = new LinkedHashSet<>();

        for (QueryEngineGuard.Datasource ds : sources) {
            // load() 已经 join 过 project，projectId 必定有效；不在本租户项目里＝跨租户共享
            boolean shared = !ownProjects.contains(ds.projectId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ds.id());
            item.put("name", ds.name());
            item.put("type", ds.dsType());
            item.put("engine", ds.engine());
            item.put("queryable", ds.queryable());
            item.put("projectId", shared ? null : ds.projectId());
            item.put("databases", shared ? List.of() : ds.databases());
            item.put("shared", shared);
            List<String> aliases = shared ? List.of() : ds.databases();
            if (ds.queryable()) {
                for (String alias : aliases) {
                    // 只把能当 SQL 库名的别名当前缀：中文名（如「内置 Doris」）不适合补全
                    if (isIdentifier(alias)) {
                        prefixes.add(alias + ".");
                    }
                }
                queryable.add(item);
            } else {
                for (String alias : aliases) {
                    if (isIdentifier(alias)) {
                        blockedPrefixes.add(alias + ".");
                    }
                }
                item.put("reason", DatasourceEngine.unsupportedMessage(ds.name(), ds.engine()));
                notQueryable.add(item);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", DatasourceEngine.PLATFORM_ENGINE);
        out.put("queryable", queryable);
        out.put("notQueryable", notQueryable);
        // 供编辑器补全：可查数据源的库名前缀（"db." 形式）
        out.put("tablePrefixes", new ArrayList<>(prefixes));
        // 供补全过滤：仅导入数据源的库名/数据源名（后端 /api/query/tables 已按它剔除）
        out.put("notQueryablePrefixes", new ArrayList<>(blockedPrefixes));
        out.put("note", "SQL 统一在 Doris 执行；Kafka 与文件类数据源请先入湖或导入");
        return out;
    }

    /** 不可查数据源的名字/库别名（小写），用于补全剔除。 */
    public Set<String> notQueryableAliases(Long projectId) {
        return QueryEngineGuard.notQueryableAliases(load(projectId));
    }

    /** 当前租户名下的项目 id（D24：判断数据源是否跨租户共享）。 */
    private Set<Long> ownProjectIds() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "select id from platform.project where tenant_id = ?",
                Long.class, TenantScope.current()));
    }

    // ---------------------------------------------------------------- 别名

    /**
     * 可用于匹配 SQL 库前缀的别名：数据源名、规范化后的名字、config_json 里的库名、
     * JDBC URL 末尾的库名。去重保序。
     */
    static List<String> aliases(String name, String jdbcUrl, Map<String, Object> config) {
        Set<String> out = new LinkedHashSet<>();
        addAlias(out, name);
        String slug = slug(name);
        if (!slug.isBlank() && !slug.equalsIgnoreCase(name)) {
            addAlias(out, slug);
        }
        if (config != null) {
            for (String key : CONFIG_DB_KEYS) {
                addAlias(out, text(config.get(key)));
            }
        }
        addAlias(out, databaseFromJdbcUrl(jdbcUrl));
        return new ArrayList<>(out);
    }

    /** {@code jdbc:mysql://host:9030/kb_dev?x=1} → {@code kb_dev}；没有库名时返回空串。 */
    static String databaseFromJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String text = url.trim();
        int query = text.indexOf('?');
        if (query >= 0) {
            text = text.substring(0, query);
        }
        int slash = text.lastIndexOf('/');
        if (slash < 0 || slash >= text.length() - 1) {
            return "";
        }
        String db = text.substring(slash + 1).trim();
        // jdbc:oracle:thin:@host:1521:orcl 这类没有 "/库名" 的写法不会被走到，
        // 但 "jdbc:mysql://host:3306/db;param" 这种要截掉驱动参数
        int semi = db.indexOf(';');
        if (semi >= 0) {
            db = db.substring(0, semi);
        }
        return db.isBlank() || db.contains(":") ? "" : db;
    }

    /** 数据源名 → 可当库名前缀的下划线形式：{@code Kafka Demo → kafka_demo}。 */
    static String slug(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return value.matches("[a-z_][a-z0-9_]*") ? value : "";
    }

    /** SQL 标识符形式（可作为库名前缀）：字母/下划线开头，只含字母数字下划线。 */
    static boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static void addAlias(Set<String> out, String value) {
        if (value == null) {
            return;
        }
        String text = value.trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }

    // ---------------------------------------------------------------- 行读取

    private Map<String, Object> configOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean parseBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long number(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
