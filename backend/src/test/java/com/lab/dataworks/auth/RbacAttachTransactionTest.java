package com.lab.dataworks.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D32：{@code RbacService.attach} 的三步写必须是一个原子事务。
 *
 * <p>卡面场景：第 3 步（插新角色）失败时，旧行为留下「用户已在该租户激活、
 * 角色被删空」的半套数据——用户登录后看不到任何菜单且无人报错。
 * 期望：第 1、2 步的效果随第 3 步一起回滚，异常原样抛出。</p>
 *
 * <p>被测对象走真实 Spring 事务代理（见 {@link TxRollbackHarness}），
 * 内存表按 SQL 片段路由；把某一步换成抛异常即可注入失败。</p>
 */
class RbacAttachTransactionTest {

    /** platform.tenant_member：username → 已激活的租户成员。 */
    private final Set<String> activeMembers = new LinkedHashSet<>();
    /** platform.member_role：该用户在该租户下的角色绑定。 */
    private final List<String> roleBindings = new ArrayList<>();

    private JdbcTemplate jdbc;
    private TxRollbackHarness.SnapshotTxManager txm;
    private RbacService rbac;
    private int failAtUpdate = -1;
    private int updateCount;

    @BeforeEach
    void setUp() {
        activeMembers.clear();
        roleBindings.clear();
        // 存量状态：用户在这个租户原本绑着 VIEWER（attach 会替换成请求的角色）
        roleBindings.add("VIEWER");
        updateCount = 0;
        failAtUpdate = -1;

        jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            int n = ++updateCount;
            if (n == failAtUpdate) {
                throw new DataAccessResourceFailureException("模拟故障：attach 第 " + n + " 步写入失败");
            }
            if (sql.contains("tenant_member")) {
                activeMembers.add("alice");                      // 第 1 步：激活成员
            } else if (sql.startsWith("delete from platform.member_role")) {
                roleBindings.clear();                            // 第 2 步：清旧绑定
            } else if (sql.contains("member_role")) {
                roleBindings.add("ADMIN");                       // 第 3 步：绑新角色
            }
            return 1;
        });

        txm = new TxRollbackHarness.SnapshotTxManager(
                new TxRollbackHarness.Hooks(
                        () -> new Object[]{new LinkedHashSet<>(activeMembers),
                                new ArrayList<>(roleBindings)},
                        snap -> {
                            activeMembers.clear();
                            roleBindings.clear();
                            activeMembers.addAll((Set<String>) ((Object[]) snap)[0]);
                            roleBindings.addAll((List<String>) ((Object[]) snap)[1]);
                        }));
        rbac = TxRollbackHarness.txProxy(new RbacService(jdbc), txm);
    }

    @Test
    void normalPathCommitsAllThreeWrites() {
        rbac.attach("alice", "default", "ADMIN");

        assertTrue(activeMembers.contains("alice"), "成员应已激活");
        assertEquals(List.of("ADMIN"), roleBindings, "旧绑定应被替换为请求角色");
        assertEquals(1, txm.commits, "正常路径必须提交一次");
        assertEquals(0, txm.rollbacks);
    }

    @Test
    void thirdStepFailureRollsBackActivationAndOldBindingDeletion() {
        failAtUpdate = 3;   // 绑新角色这一步挂掉（卡面点名的场景）

        assertThrows(RuntimeException.class,
                () -> rbac.attach("alice", "default", "ADMIN"),
                "异常必须原样抛出，接口层才会给 4xx/5xx，不能静默");

        assertFalse(activeMembers.contains("alice"),
                "expect rollback：第 1 步（激活成员）不得残留——否则用户激活了却没角色，菜单全空");
        assertEquals(List.of("VIEWER"), roleBindings,
                "expect rollback：第 2 步（删除旧角色）不得生效——旧绑定必须还原");
        assertEquals(1, txm.rollbacks, "事务必须整体回滚一次");
        assertEquals(0, txm.commits, "失败路径绝不能提交");
    }

    @Test
    void secondStepFailureRollsBackFirstStep() {
        failAtUpdate = 2;   // 清旧绑定这一步挂掉：卡面要求「第 2 步失败 → 第 1 步的数据不存在」

        assertThrows(RuntimeException.class,
                () -> rbac.attach("alice", "default", "ADMIN"));

        assertFalse(activeMembers.contains("alice"),
                "expect rollback：第 2 步失败时，第 1 步写入的成员行必须不存在");
        assertEquals(List.of("VIEWER"), roleBindings);
        assertEquals(1, txm.rollbacks);
        assertEquals(0, txm.commits);
    }

    @Test
    void blankArgsShortCircuitWithoutOpeningWrites() {
        rbac.attach("  ", "default", "ADMIN");
        rbac.attach("alice", "default", null);

        assertEquals(0, updateCount, "参数为空本就不该有写入");
        assertEquals(List.of("VIEWER"), roleBindings);
    }
}
