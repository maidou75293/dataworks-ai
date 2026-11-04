package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户与权限管理。
 *
 * <p>平台管理员（默认租户）管理所有账号；其他租户的 ADMIN 只管理本租户成员
 * ——v447 前这里按权限点放行，tenant_b 的 ADMIN 可以重置别的租户用户的口令。</p>
 */
@RestController
@RequestMapping("/api/users")
@RequireAtom("user:manage")
public class UserController {

    private final UserService users;
    private final SessionService sessions;
    private final TenantGuard guard;
    private final RbacService rbac;

    public UserController(UserService users, SessionService sessions, TenantGuard guard,
                          RbacService rbac) {
        this.users = users;
        this.sessions = sessions;
        this.guard = guard;
        this.rbac = rbac;
    }

    public record CreateUserRequest(String username, String displayName, String role, String password,
                                    String tenant) {
    }

    public record UpdateUserRequest(String displayName, String role, String status) {
    }

    public record PasswordRequest(String password) {
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword) {
        // v448：过滤下沉到 SQL。之前是全表查出再用 userInTenant 过滤，属于先查后判，
        // 谁复用 users.list() 就会把其他租户的账号带出去（第二轮审计 P0-1 的另一半）。
        return users.list(keyword, TenantGuard.platform() ? null : TenantGuard.tenant());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateUserRequest req, HttpServletRequest request) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        // 只能往自己的租户建人；默认租户（平台管理员）可选目标租户
        String tenant = req.tenant();
        if (!TenantGuard.platform()) {
            if (tenant != null && !tenant.isBlank()
                    && !tenant.trim().equalsIgnoreCase(TenantGuard.tenant())) {
                throw new ApiException(403, "只能在本租户下创建用户");
            }
            tenant = TenantGuard.tenant();
        }
        requireRoleGrantable(req.role(), actor(request));
        UserService.Account account = users.create(
                req.username(), req.displayName(), req.role(), req.password(), tenant);
        // 审计记「请求授予的角色码」，不是粗粒度历史列的值（否则授予 AUDITOR 会记成 VIEWER）
        sessions.audit(actor(request), "USER_CREATE", account.username(),
                "角色=" + (req.role() == null ? account.role() : req.role()),
                "SUCCESS", AuthInterceptor.clientIp(request));
        // D18：响应里带真实绑定的角色码（app_user.role 是受约束的粗粒度历史列）
        return users.viewWithRoles(account);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody UpdateUserRequest req,
                                      HttpServletRequest request) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        UserService.Account before = ownedUser(id);
        requireRoleGrantable(req.role(), actor(request));
        UserService.Account after = users.update(
                id, req.displayName(), req.role(), req.status(), actor(request));
        sessions.audit(actor(request), "USER_UPDATE", after.username(),
                before.role() + "/" + before.status() + " → "
                        + (req.role() == null ? after.role() : req.role()) + "/" + after.status(),
                "SUCCESS", AuthInterceptor.clientIp(request));
        // D18：响应里带真实绑定的角色码（app_user.role 是受约束的粗粒度历史列）
        return users.viewWithRoles(after);
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id, @RequestBody PasswordRequest req,
                                             HttpServletRequest request) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        UserService.Account target = ownedUser(id);
        users.resetPassword(id, req.password());
        sessions.audit(actor(request), "USER_PASSWORD_RESET", target.username(),
                "管理员重置口令，已吊销该用户全部会话", "SUCCESS", AuthInterceptor.clientIp(request));
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        UserService.Account target = ownedUser(id);
        // S13：删用户会级联回收授权（revokeAllGrants），原来是「先做后记」——
        // 底层一旦抛异常（外键约束、级联回收失败）就一条审计都不留。改成先记后做，
        // 失败回填 FAILED；与删数据源 / 删项目 / 删 UDF 同一口径。
        String actor = actor(request);
        return sessions.auditAround(actor, "USER_DELETE", target.username(),
                "角色=" + target.role(), AuthInterceptor.clientIp(request), () -> {
                    users.delete(id, actor);
                    return Map.of("ok", true);
                });
    }

    /**
     * 授予 ADMIN 等同「平台管理员入口」：内置 ADMIN 角色在每个租户各有一份，
     * 非默认租户的管理员把本租户用户提成 ADMIN 后，该账号仍然只能在本租户内操作
     * 平台级接口会被拦截，但审计要求「ADMIN 只由平台管理员授予」，所以这里直接 403。
     */
    private void requireRoleGrantable(String role, String actor) {
        // D02：与 /api/rbac/members/{id}/roles 共用同一套「不得越权授权」规则：
        // ADMIN 仅平台管理员可授，其余角色必须权限点 ⊆ 调用方当前租户权限点。
        if (role == null || role.isBlank()) {
            return;
        }
        rbac.assertCanGrant(actor, TenantScope.current(), List.of(role));
    }

    /** 目标用户必须存在，且（非默认租户时）是当前租户的成员，否则 404。 */
    private UserService.Account ownedUser(long id) {
        UserService.Account account = users.requireUser(id);
        if (!guard.userInTenant(account.username())
                || !guard.userExclusiveToCurrentTenant(account.username())) {
            throw ApiException.notFound("用户不存在: " + id);
        }
        return account;
    }

    private String actor(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("username"));
    }
}
