package com.lab.dataworks.change;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D31 回归：变更单的撤回（提交人）与作废（审批账号）两条出口。
 *
 * <p>症状（修复前）：admin 自己提交的单 approve/reject 撞「不能审批自己」403、
 * cancel 一律 400、DELETE 无映射默认拒绝 403 → 单管理员租户里单据永久 PENDING，
 * 状态机死单。卡内验收：提交→撤回→CANCELLED；撤他人单 403；已审批单不可撤 409；
 * 不存在/跨租户 404。</p>
 *
 * <p>{@link FakeChangeTable} 按 SQL 文本里的 where 谓词决定命中范围：
 * 撤掉 {@code cancel} 的 {@code tenant_id} 条件、403/409 判定、或 updated==0
 * 的并发复核，对应断言必红（同 S11-3 玩法）。</p>
 */
class ChangeCancelVoidTest {

    private static final String TENANT_A = "tenant_a";
    private static final String TENANT_B = "tenant_b";
    private static final String ALICE = "alice";   // tenant_a 提交人（也是唯一审批人）
    private static final String BOB = "bob";       // tenant_a 其他用户
    private static final String CAROL = "carol";   // tenant_b 同名不同人场景外的提交人

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final FakeChangeTable table = new FakeChangeTable();
    private ChangeService changes;

    /** true 时 UPDATE 一律 0 行命中：模拟 require 与 update 之间被并发审批抢改。 */
    private boolean simulateRaceLost = false;

