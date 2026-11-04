package com.lab.dataworks.project;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 项目的 DEV / PROD 环境（D57）。
 *
 * <p>平台的环境隔离靠的是 {@code platform.project.dev_database / prod_database} 两列：
 * 试跑落 dev 库、发布落 prd 库。D57 立案时这两列在真实项目里是<b>空的</b>
 * （不传部门也不传库名就能建项目），于是「环境」只是界面上的词：
 * 连接没有默认库（{@code select database()} 返回空串），SQL 必须全限定，
 * 而写哪个库都没人管——生产库和开发库在数据层没有任何闸门。</p>
 *
 * <p>这个类把三件事收到一处：库名怎么推导、开发面能不能碰生产库、
 * 以及空壳库的巡检与清理（只删确认无表、且没有别的项目引用的库）。</p>
 */
@Service
public class ProjectEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(ProjectEnvironmentService.class);
    /** Doris 库名上限按 64 处理（与 {@code ProjectController.dbName} 同一口径）。 */
    private static final int MAX_DB_NAME = 64;
    /**
     * 显式带库前缀的表引用：{@code db.table} 或 {@code catalog.db.table}。
     *
     * <p>刻意只用字符类（{@code [^A-Za-z0-9_]}）表达边界、空白与反引号/引号，
     * 不写正则转义——转义层级在跨语言补丁里最容易出错，字符类一眼看得懂。</p>
     */
    private static final java.util.regex.Pattern QUALIFIED_REF = java.util.regex.Pattern.compile(
            "(?is)(?:^|[^A-Za-z0-9_])(?:from|join|into|update|table)[^A-Za-z0-9_]*"
                    + "([A-Za-z0-9_]+)[^A-Za-z0-9_]*[.][^A-Za-z0-9_]*([A-Za-z0-9_]+)"
                    + "(?:[^A-Za-z0-9_]*[.][^A-Za-z0-9_]*([A-Za-z0-9_]+))?");

    private final JdbcTemplate jdbc;
    private final DorisAdminService doris;
    private final WarehouseResolver warehouses;

    public ProjectEnvironmentService(JdbcTemplate jdbc, DorisAdminService doris,
                                     WarehouseResolver warehouses) {
        this.jdbc = jdbc;
        this.doris = doris;
        this.warehouses = warehouses;
    }

    /** 项目的环境信息。 */
    public record Env(long projectId, String code, String name, String devDatabase, String prodDatabase) {
        public boolean missing() {
            return blank(devDatabase) || blank(prodDatabase);
        }

        /** 这次执行实际该落在哪个库（开发面 = dev 库）。 */
        public String workbenchDatabase() {
            return devDatabase;
        }
    }

    /**
     * 由项目编码推导一对库名：{@code demo → demo_dev / demo_prd}。
     *
     * <p>取小写、把非 {@code [a-z0-9_]} 的字符换成下划线，是为了让「编码里有连字符」这类
     * 历史数据也能得到合法库名；推导不出合法名（全是不合法字符）时返回 null，
     * 由调用方决定是拒绝还是跳过——不静默给一个空库名。</p>
     */
    public static String[] deriveDatabases(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String base = code.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (base.length() > MAX_DB_NAME - 4) {
            base = base.substring(0, MAX_DB_NAME - 4);
        }
        while (base.endsWith("_")) {
            base = base.substring(0, base.length() - 1);
        }
        // 与 ProjectController.dbName 同口径：字母或下划线开头，只含字母/数字/下划线。
        // 推不出合法名就返回 null 让调用方拒绝，不能塞一个 Doris 根本不认的库名进去。
        if (!base.matches("[a-z_][a-z0-9_]{0,63}")) {
            return null;
        }
        return new String[]{base + "_dev", base + "_prd"};
    }

    /**
     * SQL 里被**显式限定**的库名（BUG-TC4-2 用）。
     *
     * <p>只看 {@code from|join|into|update|table <库>.<表>} 这种写了库前缀的引用；
     * 裸表名不在这里判（它落哪个库由项目路由决定，不是用户显式写的生产库）。
     * 三段式 {@code catalog.db.table} 取中间那段（与 {@code QueryController.qualifiedDatabases}
     * 同口径，湖 Catalog 不当成内表库）。</p>
     */
    public static Set<String> referencedDatabases(String sql) {
        Set<String> out = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return out;
        }
        Matcher matcher = QUALIFIED_REF.matcher(sql);
        while (matcher.find()) {
            String first = matcher.group(1);
            String second = matcher.group(2);
            boolean threeSegment = matcher.group(3) != null;
            if (threeSegment && ("internal".equalsIgnoreCase(first)
                    || "doris".equalsIgnoreCase(first) || "pg".equalsIgnoreCase(first))) {
                out.add(second);
            } else if (threeSegment) {
                // iceberg.xxx.t 这类湖引用：库段是 third，不是 first
                out.add(second);
            } else {
                out.add(first);
            }
        }
        return out;
    }

    /**
     * 开发态动作只能落开发库（BUG-TC4-2 试跑、BUG-TC4-3 采集 sink 共用这一道闸）。
     *
     * <p>立案事实：TC-4 实测「DEV 试跑写 {@code <任意库>_prd.<表>} → 200 并返回生产数据」，
     * 而同一张表在查询页会被 403 挡下——同一份数据两套口径。
     * D38 那套「比对本项目 prod_database」的判定在代码里**根本不存在**
     * （{@code WorkflowTaskRunController} 里没有任何 env 判定，响应里也没有 database），
     * 所以这里不是「条件过窄」，是整条闸门缺失。</p>
     *
     * <p>挡的口径（与 D57 一致，按列值而不是猜后缀为主）：
     * ① 本项目的生产库；② 本租户任何项目的生产库；③ 谁都不引用、但按命名约定是生产库的
     * {@code *_prd}（历次测试留下的空壳生产库就是这种）。
     * 跨项目的**开发库**不在拦截范围（平台允许读同租户别的项目的开发库，按租户与分级网关判），
     * 未配 dev/prd 的项目也不会被一刀切挡死——先补 D57 回填，再谈收紧成「只允许 dev 库」。</p>
     *
     * @param surface 人话描述这次是什么动作（「试跑」/「采集任务 sink」），进 400 文案
     */
    public void requireDevLanding(Long projectId, Collection<String> databases, String surface) {
        if (databases == null || databases.isEmpty()) {
            return;
        }
        // 这里不负责归属校验（调用方都先过了租户/项目校验），查不到项目就只按命名约定与
        // 本租户生产库清单判——不要因为「项目行没读到」而放过一个生产库落点。
        Env env = projectId == null ? null : envOrNull(projectId);
        for (String database : databases) {
            if (database == null || database.isBlank()) {
                continue;
            }
            if (env != null && env.prodDatabase() != null
                    && env.prodDatabase().equalsIgnoreCase(database)) {
                throw ApiException.badRequest(surface + " 只能落本项目的开发库 "
                        + (blank(env.devDatabase()) ? "<请先在项目管理配置 dev_database>" : env.devDatabase())
                        + "；" + database + " 是本项目的生产库，生产落库请走发布 / 运维通道");
            }
            String owner = productionDatabaseOwner(database);
            if (owner != null) {
                throw ApiException.badRequest(surface + " 不能落生产库 " + database
                        + "（项目「" + owner + "」的生产库）；请改成本项目的开发库，"
                        + "生产落库请走发布 / 运维通道");
            }
            if (database.toLowerCase(Locale.ROOT).endsWith("_prd")) {
                throw ApiException.badRequest(surface + " 不能落 " + database
                        + "：按平台命名约定（*_prd）它是生产库，且没有登记在任何项目的生产库上；"
                        + "请改成开发库（本项目 dev_database），或先在项目管理里把它登记清楚");
            }
        }
    }

    /**
     * 落点属于哪个环境（BUG-TC4-3 第 3 条：任务详情/列表要能核对落点）。
     * 只用于回显，不拦截：DEV / PROD / UNCONFIGURED（项目没配 dev 库）。
     */
    public String landingEnv(long projectId, String database) {
        if (database == null || database.isBlank()) {
            return null;
        }
        Env env = envOrNull(projectId);
        if (env == null) {
            return productionDatabaseOwner(database) != null
                    || database.toLowerCase(Locale.ROOT).endsWith("_prd") ? "PROD" : "OTHER";
        }
        if (env.prodDatabase() != null && env.prodDatabase().equalsIgnoreCase(database)) {
            return "PROD";
        }
        if (env.devDatabase() != null && env.devDatabase().equalsIgnoreCase(database)) {
            return "DEV";
        }
        if (productionDatabaseOwner(database) != null) {
            return "PROD";
        }
        if (database.toLowerCase(Locale.ROOT).endsWith("_prd")) {
            return "PROD";
        }
        return env.devDatabase() == null || env.devDatabase().isBlank() ? "UNCONFIGURED" : "OTHER";
    }

    /** 项目环境；查不到（跨租户 / 已删）返回 null，不抛 404——归属校验由调用方负责。 */
    private Env envOrNull(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, dev_database, prod_database
                  from platform.project
                 where id = ? and tenant_id = ?
                """, projectId, TenantScope.current());
        return rows.isEmpty() ? null : toEnv(rows.get(0));
    }

    /** 这个库是哪个项目的生产库（列值权威）；不是生产库返回 null。 */
    private String productionDatabaseOwner(String database) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select name from platform.project
                 where tenant_id = ? and lower(coalesce(prod_database, '')) = lower(?)
                 order by id limit 1
                """, TenantScope.current(), database);
        return rows.isEmpty() ? null : str(rows.get(0).get("name"));
    }

    /** 项目环境；不存在或不属于当前租户统一 404（不暴露别租户是否存在）。 */
    public Env require(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, dev_database, prod_database
                  from platform.project
                 where id = ? and tenant_id = ?
                """, projectId, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
        return toEnv(rows.get(0));
    }

    private static Env toEnv(Map<String, Object> row) {
        return new Env(((Number) row.get("id")).longValue(), str(row.get("code")), str(row.get("name")),
                str(row.get("dev_database")), str(row.get("prod_database")));
    }

    /**
     * 开发工作台只允许查本项目的 dev 库（D57）。
     *
     * <p>只挡「显式写了本项目生产库」这一种情况：不写库前缀的语句、查别的库、
     * 查系统库都不在这里判（另有租户与分级闸门），避免把正常查询误杀。
     * 生产数据要读，走发布之后的生产链路（调度 / 数据服务），那里按 PROD 解析。</p>
     */
    public void requireDevWorkbench(Env env, Set<String> databases) {
        if (env == null || databases == null || databases.isEmpty()
                || blank(env.prodDatabase())) {
            return;
        }
        for (String database : databases) {
            if (env.prodDatabase().equalsIgnoreCase(database)) {
                throw ApiException.badRequest("开发工作台不能直接查本项目的生产库 "
                        + env.prodDatabase() + "（项目 " + env.code() + "）；"
                        + "请改查开发库 " + (blank(env.devDatabase()) ? "<dev 库>" : env.devDatabase())
                        + "，生产数据请在发布后的调度 / 数据服务链路里访问");
            }
        }
    }

    /**
     * 一次性回填：把缺 dev/prod 库名的项目按编码补齐（D57 要求「已有项目提供一次性回填」）。
     *
     * @param create true 时才真的在 Doris 上建库（{@code ensureDatabase} 幂等，
     *               目标库存在且非空会抛 409 并原样报出去）；false 只补元数据，
     *               让运维先看清单再决定什么时候动数仓
     */
    public Map<String, Object> backfill(boolean create) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, dev_database, prod_database
                  from platform.project
                 where tenant_id = ?
                   and (dev_database is null or dev_database = ''
                        or prod_database is null or prod_database = '')
                 order by id
                """, TenantScope.current());
        List<Map<String, Object>> filled = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Env env = toEnv(row);
            String[] derived = deriveDatabases(env.code());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", env.projectId());
            item.put("code", env.code());
            if (derived == null) {
                item.put("reason", "项目编码推不出合法库名，请手工指定 devDatabase / prodDatabase");
                conflicts.add(item);
                continue;
            }
            long taken = referenceCount(derived[0], env.projectId()) + referenceCount(derived[1], env.projectId());
            if (taken > 0) {
                item.put("devDatabase", derived[0]);
                item.put("prodDatabase", derived[1]);
                item.put("reason", "库名已被其他项目占用（V49 唯一索引），请改编码或手工指定库名");
                conflicts.add(item);
                continue;
            }
            jdbc.update("""
                    update platform.project
                       set dev_database = ?, prod_database = ?
                     where id = ? and tenant_id = ?
                       and (dev_database is null or dev_database = ''
                            or prod_database is null or prod_database = '')
                    """, derived[0], derived[1], env.projectId(), TenantScope.current());
            item.put("devDatabase", derived[0]);
            item.put("prodDatabase", derived[1]);
            if (create) {
                Long connectionId = warehouses.connectionIdForProject(env.projectId());
                doris.ensureDatabase(connectionId, derived[0]);
                doris.ensureDatabase(connectionId, derived[1]);
                item.put("dorisCreated", true);
            }
            filled.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", TenantScope.current());
        out.put("scanned", rows.size());
        out.put("filled", filled.size());
        out.put("items", filled);
        out.put("conflicts", conflicts);
        out.put("dorisCreated", create);
        log.info("project database backfill tenant={} create={} scanned={} filled={} conflicts={}",
                TenantScope.current(), create, rows.size(), filled.size(), conflicts.size());
        return out;
    }

    /**
     * 引用里没有库前缀的表（用于「请写全限定名」提示，不做拦截）。
     *
     * <p>{@code DataGuardService.parsedTables} 已经把 SQL 里的表引用抽成
     * {@code [catalog.]schema.table} 或裸表名，这里只挑出裸名——比再造一个正则可靠。</p>
     */
    public static List<String> unqualifiedRefs(List<String> refs) {
        List<String> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (String ref : refs) {
            if (ref != null && !ref.isBlank() && !ref.contains(".")) {
                out.add(ref);
            }
        }
        return out;
    }

    /** 有多少项目引用了这个库名（V49 有唯一索引，正常最多 1；这里按集合算保险）。 */
    private long referenceCount(String database, long exceptProjectId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project
                 where id <> ?
                   and (lower(coalesce(dev_database, '')) = lower(?)
                     or lower(coalesce(prod_database, '')) = lower(?))
                """, Long.class, exceptProjectId, database, database);
        return n == null ? 0 : n;
    }

    /**
     * 空壳库巡检（D57：只增不减的历次测试库）。
     *
     * <p>只读：列出该数仓上的库、每个库的表数、被哪些项目引用，
     * 并把「0 张表且没有任何项目引用」标成空壳库。是否删、删哪些由人看清单后决定。</p>
     */
    public Map<String, Object> inventory(Long projectId) {
        Long connectionId = projectId == null ? null : warehouses.connectionIdForProject(projectId);
        List<String> databases = new ArrayList<>();
        for (Map<String, Object> row : doris.queryAdmin(connectionId, "show databases")) {
            String name = firstStringValue(row);
            if (name != null) {
                databases.add(name);
            }
        }
        Map<String, Long> tableCounts = tableCounts(connectionId);
        Map<String, List<String>> refs = referencedBy();
        List<Map<String, Object>> items = new ArrayList<>();
        int shells = 0;
        for (String database : databases) {
            long tables = tableCounts.getOrDefault(database.toLowerCase(Locale.ROOT), 0L);
            List<String> owners = refs.getOrDefault(database.toLowerCase(Locale.ROOT), List.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("database", database);
            item.put("tables", tables);
            item.put("referencedBy", owners);
            // 系统库不是「空壳」，别把它们算进待清理清单
            boolean system = Set.of("information_schema", "mysql", "__internal_schema",
                    "sys", "performance_schema").contains(database.toLowerCase(Locale.ROOT));
            item.put("system", system);
            item.put("emptyShell", !system && tables == 0 && owners.isEmpty());
            if (Boolean.TRUE.equals(item.get("emptyShell"))) {
                shells++;
            }
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", connectionId == null ? "内置 Doris" : "外部 Doris #" + connectionId);
        out.put("databases", items);
        out.put("total", items.size());
        out.put("emptyShells", shells);
        return out;
    }

    private Map<String, Long> tableCounts(Long connectionId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : doris.queryAdmin(connectionId, """
                select table_schema, count(*) as table_count
                  from information_schema.tables
                 group by table_schema
                """)) {
            String schema = null;
            long count = 0;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("table_schema")) {
                    schema = str(entry.getValue());
                } else if (key.startsWith("table_count") || key.startsWith("count")) {
                    count = entry.getValue() instanceof Number n ? n.longValue() : 0;
                }
            }
            if (schema != null) {
                counts.put(schema.toLowerCase(Locale.ROOT), count);
            }
        }
        return counts;
    }

    /**
     * 这个库里有多少张表；读不到返回 -1（调用方按「不删」处理，不猜）。
     *
     * <p>逐库单查而不是用 group by 汇总表：group by 只返回「有表的库」，
     * 真空库根本不出现在结果里，拿它判空会把该删的库当成读不到。</p>
     */
    private long tableCountIn(Long connectionId, String database) {
        List<Map<String, Object>> rows = doris.queryAdmin(connectionId,
                "select count(*) as table_count from information_schema.tables"
                        + " where lower(table_schema) = '" + database.toLowerCase(Locale.ROOT) + "'");
        for (Map<String, Object> row : rows) {
            for (Object value : row.values()) {
                if (value instanceof Number count) {
                    return count.longValue();
                }
            }
        }
        return -1;
    }

    /** 库名 → 引用它的项目编码（含别的租户：清理时必须看得见全部引用）。 */
    private Map<String, List<String>> referencedBy() {
        Map<String, List<String>> refs = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select code, dev_database, prod_database from platform.project
                """)) {
            addRef(refs, str(row.get("dev_database")), str(row.get("code")));
            addRef(refs, str(row.get("prod_database")), str(row.get("code")));
        }
        return refs;
    }

    private static void addRef(Map<String, List<String>> refs, String database, String code) {
        if (blank(database)) {
            return;
        }
        refs.computeIfAbsent(database.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(code);
    }

    /**
     * 删项目时清理它名下的库（D57 第 4 条，保守口径）：
     * <b>只删「0 张表」且「没有别的项目引用」的库</b>，其余一律保留并说明原因。
     *
     * <p>库里有数据就不能顺手删——历史上线上库和开发库同名时，误删是不可逆的。</p>
     */
    public List<Map<String, Object>> dropEmptyDatabases(Long connectionId, Collection<String> databases) {
        List<Map<String, Object>> actions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (databases != null) {
            databases.stream().filter(value -> value != null && !value.isBlank()).forEach(seen::add);
        }
        for (String database : seen) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("database", database);
            // 先验名字再碰任何语句：库名是用户可写字段，不能拼进查询
            safeDb(database);
            List<String> owners = referencedBy().getOrDefault(database.toLowerCase(Locale.ROOT), List.of());
            if (!owners.isEmpty()) {
                item.put("action", "KEPT");
                item.put("reason", "仍被项目引用：" + String.join("、", owners));
                actions.add(item);
                continue;
            }
            // 逐库问表数：group by 只会返回「有表的库」，用它判空会把真空库误判成查不到
            long tables = tableCountIn(connectionId, database);
            if (tables != 0) {
                item.put("action", "KEPT");
                item.put("reason", tables < 0 ? "数仓里查不到这个库的表数，按不删处理"
                        : "库里还有 " + tables + " 张表，删项目不清数据");
                actions.add(item);
                continue;
            }
            doris.execAdmin(connectionId, "drop database if exists `" + database + "`");
            item.put("action", "DROPPED");
            item.put("reason", "空库（0 张表）且无项目引用");
            actions.add(item);
            log.info("dropped empty project database {} on connection {}", database, connectionId);
        }
        return actions;
    }

    /** 库名来自项目元数据，但仍是用户可写字段：进语句前再过一次白名单。 */
    private static String safeDb(String database) {
        if (!database.matches("[A-Za-z0-9_]{1,64}")) {
            throw ApiException.badRequest("库名不合法，拒绝清理: " + database);
        }
        return database;
    }

    private static String firstStringValue(Map<String, Object> row) {
        for (Object value : row.values()) {
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
