package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.service.DataServiceController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.lab.dataworks.common.LikePattern;

/**
 * Agent 只读工具的内部接口。由 agent-service 调用，不经过浏览器会话。
 *
 * <p>校验顺序：内部凭证 → 账号可用 → 租户成员 → 权限点（仅在 DELEGATE 模式）。
 * SQL 一律带 {@code tenant_id}：无论开关在哪个模式，租户都是数据边界。</p>
 *
 * <p>第二阶段写操作（建/跑/停任务）不在这里开放。</p>
 */
@RestController
@RequestMapping("/api/internal/agent")
public class InternalAgentController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AgentAccess access;
    private final DataServiceController dataServices;
    /** P2-27：命名空间不能硬编码，换环境（staging/prod）时由部署注入。 */
    private final String namespace;

    public InternalAgentController(JdbcTemplate jdbc, ObjectMapper json,
                                   AgentAccess access,
                                   DataServiceController dataServices,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${platform.namespace:dw-dev}") String namespace) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.dataServices = dataServices;
        this.namespace = namespace;
    }

    @GetMapping("/datasources")
    public Map<String, Object> datasources(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(defaultValue = "1") long projectId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {
        AgentAccess.Caller caller = access.check(token, tenant, user, "datasource:read");
        // P2-28：projectId 是调用方透传的，先确认它属于调用者租户，跨租户一律 404
        access.requireProject(caller, projectId);
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, name, ds_type, jdbc_url, username, config_json::text as config
                from platform.datasource where project_id = ? and tenant_id = ? order by id
                """, projectId, caller.tenant());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String dsType = String.valueOf(row.get("dsType") == null
                    ? row.get("ds_type") : row.get("dsType"));
            if (type != null && !type.isBlank() && !type.equalsIgnoreCase(dsType)) {
                continue;
            }
            String name = String.valueOf(row.get("name"));
            if (keyword != null && !keyword.isBlank()
                    && !name.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", name);
            item.put("type", dsType);
            item.put("jdbcUrl", row.get("jdbc_url"));
            item.put("username", row.get("username"));
            out.add(item);
        }
        Map<String, Object> result = Map.of("count", out.size(), "datasources", out);
        audit(caller, null, "list_datasources",
                Map.of("projectId", projectId, "type", type == null ? "" : type,
                        "keyword", keyword == null ? "" : keyword),
                true, System.currentTimeMillis() - start, "count=" + out.size());
        return result;
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(defaultValue = "1") long projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        AgentAccess.Caller caller = access.check(token, tenant, user, "sync:read");
        access.requireProject(caller, projectId);
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, source_type, sink_type, job_mode,
                       last_job_name, last_status, last_run_at
                from platform.sync_task where project_id = ? and tenant_id = ?
                order by last_run_at desc nulls last, id desc
                """, projectId, caller.tenant());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name"));
            String code = String.valueOf(row.get("code"));
            if (keyword != null && !keyword.isBlank()
                    && !(name + " " + code).toLowerCase(Locale.ROOT)
                    .contains(keyword.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Object st = row.get("lastStatus") == null ? row.get("last_status") : row.get("lastStatus");
            if (status != null && !status.isBlank()
                    && !status.equalsIgnoreCase(String.valueOf(st))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("code", code);
            item.put("name", name);
            item.put("sourceType", row.get("sourceType") == null ? row.get("source_type") : row.get("sourceType"));
            item.put("sinkType", row.get("sinkType") == null ? row.get("sink_type") : row.get("sinkType"));
            item.put("jobMode", row.get("jobMode") == null ? row.get("job_mode") : row.get("jobMode"));
            item.put("lastJobName", row.get("lastJobName") == null ? row.get("last_job_name") : row.get("lastJobName"));
            item.put("lastStatus", st);
            item.put("lastRunAt", row.get("lastRunAt") == null ? row.get("last_run_at") : row.get("lastRunAt"));
            out.add(item);
            if (out.size() >= 50) {
                break;
            }
        }
        Map<String, Object> result = Map.of("count", out.size(), "tasks", out);
        audit(caller, null, "list_sync_tasks",
                Map.of("projectId", projectId, "keyword", keyword == null ? "" : keyword,
                        "status", status == null ? "" : status),
                true, System.currentTimeMillis() - start, "count=" + out.size());
        return result;
    }

    @GetMapping("/tasks/status")
    public Map<String, Object> taskStatus(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(defaultValue = "1") long projectId,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String code) {
        AgentAccess.Caller caller = access.check(token, tenant, user, "sync:read");
        access.requireProject(caller, projectId);
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows;
        if (id != null) {
            rows = jdbc.queryForList("""
                    select id, code, name, last_job_name, last_status, last_run_at
                    from platform.sync_task where id = ? and project_id = ? and tenant_id = ?
                    """, id, projectId, caller.tenant());
        } else {
            if (code == null || code.isBlank()) {
                throw ApiException.badRequest("id 或 code 至少给一个");
            }
            rows = jdbc.queryForList("""
                    select id, code, name, last_job_name, last_status, last_run_at
                    from platform.sync_task where code = ? and project_id = ? and tenant_id = ?
                    """, code, projectId, caller.tenant());
        }
        if (rows.isEmpty()) {
            throw ApiException.notFound("同步任务不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        // 补最近一次 Job 的实时状态（只读 k8s Job），失败不阻断
        try {
            Object jobName = result.get("last_job_name") != null
                    ? result.get("last_job_name") : result.get("lastJobName");
            if (jobName != null) {
                result.put("jobLiveStatus", liveJobStatus(String.valueOf(jobName)));
            }
        } catch (Exception ignored) {
        }
        audit(caller, null, "get_task_status",
                Map.of("projectId", projectId, "id", id == null ? 0 : id,
                        "code", code == null ? "" : code),
                true, System.currentTimeMillis() - start, "task=" + result.get("code"));
        return result;
    }

    private String liveJobStatus(String jobName) {
        try (io.fabric8.kubernetes.client.KubernetesClient client =
                     new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()) {
            var job = client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get();
            if (job == null || job.getStatus() == null) {
                return "GONE";
            }
            var st = job.getStatus();
            int failed = st.getFailed() == null ? 0 : st.getFailed();
            int succeeded = st.getSucceeded() == null ? 0 : st.getSucceeded();
            int active = st.getActive() == null ? 0 : st.getActive();
            return failed > 0 ? "FAILED" : succeeded > 0 ? "SUCCESS" : active > 0 ? "RUNNING" : "PENDING";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    @GetMapping("/lineage")
    public Map<String, Object> lineage(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(required = false) String table,
            @RequestParam(defaultValue = "DEV") String env,
            @RequestParam(defaultValue = "50") int limit) {
        AgentAccess.Caller caller = access.check(token, tenant, user, "lineage:read");
        long start = System.currentTimeMillis();
        int max = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> rows;
        if (table == null || table.isBlank()) {
            rows = jdbc.queryForList("""
                    select id, workflow_id, task_code, source_table, target_table,
                           source_column, target_column, edge_type, origin, env
                    from platform.lineage_edge where env = ? and tenant_id = ?
                    order by id desc limit ?
                    """, env.toUpperCase(Locale.ROOT), caller.tenant(), max);
        } else {
            String like = LikePattern.contains(table.trim().toLowerCase(Locale.ROOT));
            rows = jdbc.queryForList("""
                    select id, workflow_id, task_code, source_table, target_table,
                           source_column, target_column, edge_type, origin, env
                    from platform.lineage_edge
                    where env = ? and tenant_id = ?
                      and (lower(source_table) like ? or lower(target_table) like ?)
                    order by id desc limit ?
                    """, env.toUpperCase(Locale.ROOT), caller.tenant(), like, like, max);
        }
        Map<String, Object> result = Map.of("count", rows.size(), "edges", rows);
        audit(caller, null, "query_lineage",
                Map.of("table", table == null ? "" : table, "env", env),
                true, System.currentTimeMillis() - start, "count=" + rows.size());
        return result;
    }

    @GetMapping("/quality")
    public Map<String, Object> quality(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(defaultValue = "1") long projectId,
            @RequestParam(required = false) String table,
            @RequestParam(defaultValue = "50") int limit) {
        AgentAccess.Caller caller = access.check(token, tenant, user, "quality:read");
        access.requireProject(caller, projectId);
        long start = System.currentTimeMillis();
        int max = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> rows;
        if (table == null || table.isBlank()) {
            rows = jdbc.queryForList("""
                    select r.id, q.code as rule_code, q.name as rule_name, q.table_name,
                           r.biz_date, r.passed, r.detail, r.created_at
                    from platform.quality_result r
                    join platform.quality_rule q on q.id = r.rule_id
                    where q.project_id = ? and q.tenant_id = ? order by r.id desc limit ?
                    """, projectId, caller.tenant(), max);
        } else {
            String like = LikePattern.contains(table.trim().toLowerCase(Locale.ROOT));
            rows = jdbc.queryForList("""
                    select r.id, q.code as rule_code, q.name as rule_name, q.table_name,
                           r.biz_date, r.passed, r.detail, r.created_at
                    from platform.quality_result r
                    join platform.quality_rule q on q.id = r.rule_id
                    where q.project_id = ? and q.tenant_id = ? and lower(q.table_name) like ?
                    order by r.id desc limit ?
                    """, projectId, caller.tenant(), like, max);
        }
        Map<String, Object> result = Map.of("count", rows.size(), "results", rows);
        audit(caller, null, "list_quality_results",
                Map.of("projectId", projectId, "table", table == null ? "" : table),
                true, System.currentTimeMillis() - start, "count=" + rows.size());
        return result;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") int limit) {
        AgentAccess.Caller caller = access.check(token, tenant, user, "catalog:read");
        long start = System.currentTimeMillis();
        int max = Math.max(1, Math.min(500, limit));
        String like = keyword == null || keyword.isBlank()
                ? null : LikePattern.contains(keyword.trim().toLowerCase(Locale.ROOT));
        List<Map<String, Object>> rows;
        if (like == null) {
            rows = jdbc.queryForList("""
                    select table_name, count(*) as edge_count from (
                        select source_table as table_name from platform.lineage_edge
                         where tenant_id = ?
                        union all
                        select target_table as table_name from platform.lineage_edge
                         where tenant_id = ?
                    ) t where table_name is not null and table_name <> ''
                    group by table_name order by table_name limit ?
                    """, caller.tenant(), caller.tenant(), max);
        } else {
            rows = jdbc.queryForList("""
                    select table_name, count(*) as edge_count from (
                        select source_table as table_name from platform.lineage_edge
                         where tenant_id = ?
                        union all
                        select target_table as table_name from platform.lineage_edge
                         where tenant_id = ?
                    ) t where table_name is not null and lower(table_name) like ?
                    group by table_name order by table_name limit ?
                    """, caller.tenant(), caller.tenant(), like, max);
        }
        Map<String, Object> result = Map.of("count", rows.size(), "tables", rows);
        audit(caller, null, "list_catalog_tables",
                Map.of("keyword", keyword == null ? "" : keyword),
                true, System.currentTimeMillis() - start, "count=" + rows.size());
        return result;
    }

    @PostMapping("/data/{code}")
    public Map<String, Object> dataCall(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> params) {
        AgentAccess.Caller caller = access.check(token, tenant, user, "service:read");
        long start = System.currentTimeMillis();
        // 数据服务按租户归属：跨租户的服务编码即使知道也不允许调用
        Integer owned = jdbc.queryForObject(
                "select count(*) from platform.data_service where code = ? and tenant_id = ?",
                Integer.class, code, caller.tenant());
        if (owned == null || owned == 0) {
            throw ApiException.notFound("数据服务不存在: " + code);
        }
        try {
            Map<String, Object> result = dataServices.callInternal(
                    code, params, "agent:" + caller.user());
            audit(caller, null, "data.query",
                    Map.of("code", code, "params", String.valueOf(params)),
                    true, System.currentTimeMillis() - start,
                    "rows=" + result.get("rowCount"));
            return result;
        } catch (Exception e) {
            audit(caller, null, "data.query", Map.of("code", code), false,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/audit")
    public Map<String, Object> auditLog(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(defaultValue = "20") int limit) {
        // 审计查询只认内部凭证，不绑定用户/租户
        access.check(token, null, "system", null);
        int max = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant_id, username, tool, args::text as args, ok,
                       duration_ms, result_summary, created_at
                from platform.agent_tool_audit order by id desc limit ?
                """, max);
        return Map.of("count", rows.size(), "audit", rows);
    }

    private void audit(AgentAccess.Caller caller, String session, String tool,
                       Map<String, Object> args, boolean ok, long durationMs,
                       String summary) {
        try {
            // 模式必须进审计：出问题时第一件事是确认这条调用有没有走全权
            Map<String, Object> full = new LinkedHashMap<>(args);
            full.put("mode", caller.mode());
            jdbc.update("""
                    insert into platform.agent_tool_audit
                        (tenant_id, username, session_id, tool, args, ok, duration_ms, result_summary)
                    values (?, ?, ?, ?, ?::json, ?, ?, ?)
                    """, caller.tenant(), caller.user(), session, tool,
                    json.writeValueAsString(full), ok,
                    (int) Math.min(Integer.MAX_VALUE, durationMs),
                    summary == null ? null : summary.substring(0, Math.min(500, summary.length())));
        } catch (Exception e) {
            // 审计失败不能影响工具结果，但必须留日志
            org.slf4j.LoggerFactory.getLogger(InternalAgentController.class)
                    .warn("agent audit failed tool={} user={}: {}",
                            tool, caller.user(), e.getMessage());
        }
    }
}
