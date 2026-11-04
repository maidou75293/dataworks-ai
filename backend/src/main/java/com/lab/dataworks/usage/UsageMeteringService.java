package com.lab.dataworks.usage;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.query.DorisQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TC7-G4：按项目/租户的用量计量（只定义口径并采集，不做计费与账单）。
 *
 * <p>验收契约 = docs/测试-TC7-G4.md §五，守卫 = scripts/e2e/tc7-g4-usage-metering-test.py。
 * <ul>
 *   <li>{@code queryCount / resultRows}：新采集表 {@code platform.usage_query_event}，
 *       采集点在 {@code POST /api/query/doris} 成功路径（V121）；</li>
 *   <li>{@code taskInstanceCount / taskDurationMs}：{@code wf_instance} 按
 *       {@code started_at} 落窗聚合，归属走 {@code workflow → project.tenant_id}
 *       （与 {@code OpsController.instances} 同一口径——实例表的 tenant_id 是派生列，
 *       历史行可能还是默认值，不能作归属判据）；</li>
 *   <li>{@code storageBytes}：项目 {@code dev_database/prod_database} 在所属 Doris 的
 *       {@code information_schema.tables.data_length} 合计。Doris 4.1 对 table_schema
 *       等值过滤返回 0 行（QueryController.loadSchema 同款坑），所以不带过滤全量拉、
 *       Java 侧按库名收口；结果按项目缓存 60 秒，采集失败该 projects 记 0——
 *       计量是只读旁路，不能把 Doris 抖动放大成 500；</li>
 *   <li>{@code scannedRows} 不入第一版（现网无来源）。</li>
 * </ul>
 *
 * <p>时间窗：{@code [from, to)} 闭开，日界取 UTC 零点（守卫用 gmtime 生成日期，同系）。</p>
 */
