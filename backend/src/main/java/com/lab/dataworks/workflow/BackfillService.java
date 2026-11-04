package com.lab.dataworks.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 补数据：把一段业务日期切成天，按并行度逐天复用 {@link WorkflowTriggerService}
 * 触发既有运行，并收敛每一天的结果。
 *
 * <p>设计要点（对应任务单 011）：</p>
 * <ul>
 *   <li>复用既有 run 路径，不另写执行器：每天就是一次普通 trigger；</li>
 *   <li>并行度 1–4，排队而不是一次全起：RUNNING 天数达到 parallelism 后不再派发；</li>
 *   <li>单日失败隔离：每天独立线程执行并独立落 FAILED，不阻断其它日期；</li>
 *   <li>取消：未开始的日期直接 SKIPPED；已在跑的 run 让它自然结束，结果仍然回写；</li>
 *   <li>与日常调度隔离：独立线程池、只在「建 run」的瞬间持有工作流锁，不占调度线程；</li>
 *   <li>同一工作流同时只允许一个 RUNNING：应用层 409 + 数据库 partial unique index 兜底。</li>
 * </ul>
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);
    /** 业务日期所在时区：与手工触发（默认业务日期 = 今天）同一口径。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> TERMINAL_FAILURE =
            Set.of("failed", "upstream_failed", "cancelled", "skipped");
    /** 单日触发超过这个时间还拿不到 run_id，视为调度线程异常收尾，避免永远 RUNNING。 */
    private static final int DISPATCH_TIMEOUT_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkflowTriggerService triggers;
    private final TransactionTemplate transactions;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "backfill-dispatch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public BackfillService(JdbcTemplate jdbc, ObjectMapper mapper, WorkflowTriggerService triggers,
                           TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.triggers = triggers;
        this.transactions = transactions;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 校验并创建补数作业。调用方已完成工作流存在性/租户校验。
     *
     * @return 作业详情（含 {@code jobId} 与兼容字段 {@code count}）
     */
    public Map<String, Object> submit(long workflowId, String envParam, String startRaw,
                                      String endRaw, Integer parallelismParam,
                                      Map<String, Object> params, String username) {
        String tenant = TenantScope.current();
        Map<String, Object> workflow = workflow(workflowId, tenant);
        String env = resolveEnv(workflow, envParam);
        if (startRaw == null || startRaw.isBlank() || endRaw == null || endRaw.isBlank()) {
            throw ApiException.badRequest("补数必须指定起止日期");
        }
        LocalDate start = parseDate(startRaw, "dateStart");
        LocalDate end = parseDate(endRaw, "dateEnd");
        if (end.isBefore(start)) {
            throw ApiException.badRequest("结束日期不能早于开始日期");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw ApiException.badRequest("单次补数范围不能超过一年");
        }
        // D30：未来业务日期一律拒绝。以前 2027-05-01 这种区间会立刻建出未来日期的实例
        // 并执行，下游产出不存在的业务日期分区，幂等重跑时又会被当成「已补」。
        requireBizDatesNotFuture(start, end, LocalDate.now(ZONE));
        int parallelism = parallelismParam == null ? 2 : parallelismParam;
        if (parallelism < 1 || parallelism > 4) {
            throw ApiException.badRequest("并行度只能是 1–4");
        }
        WorkflowTriggerService.DeploymentRef deployment = triggers.latestDeployment(workflowId, env);
        if (deployment == null) {
            throw ApiException.badRequest("请先把该工作流发布到 " + env);
        }
        // 补数依赖「重跑同一天覆盖而不是追加」：检查真正会被执行的已发布版本。
        BackfillSafety.Analysis safety = BackfillSafety.analyze(deployedModel(workflowId, env,
                String.valueOf(workflow.get("model"))), mapper);
        if (!safety.safe()) {
            throw ApiException.badRequest("该工作流不是幂等写，补数会产生重复数据："
                    + String.join("；", safety.unsafe())
                    + "。请把追加写改为 INSERT OVERWRITE，或在同一工作流中增加"
                    + " `DELETE FROM <目标表> WHERE <日期列> = '${biz_date}'` 的前置清理任务后再补数。");
        }
        Map<String, Object> active = runningJob(workflowId);
        if (active != null) {
            throw ApiException.conflict("该工作流已有运行中的补数作业 #" + active.get("id")
                    + "，请等待结束或先取消");
        }
        long projectId = ((Number) workflow.get("project_id")).longValue();
        String paramsJson;
        try {
            paramsJson = params == null || params.isEmpty() ? null : mapper.writeValueAsString(params);
        } catch (Exception e) {
            throw ApiException.badRequest("补数参数不是合法 JSON: " + e.getMessage());
        }
        long jobId;
        try {
            jobId = transactions.execute(status -> {
                Long id = jdbc.queryForObject("""
                        insert into platform.backfill_job
                            (tenant_id, project_id, workflow_id, env, date_start, date_end,
                             parallelism, status, params_json, created_by)
                        values (?, ?, ?, ?, ?::date, ?::date, ?, 'RUNNING', ?::jsonb, ?)
                        returning id
                        """, Long.class, tenant, projectId, workflowId, env,
                        start.toString(), end.toString(), parallelism, paramsJson, username);
                if (id == null) {
                    throw ApiException.conflict("补数作业创建失败");
                }
                jdbc.update("""
                        insert into platform.backfill_day (job_id, biz_date, status)
                        select ?, d::date, 'PENDING'
                          from generate_series(?::date, ?::date, interval '1 day') d
                        """, id, start.toString(), end.toString());
                jdbc.update("""
                        update platform.backfill_job j
                           set total_days = (select count(*) from platform.backfill_day d where d.job_id = j.id)
                         where j.id = ?
                        """, id);
                return id;
            });
        } catch (DuplicateKeyException e) {
            // partial unique index：两个请求同时通过应用层检查时，第二个在这里被拦下
            throw ApiException.conflict("该工作流已有运行中的补数作业，请等待结束或先取消");
        }
        Map<String, Object> detail = detail(jobId);
        detail.put("count", detail.get("totalDays"));
        detail.put("uncheckedTasks", safety.unchecked());
        return detail;
    }

    // ------------------------------------------------------------------ 查询 / 操作

    public Map<String, Object> detail(long jobId) {
        return detail(jobId, TenantScope.current());
    }

    private Map<String, Object> detail(long jobId, String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select j.id, j.tenant_id, j.project_id, j.workflow_id, j.env,
                       j.date_start, j.date_end, j.parallelism, j.status,
                       j.total_days, j.done_days, j.failed_days,
                       j.params_json::text as params, j.created_by, j.created_at, j.finished_at,
                       w.code as workflow_code, w.name as workflow_name, p.code as project_code
                  from platform.backfill_job j
                  join platform.workflow w on w.id = j.workflow_id
                  join platform.project p on p.id = j.project_id
                 where j.id = ? and j.tenant_id = ?
                """, jobId, tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("补数作业不存在: " + jobId);
        }
        Map<String, Object> job = new LinkedHashMap<>(rows.get(0));
        // 统一成前端好用的驼峰字段，同时保留库字段（job_id/create_at 之类）兼容直接读库的脚本
        job.put("jobId", job.get("id"));
        job.put("dateStart", job.get("date_start"));
        job.put("dateEnd", job.get("date_end"));
        job.put("totalDays", job.get("total_days"));
        job.put("doneDays", job.get("done_days"));
        job.put("failedDays", job.get("failed_days"));
        job.put("createdBy", job.get("created_by"));
        job.put("finishedAt", job.get("finished_at"));
        job.put("days", jdbc.queryForList("""
                select id, biz_date as "bizDate", status, run_id as "runId", message,
                       started_at as "startedAt", finished_at as "finishedAt"
                  from platform.backfill_day
                 where job_id = ?
                 order by biz_date
                """, jobId));
        return job;
    }

    public List<Map<String, Object>> history(long workflowId) {
        workflow(workflowId, TenantScope.current());
        return jdbc.queryForList("""
                select id as "jobId", env, date_start as "dateStart", date_end as "dateEnd",
                       parallelism, status, total_days as "totalDays", done_days as "doneDays",
                       failed_days as "failedDays", created_by as "createdBy",
                       created_at as "createdAt", finished_at as "finishedAt"
                  from platform.backfill_job
                 where workflow_id = ? and tenant_id = ?
                 order by created_at desc, id desc
                 limit 50
                """, workflowId, TenantScope.current());
    }

    public Map<String, Object> retryFailed(long jobId, String bizDate) {
        Map<String, Object> job = jobRow(jobId, TenantScope.current());
        long workflowId = ((Number) job.get("workflow_id")).longValue();
        String status = String.valueOf(job.get("status"));
        if ("RUNNING".equals(status)) {
            throw ApiException.conflict("补数作业正在运行，请等待结束或先取消");
        }
        Map<String, Object> other = runningJob(workflowId);
        if (other != null && ((Number) other.get("id")).longValue() != jobId) {
            throw ApiException.conflict("该工作流已有运行中的补数作业 #" + other.get("id")
                    + "，请等待结束或先取消");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "update platform.backfill_day set status = 'PENDING', run_id = null, message = null,"
                        + " started_at = null, finished_at = null"
                        + " where job_id = ? and status = 'FAILED'");
        args.add(jobId);
        if (bizDate != null && !bizDate.isBlank()) {
            parseDate(bizDate, "date");
            sql.append(" and biz_date = ?::date");
            args.add(bizDate);
        }
        int changed = transactions.execute(ignored -> {
            int n = jdbc.update(sql.toString(), args.toArray());
            if (n > 0) {
                jdbc.update("""
                        update platform.backfill_job
                           set status = 'RUNNING', finished_at = null
                         where id = ?
                        """, jobId);
                recount(jobId);
            }
            return n;
        });
        if (changed == 0) {
            throw ApiException.badRequest(bizDate == null || bizDate.isBlank()
                    ? "没有失败的天可重跑" : "该日期不是失败状态，不能重跑: " + bizDate);
        }
        return detail(jobId);
    }

    public Map<String, Object> cancel(long jobId) {
        jobRow(jobId, TenantScope.current());
        int changed = transactions.execute(ignored -> {
            int n = jdbc.update("""
                    update platform.backfill_job
                       set status = 'CANCELLED', finished_at = now()
                     where id = ? and status = 'RUNNING'
                    """, jobId);
            if (n > 0) {
                jdbc.update("""
                        update platform.backfill_day
                           set status = 'SKIPPED', finished_at = now(), message = '已取消'
                         where job_id = ? and status = 'PENDING'
                        """, jobId);
            }
            return n;
        });
        if (changed == 0) {
            throw ApiException.conflict("只有运行中的补数作业可以取消");
        }
        return detail(jobId);
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 每隔几秒推进所有运行中的补数作业：收敛已结束的天、按并行度派发待跑的天、收尾。
     *
     * <p>后端是单副本，进程内 {@link AtomicBoolean} 足够避免两轮 tick 重叠；
     * 多副本时需要换成数据库 advisory lock（与 {@link WorkflowMutationService} 的约束一致）。</p>
     */
    @Scheduled(fixedDelayString = "${platform.backfill-tick-ms:5000}")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Map<String, Object>> jobs = jdbc.queryForList("""
                    select j.id, j.workflow_id, j.env, j.parallelism, j.status, j.created_by,
                           j.params_json::text as params
                      from platform.backfill_job j
                     where j.status = 'RUNNING'
                        or (j.status = 'CANCELLED'
                            and exists (select 1 from platform.backfill_day d
                                         where d.job_id = j.id and d.status = 'RUNNING'))
                    """);
            for (Map<String, Object> job : jobs) {
                try {
                    advance(job);
                } catch (Exception e) {
                    log.warn("advance backfill job {} failed: {}", job.get("id"), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("backfill tick failed: {}", e.getMessage());
        } finally {
            ticking.set(false);
        }
    }

    private void advance(Map<String, Object> job) {
        long jobId = ((Number) job.get("id")).longValue();
        settleRunningDays(jobId);
        if (!"RUNNING".equals(String.valueOf(job.get("status")))) {
            recount(jobId);
            return;
        }
        dispatchPending(job);
        finishIfSettled(jobId);
    }

    /** 轮询在跑的天：成功 → DONE，失败/取消 → FAILED，其余继续等。 */
    private void settleRunningDays(long jobId) {
        List<Map<String, Object>> days = jdbc.queryForList("""
                select id, run_id, started_at,
                       started_at is not null
                         and started_at < now() - (?::int * interval '1 minute') as timed_out
                  from platform.backfill_day
                 where job_id = ? and status = 'RUNNING'
                """, DISPATCH_TIMEOUT_MINUTES, jobId);
        for (Map<String, Object> day : days) {
            long dayId = ((Number) day.get("id")).longValue();
            String runId = day.get("run_id") == null ? null : String.valueOf(day.get("run_id"));
            if (runId == null) {
                if (Boolean.TRUE.equals(day.get("timed_out"))) {
                    jdbc.update("""
                            update platform.backfill_day
                               set status = 'FAILED', finished_at = now(),
                                   message = coalesce(message, '触发超时：10 分钟内没有拿到运行实例')
                             where id = ? and status = 'RUNNING'
                            """, dayId);
                }
                continue;
            }
            List<Map<String, Object>> instances = jdbc.queryForList("""
                    select state, error_message from platform.wf_instance
                     where run_id = ? order by id desc limit 1
                    """, runId);
            if (instances.isEmpty()) {
                continue;   // 触发线程还没落库，下一轮再看
            }
            String state = String.valueOf(instances.get(0).get("state")).toLowerCase(Locale.ROOT);
            if ("success".equals(state)) {
                jdbc.update("""
                        update platform.backfill_day
                           set status = 'DONE', finished_at = now(), message = null
                         where id = ? and status = 'RUNNING'
                        """, dayId);
            } else if (TERMINAL_FAILURE.contains(state)) {
                Object error = instances.get(0).get("error_message");
                String message = error == null || String.valueOf(error).isBlank()
                        ? "运行状态 " + state : String.valueOf(error);
                jdbc.update("""
                        update platform.backfill_day
                           set status = 'FAILED', finished_at = now(), message = ?
                         where id = ? and status = 'RUNNING'
                        """, truncate(message, 4000), dayId);
            }
        }
    }

    /** 并行度内派发 PENDING：先原子占位 RUNNING，再交给独立线程池触发。 */
    private void dispatchPending(Map<String, Object> job) {
        long jobId = ((Number) job.get("id")).longValue();
        int parallelism = ((Number) job.get("parallelism")).intValue();
        Integer active = jdbc.queryForObject("""
                select count(*) from platform.backfill_day
                 where job_id = ? and status = 'RUNNING'
                """, Integer.class, jobId);
        int slots = parallelism - (active == null ? 0 : active);
        if (slots <= 0) {
            return;
        }
        List<Map<String, Object>> pending = jdbc.queryForList("""
                select id, biz_date from platform.backfill_day
                 where job_id = ? and status = 'PENDING'
                 order by biz_date limit ?
                """, jobId, slots);
        for (Map<String, Object> day : pending) {
            long dayId = ((Number) day.get("id")).longValue();
            int claimed = jdbc.update("""
                    update platform.backfill_day
                       set status = 'RUNNING', started_at = now()
                     where id = ? and status = 'PENDING'
                    """, dayId);
            if (claimed == 0) {
                continue;   // 已被取消/其它 tick 占走
            }
            String bizDate = String.valueOf(day.get("biz_date"));
            executor.submit(() -> dispatch(job, dayId, bizDate));
        }
    }

    private void dispatch(Map<String, Object> job, long dayId, String bizDate) {
        long jobId = ((Number) job.get("id")).longValue();
        long workflowId = ((Number) job.get("workflow_id")).longValue();
        String env = String.valueOf(job.get("env"));
        String username = job.get("created_by") == null ? "backfill" : String.valueOf(job.get("created_by"));
        Map<String, Object> params = new LinkedHashMap<>();
        String rawParams = job.get("params") == null ? null : String.valueOf(job.get("params"));
        if (rawParams != null && !rawParams.isBlank() && !"null".equals(rawParams)) {
            try {
                params = mapper.readValue(rawParams, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("backfill job {} params unreadable: {}", jobId, e.getMessage());
            }
        }
        try {
            Map<String, Object> run = triggers.trigger(workflowId, env, bizDate, params,
                    "backfill", username);
            jdbc.update("""
                    update platform.backfill_day
                       set run_id = ?, message = null
                     where id = ? and status = 'RUNNING'
                    """, String.valueOf(run.get("runId")), dayId);
            log.info("backfill job {} day {} triggered run {}", jobId, bizDate, run.get("runId"));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("""
                    update platform.backfill_day
                       set status = 'FAILED', finished_at = now(), message = ?
                     where id = ? and status = 'RUNNING'
                    """, truncate(message, 4000), dayId);
            log.warn("backfill job {} day {} failed to trigger: {}", jobId, bizDate, message);
        } finally {
            recount(jobId);
        }
    }

    /** 没有 PENDING / RUNNING 时收尾：有失败 → FAILED，否则 DONE。 */
    private void finishIfSettled(long jobId) {
        Map<String, Object> counts = counts(jobId);
        int pending = intOf(counts.get("pending"));
        int running = intOf(counts.get("running"));
        if (pending > 0 || running > 0) {
            recount(jobId);
            return;
        }
        int failed = intOf(counts.get("failed"));
        jdbc.update("""
                update platform.backfill_job
                   set status = ?, total_days = ?, done_days = ?, failed_days = ?,
                       finished_at = now()
                 where id = ? and status = 'RUNNING'
                """, failed > 0 ? "FAILED" : "DONE",
                intOf(counts.get("total")), intOf(counts.get("done")), failed, jobId);
    }

    private void recount(long jobId) {
        Map<String, Object> counts = counts(jobId);
        jdbc.update("""
                update platform.backfill_job
                   set total_days = ?, done_days = ?, failed_days = ?
                 where id = ?
                """, intOf(counts.get("total")), intOf(counts.get("done")),
                intOf(counts.get("failed")), jobId);
    }

    private Map<String, Object> counts(long jobId) {
        return jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status = 'DONE') as done,
                       count(*) filter (where status = 'FAILED') as failed,
                       count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'RUNNING') as running
                  from platform.backfill_day where job_id = ?
                """, jobId);
    }

    // ------------------------------------------------------------------ 基础

    private Map<String, Object> workflow(long workflowId, String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, project_id, tenant_id, code, name, env, model_json::text as model
                  from platform.workflow
                 where id = ? and tenant_id = ?
                """, workflowId, tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("工作流不存在: " + workflowId);
        }
        return rows.get(0);
    }

    private Map<String, Object> jobRow(long jobId, String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from platform.backfill_job where id = ? and tenant_id = ?", jobId, tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("补数作业不存在: " + jobId);
        }
        return rows.get(0);
    }

    private Map<String, Object> runningJob(long workflowId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status from platform.backfill_job
                 where workflow_id = ? and status = 'RUNNING'
                 order by id desc limit 1
                """, workflowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 补数实际跑的是已发布版本的模型：优先取 deployment 指向的版本。 */
    private String deployedModel(long workflowId, String env, String fallback) {
        List<String> models = jdbc.queryForList("""
                select v.model_json::text
                  from platform.workflow_deployment d
                  join platform.workflow_version v
                    on v.workflow_id = d.workflow_id and v.version_no = d.version_no
                 where d.workflow_id = ? and d.env = ? and d.active
                   and d.prefect_deployment_id is not null
                 order by d.deployed_at desc, d.id desc limit 1
                """, String.class, workflowId, env);
        return models.isEmpty() ? fallback : models.get(0);
    }

    private static String resolveEnv(Map<String, Object> workflow, String requested) {
        if (requested == null || requested.isBlank()) {
            return String.valueOf(workflow.get("env"));
        }
        String env = requested.toUpperCase(Locale.ROOT);
        if (!Set.of("DEV", "PROD").contains(env)) {
            throw ApiException.badRequest("环境只能是 DEV 或 PROD");
        }
        return env;
    }

    /**
     * D30：补数区间必须落在「今天及以前」。
     *
     * <p>反向区间、非法日期、超过一年都已有校验，唯独未来日期被放行：一次误操作就会
     * 给下游建出不存在的业务日期分区，重跑时那一天的 run 已经存在，又会被当成「已补」。</p>
     *
     * @param today 由调用方传入，便于用例覆盖边界（今天 / 明天 / 昨天）
     */
    static void requireBizDatesNotFuture(LocalDate start, LocalDate end, LocalDate today) {
        if (!start.isAfter(today) && !end.isAfter(today)) {
            return;
        }
        throw ApiException.badRequest("补数业务日期不能晚于今天（" + today + "）：收到 "
                + start + ".." + end + "。未来日期还没有数据，请等到业务日期再补，"
                + "或把结束日期改到今天及以前");
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 日期格式不正确，应为 yyyy-MM-dd: " + value);
        }
    }

    private static int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
