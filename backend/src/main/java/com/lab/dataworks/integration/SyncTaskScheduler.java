package com.lab.dataworks.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 离线采集任务的周期调度。
 *
 * <p>每 30 秒扫描一次「开启调度 + OFFLINE」的任务，用 cron 判断是否到期；
 * 到点调用与手动运行相同的执行路径（{@link SyncTaskController#runScheduled}）。
 * 上一次运行还没结束时跳过，避免重叠执行。
 *
 * <p>cron 同时接受 5 段（分 时 日 月 周）和 Spring 的 6 段（秒 分 时 日 月 周）。
 * 时区取任务上的 schedule_timezone，默认 Asia/Shanghai。
 */
@Component
public class SyncTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncTaskScheduler.class);

    private final JdbcTemplate jdbc;
    private final SyncTaskController controller;
    private final RunEventService runEvents;
    private int ticks;

    public SyncTaskScheduler(JdbcTemplate jdbc, SyncTaskController controller,
                             RunEventService runEvents) {
        this.jdbc = jdbc;
        this.controller = controller;
        this.runEvents = runEvents;
    }

    @Scheduled(initialDelayString = "${platform.integration-schedule-initial-ms:20000}",
            fixedDelayString = "${platform.integration-schedule-ms:30000}")
    public void tick() {
        List<Map<String, Object>> tasks;
        try {
            tasks = jdbc.queryForList("""
                    select id, code,
                           schedule_cron as "scheduleCron",
                           schedule_timezone as "scheduleTimezone",
                           last_schedule_at as "lastScheduleAt",
                           last_status as "lastStatus",
                           depends_on_task_id as "dependsOnTaskId"
                    from platform.sync_task
                    where schedule_enabled = true
                      and released = true
                      and task_type = 'OFFLINE'
                      and schedule_cron is not null
                    order by id
                    """);
        } catch (Exception e) {
            // 迁移未跑完时不要刷屏
            log.debug("integration schedule scan skipped: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> task : tasks) {
            long id = ((Number) task.get("id")).longValue();
            String status = String.valueOf(task.get("lastStatus"));
            if ("PENDING".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status)) {
                continue;
            }
            Timestamp base = baseOf(task);
            try {
                if (isDue(task, base) && upstreamReady(task) && claim(id, base)) {
                    String jobName = controller.runScheduled(id);
                    if (jobName != null) {
                        log.info("scheduled run task={} code={} job={}", id, task.get("code"), jobName);
                    } else {
                        // runScheduled 返回 null 表示被手动运行抢先（busy）或没有新文件。
                        // 没有新文件的路径会推进 last_schedule_at；busy 路径不会，
                        // 若不释放占用，claimed_at > base 会让这个任务永久不再触发。
                        jdbc.update(
                                "update platform.sync_task set schedule_claimed_at = null where id = ?",
                                id);
                    }
                }
            } catch (Exception e) {
                // 抢占后没跑起来：释放占用，否则这个基准时间会被永久挡住
                jdbc.update("update platform.sync_task set schedule_claimed_at = null where id = ?", id);
                log.warn("scheduled run failed task={} code={}: {}",
                        id, task.get("code"), e.getMessage());
            }
        }
        reconcileRunning();
        if (++ticks % 2 == 0) {   // 每分钟一次
            controller.cleanupStaleSecrets();
        }
    }

    /**
     * 上游依赖：上游最近一次运行必须成功，且必须发生在本任务上次调度之后。
     * 上游失败或本轮还没跑成功时，下游保持等待（本次跳过，下一轮再看）。
     */
    private boolean upstreamReady(Map<String, Object> task) {
        Object raw = task.get("dependsOnTaskId");
        if (!(raw instanceof Number dep) || dep.longValue() <= 0) {
            return true;
        }
        List<Map<String, Object>> up = jdbc.queryForList("""
                select last_status as "lastStatus",
                       last_success_at as "lastSuccessAt"
                from platform.sync_task where id = ?
                """, dep.longValue());
        if (up.isEmpty()) {
            log.warn("upstream task {} not found for task={}", dep, task.get("id"));
            return false;
        }
        Map<String, Object> u = up.get(0);
        // 用最近一次成功时间：上游正在跑新一轮（PENDING）不影响判断
        Object success = u.get("lastSuccessAt");
        if (!(success instanceof Timestamp ok)) {
            log.info("skip task={}: upstream {} has no successful run yet",
                    task.get("id"), dep);
            return false;
        }
        Object lastSchedule = task.get("lastScheduleAt");
        if (lastSchedule instanceof Timestamp mine && !ok.toInstant().isAfter(mine.toInstant())) {
            log.info("skip task={}: upstream {} has no new successful run", task.get("id"), dep);
            return false;
        }
        return true;
    }

    /** 判断是否到期用的基准时间：上次调度触发时间（没有就取 1 分钟前）。 */
    private static Timestamp baseOf(Map<String, Object> task) {
        Object last = task.get("lastScheduleAt");
        return last instanceof Timestamp ts ? ts : Timestamp.from(Instant.now().minusSeconds(60));
    }

    /**
     * 抢占一次周期触发：多副本同时扫描时，只有一个副本的条件更新会命中。
     *
     * <p>条件用「基准时间」而不是固定窗口：运行成功后 {@code last_schedule_at} 会前进，
     * 下一次触发（哪怕只隔 1 分钟）就能再次抢占；同一基准时间只会被一个副本拿走。
     */
    private boolean claim(long id, Timestamp base) {
        int updated = jdbc.update("""
                update platform.sync_task
                set schedule_claimed_at = now()
                where id = ?
                  and (schedule_claimed_at is null or schedule_claimed_at <= ?)
                """, id, base);
        return updated > 0;
    }

    /**
     * 调度触发的运行没有前端轮询，这里主动对账：仍在 PENDING/RUNNING 且有 jobName 的任务
     * 每 30 秒查一次真实状态，成功后触发水位推进与文件归档；
     * 失败按 retry_times 重试，超过 timeout_minutes 直接终止。
     */
    private void reconcileRunning() {
        // P2-20：固定 order by id limit 20 会饿死前 20 个长期运行之外的任务；
        // 改成 id 游标分页，单轮最多 500 条，剩下的下一轮继续。
        long cursor = 0;
        int processed = 0;
        while (processed < 500) {
            List<Map<String, Object>> running;
            try {
                running = jdbc.queryForList("""
                        select id, code, last_job_name as "jobName",
                               last_run_at as "lastRunAt",
                               retry_times as "retryTimes",
                               timeout_minutes as "timeoutMinutes",
                               schedule_retry_count as "retryCount"
                        from platform.sync_task
                        where last_status in ('PENDING', 'RUNNING')
                          and last_job_name is not null
                          and task_type = 'OFFLINE'
                          and last_run_at < now() - interval '25 seconds'
                          and id > ?
                        order by id
                        limit 20
                        """, cursor);
            } catch (Exception e) {
                log.debug("integration status reconcile skipped: {}", e.getMessage());
                return;
            }
            if (running.isEmpty()) {
                return;
            }
            for (Map<String, Object> t : running) {
                cursor = Math.max(cursor, ((Number) t.get("id")).longValue());
                processed++;
                reconcileOne(t);
            }
            if (running.size() < 20) {
                return;
            }
        }
    }

    private void reconcileOne(Map<String, Object> t) {
            long id = ((Number) t.get("id")).longValue();
            try {
                Map<String, Object> st = controller.refreshStatus(id);
                String status = String.valueOf(st.get("status"));
                if (("PENDING".equals(status) || "RUNNING".equals(status)) && timedOut(t)) {
                    String jobName = String.valueOf(t.get("jobName"));
                    controller.stopJob(jobName);
                    jdbc.update("update platform.sync_task set last_status = 'TIMEOUT' where id = ?", id);
                    // 只改任务表会让运行实例永远停在 RUNNING，运行历史/耗时/事件都错
                    jdbc.update("""
                            update platform.sync_task_run
                            set status = 'TIMEOUT',
                                finished_at = coalesce(finished_at, now()),
                                duration_ms = coalesce(duration_ms,
                                    (extract(epoch from (now() - coalesce(started_at, created_at)))
                                     * 1000)::bigint),
                                failure_reason = coalesce(failure_reason, '调度超时，作业已停止')
                            where task_id = ? and job_name = ?
                              and status in ('SUBMITTED', 'RUNNING', 'QUEUED')
                            """, id, jobName);
                    Long runId = jdbc.queryForObject("""
                            select id from platform.sync_task_run
                            where task_id = ? and job_name = ?
                            order by id desc limit 1
                            """, Long.class, id, jobName);
                    if (runId != null) {
                        runEvents.emit(runId, "done", "ERROR", "调度超时，作业已停止", null);
                    }
                    log.warn("scheduled run timed out task={} code={} job={} after {} min",
                            id, t.get("code"), jobName, t.get("timeoutMinutes"));
                    status = "TIMEOUT";
                }
                if ("FAILED".equals(status) || "TIMEOUT".equals(status)) {
                    maybeRetry(id, t);
                }
            } catch (Exception e) {
                log.warn("status reconcile failed task={} code={}: {}",
                        id, t.get("code"), e.getMessage());
            }
    }

    private boolean timedOut(Map<String, Object> task) {
        Object lastRun = task.get("lastRunAt");
        if (!(lastRun instanceof Timestamp ts)) {
            return false;
        }
        int minutes = intOf(task.get("timeoutMinutes"), 60);
        return ts.toInstant().plusSeconds(minutes * 60L).isBefore(Instant.now());
    }

    /** 失败/超时后按 retry_times 立即再跑一次；次数记在 schedule_retry_count。 */
    private void maybeRetry(long id, Map<String, Object> task) {
        int allow = intOf(task.get("retryTimes"), 0);
        int used = intOf(task.get("retryCount"), 0);
        if (allow <= 0 || used >= allow) {
            return;
        }
        // 乐观锁：只有把「已用次数」从 used 推进到 used+1 的那个副本才真正重试
        int updated = jdbc.update("""
                update platform.sync_task set schedule_retry_count = schedule_retry_count + 1
                where id = ? and schedule_retry_count = ?
                """, id, used);
        if (updated == 0) {
            return;
        }
        try {
            String jobName = controller.runScheduled(id, false);
            log.info("retry {}/{} task={} code={} job={}",
                    used + 1, allow, id, task.get("code"), jobName);
        } catch (Exception e) {
            log.warn("retry failed task={} code={}: {}", id, task.get("code"), e.getMessage());
        }
    }

    private static int intOf(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private boolean isDue(Map<String, Object> task, Timestamp base) {
        String rawCron = String.valueOf(task.get("scheduleCron"));
        String zoneName = task.get("scheduleTimezone") == null
                ? "Asia/Shanghai" : String.valueOf(task.get("scheduleTimezone"));
        ZoneId zone = ZoneId.of(zoneName);
        CronExpression cron;
        try {
            cron = CronExpression.parse(normalizeCron(rawCron));
        } catch (IllegalArgumentException e) {
            log.warn("invalid cron on task={}: {}", task.get("id"), rawCron);
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = cron.next(base.toInstant().atZone(zone));
        return next != null && !next.isAfter(now);
    }

    /** 下次触发时间（ISO-8601，带时区偏移）；cron 非法或未开调度返回 null。 */
    public static String nextRunAt(String cron, String timezone) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        try {
            ZoneId zone = ZoneId.of(timezone == null || timezone.isBlank()
                    ? "Asia/Shanghai" : timezone);
            ZonedDateTime next = CronExpression.parse(normalizeCron(cron))
                    .next(ZonedDateTime.now(zone));
            return next == null ? null : next.toOffsetDateTime().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 5 段（分 时 日 月 周）补一个秒位，兼容前端常见的定时写法。 */
    static String normalizeCron(String cron) {
        String c = cron == null ? "" : cron.trim();
        return c.split("\\s+").length == 5 ? "0 " + c : c;
    }
}
