package com.lab.dataworks.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/integration/tasks")
public class SyncTaskController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SyncTaskController.class);

    private static final String SELECT = """
            select id, project_id as "projectId", code, name,
                   source_datasource_id as "sourceDatasourceId",
                   sink_datasource_id as "sinkDatasourceId",
                   (select d.name from platform.datasource d where d.id = source_datasource_id)
                       as "sourceDatasourceName",
                   (select d.name from platform.datasource d where d.id = sink_datasource_id)
                       as "sinkDatasourceName",
                   source_type as "sourceType", source_config as "sourceConfig",
                   sink_type as "sinkType", sink_config as "sinkConfig",
                   job_mode as "jobMode",
                   source_jdbc_url as "sourceJdbcUrl", source_driver as "sourceDriver",
                   source_user as "sourceUser", source_query as "sourceQuery",
                   sink_fenodes as "sinkFenodes", sink_database as "sinkDatabase",
                   sink_table as "sinkTable", sink_user as "sinkUser",
                   parallelism, last_job_name as "lastJobName", last_status as "lastStatus",
                   last_run_at as "lastRunAt", created_by as "createdBy", created_at as "createdAt",
                   task_type as "taskType", schedule_enabled as "scheduleEnabled",
                   schedule_cron as "scheduleCron", schedule_timezone as "scheduleTimezone",
                   retry_times as "retryTimes", timeout_minutes as "timeoutMinutes",
                   last_schedule_at as "lastScheduleAt",
                   schedule_retry_count as "scheduleRetryCount",
                   depends_on_task_id as "dependsOnTaskId",
                   released, released_at as "releasedAt", released_by as "releasedBy",
                   ever_released as "everReleased",
                   last_success_at as "lastSuccessAt",
                   (select r.failure_reason from platform.sync_task_run r
                     where r.task_id = platform.sync_task.id
                     order by r.id desc limit 1) as "lastError"
            from platform.sync_task
            -- 归属按项目判定：tenant_id 是派生列，历史行可能还是默认值
            where project_id in (select p.id from platform.project p where p.tenant_id = ?)
            """;

    private final JdbcTemplate jdbc;
    private final SyncTaskService service;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final ConnectorCatalog catalog;
    private final FileIncrementService fileIncrement;
    private final RunEventService runEvents;
    private final RunMetricService runMetrics;
    private final com.lab.dataworks.auth.SessionService sessions;
    /**
     * BUG-TC4-3：采集 sink 的落点闸与环境回显。字段注入（这个控制器的构造参数很长，
     * 加位置参数会牵动不在本卡文件域的调用方）；Bean 缺失时只是少一道闸，不影响建任务。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lab.dataworks.project.ProjectEnvironmentService environment;
    /** REQ-DW-17：运行结果摘要统一契约（一次性立即执行 / 周期最近一次运行同一口径）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private SyncRunSummaryService runSummary;
    /** 同一任务的提交/文件登记串行化：手动连点与调度同时到达时不能各起一个作业。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> runLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SyncTaskController(JdbcTemplate jdbc, SyncTaskService service, SecretCipher cipher,
                              ObjectMapper json, ConnectorCatalog catalog,
                              FileIncrementService fileIncrement, RunEventService runEvents,
                              RunMetricService runMetrics,
                              com.lab.dataworks.auth.SessionService sessions) {
        this.jdbc = jdbc;
        this.service = service;
        this.cipher = cipher;
        this.json = json;
        this.catalog = catalog;
        this.fileIncrement = fileIncrement;
        this.runEvents = runEvents;
        this.runMetrics = runMetrics;
        this.sessions = sessions;
    }

    public record TaskRequest(
            Long projectId, String code, String name,
            String sourceType, Map<String, Object> sourceConfig,
            String sinkType, Map<String, Object> sinkConfig, String jobMode,
            String sourceJdbcUrl, String sourceDriver,
            String sourceUser, String sourcePassword, String sourceQuery,
            String sinkFenodes, String sinkDatabase, String sinkTable,
            String sinkUser, String sinkPassword, Integer parallelism,
            Long sourceDatasourceId, Long sinkDatasourceId,
            String taskType, Boolean scheduleEnabled, String scheduleCron,
            String scheduleTimezone, Integer retryTimes, Integer timeoutMinutes,
            Long dependsOnTaskId) {
    }

    private record ConnectorConfig(String type, Map<String, Object> config, Long datasourceId) {
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long projectId) {
        String sql = SELECT + (projectId == null ? "" : " and project_id = ?") + " order by id desc";
        List<Map<String, Object>> rows = projectId == null
                ? jdbc.queryForList(sql, com.lab.dataworks.auth.TenantScope.current())
                : jdbc.queryForList(sql, com.lab.dataworks.auth.TenantScope.current(), projectId);
        rows.forEach(this::normalize);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("tasks", withLandingEnv(rows));      // BUG-TC4-3：列表也带 env，落点可核对
        return out;
    }

    /** 单个任务详情：编辑页/脚本按 id 取配置（列表只有摘要，超过一屏就要靠它）。 */
    @GetMapping("/{id}")
    @RequireAtom("sync:read")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> row = require(id);
        return withLandingEnv(java.util.List.of(row)).get(0);
    }

    /**
     * 任务详情/列表回显落点环境（BUG-TC4-3 第 3 条：SY-03 原来「有库名、无 env 字段」，
     * 验收侧无法核对「试跑到底落在哪个库」）。按**每个任务自己的项目**取一次环境，
     * 不做拦截——拦截在创建/修改入口做。
     */
    private List<Map<String, Object>> withLandingEnv(List<Map<String, Object>> rows) {
        if (environment == null || rows.isEmpty()) {
            return rows;
        }
        for (Map<String, Object> row : rows) {
            Object project = row.get("projectId");
            Object database = row.get("sinkDatabase");
            if (!(project instanceof Number number) || database == null) {
                row.put("env", null);
                continue;
            }
            long projectId = number.longValue();
            try {
                String landing = environment.landingEnv(projectId, String.valueOf(database));
                row.put("env", landing);
                if ("UNCONFIGURED".equals(landing)) {
                    row.put("envHint", "该项目未配置 dev_database：请先在项目管理补齐，"
                            + "或调用 POST /api/projects/backfill-databases 一次性回填");
                }
            } catch (ApiException e) {
                row.put("env", null);        // 项目被删 / 跨租户：不编一个环境出来
            }
        }
        return rows;
    }

    /** sink 落点闸（建/改任务共用）：生产库直接 400，未配 dev 库的项目也按 *_prd 命名兜底。 */
    private void requireSinkLanding(long projectId, String sinkDatabase) {
        if (environment == null || sinkDatabase == null || sinkDatabase.isBlank()) {
            return;
        }
        environment.requireDevLanding(projectId, java.util.Set.of(sinkDatabase), "采集任务 sink");
    }

    @PostMapping
    @RequireAtom("sync:write")
    public Map<String, Object> create(@RequestBody TaskRequest req, HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.projectId() == null) {
            throw ApiException.badRequest("projectId 不能为空");
        }
        long projectId = req.projectId();
        requireProject(projectId);
        // BUG-TC4-3：sink 落点必须还是开发库。前端只是「默认填 dev_database」，
        // 绕过界面（或直接调 API）就能把试运行的写入指向生产库——「试跑→dev 库」
        // 这条判据在采集链路此前没有任何人守。
        requireSinkLanding(projectId, req.sinkDatabase());
        validateDependency(projectId, req.dependsOnTaskId(), 0);
        ConnectorConfig source = resolve(projectId, req, true);
        ConnectorConfig sink = resolve(projectId, req, false);
        validate(req, true, source, sink);
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.sync_task where project_id = ? and code = ?",
                Integer.class, projectId, req.code());
        if (exists != null && exists > 0) {
            throw ApiException.badRequest("任务编码已存在: " + req.code());
        }
        Long id = jdbc.queryForObject("""
                insert into platform.sync_task
                    (project_id, code, name,
                     source_type, source_config, sink_type, sink_config, job_mode,
                     source_jdbc_url, source_driver, source_user, source_password, source_query,
                     sink_fenodes, sink_database, sink_table, sink_user, sink_password,
                     parallelism, created_by, source_datasource_id, sink_datasource_id,
                     task_type, schedule_enabled, schedule_cron, schedule_timezone,
                     retry_times, timeout_minutes, depends_on_task_id, tenant_id)
                values (?, ?, ?, ?, ?::json, ?, ?::json, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                projectId, req.code(), req.name(),
                source.type(), writeJson(sanitize(source.config())), sink.type(), writeJson(sanitize(sink.config())),
                jobMode(req),
                source.config().get("url"), source.config().get("driver"),
                userOf(source.config()), encryptOrNull(string(source.config().get("password"))),
                source.config().get("query"),
                sink.config().get("fenodes"), sink.config().get("database"),
                sink.config().get("table"), userOf(sink.config()),
                encryptOrNull(string(sink.config().get("password"))),
                parallelism(req), user(http), source.datasourceId(), sink.datasourceId(),
                taskType(req), Boolean.TRUE.equals(req.scheduleEnabled()), req.scheduleCron(),
                scheduleTimezone(req), retryTimes(req), timeoutMinutes(req),
                req.dependsOnTaskId(), com.lab.dataworks.auth.TenantScope.current());
        return get(id);
    }

    @PutMapping("/{id}")
    @RequireAtom("sync:write")
    public Map<String, Object> update(@PathVariable long id, @RequestBody TaskRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Map<String, Object> existing = require(id);
        // 生产态配置冻结：直接改会让「测试通过」与线上跑的内容脱节
        if (Boolean.TRUE.equals(existing.get("released"))) {
            throw ApiException.conflict(
                    "任务已发布到生产环境，不能直接修改；请先下线，修改并试运行成功后再发布");
        }
        long projectId = ((Number) existing.get("projectId")).longValue();
        requireSinkLanding(projectId, req.sinkDatabase());   // BUG-TC4-3：改任务同样要挡生产落库
        validateDependency(projectId, req.dependsOnTaskId(), id);
        ConnectorConfig source = resolve(projectId, req, true);
        ConnectorConfig sink = resolve(projectId, req, false);
        validate(req, false, source, sink);
        jdbc.update("""
                update platform.sync_task set
                    name = ?,
                    source_type = ?, source_config = ?::json,
                    sink_type = ?, sink_config = ?::json,
                    job_mode = ?,
                    source_jdbc_url = ?, source_driver = ?, source_user = ?,
                    source_password = coalesce(?, source_password), source_query = ?,
                    sink_fenodes = ?, sink_database = ?, sink_table = ?, sink_user = ?,
                    sink_password = coalesce(?, sink_password),
                    parallelism = ?,
                    source_datasource_id = ?, sink_datasource_id = ?,
                    task_type = ?, schedule_enabled = ?, schedule_cron = ?,
                    schedule_timezone = ?, retry_times = ?, timeout_minutes = ?,
                    -- 配置变了，旧的成功不能继续给下游当「上游已就绪」的依据
                    last_success_at = null,
                    depends_on_task_id = ?
                where id = ?
                """,
                req.name(),
                source.type(), writeJson(sanitize(source.config())),
                sink.type(), writeJson(sanitize(sink.config())),
                jobMode(req),
                source.config().get("url"), source.config().get("driver"),
                userOf(source.config()), encryptOrNull(string(source.config().get("password"))),
                source.config().get("query"),
                sink.config().get("fenodes"), sink.config().get("database"),
                sink.config().get("table"), userOf(sink.config()),
                encryptOrNull(string(sink.config().get("password"))),
                parallelism(req), source.datasourceId(), sink.datasourceId(),
                taskType(req), Boolean.TRUE.equals(req.scheduleEnabled()), req.scheduleCron(),
                scheduleTimezone(req), retryTimes(req), timeoutMinutes(req),
                req.dependsOnTaskId(), id);
        return get(id);
    }

    /**
     * 发布到生产：发布后配置冻结，周期调度只跑已发布的任务。
     * 门槛是在开发态试运行成功 —— 任何修改都会把 last_success_at 置空。
     */
    @PostMapping("/{id}/publish")
    @RequireAtom("sync:write")
    public Map<String, Object> publish(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> task = require(id);
        if (Boolean.TRUE.equals(task.get("released"))) {
            return get(id);
        }
        if (task.get("lastSuccessAt") == null) {
            throw ApiException.badRequest(
                    "请先在开发环境试运行成功（最近一次修改之后）再发布");
        }
        jdbc.update("""
                update platform.sync_task
                set released = true, released_at = now(), released_by = ?,
                    ever_released = true
                where id = ?
                  and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, String.valueOf(http.getAttribute("username")), id, com.lab.dataworks.auth.TenantScope.current());
        return get(id);
    }

    /** 下线：回到开发态，可以修改；周期调度同时停掉。 */
    @PostMapping("/{id}/offline")
    @RequireAtom("sync:write")
    public Map<String, Object> offline(@PathVariable long id, jakarta.servlet.http.HttpServletRequest http) {
        Map<String, Object> before = require(id);
        jdbc.update("""
                update platform.sync_task
                set released = false, schedule_enabled = false,
                    released_at = null, released_by = null
                where id = ?
                  and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, com.lab.dataworks.auth.TenantScope.current());
        // S3（2026-10-26 扫描 P1）：released / schedule_enabled 被静默翻转，原先零审计
        sessions.audit(String.valueOf(http.getAttribute("username")), "SYNC_TASK_OFFLINE",
                "task#" + id,
                "name=" + before.get("name") + " 发布状态 " + booleanOf(before.get("released"))
                        + "→未发布 周期调度 " + booleanOf(before.get("scheduleEnabled")) + "→已停");
        return get(id);
    }

    @DeleteMapping("/{id}")
    @RequireAtom("sync:delete")
    public Map<String, Object> delete(@PathVariable long id, jakarta.servlet.http.HttpServletRequest http) {
        Map<String, Object> task = require(id);
        if (Boolean.TRUE.equals(task.get("released"))) {
            throw ApiException.conflict("任务已发布到生产环境，请先下线再删除");
        }
        String status = task.get("lastStatus") == null ? "" : String.valueOf(task.get("lastStatus"));
        if ("PENDING".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status)) {
            throw ApiException.conflict("任务正在运行，请先停止再删除");
        }
        int deleted = jdbc.update("""
                delete from platform.sync_task
                 where id = ?
                   and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (deleted == 0) {
            throw ApiException.notFound("同步任务不存在: " + id);
        }
        // S3：删除是破坏性动作，必须留下「删的是哪个任务、什么类型、最近一次状态」
        sessions.audit(String.valueOf(http.getAttribute("username")), "SYNC_TASK_DELETE",
                "task#" + id,
                "name=" + task.get("name") + " taskType=" + task.get("taskType")
                        + " projectId=" + task.get("projectId") + " lastStatus=" + status);
        return Map.of("ok", true, "id", id);
    }

    private static String booleanOf(Object value) {
        return Boolean.TRUE.equals(value) ? "已开启" : "未开启";
    }

    public record RunBody(Map<String, Object> params) {
    }

    public record StopBody(String reason) {
    }

    @PostMapping("/{id}/run")
    @RequireAtom("sync:run")
    public Map<String, Object> run(@PathVariable long id,
                                   @RequestBody(required = false) RunBody body,
                                   @RequestParam(required = false) Boolean forceDev) {
        return runInternal(id, body == null ? null : body.params(), "MANUAL",
                Boolean.TRUE.equals(forceDev));
    }

    /**
     * 停止当前运行：删除 k8s Job，SeaTunnel 客户端退出后 Zeta 作业随之取消。
     *
     * <p>之前只有内部 {@code stopJob(jobName)}，前端没有入口、也没有 HTTP 路由，
     * 用户只能等超时。这里补上独立接口，并把运行实例收尾为 STOPPED。</p>
     */
    @PostMapping("/{id}/stop")
    @RequireAtom("sync:run")
    public Map<String, Object> stop(@PathVariable long id,
                                    @RequestBody(required = false) StopBody body,
                                    HttpServletRequest http) {
        Map<String, Object> task = require(id);
        Object rawJob = task.get("lastJobName");
        String jobName = rawJob == null ? null : String.valueOf(rawJob);
        String status = task.get("lastStatus") == null
                ? "" : String.valueOf(task.get("lastStatus"));
        if (jobName == null || jobName.isBlank()) {
            return Map.of("ok", true, "id", id, "status", "NEVER_RUN",
                    "stopped", false, "message", "任务还没有运行过");
        }
        if (!"PENDING".equalsIgnoreCase(status) && !"RUNNING".equalsIgnoreCase(status)) {
            throw ApiException.conflict("任务当前不是运行中（" + status + "），无需停止");
        }
        boolean stopped = service.stop(jobName);
        if (!stopped) {
            // Job 可能已被 TTL 清理或人工删除：不要假装成功，返回真实状态
            Map<String, Object> fresh = refreshStatus(id);
            throw new ApiException(409, "作业已结束或不存在，当前状态: " + fresh.get("status"));
        }
        String reason = body == null || body.reason() == null || body.reason().isBlank()
                ? "用户停止" : body.reason().trim();
        jdbc.update("""
                update platform.sync_task
                   set last_status = 'STOPPED'
                 where id = ?
                   and project_id in (select p.id from platform.project p
                                       where p.tenant_id = ?)
                """, id, com.lab.dataworks.auth.TenantScope.current());
        jdbc.update("""
                update platform.sync_task_run
                   set status = 'STOPPED', finished_at = now(),
                       duration_ms = coalesce(duration_ms,
                           (extract(epoch from (now() - coalesce(started_at, created_at)))
                            * 1000)::bigint),
                       failure_reason = coalesce(?, failure_reason)
                 where job_name = ?
                   and status in ('SUBMITTED', 'RUNNING', 'QUEUED', 'PENDING')
                """, reason, jobName);
        long runId = runIdOf(jobName);
        if (runId > 0) {
            runEvents.emit(runId, "done", "INFO", "用户停止：" + reason, null);
        }
        sessions.audit(String.valueOf(http.getAttribute("username")), "SYNC_TASK_STOP",
                String.valueOf(id), "job=" + jobName + " reason=" + reason, "SUCCESS",
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return Map.of("ok", true, "id", id, "status", "STOPPED",
                "jobName", jobName, "stopped", true, "reason", reason);
    }

    /**
     * 手动运行与 Agent/MCP 内部触发共用的提交入口。
     *
     * <p>必须在同一把任务锁里做 busy 检查与文件登记：Agent 以前直接调用
     * {@link SyncTaskService#run}，会与手动/调度并发提交同一任务，文件级增量任务
     * 还会绕过文件登记，导致重复读或归档搬走对方未读的文件。
     */
    public Map<String, Object> runInternal(long id, Map<String, Object> params, String runType) {
        return runInternal(id, params, runType, false);
    }

    /**
     * @param devTrial 显式以开发态试跑：即使任务已下线也允许（改完配置要先试运行才能重新发布）
     */
    public Map<String, Object> runInternal(long id, Map<String, Object> params, String runType,
                                           boolean devTrial) {
        synchronized (runLocks.computeIfAbsent(id, k -> new Object())) {
            if (busy(id)) {
                throw ApiException.conflict("任务正在运行，请等待本次运行结束");
            }
            Map<String, Object> task = requireWithSecrets(id);
            // v449 P1-4：下线过的任务不允许再被普通 run 拉起（之前 released=false 也能真起
            // K8s 作业）。从未发布过的任务仍允许试运行——发布门槛要求「先试运行成功」。
            boolean released = Boolean.TRUE.equals(task.get("released"));
            if (!released && !devTrial && Boolean.TRUE.equals(task.get("everReleased"))) {
                throw ApiException.conflict(
                        "任务已下线，不能直接运行：请先重新发布，或带 forceDev=true 以开发态试跑");
            }
            task.put("runParams", params == null ? Map.of() : params);
            String batchId = java.util.UUID.randomUUID().toString();
            FileIncrementService.Prepared prep = fileIncrement.prepare(id, task, batchId);
            if (prep.applicable() && prep.newFiles() == 0) {
                jdbc.update("""
                        update platform.sync_task set last_status = 'SKIPPED', last_run_at = now()
                        where id = ?
                        """, id);
                return Map.of("ok", true, "id", id, "status", "SKIPPED",
                        "message", prep.message() == null ? "没有新文件" : prep.message());
            }
            task.put("runType", runType);
            SyncTaskService.RunStart start = runWithRunId(task);
            String jobName = start.jobName();
            fileIncrement.bindBatch(id, batchId, jobName);
            runEvents.emit(start.id(), "prepare", "INFO",
                    "API".equals(runType) ? "Agent 提交作业" : "手动提交作业",
                    RunEventService.metrics("newFiles", prep.applicable() ? prep.newFiles() : -1));
            markSubmitted(id, jobName, false);
            return Map.of("ok", true, "id", id, "jobName", jobName, "runId", start.id(),
                    "status", "PENDING",
                    "newFiles", prep.applicable() ? prep.newFiles() : -1);
        }
    }

    /**
     * 周期调度入口：与手动运行同一路径，不走 HTTP 权限校验。
     * 由 {@link SyncTaskScheduler} 在 cron 到期时调用。
     */
    public String runScheduled(long id) {
        return runScheduled(id, true);
    }

    /**
     * @param resetRetry true 表示这是一次新的周期触发（重试计数归零）；
     *                   false 用于失败重试，保留已用次数
     */
    public String runScheduled(long id, boolean resetRetry) {
        synchronized (runLocks.computeIfAbsent(id, k -> new Object())) {
            // 手动运行与调度可能同时到达；这里再检查一次，避免文件登记/归档交叉
            if (busy(id)) {
                log.info("scheduled run skipped task={}: already running", id);
                return null;
            }
            return doRunScheduled(id, resetRetry);
        }
    }

    private boolean busy(long id) {
        try {
            String status = jdbc.queryForObject(
                    "select last_status from platform.sync_task where id = ?", String.class, id);
            return "PENDING".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status);
        } catch (Exception e) {
            return false;
        }
    }

    private String doRunScheduled(long id, boolean resetRetry) {
        if (resetRetry) {
            jdbc.update("update platform.sync_task set schedule_retry_count = 0 where id = ?", id);
        }
        Map<String, Object> task = requireWithSecrets(id);
        task.put("runParams", Map.of());
        String batchId = java.util.UUID.randomUUID().toString();
        FileIncrementService.Prepared prep = fileIncrement.prepare(id, task, batchId);
        if (prep.applicable() && prep.newFiles() == 0) {
            // 没有新文件也要推进 last_schedule_at，否则调度器每 30 秒重试一次
            jdbc.update("""
                    update platform.sync_task
                    set last_status = 'SKIPPED', last_run_at = now(), last_schedule_at = now()
                    where id = ?
                    """, id);
            log.info("scheduled run skipped task={} reason={}", id, prep.message());
            return null;
        }
        task.put("runType", "SCHEDULE");
        SyncTaskService.RunStart start = runWithRunId(task);
        String jobName = start.jobName();
        fileIncrement.bindBatch(id, batchId, jobName);
        runEvents.emit(start.id(), "prepare", "INFO", "调度触发提交作业", null);
        markSubmitted(id, jobName, true);
        return jobName;
    }

    /** 提交运行，并把 run id 放进 MDC，让这一段的后端日志都带 [run:xxx]。 */
    private SyncTaskService.RunStart runWithRunId(Map<String, Object> task) {
        long taskId = Long.parseLong(String.valueOf(task.get("id")));
        SyncTaskService.RunStart start = service.run(task);
        if (start.id() > 0) {
            log.info("run started task={} run={} job={}", taskId, start.id(), start.jobName());
        }
        return start;
    }

    public record BackfillBody(List<String> dates, String start, String end) {
    }

    /**
     * 补数：对指定业务日期逐天提交运行，运行参数只传 {@code bizdate}（内置变量，
     * SQL 里用 {@code ${bizdate}} 引用）。一次最多 10 天，避免瞬时打满作业配额。
     */
    @PostMapping("/{id}/backfill")
    @RequireAtom("ops:backfill")
    public Map<String, Object> backfill(@PathVariable long id,
                                        @RequestBody BackfillBody body) {
        Map<String, Object> probe = require(id);
        Object rawConfig = probe.get("sourceConfig");
        if (rawConfig instanceof Map<?, ?> cfg
                && Boolean.TRUE.equals(cfg.get("file_incremental"))) {
            throw ApiException.badRequest("文件级增量任务不支持补数：补数会重复读取同一批文件");
        }
        // P2-22：补数前先看是否已有正在运行的实例；否则 N 天会并发写同一目标表
        Object current = probe.get("lastStatus");
        if (current != null && java.util.Set.of("PENDING", "RUNNING")
                .contains(String.valueOf(current))) {
            throw ApiException.conflict("任务正在运行（" + current
                    + "），请等待结束或先停止，再提交补数");
        }
        List<java.time.LocalDate> dates = resolveBackfillDates(body);
        List<Map<String, Object>> runs = new java.util.ArrayList<>();
        List<String> failed = new java.util.ArrayList<>();
        for (java.time.LocalDate day : dates) {
            try {
                Map<String, Object> task = requireWithSecrets(id);
                String bizdate = day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                task.put("runParams", Map.of("bizdate", bizdate));
                task.put("runType", "BACKFILL");
                SyncTaskService.RunStart start = runWithRunId(task);
                String jobName = start.jobName();
                runEvents.emit(start.id(), "prepare", "INFO", "补数提交 bizdate=" + bizdate,
                        RunEventService.metrics("bizdate", bizdate));
                jdbc.update("""
                        update platform.sync_task
                        set last_job_name = ?, last_status = 'PENDING', last_run_at = now()
                        where id = ?
                        """, jobName, id);
                runs.add(Map.of("bizdate", bizdate, "jobName", jobName));
            } catch (Exception e) {
                failed.add(day + ": " + e.getMessage());
            }
        }
        return Map.of("ok", failed.isEmpty(), "id", id,
                "submitted", runs.size(), "runs", runs, "failed", failed);
    }

    /**
     * D16：补数日期严格解析。之前的 {@code ofPattern("y-M-d")} 默认
     * {@code ResolverStyle.SMART}，会把 2026-02-30 静默夹成 2026-02-28、
     * 按错误 bizdate 补数。改为 {@code u-M-d} + STRICT（与质量规则/工作流
     * 日期入口同口径）：非法日历日期直接 400。{@code u} 是公历年，
     * STRICT 下不能用 {@code y}（缺 era 会解析失败）。
     */
    private static final java.time.format.DateTimeFormatter BACKFILL_FLEX =
            java.time.format.DateTimeFormatter.ofPattern("u-M-d")
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    static List<java.time.LocalDate> resolveBackfillDates(BackfillBody body) {
        java.util.Set<java.time.LocalDate> dates = new java.util.TreeSet<>();
        if (body != null && body.dates() != null) {
            // 先看数量再解析：避免一个超大数组在 parse 阶段消耗 CPU
            if (body.dates().size() > 10) {
                throw ApiException.badRequest("一次最多补 10 天，当前 " + body.dates().size() + " 天");
            }
            for (String raw : body.dates()) {
                dates.add(parseBackfillDate(raw, BACKFILL_FLEX));
            }
        } else if (body != null && body.start() != null && body.end() != null) {
            java.time.LocalDate start = parseBackfillDate(body.start(), BACKFILL_FLEX);
            java.time.LocalDate end = parseBackfillDate(body.end(), BACKFILL_FLEX);
            if (end.isBefore(start)) {
                throw ApiException.badRequest("补数结束日期不能早于开始日期");
            }
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
            // 先按天数拒绝，再逐日展开：否则用户传 0001~9999 会先构造几百万个 LocalDate
            if (days > 10) {
                throw ApiException.badRequest("一次最多补 10 天，当前 " + days + " 天");
            }
            for (java.time.LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                dates.add(d);
            }
        } else {
            throw ApiException.badRequest("补数需要 dates，或 start + end");
        }
        if (dates.isEmpty()) {
            throw ApiException.badRequest("补数日期不能为空");
        }
        if (dates.size() > 10) {
            throw ApiException.badRequest("一次最多补 10 天，当前 " + dates.size() + " 天");
        }
        return new java.util.ArrayList<>(dates);
    }

    static java.time.LocalDate parseBackfillDate(String raw, java.time.format.DateTimeFormatter flex) {
        String v = raw == null ? "" : raw.trim();
        try {
            if (v.matches("\\d{8}")) {
                return java.time.LocalDate.parse(v,
                        java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            return java.time.LocalDate.parse(v, flex);
        } catch (Exception e) {
            throw ApiException.badRequest("补数日期不合法: " + raw + "（用 yyyy-MM-dd 或 yyyyMMdd）");
        }
    }

    /** 运行前校验：SQL + 源库 + 目标表；前端必须校验通过才允许提交。 */
    @PostMapping("/{id}/validate")
    @RequireAtom("sync:run")
    public Map<String, Object> validateRun(@PathVariable long id,
                                           @RequestBody(required = false) RunBody body) {
        Map<String, Object> task = requireWithSecrets(id);
        return service.validate(task, body == null || body.params() == null
                ? Map.of() : body.params());
    }

    /** 配置期校验：不保存任务，直接用表单内容检查连接器/SQL/连接/目标表。 */
    @PostMapping("/validate")
    @RequireAtom("sync:write")
    public Map<String, Object> validateConfig(@RequestBody TaskRequest req) {
        long projectId = req.projectId() == null ? 1L : req.projectId();
        ConnectorConfig source = resolve(projectId, req, true);
        ConnectorConfig sink = resolve(projectId, req, false);
        // 与保存同一条校验：缺 topic / 消费组 这类问题应在「配置校验」就报出来
        validate(req, false, source, sink);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", 0);
        task.put("projectId", projectId);
        task.put("sourceType", source.type());
        task.put("sourceConfig", source.config());
        task.put("sinkType", sink.type());
        task.put("sinkConfig", sink.config());
        return service.validate(task, SqlTemplates.sampleOverrides(source.config()));
    }

    /** 预览：传入运行参数，返回渲染后的 SQL 与参数快照；不提交作业。 */
    @PostMapping("/{id}/preview")
    @com.lab.dataworks.auth.RequirePermission(Permission.READ)
    public Map<String, Object> preview(@PathVariable long id,
                                       @RequestBody(required = false) RunBody body) {
        Map<String, Object> task = requireWithSecrets(id);
        return service.preview(task, body == null || body.params() == null
                ? Map.of() : body.params());
    }

    /** 最近运行快照：每次运行的参数与渲染 SQL。 */
    @GetMapping("/{id}/runs")
    public Map<String, Object> runs(@PathVariable long id) {
        require(id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, task_id as "taskId", job_name as "jobName",
                       params::text as "params", rendered_sql::text as "renderedSql",
                       created_by as "createdBy",
                       status, run_type as "runType", started_at as "startedAt",
                       finished_at as "finishedAt", duration_ms as "durationMs",
                       rows_read as "rowsRead", rows_written as "rowsWritten",
                       verify_mode as "verifyMode", verify_result as "verifyResult",
                       failure_reason as "failureReason",
                       created_at as "createdAt"
                from platform.sync_task_run where task_id = ?
                order by id desc limit 20
                """, id);
        // 列表与详情保持同一契约：jsonb 直接返回会被 Jackson 包成 {type,value}
        for (Map<String, Object> row : rows) {
            for (String key : List.of("params", "renderedSql")) {
                Object raw = row.get(key);
                if (raw instanceof String text && !text.isBlank()) {
                    try {
                        row.put(key, json.readValue(text, Object.class));
                    } catch (Exception ignored) {
                        // 脏数据保持原样
                    }
                }
            }
        }
        return Map.of("count", rows.size(), "runs", rows);
    }

    /** 单个运行实例详情。 */
    @GetMapping("/runs/{runId}")
    public Map<String, Object> runDetail(@PathVariable long runId) {
        requireRun(runId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.task_id as "taskId", t.code as "taskCode", t.name as "taskName",
                       r.job_name as "jobName", r.run_type as "runType", r.status,
                       r.params::text as "params", r.rendered_sql::text as "renderedSql",
                       r.created_by as "createdBy",
                       r.started_at as "startedAt", r.finished_at as "finishedAt",
                       r.duration_ms as "durationMs", r.rows_read as "rowsRead",
                       r.rows_written as "rowsWritten", r.verify_mode as "verifyMode",
                       r.verify_result as "verifyResult", r.failure_reason as "failureReason",
                       r.created_at as "createdAt"
                from platform.sync_task_run r
                left join platform.sync_task t on t.id = r.task_id
                where r.id = ?
                """, runId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("运行实例不存在: " + runId);
        }
        Map<String, Object> row = rows.get(0);
        for (String key : List.of("params", "renderedSql")) {
            Object raw = row.get(key);
            if (raw instanceof String text && !text.isBlank()) {
                try {
                    row.put(key, json.readValue(text, Object.class));
                } catch (Exception ignored) {
                    // 保持原样
                }
            }
        }
        return row;
    }

    /** 保留期清理：事件 90 天、采样 30 天。 */
    /**
     * 运行历史保留期清理：删的是**全平台**的 sync_run_event / sync_run_metric（SQL 无租户条件）。
     * v449 P0-1：之前只有 ops:stop（各租户 ADMIN 都有），tenant_b 一条请求就能清掉其他租户的
     * 运行时间线与指标。现在改为平台管理员专属，并要求 confirm=true 显式确认。
     */
    @PostMapping("/runs/prune")
    @RequireAtom("system:manage")
    public Map<String, Object> pruneRuns(@RequestParam(required = false) Boolean confirm,
                                         HttpServletRequest http) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw ApiException.badRequest(
                    "清理全平台运行历史需要显式确认：请传 confirm=true");
        }
        if (!com.lab.dataworks.common.TenantGuard.platform()) {
            throw new ApiException(403, "平台级操作仅平台管理员（默认租户）可执行");
        }
        int events = runEvents.pruneEvents(90);
        int metrics = runMetrics.pruneSamples(30);
        sessions.audit(String.valueOf(http.getAttribute("username")), "RUN_HISTORY_PRUNE",
                "platform", "events=" + events + ", samples=" + metrics, "SUCCESS",
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return Map.of("ok", true, "eventsDeleted", events, "samplesDeleted", metrics);
    }

    /** 运行实例的业务事件（时间线）。 */
    @GetMapping("/runs/{runId}/events")
    public Map<String, Object> runEventList(@PathVariable long runId) {
        requireRun(runId);
        return Map.of("runId", runId, "events", runEvents.events(runId));
    }

    /** 实时运行的周期采样（运行时长、行列数、Kafka lag）。 */
    @GetMapping("/runs/{runId}/metrics")
    public Map<String, Object> runMetricList(@PathVariable long runId) {
        requireRun(runId);
        return Map.of("runId", runId, "samples", runMetrics.samples(runId));
    }

    /** 运行实例日志：每行带 [run:id] 前缀，内容已脱敏。 */
    @GetMapping("/runs/{runId}/logs")
    public Map<String, Object> runLogs(@PathVariable long runId) {
        requireRun(runId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select job_name as "jobName" from platform.sync_task_run where id = ?
                """, runId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("运行实例不存在: " + runId);
        }
        String jobName = String.valueOf(rows.get(0).get("jobName"));
        String log = service.logs(jobName);
        String prefix = "[run:" + runId + "] ";
        String body = log == null ? "" : java.util.Arrays.stream(log.split("\\n", -1))
                .map(line -> prefix + line)
                .collect(java.util.stream.Collectors.joining("\n"));
        return Map.of("runId", runId, "jobName", jobName, "log", body);
    }

    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable long id) {
        return refreshStatus(id);
    }

    /**
     * REQ-DW-17：单次运行的结果摘要（记录数 / 字段清单 / 项目→库→表落点 / 失败阶段）。
     * 只读 DB 快照，不触发 k8s 刷新——刷新走既有 {@code /{id}/status}，前端先刷再取摘要。
     */
    @GetMapping("/runs/{runId}/summary")
    public Map<String, Object> runSummary(@PathVariable long runId) {
        return runSummary.forRun(runId);
    }

    /**
     * REQ-DW-17：任务最近一次运行的结果摘要；从未运行返回同契约的 NEVER_RUN，
     * 前端周期任务与一次性任务共用一套渲染。
     */
    @GetMapping("/{id}/summary")
    public Map<String, Object> taskSummary(@PathVariable long id) {
        return runSummary.forTask(id);
    }

    /**
     * 同步作业真实状态到任务表；首次看到 SUCCESS 时推进 JDBC 水位、归档文件。
     * 前端轮询与调度器对账（{@link SyncTaskScheduler}）共用这一条路径。
     */
    /** 兜底清理过期作业 Secret（调度器每分钟调一次）。 */
    public void cleanupStaleSecrets() {
        service.cleanupStaleSecrets();
    }

    /** 调度器超时终止：删除 k8s Job（SeaTunnel 客户端退出后作业随之取消）。 */
    /**
     * P2-21：k8s Job 已经创建、但任务行更新失败时，必须把 Job 删掉再抛错。
     * 否则调度 catch 清掉 claim，下一轮又建一个 Job——同一任务重复提交。
     */
    private void markSubmitted(long taskId, String jobName, boolean scheduled) {
        try {
            if (scheduled) {
                jdbc.update("""
                        update platform.sync_task
                        set last_job_name = ?, last_status = 'PENDING',
                            last_run_at = now(), last_schedule_at = now()
                        where id = ?
                        """, jobName, taskId);
            } else {
                jdbc.update("""
                        update platform.sync_task
                        set last_job_name = ?, last_status = 'PENDING', last_run_at = now()
                        where id = ?
                        """, jobName, taskId);
            }
        } catch (RuntimeException e) {
            try {
                stopJob(jobName);
            } catch (RuntimeException ignored) {
                // 补偿删除失败也要把原始异常抛出去，下一轮对账不会再看到这个 job
            }
            throw e;
        }
    }

    public void stopJob(String jobName) {
        try {
            service.stop(jobName);
        } catch (Exception e) {
            log.warn("stop job {} failed: {}", jobName, e.getMessage());
        }
    }

    /** 收尾被 last_job_name 覆盖掉的旧 run：状态以实际 job 为准。 */
    private void reconcileStaleRuns(long taskId, String currentJob) {
        List<Map<String, Object>> stale = jdbc.queryForList("""
                select job_name from platform.sync_task_run
                where task_id = ? and status in ('SUBMITTED', 'RUNNING', 'QUEUED')
                  and job_name is not null and job_name <> ?
                  and created_at > now() - interval '2 days'
                order by id limit 10
                """, taskId, currentJob == null ? "" : currentJob);
        for (Map<String, Object> row : stale) {
            String staleJob = String.valueOf(row.get("job_name"));
            try {
                Map<String, Object> r = service.status(staleJob);
                String status = String.valueOf(r.get("status"));
                if (!java.util.Set.of("SUCCESS", "FAILED", "TIMEOUT", "GONE").contains(status)) {
                    continue;
                }
                jdbc.update("""
                        update platform.sync_task_run
                        set status = ?,
                            finished_at = coalesce(finished_at, now()),
                            duration_ms = coalesce(duration_ms,
                                (extract(epoch from (now() - coalesce(started_at, created_at)))
                                 * 1000)::bigint),
                            rows_read = coalesce(?, rows_read),
                            rows_written = coalesce(?, rows_written)
                        where task_id = ? and job_name = ? and status in ('SUBMITTED', 'RUNNING', 'QUEUED')
                        """, status, asLong(r.get("rowsRead")), asLong(r.get("rowsWritten")),
                        taskId, staleJob);
            } catch (Exception e) {
                log.warn("reconcile stale run {} failed: {}", staleJob, e.getMessage());
            }
        }
    }

    public Map<String, Object> refreshStatus(long id) {
        Map<String, Object> task = require(id);
        Object jobName = task.get("lastJobName");
        String previous = task.get("lastStatus") == null ? null : String.valueOf(task.get("lastStatus"));
        Map<String, Object> result = service.status(jobName == null ? null : String.valueOf(jobName));
        String status = String.valueOf(result.get("status"));
        // Job 完成 30 分钟后会被 ttlSecondsAfterFinished 清掉，之后 status 变 GONE。
        // 已经成功的运行不能因为 Job 被回收就降级成 GONE。
        if ("GONE".equals(status) && java.util.Set.of(
                "SUCCESS", "FAILED", "TIMEOUT", "CANCELLED", "STOPPED").contains(previous)) {
            status = previous;
            result.put("status", previous);
        }

        // 校验：Job succeeded 不等于数据正确。verify=ROWS 时对比源读取与汇写入行数
        Long rowsRead = asLong(result.get("rowsRead"));
        Long rowsWritten = asLong(result.get("rowsWritten"));
        String verifyMode = runVerifyMode(jobName == null ? null : String.valueOf(jobName));
        SyncTaskService.VerifyOutcome verify =
                SyncTaskService.verify(verifyMode, status, rowsRead, rowsWritten);
        status = verify.status();
        String verifyResult = verify.verifyResult();
        if (verify.reason() != null) {
            result.put("status", status);
            result.put("failureReason", verify.reason());
        }
        String failureReason = result.get("failureReason") == null
                ? null : String.valueOf(result.get("failureReason"));
        if (jobName != null) {
            boolean terminal = java.util.Set.of("SUCCESS", "FAILED", "TIMEOUT", "GONE", "STOPPED")
                    .contains(status);
            jdbc.update("""
                    update platform.sync_task_run
                    set status = ?,
                        finished_at = case when ? then now() else finished_at end,
                        duration_ms = case when ?
                            then (extract(epoch from (now() - coalesce(started_at, created_at))) * 1000)::bigint
                            else duration_ms end,
                        rows_read = coalesce(?, rows_read),
                        rows_written = coalesce(?, rows_written),
                        verify_result = coalesce(?, verify_result),
                        failure_reason = case when ? = 'SUCCESS' then null
                                              else coalesce(?, failure_reason) end
                    where job_name = ?
                    """, status, terminal, terminal, rowsRead, rowsWritten,
                    verifyResult, status, failureReason, String.valueOf(jobName));
        }
        // 补数/重复提交会留下多个 job：只对 last_job_name 更新会让旧 run 永远 RUNNING。
        // 这里顺带按真实 job 状态收尾其他未完成 run（最多 10 条，避免每次轮询打爆 k8s）。
        reconcileStaleRuns(id, jobName == null ? null : String.valueOf(jobName));
        // 业务事件：只在状态跃迁时写，轮询不会重复刷
        long runId = runIdOf(jobName);
        if (runId > 0) {
            boolean terminal = java.util.Set.of("SUCCESS", "FAILED", "TIMEOUT", "GONE", "STOPPED")
                    .contains(status);
            boolean wasTerminal = java.util.Set.of("SUCCESS", "FAILED", "TIMEOUT", "GONE", "STOPPED")
                    .contains(previous);
            if ("RUNNING".equals(status) && !"RUNNING".equals(previous)) {
                runEvents.emit(runId, "read", "INFO", "作业开始运行", null);
            }
            if (terminal && !wasTerminal) {
                if (rowsRead != null || rowsWritten != null) {
                    runEvents.emit(runId, "write", "INFO", "作业完成读取/写入",
                            RunEventService.metrics("rowsRead", rowsRead,
                                    "rowsWritten", rowsWritten));
                }
                if (verifyResult != null) {
                    runEvents.emit(runId, "verify",
                            "PASSED".equals(verifyResult) ? "INFO" : "ERROR",
                            "校验 " + verifyResult
                                    + (verify.reason() == null ? "" : "：" + verify.reason()),
                            RunEventService.metrics("verifyMode", verifyMode,
                                    "verifyResult", verifyResult));
                }
                runEvents.emit(runId, "done", "SUCCESS".equals(status) ? "INFO" : "ERROR",
                        "运行结束：" + status + (failureReason == null ? "" : " - " + failureReason),
                        RunEventService.metrics("durationMs",
                                jdbc.queryForObject("""
                                        select duration_ms from platform.sync_task_run
                                        where job_name = ?
                                        """, Long.class, String.valueOf(jobName))));
            }
        }
        if (java.util.Set.of("SUCCESS", "FAILED", "TIMEOUT", "GONE", "STOPPED").contains(status)) {
            service.cleanupSecret(jobName == null ? null : String.valueOf(jobName));
        }
        // 只在“首次看到 SUCCESS”时推进水位/归档，避免每次轮询都重复处理
        if ("SUCCESS".equals(status) && !"SUCCESS".equals(previous)) {
            jdbc.update("update platform.sync_task set last_success_at = now() where id = ?", id);
            // 补数按历史业务日期重跑，不能把水位推到当前 max：否则补数区间之后、
            // 今天之前的积压永远不会再被读。只有手动/调度/API 运行推进水位。
            String runType = jobName == null ? null : runTypeOf(String.valueOf(jobName));
            if (!"BACKFILL".equalsIgnoreCase(runType)) {
                service.advanceWatermark(id, jobName == null ? null : String.valueOf(jobName));
            } else {
                log.info("backfill success task={} job={}: watermark not advanced", id, jobName);
            }
            try {
                int archived = fileIncrement.archive(id, requireWithSecrets(id),
                        jobName == null ? null : String.valueOf(jobName));
                if (archived > 0) {
                    result.put("archivedFiles", archived);
                }
            } catch (Exception e) {
                log.warn("file archive failed task={}: {}", id, e.getMessage());
            }
        }
        // 终态最后写：水位/归档失败时保持上一状态，下一轮轮询会重试副作用，
        // 而不是因为库里已经写 SUCCESS 就永久跳过。
        jdbc.update("update platform.sync_task set last_status = ? where id = ?", status, id);
        result.put("id", id);
        return result;
    }

    /** 当前作业的运行类型（MANUAL / SCHEDULE / BACKFILL / API）；查不到返回 null。 */
    private String runTypeOf(String jobName) {
        try {
            List<String> types = jdbc.queryForList("""
                    select run_type from platform.sync_task_run where job_name = ?
                    """, String.class, jobName);
            return types.isEmpty() ? null : types.get(0);
        } catch (Exception e) {
            log.warn("read run type failed job={}: {}", jobName, e.getMessage());
            return null;
        }
    }

    private long runIdOf(Object jobName) {
        if (jobName == null || String.valueOf(jobName).isBlank()) {
            return 0;
        }
        List<Long> ids = jdbc.queryForList("""
                select id from platform.sync_task_run where job_name = ?
                """, Long.class, String.valueOf(jobName));
        return ids.isEmpty() || ids.get(0) == null ? 0 : ids.get(0);
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private String runVerifyMode(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return "ROWS";
        }
        List<String> modes = jdbc.queryForList("""
                select verify_mode from platform.sync_task_run where job_name = ?
                """, String.class, jobName);
        return modes.isEmpty() || modes.get(0) == null ? "ROWS" : modes.get(0);
    }

    /** 文件级增量：本任务登记过的文件（最近 100 条），用于确认“这次会读哪些文件”。 */
    @GetMapping("/{id}/files")
    public Map<String, Object> files(@PathVariable long id) {
        require(id);
        List<Map<String, Object>> rows = fileIncrement.recentFiles(id, 100);
        return Map.of("count", rows.size(), "files", rows);
    }


    @GetMapping("/{id}/watermarks")
    public Map<String, Object> watermarks(@PathVariable long id) {
        // v449 P1-5：之前按 id 直查，既无租户条件也不判存在性，tenant_b 可读任意任务水位
        require(id);
        return Map.of("watermarks", jdbc.queryForList("""
                select table_name as "table", cursor_column as "column",
                       watermark_value as "value", updated_at as "updatedAt"
                from platform.sync_task_watermark where task_id = ? order by table_name
                """, id));
    }

    /**
     * 日志/conf 是纯文本接口，但不能用 {@code produces=text/plain} 把请求方锁死：
     * 客户端带 {@code Accept: application/json} 时 Spring 会抛
     * HttpMediaTypeNotAcceptableException，落到通用 500（审计里的「conf 恒 500」）。
     * 这里显式返回 text/plain + 200，Accept 不再参与淘汰。
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<String> logs(@PathVariable long id) {
        Map<String, Object> task = require(id);
        Object jobName = task.get("lastJobName");
        return text(service.logs(jobName == null ? null : String.valueOf(jobName)));
    }

    @GetMapping("/{id}/conf")
    @RequireAtom("sync:write")
    public ResponseEntity<String> conf(@PathVariable long id) {
        Map<String, Object> task = requireWithSecrets(id);
        // 预览必须是「真正会执行的配置」：先渲染 ${bizdate} 等模板并跑只读校验。
        // renderConf 自身的异常（NPE/类型不匹配等）不能落到通用 500：配置类错误
        // 明确返回 400 + 原因，便于用户按提示改配置。
        try {
            Object rawSource = task.get("sourceConfig");
            Map<String, Object> overrides = rawSource instanceof Map<?, ?> source
                    ? SqlTemplates.sampleOverrides(castMap(source)) : Map.of();
            SqlTemplates.apply(task, overrides);
            return text(maskSecrets(service.renderConf(task)));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw ApiException.badRequest("渲染 conf 失败: "
                    + (detail.length() > 500 ? detail.substring(0, 500) : detail));
        }
    }

    /**
     * S7：conf 是给用户看的预览，但里面带着口令。
     * SeaTunnel 用 HOCON（{@code password = "xxx"}），历史配置里也有 JSON 写法，
     * 两种都抹掉，只留首尾各 2 个字符便于核对。
     */
    private static String maskSecrets(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String masked = body.replaceAll(
                "(?i)([\"']?(?:password|passwd|secret[_-]?key|access[_-]?key|token)[\"']?\\s*[=:]\\s*[\"'])([^\"']{1,200})([\"'])",
                "$1******$3");
        return masked;
    }

    private ResponseEntity<String> text(String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .body(body == null ? "" : body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> source) {
        return (Map<String, Object>) source;
    }

    private Map<String, Object> get(long id) {
        return require(id);
    }

    /** 运行实例归属：run → task → project → tenant，取不到 404（v447 by-id 收口）。 */
    private void requireRun(long runId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.sync_task_run r
                 join platform.sync_task t on t.id = r.task_id
                 join platform.project p on p.id = t.project_id
                 where r.id = ? and p.tenant_id = ?
                """, Long.class, runId, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("运行实例不存在: " + runId);
        }
    }

    private Map<String, Object> require(long id) {
        // SELECT 里已经有 where tenant_id = ?（第三期加的），这里只能追加 and
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " and id = ?",
                com.lab.dataworks.auth.TenantScope.current(), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("同步任务不存在: " + id);
        }
        return normalize(rows.get(0));
    }

    private Map<String, Object> requireWithSecrets(long id) {
        Map<String, Object> task = require(id);
        // 密码列不进 list SELECT，单独取，避免列表接口泄密
        Map<String, Object> passwords = jdbc.queryForMap("""
                select source_password as "sourcePassword", sink_password as "sinkPassword"
                from platform.sync_task where id = ?
                """, id);
        task.putAll(passwords);
        task.put("sourceConfig", withDatasource(task, true));
        task.put("sinkConfig", withDatasource(task, false));
        return task;
    }

    /**
     * 把任务配置与数据源的最新连接信息/密钥合并。任务表里不存密钥（新任务），
     * 旧任务的 legacy password 列在这里解密后放入 config。
     */
    private Map<String, Object> withDatasource(Map<String, Object> task, boolean source) {
        String side = source ? "source" : "sink";
        String type = string(task.get(side + "Type"));
        Map<String, Object> config = new LinkedHashMap<>(jsonMap(task.get(side + "Config")));
        Long dsId = number(task.get(side + "DatasourceId"));
        if (dsId != null) {
            Map<String, Object> ds = datasource(dsId);
            String dsType = string(ds.get("ds_type"));
            String connector = connectorFor(dsType);
            if (type == null || type.isBlank()) {
                type = connector;
                task.put(side + "Type", connector);
            }
            Map<String, Object> dsConfig = jsonMap(ds.get("config_json"));
            // 任务级配置优先于数据源默认值（例如任务的 path 覆盖数据源里的 path）
            dsConfig.forEach(config::putIfAbsent);
            switch (connector) {
                case "Jdbc" -> {
                    config.put("url", ds.get("jdbc_url"));
                    config.put("driver", driverFor(dsType));
                    config.put("user", ds.get("username"));
                    config.put("password", ds.get("password"));
                    // F-A：DORIS 型数据源但任务显式选 Jdbc 连接器时，自动建表需要认出
                    // 「这其实是个 Doris」。只在这条运行时展开里加，不持久化。
                    config.put("sinkEngineType", dsType);
                }
                case "Doris" -> {
                    config.put("fenodes", fenodesFor(string(ds.get("jdbc_url")), dsType));
                    config.put("username", ds.get("username"));
                    config.put("password", ds.get("password"));
                    // F-A：FE MySQL 口原地址（渲染 SeaTunnel conf 不用它，只在缺表自动建表时连 9030 执行 DDL）
                    config.put("jdbcUrl", ds.get("jdbc_url"));
                    config.put("sinkEngineType", dsType);
                }
                case "S3File" -> {
                    config.put("accessKey", ds.get("username"));
                    config.put("secretKey", ds.get("password"));
                }
                case "MongoDB", "Redis", "ClickHouse" -> {
                    if (ds.get("username") != null) {
                        config.putIfAbsent("user", ds.get("username"));
                    }
                    if (ds.get("password") != null) {
                        config.put("password", ds.get("password"));
                    }
                }
                default -> {
                    if (ds.get("username") != null) {
                        config.putIfAbsent("user", ds.get("username"));
                        config.putIfAbsent("username", ds.get("username"));
                    }
                    if (ds.get("password") != null) {
                        config.put("password", ds.get("password"));
                    }
                }
            }
        } else {
            // 旧任务：密码在 legacy 列，解密后放入 config
            Object pwd = task.get(side + "Password");
            if (pwd != null) {
                config.put("password", cipher.decrypt(String.valueOf(pwd)));
            }
        }
        return config;
    }

    private ConnectorConfig resolve(long projectId, TaskRequest req, boolean source) {
        Long dsId = source ? req.sourceDatasourceId() : req.sinkDatasourceId();
        String requestedType = source ? req.sourceType() : req.sinkType();
        Map<String, Object> requestedConfig = source ? req.sourceConfig() : req.sinkConfig();
        if (dsId != null) {
            Map<String, Object> ds = datasource(projectId, dsId, source ? "源" : "目标");
            String connector = connectorFor(string(ds.get("ds_type")));
            Map<String, Object> config = new LinkedHashMap<>(jsonMap(ds.get("config_json")));
            if (requestedConfig != null) {
                config.putAll(requestedConfig);
            }
            switch (connector) {
                case "Jdbc" -> {
                    config.put("url", ds.get("jdbc_url"));
                    config.put("driver", driverFor(string(ds.get("ds_type"))));
                    config.put("user", ds.get("username"));
                    config.put("password", ds.get("password"));
                    if (source) {
                        config.putIfAbsent("query", req.sourceQuery());
                    } else {
                        config.putIfAbsent("table", sinkTable(req));
                    }
                }
                case "Doris" -> {
                    config.put("fenodes", fenodesFor(string(ds.get("jdbc_url")), string(ds.get("ds_type"))));
                    config.put("username", ds.get("username"));
                    config.put("password", ds.get("password"));
                    if (!source) {
                        config.put("database", blankTo(req.sinkDatabase(),
                                string(config.get("database"))));
                        config.put("table", blankTo(req.sinkTable(), string(config.get("table"))));
                    }
                }
                case "S3File" -> {
                    config.put("accessKey", ds.get("username"));
                    config.put("secretKey", ds.get("password"));
                }
                case "MongoDB", "Redis", "ClickHouse" -> {
                    if (ds.get("username") != null) {
                        config.putIfAbsent("user", ds.get("username"));
                    }
                    if (ds.get("password") != null) {
                        config.put("password", ds.get("password"));
                    }
                }
                default -> {
                    // 通用连接器：用户名/密码列同时提供 user 和 username 两种键名
                    if (ds.get("username") != null) {
                        config.putIfAbsent("user", ds.get("username"));
                        config.putIfAbsent("username", ds.get("username"));
                    }
                    if (ds.get("password") != null) {
                        config.put("password", ds.get("password"));
                    }
                }
            }
            // 这里必须保留解密后的密码：配置校验要真的连库。落库前的
            // sanitize 由 create/update 负责，别在这一层提前抹掉导致“密码错”。
            return new ConnectorConfig(connector, config, dsId);
        }

        String type = canonical(requestedType, source);
        Map<String, Object> config = new LinkedHashMap<>();
        if (requestedConfig != null) {
            config.putAll(requestedConfig);
        }
        if ("Jdbc".equals(type)) {
            config.putIfAbsent("url", req.sourceJdbcUrl());
            config.putIfAbsent("driver", req.sourceDriver());
            config.putIfAbsent("user", source ? req.sourceUser() : req.sinkUser());
            config.putIfAbsent("password", source ? req.sourcePassword() : req.sinkPassword());
            if (source) {
                config.putIfAbsent("query", req.sourceQuery());
            } else {
                config.putIfAbsent("table", sinkTable(req));
            }
            // 不能在这里 sanitize：create/update 还要用 config 里的密码加密落库；
            // 写 config_json 时再 sanitize（见 create/update）。
            return new ConnectorConfig("Jdbc", config, null);
        }
        if ("Doris".equals(type)) {
            config.putIfAbsent("fenodes", req.sinkFenodes());
            config.putIfAbsent("username", req.sinkUser());
            config.putIfAbsent("password", req.sinkPassword());
            config.putIfAbsent("database", req.sinkDatabase());
            config.putIfAbsent("table", req.sinkTable());
            return new ConnectorConfig("Doris", config, null);
        }
        // 非 JDBC 的连接信息含密钥，v1 要求先建数据源，避免明文进 sync_task。
        // 必须递归检查：S3 的 fs.s3a.secret.key、MongoDB URI 里的 user:pass
        // 都不是顶层 password/secretKey。
        if (containsSecret(config)) {
            throw ApiException.badRequest("非 JDBC 连接器请先创建数据源，再在任务里引用，避免密钥明文入库");
        }
        return new ConnectorConfig(type, sanitize(config), null);
    }

    /**
     * 上游依赖校验：必须存在、同项目、且不能形成环。
     * 环里的任务永远不会同时满足「上游已成功」，只会每 30 秒静默跳过。
     */
    private void validateDependency(long projectId, Long dependsOn, long selfId) {
        if (dependsOn == null) {
            return;
        }
        if (dependsOn <= 0) {
            throw ApiException.badRequest("上游任务 id 不合法: " + dependsOn);
        }
        if (dependsOn == selfId) {
            throw ApiException.badRequest("不能依赖自己");
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        Long current = dependsOn;
        for (int depth = 0; current != null && depth < 50; depth++) {
            if (!seen.add(current)) {
                throw ApiException.badRequest("依赖关系存在环");
            }
            if (current == selfId) {
                throw ApiException.badRequest("依赖关系存在环：上游最终指回当前任务");
            }
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select project_id, depends_on_task_id
                    from platform.sync_task where id = ?
                    """, current);
            if (rows.isEmpty()) {
                throw ApiException.badRequest("上游任务不存在: " + current);
            }
            long owner = ((Number) rows.get(0).get("project_id")).longValue();
            if (owner != projectId) {
                throw ApiException.badRequest("上游任务不属于当前项目: " + current);
            }
            Object next = rows.get(0).get("depends_on_task_id");
            current = next instanceof Number n ? n.longValue() : null;
        }
        if (current != null) {
            throw ApiException.badRequest("依赖链过深（超过 50 层）");
        }
    }

    private void validate(TaskRequest req, boolean creating, ConnectorConfig source, ConnectorConfig sink) {
        if (creating) {
            if (req.projectId() == null) {
                throw ApiException.badRequest("projectId 不能为空");
            }
            if (req.code() == null || !req.code().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                throw ApiException.badRequest("任务编码不合法（字母开头，只含字母数字下划线）");
            }
        }
        if (isBlank(req.name())) {
            throw ApiException.badRequest("任务名称不能为空");
        }
        if (req.name().trim().length() > 128) {
            throw ApiException.badRequest("任务名称不能超过 128 个字符");
        }
        String sourceType = canonical(source.type(), true);
        String sinkType = canonical(sink.type(), false);
        catalog.requireRunnable(sourceType, true);
        catalog.requireRunnable(sinkType, false);
        requireKeys(sourceType, source.config(), true);
        requireKeys(sinkType, sink.config(), false);
        // 8.4 CDC 版本闸门已在 SeaTunnel 2.3.13 升级后移除（2.3.12+ 已适配
        // SHOW BINARY LOG STATUS），见 docs/数据集成-MySQL版本矩阵-20261020.md 4.3。
        if (!"BATCH".equalsIgnoreCase(jobMode(req)) && !"STREAMING".equalsIgnoreCase(jobMode(req))) {
            throw ApiException.badRequest("jobMode 只能是 BATCH 或 STREAMING");
        }
        String type = taskType(req);
        if (!"OFFLINE".equals(type) && !"REALTIME".equals(type)) {
            throw ApiException.badRequest("taskType 只能是 OFFLINE（离线）或 REALTIME（实时）");
        }
        if (!isBlank(req.scheduleTimezone())) {
            try {
                java.time.ZoneId.of(req.scheduleTimezone().trim());
            } catch (java.time.DateTimeException e) {
                throw ApiException.badRequest("scheduleTimezone 不合法: " + req.scheduleTimezone());
            }
        }
        if (Boolean.TRUE.equals(req.scheduleEnabled())) {
            if ("REALTIME".equals(type)) {
                throw ApiException.badRequest("实时任务常驻运行，不支持周期调度；请使用离线任务");
            }
            if (isBlank(req.scheduleCron())) {
                throw ApiException.badRequest("开启周期调度时必须填写 scheduleCron");
            }
            try {
                org.springframework.scheduling.support.CronExpression.parse(
                        SyncTaskScheduler.normalizeCron(req.scheduleCron()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("cron 表达式不合法: " + req.scheduleCron()
                        + "（5 段：分 时 日 月 周；6 段：秒 分 时 日 月 周）");
            }
        }
        if (req.dependsOnTaskId() != null && req.dependsOnTaskId() <= 0) {
            throw ApiException.badRequest("上游任务 id 不合法");
        }
        Object incremental = source.config().get("file_incremental");
        boolean fileIncremental = Boolean.TRUE.equals(incremental)
                || "true".equalsIgnoreCase(String.valueOf(incremental));
        if (fileIncremental) {
            if (!"SftpFile".equals(sourceType) && !"FtpFile".equals(sourceType)
                    && !"S3File".equals(sourceType) && !"LocalFile".equals(sourceType)) {
                throw ApiException.badRequest(
                        "文件级增量支持 SftpFile / FtpFile / S3File / LocalFile 源，当前源类型为 "
                                + sourceType);
            }
            if (isBlank(string(source.config().get("path")))) {
                throw ApiException.badRequest("文件级增量必须配置源目录 path");
            }
            if (FileIncrementService.archiveInsideSource(source.config())) {
                throw ApiException.badRequest(
                        "归档目录不能放在源目录内部：SeaTunnel 文件源会递归读取子目录，"
                                + "归档文件会被下次运行重新读入；请放到源目录旁边（如 <path>_done）");
            }
            // P3-29：保存/校验时就拦住非法正则，别等到运行时才失效
            FileIncrementService.compileFilter(source.config().get("file_filter_pattern"));
        }
    }

    private void requireKeys(String type, Map<String, Object> config, boolean source) {
        switch (type) {
            case "Jdbc" -> {
                require(config, "url");
                Object tables = config.get("tables");
                boolean multi = tables instanceof java.util.List<?> list && !list.isEmpty();
                if (source) {
                    if (!multi) require(config, "query");
                } else if (!multi) {
                    require(config, "table");
                }
            }
            case "Doris" -> {
                require(config, "fenodes");
                // Source 的 2.3.9 插件强制要求 database/table；只给 fenodes
                // 能保存成功但作业必失败，应在配置期拦下。
                if (isBlank(string(config.get("table.identifier")))) {
                    require(config, "database");
                    require(config, "table");
                }
            }
            case "S3File" -> {
                require(config, "bucket");
                require(config, "path");
                require(config, "endpoint");
            }
            case "LocalFile" -> require(config, "path");
            case "MongoDB" -> {
                Object uri = config.get("uri");
                Object host = config.get("host");
                if ((uri == null || String.valueOf(uri).isBlank())
                        && (host == null || String.valueOf(host).isBlank())) {
                    require(config, "uri");
                }
                require(config, "database");
                require(config, "collection");
            }
            case "Redis" -> {
                require(config, "host");
                // keys 是源端扫描模式；汇端由上游的 key 字段决定，不能强制要求
                if (source) require(config, "keys");
            }
            case "Kafka" -> {
                require(config, "bootstrap.servers");
                require(config, "topic");
                // 消费组决定位点与积压统计：没有它 SeaTunnel 会用默认组，多任务互相顶位点
                if (source) {
                    require(config, "consumer.group");
                    // json 消息必须声明字段，否则 SeaTunnel 到运行时才报「schema 缺失」
                    String format = String.valueOf(config.getOrDefault("format", "json"));
                    if ("json".equalsIgnoreCase(format) && config.get("schema") == null) {
                        throw ApiException.badRequest(
                                "Kafka 源使用 json 格式时必须配置字段定义 schema（消息字段 JSON）");
                    }
                }
            }
            case "ClickHouse" -> {
                require(config, "host");
                require(config, "database");
                require(config, "table");
            }
            default -> { }
        }
    }

    private void require(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw ApiException.badRequest("缺少连接器配置: " + key);
        }
    }

    private Map<String, Object> datasource(long projectId, long id, String label) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, ds_type, jdbc_url, username, password, config_json
                from platform.datasource where id = ? and project_id = ?
                """, id, projectId);
        if (rows.isEmpty()) {
            throw ApiException.badRequest(label + "数据源不存在或不属于当前项目: " + id);
        }
        Map<String, Object> row = rows.get(0);
        row.put("password", row.get("password") == null ? null
                : cipher.decrypt(String.valueOf(row.get("password"))));
        return row;
    }

    private Map<String, Object> datasource(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, ds_type, jdbc_url, username, password, config_json
                from platform.datasource where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("数据源不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        row.put("password", row.get("password") == null ? null
                : cipher.decrypt(String.valueOf(row.get("password"))));
        return row;
    }

    private Map<String, Object> normalize(Map<String, Object> row) {
        row.put("sourceType", blankTo(string(row.get("sourceType")), "Jdbc"));
        row.put("sinkType", blankTo(string(row.get("sinkType")), "Doris"));
        row.put("sourceConfig", jsonMap(row.get("sourceConfig")));
        row.put("sinkConfig", jsonMap(row.get("sinkConfig")));
        // 下次触发时间：只对开了调度的离线任务计算，前端直接展示
        if (Boolean.TRUE.equals(row.get("scheduleEnabled"))) {
            row.put("nextRunAt", SyncTaskScheduler.nextRunAt(
                    string(row.get("scheduleCron")), string(row.get("scheduleTimezone"))));
        }
        return row;
    }

    private String connectorFor(String dsType) {
        return switch (dsType.toUpperCase()) {
            // 这些方言在 2.3.9 都由 connector-jdbc + 驱动支持；漏掉会让
            // withDatasource 走 default 分支，只带 user/password 不带 jdbc_url。
            case "POSTGRESQL", "MYSQL", "ORACLE", "SQLSERVER", "DB2",
                 "GREENPLUM", "VERTICA", "TIDB" -> "Jdbc";
            case "DORIS" -> "Doris";
            case "S3", "S3FILE" -> "S3File";
            case "LOCALFILE", "LOCAL_FILE" -> "LocalFile";
            case "MONGODB" -> "MongoDB";
            case "REDIS" -> "Redis";
            case "CLICKHOUSE" -> "ClickHouse";
            case "SFTP", "SFTPFILE" -> "SftpFile";
            case "FTP", "FTPFILE" -> "FtpFile";
            case "RABBITMQ" -> "Rabbitmq";
            case "ELASTICSEARCH" -> "Elasticsearch";
            case "INFLUXDB" -> "InfluxDB";
            case "NEO4J" -> "Neo4j";
            case "KAFKA" -> "Kafka";
            default -> catalog.canonical(dsType);
        };
    }

    private String canonical(String type, boolean source) {
        String value = isBlank(type) ? (source ? "Jdbc" : "Doris") : type;
        // MySQL / PostgreSQL 在 2.3.9 没有独立插件，统一走 Jdbc；
        // 这里必须先归一化，否则会被当成“非 JDBC 连接器”拒绝明文密码。
        String lower = value.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "mysql", "postgresql", "postgres", "oracle", "sqlserver", "db2",
                 "greenplum", "vertica", "tidb" -> {
                // 这些方言在 2.3.9 统一由 connector-jdbc + 驱动支持
                return "Jdbc";
            }
            default -> {
            }
        }
        return catalog.canonical(value);
    }

    private String sinkTable(TaskRequest req) {
        if (!isBlank(req.sinkTable())) {
            return isBlank(req.sinkDatabase()) ? req.sinkTable()
                    : req.sinkDatabase() + "." + req.sinkTable();
        }
        return null;
    }

    private String jobMode(TaskRequest req) {
        return isBlank(req.jobMode()) ? "BATCH" : req.jobMode().toUpperCase();
    }

    private String taskType(TaskRequest req) {
        String value = req.taskType();
        return isBlank(value) ? "OFFLINE" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String scheduleTimezone(TaskRequest req) {
        return isBlank(req.scheduleTimezone()) ? "Asia/Shanghai" : req.scheduleTimezone().trim();
    }

    private int retryTimes(TaskRequest req) {
        Integer value = req.retryTimes();
        return value == null ? 0 : Math.max(0, Math.min(10, value));
    }

    private int timeoutMinutes(TaskRequest req) {
        Integer value = req.timeoutMinutes();
        return value == null ? 60 : Math.max(1, Math.min(1440, value));
    }

    private int parallelism(TaskRequest req) {
        return req.parallelism() == null ? 1 : Math.max(1, Math.min(8, req.parallelism()));
    }

    private static final java.util.Set<String> SECRET_KEYS = java.util.Set.of(
            "password", "secretkey", "secret_key", "access_secret", "secret",
            "token", "apikey", "api_key", "privatekey", "private_key", "auth",
            "fs.s3a.secret.key");

    /** 递归判断配置里是否含明文密钥（包括嵌套 map / list 与带 userinfo 的 URI）。 */
    private boolean containsSecret(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (SECRET_KEYS.contains(key)) {
                    Object v = entry.getValue();
                    if (v != null && !String.valueOf(v).isBlank()) {
                        return true;
                    }
                }
                if (containsSecret(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (containsSecret(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof String text) {
            // mongodb://user:password@host/db
            return text.matches("(?s).*[A-Za-z][A-Za-z0-9+.-]*://[^/@\\s]+@.*");
        }
        return false;
    }

    private Map<String, Object> sanitize(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (config == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (SECRET_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.put(entry.getKey(), sanitizeValue(entry.getValue()));
        }
        return out;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!SECRET_KEYS.contains(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT))) {
                    nested.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
                }
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new java.util.ArrayList<>();
            for (Object item : list) {
                out.add(sanitizeValue(item));
            }
            return out;
        }
        if (value instanceof String text && text.matches("(?s).*[A-Za-z][A-Za-z0-9+.-]*://[^/@\\s]+@.*")) {
            return text.replaceFirst("(://)[^/@\\s]+@", "$1");
        }
        return value;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw ApiException.badRequest("配置序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> jsonMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String driverFor(String type) {
        return "POSTGRESQL".equals(type)
                ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver";
    }

    /** 从 JDBC URL 取 host:port；Doris 的 MySQL 口 9030，但 SeaTunnel sink 要 FE HTTP 8030。 */
    private String fenodesFor(String jdbcUrl, String type) {
        String rest = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int query = rest.indexOf('?');
        if (query >= 0) rest = rest.substring(0, query);
        if ("DORIS".equals(type)) {
            // 支持 jdbc:mysql://fe1:9030,fe2:9030/db 这类多 FE 地址
            List<String> fenodes = new java.util.ArrayList<>();
            for (String host : rest.split(",")) {
                String value = host.trim();
                if (value.isEmpty()) {
                    continue;
                }
                int colon = value.lastIndexOf(':');
                if (colon > 0 && value.indexOf(']') < colon) {
                    value = value.substring(0, colon);
                }
                fenodes.add(value + ":8030");
            }
            return String.join(",", fenodes);
        }
        return rest;
    }

    private void requireProject(long projectId) {
        // v447：项目必须属于当前租户，不能把任务建到别人的项目里
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private String user(HttpServletRequest request) {
        Object u = request.getAttribute("username");
        return u == null ? "anonymous" : String.valueOf(u);
    }

    private boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private String blankTo(String v, String fallback) {
        return isBlank(v) ? fallback : v;
    }

    /**
     * 连接配置里的用户名：JDBC 用 user，Doris 用 username。
     * 以前两处都只读 user，导致手填 Doris 目标时 sink_user 永远为空。
     */
    private static Object userOf(Map<String, Object> config) {
        Object user = config.get("user");
        return user != null ? user : config.get("username");
    }

    private String encryptOrNull(String v) {
        return v == null ? null : cipher.encrypt(v);
    }
}
