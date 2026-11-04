package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent-service 的会话 / 记忆 / 知识检索内部接口。
 *
 * <p>会话与消息由 Python 侧在每轮对话写入，保证刷新页面、重启服务、换实例后都能续聊；
 * 知识检索走 MCP 的 kb.search（文档已向量化在 kb_dev）。
 */
@RestController
@RequestMapping("/api/internal/agent")
public class InternalAgentMemoryController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;
    private final RestTemplate mcp;
    private final String mcpBaseUrl;
    private final String mcpToken;
    private final AgentAccess access;

    public InternalAgentMemoryController(JdbcTemplate jdbc, ObjectMapper json,
                                         RestTemplateBuilder builder,
                                         com.lab.dataworks.auth.InternalTokenService internalTokens,
                                         @Value("${mcp.base-url:}") String mcpBaseUrl,
                                         @Value("${mcp.token:}") String mcpToken,
                                         AgentAccess access) {
        this.jdbc = jdbc;
        this.json = json;
        this.internalTokens = internalTokens;
        this.mcp = builder.setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(20)).build();
        this.mcpBaseUrl = mcpBaseUrl == null ? "" : mcpBaseUrl.replaceAll("/+$", "");
        this.mcpToken = mcpToken == null ? "" : mcpToken.trim();
        this.access = access;
    }

    public record EnsureConversation(String sessionId, Long projectId, String title) {
    }

    public record MessageRequest(String role, String content, Object toolCalls, String status) {
    }

    public record MemoryRequest(String scope, Long projectId, String key, String value,
                                String memType, String kind, String subtype, String condition) {
    }

    public record SummaryRequest(String summary, Integer uptoSeq) {
    }

    public record DraftRequest(String stage, Object slots, String plan) {
    }

    // ---------- 会话 ----------

    @PostMapping("/conversations")
    public Map<String, Object> ensureConversation(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody EnsureConversation req) {
        check(token, user);
        if (req == null || req.sessionId() == null || req.sessionId().isBlank()) {
            throw ApiException.badRequest("sessionId 不能为空");
        }
        String title = req.title() == null || req.title().isBlank()
                ? "新对话" : cut(req.title().trim(), 80);
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        jdbc.update("""
                insert into platform.agent_conversation
                    (session_id, tenant_id, username, project_id, title, last_message_at)
                values (?, ?, ?, ?, ?, now())
                on conflict (session_id) do update
                   set project_id = coalesce(excluded.project_id, platform.agent_conversation.project_id),
                       updated_at = now()
                """, req.sessionId(), tenantId, user, req.projectId(), title);
        return Map.of("sessionId", req.sessionId(), "tenantId", tenantId, "username", user);
    }

    @PostMapping("/conversations/{sessionId}/messages")
    public Map<String, Object> appendMessage(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody MessageRequest req) {
        check(token, user);
        if (req == null || req.role() == null) {
            throw ApiException.badRequest("role 不能为空");
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.agent_conversation where session_id = ?",
                Integer.class, sessionId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("会话不存在: " + sessionId);
        }
        Integer seq = jdbc.queryForObject("""
                select coalesce(max(seq), 0) + 1 from platform.agent_message where session_id = ?
                """, Integer.class, sessionId);
        String toolCalls = null;
        try {
            toolCalls = req.toolCalls() == null ? null : json.writeValueAsString(req.toolCalls());
        } catch (Exception ignored) {
            // 工具摘要不是关键信息，序列化失败就只存正文
        }
        jdbc.update("""
                insert into platform.agent_message
                    (session_id, seq, role, content, tool_calls, status)
                values (?, ?, ?, ?, ?::jsonb, ?)
                """, sessionId, seq, req.role(), cut(req.content(), 100_000),
                toolCalls, req.status());
        jdbc.update("""
                update platform.agent_conversation
                   set last_message_at = now(), updated_at = now()
                 where session_id = ?
                """, sessionId);
        return Map.of("ok", true, "seq", seq);
    }

    @GetMapping("/conversations/{sessionId}/messages")
    public List<Map<String, Object>> messages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int afterSeq,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user) {
        check(token, user);
        int cap = Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList("""
                select seq, role, content, tool_calls::text as "toolCalls", status
                from (
                    select * from platform.agent_message
                     where session_id = ? and seq > ? order by seq desc limit ?
                ) t order by seq
                """, sessionId, Math.max(0, afterSeq), cap);
    }

    @GetMapping("/conversations/{sessionId}/summary")
    public Map<String, Object> summary(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user) {
        check(token, user);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(summary, '') as summary,
                       summary_upto_seq as "uptoSeq"
                from platform.agent_conversation where session_id = ?
                """, sessionId);
        return rows.isEmpty() ? Map.of("summary", "", "uptoSeq", 0) : rows.get(0);
    }

    @PutMapping("/conversations/{sessionId}/summary")
    public Map<String, Object> saveSummary(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody SummaryRequest req) {
        check(token, user);
        int n = jdbc.update("""
                update platform.agent_conversation
                   set summary = ?, summary_upto_seq = ?, updated_at = now()
                 where session_id = ?
                """, req == null ? "" : cut(req.summary(), 8000),
                req == null || req.uptoSeq() == null ? 0 : Math.max(0, req.uptoSeq()),
                sessionId);
        if (n == 0) {
            throw ApiException.notFound("会话不存在: " + sessionId);
        }
        return Map.of("ok", true);
    }

    // ---------- 记忆 ----------

    @GetMapping("/memories")
    public Map<String, Object> memories(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(required = false) Long projectId) {
        check(token, user);
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        // 只读快照：读取不改 use_count（排序稳定），本轮实际注入后再调 /memories/used
        List<Map<String, Object>> platformRows = rows(tenantId, "platform", "*");
        List<Map<String, Object>> userRows = rows(tenantId, "user", user);
        List<Map<String, Object>> projectRows = projectId == null
                ? List.of() : rows(tenantId, "project", String.valueOf(projectId));
        return Map.of("platform", platformRows, "system", platformRows,
                "user", userRows, "project", projectRows);
    }

    /** 本轮实际注入后再记使用次数，避免「读一次就改排序」。 */
    @PostMapping("/memories/used")
    public Map<String, Object> used(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody List<Long> ids) {
        check(token, user);
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        int n = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            n += jdbc.update("""
                    update platform.agent_memory
                       set use_count = use_count + 1, last_used_at = now()
                     where tenant_id = ? and id = ?
                    """, tenantId, id);
        }
        return Map.of("ok", true, "updated", n);
    }

    /** 身份不是记忆：实时读账号体系，供提示词拼装用。 */
    @GetMapping("/identity")
    public Map<String, Object> identity(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(required = false) Long projectId) {
        check(token, user);
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user);
        out.put("username", user);
        out.put("tenantId", tenantId);
        List<Map<String, Object>> users = jdbc.queryForList("""
                select display_name as "displayName", role from platform.app_user where username = ?
                """, user);
        out.put("displayName", users.isEmpty() ? user : users.get(0).get("displayName"));
        out.put("role", users.isEmpty() ? "" : users.get(0).get("role"));
        out.put("projectId", projectId == null ? 0 : projectId);
        out.put("department", "");
        if (projectId != null) {
            // P2-28：projectId 由调用方透传，必须同时按租户过滤，
            // 否则可以读到其他租户同名 id 的项目名/部门。
            List<Map<String, Object>> projects = jdbc.queryForList("""
                    select p.name as "projectName", d.name as "departmentName"
                      from platform.project p
                      left join platform.department d on d.id = p.department_id
                     where p.id = ? and p.tenant_id = ?
                    """, projectId, tenantId);
            if (!projects.isEmpty()) {
                out.put("projectName", projects.get(0).get("projectName"));
                out.put("department", projects.get(0).get("departmentName"));
            }
        }
        return out;
    }

    @PostMapping("/memories")
    public Map<String, Object> remember(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody MemoryRequest req) {
        check(token, user);
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String requested = req.scope() == null ? "user" : req.scope().trim().toLowerCase();
        if ("platform".equals(requested) || "system".equals(requested)) {
            // 平台制度影响全租户，只能由管理员在记忆面板维护，模型不能写
            throw new ApiException(403, "平台制度只能由平台管理员维护");
        }
        String scope = "project".equals(requested) ? "project" : "user";
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        if ("project".equals(scope)) {
            if (req.projectId() == null) {
                throw ApiException.badRequest("项目级记忆需要 projectId");
            }
            // 项目规范/口径只有负责人或管理员能写；普通成员的说法降级为个人记忆
            if (!canWriteProject(tenantId, user, req.projectId())) {
                scope = "user";
            }
        }
        String owner = "project".equals(scope)
                ? String.valueOf(req.projectId()) : user;
        String key = requireText(req.key(), 128);
        String value = requireText(req.value(), 2000);
        String kind = kindOf(req.kind(), req.memType());
        String subtype = subtypeOf(req.subtype(), kind, req.memType());
        String condition = req.condition() == null ? "" : req.condition().trim();
        String summary = value.length() > 200 ? value.substring(0, 200) : value;
        String hash = jdbc.queryForObject(
                "select encode(sha256(convert_to(? || E'\\n' || ?, 'UTF8')), 'hex')",
                String.class, value, summary);
        List<Map<String, Object>> active = jdbc.queryForList("""
                select content_hash as "contentHash" from platform.agent_memory
                 where tenant_id = ? and scope = ? and owner = ? and mem_key = ?
                   and condition = ? and status = 'active'
                """, tenantId, scope, owner, key, condition);
        if (!active.isEmpty()
                && String.valueOf(active.get(0).get("contentHash")).equals(hash)) {
            return Map.of("ok", true, "scope", scope, "key", key, "value", value,
                    "kind", kind, "subtype", subtype, "idempotent", true);
        }
        jdbc.update("""
                update platform.agent_memory
                   set status = 'superseded', status_reason = 'superseded', updated_at = now()
                 where tenant_id = ? and scope = ? and owner = ? and mem_key = ?
                   and condition = ? and status = 'active'
                """, tenantId, scope, owner, key, condition);
        jdbc.update("""
                insert into platform.agent_memory
                    (tenant_id, scope, owner, mem_key, mem_value, kind, subtype, condition,
                     summary, source, created_by, content_hash, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, ?, 1)
                """, tenantId, scope, owner, key, value, kind, subtype, condition,
                summary, user, hash);
        Long auditId = jdbc.queryForObject("""
                insert into platform.agent_tool_audit
                    (tenant_id, username, tool, args, ok, result_summary)
                values (?, ?, 'memory.write',
                        json_build_object('scope', ?, 'key', ?, 'kind', ?, 'subtype', ?)::json,
                        true, ?)
                returning id
                """, Long.class, tenantId, user, scope, key, kind, subtype,
                "write " + key);
        return Map.of("ok", true, "scope", scope, "key", key, "value", value,
                "kind", kind, "subtype", subtype, "writeAuditId", auditId);
    }

    @DeleteMapping("/memories")
    public Map<String, Object> forget(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam String scope, @RequestParam String key,
            @RequestParam(required = false) Long projectId) {
        check(token, user);
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        boolean project = "project".equalsIgnoreCase(scope) || "PROJECT".equalsIgnoreCase(scope);
        String owner = project
                ? (projectId == null ? "" : String.valueOf(projectId)) : user;
        // 内核模型：不物理删，撤回 = 归档并记录原因
        int n = jdbc.update("""
                update platform.agent_memory
                   set status = 'archived', status_reason = 'user_retracted', updated_at = now()
                 where tenant_id = ? and scope = ? and owner = ? and mem_key = ?
                   and status = 'active'
                """, tenantId, project ? "project" : "user", owner, key);
        return Map.of("ok", true, "archived", n);
    }

    private List<Map<String, Object>> rows(String tenantId, String scope, String owner) {
        return jdbc.queryForList("""
                select id, scope, owner as "scopeId", mem_key as "key", mem_value as "content",
                       kind, subtype, condition, summary, outcome, importance, confidence,
                       source, use_count as "useCount", updated_at as "updatedAt",
                       content_hash as "contentHash"
                from platform.agent_memory
                where tenant_id = ? and scope = ? and owner = ? and status = 'active'
                  and (expires_at is null or expires_at > now())
                order by case kind when 'procedural' then 0 when 'semantic' then 1 else 2 end,
                         use_count desc, updated_at desc
                limit 50
                """, tenantId, scope, owner);
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
        String s = rawSubtype == null ? "" : rawSubtype.trim().toLowerCase();
        if (!s.isEmpty()) {
            return s.length() > 32 ? s.substring(0, 32) : s;
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

    // ---------- 建任务槽位草稿 ----------

    @GetMapping("/drafts/{sessionId}")
    public Map<String, Object> draft(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user) {
        check(token, user);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select stage, slots::text as "slots", plan,
                       tenant_id as "tenantId", username
                from platform.agent_task_draft where session_id = ?
                """, sessionId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    @PutMapping("/drafts/{sessionId}")
    public Map<String, Object> saveDraft(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody DraftRequest req) {
        check(token, user);
        String slots;
        try {
            slots = req == null || req.slots() == null ? "{}" : json.writeValueAsString(req.slots());
        } catch (Exception e) {
            throw ApiException.badRequest("槽位格式不合法");
        }
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        jdbc.update("""
                insert into platform.agent_task_draft
                    (session_id, tenant_id, username, stage, slots, plan, updated_at)
                values (?, ?, ?, ?, ?::jsonb, ?, now())
                on conflict (session_id) do update
                   set stage = excluded.stage, slots = excluded.slots,
                       plan = excluded.plan, updated_at = now()
                """, sessionId, tenantId, user, req == null ? null : req.stage(),
                slots, req == null ? null : req.plan());
        return Map.of("ok", true);
    }

    @DeleteMapping("/drafts/{sessionId}")
    public Map<String, Object> deleteDraft(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user) {
        check(token, user);
        int n = jdbc.update(
                "delete from platform.agent_task_draft where session_id = ?", sessionId);
        return Map.of("ok", true, "deleted", n);
    }

    public record AccessRequestInput(String resourceType, String catalog, String schemaName,
                                     String tableName, String permission, String reason) {
    }

    /** 对话里代用户提交库/表权限申请；只落申请，不直接授权。 */
    @PostMapping("/access-requests")
    public Map<String, Object> requestAccess(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody AccessRequestInput req) {
        check(token, user);
        access.check(token, tenant, user, "access:request");
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String type = req.resourceType() == null ? "" : req.resourceType().trim().toUpperCase();
        String permission = req.permission() == null ? "" : req.permission().trim().toUpperCase();
        if (!List.of("DATABASE", "TABLE").contains(type)) {
            throw ApiException.badRequest("resourceType 只能是 DATABASE 或 TABLE");
        }
        if (!List.of("READ", "WRITE").contains(permission)) {
            throw ApiException.badRequest("permission 只能是 READ 或 WRITE");
        }
        if (req.catalog() == null || req.schemaName() == null
                || req.catalog().isBlank() || req.schemaName().isBlank()) {
            throw ApiException.badRequest("catalog / schemaName 不能为空");
        }
        String table = "TABLE".equals(type) ? req.tableName() : null;
        if ("TABLE".equals(type) && (table == null || table.isBlank())) {
            throw ApiException.badRequest("TABLE 申请需要 tableName");
        }
        Integer exists = jdbc.queryForObject("""
                select count(*) from platform.managed_table
                 where catalog = ? and schema_name = ?
                   and (? = 'DATABASE' or table_name = ?)
                """, Integer.class, req.catalog().trim(), req.schemaName().trim(), type, table);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("资源不存在或未纳管");
        }
        String tenantId = tenant == null || tenant.isBlank() ? "default" : tenant;
        Long id = jdbc.queryForObject("""
                insert into platform.access_request
                    (requester, requester_tenant, resource_type, catalog, schema_name,
                     table_name, permission, reason)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, user, tenantId, type, req.catalog().trim(),
                req.schemaName().trim(), table, permission,
                cut(req.reason(), 500));
        return Map.of("ok", true, "id", id, "status", "PENDING",
                "message", "已提交权限申请，等待资源负责人审批；尚未获得权限");
    }

    // ---------- 知识检索（RAG） ----------

    @GetMapping("/knowledge")
    public Map<String, Object> knowledge(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        check(token, user);
        if (query == null || query.isBlank()) {
            return Map.of("results", List.of());
        }
        if (mcpBaseUrl.isBlank()) {
            return Map.of("results", List.of(), "error", "MCP 未配置");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!mcpToken.isBlank()) {
                headers.setBearerAuth(mcpToken);
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("query", query);
            params.put("top_k", Math.max(1, Math.min(topK, 10)));
            String body = mcp.postForObject(mcpBaseUrl + "/tools/kb.search",
                    new HttpEntity<>(Map.of("params", params), headers), String.class);
            JsonNode root = json.readTree(body == null ? "{}" : body);
            JsonNode result = root.has("result") ? root.path("result") : root;
            return Map.of("results", result.path("results"));
        } catch (Exception e) {
            // 检索失败不能打断对话，降级为空结果并说明
            return Map.of("results", List.of(), "error", cut(e.getMessage(), 200));
        }
    }

    @GetMapping("/knowledge/chunk")
    public Map<String, Object> knowledgeChunk(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam long chunkId) {
        // Harness KnowledgePort.get_chunk：按片段 id 回取正文，用于引用复核
        check(token, user);
        if (mcpBaseUrl.isBlank()) {
            throw new ApiException(503, "MCP 未配置，无法读取知识片段");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!mcpToken.isBlank()) {
                headers.setBearerAuth(mcpToken);
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chunk_id", chunkId);
            String body = mcp.postForObject(mcpBaseUrl + "/tools/kb.get_chunk",
                    new HttpEntity<>(Map.of("params", params), headers), String.class);
            JsonNode root = json.readTree(body == null ? "{}" : body);
            JsonNode result = root.has("result") ? root.path("result") : root;
            if (!result.has("chunk_id")) {
                throw ApiException.notFound("知识片段不存在: " + chunkId);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("chunkId", result.path("chunk_id").asLong());
            out.put("docId", result.path("doc_id").asLong());
            out.put("title", result.path("title").asText(""));
            out.put("content", result.path("content").asText(""));
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // MCP 返回 404 CHUNK_NOT_FOUND：片段不存在是「没有」，不是网关错误
            throw ApiException.notFound("知识片段不存在: " + chunkId);
        } catch (Exception e) {
            throw new ApiException(502, "知识片段读取失败：" + cut(e.getMessage(), 200));
        }
    }

    // ---------- 工具 ----------

    private boolean canWriteProject(String tenantId, String user, long projectId) {
        Integer owned = jdbc.queryForObject("""
                select count(*) from platform.project
                 where id = ? and owner_user = ? and tenant_id = ?
                """, Integer.class, projectId, user, tenantId);
        if (owned != null && owned > 0) {
            return true;
        }
        List<String> roles = jdbc.queryForList("""
                select role from platform.app_user where username = ? and tenant = ?
                """, String.class, user, tenantId);
        return !roles.isEmpty() && "ADMIN".equals(roles.get(0));
    }

    private void check(String token, String user) {
        if (!internalTokens.configured()) {
            throw new ApiException(503, "内部接口未启用（PLATFORM_INTERNAL_TOKEN 未配置）");
        }
        if (!internalTokens.matches(token)) {
            throw new ApiException(401, "内部凭证无效");
        }
        if (user == null || user.isBlank()) {
            throw ApiException.badRequest("缺少 X-User-Id，会话与记忆必须可追溯");
        }
    }

    private String requireText(String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("记忆名称和内容不能为空");
        }
        return cut(text, max);
    }

    private String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
