package com.lab.dataworks.prefect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PrefectBridgeClient {

    private static final Logger log = LoggerFactory.getLogger(PrefectBridgeClient.class);

    private final RestTemplate http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String internalToken;

    public record Deployment(String flowId, String deploymentId, String flowName, String deploymentName) {
    }

    public record Run(String flowRunId, String name, String state, String stateMessage,
                      Map<String, Object> parameters, String startTime, String endTime) {
    }

    public record TaskRun(String id, String name, String taskKey, String state, String stateMessage,
                          String startTime, String endTime) {
    }

    public record LogEntry(String timestamp, int level, String message, String taskRunId) {
    }

    public PrefectBridgeClient(RestTemplateBuilder builder, ObjectMapper mapper,
                               @Value("${prefect.bridge-url}") String baseUrl,
                               @Value("${platform.internal-token:}") String internalToken) {
        this.http = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public Deployment createDeployment(String flowName, String deploymentName, String entrypoint,
                                       String cron, String rrule, List<String> tags, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flow_name", flowName);
        body.put("deployment_name", deploymentName);
        body.put("entrypoint", entrypoint);
        body.put("work_pool", "platform");
        body.put("cron", cron);
        body.put("rrule", rrule);
        body.put("tags", tags == null ? List.of() : tags);
        body.put("description", description);
        JsonNode json = post("/deployments", body);
        return new Deployment(
                json.path("flow_id").asText(),
                json.path("deployment_id").asText(),
                json.path("flow_name").asText(),
                json.path("deployment_name").asText());
    }

    public void deleteDeployment(String deploymentId) {
        if (deploymentId == null || deploymentId.isBlank()) {
            return;
        }
        try {
            exchange(HttpMethod.DELETE, "/deployments/" + deploymentId, null);
        } catch (ApiException e) {
            if (e.getStatus() != 404) {
                throw e;
            }
        }
    }

    public Run createRun(String deploymentId, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parameters", params == null ? Map.of() : params);
        JsonNode json = post("/deployments/" + deploymentId + "/runs", body);
        return toRun(json, params);
    }

    /** 取消 Flow Run（v450 P1-8）：bridge 用 Prefect 的 Cancelling 状态收敛。 */
    public Run cancelRun(String runId) {
        return toRun(post("/runs/" + runId + "/cancel", Map.of()), null);
    }

    public Run getRun(String runId) {
        return toRun(get("/runs/" + runId), null);
    }

    /**
     * 探测 Flow 文件是否已挂到执行器（bridge 与 worker 同节点、挂同一个 ConfigMap 卷）。
     * 老版本 bridge 没有 /flows 接口或临时不可达时返回 null，调用方退回按时间等待。
     */
    public Boolean flowReady(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        try {
            JsonNode json = get("/flows/" + filename);
            return json.has("ready") ? json.path("ready").asBoolean(false) : null;
        } catch (ApiException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * bridge 与它背后的 Prefect API 是否可用。用于「探测不到 Flow 文件」时区分原因：
     * 老版本 bridge 没有 /flows（应该按时间兜底等待），还是 bridge/Prefect 根本连不上
     * （此时再等多久都白等，触发注定失败，应当立即报错）。
     * bridge 的 /health 会做一次轻量 Prefect 读调用。
     */
    public boolean reachable() {
        try {
            get("/health");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Run> listRuns(String deploymentId, int limit) {
        JsonNode json = get("/deployments/" + deploymentId + "/runs?limit=" + Math.max(1, limit));
        List<Run> out = new ArrayList<>();
        for (JsonNode node : json.path("runs")) {
            out.add(toRun(node, null));
        }
        return out;
    }

    public List<TaskRun> listTasks(String runId) {
        JsonNode json = get("/runs/" + runId + "/tasks");
        List<TaskRun> out = new ArrayList<>();
        for (JsonNode node : json.path("tasks")) {
            out.add(new TaskRun(
                    node.path("id").asText(),
                    node.path("name").asText(),
                    node.path("task_key").asText(),
                    node.path("state").asText(),
                    text(node, "state_message"),
                    text(node, "start_time"),
                    text(node, "end_time")));
        }
        return out;
    }

    public List<LogEntry> listLogs(String runId, String taskRunId) {
        String path = "/runs/" + runId + "/logs?limit=1000";
        if (taskRunId != null && !taskRunId.isBlank()) {
            path += "&task_run_id=" + taskRunId;
        }
        JsonNode json = get(path);
        List<LogEntry> out = new ArrayList<>();
        for (JsonNode node : json.path("logs")) {
            out.add(new LogEntry(
                    text(node, "timestamp"),
                    node.path("level").asInt(20),
                    node.path("message").asText(""),
                    text(node, "task_run_id")));
        }
        return out;
    }

    public JsonNode health() {
        return get("/health");
    }

    private Run toRun(JsonNode node, Map<String, Object> fallbackParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode p = node.path("parameters");
        if (p.isObject()) {
            p.fields().forEachRemaining(e -> params.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
        }
        if (params.isEmpty() && fallbackParams != null) {
            params.putAll(fallbackParams);
        }
        return new Run(
                node.path("flow_run_id").asText(),
                node.path("name").asText(),
                node.path("state").asText(),
                text(node, "state_message"),
                params,
                text(node, "start_time"),
                text(node, "end_time"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private JsonNode get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    private JsonNode post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    private JsonNode exchange(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!internalToken.isBlank()) {
            headers.set("X-Internal-Token", internalToken);
        }
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<JsonNode> response = http.exchange(baseUrl + path, method, entity, JsonNode.class);
            return response.getBody() == null ? mapper.createObjectNode() : response.getBody();
        } catch (HttpClientErrorException e) {
            String msg = e.getResponseBodyAsString();
            log.warn("prefect bridge {} {} -> {} {}", method, path, e.getStatusCode().value(), msg);
            throw new ApiException(e.getStatusCode().value(), "Prefect Bridge 错误: " + msg);
        } catch (Exception e) {
            log.error("prefect bridge call failed {} {}", method, path, e);
            throw new ApiException(502, "Prefect Bridge 连接失败: " + e.getMessage());
        }
    }
}
