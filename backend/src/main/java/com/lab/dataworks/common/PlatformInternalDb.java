package com.lab.dataworks.common;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台自己的元数据库闸门（S1，2026-10-26 泳道 3 扫描 P0）。
 *
 * <p>缺陷形态：工作流 SQL 节点把 {@code datasource} 填成 {@code "PG"}，Prefect 渲染出来的
 * Flow 就用 worker 环境变量 {@code DW_DB_*}（平台库属主账号）连平台元数据库执行节点脚本，
 * 实测 {@code select count(*) from platform.audit_log} 跑成功——等于任何有
 * {@code workflow:create/publish + ops:run} 的账号都能在平台库上执行任意 SQL，
 * 包括改写审计记录。</p>
 *
 * <p>这里收两件事：
 * <ol>
 *   <li><b>保留字</b>：{@code PG / POSTGRES / PLATFORM / METADB / INTERNAL} 这类指向平台库的
 *       数据源别名，一律不许出现在工作流 SQL 节点上（历史上 {@code PG} 是合法取值）；</li>
 *   <li><b>地址</b>：数据源的 JDBC 目标如果落在平台元数据库的端点上
 *       （{@code postgres.dw-dev.svc.cluster.local:5432}、{@code <节点IP>:30432}）、
 *       或 PG 库名/schema 用了 {@code dataworks_dev} / {@code platform}，直接 400。</li>
 * </ol>
 * 生效端点由 {@link PlatformInternalPolicy} 在启动时从 {@code spring.datasource.url} 与
 * {@code platform.internal-db.*} 注入，换环境不用改代码。</p>
 *
 * <p>与 {@link JdbcUrlGuard} 的分工：出网守卫管「这个地址能不能连」，这里管
 * 「这个地址是不是平台自己的库」——平台库即使在出网白名单里（扫描 S10 的既成事实）
 * 也不能被登记成用户可用的数据源。</p>
 */
public final class PlatformInternalDb {

