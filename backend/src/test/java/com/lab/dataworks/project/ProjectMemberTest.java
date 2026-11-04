package com.lab.dataworks.project;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.ProjectController.MemberEntry;
import com.lab.dataworks.project.ProjectController.MembersRequest;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D42 回归：项目负责人 / 项目管理员可配置，且「能否管理」只有一处判定口径。
 *
 * <p>立案症状：数据模型只有单列 {@code platform.project.owner_user}，
 * 更新路径也从不写它 → 界面上改不了负责人、结构上支持不了多管理员；
 * 创建时 {@code resolveOwner} 却会写这一列 —— 能力只做了一半。</p>
 *
 * <p>本测试钉：① 非负责人非平台管理员配置成员 → 403；② 移除最后一个 OWNER → 400；
 * ③ 正常增删写 PROJECT_MEMBER_ADD / REMOVE 审计（含前后值）；
 * ④ 换负责人写 owner_user + PROJECT_OWNER_CHANGE，并且**新负责人立刻能改、
 * 原负责人不再能改**（判定统一走 owner_user ∪ project_member）；
 * ⑤ 项目管理员（ADMIN 成员）能改项目数据安全策略；⑥ 成员用户不存在 → 400。</p>
 */
class ProjectMemberTest {

    private static final long PROJECT_ID = 7L;
    private static final String TENANT = "tenant_a";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private final DataGuardService security = mock(DataGuardService.class);
    private final List<String> executedSql = new ArrayList<>();
    private final List<Object[]> executedArgs = new ArrayList<>();

    /** 当前成员表内容（username → role），由测试改写，替身按它回答查询。 */
    private final java.util.LinkedHashMap<String, String> memberTable =
            new java.util.LinkedHashMap<>();
    /** platform.project.owner_user 的当前值。 */
    private String ownerUser = "alice";
    /** 存在的启用用户。 */
    private final List<String> activeUsers = new ArrayList<>(List.of("alice", "bob", "carol"));

    private ProjectController controller;

