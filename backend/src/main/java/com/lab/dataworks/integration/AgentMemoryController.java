package com.lab.dataworks.integration;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话与记忆的浏览器接口。
 *
 * <p>消息、槽位由 agent-service 通过 /api/internal/agent/* 写入；
 * 前端只读会话列表 / 消息，并管理「记忆」。
 */
@RestController
@RequestMapping("/api/agent")
@RequireAtom("agent:chat")
public class AgentMemoryController {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;

    public AgentMemoryController(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    public record MemoryRequest(String scope, Long projectId, String key, String value,
                                String expiresAt, String memType, String kind, String subtype,
                                String condition) {
    }

    public record RenameRequest(String title) {
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest http) {
        String user = user(http);
        String tenant = tenantOf(user);
        int cap = Math.max(1, Math.min(limit, 100));
        if (projectId != null) {
            return jdbc.queryForList("""
                    select c.session_id as "sessionId", c.title, c.project_id as "projectId",
                           c.created_at as "createdAt", c.last_message_at as "lastMessageAt",
                           (select count(*) from platform.agent_message m
                             where m.session_id = c.session_id) as "messageCount"
                    from platform.agent_conversation c
                    where c.tenant_id = ? and c.username = ? and c.project_id = ?
                    order by coalesce(c.last_message_at, c.created_at) desc
                    limit ?
                    """, tenant, user, projectId, cap);
        }
        return jdbc.queryForList("""
                select c.session_id as "sessionId", c.title, c.project_id as "projectId",
                       c.created_at as "createdAt", c.last_message_at as "lastMessageAt",
                       (select count(*) from platform.agent_message m
                         where m.session_id = c.session_id) as "messageCount"
                from platform.agent_conversation c
                where c.tenant_id = ? and c.username = ?
                order by coalesce(c.last_message_at, c.created_at) desc
                limit ?
                """, tenant, user, cap);
    }

    @GetMapping("/conversations/{sessionId}")
    public Map<String, Object> conversation(@PathVariable String sessionId,
                                            HttpServletRequest http) {
        Map<String, Object> row = ownConversation(sessionId, http);
        List<Map<String, Object>> messages = jdbc.queryForList("""
                select seq, role, content, tool_calls::text as "toolCalls", status,
                       created_at as "createdAt"
                from platform.agent_message
                where session_id = ? order by seq
                """, sessionId);
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("messages", messages);
        return out;
    }

    @PatchMapping("/conversations/{sessionId}")
    public Map<String, Object> rename(@PathVariable String sessionId,
                                      @RequestBody RenameRequest req,
                                      HttpServletRequest http) {
        ownConversation(sessionId, http);
        String title = req == null || req.title() == null ? "" : req.title().trim();
        if (title.isEmpty() || title.length() > 200) {
            throw ApiException.badRequest("标题不能为空且不超过 200 字");
        }
        jdbc.update("update platform.agent_conversation set title = ?, updated_at = now()"
                + " where session_id = ?", title, sessionId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/conversations/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId, HttpServletRequest http) {
        ownConversation(sessionId, http);
        jdbc.update("delete from platform.agent_conversation where session_id = ?", sessionId);
        return Map.of("ok", true);
    }

    @GetMapping("/memories")
    public Map<String, Object> memories(@RequestParam(required = false) Long projectId,
                                        HttpServletRequest http) {
        String user = user(http);
        String tenant = tenantOf(user);
        return Map.of(
                // platform 级（制度）由管理员维护，所有用户只读；project/user 照旧
                "system", rows(tenant, "platform", "*"),
                "user", rows(tenant, "user", user),
                "project", projectId == null ? List.of() : rows(tenant, "project", String.valueOf(projectId)),
                "projectId", projectId == null ? 0 : projectId);
    }

    /**
     * 写入一条记忆（同 key 同 condition 覆盖旧值）。
     *
     * <p>D15：整段写入放在一个事务里，且 {@code expiresAt} 在动数据之前先校验。
     * 原来 {@link #upsert} 先把旧记忆置 {@code superseded} 再 insert 新行，
     * insert 里 {@code ?::timestamptz} 遇到 {@code 2026-02-30} 这类非法日期抛异常，
     * 第一步却已经提交——一次拼错日期就静默丢一条已保存的记忆，接口还回 409
     * 「违反唯一性或外键约束」把排查带偏。</p>
     */
    @PostMapping("/memories")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> remember(@RequestBody MemoryRequest req, HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String user = user(http);
        String tenant = tenantOf(user);
        String scope = scopeOf(req.scope());
        String owner;
        if ("platform".equals(scope)) {
            // 平台制度对整个租户生效，只能由平台管理员维护
            if (!admin(http)) {
                throw new ApiException(403, "只有平台管理员可以维护平台制度");
            }
            owner = "*";
        } else if ("project".equals(scope)) {
            // 项目级是项目内共享口径，只有项目负责人或管理员能改
            owner = requireProject(req.projectId());
            if (!admin(http) && !ownsProject(Long.valueOf(owner), user)) {
                throw new ApiException(403, "只有项目负责人或管理员可以维护项目级记忆");
            }
        } else {
            owner = user;
        }
        String key = requireText(req.key(), "记忆名称不能为空", 128);
        String value = requireText(req.value(), "记忆内容不能为空", 2000);
        String kind = kindOf(req.kind(), req.memType());
        String subtype = subtypeOf(req.subtype(), kind, req.memType());
        String condition = trimTo(req.condition(), 64);
        // D15：非法 expiresAt 必须在动数据之前挡住（400），不能让「归档旧记忆」先提交
        String expiry = checkExpiry(req.expiresAt());
        upsert(tenant, scope, owner, key, value, user, expiry, kind, subtype, condition);
        Long auditId = jdbc.queryForObject("""
                insert into platform.agent_tool_audit
                    (tenant_id, username, tool, args, ok, result_summary)
                values (?, ?, 'memory.write',
                        json_build_object('scope', ?, 'key', ?, 'kind', ?, 'subtype', ?)::json,
                        true, ?)
                returning id
                """, Long.class, tenant, user, scope, key, kind, subtype, "write " + key);
        return Map.of("ok", true, "scope", scope, "key", key, "value", value,
                "kind", kind, "subtype", subtype, "writeAuditId", auditId);
    }

    /**
     * 撤回一条记忆。
     *
     * <p>D34（2026-10-26）：归档语义原本是对的（内核只追加、不物理删），但全程一条审计都不写
     * ——谁撤回了哪条记忆、属于哪个 scope/创建者，事后查不到；被拒（403）与不存在（404）的
     * 尝试同样什么都不留，涉及对话记忆的撤回在合规上成了「处理个人数据无记录」。
     * 现在按 S13 口径先记 {@code AGENT_MEMORY_FORGET}（目标 {@code m<id>}）的 PENDING，
     * 归档前读到的 scope/owner/创建者/记忆名进 detail，成功回填 SUCCESS，
     * 403/404/底层异常回填 FAILED + 原因，异常原样上抛（状态码口径不变）。</p>
     *
     * <p>业务语义不变：仍然是归档而非物理删除，返回值仍是 {@code {ok,archived}}。</p>
     */
    @DeleteMapping("/memories/{id}")
    public Map<String, Object> forget(@PathVariable long id, HttpServletRequest http) {
        String user = user(http);
        java.util.concurrent.atomic.AtomicReference<String> detail =
                new java.util.concurrent.atomic.AtomicReference<>("");
        return sessions.auditAround(user, "AGENT_MEMORY_FORGET", "m" + id,
                "撤回记忆（归档）", AuthInterceptor.clientIp(http),
                () -> doForget(id, user, http, detail), ignored -> detail.get());
    }

    private Map<String, Object> doForget(long id, String user, HttpServletRequest http,
                                         java.util.concurrent.atomic.AtomicReference<String> detail) {
        String tenant = tenantOf(user);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select scope, owner, mem_key as "key", created_by as "createdBy"
                from platform.agent_memory where id = ? and tenant_id = ?
                """, id, tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("记忆不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        String scope = str(row.get("scope"));
        // 归档后就查不到原值了，先把可追溯信息落到审计 detail
        detail.set("scope=" + scope + " owner=" + row.get("owner")
                + " key=" + row.get("key") + " 创建者=" + row.get("createdBy"));
        boolean allowed;
        if ("platform".equals(scope)) {
            // 平台制度对所有人生效，只能由管理员下线
            allowed = admin(http);
        } else {
            allowed = user.equals(str(row.get("createdBy")));
            if (!allowed && "project".equals(scope)) {
                // 项目级记忆：项目负责人或平台管理员可以清理
                allowed = ownsProject(Long.valueOf(str(row.get("owner"))), user) || admin(http);
            }
        }
        if (!allowed) {
            throw new ApiException(403, "只有创建者、项目负责人或管理员可以删除这条记忆");
        }
        // 内核模型：只追加、不物理删；删除 = 归档，保留审计与恢复能力
        jdbc.update("""
                update platform.agent_memory
                   set status = 'archived', status_reason = 'user_retracted', updated_at = now()
                 where id = ? and tenant_id = ?
                """, id, tenant);
        return Map.of("ok", true, "archived", true);
    }

    private List<Map<String, Object>> rows(String tenant, String scope, String owner) {
        return jdbc.queryForList("""
                select id, scope, owner as "scopeId", mem_key as "key", mem_value as "content",
                       kind, subtype, condition, summary, status, status_reason as "statusReason",
                       use_count as "useCount", last_used_at as "lastUsedAt", source,
                       expires_at as "expiresAt", created_by as "createdBy",
                       updated_at as "updatedAt", content_hash as "contentHash"
                from platform.agent_memory
                where tenant_id = ? and scope = ? and owner = ? and status = 'active'
                  and (expires_at is null or expires_at > now())
                order by updated_at desc
                """, tenant, scope, owner);
    }

    private void upsert(String tenant, String scope, String owner, String key, String value,
                        String user, String expiresAt, String kind, String subtype,
                        String condition) {
        String summary = value.length() > 200 ? value.substring(0, 200) : value;
        String expiry = expiresAt == null || expiresAt.isBlank() ? null : expiresAt.trim();
        String hash = jdbc.queryForObject(
                "select encode(sha256(convert_to(? || E'\\n' || ?, 'UTF8')), 'hex')",
                String.class, value, summary);
        List<Map<String, Object>> active = jdbc.queryForList("""
                select content_hash as "contentHash" from platform.agent_memory
                 where tenant_id = ? and scope = ? and owner = ? and mem_key = ?
                   and condition = ? and status = 'active'
                """, tenant, scope, owner, key, condition);
        // 幂等：同 key 同 condition 同内容不新增行
        if (!active.isEmpty() && str(active.get(0).get("contentHash")).equals(hash)) {
            return;
        }
        // 只追加 + supersede 失效：旧 active 置为 superseded，再插新行
        jdbc.update("""
                update platform.agent_memory
                   set status = 'superseded', status_reason = 'superseded', updated_at = now()
                 where tenant_id = ? and scope = ? and owner = ? and mem_key = ?
                   and condition = ? and status = 'active'
                """, tenant, scope, owner, key, condition);
        jdbc.update("""
                insert into platform.agent_memory
                    (tenant_id, scope, owner, mem_key, mem_value, kind, subtype, condition,
                     summary, source, expires_at, created_by, content_hash, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?::timestamptz, ?, ?, 1)
                """, tenant, scope, owner, key, value, kind, subtype, condition, summary,
                expiry, user, hash);
    }

    private String kindOf(String rawKind, String rawType) {
        String k = rawKind == null ? "" : rawKind.trim().toLowerCase();
        if (k.equals("semantic") || k.equals("procedural") || k.equals("profile")) {
            return k;
        }
        String t = rawType == null ? "" : rawType.trim().toUpperCase();
        return switch (t) {
            case "RULE" -> "procedural";
            case "PREFERENCE" -> "profile";
            default -> "semantic";
        };
    }

    private String subtypeOf(String rawSubtype, String kind, String rawType) {
        String s = trimTo(rawSubtype, 32).toLowerCase();
        if (!s.isEmpty()) {
            return s;
        }
        String t = rawType == null ? "" : rawType.trim().toUpperCase();
        return switch (t) {
            case "RULE" -> "policy";
            case "PREFERENCE" -> "preference";
            case "DECISION" -> "decision";
            default -> switch (kind) {
                case "procedural" -> "policy";
                case "profile" -> "preference";
                default -> "fact";
            };
        };
    }

    private String trimTo(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private Map<String, Object> ownConversation(String sessionId, HttpServletRequest http) {
        String user = user(http);
        String tenant = tenantOf(user);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select session_id as "sessionId", title, project_id as "projectId",
                       summary, summary_upto_seq as "summaryUptoSeq",
                       created_at as "createdAt", last_message_at as "lastMessageAt"
                from platform.agent_conversation
                where session_id = ? and tenant_id = ? and username = ?
                """, sessionId, tenant, user);
        if (rows.isEmpty()) {
            throw ApiException.notFound("对话不存在: " + sessionId);
        }
        return rows.get(0);
    }

    /**
     * 记忆作用域：user / project / platform（platform 兼容旧写法 system）。
     *
     * <p>D22：不认识的 scope 之前一律静默按 user 存，调用方以为写进了项目/平台级；
     * 现在 400 并给出可选值。缺省（不传）仍是 user——这是前端「我的记忆」的默认口径。</p>
     */
    static String scopeOf(String raw) {
        String value = raw == null ? "" : raw.trim()
                .toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "", "user" -> "user";
            case "project" -> "project";
            case "platform", "system" -> "platform";
            default -> throw ApiException.badRequest(
                    "不支持的 scope: " + raw.trim() + "（可选 user / project / platform）");
        };
    }

