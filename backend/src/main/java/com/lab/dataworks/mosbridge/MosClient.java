package com.lab.dataworks.mosbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REQ-UNIFY-1 · ModelOneStation（对方平台）服务账号客户端。
 *
 * <p>契约 §6.2 的机器侧落地：服务账号口令登录拿会话 Cookie，<b>按中台用户名代持</b>
 * （某中台用户首次触发时服务端建立会话，之后复用；401 时重登一次再重放原请求）。
 * 前端永远不带 mos Cookie，浏览器也永远拿不到它。</p>
 *
 * <p>对方认证事实（取证锚点）：Cookie {@code modelonestation_session}，HttpOnly、7 天
 * （mos/platform/backend/auth.py:33-34）；登录 {@code POST /api/auth/login} JSON
 * {username,password}（api/auth.py:77）；whoami {@code GET /api/auth/me} 未登录 401
 * （api/auth.py:124）；全路由挂 /api 前缀（main.py:165）。</p>
 *
 * <p>口令不硬编码：{@code platform.mos.password} 未配置时 {@link #configured()} 为 false，
 * 调用直接抛 {@link IllegalStateException}，由 {@link MosProxyController} 映射成 503 明确文案
 * （凭据边界：Worker/代码库里不存生产口令，口令走 scripts/ops/secret.sh 体系，契约 §6.2-2）。</p>
 */
@Component
public class MosClient {

    /** 对方会话 Cookie 名（mos/platform/backend/auth.py:33）。 */
    public static final String SESSION_COOKIE = "modelonestation_session";

    private static final Logger log = LoggerFactory.getLogger(MosClient.class);

    /**
     * 上游响应（整体缓冲：对方是 API JSON / 小页面，不是大文件下载通道；
     * 媒体直连走 MinIO，见 merge-plan §2.5）。
     *
     * <p>刻意<b>不</b>携带上游 Set-Cookie——会话只留在这台服务端（代持），
     * 透传给浏览器等于把 mos 会话交到前端，违背「浏览器永不直连 mos」。</p>
     */
    public record Upstream(int status, String contentType, String location, byte[] body) {
    }

    private final String baseUrl;
    private final String serviceUser;
    private final String servicePassword;
    private final long timeoutMs;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 会话代持缓存：中台用户名 → mos 会话 token。首次触发建立，401 重登替换。 */
    private final ConcurrentHashMap<String, String> heldSessions = new ConcurrentHashMap<>();

    /** 登录次数（首次代持 + 401 重登），供单测钉住「复用会话不重复登录」。 */
    private final AtomicLong loginCalls = new AtomicLong();

    public MosClient(@Value("${platform.mos.base-url:http://127.0.0.1:30095}") String baseUrl,
                     @Value("${platform.mos.username:svc-dataworks}") String serviceUser,
                     @Value("${platform.mos.password:}") String servicePassword,
                     @Value("${platform.mos.timeout-ms:15000}") long timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceUser = serviceUser == null ? "" : serviceUser.trim();
        this.servicePassword = servicePassword == null ? "" : servicePassword;
        this.timeoutMs = timeoutMs <= 0 ? 15000 : timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                // 钉死 HTTP/1.1（2026-10-28 真实 mos 取证踩坑）：java.net.http 默认走
                // HTTP_2，会发 h2c 升级前言；uvicorn/h11 协商回落 1.1 时 POST body 被吞，
                // FastAPI 报 422 missing body——对方平台是 uvicorn，必须显式 1.1。
                .version(HttpClient.Version.HTTP_1_1)
                // 不自动跟随重定向：3xx 与 Location 原样交给代理层决定（不隐式扩大访问面）
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 服务账号口令是否已配置（未配置时通道 503，而不是拿空口令硬试）。 */
    public boolean configured() {
        return !servicePassword.isBlank() && !serviceUser.isBlank();
    }

    /** UX-MEDIAURL-7：上游基址暴露给桥接收口重写器——对方自址 host:port 进「自签发」集合。 */
    public String upstreamBaseUrl() {
        return baseUrl;
    }

    public long loginCalls() {
        return loginCalls.get();
    }

    /**
     * 以中台用户 {@code platformUser} 名下代持的 mos 会话执行一次调用。
     *
     * <p>401 恰好重登一次并重放原请求（契约 §6.2-3）；重登后仍 401 就原样返回 401，
     * 由调用方审计——不循环、不吞。</p>
     */
    public Upstream exchange(String platformUser, String method, String path,
                             byte[] body, String contentType) throws IOException, InterruptedException {
        if (!configured()) {
            throw new IllegalStateException("ModelOneStation 服务账号未配置（platform.mos.password 为空）");
        }
        String token = heldSessions.get(platformUser);
        if (token == null) {
            token = login(platformUser);
        }
        Upstream first = send(method, path, body, contentType, token);
        if (first.status() != 401) {
            return first;
        }
        log.info("mos 会话失效（中台用户 {}），重登一次", platformUser);
        token = login(platformUser);
        return send(method, path, body, contentType, token);
    }

    /** 服务账号登录；成功返回会话 token 并替换该用户名下的代持会话。 */
    private String login(String platformUser) throws IOException, InterruptedException {
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of("username", serviceUser, "password", servicePassword));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("服务账号登录体构造失败", e);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        loginCalls.incrementAndGet();
        if (resp.statusCode() != 200) {
            heldSessions.remove(platformUser);
            throw new IOException("ModelOneStation 服务账号 " + serviceUser + " 登录失败：HTTP " + resp.statusCode());
        }
        String token = extractSessionCookie(resp.headers().allValues("Set-Cookie"));
        if (token == null || token.isBlank()) {
            throw new IOException("ModelOneStation 登录响应没有 " + SESSION_COOKIE + " Cookie");
        }
        heldSessions.put(platformUser, token);
        return token;
    }

    /** 从 Set-Cookie 头里取会话 token（对方只发一个会话 Cookie，取同名项首段）。 */
    static String extractSessionCookie(List<String> setCookieHeaders) {
        for (String header : setCookieHeaders) {
            String pair = header.split(";", 2)[0].trim();
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(SESSION_COOKIE)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private Upstream send(String method, String path, byte[] body, String contentType, String token)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = (body == null || body.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Cookie", SESSION_COOKIE + "=" + token);
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        HttpResponse<byte[]> resp = http.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        String ct = resp.headers().firstValue("Content-Type").orElse(null);
        String location = resp.headers().firstValue("Location").orElse(null);
        return new Upstream(resp.statusCode(), ct, location, resp.body());
    }
}
