package com.lab.dataworks.access;

import com.lab.dataworks.auth.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * D52 离岗回收：成员被移出租户时，他在这个租户里的生效授权必须跟着收回，且逐条留痕。
 *
 * <p>判定的事实依据：{@code DataGuardService.grantsFor(user, tenant)} 只看
 * {@code requester + requester_tenant + status='APPROVED' + 未到期}，
 * <b>不复查这个人当前还在不在该租户</b>。所以只删 {@code tenant_member} 是不够的，
 * 必须把授权一起置 REVOKED。</p>
 *
 * <p>钉住四件事：</p>
 * <ol>
 *   <li>回收范围只限「这个人 + 这个租户 + APPROVED」，别的租户与审批中的单子不能动；</li>
 *   <li>每条回收都写 {@code ACCESS_AUTO_REVOKE} 审计（含原因）+ 申请单事件 + 关掉未到期提醒；</li>
 *   <li>没有可回收的授权时不写任何审计（不制造噪音记录）；</li>
 *   <li>留痕失败必须抛出（调用方在事务里整体回滚），不允许「status 改了但审计缺失」。</li>
 * </ol>
 */
class AccessRevokeServiceTest {

    /** 与 AccessReminderTest 同一套路：记录 SQL 与参数，按片段喂返回行。 */
    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        List<Map<String, Object>> revoked = List.of();

        private void record(String sql, Object... rest) {
            sqls.add(sql);
            args.add(rest);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... rest) {
            record(sql, rest);
            return sql.contains("update platform.access_request") ? revoked : List.of();
        }

        @Override
        public int update(String sql, Object... rest) {
            record(sql, rest);
            return 1;
        }

        Object argOf(String needle, int idx, int which) {
            int seen = 0;
            for (int i = 0; i < sqls.size(); i++) {
                if (sqls.get(i).contains(needle) && seen++ == idx) {
                    return args.get(i)[which];
                }
            }
            throw new AssertionError("没找到第 " + (idx + 1) + " 条包含「" + needle + "」的 SQL：" + sqls);
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
    private final AccessRevokeService service = new AccessRevokeService(jdbc, sessions);

    private static Map<String, Object> grant(long id, String table) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("resourceType", "TABLE");
        row.put("catalog", "doris_prod");
        row.put("schemaName", "demo_retail");
        row.put("tableName", table);
        row.put("columnName", null);
        row.put("permission", "READ");
        row.put("expires_at", "2026-12-31 00:00:00+08");
        return row;
    }

    @Test
    void revokesOnlyThisUsersGrantsInThisTenant() {
        jdbc.revoked = List.of(grant(11, "ods_order"), grant(12, "ods_user"));

        int n = service.revokeForTenant("alice", "tenant_a", "ops_admin", "移出租户 tenant_a");

        assertEquals(2, n);
        String sql = jdbc.sqls.get(0);
        assertTrue(sql.contains("requester = ?"), sql);
        assertTrue(sql.contains("requester_tenant = ?"),
                "必须按租户收口，否则会把别的租户授权一起收掉：" + sql);
        assertTrue(sql.contains("status = 'APPROVED'"),
                "审批中的单子由审批人判定，系统不代人驳回：" + sql);
        assertTrue(sql.contains("set status = 'REVOKED', revoked_by = ?"), sql);
        // 参数顺序：revoked_by, requester, tenant
        assertEquals("ops_admin", jdbc.argOf("update platform.access_request", 0, 0));
        assertEquals("alice", jdbc.argOf("update platform.access_request", 0, 1));
        assertEquals("tenant_a", jdbc.argOf("update platform.access_request", 0, 2));
    }

    @Test
    void eachRevokedGrantLeavesEventAuditAndClosesNotices() {
        jdbc.revoked = List.of(grant(11, "ods_order"), grant(12, "ods_user"));

        service.revokeForTenant("alice", "tenant_a", "ops_admin", "移出租户 tenant_a");

        assertEquals(2, jdbc.count("insert into platform.access_request_event"));
        assertEquals(2, jdbc.count("update platform.alert_event"));
        verify(sessions, times(2)).audit(eq("ops_admin"),
                eq(AccessRevokeService.AUTO_REVOKE_ACTION), anyString(), anyString(),
                eq("SUCCESS"), any());
        verify(sessions).audit(eq("ops_admin"), eq("ACCESS_AUTO_REVOKE"), eq("11"),
                org.mockito.ArgumentMatchers.argThat(detail ->
                        detail.contains("被回收人=alice")
                                && detail.contains("移出租户 tenant_a")
                                && detail.contains("demo_retail.ods_order")
                                && detail.contains("原到期时间 2026-12-31")),
                eq("SUCCESS"), any());
        // 事件与状态变更同事务：动作名 AUTO_REVOKE，执行人落进去
        assertEquals("AUTO_REVOKE", jdbc.argOf("insert into platform.access_request_event", 0, 1));
        assertEquals("ops_admin", jdbc.argOf("insert into platform.access_request_event", 0, 2));
        assertEquals("access-expiry:11:%", jdbc.argOf("update platform.alert_event", 0, 1));
    }

    @Test
    void nothingToRevokeWritesNothing() {
        jdbc.revoked = List.of();

        assertEquals(0, service.revokeForTenant("alice", "tenant_a", "ops_admin", "移出租户 tenant_a"));

        verify(sessions, never()).audit(anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
        assertEquals(0, jdbc.count("insert into platform.access_request_event"));
        assertEquals(0, jdbc.count("update platform.alert_event"));
    }

    /** 缺参数时连库都不碰：避免「tenant 传空」退化成回收该用户全部授权。 */
    @Test
    void blankArgumentsDoNotTouchDatabase() {
        assertEquals(0, service.revokeForTenant(" ", "tenant_a", "ops_admin", "原因"));
        assertEquals(0, service.revokeForTenant("alice", null, "ops_admin", "原因"));
        assertTrue(jdbc.sqls.isEmpty(), "不该发出任何 SQL：" + jdbc.sqls);
    }

    /** 审计写失败要抛出（调用方在事务里整体回滚），不能静默继续回收下一条。 */
    @Test
    void auditFailurePropagatesInsteadOfBeingSwallowed() {
        jdbc.revoked = List.of(grant(11, "ods_order"), grant(12, "ods_user"));
        doThrow(new RuntimeException("审计库不可用")).when(sessions).audit(anyString(), anyString(),
                eq("11"), anyString(), anyString(), any());

        assertThrows(RuntimeException.class,
                () -> service.revokeForTenant("alice", "tenant_a", "ops_admin", "移出租户 tenant_a"));

        // 第一条就炸，第二条不会被无声处理
        assertEquals(1, jdbc.count("insert into platform.access_request_event"));
    }

    /** 没给执行人时落 SYSTEM，与 UserService 的级联回收同一口径。 */
    @Test
    void nullActorBecomesSystem() {
        jdbc.revoked = List.of(grant(11, "ods_order"));

        service.revokeForTenant("alice", "tenant_a", null, "租户被删除");

        assertEquals("SYSTEM", jdbc.argOf("insert into platform.access_request_event", 0, 2));
        verify(sessions).audit(eq("system"), eq("ACCESS_AUTO_REVOKE"), eq("11"),
                org.mockito.ArgumentMatchers.argThat(detail -> detail.contains("租户被删除")),
                eq("SUCCESS"), any());
    }
}