    @BeforeEach
    void setUp() {
        TenantScope.set(TENANT_A);
        changes = new ChangeService(jdbc, sessions, mock(ChangeExecutor.class),
                mock(TenantGuard.class));

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            Object[] args = inv.getArguments();
            if (!sql.contains("from platform.change_request")) {
                return List.of();
            }
            long id = ((Number) args[1]).longValue();
            String tenant = String.valueOf(args[2]);
            if (sql.contains("reviewComment")) {   // VIEW：驼峰列名
                return table.viewRows(id, tenant);
            }
            return table.rawRows(id, tenant);      // select *：snake_case
        });

        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (!sql.toLowerCase().contains("update platform.change_request")) {
                return 0;
            }
            if (simulateRaceLost) {
                return 0;
            }
            Object[] args = inv.getArguments();
            // 按 where 谓词出现顺序消费绑定参数：红探针回退到旧实现时参数形状不同
            // （没有 tenant_id 绑定），按固定位置取参会把语义红变成越界噪音。
            int idx = 1;
            long id = -1;
            String tenant = null;
            String requester = null;
            String where = sql.substring(sql.indexOf("where"));
            for (String pred : where.split(" and ")) {
                if (!pred.contains("?")) {
                    continue;
                }
                Object value = idx < args.length ? args[idx++] : null;
                if (pred.contains("tenant_id")) {          // 先判 tenant_id：它也含 "id = ?"
                    tenant = String.valueOf(value);
                } else if (pred.contains("requester")) {
                    requester = String.valueOf(value);
                } else if (pred.contains("id = ?") && value instanceof Number n) {
                    id = n.longValue();
                }
            }
            return table.applyCancel(sql, id, tenant, requester);
        });
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    // ---------------------------------------------------------------- 撤回

    @Test
    void cancel_ownPending_becomesCancelled() {
        long id = table.insert(TENANT_A, ALICE, "PENDING");

        Map<String, Object> out = changes.cancel(id, ALICE);

        assertEquals("CANCELLED", out.get("status"), "提交人撤回自己的 PENDING 单 → CANCELLED");
        assertEquals("提交人撤回", out.get("reviewComment"));
        table.assertStatus(id, "CANCELLED");
        // 撤修复必红：撤回的 UPDATE 必须带租户条件（v447 by-id 口径，本类 javadoc 约定）
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(Object[].class));
        assertTrue(sqlCap.getValue().contains("tenant_id = ?"),
                "D31：cancel 的 UPDATE 丢了 tenant_id 条件——多租户同名账号可跨租户撤单");
    }

    @Test
    void cancel_otherUsersOrder_is403() {
        long id = table.insert(TENANT_A, BOB, "PENDING");

        ApiException e = assertThrows(ApiException.class, () -> changes.cancel(id, ALICE));
        assertEquals(403, e.getStatus(), "撤他人单必须是 403，不是一律 400");
        assertTrue(e.getMessage().contains("他人"), "文案要指明不能撤他人单");
        table.assertStatus(id, "PENDING");
    }

    @Test
    void cancel_approvedOrder_is409() {
        long id = table.insert(TENANT_A, ALICE, "APPROVED");

        ApiException e = assertThrows(ApiException.class, () -> changes.cancel(id, ALICE));
        assertEquals(409, e.getStatus(), "已审批（非 PENDING）的单不可撤，409 不是 400");
        table.assertStatus(id, "APPROVED");
    }

    @Test
    void cancel_missingOrCrossTenant_is404() {
        long foreign = table.insert(TENANT_B, ALICE, "PENDING");

        // 用户名相同不构成撤回依据：跨租户按 404（与本模块 require 口径一致，不泄露存在性）
        ApiException e = assertThrows(ApiException.class, () -> changes.cancel(foreign, ALICE));
        assertEquals(404, e.getStatus());
        table.assertStatus(foreign, "PENDING");

        ApiException missing = assertThrows(ApiException.class,
                () -> changes.cancel(99999L, ALICE));
        assertEquals(404, missing.getStatus());
    }

    @Test
    void cancel_racedByApprove_is409NotFakeSuccess() {
        long id = table.insert(TENANT_A, ALICE, "PENDING");
        simulateRaceLost = true;   // require 之后、update 之前被并发审批抢改

        ApiException e = assertThrows(ApiException.class, () -> changes.cancel(id, ALICE));
        assertEquals(409, e.getStatus(),
                "updated==0 必须报错；若仍走旧 Map.of(\"ok\",true) 返回就是假成功");
        table.assertStatus(id, "PENDING");
    }

    @Test
    void cancel_returnsFullView_notOpaqueOk() {
        long id = table.insert(TENANT_A, ALICE, "PENDING");

        Map<String, Object> out = changes.cancel(id, ALICE);

        // 前端列表按 status/reviewComment 渲染：返回体要带完整单据，不是 {ok:true}
        assertEquals("CANCELLED", out.get("status"));
        assertTrue(out.containsKey("requester"));
        assertTrue(out.containsKey("sqlText"));
    }

    // ---------------------------------------------------------------- 作废

    @Test
    void voidChange_approverCancelsPending_includingOwnOrder() {
        long id = table.insert(TENANT_A, ALICE, "PENDING");

        // 单管理员场景：唯一有 change:approve 的就是提交人自己——作废不受 self-guard 限制
        Map<String, Object> out = changes.voidChange(id, ALICE, TenantScope.current());

        assertEquals("CANCELLED", out.get("status"));
        assertEquals("已作废", out.get("reviewComment"));
        table.assertStatus(id, "CANCELLED");
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(Object[].class));
        assertTrue(sqlCap.getValue().contains("tenant_id = ?"),
                "作废的 UPDATE 同样必须带租户条件");
    }

    @Test
    void voidChange_crossTenantOrMissing_is404() {
        long foreign = table.insert(TENANT_B, CAROL, "PENDING");

        ApiException e = assertThrows(ApiException.class,
                () -> changes.voidChange(foreign, ALICE, TENANT_A));
        assertEquals(404, e.getStatus(), "作废也只能处理本租户的单，跨租户 404 不泄露存在性");
        table.assertStatus(foreign, "PENDING");

        ApiException missing = assertThrows(ApiException.class,
                () -> changes.voidChange(99999L, ALICE, TENANT_A));
        assertEquals(404, missing.getStatus());
    }

    @Test
    void voidChange_nonPending_is409() {
        long id = table.insert(TENANT_A, BOB, "DONE");

        ApiException e = assertThrows(ApiException.class,
                () -> changes.voidChange(id, ALICE, TENANT_A));
        assertEquals(409, e.getStatus());
        table.assertStatus(id, "DONE");
    }

    @Test
    void voidChange_deniedAttempt_auditedByCancel() {
        long id = table.insert(TENANT_A, BOB, "PENDING");

        assertThrows(ApiException.class, () -> changes.cancel(id, ALICE));
        verify(sessions).audit(org.mockito.ArgumentMatchers.eq(ALICE),
                org.mockito.ArgumentMatchers.eq("CHANGE_CANCEL_DENIED"),
                org.mockito.ArgumentMatchers.eq("c" + id),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("FAILED"),
                org.mockito.ArgumentMatchers.isNull());
    }

    // ---------------------------------------------------------------- 假表

    /**
     * platform.change_request 的内存替身。UPDATE 是否命中完全取决于被测 SQL 文本里
     * 写了哪些谓词（tenant_id / requester / status='PENDING'），所以代码里删掉哪个
     * 条件，这里就表现出哪个越权/误改行为。
     */
    private static final class FakeChangeTable {
        private final List<Row> rows = new ArrayList<>();
        private long seq = 1;

        long insert(String tenant, String requester, String status) {
            Row r = new Row(seq++, tenant, requester, status,
                    "DROP TABLE t_report", null);
            rows.add(r);
            return r.id;
        }

        /** require()：select *，snake_case；租户条件在 SQL 文本里。 */
        List<Map<String, Object>> rawRows(long id, String tenant) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Row r : rows) {
                if (r.id != id || !r.tenant.equals(tenant)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id);
                m.put("tenant_id", r.tenant);
                m.put("requester", r.requester);
                m.put("status", r.status);
                m.put("review_comment", r.comment);
                out.add(m);
            }
            return out;
        }

        /** view()：驼峰列名；SQL 里同样带 tenant_id 条件。 */
        List<Map<String, Object>> viewRows(long id, String tenant) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Row r : rows) {
                if (r.id != id || !r.tenant.equals(tenant)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id);
                m.put("tenantId", r.tenant);
                m.put("requester", r.requester);
                m.put("status", r.status);
                m.put("reviewComment", r.comment);
                m.put("sqlText", r.sqlText);
                out.add(m);
            }
            return out;
        }

        int applyCancel(String sql, long id, String tenant, String requester) {
            int n = 0;
            for (Row r : rows) {
                if (r.id != id) {
                    continue;
                }
                // 按 SQL 文本模拟 where 语义：谓词没写 = 数据库也拦不住
                // （tenant 为 null 表示 UPDATE 根本没有租户条件，跨租户行照样命中）
                if (tenant != null && !r.tenant.equals(tenant)) {
                    continue;
                }
                if (requester != null && !r.requester.equals(requester)) {
                    continue;
                }
                if (sql.contains("status = 'PENDING'") && !"PENDING".equals(r.status)) {
                    continue;
                }
                r.status = "CANCELLED";
                if (r.comment == null) {
                    r.comment = sql.contains("'提交人撤回'") ? "提交人撤回" : "已作废";
                }
                n++;
            }
            return n;
        }

        void assertStatus(long id, String expected) {
            Row r = rows.stream().filter(x -> x.id == id).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(expected, r.status,
                    "单据 id=" + id + " 状态不应被越权/越状态改动");
        }
    }

    private static final class Row {
        final long id;
        final String tenant;
        final String requester;
        final String sqlText;
        String status;
        String comment;

        Row(long id, String tenant, String requester, String status, String sqlText,
            String comment) {
            this.id = id;
            this.tenant = tenant;
            this.requester = requester;
            this.status = status;
            this.sqlText = sqlText;
            this.comment = comment;
        }
    }
}
