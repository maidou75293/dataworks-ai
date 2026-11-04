package com.lab.dataworks.common;

import com.lab.dataworks.auth.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「按 id 直达」路由的统一租户守卫（v447）。
 *
 * <p>外部审计实测：列表接口过滤了租户，但 {@code /{id}} 的读/改/删只校验记录存在，
 * tenant_b 的 ADMIN 能删别的租户的工作流、读别人的 jdbc_url、拿别人的数据服务 Token。
 * 这类缺口不是零星几个，而是一整类写法。以后新增 by-id 路由必须走这里，不要再
 * 直接 {@code where id = ?}。</p>
 *
 * <p>约定：<b>不存在</b>与<b>不属于当前租户</b>一律返回 404，避免被用来探测 id 是否存在。
 * 归属判定优先用 {@code project_id → project.tenant_id}（项目才是所有者），
 * 因为历史的 {@code tenant_id} 列有默认值 'default'，只看它会把漏回填的行误判。</p>
 */
@Component
public class TenantGuard {

    /** 允许按主键 + tenant_id 直接判定的表（只能传常量，不能拼接用户输入）。 */
    private static final Set<String> TENANT_TABLES = Set.of(
            "project", "workflow", "workflow_version", "wf_instance", "lineage_edge",
            "datasource", "sync_task", "data_service", "alert_rule", "quality_rule",
            "bi_dashboard", "flink_sql_job", "warehouse_connection", "udf_resource");

    /** 允许按 project_id 判定归属的表。 */
    private static final Set<String> PROJECT_TABLES = Set.of(
            "datasource", "sync_task", "data_service", "quality_rule", "alert_rule",
            "bi_dashboard", "flink_sql_job", "workflow", "udf_resource", "notify_channel");

    private final JdbcTemplate jdbc;

    public TenantGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String tenant() {
        return TenantScope.current();
    }

    /** 默认租户 = 平台自身；只有它能看到 / 管理平台级对象。 */
    public static boolean platform() {
        return TenantScope.DEFAULT.equalsIgnoreCase(TenantScope.current());
    }

    /** 项目必须属于当前租户，否则 404。 */
    public void requireProject(long projectId) {
        Long n = jdbc.queryForObject(
                "select count(*) from platform.project where id = ? and tenant_id = ?",
                Long.class, projectId, tenant());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    /** 平台级操作（部门、系统配置）仅默认租户可写。 */
    public void requirePlatform() {
        if (!platform()) {
            throw new ApiException(403, "该操作仅平台管理员（默认租户）可执行");
        }
    }

    /**
     * 表自带 project_id：按「项目归属当前租户」判定，取不到 404。
     * 比直接看 tenant_id 更稳，能兜住历史上 tenant_id 漏填的记录。
     */
    public Map<String, Object> requireProjectOwned(String table, long id, String label) {
        if (!PROJECT_TABLES.contains(table)) {
            throw new IllegalStateException("不允许的表: " + table);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select t.* from platform.%s t
                 where t.id = ?
                   and t.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """.formatted(table), id, tenant());
        if (rows.isEmpty()) {
            throw ApiException.notFound(label + "不存在: " + id);
        }
        return rows.get(0);
    }

    public void requireProjectOwnedExists(String table, long id, String label) {
        requireProjectOwned(table, id, label);
    }

    /** 表自带 tenant_id：按主键 + 租户判定，取不到 404。 */
    public Map<String, Object> requireTenantOwned(String table, long id, String label) {
        if (!TENANT_TABLES.contains(table)) {
            throw new IllegalStateException("不允许的表: " + table);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select t.* from platform." + table + " t where t.id = ? and t.tenant_id = ?",
                id, tenant());
        if (rows.isEmpty()) {
            throw ApiException.notFound(label + "不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * 表只挂 workflow_id：按工作流所属项目判定（运行实例 / 版本 / 血缘）。
     */
    public Map<String, Object> requireWorkflowOwned(String table, long id, String label) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select t.* from platform.%s t
                 join platform.workflow w on w.id = t.workflow_id
                 join platform.project p on p.id = w.project_id
                 where t.id = ? and p.tenant_id = ?
                """.formatted(table), id, tenant());
        if (rows.isEmpty()) {
            throw ApiException.notFound(label + "不存在: " + id);
        }
        return rows.get(0);
    }

    /** 站内其它租户的人不可见 / 不可管：用户必须当前租户的成员。 */
    public boolean userInTenant(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        if (platform()) {
            return true;
        }
        Long n = jdbc.queryForObject("""
                select count(*) from platform.app_user u
                  join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                  join platform.tenant t on t.id = tm.tenant_id
                 where u.username = ? and t.code = ?
                """, Long.class, username, tenant());
        return n != null && n > 0;
    }

    /**
     * 用户是否<b>只</b>属于当前租户。
     *
     * <p>{@code app_user.role} / {@code display_name} / 口令都是全局列，没有租户维度：
     * 租户 B 的管理员如果去改一个「同时属于 default 和 tenant_b」的账号，影响会穿透
     * 到另一个租户（典型是平台 staff 被邀请进 B）。因此非平台租户只能管理
     * 独占本租户的账号，跨租户账号一律 404，由平台管理员处理。</p>
     */
    public boolean userExclusiveToCurrentTenant(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        if (platform()) {
            return true;
        }
        Long n = jdbc.queryForObject("""
                select count(*) from platform.app_user u
                  join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                  join platform.tenant t on t.id = tm.tenant_id
                 where u.username = ? and t.code <> ?
                """, Long.class, username, tenant());
        return n == null || n == 0;
    }
}
