package com.lab.dataworks.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限模型第一期：只读访问「租户 / 角色 / 权限点」新表。
 *
 * <p>本期判定仍走 {@link Role} 枚举（行为不变），这里提供的接口用于：
 * 1) 界面展示用户实际拥有的角色与权限点；
 * 2) 迁移期做「旧枚举 vs 新表」的一致性核对；
 * 3) 第二期把 {@code RequirePermission} 切到权限点时的数据来源。</p>
 */
@Service
public class RbacService {

    private final JdbcTemplate jdbc;

    public RbacService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 用户加入的租户（含默认租户标记与租户内角色）。 */
    public List<Map<String, Object>> tenants(String username) {
        return jdbc.query("""
                select t.code, t.name, tm.is_default,
                       coalesce(string_agg(distinct r.code, ','), '') as roles
                from platform.tenant_member tm
                join platform.tenant t on t.id = tm.tenant_id
                join platform.app_user u on u.id = tm.user_id
                left join platform.member_role mr
                       on mr.tenant_id = tm.tenant_id and mr.user_id = tm.user_id
                left join platform.role r on r.id = mr.role_id and r.status = 'ACTIVE'
                where u.username = ? and tm.status = 'ACTIVE' and t.status = 'ACTIVE'
                group by t.code, t.name, tm.is_default
                order by tm.is_default desc, t.code
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", rs.getString("code"));
            row.put("name", rs.getString("name"));
            row.put("isDefault", rs.getBoolean("is_default"));
            row.put("roles", split(rs.getString("roles")));
            return row;
        }, username);
    }

    /** 用户在指定租户下的有效权限点（多角色取并集）。 */
    public Set<String> permissions(String username, String tenantCode) {
        List<String> rows = jdbc.queryForList("""
                select distinct rp.permission
                from platform.app_user u
                join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                join platform.tenant t on t.id = tm.tenant_id and t.code = ?
                join platform.member_role mr
                     on mr.tenant_id = tm.tenant_id and mr.user_id = tm.user_id
                join platform.role r on r.id = mr.role_id and r.status = 'ACTIVE'
                join platform.role_permission rp on rp.role_id = r.id
                where u.username = ?
                order by rp.permission
                """, String.class, tenantCode, username);
        return new LinkedHashSet<>(rows);
    }

    /** 用户在指定租户下的角色编码。 */
    public List<String> roles(String username, String tenantCode) {
        return jdbc.queryForList("""
                select distinct r.code
                from platform.app_user u
                join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                join platform.tenant t on t.id = tm.tenant_id and t.code = ?
                join platform.member_role mr
                     on mr.tenant_id = tm.tenant_id and mr.user_id = tm.user_id
                join platform.role r on r.id = mr.role_id and r.status = 'ACTIVE'
                where u.username = ?
                order by r.code
                """, String.class, tenantCode, username);
    }

    /**
     * 这个账号是否已经进入租户成员表（即权限由 RBAC 表管理）。
     *
     * <p>V55 迁移把所有存量用户都写进了 tenant_member，所以「没有任何成员行」只可能是
     * 迁移没覆盖到的账号。用它区分「RBAC 里角色被清空」（必须什么都不能做）与
     * 「RBAC 里根本没有这个账号」（可以回退旧枚举，避免把人锁在门外）。</p>
     */
    public boolean hasAnyMembership(String username) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from platform.app_user u
                join platform.tenant_member tm on tm.user_id = u.id
                where u.username = ?
                """, Integer.class, username);
        return count != null && count > 0;
    }

    /** 权限点目录（按分组）。 */
    public List<Map<String, Object>> catalog() {
        return jdbc.queryForList("""
                select code, name, group_name, danger
                from platform.permission_catalog
                order by group_name, code
                """);
    }

