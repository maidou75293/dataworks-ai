package com.lab.dataworks.lifecycle;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D51 回归：生命周期删除必须过人工确认闸门，且删除可恢复。
 *
 * <p>立案症状（卡原文写「没有任何删除/归档执行代码」，实测不成立 —— 见
 * {@code docs/运维-生命周期自动删除闸门-D51-20261026.md}）：
 * 删除链其实<b>已经上线并且每天自动跑</b>：
 * {@code daily() → run(false, null) → alter table … drop partition}，
 * 没有闸门、没有回收站、没有任何开关能关。线上 6 张纳管表恰好 0 张满足条件
 * ⇒ 没出事，但<b>没出事 ≠ 有闸门</b>。</p>
 *
 * <p>本测试钉住改后的四条：</p>
 * <ol>
 *   <li>未确认的到期分区<b>永远不被处理</b>（含定时任务那条路径）；</li>
 *   <li>确认之后执行的动作是「改名进回收」，不是直接 {@code drop partition}；</li>
 *   <li>回收中的分区可以恢复，且恢复记录里能查到是谁删的；</li>
 *   <li>只有保留期满（30 天）的回收分区才被物理删除，且只认 {@code lctrash_} 前缀。</li>
 * </ol>
 */
class LifecycleConfirmationTest {

    private static final long TABLE_ID = 121L;
    private static final String TENANT = "default";
    private static final String OWNER = "alice";
    private static final String EXPIRED_PARTITION = "p20200101";   // 远超 1095 天保留

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DorisAdminService doris = mock(DorisAdminService.class);
    private LifecycleService service;

    /** 表在 Doris 里的真实分区（rename 会改这份，替身才有「回收中」的状态）。 */
    private final List<String> partitions = new ArrayList<>();
    /** lifecycle_job_log 的内存替身。 */
    private final List<Map<String, Object>> logs = new ArrayList<>();
    private String lifecycleStatus = "ACTIVE";

