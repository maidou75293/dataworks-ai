package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 实时运行的周期采样（每 30 秒）。
 *
 * <p>数据可得性分级：
 * <ul>
 *   <li>运行时长、读取/写入行数：来自客户端日志的周期性汇总（SeaTunnel 每 60 秒打印一次）；</li>
 *   <li>Kafka lag：后端用 AdminClient 直接算消费组积压，只对 Kafka 源有意义；</li>
 *   <li>checkpoint：SeaTunnel 2.3.13 的 HTTP 指标接口只暴露 JVM 指标，取不到作业级
 *       checkpoint，样本里明确记 {@code checkpointSupported=false}，不伪造数字。</li>
 * </ul>
 */
@Service
public class RunMetricService {

    private static final Logger log = LoggerFactory.getLogger(RunMetricService.class);

    private final JdbcTemplate jdbc;
    private final SyncTaskService tasks;
    private final ObjectMapper json = new ObjectMapper();

    public RunMetricService(JdbcTemplate jdbc, SyncTaskService tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Scheduled(initialDelayString = "${platform.run-metric-initial-ms:25000}",
            fixedDelayString = "${platform.run-metric-ms:30000}")
    public void sampleRunning() {
        List<Map<String, Object>> runs;
        try {
            runs = jdbc.queryForList("""
                    select r.id as "runId", r.job_name as "jobName",
                           r.started_at as "startedAt",
                           t.source_type as "sourceType",
                           t.source_config::text as "sourceConfig"
                    from platform.sync_task_run r
                    join platform.sync_task t on t.id = r.task_id
                    where r.status in ('RUNNING', 'PENDING')
                      and t.task_type = 'REALTIME'
                    order by r.id
                    limit 50
                    """);
        } catch (Exception e) {
            return;      // 迁移未跑完
        }
        for (Map<String, Object> run : runs) {
            try {
                Map<String, Object> metrics = sample(run);
                jdbc.update("""
                        insert into platform.sync_run_metric (run_id, metrics)
                        values (?, ?::json)
                        """, run.get("runId"), json.writeValueAsString(metrics));
            } catch (Exception e) {
                log.warn("sample run metric failed run={}: {}", run.get("runId"), e.getMessage());
            }
        }
    }

    private Map<String, Object> sample(Map<String, Object> run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkpointSupported", false);
        Object started = run.get("startedAt");
        if (started instanceof java.sql.Timestamp ts) {
            out.put("elapsedSeconds",
                    Duration.between(ts.toInstant(), Instant.now()).getSeconds());
        }
        Map<String, Long> logMetrics = tasks.logMetrics(String.valueOf(run.get("jobName")));
        if (logMetrics.get("rowsRead") != null) {
            out.put("rowsRead", logMetrics.get("rowsRead"));
        }
        if (logMetrics.get("rowsWritten") != null) {
            out.put("rowsWritten", logMetrics.get("rowsWritten"));
        }
        if ("Kafka".equals(String.valueOf(run.get("sourceType")))) {
            Long lag = kafkaLag(run.get("sourceConfig"));
            if (lag != null) {
                out.put("lag", lag);
            }
        }
        return out;
    }

    /** 消费组积压 = Σ(分区最新位点 - 已提交位点)。取不到（组还没提交位点）时返回 null。 */
    @SuppressWarnings("unchecked")
    Long kafkaLag(Object rawConfig) {
        Map<String, Object> cfg;
        try {
            cfg = rawConfig == null ? Map.of()
                    : json.readValue(String.valueOf(rawConfig), Map.class);
        } catch (Exception e) {
            return null;
        }
        String bootstrap = str(cfg.get("bootstrap.servers"));
        String group = str(cfg.get("consumer.group"));
        if (bootstrap == null || group == null) {
            return null;
        }
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("request.timeout.ms", "5000");
        props.put("default.api.timeout.ms", "6000");
        try (AdminClient admin = AdminClient.create(props)) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin
                    .listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(6, TimeUnit.SECONDS);
            if (committed.isEmpty()) {
                return null;
            }
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            committed.keySet().forEach(tp -> latest.put(tp, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(latest).all().get(6, TimeUnit.SECONDS);
            long lag = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                var end = ends.get(e.getKey());
                if (end == null) {
                    continue;
                }
                lag += Math.max(0, end.offset() - e.getValue().offset());
            }
            return lag;
        } catch (Exception e) {
            return null;
        }
    }

    /** 每天凌晨清理 30 天前的采样。 */
    @Scheduled(cron = "0 35 3 * * *")
    public void scheduledPrune() {
        pruneSamples(30);
    }

    public int pruneSamples(int days) {
        try {
            return jdbc.update("""
                    delete from platform.sync_run_metric where ts < now() - (? * interval '1 day')
                    """, days);
        } catch (Exception e) {
            log.warn("prune run metrics failed: {}", e.getMessage());
            return 0;
        }
    }

    public List<Map<String, Object>> samples(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, ts, metrics::text as "metrics",
                       (extract(epoch from ts) * 1000)::bigint as "tsMs"
                from platform.sync_run_metric
                where run_id = ?
                order by id desc limit 200
                """, runId);
        // jsonb 直接取出来是 PGobject 包装，转成普通 Map 给前端/测试
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

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
