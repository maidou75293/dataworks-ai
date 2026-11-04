package com.lab.dataworks.flink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 作业状态对账：平台状态不能只靠用户点「刷新」。
 *
 * <p>外部审计发现：作业启动即失败时 Flink 侧一直在 RESTARTING/FAILED，
 * 平台却永远显示 RUNNING、error 为空，只能人工停止。这里每 30 秒对一遍
 * 「平台记着有作业句柄的行」，把远端状态回写，并把最近一次作业 ID 留在
 * last_flink_job_id 供停止后复盘。</p>
 *
 * <p>v451 审计补齐：STARTING / STOPPING 也要对账（初始化阶段失败不会卡在「启动中」）；
 * 远端取消失败时保留句柄下一轮重试（以前无论取消成败都清句柄，远端作业成孤儿）；
 * PROD 与 DEV 同口径，重启超过 2 分钟收敛为 FAILED。</p>
 */
@Component
public class FlinkJobReconciler {

    private static final Logger log = LoggerFactory.getLogger(FlinkJobReconciler.class);
    private static final String RESTARTING_NOTE = "作业在重启中（Flink 正在重试，持续失败会转为 FAILED）";
    /** 持续重启超过这个时长就判定失败（Flink 默认重启策略可能是无限重试）。 */
    private static final long RESTARTING_LIMIT_MS = 120_000L;

    private final JdbcTemplate jdbc;
    private final FlinkSqlService flink;
    private final com.lab.dataworks.auth.SessionService sessions;

