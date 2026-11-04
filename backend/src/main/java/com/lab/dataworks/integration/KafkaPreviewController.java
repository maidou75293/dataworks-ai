package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** DEV 环境的 Kafka 数据预览：挂在一个 Kafka 数据源下面。 */
@RestController
@RequestMapping("/api/datasources/{id}/kafka")
public class KafkaPreviewController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KafkaPreviewService kafka;

    public KafkaPreviewController(JdbcTemplate jdbc, ObjectMapper json, KafkaPreviewService kafka) {
        this.jdbc = jdbc;
        this.json = json;
        this.kafka = kafka;
    }

    @GetMapping("/topics")
    @RequireAtom("query:run")
    public List<String> topics(@PathVariable long id) {
        return kafka.topics(bootstrapOf(id));
    }

    @GetMapping("/preview")
    @RequireAtom("query:run")
    public Map<String, Object> preview(@PathVariable long id,
                                       @RequestParam String topic,
                                       @RequestParam(defaultValue = "20") int limit,
                                       @RequestParam(defaultValue = "5000") int timeoutMs,
                                       @RequestParam(required = false) String groupId,
                                       @RequestParam(defaultValue = "earliest") String offset) {
        if (topic == null || topic.isBlank()) {
            throw ApiException.badRequest("topic 不能为空");
        }
        // Kafka topic 只允许字母数字 . _ -；带 / 或 .. 的输入直接拒绝，避免把非法值传给客户端
        if (!topic.matches("[A-Za-z0-9._-]{1,249}")) {
            throw ApiException.badRequest("topic 名称不合法: " + topic);
        }
        if (limit < 1) {
            throw ApiException.badRequest("limit 必须大于 0");
        }
        int rows = Math.max(1, Math.min(200, limit));
        int timeout = Math.max(1000, Math.min(30_000, timeoutMs));
        String group = groupId == null || groupId.isBlank() ? null : groupId.trim();
        if (group != null && group.length() > 249) {
            throw ApiException.badRequest("消费组名称最长 249 个字符");
        }
        String bootstrap = bootstrapOf(id);
        if (!kafka.topicExists(bootstrap, topic)) {
            throw ApiException.notFound("topic 不存在: " + topic);
        }
        return kafka.previewResult(topic, group, offset,
                kafka.preview(bootstrap, topic, rows, timeout, group, offset));
    }

    /** 取数据源的 bootstrap 地址：必须属于当前租户，类型必须是 KAFKA。 */
    private String bootstrapOf(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.ds_type, d.config_json::text as config
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where d.id = ? and p.tenant_id = ?
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        if (!"KAFKA".equalsIgnoreCase(String.valueOf(row.get("ds_type")))) {
            throw ApiException.badRequest("该数据源不是 Kafka 类型");
        }
        try {
            Map<?, ?> config = json.readValue(String.valueOf(row.get("config")), Map.class);
            Object value = config.get("bootstrap.servers");
            if (value == null) {
                value = config.get("bootstrapServers");
            }
            if (value == null || String.valueOf(value).isBlank()) {
                throw ApiException.badRequest("数据源缺少 bootstrap.servers");
            }
            // v448 P1-2：预览也会真实连 broker，同样走出网守卫
            com.lab.dataworks.common.JdbcUrlGuard.validateBootstrapServers(String.valueOf(value));
            return String.valueOf(value);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("数据源配置不是合法 JSON");
        }
    }
}
