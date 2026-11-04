package com.lab.dataworks.integration;

import com.lab.dataworks.auth.RbacService;
import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 助手内部接口的统一入口校验：内部凭证 → 账号 → 租户成员 → 权限点（看模式）。
 *
 * <p>工具名与权限点的映射是唯一事实来源，前端展示「助手现在能做什么」、
 * 后端判定、agent-service 过滤工具都读这里。</p>
 *
 * <p>SYSTEM 模式跳过权限点判定，但**不跳过租户**：租户是数据边界。</p>
 */
@Component
public class AgentAccess {

    /** 工具名（agent-service registry 里的 tool 字段）→ 需要的权限点。 */
    public static final Map<String, String> TOOL_ATOMS;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("list_datasources", "datasource:read");
        map.put("list_sync_tasks", "sync:read");
        map.put("get_sync_task_status", "sync:read");
        map.put("query_lineage", "lineage:read");
        map.put("list_quality_results", "quality:read");
        map.put("list_catalog_tables", "catalog:read");
        map.put("request_change", "agent:action:request");
        map.put("request_access", "access:request");
        TOOL_ATOMS = Map.copyOf(map);
    }

    public record Caller(String tenant, String user, String mode, boolean knownAccount) {
    }

    private final JdbcTemplate jdbc;
    private final RbacService rbac;
    private final AgentSettings settings;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;

    public AgentAccess(JdbcTemplate jdbc, RbacService rbac, AgentSettings settings,
                       com.lab.dataworks.auth.InternalTokenService internalTokens) {
        this.jdbc = jdbc;
        this.rbac = rbac;
        this.settings = settings;
        this.internalTokens = internalTokens;
    }

    public String identityMode() {
        return settings.identityMode();
    }

    /**
     * 校验内部调用，返回调用者上下文。
     *
     * @param atom 需要的权限点；null 表示不判权限点（只校验凭证/账号/租户）
     */
    public Caller check(String token, String tenant, String user, String atom) {
        checkToken(token);
        if (user == null || user.isBlank()) {
            throw ApiException.badRequest("缺少 X-User-Id，工具调用必须可追溯");
        }
        String code = tenant == null || tenant.isBlank() ? AgentTenant.DEFAULT : tenant.trim();
        String mode = settings.identityMode();

        Boolean known = knownAccountActive(user);
        if (known == null) {
            // MCP 等系统调用方用不对应平台账号的用户名：内部凭证兜底（与写接口保持一致）
            if (atom != null && AgentSettings.DELEGATE.equals(mode)) {
                throw new ApiException(403, "助手当前为「跟随用户权限」模式，"
                        + "无法确认用户 " + user + " 的身份，已拒绝");
            }
            return new Caller(code, user, mode, false);
        }
        if (!known) {
            throw new ApiException(403, "账号不存在或已被禁用: " + user);
        }
        if (!member(user, code)) {
            throw new ApiException(403, "用户 " + user + " 不属于租户 " + code);
        }
        Caller caller = new Caller(code, user, mode, true);
        if (atom != null && AgentSettings.DELEGATE.equals(mode)) {
            requireAtom(caller, atom);
        }
        return caller;
    }

    /**
     * 无条件按权限点判定（写操作用：写操作不受「全权测试」开关影响）。
     * 非平台账号（MCP 系统调用方）按内部凭证处理，保持原有行为。
     */
    public void requireAtom(Caller caller, String atom) {
        if (!caller.knownAccount()) {
            return;
        }
        Set<String> atoms = rbac.permissions(caller.user(), caller.tenant());
        if (!atoms.contains(atom)) {
            throw new ApiException(403, "用户 " + caller.user()
                    + " 缺少权限点 " + atom + "，无法执行该操作");
        }
    }

    /**
     * P2-28：调用方透传的 projectId 必须属于当前租户，跨租户统一 404
     * （不暴露「项目存在但你没权限」这一信息）。
     *
     * <p>同租户内的项目成员隔离需要 {@code project_member} 模型，属产品待定义项；
     * 这里先把租户边界收口，避免助手内部接口成为跨租户读数据的旁路。</p>
     */
    public void requireProject(Caller caller, long projectId) {
        requireProject(caller.tenant(), projectId);
    }

    /** 浏览器会话（已由 AuthInterceptor 解析出租户）同样要校验项目归属。 */
    public void requireProject(String tenant, long projectId) {
        String code = tenant == null || tenant.isBlank() ? "default" : tenant;
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Integer.class, projectId, code);
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    /** 当前模式下这个用户在租户里可用的助手工具（返回给前端与 agent-service 过滤）。 */
    public List<String> allowedTools(String user, String tenant) {
        List<String> all = new ArrayList<>(TOOL_ATOMS.keySet());
        all.sort(String::compareTo);
        if (AgentSettings.SYSTEM.equals(settings.identityMode())) {
            return all;
        }
        Set<String> atoms = rbac.permissions(user, tenant);
        return all.stream()
                .filter(tool -> atoms.contains(TOOL_ATOMS.get(tool)))
                .toList();
    }

    private void checkToken(String token) {
        if (!internalTokens.configured()) {
            throw new ApiException(503, "内部接口未启用（PLATFORM_INTERNAL_TOKEN 未配置）");
        }
        if (!internalTokens.matches(token)) {
            throw new ApiException(401, "内部凭证无效");
        }
    }

    /** null = 不是平台账号（系统调用方），true/false = 存在且是否可用。 */
    private Boolean knownAccountActive(String user) {
        List<String> rows = jdbc.queryForList(
                "select status from platform.app_user where username = ?", String.class, user);
        if (rows.isEmpty()) {
            return null;
        }
        return "ACTIVE".equalsIgnoreCase(rows.get(0));
    }

    private boolean member(String user, String tenant) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.app_user u
                join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                join platform.tenant t on t.id = tm.tenant_id and t.status = 'ACTIVE'
                where u.username = ? and t.code = ?
                """, Integer.class, user, tenant);
        return n != null && n > 0;
    }

    /** 租户常量的本地别名，避免 integration 包对 auth 包的静态依赖过深。 */
    private static final class AgentTenant {
        static final String DEFAULT = "default";
    }
}
