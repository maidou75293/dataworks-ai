package com.lab.dataworks.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus 查询客户端：只读即时查询（/api/v1/query）。
 *
 * <p>系统监控页在 Prometheus 不可用时必须仍能打开，所以所有异常都降级为
 * 空结果，由调用方决定展示“暂无数据”，而不是让整个接口 500。</p>
 */
@Component
public class PrometheusClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

    private final RestClient http;
    private final ObjectMapper json;
    private final String baseUrl;

    public PrometheusClient(@Value("${platform.prometheus-url:http://prometheus:9090}") String baseUrl,
                            ObjectMapper json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "http://prometheus:9090" : baseUrl.trim())
                .replaceAll("/+$", "");
        this.http = RestClient.builder().requestFactory(factory).build();
        this.json = json;
    }

    /** 一条即时查询结果。 */
    public record Sample(Map<String, String> labels, double value) {
    }

    public List<Sample> query(String promql) {
        try {
            // 手动编码：PromQL 里的 {} 会被 Spring 当成 URI 模板变量
            String url = baseUrl + "/api/v1/query?query="
                    + UriUtils.encodeQueryParam(promql, StandardCharsets.UTF_8);
            String body = http.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .body(String.class);
            JsonNode root = json.readTree(body == null ? "{}" : body);
            if (!"success".equals(root.path("status").asText())) {
                log.warn("prometheus query failed: {}", root.path("error").asText());
                return List.of();
            }
            List<Sample> out = new ArrayList<>();
            for (JsonNode item : root.path("data").path("result")) {
                Map<String, String> labels = new LinkedHashMap<>();
                item.path("metric").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                JsonNode value = item.path("value");
                if (value.isArray() && value.size() >= 2) {
                    out.add(new Sample(labels, parse(value.get(1).asText())));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("prometheus unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /** 标量查询：取第一条结果，取不到时返回默认值。 */
    public double scalar(String promql, double fallback) {
        List<Sample> samples = query(promql);
        return samples.isEmpty() ? fallback : samples.get(0).value();
    }

    /** 按某个标签把查询结果做成一维映射（重复标签取最大值）。 */
    public Map<String, Double> byLabel(String promql, String label) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Sample sample : query(promql)) {
            String key = sample.labels().get(label);
            if (key == null || key.isBlank()) {
                continue;
            }
            out.merge(key, sample.value(), Math::max);
        }
        return out;
    }

    private static double parse(String text) {
        try {
            double v = Double.parseDouble(text);
            return Double.isFinite(v) ? v : 0d;
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
