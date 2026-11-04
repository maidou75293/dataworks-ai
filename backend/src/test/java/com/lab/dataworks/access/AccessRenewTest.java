package com.lab.dataworks.access;

import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D52 续期：口径与安全边界。
 *
 * <p>续期<em>不是</em>「直接延长 expires_at」——那等于开一条绕过审批人的授权通道，
 * L4 双人闸门也会跟着失效。这里是「对同一资源再接一张新单走原审批链」，
 * 因此必须钉住：</p>
 * <ol>
 *   <li>只有申请人本人能发起（代提会让审计里的「谁申请的」失真）；</li>
 *   <li>只有「已通过 / 已过期」能续；被回收的要重新申请并说明用途（不能静默绕过回收判定）；</li>
 *   <li>资源还得在纳管清单里，否则 404（续一条已经不存在的表＝让审批人白批）；</li>
 *   <li>新单的 reason 必须写明「续期自 #旧单」，旧单追加 RENEW_REQUEST 事件，
 *       审计落在新单 id 上 —— 事后能从任一端追到另一端；</li>
 *   <li>续期<em>不</em>检查「已有生效授权」（那条正是要接上的），新建才检查；
 *       同参 PENDING 仍然去重复用，不叠第二条。</li>
 * </ol>
 */
class AccessRenewTest {

    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        /** 按 id 返回申请单行；测试自己塞。 */
        Map<String, Object> requestRow = new LinkedHashMap<>();
        /** 已纳管资源条数（0 表示资源已不存在）。 */
        int managed = 1;
        /** 命中去重的 PENDING 单；空表示没有。 */
        List<Long> pending = List.of();
        long nextId = 101;

        private void record(String sql, Object... rest) {
            sqls.add(sql);
            args.add(rest);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... rest) {
            record(sql, rest);
            if (sql.contains("from platform.access_request ar")) {
                if (requestRow == null) {
                    return List.of();
                }
                // 控制器会为「旧单」和「新单」各查一次同一形状的 SQL；
                // 返回行里的 id 必须跟着查询参数走，否则断言会拿到错的 id
                Map<String, Object> copy = new LinkedHashMap<>(requestRow);
                if (rest.length > 0 && rest[0] != null) {
                    copy.put("id", Long.parseLong(String.valueOf(rest[0])));
                }
                return List.of(copy);
            }
            return List.of();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<T> queryForList(String sql, Class<T> type, Object... rest) {
            record(sql, rest);
            return (List<T>) pending;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... rest) {
            record(sql, rest);
            if (sql.contains("pg_advisory_xact_lock")) {
                return null;
            }
            if (sql.contains("from platform.managed_table")) {
                return type.equals(Integer.class) ? type.cast(managed) : null;
            }
            if (sql.contains("insert into platform.access_request")) {
                return type.equals(Long.class) ? type.cast(nextId) : null;
            }
            if (sql.contains("select count(*) from platform.access_request")) {
                return type.equals(Integer.class) ? type.cast(0) : null;
            }
            return null;
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

        boolean ran(String needle) {
            return sqls.stream().anyMatch(s -> s.contains(needle));
        }
    }

    private final FakeJdbc jdbc = new FakeJdbc();
    private final SessionService sessions = mock(SessionService.class);
    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final AccessExpiryJob job = mock(AccessExpiryJob.class);
    private final AccessEscalationJob escalation = mock(AccessEscalationJob.class);
    private AccessController controller;

    @BeforeEach
    void setUp() {
        controller = new AccessController(jdbc, sessions, permissions, tx, job, escalation);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        when(permissions.atoms(anyString(), anyString())).thenReturn(Set.of("access:request"));
        TenantScope.set("default");
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    private static MockHttpServletRequest as(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/access/requests/9/renew");
        request.setAttribute("username", username);
        return request;
    }

    private Map<String, Object> approvedRow(String requester) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9L);
        row.put("requester", requester);
        row.put("requesterTenant", "default");
        row.put("resourceType", "TABLE");
        row.put("catalog", "doris_prod");
        row.put("schemaName", "demo_retail");
        row.put("tableName", "ods_order");
        row.put("columnName", null);
        row.put("permission", "READ");
        row.put("status", "APPROVED");
        row.put("approver", "bob");
        row.put("expiresAt", "2026-11-02 00:00:00+08");
        return row;
    }

    @Test
    void applicantCanRenewAndBothSidesAreTraced() {
        jdbc.requestRow = approvedRow("alice");

        Map<String, Object> out = controller.renew(9L, null, as("alice"));

        assertEquals(9L, out.get("renewOf"));
        assertEquals(101L, out.get("id"));
        // 新单的 reason 必须写清续期来源与原到期时间
        // 列序：requester, requester_tenant, resource_type, catalog, schema_name,
        //       table_name, column_name, folder_id, permission, reason（V110 加了 folder_id）
        String reason = String.valueOf(jdbc.argOf("insert into platform.access_request", 0, 9));
        assertTrue(reason.contains("续期自 #9"), reason);
        assertTrue(reason.contains("2026-11-02"), reason);
        // requester/requester_tenant 沿用原单（申请人本人、原租户），不是当前会话凑出来的
        assertEquals("alice", jdbc.argOf("insert into platform.access_request", 0, 0));
        assertEquals("default", jdbc.argOf("insert into platform.access_request", 0, 1));
        assertEquals("ods_order", jdbc.argOf("insert into platform.access_request", 0, 5));
        assertEquals("READ", jdbc.argOf("insert into platform.access_request", 0, 8));
        assertEquals(null, jdbc.argOf("insert into platform.access_request", 0, 7));
        // 旧单追加事件 + 审计落在新单 id 上，两端都能追
        verify(sessions).audit(eq("alice"), eq("ACCESS_REQUEST_RENEW"), eq("101"),
                org.mockito.ArgumentMatchers.argThat(text -> text != null && text.contains("续期自 #9")));
        assertEquals("RENEW_REQUEST", jdbc.argOf("insert into platform.access_request_event", 0, 1));
        assertEquals(9L, jdbc.argOf("insert into platform.access_request_event", 0, 0));
    }