    public FlinkJobReconciler(JdbcTemplate jdbc, FlinkSqlService flink,
                              com.lab.dataworks.auth.SessionService sessions) {
        this.jdbc = jdbc;
        this.flink = flink;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${flink.reconcile-ms:30000}", initialDelay = 20000)
    public void reconcile() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id, flink_job_id, status, last_error, restarting_since
                      from platform.flink_sql_job
                     where flink_job_id is not null
                       and status in ('STARTING', 'RUNNING', 'RESTARTING', 'STOPPING', 'FAILED')
                    """);
            for (Map<String, Object> row : rows) {
                sync((Number) row.get("id"), String.valueOf(row.get("flink_job_id")),
                        String.valueOf(row.get("status")),
                        row.get("last_error") == null ? null : String.valueOf(row.get("last_error")),
                        (Timestamp) row.get("restarting_since"));
            }
        } catch (RuntimeException e) {
            // 对账失败不能影响业务请求；下一轮继续
            log.warn("flink reconcile failed: {}", e.getMessage());
        }
        try {
            List<Map<String, Object>> prods = jdbc.queryForList("""
                    select id, prod_job_id, prod_status, prod_restarting_since
                      from platform.flink_sql_job
                     where prod_job_id is not null
                       and prod_status in ('RUNNING', 'RESTARTING', 'FAILED')
                    """);
            for (Map<String, Object> row : prods) {
                syncProd((Number) row.get("id"), String.valueOf(row.get("prod_job_id")),
                        String.valueOf(row.get("prod_status")),
                        (Timestamp) row.get("prod_restarting_since"));
            }
        } catch (RuntimeException e) {
            log.warn("flink prod reconcile failed: {}", e.getMessage());
        }
    }

    private void sync(Number id, String jobId, String status, String lastError,
                      Timestamp restartingSince) {
        String state;
        try {
            state = String.valueOf(flink.jobStatus(jobId).get("state"));
        } catch (RuntimeException e) {
            return; // 连不上 Flink：保持原状，下一轮再试
        }
        switch (state) {
            case "FAILED", "SUSPENDED" -> fail(id, jobId, status, safe(flink::jobException, jobId));
            case "FINISHED", "CANCELED", "CANCELLED", "GONE" -> stopRemote(id, jobId, status, state);
            case "RESTARTING", "RECONCILING" ->
                    restarting(id, jobId, status, lastError, restartingSince);
            case "CREATED", "INITIALIZING" -> setStatus(id, jobId, status, "STARTING");
            case "CANCELLING", "CANCELING" -> setStatus(id, jobId, status, "STOPPING");
            case "RUNNING" -> running(id, jobId, status, lastError, restartingSince);
            // 未知状态不猜：保持原状，避免把真实状态覆盖成乐观值
            default -> { }
        }
    }

    /** 远端失败/持续重启超过上限：收敛成 FAILED；取消成功才丢句柄，失败留着重试。 */
    private void fail(Number id, String jobId, String status, String error) {
        boolean cancelled = tryCancel(jobId);
        int updated = jdbc.update("""
                update platform.flink_sql_job
                   set status = 'FAILED', last_error = ?,
                       last_flink_job_id = coalesce(last_flink_job_id, ?),
                       last_job_at = coalesce(last_job_at, submitted_at),
                       flink_job_id = case when ? then null else flink_job_id end,
                       session_handle = case when ? then null else session_handle end,
                       restarting_since = null,
                       stopped_at = case when ? then now() else stopped_at end,
                       updated_at = now()
                 where id = ? and flink_job_id = ?
                """, error == null || error.isBlank() ? "作业在 Flink 上失败" : error,
                jobId, cancelled, cancelled, cancelled, id.longValue(), jobId);
        if (updated == 0) {
            return; // 期间已被用户停止/重新提交：这一轮的判断作废，不能写审计
        }
        if (!"FAILED".equals(status)) {
            sessions.audit("system", "FLINK_JOB_FAILED", "s" + id,
                    "作业在 Flink 上失败（job=" + jobId + "）");
            log.info("flink job {} marked FAILED (job={})", id, jobId);
        } else if (!cancelled) {
            log.debug("flink job {} cancel retry pending (job={})", id, jobId);
        }
    }

    /** 远端已结束：平台归位成已停止，并清掉句柄（句柄已无对象可停）。 */
    private void stopRemote(Number id, String jobId, String status, String state) {
        int updated = jdbc.update("""
                update platform.flink_sql_job
                   set status = 'STOPPED', last_flink_job_id = coalesce(last_flink_job_id, ?),
                       last_job_at = coalesce(last_job_at, submitted_at),
                       flink_job_id = null, session_handle = null, restarting_since = null,
                       stopped_at = now(), updated_at = now()
                 where id = ? and flink_job_id = ?
                """, jobId, id.longValue(), jobId);
        if (updated == 0) {
            return;
        }
        // 外部取消/集群回收与用户主动停止要能区分
        if (("CANCELED".equals(state) || "CANCELLED".equals(state) || "GONE".equals(state))
                && !"STOPPED".equals(status) && !"FAILED".equals(status)) {
            sessions.audit("system", "FLINK_JOB_EXTERNAL_STOP", "s" + id,
                    "作业在 Flink 上被取消或回收（state=" + state + "）");
        }
        log.info("flink job {} finished remotely ({}) -> STOPPED", id, state);
    }

    private void running(Number id, String jobId, String status, String lastError,
                         Timestamp restartingSince) {
        boolean needs = !"RUNNING".equals(status) || restartingSince != null
                || RESTARTING_NOTE.equals(lastError);
        if (!needs) {
            return;
        }
        jdbc.update("""
                update platform.flink_sql_job
                   set status = 'RUNNING', last_error = null,
                       restarting_since = null, updated_at = now()
                 where id = ? and flink_job_id = ?
                """, id.longValue(), jobId);
    }

    private void setStatus(Number id, String jobId, String current, String target) {
        if (target.equals(current)) {
            return;
        }
        jdbc.update("""
                update platform.flink_sql_job set status = ?, updated_at = now()
                 where id = ? and flink_job_id = ?
                """, target, id.longValue(), jobId);
    }

    private void restarting(Number id, String jobId, String status, String lastError,
                            Timestamp restartingSince) {
        long since = restartingSince == null ? 0 : restartingSince.getTime();
        if (since == 0) {
            if (!"RESTARTING".equals(status)) {
                sessions.audit("system", "FLINK_JOB_RESTARTING", "s" + id, RESTARTING_NOTE);
            }
            jdbc.update("""
                    update platform.flink_sql_job
                       set status = 'RESTARTING', restarting_since = now(), last_error = ?,
                           updated_at = now()
                     where id = ? and flink_job_id = ?
                    """, RESTARTING_NOTE, id.longValue(), jobId);
            return;
        }
        if (System.currentTimeMillis() - since > RESTARTING_LIMIT_MS) {
            // 无限重启等于跑不起来：收敛成 FAILED，把最近一次异常带给用户
            fail(id, jobId, status, safe(flink::jobException, jobId));
            log.info("flink job {} stuck RESTARTING > 2min -> FAILED", id);
            return;
        }
        if (!"RESTARTING".equals(status) || !RESTARTING_NOTE.equals(lastError)) {
            jdbc.update("""
                    update platform.flink_sql_job
                       set status = 'RESTARTING', last_error = ?, updated_at = now()
                     where id = ? and flink_job_id = ?
                    """, RESTARTING_NOTE, id.longValue(), jobId);
        }
    }

    // ---------------------------------------------------------------- PROD

    private void syncProd(Number id, String jobId, String prodStatus, Timestamp restartingSince) {
        String state;
        try {
            state = String.valueOf(flink.asProd(() -> flink.jobStatus(jobId)).get("state"));
        } catch (RuntimeException e) {
            return;
        }
        switch (state) {
            case "FAILED", "SUSPENDED" -> failProd(id, jobId, prodStatus);
            case "FINISHED", "CANCELED", "CANCELLED", "GONE" -> stopProdRemote(id, jobId);
            case "RESTARTING", "RECONCILING" ->
                    restartingProd(id, jobId, prodStatus, restartingSince);
            default -> markProdRunning(id, jobId, prodStatus, restartingSince);
        }
    }

    private void failProd(Number id, String jobId, String prodStatus) {
        boolean cancelled = tryCancelProd(jobId);
        int updated = jdbc.update("""
                update platform.flink_sql_job
                   set prod_status = 'FAILED',
                       prod_job_id = case when ? then null else prod_job_id end,
                       prod_session = case when ? then null else prod_session end,
                       prod_restarting_since = null, updated_at = now()
                 where id = ? and prod_job_id = ?
                """, cancelled, cancelled, id.longValue(), jobId);
        if (updated == 0) {
            return;
        }
        if (!"FAILED".equals(prodStatus)) {
            sessions.audit("system", "FLINK_PROD_JOB_FAILED", "s" + id,
                    "线上作业在 Flink 上失败（job=" + jobId + "）");
            log.info("flink prod job {} marked FAILED (job={})", id, jobId);
        } else if (!cancelled) {
            log.debug("flink prod job {} cancel retry pending (job={})", id, jobId);
        }
    }

    private void stopProdRemote(Number id, String jobId) {
        jdbc.update("""
                update platform.flink_sql_job
                   set prod_status = 'STOPPED', prod_job_id = null, prod_session = null,
                       prod_restarting_since = null, updated_at = now()
                 where id = ? and prod_job_id = ?
                """, id.longValue(), jobId);
        log.info("flink prod job {} finished remotely -> STOPPED", id);
    }

    private void restartingProd(Number id, String jobId, String prodStatus,
                                Timestamp restartingSince) {
        long since = restartingSince == null ? 0 : restartingSince.getTime();
        if (since == 0) {
            jdbc.update("""
                    update platform.flink_sql_job
                       set prod_status = 'RESTARTING', prod_restarting_since = now(),
                           updated_at = now()
                     where id = ? and prod_job_id = ?
                    """, id.longValue(), jobId);
            sessions.audit("system", "FLINK_PROD_JOB_RESTARTING", "s" + id,
                    "线上作业在重启中（Flink 正在重试）");
            return;
        }
        if (System.currentTimeMillis() - since > RESTARTING_LIMIT_MS) {
            failProd(id, jobId, prodStatus);
            log.info("flink prod job {} stuck RESTARTING > 2min -> FAILED", id);
            return;
        }
        if (!"RESTARTING".equals(prodStatus)) {
            jdbc.update("""
                    update platform.flink_sql_job
                       set prod_status = 'RESTARTING', updated_at = now()
                     where id = ? and prod_job_id = ?
                    """, id.longValue(), jobId);
        }
    }

    private void markProdRunning(Number id, String jobId, String prodStatus,
                                 Timestamp restartingSince) {
        if ("RUNNING".equals(prodStatus) && restartingSince == null) {
            return;
        }
        jdbc.update("""
                update platform.flink_sql_job
                   set prod_status = 'RUNNING', prod_restarting_since = null, updated_at = now()
                 where id = ? and prod_job_id = ?
                """, id.longValue(), jobId);
    }

    // ---------------------------------------------------------------- 工具

    private boolean tryCancel(String jobId) {
        try {
            flink.cancel(jobId);
            return true;
        } catch (RuntimeException e) {
            // 保留句柄，下一轮对账继续尝试；期间用户仍可点「停止」
            log.debug("flink cancel failed job={}: {}", jobId, e.getMessage());
            return false;
        }
    }

    private boolean tryCancelProd(String jobId) {
        try {
            flink.asProd(() -> flink.cancel(jobId));
            return true;
        } catch (RuntimeException e) {
            log.debug("flink prod cancel failed job={}: {}", jobId, e.getMessage());
            return false;
        }
    }

    private String safe(java.util.function.Function<String, String> fn, String jobId) {
        try {
            return fn.apply(jobId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
