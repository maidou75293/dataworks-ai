package com.lab.dataworks.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统配置页「对话智能体」区块（REQ-AGENT-CFGPANEL-1，）。
 *
 * <p>同源=读写 agent-service 进程里的生效值，Java 侧不另存一份拷贝：端点/密钥/
 * 模型名只存在于 agent-service 的环境与 .env（②BUG-AGENT-KEY-FIX-1 实锤过配置
 * 分裂的代价——页面改 DB、真路由在 .env，key 空了对话必挂而页面毫无察觉）。
 * GET 透传 agent-service 的脱敏快照：密钥只出「已配置 + 尾 4 位指纹」，明文连
 * Java 都看不到；PUT 把用户填的值原样转发给 agent-service 写 .env 并即时生效
 * （免重启、免登节点=「改配置不出页面」）。审计行只记字段名与上游指纹。</p>
 *
 * <p>权限：{@code agent:config} + 平台级。与 v449 P0-2 同一口径：模型路由是全局
 * 单例，内置 ADMIN 在每个租户各有一份，不放平台闸就等于任何租户 ADMIN 都能把
 * 全平台的模型端点改到自己指定的服务上。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentModelConfigController {

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String serviceUrl;
    private final String token;
    private final SessionService sessions;
    private final TenantGuard guard;

    public AgentModelConfigController(ObjectMapper json,
                                      @Value("${agent.service-url:http://127.0.0.1:8097}") String serviceUrl,
                                      @Value("${agent.service-token:${platform.internal-token:}}") String token,
                                      SessionService sessions, TenantGuard guard) {
        this.json = json;
        this.serviceUrl = serviceUrl.endsWith("/")
                ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;
        this.token = token == null ? "" : token.trim();
        this.sessions = sessions;
        this.guard = guard;
    }

    /** null/省略=不改；apiKey 传 ""=显式清除——所以判「有没有内容」必须用 null 而不是 isBlank。 */
    public record ModelConfigInput(String baseUrl, String modelName, String apiKey) {
    }

    @GetMapping("/model-config")
    @RequireAtom("agent:config")
    public Map<String, Object> read() {
        guard.requirePlatform();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(serviceUrl + "/api/config"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("X-Agent-Token", token)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return json.readValue(resp.body(), new TypeReference<LinkedHashMap<String, Object>>() {
                });
            }
            // 上游非 200 也要把灯画红而不是抛 502：这个区块的存在意义就是让故障可见
            return redLamp("agent-service 配置端点返回 " + resp.statusCode());
        } catch (Exception e) {
            return redLamp("Agent 服务不可用: " + reason(e));
        }
    }

    @PutMapping("/model-config")
    @RequireAtom("agent:config")
    public Map<String, Object> update(@RequestBody(required = false) ModelConfigInput input,
                                      HttpServletRequest request) {
        if (input == null || (input.baseUrl() == null && input.modelName() == null
                && input.apiKey() == null)) {
            throw ApiException.badRequest("没有要修改的内容：baseUrl/modelName/apiKey 全为空");
        }
        // 页面通道只许「换钥」不许「清钥」：空串透传到底层是显式清除语义，
        // 手滑一下全平台对话必挂——清除密钥只走节点 .env/运维通道（agentconfig 层保留该语义供测试）
        if (input.apiKey() != null && input.apiKey().isBlank()) {
            throw ApiException.badRequest("apiKey 不能为空串：页面只支持替换密钥，清除请走运维通道");
        }
        guard.requirePlatform();
        String actor = String.valueOf(request.getAttribute("username"));
        Map<String, Object> upstreamBody = new LinkedHashMap<>();
        if (input.baseUrl() != null) {
            upstreamBody.put("baseUrl", input.baseUrl());
        }
        if (input.modelName() != null) {
            upstreamBody.put("modelName", input.modelName());
        }
        if (input.apiKey() != null) {
            upstreamBody.put("apiKey", input.apiKey());
        }
        // 审计/报错只提字段名：apiKey 的明文只许在请求体里过路，不落日志、不落审计
        String fieldNames = "fields=" + upstreamBody.keySet();
        String ip = AuthInterceptor.clientIp(request);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(serviceUrl + "/api/config"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Agent-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(upstreamBody), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = null;
            try {
                parsed = json.readValue(resp.body(), new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (Exception ignored) {
                // 上游不是 JSON（如网关 HTML 错误页）：走下面的状态码分支兜底
            }
            if (resp.statusCode() >= 300) {
                String msg = parsed != null && parsed.get("detail") != null
                        ? String.valueOf(parsed.get("detail"))
                        : "上游返回 " + resp.statusCode();
                sessions.audit(actor, "AGENT_MODEL_CONFIG", "platform",
                        fieldNames + " 失败：" + msg, "FAIL", ip);
                throw new ApiException(resp.statusCode() == 400 ? 400 : 502,
                        "模型配置保存失败：" + msg);
            }
            if (parsed == null) {
                parsed = new LinkedHashMap<>();
            }
            sessions.audit(actor, "AGENT_MODEL_CONFIG", "platform",
                    fieldNames + "; changed=" + parsed.getOrDefault("changed", "")
                            + "; keyFingerprint=" + parsed.getOrDefault("keyFingerprint", ""),
                    "SUCCESS", ip);
            return parsed;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            sessions.audit(actor, "AGENT_MODEL_CONFIG", "platform",
                    fieldNames + " 失败：Agent 服务不可用", "FAIL", ip);
            throw new ApiException(503, "Agent 服务不可用: " + reason(e));
        }
    }

    private Map<String, Object> redLamp(String why) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("healthy", false);
        out.put("healthReason", why);
        out.put("service", serviceUrl);
        return out;
    }

    /** 异常提示兜底：message 为空时用异常类名，不给页面看 "null"（同 AgentChatController）。 */
    private static String reason(Throwable e) {
        String message = e == null ? null : e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
