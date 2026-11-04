package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import com.lab.dataworks.common.LikePattern;

/**
 * 平台用户与角色。角色每次请求从库里解析，管理员改角色/禁用后立即生效。
 *
 * <p>内置管理员（PLATFORM_ADMIN_USER）的密码来自部署配置，不写库，
 * 也不能在界面上改角色或禁用，避免把自己锁在门外。</p>
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    public enum AuthReason { OK, NOT_FOUND, BAD_PASSWORD, DISABLED }

    public record AuthResult(Account account, AuthReason reason) {
    }

    public record Account(long id, String username, String displayName, String role,
                          String status, String source, boolean builtin,
                          Timestamp createdAt, Timestamp lastLoginAt) {
        public Role roleEnum() {
            return Role.valueOf(role);
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$");

    private final JdbcTemplate jdbc;
    private final String builtinAdmin;
    private final String builtinPassword;
    private final boolean ssoAllowLocalBinding;
    private final RbacService rbac;
    private final PermissionResolver permissions;
    private final com.lab.dataworks.auth.SessionService sessions;

    public UserService(JdbcTemplate jdbc,
                       @Value("${platform.admin-user:admin}") String builtinAdmin,
                       @Value("${platform.admin-password:admin}") String builtinPassword,
                       @Value("${platform.sso.allow-local-account-binding:false}")
                       boolean ssoAllowLocalBinding,
                       RbacService rbac, PermissionResolver permissions,
                       com.lab.dataworks.auth.SessionService sessions) {
        this.jdbc = jdbc;
        this.builtinAdmin = builtinAdmin;
        this.builtinPassword = builtinPassword;
        this.ssoAllowLocalBinding = ssoAllowLocalBinding;
        this.rbac = rbac;
        this.permissions = permissions;
        this.sessions = sessions;
    }

    // ---------- 查询 ----------

    public Account find(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        List<Account> rows = jdbc.query("""
                select id, username, display_name, role, status, source, created_at, last_login_at
                from platform.app_user where username = ?
                """, (rs, i) -> new Account(
                rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getString("status"), rs.getString("source"),
                isBuiltin(rs.getString("username")),
                rs.getTimestamp("created_at"), rs.getTimestamp("last_login_at")), username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Account findById(long id) {
        List<Account> rows = jdbc.query("""
                select id, username, display_name, role, status, source, created_at, last_login_at
                from platform.app_user where id = ?
                """, (rs, i) -> new Account(
                rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getString("status"), rs.getString("source"),
                isBuiltin(rs.getString("username")),
                rs.getTimestamp("created_at"), rs.getTimestamp("last_login_at")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 用户列表。
     *
     * @param tenant 非 null 时只返回该租户的成员（v448：v447 只在前端层过滤，
     *               列表接口仍然把全平台账号查出来再筛，属于「先查后判」，一旦有人
     *               复用了 {@code users.list()} 就又漏了；现在过滤下沉到 SQL）。
     */
    public List<Map<String, Object>> list(String keyword, String tenant) {
        StringBuilder sql = new StringBuilder("""
                select u.id, u.username, u.display_name, u.role, u.status, u.source,
                       u.created_at, u.last_login_at
                from platform.app_user u
                """);
        List<Object> args = new ArrayList<>();
        if (tenant != null && !tenant.isBlank()) {
            sql.append("""
                     join platform.tenant_member tm
                       on tm.user_id = u.id and tm.status = 'ACTIVE'
                     join platform.tenant t
                       on t.id = tm.tenant_id and t.code = ?
                    where 1 = 1
                    """);
            args.add(tenant);
        } else {
            sql.append(" where 1 = 1");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (username ilike ? or display_name ilike ?)");
            String like = LikePattern.contains(keyword);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by u.id");
        return jdbc.query(sql.toString(), (rs, i) -> new Account(
                        rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("role"), rs.getString("status"), rs.getString("source"),
                        isBuiltin(rs.getString("username")),
                        rs.getTimestamp("created_at"), rs.getTimestamp("last_login_at")),
                args.toArray()).stream().map(this::view).toList();
    }

    /** 保留旧签名（内部/测试用）：不带租户过滤。业务接口请显式传租户。 */
    public List<Map<String, Object>> list(String keyword) {
        return list(keyword, null);
    }

    public Map<String, Object> view(Account account) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", account.id());
        out.put("username", account.username());
        out.put("displayName", account.displayName());
        out.put("role", account.role());
        out.put("roleLabel", Role.valueOf(account.role()).label());
        out.put("status", account.status());
        out.put("source", account.source());
        out.put("builtin", account.builtin());
        out.put("createdAt", account.createdAt());
        out.put("lastLoginAt", account.lastLoginAt());
        return out;
    }

    /**
     * 创建 / 更新用户的响应：旧字段之外带上**权威的角色绑定**（member_role）。
     *
     * <p>D18 之后 {@code POST /api/users} 接受内置角色码（REQ-DW-12 收敛为 5 个），而 {@code app_user.role}
     * 历史列受 CHECK 约束只能存 4 个粗粒度值；只回旧字段会让调用方以为请求的 AUDITOR
     * 被静默降级成 VIEWER。这里同时回 {@code roles / roleLabels / atoms / tenant}
     * （与 /api/auth/me 同口径：{@code roles} 是角色码、{@code roleLabels} 是角色名、
     * {@code atoms} 是该租户下实际生效的权限点、{@code tenant} 说明这些角色属于哪个租户），
     * 并把 {@code roleLabel} 改成真实绑定角色的名字。列表接口仍用 {@link #view(Account)}
     * 以免每行多两条查询。</p>
     */
    public Map<String, Object> viewWithRoles(Account account) {
        Map<String, Object> out = view(account);
        String tenant = rbac.defaultTenant(account.username());
        List<String> roles = tenant == null ? List.of() : rbac.roles(account.username(), tenant);
        Map<String, String> names = new LinkedHashMap<>();
        if (tenant != null) {
            for (Map<String, Object> row : rbac.rolesOfTenant(tenant)) {
                names.put(String.valueOf(row.get("code")), String.valueOf(row.get("name")));
            }
        }
        List<String> labels = roles.stream()
                .map(code -> names.getOrDefault(code, code)).toList();
        out.put("roles", roles);
        out.put("roleLabels", labels);
        out.put("tenant", tenant);
        // /api/auth/me 的口径：新界面读 roles + atoms，旧 role 字段只是历史列
        out.put("atoms", tenant == null ? List.of()
                : new ArrayList<>(permissions.atoms(account.username(), tenant)));
        if (!labels.isEmpty()) {
            out.put("roleLabel", String.join(" / ", labels));
        }
        return out;
    }

    // ---------- 登录 ----------

    public AuthResult authenticate(String username, String password) {
        Account account = find(username);
        if (account == null && isBuiltin(username)) {
            // 部署配置里的管理员用户名与种子行不一致时，首次登录补建，避免把自己锁在门外
            ensureBuiltinAdmin();
            account = find(username);
        }
        if (account == null) {
            PasswordHash.dummyVerify(password);
            return new AuthResult(null, AuthReason.NOT_FOUND);
        }
        if (!account.active()) {
            // P3-7：禁用账号也跑一次同成本哈希，避免通过响应时间区分
            // 「账号不存在」与「账号被禁用」。
            PasswordHash.dummyVerify(password);
            return new AuthResult(account, AuthReason.DISABLED);
        }
        if (account.builtin()) {
            boolean ok = constantEquals(password, builtinPassword);
            return new AuthResult(ok ? account : null, ok ? AuthReason.OK : AuthReason.BAD_PASSWORD);
        }
        String stored = jdbc.queryForObject(
                "select password_hash from platform.app_user where id = ?", String.class, account.id());
        boolean ok = PasswordHash.verify(password, stored);
        if (ok && PasswordHash.needsRehash(stored)) {
            // P3-9：登录时把低迭代次数的旧哈希透明升级到当前强度。
            // 升级失败不能影响本次登录，只记一条告警。
            try {
                jdbc.update("update platform.app_user set password_hash = ?, updated_at = now() "
                        + "where id = ?", PasswordHash.hash(password), account.id());
            } catch (RuntimeException e) {
                log.warn("口令哈希升级失败 user={}：{}", account.username(), e.getMessage());
            }
        }
        return new AuthResult(ok ? account : null, ok ? AuthReason.OK : AuthReason.BAD_PASSWORD);
    }

    /**
     * SSO 首次登录自动开户；已存在的 SSO 账号沿用管理员设置的角色。
     *
     * <p>默认不允许 SSO 隐式接管本地/内置账号：Keycloak 用户名是可变的，
     * 若直接按用户名匹配，IdP 里建一个 {@code admin} 就能拿到平台管理员。
     * 需要预置本地账号走 SSO 时显式打开
     * {@code platform.sso.allow-local-account-binding}。</p>
     */
    public Account ensureSsoUser(String username) {
        if (username == null || username.isBlank() || username.length() > 64) {
            return null;
        }
        Account existing = find(username);
        if (existing != null) {
            if (!existing.active()) {
                return null;
            }
            if (!ssoMayReuse(existing.source(), existing.builtin(), ssoAllowLocalBinding)) {
                return null;
            }
            return existing;
        }
        try {
            jdbc.update("""
                    insert into platform.app_user (username, display_name, role, status, source)
                    values (?, ?, 'VIEWER', 'ACTIVE', 'SSO')
                    """, username, username);
        } catch (DuplicateKeyException e) {
            // 两个标签页同时回调：另一个请求已经建好账号
        }
        rbac.attach(username, "default", "VIEWER");
        Account created = find(username);
        return created != null && created.active() ? created : null;
    }

    public void touchLogin(String username) {
        jdbc.update("update platform.app_user set last_login_at = now() where username = ?", username);
    }

    // ---------- 管理 ----------

    public Account create(String username, String displayName, String roleText, String password) {
        return create(username, displayName, roleText, password, null);
    }

    public Account create(String username, String displayName, String roleText, String password,
                          String tenantText) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw ApiException.badRequest("用户名需为 3–64 位字母、数字、点、下划线或短横线，且以字母或数字开头");
        }
        if (isBuiltin(name) || name.equalsIgnoreCase(builtinAdmin)) {
            throw ApiException.conflict("内置管理员账号已存在（用户名不区分大小写）");
        }
        if (find(name) != null) {
            throw ApiException.conflict("用户名已存在: " + name);
        }
        // D18：可用角色以 RBAC 内置角色表为准（每租户 5 个，REQ-DW-12 收敛），不再只认 Role 枚举的 4 个旧角色。
        String roleCode = normalizeRole(roleText);
        Role role = Role.legacyOf(roleCode);
        PasswordHash.validate(password);
        if (password.equalsIgnoreCase(name)) {
            throw ApiException.badRequest("密码不能与用户名相同");
        }
        String display = normalizeDisplay(displayName, name);
        String tenant = tenantText == null || tenantText.isBlank()
                ? "default" : tenantText.trim();
        if (!tenant.matches("[A-Za-z0-9_-]{1,64}")) {
            throw ApiException.badRequest("租户编码只能是 1-64 位字母、数字、下划线或短横线");
        }
        // 租户必须真实存在且启用：attach() 是 insert ... select，租户不存在时静默插 0 行，
        // 结果是一个「没有任何成员关系」的用户——登录会回落到 default 租户并凭旧 role
        // 拿到 READ（实测能读默认租户的项目与工作流）。必须在这里挡住。
        Integer tenantOk = jdbc.queryForObject("""
                select count(*) from platform.tenant where code = ? and status = 'ACTIVE'
                """, Integer.class, tenant);
        if (tenantOk == null || tenantOk == 0) {
            throw ApiException.badRequest("租户不存在或已停用: " + tenant);
        }
        requireBuiltinRole(roleCode, tenant);
        try {
            jdbc.update("""
                    insert into platform.app_user
                        (username, display_name, role, status, source, password_hash, tenant)
                    values (?, ?, ?, 'ACTIVE', 'LOCAL', ?, ?)
                    """, name, display, role.name(), PasswordHash.hash(password), tenant);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("用户名已存在: " + name);
        }
        // 权限模型第一期：新用户同时落 tenant_member / member_role（旧 role 列仍保留）。
        // 绑定的是**请求的内置角色码**（AUDITOR 等），不是粗粒度的旧枚举名，
        // 否则权限点会按旧角色发放，与 /api/rbac/roles 的口径不一致。
        rbac.attach(name, tenant, roleCode);
        permissions.invalidate(name);
        return find(name);
    }

    public Account update(long id, String displayName, String roleText, String statusText, String actor) {
        Account target = requireUser(id);
        if (target.builtin()) {
            throw ApiException.badRequest("内置管理员的角色和状态由部署配置固定，不能修改");
        }
        // D18：改角色与建用户同一口径——请求的角色码必须是该租户的内置角色。
        String attachTenant = rbac.defaultTenant(target.username());
        String roleCode = null;
        Role role;
        if (roleText == null) {
            role = target.roleEnum();
        } else {
            roleCode = normalizeRole(roleText);
            requireBuiltinRole(roleCode, attachTenant == null ? "default" : attachTenant);
            role = Role.legacyOf(roleCode);
        }
        String status = statusText == null ? target.status() : parseStatus(statusText);
        String display = displayName == null ? target.displayName() : normalizeDisplay(displayName, target.username());
        if (target.username().equals(actor) && (role != Role.ADMIN || !"ACTIVE".equals(status))) {
            throw ApiException.badRequest("不能降低或禁用当前登录账号自己的权限");
        }
        boolean losesActiveAdmin = target.roleEnum() == Role.ADMIN && "ACTIVE".equals(target.status())
                && (role != Role.ADMIN || !"ACTIVE".equals(status));
        if (losesActiveAdmin && countOtherActiveAdmins(target.id()) == 0) {
            throw ApiException.badRequest("至少保留一个可用的平台管理员");
        }
        jdbc.update("""
                update platform.app_user
                set display_name = ?, role = ?, status = ?, updated_at = now()
                where id = ?
                """, display, role.name(), status, id);
        if (!"ACTIVE".equals(status)) {
            // 禁用后立刻踢掉已有会话，不用等 token 过期
            jdbc.update("delete from platform.app_session where username = ?", target.username());
            revokeAllGrants(target.username(), actor, "账号被禁用");
        }
        // 权限模型第一期：角色变更同步到 member_role（该账号所属租户下的同名内置角色）。
        // 没有任何有效租户的账号（孤儿）不能借这次更新被重新挂回 default（D03）。
        // REQ-DW-12 附带修复：attach 是覆盖式（先清空该租户全部绑定再挂一个）。
        // 只在**显式改了角色**时才重绑；只改显示名/状态的 PUT 若照样 attach，
        // 会把 OWNER/SECURITY_ADMIN 等「历史列=VIEWER 桶」的用户降级成只读访客。
        if (attachTenant != null && roleCode != null) {
            rbac.attach(target.username(), attachTenant, roleCode);
        }
        permissions.invalidate(target.username());
        return requireUser(id);
    }

    /**
     * 用户自助修改密码：校验原密码、非内置管理员、新密码规则，改完吊销该用户全部会话。
     */
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        Account account = find(username);
        if (account == null) {
            throw ApiException.badRequest("账号不存在");
        }
        if (account.builtin()) {
            throw ApiException.badRequest("内置管理员密码由部署配置 PLATFORM_ADMIN_PASSWORD 管理");
        }
        AuthResult check = authenticate(username, oldPassword == null ? "" : oldPassword);
        if (check.reason() != AuthReason.OK) {
            throw ApiException.badRequest("原密码不正确");
        }
        PasswordHash.validate(newPassword);
        if (newPassword.equalsIgnoreCase(username)) {
            throw ApiException.badRequest("密码不能与用户名相同");
        }
        jdbc.update("""
                update platform.app_user set password_hash = ?, updated_at = now() where id = ?
                """, PasswordHash.hash(newPassword), account.id());
        jdbc.update("delete from platform.app_session where username = ?", username);
    }

    public void resetPassword(long id, String password) {
        Account target = requireUser(id);
        if (target.builtin()) {
            throw ApiException.badRequest("内置管理员密码由部署配置 PLATFORM_ADMIN_PASSWORD 管理");
        }
        PasswordHash.validate(password);
        if (password.equalsIgnoreCase(target.username())) {
            throw ApiException.badRequest("密码不能与用户名相同");
        }
        jdbc.update("""
                update platform.app_user set password_hash = ?, updated_at = now() where id = ?
                """, PasswordHash.hash(password), id);
        jdbc.update("delete from platform.app_session where username = ?", target.username());
    }

    /**
     * 删除账号：回收授权 → 删用户 → 清会话 → 清角色绑定。
     *
     * <p>D32：整条路径是一个原子操作。之前没有事务边界，「删用户成功但
     * 清会话/角色失败」会留下残留会话仍可用（安全问题，不只是脏数据）。
     * 现在任何一步失败整体回滚，异常原样抛给接口层（4xx/5xx 带失败原因）。
     * 注意 revokeAllGrants 的吞异常 catch 一并进了事务：PG 事务内一条语句
     * 失败即进入 aborted 态，后续删除必然失败回滚——这正是「不许留半套」
     * 的口径（S13 的 error 留痕保留，用于定位失败点）。</p>
     */
    @Transactional
    public void delete(long id, String actor) {
        Account target = requireUser(id);
        if (target.builtin()) {
            throw ApiException.badRequest("内置管理员账号不能删除");
        }
        if (target.username().equals(actor)) {
            throw ApiException.badRequest("不能删除当前登录账号");
        }
        if (target.roleEnum() == Role.ADMIN && "ACTIVE".equals(target.status())
                && countOtherActiveAdmins(target.id()) == 0) {
            throw ApiException.badRequest("至少保留一个可用的平台管理员");
        }
        // v469 F-P1-4：先回收授权再删账号（审计里还能按 username 追溯），
        // 并清掉角色绑定，避免同名重建继承任何残留。
        revokeAllGrants(target.username(), actor, "账号被删除");
        jdbc.update("delete from platform.app_user where id = ?", id);
        jdbc.update("delete from platform.app_session where username = ?", target.username());
        jdbc.update("delete from platform.member_role where user_id = ?", id);
        permissions.invalidate(target.username());
    }

    /**
     * v469 F-P1-4：级联撤销该用户**所有**仍生效的授权（APPROVED），逐条写审计。
     *
     * <p>删除/禁用账号时必须调用，否则授权行仍留在 `access_request` 里，
     * 同名重建或后续按 requester 判权时会继承旧权限。审计同时写资源租户，
     * 资源方能看到「这个人被删了，授权已回收」。</p>
     */
    public int revokeAllGrants(String username, String actor, String reason) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        try {
            // access_request 没有资源租户列，归属走 managed_table（catalog+schema+table）
            List<Map<String, Object>> revoked = jdbc.queryForList("""
                    update platform.access_request
                       set status = 'REVOKED', revoked_by = 'SYSTEM', revoked_at = now()
                     where requester = ? and status = 'APPROVED'
                    returning id, catalog, schema_name, table_name
                    """, username);
            for (Map<String, Object> grant : revoked) {
                String action = "ACCESS_REQUEST_REVOKE_ON_DELETE";
                String target = String.valueOf(grant.get("id"));
                String detail = reason + "：授权自动回收";
                // 授权已被上面的 update 收回，审计写失败绝不能中断循环（否则后面的授权
                // 回收就无声丢失）；S13 要求至少 error 级留痕。
                try {
                    sessions.audit(actor == null ? "system" : actor, action, target, detail);
                    String ownerTenant = jdbc.query("""
                            select tenant from platform.managed_table
                             where catalog = ? and schema_name = ? and table_name = ?
                             limit 1
                            """, rs -> rs.next() ? rs.getString(1) : null,
                            grant.get("catalog"), grant.get("schema_name"), grant.get("table_name"));
                    if (ownerTenant != null && !ownerTenant.isBlank()
                            && !ownerTenant.equals(TenantScope.current())) {
                        sessions.auditAs(ownerTenant, actor == null ? "system" : actor, action, target,
                                "资源租户视角：" + detail, "SUCCESS", null);
                    }
                } catch (RuntimeException e) {
                    log.error("账号级联回收审计写入失败 user={} requestId={}: {}",
                            username, target, e);
                }
            }
            return revoked.size();
        } catch (RuntimeException e) {
            // 回收失败不能阻断删除主流程，但必须留日志（S13：静默吞 = 无痕）
            log.error("revoke grants for {} failed: {}", username, e, e);
            return 0;
        }
    }

    public Account requireUser(long id) {
        Account account = findById(id);
        if (account == null) {
            throw ApiException.notFound("用户不存在: " + id);
        }
        return account;
    }

    public boolean isBuiltin(String username) {
        return builtinAdmin.equals(username);
    }

    /** SSO 是否可以复用既有账号：内置账号永远不行，本地账号需显式开关。 */
    static boolean ssoMayReuse(String source, boolean builtin, boolean allowLocalBinding) {
        if (builtin) {
            return false;
        }
        return "SSO".equals(source) || allowLocalBinding;
    }

    private int countOtherActiveAdmins(long exceptId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.app_user
                where role = 'ADMIN' and status = 'ACTIVE' and id <> ?
                """, Integer.class, exceptId);
        return n == null ? 0 : n;
    }

    private String parseStatus(String value) {
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("不支持的状态: " + value);
        }
        return status;
    }

    /** 角色码规范化：与 {@code RbacService.normalizeCode} 同一口径（大写、字母开头）。 */
    private static String normalizeRole(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw ApiException.badRequest("角色编码需为 2-64 位大写字母、数字或下划线");
        }
        return code;
    }

    /**
     * D18：建用户 / 改角色接受的角色码，必须存在于目标租户的**内置角色**里
     * （与 {@code GET /api/rbac/roles} 同一张表），不再只认 {@link Role} 枚举的 4 个旧角色。
     *
     * <p>为什么只收内置角色：{@code RbacService.attach()} 的绑定语句带
     * {@code r.builtin and r.status='ACTIVE'}，传自定义角色码会「一行都没插」却返回 200，
     * 账号变成能登录但处处 403（v449 P2 在成员角色接口上修过的同一个坑）。
     * 自定义角色请走 {@code POST /api/rbac/members/{userId}/roles}。</p>
     */
    private void requireBuiltinRole(String roleCode, String tenant) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.role r
                  join platform.tenant t on t.id = r.tenant_id
                 where t.code = ? and r.code = ? and r.builtin and r.status = 'ACTIVE'
                """, Integer.class, tenant, roleCode);
        if (n == null || n == 0) {
            throw ApiException.badRequest("不支持的角色: " + roleCode
                    + "（该租户没有这个内置角色；可用内置角色见 GET /api/rbac/roles，"
                    + "自定义角色用 POST /api/rbac/members/{userId}/roles 绑定）");
        }
    }

    private String normalizeDisplay(String displayName, String fallback) {
        if (displayName == null) {
            return fallback;
        }
        String value = displayName.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        if (value.length() > 128) {
            throw ApiException.badRequest("显示名不能超过 128 个字符");
        }
        return value;
    }

    private void ensureBuiltinAdmin() {
        jdbc.update("""
                insert into platform.app_user (username, display_name, role, status, source)
                values (?, '内置管理员', 'ADMIN', 'ACTIVE', 'LOCAL')
                on conflict (username) do nothing
                """, builtinAdmin);
        rbac.attach(builtinAdmin, "default", "ADMIN");
    }

    private static boolean constantEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                (a == null ? "" : a).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (b == null ? "" : b).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
