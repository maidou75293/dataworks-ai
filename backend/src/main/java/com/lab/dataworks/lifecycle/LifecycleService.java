package com.lab.dataworks.lifecycle;

import com.lab.dataworks.project.DorisAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表生命周期（D3 + D51）：热 → 冷（Doris Storage Policy 自动搬）→ 到期进**待确认清单** →
 * 人工确认 → 标记删除（改名进回收）→ 保留期满才物理删。
 *
 * <p><b>D51 之前</b>：{@code daily()} 直接 {@code run(false, null)}，到期分区当天就被
 * {@code DROP PARTITION} 物理删掉，没有任何人工确认闸门、也没有回收站。
 * 当时线上 6 张纳管表恰好 0 张满足删除条件（5 张非 Doris catalog、1 张非分区表），
 * 所以没造成损失 —— <b>没出事不等于有闸门</b>。现在这条链改成：</p>
 * <ol>
 *   <li>定时任务只做「标记到期 + 生成待确认清单 + 清理保留期满的回收分区」，
 *       **永不物理删除未经确认的分区**（第 5 条写死，见 {@link #run}）；</li>
 *   <li>确认动作按分区记 {@code CONFIRM} 日志（谁、什么时候）；</li>
 *   <li>执行时只处理「有 CONFIRM 且其后没有再被 TRASH」的分区，动作是
 *       {@code RENAME PARTITION} 进回收名（可恢复），不是 drop；</li>
 *   <li>回收分区超过 {@link #TRASH_RETENTION_DAYS} 才真 {@code DROP PARTITION}，
 *       恢复入口 {@link #recover} 能查到是谁删的、什么时候删的。</li>
 * </ol>
 *
 * <p>刻意不做的事：导出归档、parquet 转换、归档回查（用户 07:54 明确砍掉，见 V108 注释）。
 * 卡面第 3 条的「归档（移到冷/归档库）」因此仍不落地 —— 那是与既有决定冲突的需求，
 * 需要单独评审确认，不在这里偷偷实现。</p>
 *
 * <p>安全阀：只认 {@code pYYYYMMDD} / {@code dt=YYYY-MM-dd} 这类可解析的时间分区，
 * 认不出的一律跳过并记 SKIP；单表单次最多动 {@value #MAX_DROP_PER_RUN} 个分区。</p>
 */
@Service
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    /** 单表单次最多处理多少个分区，避免一次动作过大。 */
    private static final int MAX_DROP_PER_RUN = 30;
    /** 到期前多少天标记 EXPIRING。 */
    private static final int EXPIRING_WINDOW_DAYS = 30;
    /** D51：标记删除后的回收保留期（天）；期满才物理删。 */
    static final int TRASH_RETENTION_DAYS = 30;
    /** D51：回收分区名前缀。带这个前缀的分区才是本服务管的回收对象，别的名字一律不碰。 */
    static final String TRASH_PREFIX = "lctrash_";

    private static final Pattern P_DAY = Pattern.compile("(?i)^p?(\\d{8})$");
    private static final Pattern DT_DAY = Pattern.compile("(?i)^dt=(\\d{4}-\\d{2}-\\d{2})$");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;
    private final DorisAdminService doris;

    public LifecycleService(JdbcTemplate jdbc, DorisAdminService doris) {
        this.jdbc = jdbc;
        this.doris = doris;
    }

    public Map<String, Object> settings(long tableId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select id, tenant, catalog, schema_name, table_name,
                       retention_days, cold_after_days, lifecycle_status, created_at
                from platform.managed_table where id = ?
                """, tableId);
        return describe(row);
    }

    public Map<String, Object> updateSettings(long tableId, Integer retentionDays, Integer coldAfterDays) {
        int retention = retentionDays == null ? 1095 : Math.max(1, Math.min(3650, retentionDays));
        int cold = coldAfterDays == null ? 90 : Math.max(1, Math.min(3650, coldAfterDays));
        int updated = jdbc.update("""
                update platform.managed_table
                   set retention_days = ?, cold_after_days = ?
                 where id = ?
                """, retention, cold, tableId);
        if (updated == 0) {
            throw com.lab.dataworks.common.ApiException.notFound("表不存在: " + tableId);
        }
        return settings(tableId);
    }

    public List<Map<String, Object>> logs(Long tableId, int limit) {
        int size = Math.max(1, Math.min(500, limit));
        String sql = tableId == null
                ? "select * from platform.lifecycle_job_log order by created_at desc limit ?"
                : "select * from platform.lifecycle_job_log where table_id = ? order by created_at desc limit ?";
        return tableId == null
                ? jdbc.queryForList(sql, size)
                : jdbc.queryForList(sql, tableId, size);
    }

    /**
     * 每日 02:30 的定时任务（D51 改口径）：
     * 只做「标记到期 + 生成待确认清单 + 清理保留期满的回收分区」，
     * **不再直接物理删除任何未经确认的分区**。
     */
    @Scheduled(cron = "${platform.lifecycle-cron:0 30 2 * * *}")
    public void daily() {
        try {
            Map<String, Object> scan = scan(true, null, true);
            int purged = purgeExpiredTrash();
            log.info("lifecycle daily done: {} purged={}", scan.get("summary"), purged);
        } catch (Exception e) {
            log.warn("lifecycle daily failed: {}", e.getMessage());
        }
    }

    /**
     * 执行一轮扫描（D51：带人工确认闸门）。
     *
     * @param dryRun true 只记账不动手
     * @param onlyTableId 只处理某张表（空=全部）
     */
    public Map<String, Object> run(boolean dryRun, Long onlyTableId) {
        return scan(dryRun, onlyTableId, false);
    }

    /**
     * @param scheduled true 表示由定时任务调用：此时**不要求**确认（只生成清单），
     *                  且绝不会走到 rename/drop 分支。
     */
    private Map<String, Object> scan(boolean dryRun, Long onlyTableId, boolean scheduled) {
        String sql = """
                select id, tenant, catalog, schema_name, table_name, owner_user,
                       retention_days, cold_after_days, lifecycle_status, created_at
                from platform.managed_table
                where (?::bigint is null or id = ?::bigint)
                """;
        List<Map<String, Object>> tables = jdbc.queryForList(sql, onlyTableId, onlyTableId);

        int expired = 0;
        int pending = 0;
        int trashed = 0;
        int unconfirmed = 0;
        int skipped = 0;
        List<String> details = new ArrayList<>();

        for (Map<String, Object> t : tables) {
            long tableId = ((Number) t.get("id")).longValue();
            String catalog = str(t.get("catalog"));
            String schema = str(t.get("schema_name"));
            String table = str(t.get("table_name"));
            int retention = ((Number) t.get("retention_days")).intValue();

            try {
                if (markExpiring(t, retention)) {
                    expired++;
                }
                if (!catalog.toLowerCase(Locale.ROOT).contains("doris")) {
                    continue;   // 只对 Doris 内表做分区回收；湖表依赖 Iceberg 快照策略
                }
                List<String> partitions = listPartitions(schema, table);
                LocalDate today = LocalDate.now();
                int handledHere = 0;
                for (String p : partitions) {
                    if (p.startsWith(TRASH_PREFIX)) {
                        continue;   // 已在回收名下的分区，交给 purge 阶段，不参与本轮
                    }
                    LocalDate day = parsePartitionDay(p);
                    if (day == null) {
                        logAction(tableId, t, p, "SKIP", "无法识别的时间分区，跳过");
                        skipped++;
                        continue;
                    }
                    if (day.plusDays(retention).isAfter(today)) {
                        continue;   // 还没到期
                    }
                    if (handledHere >= MAX_DROP_PER_RUN) {
                        logAction(tableId, t, p, "SKIP", "达到单次上限 " + MAX_DROP_PER_RUN + "，下次继续");
                        skipped++;
                        continue;
                    }
                    // D51 第 5 条（写死）：未经确认的到期分区永远不自动处理。
                    if (scheduled || !isConfirmed(tableId, p)) {
                        pending++;
                        unconfirmed++;
                        if (!scheduled) {
                            logAction(tableId, t, p, "PENDING_CONFIRM",
                                    "到期日 " + day + "，未确认不处理（需表负责人或平台管理员确认）");
                        }
                        continue;
                    }
                    if (handledHere >= MAX_DROP_PER_RUN) {
                        continue;
                    }
                    long startedAt = System.currentTimeMillis();
                    String trashName = trashNameOf(p);
                    if (dryRun) {
                        logAction(tableId, t, p, "TRASH_PARTITION",
                                "dry-run：到期日 " + day + " 将改名为 " + trashName
                                        + "（回收保留 " + TRASH_RETENTION_DAYS + " 天）");
                        details.add(schema + "." + table + "." + p + "(dry-run)");
                    } else {
                        doris.execAdmin("alter table `" + schema + "` .`" + table
                                + "` rename partition `" + p + "` " + trashName);
                        logAction(tableId, t, p, "TRASH_PARTITION",
                                "到期日 " + day + "；改名 " + trashName
                                        + "；回收保留 " + TRASH_RETENTION_DAYS + " 天"
                                        + "；耗时 " + (System.currentTimeMillis() - startedAt) + "ms");
                        markStatus(tableId, "CONFIRMED_TRASHED");
                        details.add(schema + "." + table + "." + p);
                        handledHere++;
                        trashed++;
                    }
                }
            } catch (Exception e) {
                logAction(tableId, t, null, "ERROR", e.getMessage());
                log.warn("lifecycle table {} failed: {}", tableId, e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("scheduled", scheduled);
        out.put("tables", tables.size());
        out.put("expiringMarked", expired);
        out.put("partitionsTrashed", trashed);
        out.put("pendingConfirm", pending);
        out.put("unconfirmedSkipped", unconfirmed);
        out.put("skipped", skipped);
        out.put("details", details.size() > 50 ? details.subList(0, 50) : details);
        out.put("summary", "expiring=" + expired + " trashed=" + trashed
                + " pendingConfirm=" + pending + " skipped=" + skipped);
        return out;
    }

    /**
     * 待确认清单（D51 第 1 条）：到期但还没被确认的分区，按表汇总。
     * 只读，不改状态、不动数据。
     */
    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("""
                select id, tenant, catalog, schema_name, table_name, owner_user,
                       retention_days, lifecycle_status, created_at
                  from platform.managed_table
                 where tenant = ?
                 order by id
                """, com.lab.dataworks.auth.TenantScope.current())) {
            if (!str(t.get("catalog")).toLowerCase(Locale.ROOT).contains("doris")) {
                continue;
            }
            long tableId = ((Number) t.get("id")).longValue();
            int retention = ((Number) t.get("retention_days")).intValue();
            for (String p : listPartitions(str(t.get("schema_name")), str(t.get("table_name")))) {
                if (p.startsWith(TRASH_PREFIX)) {
                    continue;
                }
                LocalDate day = parsePartitionDay(p);
                if (day == null || day.plusDays(retention).isAfter(LocalDate.now())) {
                    continue;
                }
                if (isConfirmed(tableId, p)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableId", tableId);
                item.put("tenant", t.get("tenant"));
                item.put("database", t.get("schema_name"));
                item.put("table", t.get("table_name"));
                item.put("partition", p);
                item.put("expireDay", day.plusDays(retention).toString());
                item.put("retentionDays", retention);
                item.put("ownerUser", t.get("owner_user"));
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 人工确认（D51 第 2 条）：只有表负责人（{@code managed_table.owner_user}）
     * 或平台管理员能确认；按分区记 {@code CONFIRM} 日志，作为执行闸门。
     */
    public Map<String, Object> confirm(long tableId, List<String> partitions, String actor,
                                       boolean platformAdmin) {
        Map<String, Object> t = tableRow(tableId);
        if (!platformAdmin && !actor.equals(str(t.get("owner_user")))) {
            throw new com.lab.dataworks.common.ApiException(403,
                    "只有表负责人（" + t.get("owner_user") + "）或平台管理员可以确认生命周期删除");
        }
        if (partitions == null || partitions.isEmpty()) {
            throw com.lab.dataworks.common.ApiException.badRequest("要确认的分区不能为空");
        }
        List<String> allowed = listPartitions(str(t.get("schema_name")), str(t.get("table_name")));
        int confirmed = 0;
        for (String p : partitions) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String name = p.trim();
            if (name.startsWith(TRASH_PREFIX) || !allowed.contains(name)) {
                throw com.lab.dataworks.common.ApiException.badRequest(
                        "分区不属于该表或已在回收中，不能确认: " + name);
            }
            if (isConfirmed(tableId, name)) {
                continue;   // 幂等：重复确认不重复记账
            }
            logAction(tableId, t, name, "CONFIRM", "确认人 " + actor);
            confirmed++;
        }
        markStatus(tableId, "CONFIRMED");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tableId", tableId);
        out.put("confirmed", confirmed);
        out.put("summary", "confirmed=" + confirmed);
        return out;
    }

    /**
     * 恢复（D51 第 4 条）：把回收中的分区改回原名。detail 里能查到是谁、什么时候删的。
     */
    public Map<String, Object> recover(long tableId, String trashName, String actor,
                                       boolean platformAdmin) {
        Map<String, Object> t = tableRow(tableId);
        if (!platformAdmin && !actor.equals(str(t.get("owner_user")))) {
            throw new com.lab.dataworks.common.ApiException(403,
                    "只有表负责人或平台管理员可以恢复回收中的分区");
        }
        if (trashName == null || !trashName.startsWith(TRASH_PREFIX)) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "只有 " + TRASH_PREFIX + "* 形态的回收分区能被恢复，收到: " + trashName);
        }
        String original = originalNameOf(trashName);
        List<String> partitions = listPartitions(str(t.get("schema_name")), str(t.get("table_name")));
        if (!partitions.contains(trashName)) {
            throw com.lab.dataworks.common.ApiException.notFound("回收分区不存在: " + trashName);
        }
        long startedAt = System.currentTimeMillis();
        doris.execAdmin("alter table `" + str(t.get("schema_name")) + "` .`" + str(t.get("table_name"))
                + "` rename partition `" + trashName + "` " + original);
        Map<String, Object> whoDeleted = jdbc.queryForList("""
                select detail, created_at from platform.lifecycle_job_log
                 where table_id = ? and partition_name = ? and action = 'TRASH_PARTITION'
                 order by created_at desc limit 1
                """, tableId, original).stream().findFirst().orElse(Map.of());
        logAction(tableId, t, original, "RECOVER_PARTITION",
                "恢复人 " + actor + "；原回收名 " + trashName
                        + "；删除记录 " + (whoDeleted.isEmpty() ? "（无，可能来自更早版本）"
                        : str(whoDeleted.get("created_at")) + " " + str(whoDeleted.get("detail")))
                        + "；耗时 " + (System.currentTimeMillis() - startedAt) + "ms");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tableId", tableId);
        out.put("partition", original);
        out.put("restored", true);
        return out;
    }

    /** 回收中的分区（含被谁删的），给界面「回收站」用。 */
    public List<Map<String, Object>> trashed() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("""
                select id, tenant, catalog, schema_name, table_name, owner_user, retention_days
                  from platform.managed_table where tenant = ? order by id
                """, com.lab.dataworks.auth.TenantScope.current())) {
            if (!str(t.get("catalog")).toLowerCase(Locale.ROOT).contains("doris")) {
                continue;
            }
            for (String p : listPartitions(str(t.get("schema_name")), str(t.get("table_name")))) {
                if (!p.startsWith(TRASH_PREFIX)) {
                    continue;
                }
                String original = originalNameOf(p);
                Map<String, Object> logRow = jdbc.queryForList("""
                        select detail, created_at from platform.lifecycle_job_log
                         where table_id = ? and partition_name = ? and action = 'TRASH_PARTITION'
                         order by created_at desc limit 1
                        """, ((Number) t.get("id")).longValue(), original)
                        .stream().findFirst().orElse(Map.of());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableId", t.get("id"));
                item.put("database", t.get("schema_name"));
                item.put("table", t.get("table_name"));
                item.put("trashName", p);
                item.put("partition", original);
                item.put("deletedAt", logRow.get("created_at"));
                item.put("deletedDetail", logRow.get("detail"));
                item.put("ownerUser", t.get("owner_user"));
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 保留期满的回收分区才真删（D51 第 4 条后半）。
     * 只认 {@code lctrash_} 前缀 + 有 TRASH 日志的分区，其他一律不碰。
     */
    /** 包内可见：便于单测直接验证「保留期满才物理删」。 */
    int purgeExpiredTrash() {
        int purged = 0;
        for (Map<String, Object> row : jdbc.queryForList("""
                select l.table_id, l.partition_name, l.created_at,
                       m.catalog, m.schema_name, m.table_name
                  from platform.lifecycle_job_log l
                  join platform.managed_table m on m.id = l.table_id
                 where l.action = 'TRASH_PARTITION'
                   and l.created_at < now() - (? * interval '1 day')
                 order by l.created_at
                """, TRASH_RETENTION_DAYS)) {
            if (!str(row.get("catalog")).toLowerCase(Locale.ROOT).contains("doris")) {
                continue;
            }
            String original = str(row.get("partition_name"));
            String trashName = trashNameOf(original);
            long tableId = ((Number) row.get("table_id")).longValue();
            try {
                List<String> partitions = listPartitions(str(row.get("schema_name")),
                        str(row.get("table_name")));
                if (!partitions.contains(trashName)) {
                    continue;   // 已被恢复或本来不存在
                }
                long startedAt = System.currentTimeMillis();
                doris.execAdmin("alter table `" + str(row.get("schema_name")) + "` .`"
                        + str(row.get("table_name")) + "` drop partition `" + trashName + "`");
                logAction(tableId, Map.of("tenant", "", "catalog", str(row.get("catalog")),
                        "schema_name", str(row.get("schema_name")),
                        "table_name", str(row.get("table_name")), "owner_user", ""),
                        original, "PURGE_PARTITION",
                        "回收期满 " + TRASH_RETENTION_DAYS + " 天，物理删除 " + trashName
                                + "；耗时 " + (System.currentTimeMillis() - startedAt) + "ms");
                purged++;
            } catch (Exception e) {
                log.warn("lifecycle purge failed table={} trash={}: {}", tableId, trashName,
                        e.getMessage());
            }
        }
        return purged;
    }

    /** 已确认 = 有 CONFIRM 日志，且其后没有再被 TRASH（避免同一分区反复删）。 */
    private boolean isConfirmed(long tableId, String partition) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select action from platform.lifecycle_job_log
                 where table_id = ? and partition_name = ? and action in ('CONFIRM', 'TRASH_PARTITION')
                 order by created_at desc, id desc limit 1
                """, tableId, partition);
        return !rows.isEmpty() && "CONFIRM".equals(str(rows.get(0).get("action")));
    }

    private Map<String, Object> tableRow(long tableId) {
        // 按 id 直达的写路径也要带租户条件（by-id 租户隔离口径）：
        // 用户名跨租户可复用，只判 owner_user 不够，别的租户的人不能确认/恢复他租户的表。
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant, catalog, schema_name, table_name, owner_user,
                       retention_days, cold_after_days, lifecycle_status, created_at
                  from platform.managed_table where id = ? and tenant = ?
                """, tableId, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw com.lab.dataworks.common.ApiException.notFound("表不存在: " + tableId);
        }
        return rows.get(0);
    }

    private void markStatus(long tableId, String status) {
        jdbc.update("update platform.managed_table set lifecycle_status = ?"
                + " where id = ? and tenant = ?",
                status, tableId, com.lab.dataworks.auth.TenantScope.current());
    }

    /** 回收名带上原分区名与时间戳，恢复时反推原名；长度受 Doris 限制，超长按截断处理。 */
    static String trashNameOf(String partition) {
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String base = partition.length() > 80 ? partition.substring(0, 80) : partition;
        return TRASH_PREFIX + base + "_" + stamp;
    }

    /** {@code lctrash_<原名>_<时间戳>} → {@code <原名>}；原名里带下划线也能反推（时间戳固定 14 位）。 */
    static String originalNameOf(String trashName) {
        String body = trashName.substring(TRASH_PREFIX.length());
        int cut = body.length() - 15;   // 末尾 "_yyyyMMddHHmmss"
        return cut > 0 ? body.substring(0, cut) : body;
    }

    private boolean markExpiring(Map<String, Object> t, int retention) {
        Object created = t.get("created_at");
        if (!(created instanceof Timestamp ts)) {
            return false;
        }
        LocalDate createdDay = ts.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        LocalDate expireDay = createdDay.plusDays(retention);
        boolean soon = !expireDay.isAfter(LocalDate.now().plusDays(EXPIRING_WINDOW_DAYS));
        String status = str(t.get("lifecycle_status"));
        if (soon && "ACTIVE".equals(status)) {
            jdbc.update("update platform.managed_table set lifecycle_status = 'EXPIRING' where id = ?",
                    ((Number) t.get("id")).longValue());
            logAction(((Number) t.get("id")).longValue(), t, null, "EXPIRE_MARK",
                    EXPIRING_WINDOW_DAYS + " 天内到期（" + expireDay + "）");
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> listPartitions(String schema, String table) {
        List<Map<String, Object>> rows = doris.queryAdmin(
                "show partitions from `" + schema + "` .`" + table + "`");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("PartitionName");
            if (name == null) {
                name = row.get("partition_name");
            }
            if (name != null) {
                out.add(String.valueOf(name));
            }
        }
        return out;
    }

    private static LocalDate parsePartitionDay(String partition) {
        if (partition == null) {
            return null;
        }
        String v = partition.trim();
        Matcher m = DT_DAY.matcher(v);
        if (m.matches()) {
            return LocalDate.parse(m.group(1));
        }
        m = P_DAY.matcher(v);
        if (m.matches()) {
            return LocalDate.parse(m.group(1), YMD);
        }
        return null;
    }

    private void logAction(long tableId, Map<String, Object> t, String partition,
                           String action, String detail) {
        jdbc.update("""
                insert into platform.lifecycle_job_log
                    (table_id, tenant, catalog, schema_name, table_name, partition_name, action, detail)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, tableId, str(t.get("tenant")), str(t.get("catalog")),
                str(t.get("schema_name")), str(t.get("table_name")), partition, action, detail);
    }

    private Map<String, Object> describe(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        int retention = ((Number) row.get("retention_days")).intValue();
        int cold = ((Number) row.get("cold_after_days")).intValue();
        Object created = row.get("created_at");
        long ageDays = 0;
        LocalDate expireDay = null;
        if (created instanceof Timestamp ts) {
            LocalDate createdDay = ts.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            ageDays = java.time.temporal.ChronoUnit.DAYS.between(createdDay, LocalDate.now());
            expireDay = createdDay.plusDays(retention);
        }
        String stage = ageDays < cold ? "HOT" : "COLD";
        out.put("ageDays", ageDays);
        out.put("coldAfterDays", cold);
        out.put("retentionDays", retention);
        out.put("stage", stage);
        out.put("expireDay", expireDay == null ? null : expireDay.toString());
        out.put("daysToExpire", expireDay == null ? null
                : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expireDay));
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