    /** 能被 {@code ?::timestamptz} 接受的写法，从严到宽逐个试。 */
    private static final List<java.util.function.Function<String, Object>> EXPIRY_FORMATS = List.of(
            java.time.OffsetDateTime::parse,                    // 2026-11-01T00:00:00+08:00 / …Z
            s -> java.time.LocalDateTime.parse(s.replace(' ', 'T')),  // 2026-11-01 00:00:00
            java.time.LocalDate::parse);                        // 2026-11-01

    /**
     * D15：校验并规范化 {@code expiresAt}。空值＝不过期（返回 null）。
     *
     * <p>只认 ISO 日期/时间（可带时区偏移），非法值 400；原样回传字符串，
     * 交给 SQL 的 {@code ?::timestamptz} 保持既有语义不变。以前这里不校验，
     * 非法日期会让 upsert 的第二步失败而第一步（归档旧记忆）已提交，静默丢数据。</p>
     */
    static String checkExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        for (java.util.function.Function<String, Object> parse : EXPIRY_FORMATS) {
            try {
                parse.apply(text);
                return text;
            } catch (java.time.format.DateTimeParseException ignored) {
                // 试下一种写法
            }
        }
        throw ApiException.badRequest("expiresAt 不合法: " + text
                + "（示例：2026-11-01、2026-11-01T10:00:00、2026-11-01T10:00:00+08:00）");
    }

    private boolean ownsProject(long projectId, String user) {
        Integer owned = jdbc.queryForObject("""
                select count(*) from platform.project
                 where id = ? and owner_user = ? and tenant_id = ?
                """, Integer.class, projectId, user,
                com.lab.dataworks.auth.TenantScope.current());
        return owned != null && owned > 0;
    }

    private String requireProject(Long projectId) {
        if (projectId == null) {
            throw ApiException.badRequest("项目级记忆需要 projectId");
        }
        Integer n = jdbc.queryForObject(
                "select count(*) from platform.project where id = ? and tenant_id = ?",
                Integer.class, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
        return String.valueOf(projectId);
    }

    private String requireText(String value, String message, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest(message);
        }
        if (text.length() > max) {
            throw ApiException.badRequest(message + "（不超过 " + max + " 字）");
        }
        return text;
    }

    private String tenantOf(String user) {
        List<String> rows = jdbc.queryForList(
                "select tenant from platform.app_user where username = ?", String.class, user);
        return rows.isEmpty() || rows.get(0) == null ? "default" : rows.get(0);
    }

    private String user(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        if (value == null) {
            throw new ApiException(401, "未登录");
        }
        return String.valueOf(value);
    }

    private boolean admin(HttpServletRequest request) {
        // 平台级记忆/制度只有平台管理员可改；按权限点判定，兼容自定义角色
        return com.lab.dataworks.auth.AtomCheck.has(request, "user:manage");
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
