package com.lab.dataworks.flywheel;

import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 编排运行参数（REQ-FW-4）——存 {@code platform.setting}，口径同 {@link FlywheelSettings}：
 * 一次读取一遍主键查询、改了下一圈即生效，不做进程内缓存。
 *
 * <p>四个键（V200 种子）：{@code flywheel.orch.annotation_gate}/{@code eval_gate}
 * （MANUAL=人工质量闸 / AUTO=配置自动放行）、{@code flywheel.orch.eval_min_score}
 * （AUTO 模式放行下限——低分不放行，AUTO≠无条件放行）、{@code flywheel.orch.auto_renew}
 * （一圈 CLOSED 后是否自动续下一圈）。非法值显式拒绝写入（不许静默），读取容错回默认并告警。</p>
 */
@Component
public class OrchestratorSettings {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorSettings.class);

    public static final String KEY_ANNOTATION_GATE = "flywheel.orch.annotation_gate";
    public static final String KEY_EVAL_GATE = "flywheel.orch.eval_gate";
    public static final String KEY_EVAL_MIN_SCORE = "flywheel.orch.eval_min_score";
    public static final String KEY_AUTO_RENEW = "flywheel.orch.auto_renew";

    /** 设置端点允许读写的键白名单——不在名单里的键一律 400（防拿编排口改任意 setting）。 */
    public static final Set<String> WRITABLE_KEYS = Set.of(
            KEY_ANNOTATION_GATE, KEY_EVAL_GATE, KEY_EVAL_MIN_SCORE, KEY_AUTO_RENEW,
            FlywheelSettings.KEY_BATCH_SIZE, FlywheelSettings.KEY_CONFIDENCE,
            FlywheelSettings.KEY_TARGET_MODEL);

    static final String DEFAULT_GATE = "MANUAL";
    static final double DEFAULT_EVAL_MIN_SCORE = 7.0;

    private final JdbcTemplate jdbc;

    public OrchestratorSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String annotationGate() {
        return gateValue(read(KEY_ANNOTATION_GATE), KEY_ANNOTATION_GATE);
    }

    public String evalGate() {
        return gateValue(read(KEY_EVAL_GATE), KEY_EVAL_GATE);
    }

    public double evalMinScore() {
        String raw = read(KEY_EVAL_MIN_SCORE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_EVAL_MIN_SCORE;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (Double.isNaN(v)) {
                throw new NumberFormatException("NaN");
            }
            return v;
        } catch (NumberFormatException e) {
            log.warn("flywheel setting {} 不可解析（{}），按默认 {} 执行", KEY_EVAL_MIN_SCORE, raw, DEFAULT_EVAL_MIN_SCORE);
            return DEFAULT_EVAL_MIN_SCORE;
        }
    }

    public boolean autoRenew() {
        String raw = read(KEY_AUTO_RENEW);
        return raw == null || raw.isBlank() || !"false".equalsIgnoreCase(raw.trim());
    }

    /** 全量视图（设置弹窗/审计用）；只回本包 + FW-3 白名单键。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : WRITABLE_KEYS) {
            out.put(key, read(key));
        }
        return out;
    }

    /**
     * 白名单写入 + 值校验（非法 400 报当前值）。调用方（控制器）负责权限与审计。
     */
    public void write(String key, String value) {
        if (key == null || !WRITABLE_KEYS.contains(key)) {
            throw ApiException.badRequest("不允许的设置键：" + key + "（白名单=" + WRITABLE_KEYS + "）");
        }
        String v = value == null ? "" : value.trim();
        switch (key) {
            case KEY_ANNOTATION_GATE, KEY_EVAL_GATE -> {
                if (!"MANUAL".equalsIgnoreCase(v) && !"AUTO".equalsIgnoreCase(v)) {
                    throw ApiException.badRequest(key + " 只允许 MANUAL/AUTO（收到：" + value + "）");
                }
                v = v.toUpperCase(Locale.ROOT);
            }
            case KEY_EVAL_MIN_SCORE -> {
                double d;
                try {
                    d = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest(key + " 必须是数字（收到：" + value + "）");
                }
                if (d < 0 || d > 10) {
                    throw ApiException.badRequest(key + " 允许 0–10（评测 avg 口径 0–10，收到：" + value + "）");
                }
            }
            case KEY_AUTO_RENEW -> {
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                    throw ApiException.badRequest(key + " 只允许 true/false（收到：" + value + "）");
                }
                v = v.toLowerCase(Locale.ROOT);
            }
            case FlywheelSettings.KEY_BATCH_SIZE -> {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest(key + " 必须是整数（收到：" + value + "）");
                }
                if (n <= 0) {
                    throw ApiException.badRequest(key + " 必须为正整数（收到：" + value + "）");
                }
            }
            case FlywheelSettings.KEY_CONFIDENCE -> {
                double d;
                try {
                    d = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest(key + " 必须是数字（收到：" + value + "）");
                }
                if (d < 0 || d > 1) {
                    throw ApiException.badRequest(key + " 允许 0–1（收到：" + value + "）");
                }
            }
            default -> {
                // KEY_TARGET_MODEL：字符串原样存（空串=未配置，同 FlywheelSettings.targetModel 口径）
            }
        }
        jdbc.update("insert into platform.setting (setting_key, setting_value, updated_by) "
                        + "values (?, ?, 'flywheel-orch') "
                        + "on conflict (setting_key) do update set setting_value = excluded.setting_value, "
                        + "updated_at = now(), updated_by = 'flywheel-orch'",
                key, v);
    }

    private String gateValue(String raw, String key) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_GATE;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if ("AUTO".equals(v)) {
            return "AUTO";
        }
        if (!"MANUAL".equals(v)) {
            log.warn("flywheel setting {} 非法值（{}），按 MANUAL 执行", key, raw);
        }
        return "MANUAL";
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
