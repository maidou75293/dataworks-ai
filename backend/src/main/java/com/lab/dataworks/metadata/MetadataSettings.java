package com.lab.dataworks.metadata;

import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 元数据来源开关（V101）。
 *
 * <ul>
 *   <li>{@link #GRAVITINO}：元数据由 Gravitino 统一维护（默认）。DORIS 数据源
 *       保存时同步注册 Catalog。</li>
 *   <li>{@link #DIRECT}：元数据直连各数据源，跳过 Gravitino 同步。</li>
 * </ul>
 *
 * <p>配置存 {@code platform.setting}（键 {@code metadata.source}），进程内缓存 3 秒，
 * 管理员切换后下一次请求即生效（setter 会同步刷新本机缓存）。本期开关只影响数据源
 * 的 Catalog 同步，不改变元数据读取链路。</p>
 */
@Component
public class MetadataSettings {

    private static final Logger log = LoggerFactory.getLogger(MetadataSettings.class);

    public static final String KEY = "metadata.source";
    public static final String GRAVITINO = "gravitino";
    public static final String DIRECT = "direct";

    private static final long TTL_MS = 3_000L;

    private final JdbcTemplate jdbc;
    private final long ttlMs;
    private volatile String cached = GRAVITINO;
    private volatile long cachedAt = 0L;

    @org.springframework.beans.factory.annotation.Autowired
    public MetadataSettings(JdbcTemplate jdbc) {
        this(jdbc, TTL_MS);
    }

    /** 测试用：可缩短缓存 TTL。 */
    MetadataSettings(JdbcTemplate jdbc, long ttlMs) {
        this.jdbc = jdbc;
        this.ttlMs = Math.max(0L, ttlMs);
    }

    public String source() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < ttlMs) {
            return cached;
        }
        String value = GRAVITINO;
        try {
            List<String> rows = jdbc.queryForList(
                    "select setting_value from platform.setting where setting_key = ?",
                    String.class, KEY);
            if (!rows.isEmpty()) {
                String raw = rows.get(0) == null ? "" : rows.get(0).trim();
                if (GRAVITINO.equalsIgnoreCase(raw)) {
                    value = GRAVITINO;
                } else if (DIRECT.equalsIgnoreCase(raw)) {
                    value = DIRECT;
                } else {
                    // 值被手工改坏时回落到默认 gravitino，而不是静默关掉同步
                    log.warn("invalid {}={}, fallback {}", KEY, raw, GRAVITINO);
                }
            }
        } catch (RuntimeException e) {
            // 迁移没跑到/数据库抖动不能把数据源保存打挂：按默认继续并留日志
            log.warn("read metadata.source failed, fallback {}: {}", cached, e.getMessage());
            value = cached;
        }
        cached = value;
        cachedAt = now;
        return value;
    }

    public boolean gravitinoEnabled() {
        return GRAVITINO.equals(source());
    }

    public void setSource(String source, String actor) {
        String normalized = normalize(source);
        jdbc.update("""
                insert into platform.setting (setting_key, setting_value, updated_at, updated_by)
                values (?, ?, now(), ?)
                on conflict (setting_key) do update
                  set setting_value = excluded.setting_value,
                      updated_at = now(),
                      updated_by = excluded.updated_by
                """, KEY, normalized, actor);
        cached = normalized;
        cachedAt = System.currentTimeMillis();
    }

    /** 非法值必须报 400：开关取值只能精确匹配，不能「猜一个」。 */
    static String normalize(String source) {
        String value = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (!GRAVITINO.equals(value) && !DIRECT.equals(value)) {
            throw ApiException.badRequest("metadata.source 只能是 gravitino 或 direct");
        }
        return value;
    }
}
