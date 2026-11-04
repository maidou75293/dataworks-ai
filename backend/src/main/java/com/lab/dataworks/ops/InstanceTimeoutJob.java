package com.lab.dataworks.ops;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.prefect.PrefectBridgeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 运行超时兜底（v450 P1-8）。
 *
 * <p>以前实例只有「queued 24 小时清理」，RUNNING 的慢 SQL / 死循环可以无限占住
 * 同业务日期的触发窗口。这里按 {@code platform.instance-timeout-hours}（默认 24 小时）
 * 扫描仍在 queued/running 的实例：先取消 Prefect run，再落 FAILED + 原因，并写审计。</p>
 */
@Component
public class InstanceTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(InstanceTimeoutJob.class);

    private final JdbcTemplate jdbc;
    private final PrefectBridgeClient prefect;
    private final SessionService sessions;
    private final int timeoutHours;

    public InstanceTimeoutJob(JdbcTemplate jdbc, PrefectBridgeClient prefect,
                              SessionService sessions,
                              @Value("${platform.instance-timeout-hours:24}") int timeoutHours) {
        this.jdbc = jdbc;
        this.prefect = prefect;
        this.sessions = sessions;
        this.timeoutHours = timeoutHours;
    }

    @Scheduled(fixedDelayString = "${platform.instance-timeout-scan-ms:600000}", initialDelay = 60000)
    public void scan() {
        if (timeoutHours <= 0) {
            return;
        }
        // v467 G-P1-9：按 id 游标分页扫描，而不是每轮固定 `order by id limit 50`。
        // 固定窗口会「饿死」：前 50 条因为取消失败/并发状态变化留在集合里时，
        // 第 51 条之后永远轮不到。游标保证每轮从上次位置继续，单轮上限防止扫爆。
        long cursor = 0;
        int processed = 0;
        int batch = 50;
        int maxPerScan = 5000;
        while (processed < maxPerScan) {
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList("""
                        select id, run_id, dag_id from platform.wf_instance
                         where state in ('queued', 'running')
                           and coalesce(started_at, created_at) < now() - (? || ' hours')::interval
                           and id > ?
                         order by id limit ?
                        """, String.valueOf(timeoutHours), cursor, batch);
            } catch (RuntimeException e) {
                log.warn("instance timeout scan failed: {}", e.getMessage());
                return;
            }
            if (rows.isEmpty()) {
                return;
            }
            for (Map<String, Object> row : rows) {
                cursor = Math.max(cursor, ((Number) row.get("id")).longValue());
                handle(row);
                processed++;
            }
            if (rows.size() < batch) {
                return;
            }
        }
        log.info("instance timeout scan hit {} per-run cap, continuing next round at id {}",
                maxPerScan, cursor);
    }

    private void handle(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        Object runId = row.get("run_id");
        if (runId != null && !String.valueOf(runId).isBlank()) {
            try {
                prefect.cancelRun(String.valueOf(runId));
            } catch (RuntimeException e) {
                // 执行器不可达也要把本地状态收敛，否则永远占着触发窗口
                log.warn("timeout cancel instance {} failed: {}", id, e.getMessage());
            }
        }
        String reason = "运行超时：超过 " + timeoutHours + " 小时仍未结束，已自动取消";
        int updated = jdbc.update("""
                update platform.wf_instance
                   set state = 'failed', error_message = ?, ended_at = now(), updated_at = now()
                 where id = ? and state in ('queued', 'running')
                """, reason, id);
        if (updated > 0) {
            sessions.audit("system", "INSTANCE_TIMEOUT", "i" + id, reason);
            log.info("instance {} timed out after {}h -> failed", id, timeoutHours);
        }
    }
}
