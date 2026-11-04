package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话入口代理：浏览器 → Java（会话/RBAC）→ agent-service（独立 Python 服务）。
 * 只代理只读对话；写操作第二阶段再做。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String serviceUrl;
    private final String token;
    private final AgentAccess access;

    public AgentChatController(ObjectMapper json,
                               @Value("${agent.service-url:http://127.0.0.1:8097}") String serviceUrl,
                               @Value("${agent.service-token:${platform.internal-token:}}") String token,
                               AgentAccess access) {
        this.json = json;
        this.serviceUrl = serviceUrl.endsWith("/")
                ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;
        this.token = token == null ? "" : token.trim();
        this.access = access;
    }

    public record ChatRequest(String message, String sessionId,
                              List<Map<String, Object>> history, Long projectId) {
    }

    /**
     * 对话入口（SSE 流式响应）。
     *
     * <p>D13：这里原来带 {@code produces = text/event-stream}，于是 {@code Accept:
     * application/json}（或 text/plain）在**进入方法之前**就被内容协商判成
     * {@code HttpMediaTypeNotAcceptableException}，而全局处理器没有这个异常的分支，
     * 落到兜底 500「服务器内部错误」——同一条非法输入按 Accept 分成 400/500 两种结果，
     * 监控还会把它当成服务端故障。</p>
     *
     * <p>成功响应的 content-type 由下面的 {@code ResponseEntity.contentType(TEXT_EVENT_STREAM)}
     * 显式给出，不依赖注解；去掉 produces 之后空 body 会真正进到方法体，
     * 统一按 400「message 不能为空」返回，错误体格式仍由全局处理器按 Accept 降级。</p>
     */
    @PostMapping("/chat")
    @RequireAtom("agent:chat")
    public ResponseEntity<StreamingResponseBody> chat(
            @RequestBody(required = false) ChatRequest req,
            HttpServletRequest request) {
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw ApiException.badRequest("message 不能为空");
        }
        String user = String.valueOf(request.getAttribute("username"));
        if (user == null || user.isBlank() || "null".equals(user)) {
            throw new ApiException(401, "未登录");
        }
        // 用拦截器校验过的租户，而不是客户端原始 header：
        // 未校验的 header 会被原样转给 agent-service，再以内部凭证发出去。
        String tenant = com.lab.dataworks.auth.TenantScope.of(request);
        // P2-28：projectId 不能原样透传给 agent-service，先确认属于当前租户；
        // 跨租户与不存在都返回 404，避免暴露项目是否存在。
        long projectId = req.projectId() == null ? 1L : req.projectId();
        access.requireProject(tenant, projectId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", req.message());
        body.put("sessionId", req.sessionId() == null ? "" : req.sessionId());
        body.put("history", req.history() == null ? List.of() : req.history());
        body.put("projectId", projectId);
        body.put("tenantId", tenant);
        body.put("userId", user);
        // 助手权限模式与可用工具：SYSTEM 全权（测试用），DELEGATE 跟随使用者
        body.put("identityMode", access.identityMode());
        body.put("allowedTools", access.allowedTools(user, tenant));

        HttpResponse<InputStream> upstream;
        try {
            HttpRequest upstreamReq = HttpRequest.newBuilder(URI.create(serviceUrl + "/api/chat"))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Agent-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            upstream = http.send(upstreamReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            // 连接被拒时 e.getMessage() 可能是 null（实测 Java HttpClient 抛的
            // ConnectException），直接拼进提示会变成「Agent 服务不可用: null」。
            throw new ApiException(503, "Agent 服务不可用: " + reason(e));
        }
        if (upstream.statusCode() >= 300) {
            // 错误分支不关闭 InputStream 会泄漏连接，agent 服务抖动时会耗尽连接池
            try (InputStream ignored = upstream.body()) {
                // 不读取内容：错误原因由状态码表达
            } catch (Exception ignored) {
                // 关闭失败不覆盖原始错误
            }
            throw new ApiException(502, "Agent 服务返回 " + upstream.statusCode());
        }
        StreamingResponseBody stream = out -> {
            try (InputStream in = upstream.body()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        };
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(serviceUrl + "/api/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return Map.of("ok", resp.statusCode() == 200,
                    "status", resp.statusCode(),
                    "service", serviceUrl,
                    "detail", resp.body());
        } catch (Exception e) {
            // Map.of 不接受 null 值：连接被拒时 getMessage() 为 null，
            // 原来这条健康检查会被 NPE 变成 500，正好在最需要它的时候失效。
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("service", serviceUrl);
            out.put("error", reason(e));
            return out;
        }
    }

    /** 异常提示兜底：message 为空时用异常类名，不能给用户看 "null"。 */
    private static String reason(Throwable e) {
        String message = e == null ? null : e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
