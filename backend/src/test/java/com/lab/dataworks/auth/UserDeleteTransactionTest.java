package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D32：{@code UserService.delete} 的三删必须是一个原子事务。
 *
 * <p>卡面场景：删用户成功但清会话/角色失败 → 残留会话仍可用（安全问题，
 * 不只是脏数据）。期望：任何一步失败整体回滚，账号/会话/角色绑定原样保留，
 * 异常原样抛出（接口层 4xx/5xx 带失败原因）。</p>
 *
 * <p>事务边界用真实 Spring 代理织入（见 {@link TxRollbackHarness}）：
 * 把 delete 上的 {@code @Transactional} 撤掉，本文件两条 rollback 用例必红。</p>
 */
class UserDeleteTransactionTest {

    /** platform.app_user：现存用户 id。 */
    private final List<Long> users = new ArrayList<>(List.of(7L, 8L));
    /** platform.app_session：username|token。 */
    private final List<String> sessions = new ArrayList<>(List.of("alice|token-1"));
    /** platform.member_role：绑定了角色的 user_id。 */
    private final List<Long> memberRoleUserIds = new ArrayList<>(List.of(7L));

    private JdbcTemplate jdbc;
    private TxRollbackHarness.SnapshotTxManager txm;
    private UserService service;
    private int failAtUpdate = -1;
    private int updateCount;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        users.clear();
        users.addAll(List.of(7L, 8L));
        sessions.clear();
        sessions.add("alice|token-1");
        memberRoleUserIds.clear();
        memberRoleUserIds.add(7L);
        updateCount = 0;
        failAtUpdate = -1;

        jdbc = mock(JdbcTemplate.class);

        // findById：delete → requireUser 唯一的读路径（用户名 alice，非内置、VIEWER）
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(7L);
        when(row.getString("username")).thenReturn("alice");
        when(row.getString("display_name")).thenReturn("Alice");
        when(row.getString("role")).thenReturn("VIEWER");
        when(row.getString("status")).thenReturn("ACTIVE");
        when(row.getString("source")).thenReturn("LOCAL");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (!sql.contains("from platform.app_user where id = ?")) {
                        return new ArrayList<>();
                    }
                    RowMapper<UserService.Account> mapper = inv.getArgument(1);
                    return new ArrayList<>(List.of(mapper.mapRow(row, 0)));
                });

        // revokeAllGrants 走 queryForList（Mockito 默认返回空列表：无 APPROVED 授权）
        // 三条 DELETE 按 SQL 路由到内存表；第 failAtUpdate 次 update 抛异常注入故障
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            int n = ++updateCount;
            if (n == failAtUpdate) {
                throw new DataAccessResourceFailureException("模拟故障：delete 第 " + n + " 步写入失败");
            }
            if (sql.startsWith("delete from platform.app_user")) {
                users.remove(7L);
            } else if (sql.startsWith("delete from platform.app_session")) {
                sessions.remove("alice|token-1");
            } else if (sql.startsWith("delete from platform.member_role")) {
                memberRoleUserIds.remove(7L);
            }
            return 1;
        });

        txm = new TxRollbackHarness.SnapshotTxManager(
                new TxRollbackHarness.Hooks(
                        () -> new Object[]{new ArrayList<>(users), new ArrayList<>(sessions),
                                new ArrayList<>(memberRoleUserIds)},
                        snap -> {
                            Object[] s = (Object[]) snap;
                            users.clear();
                            sessions.clear();
                            memberRoleUserIds.clear();
                            users.addAll((List<Long>) s[0]);
                            sessions.addAll((List<String>) s[1]);
                            memberRoleUserIds.addAll((List<Long>) s[2]);
                        }));
        service = TxRollbackHarness.txProxy(new UserService(
                jdbc, "admin", "admin", false,
                mock(RbacService.class), mock(PermissionResolver.class),
                mock(SessionService.class)), txm);
    }

    @Test
    void normalPathDeletesUserSessionsAndRoleBindings() {
        service.delete(7L, "admin");

        assertEquals(List.of(8L), users, "目标账号已删，其他账号不动");
        assertTrue(sessions.isEmpty(), "会话必须随账号清空");
        assertTrue(memberRoleUserIds.isEmpty(), "角色绑定必须随账号清空");
        assertEquals(1, txm.commits, "正常路径必须提交一次");
        assertEquals(0, txm.rollbacks);
    }

    @Test
    void sessionDeletionFailureRollsBackUserDeletion() {
        failAtUpdate = 2;   // 卡面场景：删用户成功、清会话失败

        assertThrows(RuntimeException.class, () -> service.delete(7L, "admin"),
                "异常必须原样抛出，接口层才会返回 5xx 并说明失败点");

        assertTrue(users.contains(7L),
                "expect rollback：第 2 步失败时，第 1 步（删 app_user）不得残留");
        assertEquals(List.of("alice|token-1"), sessions,
                "expect rollback：会话行必须还原——否则出现「账号没了、token 还能用」");
        assertEquals(List.of(7L), memberRoleUserIds);
        assertEquals(1, txm.rollbacks);
        assertEquals(0, txm.commits, "失败路径绝不能提交");
    }

    @Test
    void roleBindingDeletionFailureRollsBackFirstTwoSteps() {
        failAtUpdate = 3;   // 清角色绑定这一步挂掉

        assertThrows(RuntimeException.class, () -> service.delete(7L, "admin"));

        assertTrue(users.contains(7L), "expect rollback：账号不得被删");
        assertTrue(sessions.isEmpty() || sessions.contains("alice|token-1"), "会话状态不得为半套");
        assertEquals(List.of("alice|token-1"), sessions,
                "expect rollback：第 2 步（清会话）也必须还原，整体回到事务开始前");
        assertEquals(List.of(7L), memberRoleUserIds);
        assertEquals(1, txm.rollbacks);
        assertEquals(0, txm.commits);
    }

    @Test
    void guardRejectionsDoNotTouchAnyTable() {
        // 删自己：ApiException 在任何写入之前抛出（不是事务回滚，是压根没动手）
        assertThrows(ApiException.class, () -> service.delete(7L, "alice"));
        assertEquals(List.of(7L, 8L), users);
        assertEquals(List.of("alice|token-1"), sessions);
        assertEquals(0, updateCount, "守卫拒绝不应有 DELETE");
    }
}
