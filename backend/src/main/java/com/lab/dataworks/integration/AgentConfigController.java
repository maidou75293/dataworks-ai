package com.lab.dataworks.integration;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RbacService;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 助手权限模式开关（页面上那个小开关）与「现在能做什么」。
 *
 * <p>开关本身只影响助手：SYSTEM 全权（测试用）／DELEGATE 跟随使用者权限。
 * 需要 {@code agent:config}（默认只有内置 ADMIN）才能切换，避免普通用户自己
 * 把助手切成全权再越权查数。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentConfigController {

    private final AgentSettings settings;
    private final AgentAccess access;
    private final RbacService rbac;
    private final PermissionResolver permissions;
    private final SessionService sessions;
    private final TenantGuard guard;

    public AgentConfigController(AgentSettings settings, AgentAccess access,
                                 RbacService rbac, PermissionResolver permissions,
                                 SessionService sessions, TenantGuard guard) {
        this.settings = settings;
        this.access = access;
        this.rbac = rbac;
        this.permissions = permissions;
        this.sessions = sessions;
        this.guard = guard;
    }

    public record ConfigInput(String identityMode) {
    }

    @GetMapping("/config")
    @RequireAtom("agent:chat")
    public Map<String, Object> config(HttpServletRequest request) {
        String user = String.valueOf(request.getAttribute("username"));
        String tenant = TenantScope.of(request);
        String mode = settings.identityMode();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identityMode", mode);
        out.put("tenant", tenant);
        out.put("modes", List.of(
                Map.of("code", AgentSettings.SYSTEM,
                        "name", "全权（测试）",
                        "description", "助手不受使用者权限限制，可查租户内全部数据。仅用于开发联调。"),
                Map.of("code", AgentSettings.DELEGATE,
                        "name", "跟随使用者权限",
                        "description", "助手只能做使用者本人有权限的事，缺权限点会被拒绝。")));
        out.put("allowedTools", access.allowedTools(user, tenant));
        out.put("toolAtoms", AgentAccess.TOOL_ATOMS);
        // v449 P0-2：身份模式是全局单例，只有平台管理员能改
        out.put("canConfigure", TenantGuard.platform()
                && (permissions.atoms(user).contains("agent:config")
                    || rbac.permissions(user, tenant).contains("agent:config")));
        return out;
    }

    @PutMapping("/config")
    @RequireAtom("agent:config")
    public Map<String, Object> update(@RequestBody ConfigInput input, HttpServletRequest request) {
        if (input == null || input.identityMode() == null || input.identityMode().isBlank()) {
            throw ApiException.badRequest("identityMode 不能为空");
        }
        // v449 P0-2：写入的是全局单例 AgentSettings（system_config），不是租户级配置。
        // 之前只校验 agent:config，内置 ADMIN 在每个租户都有一份 → 任意租户 ADMIN
        // 都能把全平台助手从 DELEGATE（跟随使用者）切成 SYSTEM（全权）。
        guard.requirePlatform();
        String actor = String.valueOf(request.getAttribute("username"));
        String before = settings.identityMode();
        settings.setIdentityMode(input.identityMode(), actor);
        sessions.audit(actor, "AGENT_IDENTITY_MODE", "platform",
                before + " → " + settings.identityMode(), "SUCCESS",
                AuthInterceptor.clientIp(request));
        return Map.of("ok", true, "identityMode", settings.identityMode(), "updatedBy", actor);
    }
}
