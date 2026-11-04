package com.lab.dataworks.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权限模型第一期只读接口：租户、角色、权限点目录。
 * 判定逻辑本期不变（仍走旧枚举），这些接口用于展示与迁移核对。
 */
@RestController
@RequestMapping("/api/rbac")
public class RbacController {

    private final RbacService rbac;
    private final PermissionResolver permissions;
    private final SessionService sessions;
    /** 离岗回收（D52）：移出租户要顺带收回他在这个租户拿到的生效授权。 */
    private final com.lab.dataworks.access.AccessRevokeService accessRevokes;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public RbacController(RbacService rbac, PermissionResolver permissions, SessionService sessions,
                          com.lab.dataworks.access.AccessRevokeService accessRevokes,
                          org.springframework.transaction.support.TransactionTemplate tx) {
        this.rbac = rbac;
        this.permissions = permissions;
        this.sessions = sessions;
        this.accessRevokes = accessRevokes;
        this.tx = tx;
    }

    /**
     * S3（2026-10-26 泳道 3 扫描 P1）：这里写的每一个动作都在「铸权限」，
     * 但整条 RBAC 链路原先一条审计都不留——实测建角色、改权限点、授角色、
     * 邀请/移除成员、建/删租户全部 200 生效，安全中心审计页搜不到 ROLE/MEMBER/TENANT。
     * 现在与 {@code /api/users}（USER_CREATE/USER_UPDATE/USER_DELETE）同一口径：
     * 每个写动作记一条，尽量带上「旧值 → 新值」，事后能回答「谁把什么权限给了谁」。
     */

    @GetMapping("/tenants")
    public Object tenants(HttpServletRequest request) {
        return rbac.tenants(username(request));
    }

