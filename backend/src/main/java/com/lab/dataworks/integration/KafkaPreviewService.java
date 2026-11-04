package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * DEV 环境 Kafka 数据预览：列 topic、取最新/最早若干条消息。
 *
 * <p>用临时消费组 + earliest 读一次就关，不提交位点，不改动业务消费组；
 * 预览只用于确认「源端有数据、字段长什么样」，不参与同步。</p>
 */
@Service
public class KafkaPreviewService {

    public record Message(int partition, long offset, long timestamp, String value) {
    }

    public List<String> topics(String bootstrap) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("request.timeout.ms", "5000");
        props.put("default.api.timeout.ms", "6000");
        try (AdminClient admin = AdminClient.create(props)) {
            List<String> names = new ArrayList<>(admin.listTopics().names().get(6, TimeUnit.SECONDS));
            names.removeIf(name -> name.startsWith("__"));
            names.sort(String::compareTo);
            return names;
        } catch (Exception e) {
            throw new ApiException(502, "Kafka 不可用: " + e.getMessage());
        }
    }

    /**
     * 读若干条消息。
     *
     * <p>不传消费组时用一次性临时组（{@code dw-preview-<uuid>}），读完即关、不提交位点；
     * 传了就按调用方的组读——Kafka 各环境独立，允许开发自己指定组确认位点行为。
     * 无论哪种都不提交位点，预览不会顶掉采集任务的进度。</p>
     */
    /** topic 不存在时给 404，别把「拼错 topic」当成「这个 topic 没消息」。 */
    public boolean topicExists(String bootstrap, String topic) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("request.timeout.ms", "5000");
        props.put("default.api.timeout.ms", "6000");
        try (AdminClient admin = AdminClient.create(props)) {
            // 不能用 describeTopics：broker 开着 auto.create.topics.enable 时，
            // 查一个不存在的 topic 会把它建出来（实测会发生）。
            return admin.listTopics().names().get(6, TimeUnit.SECONDS).contains(topic);
        } catch (Exception e) {
            // 网络/无权限等错误交给后续 preview 报出真实原因
            return true;
        }
    }

    public List<Message> preview(String bootstrap, String topic, int limit, int timeoutMs,
                                 String groupId, String offset) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId(groupId));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "latest".equalsIgnoreCase(offset) ? "latest" : "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "dw-preview");
        List<Message> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (out.size() < limit && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> record : records) {
                    out.add(new Message(record.partition(), record.offset(),
                            record.timestamp(), truncate(record.value())));
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new ApiException(502, "读取 Kafka 消息失败: " + e.getMessage());
        }
    }

    public Map<String, Object> previewResult(String topic, String groupId, String offset,
                                             List<Message> messages) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic", topic);
        out.put("groupId", groupId(groupId));
        out.put("ephemeralGroup", isBlank(groupId));
        out.put("offset", "latest".equalsIgnoreCase(offset) ? "latest" : "earliest");
        out.put("count", messages.size());
        out.put("messages", messages);
        return out;
    }

    private String groupId(String groupId) {
        return isBlank(groupId) ? "dw-preview-" + UUID.randomUUID() : groupId.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 4000 ? value.substring(0, 4000) + "…" : value;
    }
}
