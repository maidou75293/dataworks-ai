package com.lab.dataworks.access;

import com.lab.dataworks.auth.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D52 到期提醒：判定与留痕的口径。
 *
 * <p>提醒本体（webhook/邮件/钉钉）由 {@code NotifyService} 的投递循环负责，
 * 本测试只管三件事：</p>
 * <ol>
 *   <li><b>可核对</b>：提醒内容必须同时带申请人、审批人、资源、到期时间、剩余时间、续期入口；</li>
 *   <li><b>幂等条件在 SQL 里</b>：候选查询必须自带 {@code not exists}
 *       （按 {@code access-expiry:<申请id>:<档名>} 去重），不能靠「先查一遍再插」；
 *       「跑两遍不重复发」由 e2e 脚本在现网验——mock 证明不了 SQL 语义；</li>
 *   <li><b>不积累</b>：授权到期或被回收时，未关闭的提醒事件跟着关掉。</li>
 * </ol>
 *
 * <p>用记录型假 JdbcTemplate 而不是 Mockito 匹配器：这条链路上全是
 * {@code queryForList(sql, Object...)} 变参，要断言的正是「第几次调用的第几个参数」，
 * 匹配器写出来既脆又读不懂。</p>
 */
class AccessReminderTest {

    /** 记录所有 SQL 调用；候选行 / 到期行按场景喂。 */
    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        List<Map<String, Object>> candidates = List.of();
        List<Map<String, Object>> expired = List.of();

        private void record(String sql, Object... rest) {
            sqls.add(sql);
            args.add(rest);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... rest) {
            return route(sql, rest);
        }