    /** 当前用户在某个租户下的有效权限点与角色；不传 tenant 时取默认租户。 */
    @GetMapping("/permissions")
    public Map<String, Object> permissions(@RequestParam(required = false) String tenant,
                                           HttpServletRequest request) {
        String user = username(request);
        String code = tenant;
        if (code == null || code.isBlank()) {
            code = rbac.tenants(user).stream()
                    .filter(row -> Boolean.TRUE.equals(row.get("isDefault")))
                    .map(row -> String.valueOf(row.get("code")))
                    .findFirst().orElse("default");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", code);
        out.put("roles", rbac.roles(user, code));
        out.put("permissions", rbac.permissions(user, code));
        out.put("tenants", rbac.tenants(user));
        return out;
    }

    /** 权限点目录（给角色配置界面用）。 */
    @GetMapping("/catalog")
    @RequireAtom("role:read")
    public Object catalog() {
        return rbac.catalog();
    }

    /** 某租户的角色定义。 */
    @GetMapping("/roles")
    @RequireAtom("role:read")
    public Object roles(@RequestParam(required = false) String tenant,
                        HttpServletRequest request) {
        String code = tenant;
        if (code == null || code.isBlank()) {
            code = rbac.tenants(username(request)).stream()
                    .filter(row -> Boolean.TRUE.equals(row.get("isDefault")))
                    .map(row -> String.valueOf(row.get("code")))
                    .findFirst().orElse("default");
        }
        return rbac.rolesOfTenant(scopedTenant(code));
    }

    /**
     * 非默认租户只能操作自己的租户：传别人的 tenant 一律 404（v447）。
     * 不报 403 是为了不暴露「这个租户存在」。
     */
    private String scopedTenant(String requested) {
        String code = requested == null || requested.isBlank()
                ? TenantScope.current() : requested.trim();
        if (!com.lab.dataworks.common.TenantGuard.platform()
                && !code.equalsIgnoreCase(TenantScope.current())) {
            throw com.lab.dataworks.common.ApiException.notFound("租户不存在或无权访问: " + code);
        }
        return code;
    }

    public record RoleInput(String tenant, String code, String name,
                            java.util.List<String> permissions) {
    }

    /**
     * 成员角色提交体。BUG-TC7-G3-1：{@code clearAll} 是显式清空确认参数——
     * {@code roles} 为空时必须带 {@code clearAll=true} 才允许清空，否则 400。
     */
    public record MemberRoles(String tenant, java.util.List<String> roles, Boolean clearAll) {
    }

    /** 建自定义角色（内置角色不可建/改/删）。 */
    @org.springframework.web.bind.annotation.PostMapping("/roles")
    @RequireAtom("role:manage")
    public Map<String, Object> createRole(@org.springframework.web.bind.annotation.RequestBody
                                          RoleInput input,
                                          HttpServletRequest request) {
        String tenant = scopedTenant(input.tenant());
        // D20：角色编码/名称在入口做长度与格式校验，超长返回 400 而不是撞约束的 409
        String code = input.code() == null ? "" : input.code().trim();
        String roleName = input.name() == null ? "" : input.name().trim();
        if (!code.matches("[A-Za-z][A-Za-z0-9_]{1,31}")) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "角色编码需为 2-32 位字母、数字或下划线，且以字母开头");
        }
        if (roleName.isEmpty() || roleName.length() > 64) {
            throw com.lab.dataworks.common.ApiException.badRequest("角色名称需为 1-64 个字符");
        }
        rbac.createRole(tenant, code, roleName, input.permissions());
        sessions.audit(username(request), "ROLE_CREATE", tenant + "/" + input.code(),
                "名称=" + input.name() + " 权限点=" + join(input.permissions()));
        return Map.of("ok", true);
    }

    @org.springframework.web.bind.annotation.PutMapping("/roles/{code}")
    @RequireAtom("role:manage")
    public Map<String, Object> updateRole(@org.springframework.web.bind.annotation.PathVariable
                                          String code,
                                          @org.springframework.web.bind.annotation.RequestBody
                                          RoleInput input,
                                          HttpServletRequest request) {
        String tenant = scopedTenant(input.tenant());
        String before = rolePermissionsText(tenant, code);
        rbac.updateRole(tenant, code, input.name(), input.permissions());
        String detail = input.permissions() == null
                ? "仅改名称，权限点未提交（保持 " + before + "）"
                : "权限点 " + before + " → " + join(input.permissions());
        sessions.audit(username(request), "ROLE_UPDATE", tenant + "/" + code, detail);
        return Map.of("ok", true);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/roles/{code}")
    @RequireAtom("role:manage")
    public Map<String, Object> deleteRole(@org.springframework.web.bind.annotation.PathVariable
                                          String code,
                                          @RequestParam(required = false) String tenant,
                                          HttpServletRequest request) {
        String scoped = scopedTenant(tenant);
        String before = rolePermissionsText(scoped, code);
        // S13：删角色是破坏性动作——内置角色 / 仍有成员持有该角色时守卫会抛异常，
        // 原来「先做后记」一条都不留。改成先记后做，失败回填 FAILED。
        return sessions.auditAround(username(request), "ROLE_DELETE", scoped + "/" + code,
                "删除时该角色的权限点=" + before, AuthInterceptor.clientIp(request), () -> {
                    rbac.deleteRole(scoped, code);
                    return Map.of("ok", true);
                });
    }

    /** 租户成员及其角色。 */
    @GetMapping("/members")
    @RequireAtom("user:manage")
    public Object members(@RequestParam(required = false) String tenant) {
        return rbac.members(scopedTenant(tenant));
    }

    /**
     * 给成员设置该租户下的角色（BUG-TC7-G3-1：契约与实现对齐）。
     *
     * <p>提交体 {@code roles} 是<b>全量覆盖</b>：覆盖该租户下的全部角色，<b>含内置角色</b>，
     * 由管理员显式决定成员在这个租户里的角色集合。只传一个自定义角色码会摘掉该成员
     * 原有的内置角色；要清空全部角色（账号会变成「能登录、所有接口 403」的僵尸状态，
     * 所以必须带 {@code clearAll=true}）。</p>
     *
     * <p>调用方若要保留现有内置角色，必须把内置角色码一起提交
     * （先 GET /api/rbac/members 读回当前 roles，再在此基础上增删）。
     * 提交未知角色码会 400；{@code roles} 为空且没带 {@code clearAll=true} 也会 400——
     * 清空一个成员的全部角色是显式动作，不许顺手发生。</p>
     */
    @org.springframework.web.bind.annotation.PostMapping("/members/{userId}/roles")
    @RequireAtom("user:manage")
    public Map<String, Object> setMemberRoles(
            @org.springframework.web.bind.annotation.PathVariable long userId,
            @org.springframework.web.bind.annotation.RequestBody MemberRoles input,
            HttpServletRequest request) {
        String tenant = scopedTenant(input.tenant());
        String username = rbac.usernameOf(userId);
        if (username == null) {
            // v448（第二轮审计「待确认 1」）：只能改本租户成员的租户内角色。
            // 之前对任意 userId 都写 member_role，等于绕过成员关系给外部账号发权限。
            throw com.lab.dataworks.common.ApiException.notFound("用户不存在: " + userId);
        }
        boolean member = rbac.members(tenant).stream()
                .anyMatch(row -> userId == ((Number) row.get("userId")).longValue());
        if (!member) {
            throw com.lab.dataworks.common.ApiException.notFound(
                    "用户不是该租户的成员: " + userId);
        }
        String before = memberRolesText(tenant, userId);
        rbac.setRoles(username(request), username, tenant, input.roles(),
                Boolean.TRUE.equals(input.clearAll()));
        permissions.invalidate(username);
        sessions.audit(username(request), "MEMBER_ROLE_UPDATE", tenant + "/" + username,
                "角色 " + before + " → " + join(input.roles()));
        return Map.of("ok", true);
    }

    // ---------- 租户管理（第四期） ----------

    public record TenantInput(String code, String name) {
    }

    public record MemberInvite(Long userId, java.util.List<String> roles) {
    }

    /** 平台级租户清单（含成员数、业务数据量），用于管理页。 */
    @GetMapping("/all-tenants")
    @RequireAtom("tenant:manage")
    public Object allTenants() {
        return rbac.allTenants();
    }

    /** 建租户：自动复制 8 个内置角色，创建者成为该租户 ADMIN。 */
    @org.springframework.web.bind.annotation.PostMapping("/tenants")
    @RequireAtom("tenant:manage")
    public Map<String, Object> createTenant(
            @org.springframework.web.bind.annotation.RequestBody TenantInput input,
            HttpServletRequest request) {
        if (input == null) {
            throw com.lab.dataworks.common.ApiException.badRequest("请求体不能为空");
        }
        String actor = username(request);
        rbac.createTenant(input.code(), input.name(), actor);
        permissions.invalidateAll();
        sessions.audit(actor, "TENANT_CREATE", String.valueOf(input.code()).trim(),
                "名称=" + input.name() + " 创建者成为该租户 ADMIN（复制 default 的内置角色）");
        return Map.of("ok", true, "code", input.code() == null
                ? "" : input.code().trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 删除租户。
     *
     * <p>D54：必须带 {@code ?confirm=<租户code>}（去空格、忽略大小写后完全一致），否则 400。
     * 一个租户下面挂着成员关系、租户内角色与项目归属，误点一次就是整片下线，
     * 这种代价不该由一次点击决定。护栏放在后端：绕过界面直接发请求也拦得住，
     * 只有界面弹窗（要求手输 code）才能凑齐这个参数。</p>
     *
     * <p>原有前置校验不变：{@code rbac.deleteTenant} 仍要求「无成员、无业务数据」。</p>
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/tenants/{code}")
    @RequireAtom("tenant:manage")
    public Map<String, Object> deleteTenant(
            @org.springframework.web.bind.annotation.PathVariable String code,
            @RequestParam(required = false) String confirm,
            HttpServletRequest request) {
        requireConfirmCode(code, confirm);
        String name = tenantName(code);
        // S13：删租户的守卫（有成员 / 有业务数据 → 409）原来抛异常就无痕；
        // 先记后做，失败也落 FAILED，权限缓存失效放在动作成功之后。
        return sessions.auditAround(username(request), "TENANT_DELETE", code,
                "名称=" + name + "（删除前已校验：无成员、无业务数据；confirm 已核对）",
                AuthInterceptor.clientIp(request), () -> {
                    rbac.deleteTenant(code);
                    permissions.invalidateAll();
                    return Map.of("ok", true);
                });
    }

    /** D54 高危确认：{@code confirm} 必须与目标 code 一致（忽略大小写与首尾空格）。 */
    static void requireConfirmCode(String code, String confirm) {
        if (confirm == null || confirm.trim().isEmpty()) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "删除租户必须带 confirm=<租户编码>，用于确认不是误点（本次请求没有该参数）");
        }
        if (!confirm.trim().equalsIgnoreCase(String.valueOf(code).trim())) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "confirm 与租户编码不一致：需要 " + String.valueOf(code).trim()
                            + "，收到 " + confirm.trim());
        }
    }

    /** 邀请已有账号加入租户并设置角色（覆盖该租户下的全部角色）。 */
    @org.springframework.web.bind.annotation.PostMapping("/tenants/{code}/members")
    @RequireAtom("tenant:manage")
    public Map<String, Object> addMember(
            @org.springframework.web.bind.annotation.PathVariable String code,
            @org.springframework.web.bind.annotation.RequestBody MemberInvite input,
            HttpServletRequest request) {
        if (input == null || input.userId() == null) {
            throw com.lab.dataworks.common.ApiException.badRequest("userId 不能为空");
        }
        String userName = rbac.usernameOf(input.userId());
        if (userName == null) {
            throw com.lab.dataworks.common.ApiException.notFound("用户不存在: " + input.userId());
        }
        String before = memberRolesText(code, input.userId());
        rbac.addMember(username(request), code, userName, input.roles());
        permissions.invalidate(userName);
        sessions.audit(username(request), "TENANT_MEMBER_ADD", code + "/" + userName,
                "成员角色 " + before + " → " + join(input.roles()));
        return Map.of("ok", true, "tenant", code, "username", userName);
    }

    /**
     * 移除租户成员。删除租户前必须先移除成员（D03 的删除守卫），
     * 否则租户一旦创建就永远删不掉。
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/tenants/{code}/members/{userId}")
    @RequireAtom("tenant:manage")
    public Map<String, Object> removeMember(
            @org.springframework.web.bind.annotation.PathVariable String code,
            @org.springframework.web.bind.annotation.PathVariable long userId,
            HttpServletRequest request) {
        String userName = rbac.usernameOf(userId);
        if (userName == null) {
            throw com.lab.dataworks.common.ApiException.notFound("用户不存在: " + userId);
        }
        String before = memberRolesText(code, userId);
        // S13：移除成员会连带撤掉该成员在本租户的全部授权，守卫失败原来无痕；
        // 先记后做，失败回填 FAILED，detail 保留「移除前的角色」便于事后追责。
        // D52：移出租户与授权回收必须同事务，分两步会出现「人已移出、授权还在」的窗口——
        // 判定侧只看 access_request 的 status，不复查他是否还在租户里。回收条数走
        // outcomeDetail 追加，先记的 PENDING 里没有这个数字。
        final String actor = username(request);
        return sessions.auditAround(actor, "TENANT_MEMBER_REMOVE", code + "/" + userName,
                "移除前该成员在本租户的角色=" + before, AuthInterceptor.clientIp(request), () -> {
                    Integer revoked = tx.execute(ignored -> {
                        rbac.removeMember(code, userId);
                        return accessRevokes.revokeForTenant(userName,
                                code.toLowerCase(java.util.Locale.ROOT), actor, "移出租户 " + code);
                    });
                    permissions.invalidate(userName);
                    int count = revoked == null ? 0 : revoked;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("tenant", code.toLowerCase(java.util.Locale.ROOT));
                    out.put("username", userName);
                    out.put("revokedGrants", count);
                    return out;
                },
                out -> "自动回收该租户下的生效授权 " + out.get("revokedGrants") + " 条");
    }

    /** 审计用的旧值：某自定义角色当前的权限点集合文本；角色不存在时返回 {@code (无)}。 */
    private String rolePermissionsText(String tenantCode, String roleCode) {
        return rbac.rolesOfTenant(tenantCode).stream()
                .filter(row -> roleCode == null || String.valueOf(row.get("code"))
                        .equalsIgnoreCase(String.valueOf(roleCode).trim()))
                .findFirst()
                .map(row -> join(row.get("permissions")))
                .orElse("(无)");
    }

    /** 审计用的旧值：某成员在该租户下的角色文本；不是成员时返回 {@code (无)}。 */
    private String memberRolesText(String tenantCode, long userId) {
        return rbac.members(tenantCode).stream()
                .filter(row -> row.get("userId") instanceof Number n && n.longValue() == userId)
                .findFirst()
                .map(row -> join(row.get("roles")))
                .orElse("(无)");
    }

    private String tenantName(String code) {
        return rbac.allTenants().stream()
                .filter(row -> String.valueOf(row.get("code")).equalsIgnoreCase(String.valueOf(code)))
                .map(row -> String.valueOf(row.get("name")))
                .findFirst().orElse("");
    }

    /** 集合/字符串转成审计里可读的一行；null 显示成 (无)。 */
    private static String join(Object value) {
        if (value == null) {
            return "(无)";
        }
        if (value instanceof java.util.Collection<?> items) {
            return items.isEmpty() ? "(空)" : String.join(",", items.stream()
                    .map(String::valueOf).sorted().toList());
        }
        return String.valueOf(value);
    }

    private static String join(java.util.List<String> values) {
        return values == null ? "(无)" : (values.isEmpty() ? "(空)"
                : String.join(",", values.stream().filter(java.util.Objects::nonNull)
                        .sorted().toList()));
    }

    private static String username(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("username"));
    }
}