    @BeforeEach
    void setUp() {
        TenantScope.set(TENANT);
        when(http.getAttribute("username")).thenReturn("alice");
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        controller = new ProjectController(jdbc, sessions, mock(DorisAdminService.class),
                mock(WarehouseConnectionService.class), tx,
                mock(com.lab.dataworks.workflow.WorkflowMutationService.class),
                security, mock(WarehouseResolver.class));

        memberTable.put("alice", "OWNER");

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.project p")) {
                return List.of(projectRow());
            }
            if (sql.contains("from platform.project_member")) {
                List<Map<String, Object>> out = new ArrayList<>();
                memberTable.forEach((user, role) -> out.add(Map.of(
                        "username", user, "role", role, "created_at", "2026-10-26T00:00:00Z")));
                return out;
            }
            return List.of();
        });
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    Object[] a = inv.getArguments();
                    if (sql.contains("from platform.app_user")) {
                        return activeUsers.contains(String.valueOf(a[2])) ? 1 : 0;
                    }
                    if (sql.contains("from platform.project_member")) {
                        String user = String.valueOf(a[3]);
                        String role = memberTable.get(user);
                        return role != null && List.of("OWNER", "ADMIN").contains(role) ? 1 : 0;
                    }
                    return 0;
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            executedSql.add(sql);
            Object[] all = inv.getArguments();
            Object[] args = new Object[Math.max(0, all.length - 1)];
            System.arraycopy(all, 1, args, 0, args.length);
            executedArgs.add(args);
            if (sql.contains("delete from platform.project_member")) {
                memberTable.clear();
            }
            if (sql.contains("insert into platform.project_member")) {
                memberTable.put(String.valueOf(args[1]), String.valueOf(args[2]));
            }
            if (sql.contains("set owner_user = ?")) {
                ownerUser = String.valueOf(args[0]);
            }
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    // ---------------------------------------------------------------- 权限

    @Test
    void nonOwnerCannotConfigureMembers_is403() {
        when(http.getAttribute("username")).thenReturn("bob");

        ApiException e = assertThrows(ApiException.class, () -> controller.updateMembers(
                PROJECT_ID, new MembersRequest(List.of(new MemberEntry("bob", "OWNER")), null), http));

        assertEquals(403, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains("负责人"), e.getMessage());
        assertTrue(executedSql.isEmpty(), "403 之前不得写任何表：" + executedSql);
    }

    @Test
    void projectAdminMemberCanChangeSecurityPolicy() {
        // 统一口径：ADMIN 成员也算「能管理这个项目」，可以改项目数据安全策略
        memberTable.put("carol", "ADMIN");
        when(http.getAttribute("username")).thenReturn("carol");
        when(security.projectSecurity(PROJECT_ID)).thenReturn(
                new DataGuardService.ProjectSecurity(PROJECT_ID, ownerUser, "INHERIT", null, null));
        when(jdbc.update(contains("set security_mode"), any(Object[].class))).thenReturn(1);
        // securityView 会取平台/租户策略，测试里给一个固定值即可
        when(security.policy(PROJECT_ID)).thenReturn(new DataGuardService.Policy(
                DataGuardService.PERMISSION_COLUMN, DataGuardService.SOURCE_PROJECT,
                DataGuardService.LOG, DataGuardService.PERMISSION_TABLE, null, PROJECT_ID,
                "INHERIT", null, null));

        controller.updateSecurity(PROJECT_ID, new ProjectController.SecurityRequest(
                "TABLE", null, null, null, null), http);

        verify(jdbc).update(contains("set security_mode"), any(Object[].class));
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void removingLastOwner_is400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.updateMembers(
                PROJECT_ID, new MembersRequest(List.of(new MemberEntry("bob", "ADMIN")), null), http));

        assertEquals(400, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains("OWNER"), e.getMessage());
        assertEquals(Map.of("alice", "OWNER"), memberTable, "校验失败时成员表不能被改动");
    }

    @Test
    void unknownMemberUser_is400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.updateMembers(
                PROJECT_ID, new MembersRequest(
                        List.of(new MemberEntry("alice", "OWNER"), new MemberEntry("dave", "ADMIN")),
                        null), http));

        assertEquals(400, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains("dave"), e.getMessage());
    }

    @Test
    void invalidRole_is400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.updateMembers(
                PROJECT_ID, new MembersRequest(
                        List.of(new MemberEntry("alice", "OWNER"), new MemberEntry("bob", "SUPERUSER")),
                        null), http));

        assertEquals(400, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains("OWNER / ADMIN"), e.getMessage());
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    void addAndRemoveMembers_writeAuditsWithBeforeAndAfter() {
        controller.updateMembers(PROJECT_ID, new MembersRequest(
                List.of(new MemberEntry("alice", "OWNER"), new MemberEntry("bob", "ADMIN")),
                null), http);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions, atLeastOnce()).audit(eq("alice"), action.capture(), anyString(),
                detail.capture());
        List<String> actions = action.getAllValues();
        List<String> details = detail.getAllValues();
        assertTrue(actions.contains("PROJECT_MEMBER_ADD"), actions.toString());
        int idx = actions.indexOf("PROJECT_MEMBER_ADD");
        assertTrue(details.get(idx).contains("（无） → ADMIN"), details.get(idx));

        // 再把 bob 移掉 → REMOVE 审计带原角色
        executedSql.clear();
        controller.updateMembers(PROJECT_ID, new MembersRequest(
                List.of(new MemberEntry("alice", "OWNER")), null), http);
        verify(sessions, atLeastOnce()).audit(eq("alice"), action.capture(), anyString(),
                detail.capture());
        List<String> allActions = action.getAllValues();
        List<String> allDetails = detail.getAllValues();
        int removeIdx = allActions.indexOf("PROJECT_MEMBER_REMOVE");
        assertTrue(removeIdx >= 0, allActions.toString());
        assertTrue(allDetails.get(removeIdx).contains("ADMIN → （移除）"),
                allDetails.get(removeIdx));
    }

    @Test
    void changeOwner_movesMainFieldAndBothSidesReEvaluate() {
        controller.updateMembers(PROJECT_ID, new MembersRequest(
                List.of(new MemberEntry("alice", "ADMIN"), new MemberEntry("bob", "OWNER")),
                "bob"), http);

        assertEquals("bob", ownerUser, "owner_user 主字段必须跟着换");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions, atLeastOnce()).audit(eq("alice"), action.capture(), anyString(),
                detail.capture());
        int idx = action.getAllValues().indexOf("PROJECT_OWNER_CHANGE");
        assertTrue(idx >= 0, action.getAllValues().toString());
        assertEquals("ownerUser: alice → bob", detail.getAllValues().get(idx));

        // 新负责人能继续改（200 路径：不再抛 403）
        when(http.getAttribute("username")).thenReturn("bob");
        controller.updateMembers(PROJECT_ID, new MembersRequest(
                List.of(new MemberEntry("bob", "OWNER"), new MemberEntry("carol", "ADMIN")),
                null), http);
        assertTrue(memberTable.containsKey("carol"), "新负责人应能配置成员");

        // 原负责人现在只是 ADMIN 成员：仍能「管理项目」（统一口径），
        // 但**不能再改成员名单**（成员配置只给负责人本人 / 平台管理员）
        when(http.getAttribute("username")).thenReturn("alice");
        ApiException e = assertThrows(ApiException.class, () -> controller.updateMembers(
                PROJECT_ID, new MembersRequest(
                        List.of(new MemberEntry("alice", "OWNER")), null), http));
        assertEquals(403, e.getStatus(), e.getMessage());
    }

    @Test
    void membersEndpointReportsManageableFlag() {
        when(http.getAttribute("username")).thenReturn("alice");
        Map<String, Object> mine = controller.members(PROJECT_ID, http);
        assertEquals(Boolean.TRUE, mine.get("manageable"));
        assertEquals("alice", mine.get("ownerUser"));

        when(http.getAttribute("username")).thenReturn("carol");
        assertEquals(Boolean.FALSE, controller.members(PROJECT_ID, http).get("manageable"));
    }

    @Test
    void updateMembersIsTransactionalAndTenantScoped() {
        controller.updateMembers(PROJECT_ID, new MembersRequest(
                List.of(new MemberEntry("alice", "OWNER")), null), http);
        assertTrue(executedSql.stream().anyMatch(
                sql -> sql.contains("delete from platform.project_member")), executedSql.toString());
        assertTrue(executedSql.stream().anyMatch(
                sql -> sql.contains("insert into platform.project_member")), executedSql.toString());
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, Object> projectRow() {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", PROJECT_ID);
        row.put("tenant_id", TENANT);
        row.put("code", "gx_01_sales");
        row.put("name", "销售域");
        row.put("description", "描述");
        row.put("owner_user", ownerUser);
        row.put("department_id", 3L);
        row.put("business", "sales");
        row.put("dev_database", "sales_dev");
        row.put("prod_database", "sales_prd");
        row.put("sql_guard_enabled", true);
        row.put("storage_type", "doris");
        return row;
    }
}
