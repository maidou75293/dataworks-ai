package com.lab.dataworks.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时把历史明文敏感信息加密：数据源密码、同步任务源/目标密码、数据服务 Token。
 * 幂等；已经带 <code>enc:v1:</code> 前缀的行会跳过。
 */
@Component
public class SecretMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretMigration.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final ObjectMapper json = new ObjectMapper();

    public SecretMigration(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        total += encryptColumn("platform.datasource", "password");
        total += encryptColumn("platform.sync_task", "source_password");
        total += encryptColumn("platform.sync_task", "sink_password");
        total += encryptColumn("platform.data_service", "api_token");
        // v244 才引入通知渠道加密；之前创建的渠道仍是明文
        total += encryptColumn("platform.notify_channel", "secret");
        total += encryptNotifyConfigPasswords();
        if (total > 0) {
            log.info("敏感信息加密迁移完成：共 {} 个字段已加密", total);
        }
    }

    /** notify_channel.config.password 藏在 JSON 里，按行解析后重新写回。 */
    private int encryptNotifyConfigPasswords() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, config::text as config from platform.notify_channel
                where config is not null and config::text <> '{}'
                """);
        int changed = 0;
        for (Map<String, Object> row : rows) {
            String raw = row.get("config") == null ? null : String.valueOf(row.get("config"));
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> config = json.readValue(raw, Map.class);
                Object password = config.get("password");
                if (password == null || String.valueOf(password).isBlank()
                        || cipher.isEncrypted(String.valueOf(password))) {
                    continue;
                }
                Map<String, Object> updated = new LinkedHashMap<>(config);
                updated.put("password", cipher.encrypt(String.valueOf(password)));
                jdbc.update("update platform.notify_channel set config = ?::json where id = ?",
                        json.writeValueAsString(updated), ((Number) row.get("id")).longValue());
                changed++;
            } catch (Exception e) {
                log.warn("通知渠道配置加密失败 id={}: {}", row.get("id"), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("敏感信息加密：notify_channel.config.password 共 {} 行", changed);
        }
        return changed;
    }

    private int encryptColumn(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, " + column + " as value from " + table
                        + " where " + column + " is not null and " + column + " <> ''");
        int changed = 0;
        for (Map<String, Object> row : rows) {
            Object raw = row.get("value");
            if (raw == null) {
                continue;
            }
            String value = String.valueOf(raw);
            if (cipher.isEncrypted(value)) {
                continue;
            }
            jdbc.update("update " + table + " set " + column + " = ? where id = ?",
                    cipher.encrypt(value), ((Number) row.get("id")).longValue());
            changed++;
        }
        if (changed > 0) {
            log.info("敏感信息加密：{}.{} 共 {} 行", table, column, changed);
        }
        return changed;
    }
}
