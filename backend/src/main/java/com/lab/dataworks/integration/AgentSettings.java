package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 助手权限模式（运行时开关，落库，不重启即生效）。
 *
 * <ul>
 *   <li>{@link #SYSTEM}：平台全权。内部接口不看发起人权限点——仅用于开发阶段用助手测功能。</li>
 *   <li>{@link #DELEGATE}：跟随发起人。工具调用按发起人在当前租户下的权限点判定。</li>
 * </ul>
 *
 * <p>两种模式下租户过滤都生效：租户是数据边界，不属于本开关的范围。</p>
 */
@Component
public class AgentSettings {

    public static final String SYSTEM = "SYSTEM";
    public static final String DELEGATE = "DELEGATE";
    public static final String KEY = "identity_mode";

    private static final long TTL_MS = 3_000L;

    private final JdbcTemplate jdbc;
    private volatile String cached = SYSTEM;
    private volatile long cachedAt = 0L;

    public AgentSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String identityMode() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < TTL_MS) {
            return cached;
        }
        String value;
        try {
            List<String> rows = jdbc.queryForList(
                    "select setting_value from platform.agent_setting where setting_key = ?",
                    String.class, KEY);
            value = rows.isEmpty() ? SYSTEM : rows.get(0);
        } catch (Exception e) {
            // 迁移还没跑到时不能把助手打挂：按升级前的行为（全权）继续，日志留痕
            org.slf4j.LoggerFactory.getLogger(AgentSettings.class)
                    .warn("read agent setting failed, fallback {}: {}", cached, e.getMessage());
            value = cached;
        }
        cached = normalizeQuiet(value);
        cachedAt = now;
        return cached;
    }

    public void setIdentityMode(String mode, String actor) {
        String normalized = normalize(mode);
        jdbc.update("""
                insert into platform.agent_setting (setting_key, setting_value, updated_at, updated_by)
                values (?, ?, now(), ?)
                on conflict (setting_key) do update
                  set setting_value = excluded.setting_value,
                      updated_at = now(),
                      updated_by = excluded.updated_by
                """, KEY, normalized, actor);
        // 立刻同步本机缓存：管理员切完开关，下一次请求就要生效，不能等 TTL
        cached = normalized;
        cachedAt = System.currentTimeMillis();
    }

    private static String normalize(String mode) {
        String value = mode == null ? "" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SYSTEM.equals(value) && !DELEGATE.equals(value)) {
            throw ApiException.badRequest("identityMode 只能是 SYSTEM 或 DELEGATE");
        }
        return value;
    }

    private static String normalizeQuiet(String mode) {
        return DELEGATE.equalsIgnoreCase(mode) ? DELEGATE : SYSTEM;
    }
}