    /** 续期不能挡「已有生效授权」：那条正是要接上的对象；新建才会挡。 */
    @Test
    void renewDoesNotRunTheAlreadyGrantedGuard() {
        jdbc.requestRow = approvedRow("alice");

        controller.renew(9L, null, as("alice"));

        // 新建时那条「你已经有该资源的权限」查重（带 permission='WRITE' 兜判）不能被续期触发
        assertTrue(!jdbc.ran("or permission = 'WRITE'"),
                "续期不该跑「已有生效授权」查重：" + jdbc.sqls);
    }

    @Test
    void onlyTheApplicantMayRenew() {
        jdbc.requestRow = approvedRow("alice");

        ApiException e = assertThrows(ApiException.class,
                () -> controller.renew(9L, null, as("mallory")));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("申请人本人"), e.getMessage());
        assertTrue(!jdbc.ran("returning id"), "被拒之后不该已经建了新单：" + jdbc.sqls);
    }

    @Test
    void revokedOrPendingGrantsCannotBeRenewed() {
        for (String status : List.of("REVOKED", "PENDING", "REJECTED", "CANCELLED")) {
            Map<String, Object> row = approvedRow("alice");
            row.put("status", status);
            jdbc.requestRow = row;

            ApiException e = assertThrows(ApiException.class,
                    () -> controller.renew(9L, null, as("alice")), status + " 不能续期");
            assertEquals(400, e.getStatus(), status);
            assertTrue(e.getMessage().contains("已通过"), e.getMessage());
        }
    }

    /** 已过期也允许续：否则「过期才发现没人续」会变成必须重新填一遍申请。 */
    @Test
    void expiredGrantCanStillBeRenewed() {
        Map<String, Object> row = approvedRow("alice");
        row.put("status", "EXPIRED");
        jdbc.requestRow = row;

        assertEquals(101L, controller.renew(9L, null, as("alice")).get("id"));
    }

    @Test
    void missingResourceIs404() {
        jdbc.requestRow = approvedRow("alice");
        jdbc.managed = 0;

        ApiException e = assertThrows(ApiException.class,
                () -> controller.renew(9L, null, as("alice")));

        assertEquals(404, e.getStatus());
        assertTrue(e.getMessage().contains("无法续期"), e.getMessage());
        assertTrue(!jdbc.ran("returning id"), "资源都没了不应该建新单：" + jdbc.sqls);
    }

    /** 同参已有审批中的续期单：复用那一行，不叠第二条（与新建同一去重口径）。 */
    @Test
    void existingPendingRenewIsReusedNotDuplicated() {
        jdbc.requestRow = approvedRow("alice");
        jdbc.pending = List.of(77L);

        Map<String, Object> out = controller.renew(9L, null, as("alice"));

        assertEquals(77L, out.get("id"));
        assertEquals(true, out.get("reused"));
        assertTrue(!jdbc.ran("returning id"), "命中去重时不该再建一条：" + jdbc.sqls);
        String eventDetail = String.valueOf(jdbc.argOf("insert into platform.access_request_event", 0, 3));
        assertTrue(eventDetail.contains("复用审批中的那一单"), eventDetail);
    }

    /** 续期时带上说明，要进新单 reason，审批人得能看到为什么续。 */
    @Test
    void commentGoesIntoTheNewRequestReason() {
        jdbc.requestRow = approvedRow("alice");

        controller.renew(9L, new AccessController.Decision("  月度对账还要用  "), as("alice"));

        String reason = String.valueOf(jdbc.argOf("insert into platform.access_request", 0, 9));
        assertTrue(reason.contains("说明：月度对账还要用"), reason);
    }

    /**
     * D61：续期的理由是<b>拼出来的</b>（续期自 #id + 原审批意见），而历史审批意见本身
     * 没有上限（本次不迁历史数据）。落库前必须仍在 500 字内，且截断要留可见标记 ——
     * 既不许静默写一条超限记录，也不许因为旧数据把用户的续期整个挡死（那会是新回归）。
     */
    @Test
    void overlongHistoricalCommentStillFitsTheReasonLimit() {
        jdbc.requestRow = approvedRow("alice");

        controller.renew(9L, new AccessController.Decision("长".repeat(5000)), as("alice"));

        String reason = String.valueOf(jdbc.argOf("insert into platform.access_request", 0, 9));
        assertTrue(AccessController.reasonLength(reason) <= AccessController.MAX_REASON_LENGTH,
                "拼接后仍超限（" + AccessController.reasonLength(reason) + " 字）: " + reason);
        assertTrue(reason.contains("续期自 #9"), "续期来源不许被截掉: " + reason);
        assertTrue(reason.contains("说明超长已截断"), "截断要可见: " + reason);
    }

    /** D61 兜底：申请落库的唯一写入口带限长，绕过 create 的调用方也写不进超限理由。 */
    @Test
    void unboundedReasonNeverReachesTheInsert() {
        jdbc.requestRow = approvedRow("alice");
        jdbc.pending = List.of();

        ApiException e = assertThrows(ApiException.class, () -> controller.create(
                new AccessController.CreateRequest("TABLE", "doris_prod", "demo_retail",
                        "ods_order", null, null, "READ", "长".repeat(900)), as("alice")));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("申请理由不能超过 500 字"), e.getMessage());
        assertTrue(!jdbc.ran("insert into platform.access_request"),
                "限长必须排在落库之前");
    }
}