    /** 工作流 SQL 节点里禁止出现的数据源别名（大小写不敏感）。 */
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "PG", "POSTGRES", "POSTGRESQL", "PLATFORM", "METADB", "META_DB",
            "INTERNAL", "DW_DB", "PLATFORM_DB");

    /** PG 里指定 schema 的写法：{@code /db?currentSchema=platform}。 */
    private static final Pattern CURRENT_SCHEMA = Pattern.compile(
            "(?i)[?&;](?:currentschema|schema)\\s*=\\s*([^&,;\\s]+)");

    /** 内置就知的平台元数据端点；部署时 {@code platform.internal-db.endpoints} 会追加。 */
    private static final String BUILTIN_ENDPOINTS =
            "postgres.dw-dev.svc.cluster.local:5432";

    /** 内置就知的平台元数据库名/schema 名；{@code platform.internal-db.database-names} 会追加。 */
    private static final String BUILTIN_DATABASES = "dataworks_dev,platform";

    private static volatile Set<String> endpoints = withPorts(endpoints(BUILTIN_ENDPOINTS));
    private static volatile Set<String> databaseNames = names(BUILTIN_DATABASES);
    private static volatile Set<String> endpointIps = Set.of();

    private PlatformInternalDb() {
    }

    /**
     * 启动注入：元数据库 JDBC 地址 + 配置里追加的端点/库名。
     *
     * @param metadataUrl    {@code spring.datasource.url}，平台自己连的那个库
     * @param extraEndpoints 逗号分隔 {@code host} 或 {@code host:port}（缺端口按 5432）
     * @param extraDatabases 逗号分隔库名（含 schema 名 {@code platform}）
     */
    public static void configure(String metadataUrl, String extraEndpoints, String extraDatabases) {
        Set<String> merged = new LinkedHashSet<>(endpoints(BUILTIN_ENDPOINTS));
        String fromUrl = endpointOfUrl(metadataUrl);
        if (fromUrl != null) {
            merged.add(fromUrl);
        }
        merged.addAll(endpoints(extraEndpoints));
        endpoints = withPorts(merged);

        Set<String> dbs = new LinkedHashSet<>(names(BUILTIN_DATABASES));
        dbs.addAll(names(extraDatabases));
        String dbName = databaseOfUrl(metadataUrl);
        if (dbName != null) {
            dbs.add(dbName);
        }
        databaseNames = Set.copyOf(dbs);

        // 端点的 IP 形式也要能对上：同一个库既可能写 Service 名，也可能直接写节点 IP
        Set<String> ips = new LinkedHashSet<>();
        for (String endpoint : endpoints) {
            int colon = endpoint.lastIndexOf(':');
            String host = colon > 0 ? endpoint.substring(0, colon) : endpoint;
            try {
                ips.add(InetAddress.getByName(host).getHostAddress().toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
                // 解析不了就只按名字比，不影响其余端点
            }
        }
        endpointIps = Set.copyOf(ips);
    }

    /** 测试用：回到内置清单。 */
    public static void reset() {
        endpoints = withPorts(endpoints(BUILTIN_ENDPOINTS));
        databaseNames = names(BUILTIN_DATABASES);
        endpointIps = Set.of();
    }

    /** 当前登记的平台元数据端点（回归用例打印口径用）。 */
    public static Set<String> registeredEndpoints() {
        return endpoints;
    }

    /** 当前登记的平台元数据库名/schema 名。 */
    public static Set<String> registeredDatabases() {
        return databaseNames;
    }

    /** 这个别名是不是「平台内部库」的代名词。 */
    public static boolean isReservedAlias(String datasource) {
        if (datasource == null) {
            return false;
        }
        return RESERVED_ALIASES.contains(datasource.trim().toUpperCase(Locale.ROOT));
    }

    /** 别名的拒绝文案；放行时返回 null。 */
    public static String reservedAliasReason(String taskCode, String datasource) {
        if (!isReservedAlias(datasource)) {
            return null;
        }
        return "任务 " + taskCode + " 的数据源 " + datasource + " 指向平台内部元数据库，已禁止直接使用。"
                + "平台元数据库（含 platform 库与审计表）不属于业务数仓，只能由平台自身读写："
                + "请改用项目绑定的 DORIS 数仓，或在「数据源」里登记一个属于你自己的数据库连接后按 id 绑定";
    }

    /**
     * JDBC 目标是否落在平台元数据库上；命中返回拒绝原因，放行返回 null。
     *
     * <p>端口必须一起对上，避免把同一台机器上的其它服务（例如 {@code <节点IP>:9030}
     * 的 Doris FE）一起误杀；URL 没写端口时按驱动默认端口理解。</p>
     */
    public static String violationOf(JdbcUrlGuard.Target target) {
        if (target == null) {
            return null;
        }
        String host = normalize(target.host());
        if (host.isEmpty()) {
            return null;
        }
        int port = effectivePort(target);
        for (String endpoint : endpoints) {
            int colon = endpoint.lastIndexOf(':');
            String wantHost = colon > 0 ? endpoint.substring(0, colon) : endpoint;
            String wantPort = colon > 0 ? endpoint.substring(colon + 1) : "";
            boolean hostHit = host.equals(wantHost) || endpointIps.contains(host);
            if (!hostHit) {
                continue;
            }
            if (wantPort.isEmpty() || port < 0 || String.valueOf(port).equals(wantPort)) {
                return "该 JDBC 地址指向平台内部元数据库 " + host
                        + (port > 0 ? ":" + port : "")
                        + "，平台元数据库不能作为数据源登记（请改用业务库连接）";
            }
        }
        String database = normalize(target.database());
        if (isPostgres(target) && !database.isEmpty() && databaseNames.contains(database)) {
            return "该数据源使用平台元数据库的库名「" + database + "」，不允许登记";
        }
        return null;
    }

    /** 命中就 400。 */
    public static void assertNotPlatformDatabase(JdbcUrlGuard.Target target) {
        String reason = violationOf(target);
        if (reason != null) {
            throw ApiException.badRequest(reason);
        }
    }

    /** URL 参数里的 {@code currentSchema=platform} 一类写法（库名之外还能指 schema）。 */
    public static void assertNotPlatformSchema(String url) {
        String reason = schemaViolationOf(url);
        if (reason != null) {
            throw ApiException.badRequest(reason);
        }
    }

    /**
     * 整条 JDBC 地址是否指向平台内部库：给「已入库的数据源」在执行/绑定/运行时按同一口径复查。
     *
     * <p>解析不出来时按命中处理（不放行）：宁可拒绝一个坏地址，也不能让 SQL 打到平台库。</p>
     */
    public static String urlViolation(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String rest = url.startsWith("jdbc:") ? url.substring(5) : url;
            int colon = rest.indexOf(':');
            String scheme = colon > 0 ? rest.substring(0, colon).toLowerCase(Locale.ROOT) : "";
            String authority = colon >= 0 ? rest.substring(colon + 1) : rest;
            for (JdbcUrlGuard.Target target : JdbcUrlGuard.targetsOf(scheme, authority)) {
                String reason = violationOf(target);
                if (reason != null) {
                    return reason;
                }
            }
        } catch (RuntimeException e) {
            return "无法解析该 JDBC 地址，按平台内部资源处理，禁止使用：" + e.getMessage();
        }
        return schemaViolationOf(url);
    }

    /** {@code currentSchema} / {@code schema} 指向平台内部 schema 时返回原因。 */
    public static String schemaViolationOf(String url) {
        if (url == null || !isPostgresUrl(url)) {
            return null;
        }
        Matcher matcher = CURRENT_SCHEMA.matcher(url);
        while (matcher.find()) {
            String schema = normalize(matcher.group(1));
            if (databaseNames.contains(schema)) {
                return "该数据源指定了平台内部 schema「" + schema + "」，不允许登记";
            }
        }
        return null;
    }

    /** 数据源名称/别名是否占用了平台内置库的保留字。 */
    public static String reservedNameReason(String name) {
        return isReservedAlias(name) ? "数据源名称 " + name + " 是平台内部库的保留字，请换一个名字" : null;
    }

    private static boolean isPostgres(JdbcUrlGuard.Target target) {
        String scheme = target.scheme() == null ? "" : target.scheme();
        return scheme.startsWith("postgres") || isPostgresUrl("jdbc:" + scheme + ":");
    }

    private static boolean isPostgresUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgres");
    }

    /** URL 没写端口时按驱动默认端口理解；未知方言返回 -1（按「端口未声明」放宽命中）。 */
    private static int effectivePort(JdbcUrlGuard.Target target) {
        if (target.port() > 0) {
            return target.port();
        }
        String scheme = target.scheme() == null ? "" : target.scheme();
        return switch (scheme) {
            case "postgresql" -> 5432;
            case "mysql", "mariadb", "tidb" -> 3306;
            case "clickhouse" -> 8123;
            case "hive2", "presto", "trino" -> scheme.equals("hive2") ? 10000 : 8080;
            case "oracle" -> 1521;
            case "sqlserver" -> 1433;
            case "db2" -> 50000;
            default -> -1;
        };
    }

    private static Set<String> withPorts(Set<String> entries) {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : entries) {
            int colon = entry.lastIndexOf(':');
            boolean bracket = entry.startsWith("[");
            if (colon <= 0 || (bracket && colon < entry.indexOf(']'))) {
                out.add(entry + ":5432");         // 平台元数据库只可能是 PG
            } else {
                out.add(entry);
            }
        }
        return Set.copyOf(out);
    }

    private static String endpointOfUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String host = JdbcUrlGuard.hostOfUrl(url);
        if (host == null || host.isBlank()) {
            return null;
        }
        String value = normalize(host);
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || !value.substring(colon + 1).matches("\\d+")) {
            value = value + ":5432";              // 平台元数据库固定 PG，缺省按 5432
        }
        return value.isEmpty() ? null : value;
    }

    private static String databaseOfUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String value = url.trim();
        int slash = value.indexOf("//");
        if (slash < 0) {
            return null;
        }
        String after = value.substring(slash + 2);
        int end = after.length();
        for (char stop : new char[]{'?', ';'}) {
            int idx = after.indexOf(stop);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        String path = after.substring(0, end);
        int first = path.indexOf('/');
        if (first < 0) {
            return null;
        }
        String database = normalize(path.substring(first + 1));
        return database.isEmpty() ? null : database;
    }

    private static Set<String> endpoints(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return Set.of();
        }
        for (String part : csv.split(",")) {
            String value = normalize(part);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static Set<String> names(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return Set.of();
        }
        for (String part : csv.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return Set.copyOf(out);
    }

    private static String normalize(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
