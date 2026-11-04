package com.lab.dataworks.ops;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.prefect.PrefectBridgeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OpsSyncService {

    private static final Logger log = LoggerFactory.getLogger(OpsSyncService.class);

    /** error_message 里补充日志的标记；用它判断这条实例是否已经补过。 */
    private static final String ENRICHED_MARK = "错误日志：";

    private final JdbcTemplate jdbc;
    private final PrefectBridgeClient prefect;
    private volatile long lastFullSync;

    public OpsSyncService(JdbcTemplate jdbc, PrefectBridgeClient prefect) {
        this.jdbc = jdbc;
        this.prefect = prefect;
    }

    @Scheduled(fixedDelayString = "${platform.sync-ms:15000}")
    public void scheduledSync() {
        try {
            syncRecent();
            if (System.currentTimeMillis() - lastFullSync > 60_000) {
                lastFullSync = System.currentTimeMillis();
                syncKnownDeployments();
                expireGhostRuns();
            }
        } catch (Exception e) {
            log.warn("scheduled prefect sync failed: {}", e.getMessage());
        }
    }

    /**
     * 幽灵运行：Prefect 侧长期未开始的 queued/late run 会一直占着业务日期，
     * 列表里也永远显示「排队中」。超过 24 小时仍未开始就按失败收尾；
     * 若 Prefect 之后真的启动它，下一轮同步会把状态改回 running（可见，不会静默）。
     */
    private void expireGhostRuns() {
        try {
            int n = jdbc.update("""
                    update platform.wf_instance
                    set state = 'failed', ended_at = now(),
                        error_message = coalesce(error_message,
                            '启动超时：Prefect 24 小时内未开始运行'),
                        updated_at = now()
                    where state in ('queued', 'pending', 'scheduled', 'late')
                      and created_at < now() - interval '24 hours'
                    """);
            if (n > 0) {
                log.info("expired {} ghost queued instances", n);
            }
        } catch (Exception e) {
            log.warn("expire ghost runs failed: {}", e.getMessage());
        }
    }

    public void syncRecent() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, dag_id, run_id, state from platform.wf_instance
                where created_at > now() - interval '3 days'
                   or state not in ('success', 'failed', 'cancelled')
                order by id desc limit 200
                """);
        for (Map<String, Object> row : rows) {
            try {
                syncInstance(String.valueOf(row.get("dag_id")), String.valueOf(row.get("run_id")));
            } catch (Exception e) {
                log.warn("sync instance failed deployment={} run={}: {}",
                        row.get("dag_id"), row.get("run_id"), e.getMessage());
            }
        }
    }

    public void syncKnownDeployments() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select workflow_id, prefect_deployment_id
                from platform.workflow_deployment
                where prefect_deployment_id is not null
                union
                select workflow_id, prefect_deployment_id
                from platform.workflow_version
                where prefect_deployment_id is not null
                """);
        for (Map<String, Object> row : rows) {
            long workflowId = ((Number) row.get("workflow_id")).longValue();
            String deploymentId = String.valueOf(row.get("prefect_deployment_id"));
            try {
                for (PrefectBridgeClient.Run run : prefect.listRuns(deploymentId, 20)) {
                    upsertRun(workflowId, deploymentId, run);
                }
            } catch (ApiException e) {
                if (e.getStatus() != 404) {
                    log.debug("list runs failed deployment={}: {}", deploymentId, e.getMessage());
                }
            }
        }
    }

    public void syncInstance(String deploymentId, String runId) {
        PrefectBridgeClient.Run run;
        try {
            run = prefect.getRun(runId);
        } catch (ApiException e) {
            if (e.getStatus() == 404) {
                return;
            }
            throw e;
        }
        Long workflowId = findWorkflowId(deploymentId);
        if (workflowId == null) {
            log.warn("no platform workflow maps to prefect deployment={}", deploymentId);
            return;
        }
        long instanceId = upsertRun(workflowId, deploymentId, run);
        enrichFailureReason(instanceId, run);
        syncTasks(instanceId, runId);
    }

    /**
     * Prefect 对「进程直接退出」的崩溃只给 "Process exited with status code: 1"，
     * 没有任何可操作信息。这类消息去日志里捞最后一条 ERROR（通常是真实异常，
     * 例如 FileNotFoundError）补进去；普通失败（state.message 带具体异常）不动。
     * 已经补过的实例（含 ENRICHED_MARK）不再重复请求 Prefect。
     */
    private void enrichFailureReason(long instanceId, PrefectBridgeClient.Run run) {
        if (instanceId <= 0) {
            return;
        }
        String state = normalizeState(run.state());
        if (!"failed".equals(state) && !"upstream_failed".equals(state)) {
            return;
        }
        List<String> current = jdbc.queryForList(
                "select coalesce(error_message, '') from platform.wf_instance where id = ?",
                String.class, instanceId);
        String message = current.isEmpty() ? "" : current.get(0);
        if (message.contains(ENRICHED_MARK)) {
            return;
        }
        if (!message.isBlank() && !message.contains("exited with status code")) {
            return;
        }
        String detail = lastErrorLog(run.flowRunId());
        if (detail == null) {
            return;
        }
        String combined = message.isBlank()
                ? ENRICHED_MARK + detail
                : message + "\n\n" + ENRICHED_MARK + detail;
        jdbc.update(
                "update platform.wf_instance set error_message = ?, updated_at = now() where id = ?",
                combined, instanceId);
    }

    private String lastErrorLog(String runId) {
        try {
            List<PrefectBridgeClient.LogEntry> logs = prefect.listLogs(runId, null);
            for (int i = logs.size() - 1; i >= 0; i--) {
                PrefectBridgeClient.LogEntry entry = logs.get(i);
                if (entry.level() >= 40 && entry.message() != null && !entry.message().isBlank()) {
                    String text = entry.message().trim();
                    return text.length() > 3000 ? text.substring(0, 3000) : text;
                }
            }
        } catch (Exception e) {
            log.debug("read error log failed run={}: {}", runId, e.getMessage());
        }
        return null;
    }

    private void syncTasks(long instanceId, String runId) {
        try {
            for (PrefectBridgeClient.TaskRun task : prefect.listTasks(runId)) {
                String code = taskCode(task);
                if (code == null || code.isBlank()) {
                    continue;
                }
                String state = normalizeState(task.state());
                jdbc.update("""
                        insert into platform.task_instance
                            (wf_instance_id, task_code, task_name, state, try_number,
                             started_at, ended_at, prefect_task_run_id, error_message)
                        values (?, ?, ?, ?, 1, ?::timestamptz, ?::timestamptz, ?, ?)
                        on conflict (wf_instance_id, task_code) do update set
                            task_name = excluded.task_name, state = excluded.state,
                            started_at = excluded.started_at, ended_at = excluded.ended_at,
                            prefect_task_run_id = excluded.prefect_task_run_id,
                            error_message = case
                                when excluded.state in ('failed', 'upstream_failed')
                                    then coalesce(excluded.error_message,
                                                  platform.task_instance.error_message)
                                else null end,
                            updated_at = now()
                        """, instanceId, code, task.name(), state,
                        task.startTime(), task.endTime(), task.id(),
                        errorMessage(state, task.stateMessage()));
            }
            // v450 P2：Prefect 只把异常放在 Flow Run 的 state.message 里时，task 级
            // error 会是空。失败任务没有自己的原因时回填实例级原因（实例级一定有）。
            jdbc.update("""
                    update platform.task_instance t
                       set error_message = i.error_message, updated_at = now()
                      from platform.wf_instance i
                     where i.id = t.wf_instance_id and i.id = ?
                       and t.error_message is null
                       and t.state in ('failed', 'upstream_failed')
                       and i.error_message is not null
                    """, instanceId);
        } catch (Exception e) {
            log.debug("task sync failed run={}: {}", runId, e.getMessage());
        }
    }

    private String taskCode(PrefectBridgeClient.TaskRun task) {
        String key = task.taskKey();
        if (key != null && key.startsWith("t_")) {
            String value = key.substring(2);
            int dash = value.lastIndexOf('-');
            return dash > 0 ? value.substring(0, dash) : value;
        }
        String name = task.name();
        if (name == null) {
            return null;
        }
        int dash = name.lastIndexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }

    private long upsertRun(long workflowId, String deploymentId, PrefectBridgeClient.Run run) {
        String state = normalizeState(run.state());
        String bizDate = run.parameters() == null || run.parameters().get("biz_date") == null
                ? null : String.valueOf(run.parameters().get("biz_date"));
        // 定时触发的 run 没有 biz_date 参数：用开始时间（上海时区）的日期补上，
        // 否则实例列表里业务日期永远是空
        if (bizDate == null && run.startTime() != null) {
            try {
                bizDate = java.time.OffsetDateTime.parse(run.startTime())
                        .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                        .toLocalDate().toString();
            } catch (Exception ignored) {
                // 时间格式异常时不写，保持原来的空值
            }
        }
        Long id = jdbc.queryForObject("""
                insert into platform.wf_instance
                    (workflow_id, dag_id, run_id, biz_date, state, started_at, ended_at,
                     error_message, tenant_id)
                values (?, ?, ?, ?::date, ?, ?::timestamptz, ?::timestamptz, ?,
                        (select tenant_id from platform.workflow where id = ?))
                on conflict (dag_id, run_id) do update set
                    state = excluded.state, started_at = excluded.started_at,
                    ended_at = excluded.ended_at,
                    -- v467 G-P1-8：只有「成功」才清空错误。之前非失败态一律清空，
                    -- 超时兜底写入的「运行超时」会被下一轮 15s 同步（Prefect 报 cancelled）
                    -- 抹掉，用户再也看不到失败原因。
                    -- 失败态 Prefect 没给消息时保留上一次（含 §错误日志 补全）。
                    error_message = case
                        when excluded.state = 'success' then null
                        when excluded.state in ('failed', 'upstream_failed')
                            then coalesce(nullif(excluded.error_message, ''),
                                          platform.wf_instance.error_message)
                        else platform.wf_instance.error_message end,
                    updated_at = now()
                returning id
                """, Long.class, workflowId, deploymentId, run.flowRunId(), bizDate, state,
                run.startTime(), run.endTime(), errorMessage(state, run.stateMessage()),
                workflowId);
        return id == null ? -1 : id;
    }

    /** 只有失败态才记录原因；成功态的 state.message 多是 "All tasks completed!"，存下来会误导。 */
    private String errorMessage(String normalizedState, String message) {
        if (!"failed".equals(normalizedState) && !"upstream_failed".equals(normalizedState)) {
            return null;
        }
        return message == null || message.isBlank() ? null : message;
    }

    private Long findWorkflowId(String deploymentId) {
        List<Long> ids = jdbc.queryForList("""
                select workflow_id from platform.workflow_deployment
                where prefect_deployment_id = ? order by active desc, id desc limit 1
                """, Long.class, deploymentId);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        ids = jdbc.queryForList("""
                select workflow_id from platform.workflow_version
                where prefect_deployment_id = ? limit 1
                """, Long.class, deploymentId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String normalizeState(String state) {
        if (state == null) {
            return "queued";
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "success";
            case "FAILED", "CRASHED" -> "failed";
            case "CANCELLED" -> "cancelled";
            case "RUNNING" -> "running";
            default -> "queued";
        };
    }
}