        /**
         * {@code JdbcTemplate} 还有一个不带参数的 {@code queryForList(String)} 重载：
         * {@code expire()} 里的 update...returning 没有占位参数，编译期绑到的是它，
         * 只覆盖变参版本会漏过去并撞上「No DataSource set」。
         */
        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            return route(sql);
        }

        private List<Map<String, Object>> route(String sql, Object... rest) {
            record(sql, rest);
            if (sql.contains("update platform.access_request")) {
                return expired;                       // 老写法：update ... returning
            }
            if (sql.contains("from platform.access_request a")) {
                return candidates;                    // 提醒候选（带别名 a）
            }
            // 到期候选（expire()，2026-10-27 修正）：这条 select 没有别名。
            // 之前只喂了「update ... returning」那条路，而 expire() 早就是
            // 「先 select 候选 → 逐条 update」，于是候选恒为空、用例只是**空检查**
            // （断言永远不可能红）。补上这条路由，用例才有牙。
            if (sql.contains("from platform.access_request")) {
                return expired;
            }
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... rest) {
            record(sql, rest);
            return null;
        }

        @Override
        public int update(String sql, Object... rest) {
            record(sql, rest);
            return 1;
        }

        /** 第 idx 次命中 needle 的调用的第 which 个参数（不含 sql）。 */
        Object argOf(String needle, int idx, int which) {
            int seen = 0;
            for (int i = 0; i < sqls.size(); i++) {
                if (sqls.get(i).contains(needle) && seen++ == idx) {
                    return args.get(i)[which];
                }
            }
            throw new AssertionError("没找到第 " + (idx + 1) + " 条包含「" + needle
                    + "」的 SQL；实际记录：" + sqls);
        }

        int count(String needle) {
            int n = 0;
            for (String s : sqls) {
                if (s.contains(needle)) {
                    n++;
                }
            }
            return n;
        }
    }

    private final FakeJdbc jdbc = new FakeJdbc();
    private final SessionService sessions = mock(SessionService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final AccessExpiryJob job = new AccessExpiryJob(jdbc, sessions, tx);

    private AccessReminderTest() {
        // 测试里没有真实事务管理器：让 TransactionTemplate 直接把回调跑一遍
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    private static Map<String, Object> candidate(long id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("requester", "alice");
        row.put("approver", "bob");
        row.put("resourceType", "TABLE");
        row.put("catalog", "doris_prod");
        row.put("schemaName", "demo_retail");
        row.put("tableName", "ods_order");
        row.put("columnName", null);
        row.put("permission", "READ");
        row.put("expiresAt", "2026-11-02 00:00:00+08");
        row.put("remaining", "6 days 03:12:41");
        return row;
    }

    // ---------- 去重键与资源文本 ----------

    @Test
    void noticeKeyKeepsWindowsSeparatePerGrant() {
        assertEquals("access-expiry:42:7d", AccessExpiryJob.noticeKey(42, 7));
        assertEquals("access-expiry:42:1d", AccessExpiryJob.noticeKey(42, 1));
        assertEquals("7d", AccessExpiryJob.noticeSuffix(7));

        // 三种粒度都要拼出完整资源名，提醒里不能只说「某个资源」
        assertEquals("doris_prod.demo_retail.ods_order",
                AccessExpiryJob.resourceText(candidate(1)));
        Map<String, Object> db = candidate(2);
        db.put("resourceType", "DATABASE");
        assertEquals("doris_prod.demo_retail", AccessExpiryJob.resourceText(db));
        Map<String, Object> col = candidate(3);
        col.put("resourceType", "COLUMN");
        col.put("columnName", "phone");
        assertEquals("doris_prod.demo_retail.ods_order.phone", AccessExpiryJob.resourceText(col));
    }

    // ---------- 发提醒 ----------

    @Test
    void oneNoticePerWindowCarriesReviewableEvidence() {
        jdbc.candidates = List.of(candidate(42));

        Map<String, Object> out = job.notifyExpiring(new int[]{7}, "ops_admin");

        assertEquals(Map.of("7d", 1), out.get("windows"));
        assertEquals(1, out.get("total"));

        // alert_event 的三个占位就是 (title, detail, event_key)
        String title = String.valueOf(jdbc.argOf("insert into platform.alert_event", 0, 0));
        String detail = String.valueOf(jdbc.argOf("insert into platform.alert_event", 0, 1));
        assertEquals("access-expiry:42:7d", jdbc.argOf("insert into platform.alert_event", 0, 2));
        assertTrue(title.contains("alice"), title);
        for (String must : List.of("申请人=alice", "审批人=bob", "demo_retail.ods_order",
                "到期时间 2026-11-02", "剩余 6 days", "续期")) {
            assertTrue(detail.contains(must), "提醒缺少「" + must + "」：" + detail);
        }
        // 提醒记录同时落在申请单事件上，列表里能看到「已提醒过」
        assertEquals(42L, ((Number) jdbc.argOf("insert into platform.access_request_event",
                0, 0)).longValue());
        assertEquals("NOTICE_7d", jdbc.argOf("insert into platform.access_request_event", 0, 1));
        assertEquals("ops_admin", jdbc.argOf("insert into platform.access_request_event", 0, 2));
    }

    /** 幂等靠 SQL 里的 not exists，不是靠「查一遍再插」：条件必须在候选查询里。 */
    @Test
    void dedupeIsDoneInsideTheCandidateQuery() {
        jdbc.candidates = List.of();

        job.notifyExpiring(new int[]{7}, "SYSTEM");

        String used = jdbc.sqls.stream()
                .filter(s -> s.contains("from platform.access_request a"))
                .findFirst().orElse("");
        assertTrue(used.contains("not exists"), "候选查询必须自带去重条件：" + used);
        assertTrue(used.contains("e.event_key = 'access-expiry:' || a.id"), used);
        assertTrue(used.contains("a.status = 'APPROVED'"), used);
        assertTrue(used.contains("coalesce(a.expires_at, a.decided_at + interval '90 days') > now()"),
                "已过期的不该再提醒：" + used);
        assertEquals(0, jdbc.count("insert into platform.alert_event"));
    }

    @Test
    void eachWindowScansOnceWithItsOwnDays() {
        jdbc.candidates = List.of();

        Map<String, Object> out = job.notifyExpiring(new int[]{7, 1}, "SYSTEM");

        assertEquals(0, out.get("total"));
        assertEquals(2, jdbc.count("from platform.access_request a"));
        assertEquals(7, jdbc.argOf("from platform.access_request a", 0, 0));
        assertEquals(1, jdbc.argOf("from platform.access_request a", 1, 0));
        assertEquals("7d", jdbc.argOf("from platform.access_request a", 0, 1));
        assertEquals("1d", jdbc.argOf("from platform.access_request a", 1, 1));
    }

    /** 非法提前量不查询也不发提醒（手动触发口子挡住乱填）。 */
    @Test
    void nonPositiveWindowIsSkipped() {
        Map<String, Object> out = job.notifyExpiring(new int[]{0, -3}, "SYSTEM");

        assertEquals(0, out.get("total"));
        assertEquals(0, jdbc.count("from platform.access_request a"));
    }

    /** 定时轮与运维手动触发可能同时跑：批次必须先拿咨询锁再扫描。 */
    @Test
    void batchTakesAdvisoryLockBeforeScanning() {
        jdbc.candidates = List.of();

        job.notifyExpiring(new int[]{7}, "SYSTEM");

        int lock = -1;
        int scan = -1;
        for (int i = 0; i < jdbc.sqls.size(); i++) {
            if (lock < 0 && jdbc.sqls.get(i).contains("pg_advisory_xact_lock")) {
                lock = i;
            }
            if (scan < 0 && jdbc.sqls.get(i).contains("from platform.access_request a")) {
                scan = i;
            }
        }
        assertTrue(lock >= 0, "没有取咨询锁：" + jdbc.sqls);
        assertTrue(lock < scan, "锁必须在扫描之前（lock=" + lock + "，scan=" + scan + "）");
    }

    // ---------- 到期与回收要把提醒收掉 ----------

    @Test
    void expiredGrantsCloseTheirOpenNotices() {
        jdbc.expired = List.of(Map.of("id", 42L, "requester", "alice", "expires_at", "2026-10-26"));

        job.expire();

        verify(sessions).audit(eq("alice"), eq("ACCESS_REQUEST_EXPIRE"), eq("42"),
                anyString(), eq("SUCCESS"), any());
        assertEquals(1, jdbc.count("update platform.alert_event"));
        assertEquals("access-expiry-job", jdbc.argOf("update platform.alert_event", 0, 0));
        assertEquals("access-expiry:42:%", jdbc.argOf("update platform.alert_event", 0, 1));
    }

    @Test
    void nothingToExpireWritesNoAuditAndNoClose() {
        jdbc.expired = List.of();

        job.expire();

        assertEquals(0, jdbc.count("update platform.alert_event"));
        verify(sessions, never()).audit(anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void closeNoticesTargetsOnlyThatGrant() {
        AccessExpiryJob.closeNotices(jdbc, 42, "ops_admin");

        assertEquals("ops_admin", jdbc.argOf("update platform.alert_event", 0, 0));
        assertEquals("access-expiry:42:%", jdbc.argOf("update platform.alert_event", 0, 1));
        String sql = jdbc.sqls.get(jdbc.sqls.indexOf(
                jdbc.sqls.stream().filter(s -> s.contains("update platform.alert_event"))
                        .findFirst().orElseThrow()));
        assertTrue(sql.contains("status = 'OPEN'"), "只关未关闭的：" + sql);
        assertTrue(sql.contains("rule_type = 'ACCESS_EXPIRY'"), "不能误关别的告警：" + sql);
    }
}
