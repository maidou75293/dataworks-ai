package com.lab.dataworks.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink 计划血缘：{@code GET /jobs/{id}/plan} 提表级 source → sink。
 *
 * <p>Flink 的 plan JSON 没有结构化的表名字段，节点 description 里通常是
 * {@code Source: Kafka[orders]} / {@code TableSourceScan(table=[[doris, db, t]])}。
 * 这里两种写法都识别；识别不到就返回空集合，调用方保持 inferred。</p>
 */
@Component
public class FlinkPlanLineage {

    private static final Logger log = LoggerFactory.getLogger(FlinkPlanLineage.class);
    private static final Pattern TABLE_ARRAY = Pattern.compile(
            "(?i)table=\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern SOURCE = Pattern.compile("(?i)\\bSource:\\s*([^\\s,\\]]+)");
    private static final Pattern SINK = Pattern.compile("(?i)\\bSink:\\s*([^\\s,\\]]+)");

    private final RestTemplate http;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlinkPlanLineage(RestTemplateBuilder builder,
                            @Value("${flink.rest-url:http://flink-jobmanager.dw-dev.svc.cluster.local:8081}")
                            String baseUrl) {
        this.http = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    /** 表级边；拿不到计划时返回空列表（不抛异常，血缘上报不能被打挂）。 */
    public List<SqlLineageParser.Edge> edges(String jobId) {
        if (jobId == null || jobId.isBlank() || baseUrl.isEmpty()) {
            return List.of();
        }
        JsonNode plan;
        try {
            plan = http.getForObject(baseUrl + "/jobs/" + jobId + "/plan", JsonNode.class);
        } catch (RuntimeException e) {
            log.warn("flink plan lineage failed job={}: {}", jobId, e.getMessage());
            return List.of();
        }
        Set<String> sources = new LinkedHashSet<>();
        Set<String> sinks = new LinkedHashSet<>();
        for (JsonNode node : plan == null ? List.<JsonNode>of() : iterable(plan.path("nodes"))) {
            String description = node.path("description").asText("");
            Matcher array = TABLE_ARRAY.matcher(description);
            boolean sinkNode = description.toLowerCase(Locale.ROOT).contains("sink");
            while (array.find()) {
                String table = joinTable(array.group(1));
                if (table.isEmpty()) {
                    continue;
                }
                (sinkNode ? sinks : sources).add(table);
            }
            Matcher s = SOURCE.matcher(description);
            while (s.find()) {
                sources.add(clean(s.group(1)));
            }
            Matcher k = SINK.matcher(description);
            while (k.find()) {
                sinks.add(clean(k.group(1)));
            }
        }
        List<SqlLineageParser.Edge> out = new ArrayList<>();
        for (String source : sources) {
            for (String sink : sinks) {
                if (!source.isEmpty() && !sink.isEmpty()) {
                    out.add(SqlLineageParser.Edge.table(source, sink, "measured"));
                }
            }
        }
        return out;
    }

    private static Iterable<JsonNode> iterable(JsonNode array) {
        return array != null && array.isArray() ? array : List.of();
    }

    /** table=[[catalog, db, table]] → catalog.db.table；去掉字段名等尾部。 */
    private static String joinTable(String raw) {
        String[] parts = raw.split(",");
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String value = clean(part);
            if (value.isEmpty() || value.contains("=")) {
                break;
            }
            names.add(value);
        }
        return String.join(".", names);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "")
                .replace("[", "").replace("]", "").trim();
    }
}