@Service
public class UsageMeteringService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(UsageMeteringService.class);

    /** 契约 8 键（docs/测试-TC7-G4.md §五.2；scannedRows 明确不在内）。 */
    static final Set<String> CONTRACT_KEYS = Set.of("groupKey", "groupName", "queryCount",
            "resultRows", "taskInstanceCount", "taskDurationMs", "storageBytes", "share");

    private static final long STORAGE_CACHE_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final DorisQueryService doris;

    /** projectId → 存储字节缓存（60s TTL；失败也缓存 0，避免每次归集都撞一次 Doris）。 */
    private final Map<Long, StorageCache> storageCache = new ConcurrentHashMap<>();

    private record StorageCache(long at, long bytes) {
    }

    public UsageMeteringService(JdbcTemplate jdbc, DorisQueryService doris) {
        this.jdbc = jdbc;
        this.doris = doris;
    }

    // ------------------------------------------------------------------ 参数校验面

    /** groupBy 必填且只认 project / tenant，其余 400（契约 §五.1，守卫 R05）。 */
    static String checkGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            throw ApiException.badRequest("groupBy 必填：project 或 tenant");
        }
        String gb = groupBy.trim().toLowerCase(Locale.ROOT);
        if (!"project".equals(gb) && !"tenant".equals(gb)) {
            throw ApiException.badRequest("groupBy 仅支持 project / tenant，收到: " + groupBy);
        }
        return gb;
    }

    /** from/to 必填且必须 YYYY-MM-DD，否则 400（守卫 R05 的可读 message）。 */
    static LocalDate checkDay(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 必填，格式 YYYY-MM-DD");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 日期格式应为 YYYY-MM-DD，收到: " + value);
        }
    }

    /** from > to → 400；from == to 是合法空窗（守卫 R04 用未来同日起零判定）。 */
    static void checkWindow(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("起始日期不能晚于结束日期：from=" + from + " > to=" + to);
        }
    }

    // ------------------------------------------------------------------ 采集面

    /**
     * 查询成功路径记一条用量事件（TC7-G4 scope 的「采集」二字，守卫 R08 钉它）。
     *
     * <p>计量失败绝不允许影响查询本身——但也不能静默吞：S13 的教训是「新失败路径
     * 只进内存 = 静默」，所以丢弃样本必须 error 留痕。</p>
     */
    public void recordQuery(String tenant, Long projectId, String username,
                            long resultRows, long elapsedMs) {
        if (tenant == null || tenant.isBlank()) {
            // 无租户上下文的事件没有归属面，跳过而不是写默认值——
            // 把无主样本记进 default 会虚增别的租户的用量。
            log.warn("用量计量跳过：无租户上下文 project={} user={}", projectId, username);
            return;
        }
        try {
            jdbc.update("""
                    insert into platform.usage_query_event
                           (tenant_id, project_id, username, result_rows, elapsed_ms)
                    values (?, ?, ?, ?, ?)""",
                    tenant, projectId, username, Math.max(0, resultRows), Math.max(0, elapsedMs));
        } catch (RuntimeException e) {
            log.error("用量计量写入失败 tenant={} project={} user={}: {}",
                    tenant, projectId, username, e.toString());
        }
    }

    /** 明细保留 ≥90 天（docs/测试-TC7-G4.md §四评审确认值），每 6 小时清一次。 */
    @Scheduled(fixedDelayString = "${platform.usage-prune-ms:21600000}", initialDelay = 300_000)
    public void pruneExpiredEvents() {
        try {
            int deleted = jdbc.update(
                    "delete from platform.usage_query_event where created_at < now() - interval '90 days'");
            if (deleted > 0) {
                log.info("用量明细清理：删除 90 天前事件 {} 条", deleted);
            }
        } catch (RuntimeException e) {
            log.error("用量明细清理失败（下个周期自动重试）: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ 归集面

    /** 窗口内按 project / tenant 两级归集；每行契约 8 键（值可为 0）。 */
    public List<Map<String, Object>> summary(String groupBy, LocalDate from, LocalDate to) {
        Timestamp f = Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp t = Timestamp.from(to.atStartOfDay(ZoneOffset.UTC).toInstant());
        String tenant = TenantScope.current();
        List<Map<String, Object>> rows =
                "tenant".equals(groupBy) ? tenantRows(f, t, tenant) : projectRows(f, t, tenant);
        attachShares(rows);
        return rows;
    }

    private List<Map<String, Object>> projectRows(Timestamp f, Timestamp t, String tenant) {
        // 枚举集必须与 GET /api/projects 完全一致（守卫 R07：行集 ⊆ 可见项目集）
        List<Map<String, Object>> projects = jdbc.queryForList("""
                select p.id, p.code, p.name, p.dev_database, p.prod_database
                  from platform.project p
                 where p.tenant_id = ?
                 order by p.id""", tenant);
        Map<Long, long[]> query = queryByProject(f, t, tenant);
        Map<Long, long[]> instances = instancesByProject(f, t, tenant);
        List<Map<String, Object>> rows = new ArrayList<>(projects.size());
        for (Map<String, Object> p : projects) {
            long id = ((Number) p.get("id")).longValue();
            long[] q = query.getOrDefault(id, new long[2]);
            long[] i = instances.getOrDefault(id, new long[2]);
            rows.add(row(str(p.get("code")), str(p.get("name")),
                    q[0], q[1], i[0], i[1], storageBytes(p)));
        }
        return rows;
    }

    private List<Map<String, Object>> tenantRows(Timestamp f, Timestamp t, String tenant) {
        boolean platform = TenantScope.DEFAULT.equalsIgnoreCase(tenant);
        List<Map<String, Object>> tenants;
        if (platform) {
            // 平台管理员 = 计费总览视角：全部租户（含默认租户自身）
            tenants = jdbc.queryForList("select code, name from platform.tenant order by code");
        } else {
            tenants = jdbc.queryForList(
                    "select code, name from platform.tenant where code = ?", tenant);
            if (tenants.isEmpty()) {
                // 兜底：当前租户码不在租户表（历史脏态）也要给出本租户行，不能让归集空转
                Map<String, Object> synthetic = new LinkedHashMap<>();
                synthetic.put("code", tenant);
                synthetic.put("name", tenant);
                tenants = List.of(synthetic);
            }
        }
        Map<String, long[]> query = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select tenant_id, count(*)::int c, coalesce(sum(result_rows), 0)::bigint r
                  from platform.usage_query_event
                 where created_at >= ? and created_at < ?
                 group by tenant_id""", f, t)) {
            query.put(str(r.get("tenant_id")),
                    new long[]{num(r.get("c")), num(r.get("r"))});
        }
        Map<String, long[]> instances = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select p.tenant_id, count(*)::int c,
                       coalesce(sum(case when i.ended_at > i.started_at
                                         then (extract(epoch from (i.ended_at - i.started_at)) * 1000)::bigint
                                         else 0 end), 0)::bigint dur
                  from platform.wf_instance i
                  join platform.workflow w on w.id = i.workflow_id
                  join platform.project p on p.id = w.project_id
                 where i.started_at is not null and i.started_at >= ? and i.started_at < ?
                 group by p.tenant_id""", f, t)) {
            instances.put(str(r.get("tenant_id")),
                    new long[]{num(r.get("c")), num(r.get("dur"))});
        }
        List<Map<String, Object>> rows = new ArrayList<>(tenants.size());
        for (Map<String, Object> tn : tenants) {
            String code = str(tn.get("code"));
            long[] q = query.getOrDefault(code, new long[2]);
            long[] i = instances.getOrDefault(code, new long[2]);
            String name = str(tn.get("name"));
            rows.add(row(code, name == null ? code : name,
                    q[0], q[1], i[0], i[1], tenantStorageBytes(code)));
        }
        return rows;
    }

    private Map<Long, long[]> queryByProject(Timestamp f, Timestamp t, String tenant) {
        Map<Long, long[]> out = new LinkedHashMap<>();
        // project_id 为空的直查事件只归租户，不进任何项目行（口径见 V121 迁移注释）
        for (Map<String, Object> r : jdbc.queryForList("""
                select project_id, count(*)::int c, coalesce(sum(result_rows), 0)::bigint r
                  from platform.usage_query_event
                 where tenant_id = ? and project_id is not null
                   and created_at >= ? and created_at < ?
                 group by project_id""", tenant, f, t)) {
            out.put(((Number) r.get("project_id")).longValue(),
                    new long[]{num(r.get("c")), num(r.get("r"))});
        }
        return out;
    }

    private Map<Long, long[]> instancesByProject(Timestamp f, Timestamp t, String tenant) {
        Map<Long, long[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select w.project_id as pid, count(*)::int c,
                       coalesce(sum(case when i.ended_at > i.started_at
                                         then (extract(epoch from (i.ended_at - i.started_at)) * 1000)::bigint
                                         else 0 end), 0)::bigint dur
                  from platform.wf_instance i
                  join platform.workflow w on w.id = i.workflow_id
                  join platform.project p on p.id = w.project_id
                 where p.tenant_id = ? and i.started_at is not null
                   and i.started_at >= ? and i.started_at < ?
                 group by w.project_id""", tenant, f, t)) {
            out.put(((Number) r.get("pid")).longValue(),
                    new long[]{num(r.get("c")), num(r.get("dur"))});
        }
        return out;
    }

    /** 行构造：键序即契约 §五.2 的 8 键；share 先占 0，attachShares 统一回填。 */
    static Map<String, Object> row(String groupKey, String groupName, long queryCount,
                                   long resultRows, long taskInstanceCount,
                                   long taskDurationMs, long storageBytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupKey", groupKey);
        m.put("groupName", groupName);
        m.put("queryCount", queryCount);
        m.put("resultRows", resultRows);
        m.put("taskInstanceCount", taskInstanceCount);
        m.put("taskDurationMs", taskDurationMs);
        m.put("storageBytes", storageBytes);
        m.put("share", 0.0);
        return m;
    }

    /** 组内占比（0–1）：权重 = 次数类计数（queryCount + taskInstanceCount）。 */
    static void attachShares(List<Map<String, Object>> rows) {
        long total = 0;
        for (Map<String, Object> r : rows) {
            total += weight(r);
        }
        for (Map<String, Object> r : rows) {
            r.put("share", shareOf(weight(r), total));
        }
    }

    private static long weight(Map<String, Object> r) {
        return ((Number) r.get("queryCount")).longValue()
                + ((Number) r.get("taskInstanceCount")).longValue();
    }

    /** 纯函数便于单测锁口径：总权重 0 → 0；否则四位小数四舍五入，界内 [0,1]。 */
    static double shareOf(long weight, long total) {
        if (total <= 0) {
            return 0.0;
        }
        double share = Math.round(weight * 10000.0 / total) / 10000.0;
        return Math.min(1.0, Math.max(0.0, share));
    }

    // ------------------------------------------------------------------ 存储采集面

    /** 项目的 dev/prod 库在所属 Doris 上的存储字节；采集失败按 0 并缓存（只读旁路）。 */
    private long storageBytes(Map<String, Object> project) {
        long id = ((Number) project.get("id")).longValue();
        Set<String> dbs = projectDatabases(project);
        if (dbs.isEmpty()) {
            return 0; // 未配库（守卫 C08 钉过 demo dev=null 的现网态）——合法 0，不发查询
        }
        StorageCache cached = storageCache.get(id);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < STORAGE_CACHE_MS) {
            return cached.bytes();
        }
        long bytes = 0;
        try {
            // Doris 4.1 对 information_schema 的 table_schema 等值/LIKE 过滤返回 0 行，
            // 所以全量拉回、Java 侧按库名收口（与 QueryController.loadSchema 同法）。
            DorisQueryService.Result result = doris.rawQuery(id,
                    "select table_schema, coalesce(data_length, 0) as data_length "
                            + "from information_schema.tables", 20000);
            bytes = sumBytes(result.rows(), dbs);
        } catch (RuntimeException e) {
            log.warn("用量计量存储采集失败（该项目按 0 计）projectId={}: {}", id, e.toString());
        }
        storageCache.put(id, new StorageCache(now, bytes));
        return bytes;
    }

    /**
     * 租户行存储 = 该租户全部项目存储合计（走同一 projectId 60s 缓存；
     * 缓存键是 projectId，与调用方租户无关——归属过滤已由 where tenant_id 完成，
     * 平台管理员读别的租户不会读到错实例，rawQuery 按项目路由）。
     */
    private long tenantStorageBytes(String tenantCode) {
        long total = 0;
        for (Map<String, Object> p : jdbc.queryForList("""
                select p.id, p.dev_database, p.prod_database
                  from platform.project p
                 where p.tenant_id = ?""", tenantCode)) {
            total += storageBytes(p);
        }
        return total;
    }

    private static Set<String> projectDatabases(Map<String, Object> project) {
        Set<String> out = new LinkedHashSet<>();
        for (String field : new String[]{"dev_database", "prod_database"}) {
            String db = str(project.get(field));
            if (db != null && !db.isBlank()) {
                out.add(db.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 纯函数：按小写库名集合过滤行（{table_schema, data_length}），非数字按 0。 */
    static long sumBytes(List<List<String>> rows, Set<String> databasesLower) {
        if (rows == null || databasesLower == null || databasesLower.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (List<String> row : rows) {
            if (row == null || row.size() < 2) {
                continue;
            }
            String schema = row.get(0);
            if (schema == null
                    || !databasesLower.contains(schema.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = row.get(1);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                total += new BigDecimal(value.trim()).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                // 非整数字节串（理论上不出现）按不计数处理；存储面是尽力而为的旁路
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ 小工具

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