    /** 角色定义（含权限点），可按租户过滤。 */
    public List<Map<String, Object>> rolesOfTenant(String tenantCode) {
        return jdbc.query("""
                select r.code, r.name, r.builtin,
                       coalesce(string_agg(rp.permission, ',' order by rp.permission), '') as perms
                from platform.role r
                join platform.tenant t on t.id = r.tenant_id
                left join platform.role_permission rp on rp.role_id = r.id
                where t.code = ? and r.status = 'ACTIVE'
                group by r.code, r.name, r.builtin
                order by r.builtin desc, r.code
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", rs.getString("code"));
            row.put("name", rs.getString("name"));
            row.put("builtin", rs.getBoolean("builtin"));
            row.put("permissions", split(rs.getString("perms")));
            return row;
        }, tenantCode);
    }

    /**
     * 用户默认租户：优先 is_default，其次按编码排序取第一个。
     *
     * <p>没有任何<b>有效</b>租户成员关系时返回 {@code null}，不再回落到 {@code default}。
     * 旧实现回落到 default 会把「租户被删除后的孤儿账号」当成平台默认租户成员：
     * 登录拿到 default 的上下文，再凭 app_user.role 旧枚举读到平台数据（D03）。
     * 调用方必须显式处理 null（登录拒绝 / 请求 401）。</p>
     */
    public String defaultTenant(String username) {
        List<String> rows = jdbc.queryForList("""
                select t.code
                from platform.tenant_member tm
                join platform.tenant t on t.id = tm.tenant_id
                join platform.app_user u on u.id = tm.user_id
                where u.username = ? and tm.status = 'ACTIVE' and t.status = 'ACTIVE'
                order by tm.is_default desc, t.code
                limit 1
                """, String.class, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 账号是否至少属于一个有效租户（登录与拦截器判断孤儿账号用）。 */
    public boolean hasActiveTenant(String username) {
        return defaultTenant(username) != null;
    }

    /**
     * 把用户挂到某租户并绑定一个内置角色（第一期双写：旧 role 列照常维护）。
     * 已有绑定会被替换掉，保证「一人一租户一个内置角色」与旧模型等价。
     *
     * <p>D32：三步写（激活成员 → 清旧绑定 → 绑新角色）必须是一个原子操作。
     * 之前没有事务边界，第 3 步失败会留下「用户已在该租户激活、角色被删空」的
     * 半套数据——登录后看不到任何菜单，且无人报错。任何一步失败整体回滚，
     * 异常原样抛出（接口层 4xx/5xx 带失败原因），不许吞。</p>
     */
    @Transactional
    public void attach(String username, String tenantCode, String roleCode) {
        if (username == null || username.isBlank() || roleCode == null || roleCode.isBlank()) {
            return;
        }
        String tenant = tenantCode == null || tenantCode.isBlank() ? "default" : tenantCode.trim();
        jdbc.update("""
                insert into platform.tenant_member (tenant_id, user_id, status, is_default)
                select t.id, u.id, 'ACTIVE',
                       not exists (select 1 from platform.tenant_member m2
                                   where m2.user_id = u.id and m2.status = 'ACTIVE')
                from platform.tenant t, platform.app_user u
                where t.code = ? and u.username = ?
                on conflict (tenant_id, user_id) do update set status = 'ACTIVE'
                """, tenant, username);
        jdbc.update("""
                delete from platform.member_role mr
                using platform.app_user u, platform.tenant t
                where mr.user_id = u.id and mr.tenant_id = t.id
                  and u.username = ? and t.code = ?
                """, username, tenant);
        jdbc.update("""
                insert into platform.member_role (tenant_id, user_id, role_id)
                select t.id, u.id, r.id
                from platform.tenant t, platform.app_user u, platform.role r
                where t.code = ? and u.username = ?
                  and r.tenant_id = t.id and r.code = ? and r.builtin and r.status = 'ACTIVE'
                on conflict (tenant_id, user_id, role_id) do nothing
                """, tenant, username, roleCode);
    }

    /** 租户成员及其角色（角色管理界面用）。 */
    public List<Map<String, Object>> members(String tenantCode) {
        return jdbc.query("""
                select u.id, u.username, u.display_name, u.status, u.role as legacy_role,
                       coalesce(string_agg(distinct r.code, ','), '') as roles
                from platform.tenant_member tm
                join platform.tenant t on t.id = tm.tenant_id and t.code = ?
                join platform.app_user u on u.id = tm.user_id
                left join platform.member_role mr
                       on mr.tenant_id = tm.tenant_id and mr.user_id = tm.user_id
                left join platform.role r on r.id = mr.role_id and r.status = 'ACTIVE'
                group by u.id, u.username, u.display_name, u.status, u.role
                order by u.username
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", rs.getLong("id"));
            row.put("username", rs.getString("username"));
            row.put("displayName", rs.getString("display_name"));
            row.put("status", rs.getString("status"));
            row.put("legacyRole", rs.getString("legacy_role"));
            row.put("roles", split(rs.getString("roles")));
            return row;
        }, tenantCode);
    }

    /** 按用户 id 取用户名（成员角色设置用）。 */
    public String usernameOf(long userId) {
        List<String> rows = jdbc.queryForList(
                "select username from platform.app_user where id = ?", String.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------ 角色管理（第二期）

    /** 建自定义角色；内置角色不可重复建。 */
    public void createRole(String tenantCode, String code, String name,
                           java.util.List<String> permissions) {
        String roleCode = normalizeCode(code);
        if (name == null || name.isBlank()) {
            throw com.lab.dataworks.common.ApiException.badRequest("角色名称不能为空");
        }
        Integer exists = jdbc.queryForObject("""
                select count(*) from platform.role r join platform.tenant t on t.id = r.tenant_id
                where t.code = ? and r.code = ?
                """, Integer.class, tenantCode, roleCode);
        if (exists != null && exists > 0) {
            throw com.lab.dataworks.common.ApiException.conflict("角色已存在: " + roleCode);
        }
        validatePermissions(permissions);
        Long roleId = jdbc.queryForObject("""
                insert into platform.role (tenant_id, code, name, builtin, status)
                select t.id, ?, ?, false, 'ACTIVE' from platform.tenant t where t.code = ?
                returning id
                """, Long.class, roleCode, name.trim(), tenantCode);
        replacePermissions(roleId, permissions);
    }

    /** 改自定义角色；内置角色不允许改（保证可预期）。 */
    public void updateRole(String tenantCode, String code, String name,
                           java.util.List<String> permissions) {
        Long roleId = requireCustomRole(tenantCode, code);
        if (name != null && !name.isBlank()) {
            jdbc.update("update platform.role set name = ? where id = ?", name.trim(), roleId);
        }
        if (permissions != null) {
            validatePermissions(permissions);
            replacePermissions(roleId, permissions);
        }
    }

    /** 删自定义角色；仍被成员占用时 409。 */
    public void deleteRole(String tenantCode, String code) {
        Long roleId = requireCustomRole(tenantCode, code);
        Integer used = jdbc.queryForObject(
                "select count(*) from platform.member_role where role_id = ?",
                Integer.class, roleId);
        if (used != null && used > 0) {
            throw com.lab.dataworks.common.ApiException.conflict(
                    "角色仍被 " + used + " 个成员使用，请先转移");
        }
        jdbc.update("delete from platform.role where id = ?", roleId);
    }

    /**
     * 授权前校验（D02）：不能授予高于自身的角色。
     *
     * <ul>
     *   <li>内置 ADMIN 只有平台管理员（默认租户里具备 ADMIN 角色）可授；</li>
     *   <li>其余角色要求「该角色的权限点 ⊆ 调用方在目标租户的权限点」，
     *       防止只有 {@code user:manage} 的自定义角色把别人（或自己）提成高权角色。</li>
     * </ul>
     */
    public void assertCanGrant(String actor, String tenantCode, java.util.List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return;
        }
        String tenant = tenantCode == null || tenantCode.isBlank()
                ? defaultTenant(actor) : tenantCode;
        // 平台管理员按「调用方当前所在租户」判定，而不是目标租户：
        // 平台管理员建租户时当前租户是 default，目标租户是新建的 code，
        // 若按目标租户判定会把自己复制 ADMIN 的初始化动作也挡掉。
        boolean platformAdmin = "default".equalsIgnoreCase(TenantScope.current())
                && roles(actor, "default").contains("ADMIN");
        java.util.Set<String> actorAtoms = platformAdmin
                ? java.util.Set.of() : permissions(actor, tenant);
        for (String raw : roleCodes) {
            String code = normalizeCode(raw);
            if ("ADMIN".equals(code) && !platformAdmin) {
                throw new com.lab.dataworks.common.ApiException(403,
                        "只有平台管理员（默认租户 ADMIN）可以授予 ADMIN 角色");
            }
            if (platformAdmin) {
                continue;
            }
            java.util.Set<String> roleAtoms = permissionsOfRole(tenant, code);
            java.util.List<String> missing = roleAtoms.stream()
                    .filter(permission -> !actorAtoms.contains(permission))
                    .sorted().toList();
            if (!missing.isEmpty()) {
                throw new com.lab.dataworks.common.ApiException(403,
                        "不能授予高于自身的角色: " + code
                                + "（缺少权限点：" + String.join(",", missing) + "）");
            }
        }
    }

    /** 某租户下某个角色的权限点；角色不存在时返回空集合。 */
    private java.util.Set<String> permissionsOfRole(String tenantCode, String roleCode) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                select rp.permission
                  from platform.role r
                  join platform.tenant t on t.id = r.tenant_id
                  join platform.role_permission rp on rp.role_id = r.id
                 where t.code = ? and r.code = ? and r.status = 'ACTIVE'
                """, String.class, tenantCode, roleCode));
    }

    /** 给成员设置角色（覆盖该租户下的全部角色，含内置）。actor 用于「不得越权授权」校验。 */
    public void setRoles(String actor, String username, String tenantCode,
                         java.util.List<String> roleCodes) {
        setRoles(actor, username, tenantCode, roleCodes, false);
    }

    /**
     * BUG-TC7-G3-1：语义确认是「全量覆盖含内置」（与历史实现、前端面板口径一致），
     * 文档口径原写成「内置角色保持不变」，守卫脚本照文档提交就静默摘掉内置角色。
     * 现在文档已对齐实现；并且空提交必须带 {@code clearAll=true} 才放行——
     * 清空后成员只剩「能登录、所有接口 403」，不该是一个无确认的默认副作用。
     */
    public void setRoles(String actor, String username, String tenantCode,
                         java.util.List<String> roleCodes, boolean clearAll) {
        if ((roleCodes == null || roleCodes.isEmpty()) && !clearAll) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "roles 为空：覆盖会清空该成员在本租户的全部角色（含内置，成员将变成只能登录、"
                            + "所有接口 403）。确认清空请提交 clearAll=true；"
                            + "若只想摘除部分角色，请提交覆盖后的完整角色集合");
        }
        Long userId = jdbc.queryForObject(
                "select id from platform.app_user where username = ?", Long.class, username);
        Long tenantId = jdbc.queryForObject(
                "select id from platform.tenant where code = ?", Long.class, tenantCode);
        // v449 P2：未知角色码之前直接 200，但一行 member_role 都不会插进去，
        // 用户变成「能登录、roles=[]、所有接口 403」。先整体校验再落库。
        java.util.List<String> wanted = new java.util.ArrayList<>();
        for (String code : roleCodes == null ? java.util.List.<String>of() : roleCodes) {
            String normalized = normalizeCode(code);
            Integer exists = jdbc.queryForObject("""
                    select count(*) from platform.role r
                     where r.tenant_id = ? and r.code = ? and r.status = 'ACTIVE'
                    """, Integer.class, tenantId, normalized);
            if (exists == null || exists == 0) {
                throw com.lab.dataworks.common.ApiException.badRequest(
                        "未知角色: " + code + "（该租户下不存在）");
            }
            wanted.add(normalized);
        }
        assertCanGrant(actor, tenantCode, wanted);
        // 覆盖该租户下的全部角色（含内置）：由管理员显式决定成员在这个租户里的角色集合
        jdbc.update("delete from platform.member_role where tenant_id = ? and user_id = ?",
                tenantId, userId);
        for (String code : wanted) {
            jdbc.update("""
                    insert into platform.member_role (tenant_id, user_id, role_id)
                    select ?, ?, r.id from platform.role r
                    where r.tenant_id = ? and r.code = ? and r.status = 'ACTIVE'
                    on conflict do nothing
                    """, tenantId, userId, tenantId, code);
        }
    }

    private Long requireCustomRole(String tenantCode, String code) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.builtin from platform.role r
                join platform.tenant t on t.id = r.tenant_id
                where t.code = ? and r.code = ?
                """, tenantCode, normalizeCode(code));
        if (rows.isEmpty()) {
            throw com.lab.dataworks.common.ApiException.notFound("角色不存在: " + code);
        }
        if (Boolean.TRUE.equals(rows.get(0).get("builtin"))) {
            throw com.lab.dataworks.common.ApiException.badRequest("内置角色不可修改或删除");
        }
        return ((Number) rows.get(0).get("id")).longValue();
    }

    private void validatePermissions(java.util.List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return;
        }
        java.util.List<String> distinct = permissions.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
        Integer known = jdbc.queryForObject(
                "select count(*) from platform.permission_catalog where code in (" + placeholders + ")",
                Integer.class, distinct.toArray());
        if (known == null || known != distinct.size()) {
            throw com.lab.dataworks.common.ApiException.badRequest("存在未知权限点");
        }
    }

    private void replacePermissions(Long roleId, java.util.List<String> permissions) {
        jdbc.update("delete from platform.role_permission where role_id = ?", roleId);
        if (permissions == null) {
            return;
        }
        for (String perm : new java.util.LinkedHashSet<>(permissions)) {
            jdbc.update("insert into platform.role_permission (role_id, permission) "
                        + "values (?, ?) on conflict do nothing", roleId, perm);
        }
    }

    // ---------- 租户管理（第四期） ----------

    /** 平台级租户清单（管理页用）：成员数 + 是否已有业务数据。 */
    public List<Map<String, Object>> allTenants() {
        return jdbc.query("""
                select t.code, t.name, t.status, t.created_at,
                       (select count(*) from platform.tenant_member m
                         where m.tenant_id = t.id and m.status = 'ACTIVE') as members,
                       (select count(*) from platform.project p where p.tenant_id = t.code)
                     + (select count(*) from platform.workflow w where w.tenant_id = t.code)
                     + (select count(*) from platform.datasource d where d.tenant_id = t.code)
                     + (select count(*) from platform.sync_task s where s.tenant_id = t.code)
                     + (select count(*) from platform.data_service ds where ds.tenant_id = t.code)
                       as data_count
                from platform.tenant t
                order by t.code
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", rs.getString("code"));
            row.put("name", rs.getString("name"));
            row.put("status", rs.getString("status"));
            row.put("members", rs.getInt("members"));
            row.put("dataCount", rs.getInt("data_count"));
            row.put("createdAt", rs.getTimestamp("created_at"));
            return row;
        });
    }

    /**
     * 建租户：复制 default 租户的 5 个内置角色与授权（REQ-DW-12 收敛后口径，保证一致性），
     * 并把创建者加入该租户、给 ADMIN——否则建完自己进不去。
     */
    public void createTenant(String code, String name, String createdBy) {
        String tenantCode = normalizeTenant(code);
        String tenantName = name == null || name.isBlank() ? tenantCode : name.trim();
        // D20：名称上限对齐 tenant.name varchar(128)。此前入口只校验编码，超长名称直接撞
        // 约束，被全局处理器归成 409「违反唯一性或外键约束」，把调用方引向重名冲突的误判。
        if (tenantName.length() > 128) {
            throw com.lab.dataworks.common.ApiException.badRequest("租户名称不能超过 128 个字符");
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.tenant where code = ?", Integer.class, tenantCode);
        if (exists != null && exists > 0) {
            throw com.lab.dataworks.common.ApiException.conflict("租户编码已存在: " + tenantCode);
        }
        jdbc.update("insert into platform.tenant (code, name, status) values (?, ?, 'ACTIVE')",
                tenantCode, tenantName);
        jdbc.update("""
                insert into platform.role (tenant_id, code, name, builtin, status)
                select nt.id, r.code, r.name, true, 'ACTIVE'
                from platform.tenant nt
                join platform.tenant st on st.code = 'default'
                join platform.role r on r.tenant_id = st.id and r.builtin
                where nt.code = ?
                """, tenantCode);
        jdbc.update("""
                insert into platform.role_permission (role_id, permission)
                select nr.id, rp.permission
                from platform.tenant nt
                join platform.role nr on nr.tenant_id = nt.id and nr.builtin
                join platform.tenant st on st.code = 'default'
                join platform.role sr on sr.tenant_id = st.id
                                     and sr.code = nr.code and sr.builtin
                join platform.role_permission rp on rp.role_id = sr.id
                where nt.code = ?
                on conflict do nothing
                """, tenantCode);
        if (createdBy != null && !createdBy.isBlank()) {
            addMember(createdBy, tenantCode, createdBy, List.of("ADMIN"));
        }
    }

    /**
     * 删除租户：默认租户、仍有成员、或已有业务数据的租户不许删（D03）。
     *
     * <p>成员关系会随租户级联删除；不先挡住的话，成员账号会变成「没有有效租户」的孤儿，
     * 旧实现登录时回落 default 并读到平台数据。返回 409 并给出成员数。</p>
     */
    public void deleteTenant(String code) {
        String tenantCode = normalizeTenant(code);
        if ("default".equals(tenantCode)) {
            throw com.lab.dataworks.common.ApiException.badRequest("默认租户不可删除");
        }
        Integer members = jdbc.queryForObject("""
                select count(*) from platform.tenant_member m
                  join platform.tenant t on t.id = m.tenant_id
                 where t.code = ? and m.status = 'ACTIVE'
                """, Integer.class, tenantCode);
        if (members != null && members > 0) {
            throw com.lab.dataworks.common.ApiException.conflict(
                    "租户内还有 " + members + " 个成员，请先移除成员再删除租户");
        }
        Integer data = jdbc.queryForObject("""
                select (select count(*) from platform.project where tenant_id = ?)
                     + (select count(*) from platform.workflow where tenant_id = ?)
                     + (select count(*) from platform.datasource where tenant_id = ?)
                     + (select count(*) from platform.sync_task where tenant_id = ?)
                     + (select count(*) from platform.data_service where tenant_id = ?)
                """, Integer.class, tenantCode, tenantCode, tenantCode, tenantCode, tenantCode);
        if (data != null && data > 0) {
            throw com.lab.dataworks.common.ApiException.conflict(
                    "租户内还有 " + data + " 条业务数据，不能删除");
        }
        int n = jdbc.update("delete from platform.tenant where code = ?", tenantCode);
        if (n == 0) {
            throw com.lab.dataworks.common.ApiException.notFound("租户不存在: " + tenantCode);
        }
    }

    /** 移除租户成员（删除 tenant_member 与 tenant 内的角色绑定）。 */
    public void removeMember(String tenantCode, long userId) {
        String tenant = normalizeTenant(tenantCode);
        Long tenantId = jdbc.queryForObject(
                "select id from platform.tenant where code = ?", Long.class, tenant);
        int n = jdbc.update("""
                delete from platform.tenant_member tm
                 where tm.tenant_id = ? and tm.user_id = ?
                """, tenantId, userId);
        if (n == 0) {
            throw com.lab.dataworks.common.ApiException.notFound(
                    "用户不是该租户的成员: " + userId);
        }
        jdbc.update("delete from platform.member_role where tenant_id = ? and user_id = ?",
                tenantId, userId);
    }

    /** 邀请已有账号加入租户，并设置其在该租户下的角色。 */
    public void addMember(String actor, String tenantCode, String username, List<String> roleCodes) {
        String tenant = normalizeTenant(tenantCode);
        Long tenantId = jdbc.queryForObject(
                "select id from platform.tenant where code = ?", Long.class, tenant);
        Long userId = jdbc.queryForObject(
                "select id from platform.app_user where username = ?", Long.class, username);
        jdbc.update("""
                insert into platform.tenant_member (tenant_id, user_id, status, is_default)
                values (?, ?, 'ACTIVE', false)
                on conflict (tenant_id, user_id) do update set status = 'ACTIVE'
                """, tenantId, userId);
        setRoles(actor, username, tenant, roleCodes == null || roleCodes.isEmpty()
                ? List.of("VIEWER") : roleCodes);
    }

    private static String normalizeTenant(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z][a-z0-9_]{1,31}")) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "租户编码需为 2-32 位小写字母、数字或下划线，字母开头");
        }
        return value;
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "角色编码需为 2-64 位大写字母、数字或下划线");
        }
        return value;
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
