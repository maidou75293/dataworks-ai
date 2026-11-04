package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BUG-TC7-G3-1：{@code setRoles} 的空集合守卫。
 *
 * <p>卡面场景：接口 javadoc 原写「内置角色保持不变」，实现却是含内置的全量覆盖；
 * 守卫脚本按文档收尾提交 {@code {"roles":[]}}，成员被静默清空成
 * 「能登录、所有接口 403」。文档已改为如实描述全量覆盖；本测试锁死另一半契约——
 * 空提交必须显式 {@code clearAll=true} 才放行。</p>
 *
 * <p>注意：全量覆盖（含内置）语义本身保留，由管理员显式提交完整角色集合决定，
 * 见 {@link #fullOverwriteDeletesAllRolesIncludingBuiltin}。</p>
 */
class RbacSetRolesClearAllTest {

    private JdbcTemplate jdbc;
    private RbacService rbac;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // 用户 / 租户 id 查询按出现顺序返回；角色存在性查询返回「存在」。
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L, 3L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        rbac = new RbacService(jdbc);
    }

    @Test
    void emptyRolesWithoutClearAllRejectedAndNoWrites() {
        ApiException ex = assertThrows(ApiException.class,
                () -> rbac.setRoles("admin", "u_bug_t5", "default", List.of()));

        assertEquals(400, ex.getStatus(), "空集合必须 400，不能静默清空");
        assertTrue(ex.getMessage().contains("clearAll"), "报错要给出显式清空的出口：" + ex.getMessage());
        verifyNoInteractions(jdbc);   // 守卫在任何读写之前
    }

    @Test
    void nullRolesWithoutClearAllRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> rbac.setRoles("admin", "u_bug_t5", "default", null));

        assertEquals(400, ex.getStatus());
        verifyNoInteractions(jdbc);
    }

    @Test
    void emptyRolesWithClearAllProceedsToWipe() {
        rbac.setRoles("admin", "u_bug_t5", "default", List.of(), true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(3L), eq(7L));
        assertTrue(sql.getValue().startsWith("delete from platform.member_role"),
                "clearAll=true 才允许走到全删步骤，实际 SQL：" + sql.getValue());
    }

    /** 全量覆盖含内置：提交非空集合时删除步骤照常发生（卡面评审确认 ②，语义不改）。 */
    @Test
    void fullOverwriteDeletesAllRolesIncludingBuiltin() {
        rbac.setRoles("admin", "u_bug_t5", "default", List.of("bug_a_custom"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().get(0).startsWith("delete from platform.member_role"),
                "第一步必须全删（含内置行）——全量覆盖语义，实际：" + sql.getAllValues().get(0));
        assertTrue(sql.getAllValues().get(1).startsWith("insert into platform.member_role"),
                "第二步按提交集合重插，实际：" + sql.getAllValues().get(1));
        // 角色码入库前统一大写（normalizeCode）；插入是 INSERT...SELECT，绑定
        // (tenantId, userId, tenantId, code) 四个参数
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(
                        s -> s.startsWith("insert into platform.member_role")),
                eq(3L), eq(7L), eq(3L), eq("BUG_A_CUSTOM"));
    }

    @Test
    void unknownRoleCodeStillRejectedBeforeAnyDelete() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        ApiException ex = assertThrows(ApiException.class,
                () -> rbac.setRoles("admin", "u_bug_t5", "default", List.of("ghost_role")));

        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("未知角色"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
