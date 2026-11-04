package com.lab.dataworks.flywheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 飞轮③的运行参数（REQ-FW-3）——存 {@code platform.setting}（V58 通用运行时设置表，
 * 即卡面口径的 sys_config），阈值默认不做死：{@code flywheel.badcase.confidence_threshold=0.6}、
 * {@code flywheel.badcase.batch_size=50}、{@code flywheel.badcase.cursor_id}（采集游标，
 * 由本包唯一写入方）、{@code flywheel.relabel.target_model}（目标模型名，写进 mos 任务描述）。
 *
 * <p>与 {@code MetadataSettings} 不同，这里不做进程内缓存：读取点只在定时 tick 开头，
 * 一次 tick 一遍全表主键查询，改了下一圈即生效，不值得为它引入 TTL 与失效语义。</p>
 */
@Component
public class FlywheelSettings {

    private static final Logger log = LoggerFactory.getLogger(FlywheelSettings.class);

    static final String KEY_CONFIDENCE = "flywheel.badcase.confidence_threshold";
    static final String KEY_BATCH_SIZE = "flywheel.badcase.batch_size";
    static final String KEY_CURSOR = "flywheel.badcase.cursor_id";
    static final String KEY_TARGET_MODEL = "flywheel.relabel.target_model";

    static final double DEFAULT_CONFIDENCE = 0.6;
    static final int DEFAULT_BATCH_SIZE = 50;
    static final long DEFAULT_CURSOR = 0L;

    private final JdbcTemplate jdbc;

    public FlywheelSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 置信度低于该值判 LOW_CONFIDENCE；键缺失或不可解析时回退 0.6 并告警（旁路任务不中断）。 */
    public double confidenceThreshold() {
        String raw = read(KEY_CONFIDENCE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CONFIDENCE;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("flywheel setting {} 不可解析（{}），按默认 {} 执行", KEY_CONFIDENCE, raw, DEFAULT_CONFIDENCE);
            return DEFAULT_CONFIDENCE;
        }
    }

    /** 池内同租户 NEW 行数达到该值即封批触发回环；缺失/非法回退 50。 */
    public int batchSize() {
        String raw = read(KEY_BATCH_SIZE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BATCH_SIZE;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v <= 0) {
                log.warn("flywheel setting {}={} 非正数，按默认 {} 执行", KEY_BATCH_SIZE, raw, DEFAULT_BATCH_SIZE);
                return DEFAULT_BATCH_SIZE;
            }
            return v;
        } catch (NumberFormatException e) {
            log.warn("flywheel setting {} 不可解析（{}），按默认 {} 执行", KEY_BATCH_SIZE, raw, DEFAULT_BATCH_SIZE);
            return DEFAULT_BATCH_SIZE;
        }
    }

    /** 已采集到的 usage_query_event 最大 id（含跳过不成池的行，防止每圈重扫）。 */
    public long cursorId() {
        String raw = read(KEY_CURSOR);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CURSOR;
        }
        try {
            return Math.max(DEFAULT_CURSOR, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("flywheel setting {} 不可解析（{}），按默认 {} 执行", KEY_CURSOR, raw, DEFAULT_CURSOR);
            return DEFAULT_CURSOR;
        }
    }

    public void setCursorId(long cursor) {
        jdbc.update("insert into platform.setting (setting_key, setting_value, updated_by) "
                        + "values (?, ?, 'flywheel') "
                        + "on conflict (setting_key) do update set setting_value = excluded.setting_value, "
                        + "updated_at = now(), updated_by = 'flywheel'",
                KEY_CURSOR, String.valueOf(cursor));
    }

    /** 目标模型名（写进 mos 重标注任务描述）；未配置返回空串。 */
    public String targetModel() {
        String raw = read(KEY_TARGET_MODEL);
        return raw == null ? "" : raw.trim();
    }

    private String read(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select setting_value from platform.setting where setting_key = ?", key);
        if (rows.isEmpty() || rows.get(0).get("setting_value") == null) {
            return null;
        }
        return String.valueOf(rows.get(0).get("setting_value"));
    }
}
