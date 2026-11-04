package com.lab.dataworks.flywheel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.mosbridge.MosClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MosChainPort} 的生产实现：全部出站走 REQ-UNIFY-1 的 {@link MosClient}
 * 代持会话（HTTP/1.1 钉死、401 恰好重登一次的语义由它承担，本类不复制桥接客户端）。
 *
 * <p>不许静默（acceptance-rules #4）：非 2xx → 502 带上对方原文摘要；
 * 响应缺关键字段（id/version）→ 502 明说形态变化，不拿 null 假装成功。</p>
 */
@Component
public class MosClientChainPort implements MosChainPort {

    private final MosClient client;
    private final ObjectMapper json = new ObjectMapper();

    public MosClientChainPort(MosClient client) {
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return client.configured();
    }

    @Override
    public String disabledReason() {
        return "ModelOneStation 服务账号未配置（platform.mos.password 为空）";
    }

    @Override
    public String createDataset(String operator, String name) {
        JsonNode node = call(operator, "POST", "/api/datasets", Map.of("name", name));
        return requireText(node, "id", "建数据集");
    }

    @Override
    public String freezeDataset(String operator, String dsId) {
        JsonNode node = call(operator, "POST", "/api/datasets/" + dsId + "/freeze", Map.of());
        String ver = node.path("version").asText("");
        if (ver.isBlank()) {
            throw new ApiException(502, "对方 freeze 响应缺少 version 字段：" + brief(node));
        }
        return ver;
    }

    @Override
    public String submitTrainingJob(String operator, String datasetId, String method,
                                    Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method == null || method.isBlank() ? "sft" : method);
        body.put("dataset_id", datasetId);
        body.put("params", params == null ? Map.of() : params);
        body.put("force", true);
        JsonNode node = call(operator, "POST", "/api/jobs", body);
        return requireText(node, "id", "发起训练");
    }

    @Override
    public String submitEval(String operator, String name, String datasetId,
                             List<Map<String, String>> candidates, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("dataset_id", datasetId);
        body.put("candidates", candidates);
        body.put("limit", Math.max(1, Math.min(limit, 200)));
        JsonNode node = call(operator, "POST", "/api/evals", body);
        return requireText(node, "id", "建评测");
    }

    @Override
    public void runEval(String operator, String evalId) {
        call(operator, "POST", "/api/evals/" + evalId + "/run", null);
    }

    @Override
    public String registerDeployment(String operator, String modelExtId, String notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_id", modelExtId);
        if (notes != null && !notes.isBlank()) {
            body.put("notes", notes.length() > 200 ? notes.substring(0, 200) : notes);
        }
        JsonNode node = call(operator, "POST", "/api/deployments", body);
        return requireText(node, "id", "登记部署");
    }

    @Override
    public void startDeployment(String operator, String depId) {
        call(operator, "POST", "/api/deployments/" + depId + "/start", Map.of());
    }

    @Override
    public void activateDeployment(String operator, String depId) {
        call(operator, "POST", "/api/deployments/" + depId + "/activate", Map.of());
    }

    // ---------- 调用与小工具 ----------

    private JsonNode call(String operator, String method, String path, Map<String, Object> body) {
        if (!client.configured()) {
            throw new ApiException(503, disabledReason());
        }
        byte[] payload = null;
        try {
            payload = body == null ? new byte[0] : json.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new ApiException(500, "请求体序列化失败：" + e.getMessage());
        }
        try {
            MosClient.Upstream up = client.exchange(operator, method, path,
                    payload.length == 0 ? null : payload, "application/json");
            String text = up.body() == null ? "" : new String(up.body(), StandardCharsets.UTF_8);
            if (up.status() < 200 || up.status() >= 300) {
                throw new ApiException(up.status() == 401 || up.status() == 403 ? up.status() : 502,
                        "ModelOneStation " + method + " " + path + " 返回 HTTP " + up.status()
                                + "：" + brief(text));
            }
            if (text.isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(text);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(502, "无法连接 ModelOneStation：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "调用 ModelOneStation 被中断");
        }
    }

    private static String requireText(JsonNode node, String field, String action) {
        String v = node.path(field).asText("");
        if (v.isBlank()) {
            throw new ApiException(502, "对方「" + action + "」响应缺少 " + field + " 字段：" + brief(node));
        }
        return v;
    }

    private static String brief(Object nodeOrText) {
        String s = String.valueOf(nodeOrText);
        return s.length() <= 200 ? s : s.substring(0, 200) + "……";
    }
}