    @BeforeEach
    void setUp() {
        partitions.add(EXPIRED_PARTITION);
        service = new LifecycleService(jdbc, doris);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = ((String) inv.getArgument(0)).toLowerCase();
            Object[] a = arguments(inv.getArguments());
            if (sql.contains("join platform.managed_table")) {
                return purgeCandidates();
            }
            if (sql.contains("from platform.lifecycle_job_log")) {
                return jobLogRows(sql, a);
            }
            if (sql.contains("from platform.managed_table")) {
                // 按 id 直达的查询要带租户条件：替身按 tenant 参数分流，
                // 否则「跨租户确认」这条守卫红了也发现不了（tableRow 少写 and tenant = ?）
                String wanted = a.length > 1 && a[a.length - 1] instanceof String t ? t : TENANT;
                return TENANT.equals(wanted) ? List.of(managedTable()) : List.of();
            }
            return List.of();
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = ((String) inv.getArgument(0)).toLowerCase();
            Object[] a = arguments(inv.getArguments());
            if (sql.contains("insert into platform.lifecycle_job_log")) {
                Map<String, Object> row = new LinkedHashMap<>();
                // insert 的参数顺序：table_id, tenant, catalog, schema_name, table_name,
                // partition_name, action, detail
                row.put("table_id", a[0]);
                row.put("partition_name", a[5]);
                row.put("action", a[6]);
                row.put("detail", a[7]);
                row.put("created_at", Timestamp.from(Instant.now()));
                logs.add(row);
            }
            if (sql.contains("set lifecycle_status")) {
                // update ... set lifecycle_status = ? where id = ? → a[0]=状态, a[1]=id
                lifecycleStatus = String.valueOf(a[0]);
            }
            return 1;
        });
        when(doris.queryAdmin(anyString())).thenAnswer(inv -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String p : partitions) {
                out.add(Map.of("PartitionName", p));
            }
            return out;
        });
    }

    // ---------------------------------------------------------------- 1. 闸门

    @Test
    void expiredPartitionIsNeverTouchedWithoutConfirmation() {
        Map<String, Object> result = service.run(false, TABLE_ID);

        verify(doris, never()).execAdmin(anyString());
        assertEquals(0, ((Number) result.get("partitionsTrashed")).intValue(), result.toString());
        assertEquals(1, ((Number) result.get("pendingConfirm")).intValue(), result.toString());
        assertTrue(logs.stream().anyMatch(l -> "PENDING_CONFIRM".equals(l.get("action"))),
                "未确认的到期分区要留下待确认痕迹：" + logs);
        assertTrue(partitions.contains(EXPIRED_PARTITION), "分区必须还在");
    }

    @Test
    void scheduledDailyCannotTrashEvenWhenConfirmed() {
        // 定时任务这条路径不该走到「改名进回收」：它只生成待确认清单 + 清理过期回收。
        // 先确认，再跑 daily()，分区必须还挂在自己名下（没被 rename 走）。
        service.confirm(TABLE_ID, List.of(EXPIRED_PARTITION), OWNER, false);

        service.daily();

        verify(doris, never()).execAdmin(org.mockito.ArgumentMatchers.argThat(
                sql -> sql != null && sql.contains("rename partition")));
        assertTrue(partitions.contains(EXPIRED_PARTITION), "daily 不能把已确认分区直接收走");
    }

    // ---------------------------------------------------------------- 2. 确认后 = 回收，不是直删

    @Test
    void confirmedPartitionIsRenamedIntoTrashNotDropped() {
        service.confirm(TABLE_ID, List.of(EXPIRED_PARTITION), OWNER, false);

        Map<String, Object> result = service.run(false, TABLE_ID);

        assertEquals(1, ((Number) result.get("partitionsTrashed")).intValue(), result.toString());
        verify(doris, atLeastOnce()).execAdmin(org.mockito.ArgumentMatchers.argThat(
                sql -> sql.contains("rename partition") && sql.contains(LifecycleService.TRASH_PREFIX)));
        verify(doris, never()).execAdmin(org.mockito.ArgumentMatchers.argThat(
                sql -> sql.contains("drop partition")));
        Map<String, Object> trashed = logs.stream()
                .filter(l -> "TRASH_PARTITION".equals(l.get("action"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(trashed.get("detail")).contains("耗时"),
                "执行记录要带耗时（卡第 3 条）：" + trashed);
        assertTrue(String.valueOf(trashed.get("detail"))
                .contains("回收保留 " + LifecycleService.TRASH_RETENTION_DAYS + " 天"),
                String.valueOf(trashed.get("detail")));
    }

    @Test
    void confirmRejectsStrangersAndForeignPartitions() {
        ApiException stranger = assertThrows(ApiException.class,
                () -> service.confirm(TABLE_ID, List.of(EXPIRED_PARTITION), "bob", false));
        assertEquals(403, stranger.getStatus(), stranger.getMessage());

        service.confirm(TABLE_ID, List.of(EXPIRED_PARTITION), OWNER, false);
        ApiException foreign = assertThrows(ApiException.class,
                () -> service.confirm(TABLE_ID, List.of("p19990101"), OWNER, false));
        assertEquals(400, foreign.getStatus(), foreign.getMessage());

        // 平台管理员不需要是负责人
        service.confirm(TABLE_ID, List.of(EXPIRED_PARTITION), "root", true);
        assertEquals(1, logs.stream().filter(l -> "CONFIRM".equals(l.get("action"))).count(),
                "重复确认要幂等：" + logs);
    }

    // ---------------------------------------------------------------- 3. 可恢复

    @Test
    void trashCanBeRestoredAndShowsWhoDeletedIt() {
        service.confirm(TABLE_ID, List.of(EXPIRED_PARTITION), OWNER, false);
        service.run(false, TABLE_ID);
        String trashName = logs.stream()
                .filter(l -> "TRASH_PARTITION".equals(l.get("action")))
                .map(l -> LifecycleService.trashNameOf(String.valueOf(l.get("partition_name"))))
                .findFirst().orElseThrow();
        partitions.remove(EXPIRED_PARTITION);
        partitions.add(trashName);   // rename 之后 Doris 里只剩回收名

        Map<String, Object> out = service.recover(TABLE_ID, trashName, OWNER, false);

        assertEquals(Boolean.TRUE, out.get("restored"));
        verify(doris, atLeastOnce()).execAdmin(org.mockito.ArgumentMatchers.argThat(
                sql -> sql.contains("rename partition") && sql.contains(trashName)
                        && sql.endsWith(EXPIRED_PARTITION)));
        Map<String, Object> recovered = logs.stream()
                .filter(l -> "RECOVER_PARTITION".equals(l.get("action")))
                .findFirst().orElseThrow();
        String detail = String.valueOf(recovered.get("detail"));
        assertTrue(detail.contains("删除记录"), detail);
        assertTrue(detail.contains("确认人 " + OWNER), "恢复记录要能查到是谁删的：" + detail);
    }

    @Test
    void recoverOnlyAcceptsTrashNames() {
        ApiException e = assertThrows(ApiException.class,
                () -> service.recover(TABLE_ID, EXPIRED_PARTITION, OWNER, false));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains(LifecycleService.TRASH_PREFIX), e.getMessage());

        ApiException stranger = assertThrows(ApiException.class,
                () -> service.recover(TABLE_ID, LifecycleService.TRASH_PREFIX + "x_20261026000000",
                        "bob", false));
        assertEquals(403, stranger.getStatus());
    }

    // ---------------------------------------------------------------- 4. 期满才物理删

    @Test
    void purgeDropsExpiredTrashAndRecordsIt() {
        // purgeCandidates() 给的是「31 天前」的 TRASH 记录（超过 30 天保留期）；
        // Doris 里必须真的还有那个回收分区，purge 才会动手
        partitions.clear();
        partitions.add(LifecycleService.trashNameOf(EXPIRED_PARTITION));
        int purged = service.purgeExpiredTrash();

        assertEquals(1, purged);
        verify(doris).execAdmin(org.mockito.ArgumentMatchers.argThat(
                sql -> sql != null && sql.contains("drop partition")
                        && sql.contains(LifecycleService.TRASH_PREFIX)));
        Map<String, Object> purgedLog = logs.stream()
                .filter(l -> "PURGE_PARTITION".equals(l.get("action"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(purgedLog.get("detail")).contains("耗时"),
                String.valueOf(purgedLog.get("detail")));
    }

    @Test
    void purgeSkipsTrashPartitionThatWasRestored() {
        // 已经被恢复（回收名不存在了）⇒ 不该再发 drop
        partitions.clear();
        int purged = service.purgeExpiredTrash();

        assertEquals(0, purged);
        verify(doris, never()).execAdmin(org.mockito.ArgumentMatchers.argThat(
                sql -> sql != null && sql.contains("drop partition")));
    }

    @Test
    void otherTenantCannotSeeOrConfirmTheTable() {
        // by-id 直达也要租户条件：别的租户的人（哪怕用户名撞上了 owner）不能确认他租户的表
        com.lab.dataworks.auth.TenantScope.set("tenant_b");
        try {
            ApiException e = assertThrows(ApiException.class,
                    () -> service.confirm(TABLE_ID, List.of(EXPIRED_PARTITION), OWNER, false));
            assertEquals(404, e.getStatus(), e.getMessage());
            verify(doris, never()).execAdmin(anyString());
        } finally {
            com.lab.dataworks.auth.TenantScope.clear();
        }
    }

    @Test
    void trashNameRoundTripKeepsOriginalName() {
        for (String name : List.of("p20200101", "dt=2026-01-02", "p20200101_extra")) {
            String trash = LifecycleService.trashNameOf(name);
            assertTrue(trash.startsWith(LifecycleService.TRASH_PREFIX), trash);
            assertEquals(name, LifecycleService.originalNameOf(trash), trash);
        }
    }

    // ---------------------------------------------------------------- 替身

    private Map<String, Object> managedTable() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", TABLE_ID);
        row.put("tenant", "default");
        row.put("catalog", "Doris");
        row.put("schema_name", "demo_retail");
        row.put("table_name", "ods_user");
        row.put("owner_user", OWNER);
        row.put("retention_days", 3);          // 让 p20200101 一定已到期
        row.put("cold_after_days", 1);
        row.put("lifecycle_status", lifecycleStatus);
        row.put("created_at", Timestamp.from(LocalDate.of(2020, 1, 1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
        return row;
    }

    /** purge 阶段的查询：一条 31 天前的 TRASH 记录（超过 30 天保留期）。 */
    private List<Map<String, Object>> purgeCandidates() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_id", TABLE_ID);
        row.put("partition_name", EXPIRED_PARTITION);
        row.put("created_at", Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)));
        row.put("catalog", "Doris");
        row.put("schema_name", "demo_retail");
        row.put("table_name", "ods_user");
        return List.of(row);
    }

    private Map<String, Object> trashLog(long daysAgo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_id", TABLE_ID);
        row.put("partition_name", EXPIRED_PARTITION);
        row.put("action", "TRASH_PARTITION");
        row.put("detail", "确认人 " + OWNER);
        row.put("created_at", Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)));
        return row;
    }

    private List<Map<String, Object>> jobLogRows(String sql, Object[] args) {
        long tableId = args.length > 1 && args[1] instanceof Number n ? n.longValue() : TABLE_ID;
        String partition = args.length > 2 && args[2] != null ? String.valueOf(args[2]) : null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : logs) {
            if (partition != null && !partition.equals(row.get("partition_name"))) {
                continue;
            }
            if (sql.contains("action in ('confirm', 'trash_partition')")) {
                if ("CONFIRM".equals(row.get("action")) || "TRASH_PARTITION".equals(row.get("action"))) {
                    out.add(row);
                }
            } else {
                out.add(row);
            }
        }
        return out;
    }

    private static Object[] arguments(Object[] invocationArgs) {
        Object last = invocationArgs[invocationArgs.length - 1];
        if (last instanceof Object[] arr && invocationArgs.length == 2) {
            return arr;
        }
        Object[] tail = new Object[invocationArgs.length - 1];
        System.arraycopy(invocationArgs, 1, tail, 0, tail.length);
        return tail;
    }
}
