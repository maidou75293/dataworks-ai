package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行实例的业务进度事件。
 *
 * <p>阶段固定为 {@code prepare / read / transform / write / verify / done}；
 * 平台只能观测到「提交 / 开始运行 / 写完 / 校验 / 结束」，transform 阶段暂不产生事件
 * （SeaTunnel 不暴露算子级进度）。
 *
 * <p>写事件失败不能影响主流程，只记日志。
 */
@Service
public class RunEventService {

    private static final Logger log = LoggerFactory.getLogger(RunEventService.class);
    private static final Set<String> PHASES = Set.of(
            "prepare", "read", "transform", "write", "verify", "done");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public RunEventService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void emit(long runId, String phase, String type, String message,
                     Map<String, Object> metrics) {
        if (runId <= 0 || !PHASES.contains(phase)) {
            return;
        }
        try {
            jdbc.update("""
                    insert into platform.sync_run_event (run_id, phase, type, message, metrics)
                    values (?, ?, ?, ?, ?::json)
                    """, runId, phase, type == null ? "INFO" : type, message,
                    metrics == null || metrics.isEmpty()
                            ? null : json.writeValueAsString(metrics));
        } catch (Exception e) {
            log.warn("emit run event failed run={} phase={}: {}", runId, phase, e.getMessage());
        }
    }

    public List<Map<String, Object>> events(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, run_id as "runId", ts, phase, type, message,
                       metrics::text as "metrics",
                       (extract(epoch from ts) * 1000)::bigint as "tsMs"
                from platform.sync_run_event
                where run_id = ?
                order by id
                """, runId);
        for (Map<String, Object> row : rows) {
            Object raw = row.get("metrics");
            if (raw instanceof String text && !text.isBlank()) {
                try {
                    row.put("metrics", json.readValue(text, Map.class));
                } catch (Exception ignored) {
                    // 保持原样
                }
            }
        }
        return rows;
    }

    /** 每天凌晨清理 90 天前的事件。 */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 3 * * *")
    public void scheduledPrune() {
        pruneEvents(90);
    }

    public int pruneEvents(int days) {
        try {
            return jdbc.update("""
                    delete from platform.sync_run_event where ts < now() - (? * interval '1 day')
                    """, days);
        } catch (Exception e) {
            log.warn("prune run events failed: {}", e.getMessage());
            return 0;
        }
    }

    /** 便利方法：一串键值对转 metrics。 */
    public static Map<String, Object> metrics(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                out.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return out;
    }
}
