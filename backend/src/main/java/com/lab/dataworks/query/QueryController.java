package com.lab.dataworks.query;

import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QueryController.class);

    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("information_schema", "mysql", "__internal_schema");

    private final DorisQueryService doris;
    private final DataGuardService dataGuard;
    private final TenantDataScope scope;
    private final com.lab.dataworks.udf.UdfService udf;
    private final com.lab.dataworks.common.TenantGuard guard;
    private final QueryCapabilities capabilities;
    private final com.lab.dataworks.lake.LakeCatalogService lakeCatalogs;
    private final com.lab.dataworks.auth.SessionService sessions;

    /** D4：字段注入，避免改动构造签名影响既有测试；测试环境下为 null 时直接跳过限流。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryLimitsService queryLimits;

    /**
     * D57：项目 DEV/PROD 环境。同样用字段注入（{@code query/} 下 4 个测试直接 new 这个控制器）；
     * 为 null 时跳过环境闸门，保持既有测试口径。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lab.dataworks.project.ProjectEnvironmentService envService;

    /**
     * TC7-G4：按项目/租户用量计量的查询事件采集（只计量，不计费）。同样字段注入——
     * {@code query/} 下 4 个测试直接 new 本控制器；为 null 时跳过计量，不改查询行为。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lab.dataworks.usage.UsageMeteringService metering;

    public QueryController(DorisQueryService doris, DataGuardService dataGuard,
                           TenantDataScope scope, com.lab.dataworks.udf.UdfService udf,
                           com.lab.dataworks.common.TenantGuard guard,
                           QueryCapabilities capabilities,
                           com.lab.dataworks.lake.LakeCatalogService lakeCatalogs,
                           com.lab.dataworks.auth.SessionService sessions) {
        this.doris = doris;
        this.dataGuard = dataGuard;
        this.scope = scope;
        this.udf = udf;
        this.guard = guard;
        this.capabilities = capabilities;
        this.lakeCatalogs = lakeCatalogs;
        this.sessions = sessions;
    }

    public record QueryRequest(String sql, Integer maxRows, Long projectId) {
    }

    /** 自动补全用的表/字段目录；Doris 元数据变化不频繁，缓存 60 秒。 */
    private record CachedSchema(long at, List<Map<String, Object>> tables) {
    }

    /** 缓存必须按项目隔离：不同项目的 Doris 不是同一个实例，混用会给错表名。 */
    private final Map<String, CachedSchema> schemaCache = new java.util.concurrent.ConcurrentHashMap<>();

    @GetMapping("/tables")
    @RequireAtom("query:run")
    public Map<String, Object> tables(@RequestParam(required = false) Long projectId) {
        // 显式传 projectId 时也要校验归属：否则 tenant_b 用别人的 projectId 就能拿到对方库的元数据
        if (projectId != null) {
            guard.requireProject(projectId);
        }
        // 不带 projectId 时用的是内置 Doris：缓存必须按租户隔离，否则 A 租户的元数据会漏给 B。
        // D05 后带 projectId 的结果也按租户收口，但 guard.requireProject 已强制
        // 「项目属于当前租户」，projectId 唯一决定租户，按项目做缓存键仍然是安全的。
        String key = projectId == null ? "t:" + TenantScope.current() : "p:" + projectId;
        long now = System.currentTimeMillis();
        CachedSchema cached = schemaCache.get(key);
        if (cached == null || now - cached.at() > 60_000) {
            cached = new CachedSchema(now, loadSchema(projectId));
            schemaCache.put(key, cached);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // V99：补全只列「可查询」的表。Kafka / 文件类数据源的名字或库名不进补全，
        // 避免用户补出一个后端必然 400 的表名。过滤在服务端做，所有 SQL 编辑器一致。
        out.put("tables", filterNonQueryable(cached.tables(),
                capabilities.notQueryableAliases(projectId)));
        out.put("cachedAt", cached.at());
        // 已发布 UDF：编辑器的函数名自动补全（按项目隔离）
        if (projectId != null) {
            try {
                out.put("functions", udf.usable(projectId));
            } catch (ApiException e) {
                out.put("functions", List.of());
            }
        }
        return out;
    }

    /**
     * V99：可查数据源与可查表前缀，供前端标签、补全与执行前提示。
     *
     * <p>返回 {@code queryable} / {@code notQueryable} 两份清单和 {@code tablePrefixes}
     * （形如 {@code db.}）；前端据此显示「可查询 / 仅导入」，并估算「涉及 N 个数据源」。</p>
     */
    @GetMapping("/capabilities")
    @RequireAtom("query:run")
    public Map<String, Object> queryCapabilities(@RequestParam(required = false) Long projectId) {
        if (projectId != null) {
            guard.requireProject(projectId);
        }
        return capabilities.describe(projectId);
    }

    /**
     * 命中不可查数据源时直接 400（Kafka / 文件类没有 SQL 执行链路）。
     * 抽成包级方法，单测可以在不起 Spring 的情况下锁住「Kafka → 400」。
     */
    /** 被拒的表引用是否属于系统库（用于给出「审计请走专用接口」的提示）。 */
    static boolean isSystemRef(String reference) {
        if (reference == null) {
            return false;
        }
        String value = reference.trim().toLowerCase(java.util.Locale.ROOT);
        for (String schema : new String[]{"information_schema", "mysql",
                "__internal_schema", "sys", "platform"}) {
            if (value.equals(schema) || value.startsWith(schema + ".")) {
                return true;
            }
        }
        return false;
    }

    static void rejectUnsupportedEngine(QueryEngineGuard.Decision decision) {
        if (decision != null && decision.blocked()) {
            throw ApiException.badRequest(decision.message());
        }
    }

    /** 补全剔除：库名命中不可查数据源（数据源名或其库别名）的表不返回。 */
    private static List<Map<String, Object>> filterNonQueryable(
            List<Map<String, Object>> tables, Set<String> blockedAliases) {
        if (tables == null || tables.isEmpty()
                || blockedAliases == null || blockedAliases.isEmpty()) {
            return tables;
        }
        List<Map<String, Object>> out = new ArrayList<>(tables.size());
        for (Map<String, Object> table : tables) {
            Object database = table.get("database");
            String key = database == null ? "" : String.valueOf(database).trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!key.isEmpty() && blockedAliases.contains(key)) {
                continue;
            }
            out.add(table);
        }
        return out;
    }

    private List<Map<String, Object>> loadSchema(Long projectId) {
        // D05：只有内置共享 Doris 上的库名由本平台项目声明归属，需要按租户已授权库收口；
        // 项目引用外部 Doris 时不过滤，否则会把项目自己的表全部藏掉。
        boolean sharedDoris = doris.usesBuiltinDoris(projectId);
        // Doris 4.1 的 information_schema.columns 对 table_schema 做等值/LIKE/NOT IN
        // 过滤一律返回 0 行（`table_schema <> 'mysql'` 却是对的），所以不在 SQL 里筛，
        // 取全量后由后端按库名排除系统库。
        DorisQueryService.Result result = doris.rawQuery(projectId, """
                select table_schema, table_name, column_name, data_type
                from information_schema.columns
                order by table_schema, table_name, ordinal_position
                """, 20000);
        Map<String, Map<String, Object>> byTable = new LinkedHashMap<>();
        for (List<String> row : result.rows()) {
            if (row.size() < 4 || row.get(0) == null || row.get(1) == null) {
                continue;
            }
            String database = row.get(0);
            if (SYSTEM_SCHEMAS.contains(database)) {
                continue;
            }
            // 跨租户隔离：内置 Doris 上别人项目的库不出现在补全/元数据里。
            // D05：带 projectId 也一样收口——显式项目只决定连哪个 Doris，不改变租户边界；
            // 之前只在 projectId == null 分支过滤，导致带 projectId 列出全平台库表与字段。
            if (sharedDoris && !scope.owns(database)) {
                continue;
            }
            String table = row.get(1);
            String key = database + "." + table;
            Map<String, Object> entry = byTable.computeIfAbsent(key, ignored -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("database", database);
                created.put("table", table);
                created.put("columns", new ArrayList<Map<String, String>>());
                return created;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, String>> columns = (List<Map<String, String>>) entry.get("columns");
            Map<String, String> column = new LinkedHashMap<>();
            column.put("name", row.get(2));
            column.put("type", row.get(3) == null ? "" : row.get(3));
            columns.add(column);
        }
        return new ArrayList<>(byTable.values());
    }

    /**
     * V100：三段式引用 {@code catalog.db.table} 的 Catalog 部分。
     * 只取 from/join/into/update/table 后面的第一个标识符，用于执行前校验；
     * 内表 catalog（internal / doris）不在这里处理。
     */
    static java.util.Set<String> sqlCatalogs(String sql) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)\\b(?:from|join|into|update|table)\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?"
                + "\\s*\\.\\s*[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\.\\s*[`\"]?([A-Za-z0-9_]+)")
                .matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            String catalog = matcher.group(1);
            if (!"internal".equalsIgnoreCase(catalog) && !"doris".equalsIgnoreCase(catalog)
                    && !"pg".equalsIgnoreCase(catalog)) {
                out.add(catalog);
            }
        }
        return out;
    }

    private void checkLakeCatalogs(String sql) {
        for (String catalog : sqlCatalogs(sql)) {
            try {
                lakeCatalogs.assertQueryable(catalog);
            } catch (ApiException e) {
                // 未登记/跨租户 → 统一 400，与「查询前置校验」契约一致
                throw ApiException.badRequest(e.getMessage());
            }
        }
    }

    /**
     * 粗粒度提取 SQL 里显式写了库名的引用（from/join/into/update/table db.xxx）。
     *
     * <p>V100：三段式 {@code catalog.db.table} 要区分开——湖 Catalog 由
     * {@link #checkLakeCatalogs} 校验租户归属；{@code internal.db.table} 里的
     * internal 不是库名，真正的库是第二段。</p>
     */
    private java.util.Set<String> qualifiedDatabases(String sql) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)\\b(?:from|join|into|update|table)\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?"
                + "\\s*\\.\\s*[`\"]?([A-Za-z0-9_]+)[`\"]?"
                + "(?:\\s*\\.\\s*[`\"]?([A-Za-z0-9_]+)[`\"]?)?")
                .matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            String first = matcher.group(1);
            String second = matcher.group(2);
            boolean three = matcher.group(3) != null;
            if (three && ("internal".equalsIgnoreCase(first)
                    || "doris".equalsIgnoreCase(first) || "pg".equalsIgnoreCase(first))) {
                out.add(second);
            } else {
                out.add(first);
            }
        }
        return out;
    }

    /** SHOW / DESC 这类元数据语句涉及的库（没有显式库时返回 null）。 */
    private String metaDatabase(String sql) {
        String text = sql == null ? "" : sql.trim();
        java.util.regex.Matcher show = java.util.regex.Pattern.compile(
                "(?is)^show\\s+(?:tables|table)\\s+(?:from|in)\\s+`?([A-Za-z0-9_]+)")
                .matcher(text);
        if (show.find()) {
            return show.group(1);
        }
        java.util.regex.Matcher desc = java.util.regex.Pattern.compile(
                "(?is)^(?:desc|describe)\\s+`?([A-Za-z0-9_]+)`?\\s*\\.")
                .matcher(text);
        if (desc.find()) {
            return desc.group(1);
        }
        return null;
    }

    /**
     * SHOW DATABASES / SHOW TABLES / DESC / EXPLAIN 的类别。
     *
     * <p>用途有二：非默认租户按库收口（SHOW TABLES / DESC 必须带库名），
     * 以及让这些「只返回元数据、不返回数据行」的语句不参与数据分级判定。
     * 返回 null 表示不是元数据语句，按普通查询处理。</p>
     */
    static String metaKind(String sql) {
        String text = sql == null ? "" : sql.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.matches("(?s)^show\\s+(databases|schemas)\\b.*")) {
            return "DATABASES";
        }
        if (text.matches("(?s)^show\\s+(tables|table)\\b.*")) {
            return "TABLES";
        }
        if (text.matches("(?s)^(desc|describe)\\b.*")) {
            return "DESC";
        }
        // EXPLAIN 只出执行计划、不返回数据行；EXPLAIN ANALYZE 会真正执行，
        // 不归入元数据语句，让它照样过只读校验与分级网关（fail-closed）。
        if (text.matches("(?s)^explain\\b(?!\\s+analyze\\b).*")) {
            return "EXPLAIN";
        }
        return null;
    }

    /** 元数据语句收口后的行与计数（D09 / D10）。 */
    record MetaRows(List<List<String>> rows, int rowCount) {
    }

    /**
     * 元数据语句（SHOW DATABASES / SHOW TABLES / DESC / EXPLAIN）的行收口。
     *
     * <p>D09：MCP 不能把这类语句包成子查询（{@code SELECT * FROM (show databases) ...}
     * 必然语法错），JDBC 侧 {@code setMaxRows} 对 {@code SHOW} 也不保证生效，
     * 所以 {@code maxRows} 在这里统一裁剪——否则 {@code maxRows} 契约对元数据语句完全失效，
     * 调用方无法限制响应体量。</p>
     *
     * <p>D10：非默认租户的 {@code SHOW DATABASES} 还要先按本租户已授权库过滤。
     * 过滤和裁剪都会改变真正返回的行数，{@code rowCount} 一律按收口后的行重算，
     * 不能沿用执行结果里的过滤前计数（原缺陷是 {@code rows=[]} 却报 {@code rowCount:34}，
     * 顺带把全平台库总数泄露给没有数据面的租户）。</p>
     *
     * <p>抽成包级静态方法：不起 Spring、不连 Doris 也能锁住「裁剪 + 重算」这条口径。</p>
     *
     * @param maxRows        本响应的硬上限（与查询页其它语句同一个口径）
     * @param ownedDatabases 已授权库集合；{@code null} 表示不做库级过滤
     *                       （默认租户，或这条语句不是 SHOW DATABASES）
     */
    static MetaRows limitMetaRows(List<List<String>> rows, int maxRows,
                                  Set<String> ownedDatabases) {
        List<List<String>> kept = rows == null ? List.of() : rows;
        if (ownedDatabases != null) {
            List<List<String>> filtered = new ArrayList<>(kept.size());
            for (List<String> row : kept) {
                if (!row.isEmpty() && row.get(0) != null
                        && ownedDatabases.contains(String.valueOf(row.get(0))
                        .toLowerCase(java.util.Locale.ROOT))) {
                    filtered.add(row);
                }
            }
            kept = filtered;
        }
        if (kept.size() > maxRows) {
            kept = new ArrayList<>(kept.subList(0, maxRows));
        }
        return new MetaRows(kept, kept.size());
    }

    /**
     * {@code SHOW DATABASES} 的库级过滤集合：非默认租户只看自己项目名下的库，
     * 默认租户返回 {@code null}（不过滤，保持平台自身的全局视图）。
     */
    private Set<String> ownedDatabasesFor(String kind) {
        if (!"DATABASES".equals(kind) || scope.defaultTenant()) {
            return null;
        }
        return scope.ownedDatabases();
    }

    @PostMapping("/doris")
    @RequireAtom("query:run")
    public Map<String, Object> doris(@RequestBody QueryRequest req, HttpServletRequest request) {
        // D4：并发闸 + 限额下发。超限直接 429，不把请求丢给 Doris。
        if (queryLimits == null) {
            return dorisInternal(req, request);
        }
        String tenant = com.lab.dataworks.auth.TenantScope.current();
        QueryLimitsService.Limits limits = queryLimits.resolve(tenant);
        if (!queryLimits.tryAcquire(tenant, limits)) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("status", 429);
            body.put("code", "CONCURRENCY_LIMIT");
            body.put("message", "当前并发已达上限 " + limits.maxConcurrency() + "，请稍后重试");
            body.put("queueDepth", queryLimits.queueDepth(tenant));
            body.put("limits", limits);
            throw new com.lab.dataworks.common.ApiException(429, body.toString());
        }
        QueryLimitsService.setCurrent(limits);
        try {
            return dorisInternal(req, request);
        } finally {
            QueryLimitsService.clearCurrent();
            queryLimits.release(tenant);
        }
    }

    private Map<String, Object> dorisInternal(QueryRequest req, HttpServletRequest request) {
        if (req == null || req.sql() == null || req.sql().isBlank()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        int maxRows = req.maxRows() == null ? 100 : Math.max(1, Math.min(1000, req.maxRows()));
        String user = String.valueOf(request.getAttribute("username"));

        // SHOW / DESC / EXPLAIN 这类元数据语句只需判定一次，后面租户收口、分级网关、
        // SHOW DATABASES 结果过滤三处都用同一个口径
        String kind = metaKind(req.sql());

        // V99 查询前置校验：SQL 里出现 Kafka / 文件类数据源时直接 400，
        // 因为它们没有 SQL 执行链路，只能先入湖或导入。
        // 解析失败（表引用为空）或没有命中任何数据源时照常走 Doris（fail-open）。
        QueryEngineGuard.Decision engineCheck = kind == null
                ? QueryEngineGuard.evaluate(capabilities.load(req.projectId()),
                        dataGuard.parsedTables(req.sql()))
                : QueryEngineGuard.Decision.pass(List.of(), List.of(), null);
        rejectUnsupportedEngine(engineCheck);

        // V100：catalog.db.table 的湖表查询，执行前确认 Catalog 属于当前租户、
        // 状态可用、业务账号有 SELECT 权限；否则 400（不把 Doris 原始报错抛给前端）。
        if (kind == null) {
            checkLakeCatalogs(req.sql());
        }

        // UDF 先判：未发布 / 别的项目的函数要给出「函数」层面的明确提示，
        // 不能只回一句「项目不存在」（v447 验收要求）。不通过时直接拒绝。
        udf.checkUsage(req.projectId(), req.sql());

        // 显式 projectId 也要归属校验（它会决定连哪个 Doris 实例）：跨租户 → 404
        if (req.projectId() != null) {
            guard.requireProject(req.projectId());
        }

        // 跨租户隔离：不带 projectId 直连内置 Doris 时，只能碰自己项目名下的库
        String metaDb = metaDatabase(req.sql());

        // D57：环境闸——本页只面向本项目的开发库。显式写了本项目的生产库要拒绝
        // （环境隔离不能靠「用户大概不会写错」）；生产数据请走发布后的调度 / 数据服务链路。
        // SHOW TABLES FROM <库> / DESC <库>.<表> 这类元数据语句也一起判，别留侧面口子。
        com.lab.dataworks.project.ProjectEnvironmentService.Env projectEnv =
                envService == null || req.projectId() == null
                        ? null : envService.require(req.projectId());
        if (projectEnv != null) {
            java.util.Set<String> touched = new java.util.LinkedHashSet<>(qualifiedDatabases(req.sql()));
            if (metaDb != null) {
                touched.add(metaDb);
            }
            envService.requireDevWorkbench(projectEnv, touched);
        }
        if (req.projectId() == null && !scope.defaultTenant()) {
            // v460：没有数据面的租户执行 SHOW DATABASES 返回空列表（200），
            // 不再报「当前租户没有数据面」——这是查询元数据，不是访问数据。
            // 其余语句仍要求数据面存在。
            if (!"DATABASES".equals(kind)) {
                scope.requireDataPlane();
            }
            java.util.Set<String> lakeRefs = sqlCatalogs(req.sql());
            for (String database : qualifiedDatabases(req.sql())) {
                // V100：只有「三段式 Catalog 引用」才跳过内表库归属校验；
                // 两段式的同名库仍必须 scope.owns，避免湖 Catalog 名和内表库名撞车时越权。
                if (lakeRefs.contains(database) && lakeCatalogs.isOwned(database)) {
                    continue;
                }
                if (!scope.owns(database)) {
                    throw new ApiException(403, "无权访问库: " + database);
                }
            }
            // v457 MT-P1-2：SHOW/DESC 也要过库权限，不能靠「元数据语句」绕过租户隔离
            if (("TABLES".equals(kind) || "DESC".equals(kind)) && metaDb == null) {
                throw new ApiException(403, "请指定库名（如 " + (("DESC".equals(kind))
                        ? "<库>.<表>" : "SHOW TABLES FROM <库>") + "）");
            }
            if (metaDb != null && !scope.owns(metaDb)) {
                throw new ApiException(403, "无权访问库: " + metaDb);
            }
        }
        // D01：带 projectId 时也不能用 SHOW/DESC 看别的租户库。这里不依赖「库名是否在
        // 自己项目里」的 fail-open 口径，只挡「明确属于其他租户」的库。
        if (metaDb != null && dataGuard.crossTenantBlocked(user, metaDb, req.projectId())) {
            throw new ApiException(403, "无权访问其他租户的库: " + metaDb);
        }

        // 数据分级网关：先判定，再执行。ENFORCE 下解析失败也拒绝（fail-closed）。
        // 元数据语句（SHOW / DESC / EXPLAIN）不返回数据行，不参与分级判定：
        // 否则默认 ENFORCE 下「SHOW TABLES FROM x」会被当成查表 x 而 403
        // （表未纳管即按 L3 处理）。库归属与租户隔离仍由上面的 scope 校验兜住。
        // v483：按项目解析后的策略判定（项目 > 租户 > 平台），无 projectId 时用平台默认。
        DataGuardService.Guard guard = kind == null
                ? dataGuard.inspect(user, req.sql(), req.projectId())
                : new DataGuardService.Guard(dataGuard.guardMode(req.projectId()), false, false,
                        List.of(), List.of(), List.of(), false, List.of());
        dataGuard.logDecision(user, guard,
                com.lab.dataworks.auth.AuthInterceptor.clientIp(request), "query");
        // D04：系统库是硬挡，不受项目级 OFF 影响；审计请走 /api/security/audit
        if (guard.systemBlocked()) {
            throw new ApiException(403,
                    "系统库仅平台管理员可读；审计数据请使用平台审计接口 /api/security/audit。");
        }
        // D01：跨租户库是硬挡（不受项目级 OFF 影响）。平台管理员不受此限。
        if (guard.crossTenantDenied() != null && !guard.crossTenantDenied().isEmpty()) {
            throw new ApiException(403, "无权访问其他租户的数据表（"
                    + String.join("、", guard.crossTenantDenied())
                    + "）。平台管理员或本租户已授权库除外。");
        }
        if (guard.blocked()) {
            throw ApiException.badRequest(
                    "SQL 无法安全解析（涉及子查询/动态拼接时请改写成显式表名），已按安全策略拒绝");
        }
        if (guard.denied()) {
            boolean systemDenied = guard.wouldDeny().stream().anyMatch(QueryController::isSystemRef);
            if (systemDenied) {
                throw new ApiException(403, "无权访问查询涉及的数据表（"
                        + String.join("、", guard.wouldDeny()) + "）。"
                        + "系统库仅平台管理员可读；审计数据请使用平台审计接口 /api/security/audit。");
            }
            // BUG-TC4-5：未纳管的表在「权限申请」入口会 404（资源不存在或未纳管），
            // 所以不能对它说「如需访问请提交权限申请」——那是指一条走不通的路。
            List<String> unregistered = new java.util.ArrayList<>();
            List<String> ungranted = new java.util.ArrayList<>();
            for (String ref : guard.wouldDeny()) {
                int dot = ref.lastIndexOf('.');
                boolean registered = dot > 0
                        && scope.registeredTable(ref.substring(0, dot), ref.substring(dot + 1));
                (registered ? ungranted : unregistered).add(ref);
            }
            if (!unregistered.isEmpty() && ungranted.isEmpty()) {
                throw new ApiException(403, "数据表尚未纳管，无法提交权限申请（"
                        + String.join("、", unregistered) + "）。"
                        + "请先请管理员在「数据地图 / 表管理」纳管这张表（登记负责人与数据分级），"
                        + "纳管后再到「权限申请」提交读取申请。");
            }
            throw new ApiException(403, "无权访问查询涉及的数据表（"
                    + String.join("、", guard.wouldDeny()) + "）。"
                    + "如需访问请在「权限申请」按这些资源标识提交申请："
                    + String.join("、", guard.wouldDeny())
                    + (!unregistered.isEmpty()
                    ? "；其中 " + String.join("、", unregistered) + " 尚未纳管，需先纳管才能申请" : "") + "。");
        }

        DorisQueryService.Result result = doris.query(req.projectId(), req.sql(), maxRows);
        // V100：湖表查询单独留痕，target 记录来源 Catalog（审计里能直接看出查的是哪套湖）
        java.util.Set<String> usedCatalogs = kind == null
                ? sqlCatalogs(req.sql()) : java.util.Set.of();
        if (!usedCatalogs.isEmpty()) {
            try {
                sessions.audit(user, "LAKE_QUERY", String.join(",", usedCatalogs),
                        "engine=doris; projectId=" + req.projectId()
                                + "; tables=" + String.join(",", dataGuard.parsedTables(req.sql())),
                        "SUCCESS", com.lab.dataworks.auth.AuthInterceptor.clientIp(request));
            } catch (RuntimeException e) {
                // S13：审计失败不影响查询结果，但必须 error 留痕——
                // 原来 catch 后什么都不做，湖表被谁查过这件事就彻底没有线索。
                log.error("湖查询审计写入失败 user={} catalogs={}: {}",
                        user, usedCatalogs, e);
            }
        }
        // D09 / D10：元数据语句不受 MCP / JDBC 的 LIMIT 约束，在这里统一按 maxRows 裁剪；
        // 非默认租户的 SHOW DATABASES 先按本租户已授权库过滤。两种收口都会改变行数，
        // rowCount 一律取收口后真正返回的行数，不再沿用执行结果里的过滤前计数。
        MetaRows meta = kind == null ? new MetaRows(result.rows(), result.rowCount())
                : limitMetaRows(result.rows(), maxRows, ownedDatabasesFor(kind));
        List<List<String>> rows = meta.rows();
        // v478：先算出本响应实际脱敏的列（供前端提示），再套掩码/令牌。
        // TC7-G6：带原始 SQL，按回溯源列判定，改名列/表达式列不再漏遮。
        List<String> maskedColumns = dataGuard.maskedColumnNames(
                user, guard, result.columns(), req.projectId(), req.sql());
        List<String> tokenizedColumns = dataGuard.tokenizedColumnNames(
                user, guard, result.columns(), req.projectId(), req.sql());
        rows = dataGuard.mask(user, guard, result.columns(), rows, req.projectId(), req.sql());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", result.columns());
        out.put("rows", rows);
        out.put("maskedColumns", maskedColumns);
        out.put("tokenizedColumns", tokenizedColumns);
        out.put("rowCount", meta.rowCount());
        out.put("maxRows", result.maxRows());
        out.put("elapsedMs", result.elapsedMs());
        out.put("target", result.target());
        out.put("guard", guard.toMap());
        // D57：把「这次实际该用哪个库」回写给前端。内置 Doris 走 MCP，连接不带默认库
        // （MCP 的 doris.sql_query 没有 database 参数），所以未带前缀的表必须由用户写全；
        // 这里给明确提示，而不是等 Doris 报「Current database is not set」再翻译成 400。
        if (projectEnv != null) {
            out.put("environment", "DEV");
            out.put("defaultDatabase", projectEnv.devDatabase());
            List<String> bare = com.lab.dataworks.project.ProjectEnvironmentService
                    .unqualifiedRefs(dataGuard.parsedTables(req.sql()));
            if (!bare.isEmpty() && projectEnv.devDatabase() != null) {
                out.put("databaseHint", "本项目开发库是 " + projectEnv.devDatabase()
                        + "，未带库前缀的 " + String.join("、", bare)
                        + " 请写成 " + projectEnv.devDatabase() + ".<表>（当前连接没有默认库）");
            }
        }
        // V99：单引擎 Doris 的执行说明（JDBC 多源 join 时提示「涉及 N 个数据源」）
        out.put("engine", com.lab.dataworks.datasource.DatasourceEngine.PLATFORM_ENGINE);
        out.put("engineHint", engineCheck.hint());
        out.put("engineDatasources", engineCheck.involved());
        // TC7-G4：采集闭环——只有执行成功且正常返回的查询才计量（400/403 不计数）；
        // 写入失败在 recordQuery 内部 error 留痕并丢弃样本，绝不反噬查询响应。
        if (metering != null) {
            metering.recordQuery(com.lab.dataworks.auth.TenantScope.current(),
                    req.projectId(), user, meta.rowCount(), result.elapsedMs());
        }
        return out;
    }
}
