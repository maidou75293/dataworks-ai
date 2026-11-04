package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 5 * 60 * 1000L;

    private final SessionService sessions;
    private final UserService users;
    private final IdentityPayload identity;
    private final PermissionResolver permissions;
    private final RbacService rbac;
    /** username|ip → 失败次数，防在线爆破；只存内存，重启即清空。 */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong attemptWrites =
            new java.util.concurrent.atomic.AtomicLong();

    public AuthController(SessionService sessions, UserService users, IdentityPayload identity,
                          PermissionResolver permissions, RbacService rbac) {
        this.sessions = sessions;
        this.users = users;
        this.identity = identity;
        this.permissions = permissions;
        this.rbac = rbac;
    }

    /**
     * @param tenant 可选：登录即切到该租户（必须是成员）。不传则用默认租户。
     */
    public record LoginRequest(String username, String password, String tenant) {
    }

    public record SwitchTenantRequest(String tenant) {
    }

    private static final class Attempt {
        private int count;
        private long windowStart = System.currentTimeMillis();
        private long lockedUntil;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String user = req.username() == null ? "" : req.username().trim();
        String pass = req.password() == null ? "" : req.password();
        String ip = AuthInterceptor.clientIp(request);
        // 未登录接口先做长度上限：2MB 用户名会让失败计数 Map 与审计表膨胀，
        // 同时每次都跑一遍 PBKDF2（未知用户 dummyVerify），构成 CPU DoS。
        if (user.isEmpty() || user.length() > 64 || pass.length() > 256) {
            sessions.audit("-", "LOGIN_FAILED", "platform",
                    user.length() > 64 ? "用户名或密码超出长度限制" : "用户名为空", "FAILED", ip);
            throw new ApiException(401, "用户名或密码错误");
        }
        String key = user.toLowerCase(java.util.Locale.ROOT) + "|" + ip;
        long locked = lockRemaining(key);
        if (locked > 0) {
            sessions.audit(user, "LOGIN_FAILED", "platform",
                    "登录失败次数过多，已临时锁定", "FAILED", ip);
            throw new ApiException(429, "登录失败次数过多，请 5 分钟后再试");
        }
        UserService.AuthResult result = users.authenticate(user, pass);
        if (result.reason() != UserService.AuthReason.OK) {
            recordFailure(key);
            // P3-7：禁用账号与未知账号/密码错误统一返回 401 同样文案，
            // 避免外部按状态码枚举平台账号。具体原因只写审计，管理员可见。
            String detail = result.reason() == UserService.AuthReason.DISABLED
                    ? "账号已禁用" : "用户名或密码错误";
            sessions.audit(user, "LOGIN_FAILED", "platform", detail, "FAILED", ip);
            throw new ApiException(401, "用户名或密码错误");
        }
        attempts.remove(key);
        // D03：租户被删除后成员关系已级联清空，账号不能再登录；旧实现会回落 default。
        if (!rbac.hasActiveTenant(user)) {
            sessions.audit(user, "LOGIN_FAILED", "platform",
                    "账号不属于任何有效租户", "FAILED", ip);
            throw new ApiException(401, "账号不属于任何有效租户，请联系平台管理员");
        }
        users.touchLogin(user);
        sessions.cleanup();
        // v457 MT-P1-1：登录可带 tenant；校验成员关系后把租户绑进会话
        String targetTenant = null;
        if (req.tenant() != null && !req.tenant().isBlank()) {
            targetTenant = permissions.resolveTenant(user, req.tenant().trim());
            if (targetTenant == null) {
                sessions.audit(user, "ACCESS_DENIED", "tenant:" + req.tenant(),
                        "登录时请求了不属于自己的租户", "FAILED", ip);
                throw new ApiException(403, "当前账号不属于该租户：" + req.tenant());
            }
        }
        if (targetTenant != null) {
            TenantScope.set(targetTenant);
        }
        String token = sessions.create(user, targetTenant);
        sessions.audit(user, "LOGIN", "platform",
                targetTenant == null ? null : "tenant=" + targetTenant, "SUCCESS", ip);
        return identity.login(token, result.account(), targetTenant);
    }

    /**
     * 切换当前会话的租户（v457 MT-P1-1）。
     *
     * <p>签发一个绑定新租户的新 token 并吊销旧 token：之后所有请求都由会话里的租户
     * 决定上下文，刷新页面也不会回到默认租户。</p>
     */
    @PostMapping("/switch-tenant")
    public Map<String, Object> switchTenant(@RequestBody SwitchTenantRequest req,
                                            HttpServletRequest request) {
        String username = String.valueOf(request.getAttribute("username"));
        if (req == null || req.tenant() == null || req.tenant().isBlank()) {
            throw ApiException.badRequest("tenant 不能为空");
        }
        UserService.Account account = users.find(username);
        if (account == null) {
            throw new ApiException(401, "账号不存在，请重新登录");
        }
        String target = permissions.resolveTenant(username, req.tenant().trim());
        if (target == null) {
            sessions.audit(username, "ACCESS_DENIED", "tenant:" + req.tenant(),
                    "切换到不属于自己的租户", "FAILED", AuthInterceptor.clientIp(request));
            throw new ApiException(403, "当前账号不属于该租户：" + req.tenant());
        }
        String from = TenantScope.current();
        // 旧 token 立刻失效，避免两个租户的会话同时有效（切换语义）
        sessions.revoke(AuthInterceptor.bearer(request));
        TenantScope.set(target);
        String token = sessions.create(username, target);
        sessions.audit(username, "TENANT_SWITCH", target, "from=" + from, "SUCCESS",
                AuthInterceptor.clientIp(request));
        return identity.login(token, account, target);
    }

    public record PasswordChange(String oldPassword, String newPassword) {
    }

    /** 当前登录用户自助修改密码；成功后所有会话失效，需要重新登录。 */
    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordChange req,
                                              HttpServletRequest request) {
        Object username = request.getAttribute("username");
        if (username == null) {
            throw new ApiException(401, "请先登录");
        }
        if (req == null || req.oldPassword() == null || req.newPassword() == null) {
            throw ApiException.badRequest("原密码和新密码不能为空");
        }
        users.changeOwnPassword(String.valueOf(username), req.oldPassword(), req.newPassword());
        // S12：detail 不落空——改密会吊销全部会话，这个后果要能在审计里看出来
        sessions.audit(String.valueOf(username), "PASSWORD_CHANGE", "platform",
                "本人自助改密；该账号全部会话已吊销，需重新登录",
                "SUCCESS", AuthInterceptor.clientIp(request));
        return Map.of("ok", true, "relogin", true);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String token = AuthInterceptor.bearer(request);
        sessions.revoke(token);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        String username = String.valueOf(request.getAttribute("username"));
        UserService.Account account = users.find(username);
        if (account == null) {
            throw new ApiException(401, "账号不存在，请重新登录");
        }
        return identity.describe(account);
    }

    private long lockRemaining(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return 0;
        }
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (attempt.lockedUntil > now) {
                return attempt.lockedUntil - now;
            }
            if (now - attempt.windowStart > WINDOW_MS) {
                attempt.count = 0;
                attempt.windowStart = now;
            }
            return 0;
        }
    }

    private void recordFailure(String key) {
        Attempt attempt = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (now - attempt.windowStart > WINDOW_MS) {
                attempt.count = 0;
                attempt.windowStart = now;
            }
            attempt.count++;
            if (attempt.count >= MAX_FAILURES) {
                attempt.lockedUntil = now + WINDOW_MS;
            }
        }
        // 每次失败都带不同的用户名/IP 会让 Map 无限增长；定期回收已过窗口的记录
        if (attemptWrites.incrementAndGet() % 256 == 0) {
            cleanupAttempts();
        }
    }

    private void cleanupAttempts() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> {
            Attempt attempt = entry.getValue();
            synchronized (attempt) {
                return attempt.lockedUntil <= now && now - attempt.windowStart > WINDOW_MS;
            }
        });
        // 极端情况下仍有大量活跃键：只丢弃未锁定且计数为 1 的记录，保证锁定语义
        if (attempts.size() > 20_000) {
            attempts.entrySet().removeIf(entry -> {
                Attempt attempt = entry.getValue();
                synchronized (attempt) {
                    return attempt.lockedUntil <= now && attempt.count <= 1;
                }
            });
        }
    }
}
