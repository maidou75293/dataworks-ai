package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP / Agent 的写操作内部接口（建任务、跑任务、停任务）。
 *
 * 约束：
 * - 调用方必须先做用户确认（MCP 侧 confirm=true），这里只负责执行与审计；
 * - 删除、改配置不在这里开放；
 * - 每次调用都落 agent_tool_audit（租户/用户/对象/结果）。
 */
@RestController
@RequestMapping("/api/internal/agent/write")
public class InternalAgentWriteController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SyncTaskService service;
    private final SyncTaskController tasks;
    private final ConnectorCatalog catalog;
    private final SecretCipher cipher;
    private final AgentAccess access;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;

    public InternalAgentWriteController(JdbcTemplate jdbc, ObjectMapper json,
                                        SyncTaskService service, SyncTaskController tasks,
                                        ConnectorCatalog catalog,
                                        SecretCipher cipher, AgentAccess access,
                                        com.lab.dataworks.auth.InternalTokenService internalTokens) {
        this.jdbc = jdbc;
        this.json = json;
        this.service = service;
        this.tasks = tasks;
        this.catalog = catalog;
        this.cipher = cipher;
        this.access = access;
        this.internalTokens = internalTokens;
    }

    public record CreateTask(String projectId, String code, String name,
                             String sourceType, Map<String, Object> sourceConfig,
                             Long sourceDatasourceId,
                             String sinkType, Map<String, Object> sinkConfig,
                             Long sinkDatasourceId,
                             String jobMode, Integer parallelism) {
    }

    @PostMapping("/tasks")
    public Map<String, Object> create(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                      @RequestHeader(value = "X-User-Id", required = false) String user,
                                      @RequestBody CreateTask req) {
        AgentAccess.Caller caller = check(token, tenant, user, "sync:write");
        long start = System.currentTimeMillis();
        try {
            if (req == null || blank(req.code()) || blank(req.name())) {
                throw ApiException.badRequest("code / name 不能为空");
            }
            if (!req.code().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                throw ApiException.badRequest("code 不合法");
            }
            long projectId = req.projectId() == null || blank(req.projectId())
                    ? 1L : Long.parseLong(req.projectId());
            // P2-28：项目必须属于调用者租户，跨租户创建一律 404
            access.requireProject(caller, projectId);
            Integer exists = jdbc.queryForObject(
                    "select count(*) from platform.sync_task "
                            + "where project_id = ? and code = ? and tenant_id = ?",
                    Integer.class, projectId, req.code(), caller.tenant());
            if (exists != null && exists > 0) {
                throw ApiException.conflict("任务编码已存在: " + req.code());
            }
            String sourceType = canonical(req.sourceType(), true);
            String sinkType = canonical(req.sinkType(), false);
            catalog.requireRunnable(sourceType, true);
            catalog.requireRunnable(sinkType, false);
            requireDatasource(projectId, req.sourceDatasourceId(), "source", caller.tenant());
            requireDatasource(projectId, req.sinkDatasourceId(), "sink", caller.tenant());
            Map<String, Object> source = req.sourceConfig() == null ? Map.of() : req.sourceConfig();
            Map<String, Object> sink = req.sinkConfig() == null ? Map.of() : req.sinkConfig();
            Long id = jdbc.queryForObject("""
                    insert into platform.sync_task
                        (project_id, code, name, source_type, source_config,
                         source_datasource_id,
                         sink_type, sink_config, sink_datasource_id,
                         source_password, sink_password,
                         job_mode, parallelism, created_by, tenant_id)
                    values (?, ?, ?, ?, ?::json, ?, ?, ?::json, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, projectId, req.code(), req.name().trim(),
                    sourceType, json.writeValueAsString(sanitize(source)),
                    req.sourceDatasourceId(),
                    sinkType, json.writeValueAsString(sanitize(sink)),
                    req.sinkDatasourceId(),
                    // P2-23：密码列与主表同一语句写入，避免第二次更新失败留下 NULL 密码
                    encrypt(source.get("password")), encrypt(sink.get("password")),
                    blank(req.jobMode()) ? "BATCH" : req.jobMode().toUpperCase(Locale.ROOT),
                    req.parallelism() == null ? 1 : Math.max(1, Math.min(8, req.parallelism())),
                    user, caller.tenant());
            audit(caller.tenant(), caller.user(), "platform.create_task",
                    Map.of("code", req.code(), "source", sourceType, "sink", sinkType),
                    true, System.currentTimeMillis() - start, "id=" + id);
            return Map.of("ok", true, "id", id, "code", req.code());
        } catch (Exception e) {
            audit(caller.tenant(), caller.user(), "platform.create_task",
                    Map.of("code", req == null ? "" : String.valueOf(req.code())),
                    false, System.currentTimeMillis() - start, e.getMessage());
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new ApiException(500, "创建任务失败: " + e.getMessage());
        }
    }

    @PostMapping("/tasks/{id}/run")
    public Map<String, Object> run(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                   @RequestHeader(value = "X-User-Id", required = false) String user,
                                   @PathVariable long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        AgentAccess.Caller caller = check(token, tenant, user, "ops:run");
        long start = System.currentTimeMillis();
        try {
            requireTask(id, caller.tenant());
            Object params = body == null ? null : body.get("params");
            // 与手动/调度共用同一把任务锁、busy 检查和文件登记（P1-4）。
            Map<String, Object> run = tasks.runInternal(id,
                    params instanceof Map<?, ?> m ? cast(m) : Map.of(), "API");
            String jobName = String.valueOf(run.get("jobName"));
            audit(caller.tenant(), caller.user(), "platform.run_task", Map.of("id", id),
                    !"SKIPPED".equals(run.get("status")),
                    System.currentTimeMillis() - start,
                    "SKIPPED".equals(run.get("status"))
                            ? String.valueOf(run.get("message")) : "job=" + jobName);
            return run;
        } catch (Exception e) {
            audit(caller.tenant(), caller.user(), "platform.run_task", Map.of("id", id), false,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/tasks/{id}/stop")
    public Map<String, Object> stop(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                    @RequestHeader(value = "X-User-Id", required = false) String user,
                                    @PathVariable long id) {
        AgentAccess.Caller caller = check(token, tenant, user, "ops:run");
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select last_job_name, last_status from platform.sync_task "
                            + "where id = ? and tenant_id = ?",
                    id, caller.tenant());
            if (rows.isEmpty()) {
                throw ApiException.notFound("任务不存在: " + id);
            }
            Object job = rows.get(0).get("last_job_name");
            String currentStatus = String.valueOf(rows.get(0).get("last_status"));
            boolean deleted = job != null && service.stop(String.valueOf(job));
            int runsStopped = 0;
            // P2-24：只有真的删掉了 Job 才算 STOPPED；Job 已经结束（deleted=false）时保留
            // 真实结果，避免把 SUCCESS/FAILED 强行改成 STOPPED。运行实例同步收尾。
            if (deleted) {
                jdbc.update("update platform.sync_task set last_status = 'STOPPED' "
                        + "where id = ? and tenant_id = ?", id, caller.tenant());
                runsStopped = jdbc.update("""
                        update platform.sync_task_run
                           set status = 'STOPPED', finished_at = coalesce(finished_at, now())
                         where task_id = ? and status in ('PENDING', 'RUNNING')
                        """, id);
            }
            audit(caller.tenant(), caller.user(), "platform.stop_task",
                    Map.of("id", id, "job", job == null ? "" : String.valueOf(job)),
                    true, System.currentTimeMillis() - start, "deleted=" + deleted);
            return Map.of("ok", true, "id", id, "jobName", job == null ? "" : job,
                    "deleted", deleted, "status", deleted ? "STOPPED" : currentStatus,
                    "runsStopped", runsStopped);
        } catch (Exception e) {
            audit(caller.tenant(), caller.user(), "platform.stop_task", Map.of("id", id), false,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    public record ChangeRequest(String type, String targetType, Long targetId,
                                String targetName, String reason, String impact,
                                String rollback) {
    }

    private static final java.util.Set<String> CHANGE_TYPES = java.util.Set.of(
            "DELETE_TASK", "DELETE_DATASOURCE", "UPDATE_CONFIG", "UPDATE_PERMISSION",
            "UPDATE_SCHEDULE", "ALTER_TABLE");

    /**
     * 删除/改配置/权限/调度/表结构：只生成申请单，绝不执行。
     * 返回 status=PENDING_APPROVAL，由人工审批后再走原有平台流程。
     */
    @PostMapping("/change-requests")
    public Map<String, Object> requestChange(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestBody ChangeRequest req) {
        AgentAccess.Caller caller = check(token, tenant, user, "agent:action:request");
        long start = System.currentTimeMillis();
        try {
            if (req == null || blank(req.type()) || blank(req.reason())) {
                throw ApiException.badRequest("type / reason 不能为空");
            }
            String type = req.type().trim().toUpperCase(Locale.ROOT);
            if (!CHANGE_TYPES.contains(type)) {
                throw ApiException.badRequest("不支持的申请类型: " + type);
            }
            String targetType = blank(req.targetType()) ? type.replace("DELETE_", "")
                    .replace("UPDATE_", "").replace("ALTER_", "") : req.targetType().trim();
            if ("DELETE_TASK".equals(type) || "DELETE_DATASOURCE".equals(type)) {
                String table = "DELETE_TASK".equals(type)
                        ? "platform.sync_task" : "platform.datasource";
                Integer n = jdbc.queryForObject(
                        "select count(*) from " + table + " where id = ? and tenant_id = ?",
                        Integer.class, req.targetId(), caller.tenant());
                if (req.targetId() == null || n == null || n == 0) {
                    throw ApiException.notFound("申请对象不存在: " + req.targetId());
                }
            }
            Long id = jdbc.queryForObject("""
                    insert into platform.agent_change_request
                        (tenant_id, requester, request_type, target_type, target_id,
                         target_name, reason, impact, rollback)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class,
                    caller.tenant(), user, type, targetType,
                    req.targetId(), req.targetName(), req.reason(),
                    req.impact(), req.rollback());
            audit(caller.tenant(), caller.user(), "platform.request_change",
                    Map.of("type", type, "targetId", req.targetId() == null ? 0 : req.targetId(),
                            "targetName", req.targetName() == null ? "" : req.targetName()),
                    true, System.currentTimeMillis() - start, "requestId=" + id);
            return Map.of("ok", true, "id", id, "status", "PENDING_APPROVAL",
                    "message", "已生成变更申请，需人工审批；本次未执行任何删除或修改");
        } catch (Exception e) {
            audit(caller.tenant(), caller.user(), "platform.request_change",
                    Map.of("type", req == null ? "" : String.valueOf(req.type())),
                    false, System.currentTimeMillis() - start, e.getMessage());
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new ApiException(500, "生成申请失败: " + e.getMessage());
        }
    }

    private void requireDatasource(long projectId, Long dsId, String role, String tenant) {
        if (dsId == null) {
            return;
        }
        Integer n = jdbc.queryForObject(
                "select count(*) from platform.datasource "
                        + "where id = ? and project_id = ? and tenant_id = ?",
                Integer.class, dsId, projectId, tenant);
        if (n == null || n == 0) {
            throw ApiException.badRequest(role + " 数据源不存在: " + dsId);
        }
    }

    private void requireTask(long id, String tenant) {
        Integer n = jdbc.queryForObject(
                "select count(*) from platform.sync_task where id = ? and tenant_id = ?",
                Integer.class, id, tenant);
        if (n == null || n == 0) {
            throw ApiException.notFound("任务不存在: " + id);
        }
    }

    private static Map<String, Object> cast(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private Map<String, Object> loadTask(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from platform.sync_task where id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("任务不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", row.get("id"));
        task.put("code", row.get("code"));
        task.put("sourceType", row.get("source_type"));
        task.put("sinkType", row.get("sink_type"));
        task.put("jobMode", row.get("job_mode"));
        task.put("parallelism", row.get("parallelism"));
        task.put("createdBy", row.get("created_by"));
        task.put("projectId", row.get("project_id"));
        Map<String, Object> source = jsonMap(row.get("source_config"));
        Map<String, Object> sink = jsonMap(row.get("sink_config"));
        if (row.get("source_password") != null) {
            source.put("password", cipher.decrypt(String.valueOf(row.get("source_password"))));
        }
        if (row.get("sink_password") != null) {
            sink.put("password", cipher.decrypt(String.valueOf(row.get("sink_password"))));
        }
        mergeDatasource(source, row.get("source_datasource_id"));
        mergeDatasource(sink, row.get("sink_datasource_id"));
        task.put("sourceConfig", source);
        task.put("sinkConfig", sink);
        return task;
    }

    private void mergeDatasource(Map<String, Object> config, Object dsId) {
        if (dsId == null || (config.containsKey("url") && config.containsKey("password"))) {
            return;
        }
        List<Map<String, Object>> ds = jdbc.queryForList(
                "select jdbc_url, username, password, config_json from platform.datasource where id = ?",
                dsId);
        if (ds.isEmpty()) {
            return;
        }
        Map<String, Object> row = ds.get(0);
        config.putIfAbsent("url", row.get("jdbc_url"));
        config.putIfAbsent("user", row.get("username"));
        if (row.get("password") != null) {
            config.put("password", cipher.decrypt(String.valueOf(row.get("password"))));
        }
        jsonMap(row.get("config_json")).forEach(config::putIfAbsent);
        if (config.get("driver") == null && config.get("url") != null) {
            String url = String.valueOf(config.get("url")).toLowerCase(Locale.ROOT);
            if (url.startsWith("jdbc:mysql:")) {
                config.put("driver", "com.mysql.cj.jdbc.Driver");
            } else if (url.startsWith("jdbc:postgresql:")) {
                config.put("driver", "org.postgresql.Driver");
            } else if (url.startsWith("jdbc:oracle:")) {
                config.put("driver", "oracle.jdbc.OracleDriver");
            } else if (url.startsWith("jdbc:sqlserver:")) {
                config.put("driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
            } else if (url.startsWith("jdbc:clickhouse:")) {
                config.put("driver", "com.clickhouse.jdbc.ClickHouseDriver");
            }
        }
    }

    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "password", "passwd", "secretkey", "secret_key", "apikey", "api_key",
            "token", "accesstoken",
            "access_key", "accesskey", "private_key", "privatekey", "secret", "auth", "credential");

    /** P2-25：递归删除嵌套结构里的敏感键，不能只删顶层。 */
    private Map<String, Object> sanitize(Map<String, Object> config) {
        if (config == null) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) scrub(config);
        return out;
    }

    private Object scrub(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (SENSITIVE_KEYS.contains(key.trim().toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                out.put(key, scrub(entry.getValue()));
            }
            return out;
        }
        if (node instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(scrub(item));
            }
            return out;
        }
        return node;
    }

    private String encrypt(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null : cipher.encrypt(String.valueOf(value));
    }

    private String canonical(String type, boolean source) {
        String value = blank(type) ? (source ? "Jdbc" : "Doris") : type;
        switch (value.toLowerCase(Locale.ROOT)) {
            case "mysql", "postgresql", "postgres", "oracle", "sqlserver", "db2",
                 "greenplum", "vertica", "tidb" -> {
                return "Jdbc";
            }
            case "jdbc" -> {
                return "Jdbc";
            }
            case "s3", "s3file" -> {
                return "S3File";
            }
            case "postgresql-cdc", "postgres-cdc" -> {
                return "Postgres-CDC";
            }
            case "rocketmq" -> {
                return "Rocketmq";
            }
            case "rabbitmq" -> {
                return "RabbitMQ";
            }
            default -> {
                return catalog.canonical(value);
            }
        }
    }

    private Map<String, Object> jsonMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(String.valueOf(value), Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 内部写接口的校验：内部凭证 → 账号/租户 → 权限点。
     *
     * <p>写操作**不受助手「全权测试」开关影响**：开关只放开只读工具，
     * 建/跑/停任务仍然要求真实权限点（sync:write / ops:run / agent:action:request）。
     * MCP 等非平台账号由内部凭证兜底，保持原有行为。</p>
     */
    private AgentAccess.Caller check(String token, String tenant, String user, String atom) {
        AgentAccess.Caller caller = access.check(token, tenant, user, null);
        access.requireAtom(caller, atom);
        return caller;
    }

    private void audit(String tenant, String user, String tool, Map<String, Object> args,
                       boolean ok, long durationMs, String summary) {
        try {
            jdbc.update("""
                    insert into platform.agent_tool_audit
                        (tenant_id, username, session_id, tool, args, ok, duration_ms, result_summary)
                    values (?, ?, ?, ?, ?::json, ?, ?, ?)
                    """, tenant == null || tenant.isBlank() ? "default" : tenant,
                    user == null ? "unknown" : user, null, tool,
                    json.writeValueAsString(args), ok,
                    (int) Math.min(Integer.MAX_VALUE, durationMs),
                    summary == null ? null : summary.substring(0, Math.min(500, summary.length())));
        } catch (Exception ignored) {
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
