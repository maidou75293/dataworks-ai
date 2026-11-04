package com.lab.dataworks.mosbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 走真实网络的 mos 只读客户端（REQ-FW-2）。
 *
 * <p>认证 = 服务账号口令登录 + 会话 Cookie（契约 §6.2：不做会话联合，Cookie 7 天、
 * 401 恰好重登一次再重放；登录端点 auth.py:77，Cookie 名 modelonestation_session）。
 * 配置键与 REQ-UNIFY-1 的网关同源（platform.mos.base-url / username / password / timeout-ms），
 * 合 main 后一处配置两条线共用；本分支未部署 UNIFY-1 时键为空，{@link #enabled()} 为 false，
 * 轮询器空转不报错。</p>
 *
 * <p>两个已实证的坑：① uvicorn h2c 会吞带体响应——客户端固定 HTTP/1.1；
 * ② 登录口令不写默认值，缺口令就是明确不可用（不许静默降级成匿名请求）。</p>
 */
@Component
public class HttpMosGateway implements MosGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpMosGateway.class);
    static final String COOKIE_NAME = "modelonestation_session";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final int timeoutMs;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private volatile String cookie;

    public HttpMosGateway(@Value("${platform.mos.base-url:}") String baseUrl,
                          @Value("${platform.mos.username:svc-dataworks}") String username,
                          @Value("${platform.mos.password:}") String password,
                          @Value("${platform.mos.timeout-ms:15000}") int timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    @Override
    public boolean enabled() {
        return !baseUrl.isBlank() && !username.isBlank() && !password.isBlank();
    }

    @Override
    public String disabledReason() {
        if (baseUrl.isBlank()) {
            return "platform.mos.base-url 未配置";
        }
        if (username.isBlank() || password.isBlank()) {
            return "platform.mos.username/password 未配置（口令走 secret.sh，不给默认值）";
        }
        return "";
    }

    @Override
    public Map<String, Object> getJob(String jobId) throws Exception {
        return object(get("/api/jobs/" + jobId));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listModels() throws Exception {
        return (List<Map<String, Object>>) (List<?>) objectList(get("/api/models"));
    }

    @Override
    public Map<String, Object> getModel(String modelId) throws Exception {
        return object(get("/api/models/" + modelId));
    }

    @Override
    public String getManifestRaw(String modelId) throws Exception {
        return get("/api/models/" + modelId + "/manifest");
    }

    @Override
    public Map<String, Object> getEval(String evalId) throws Exception {
        return object(get("/api/evals/" + evalId));
    }

    // ---------- 传输 ----------

    /** GET 一个路径，返回响应体原文；401 时重登一次并重放，再 401 就如实抛。 */
    String get(String path) throws Exception {
        if (!enabled()) {
            throw new IllegalStateException("mos 网关未配置：" + disabledReason());
        }
        if (cookie == null) {
            login();
        }
        HttpResponse<String> resp = send(path, cookie);
        if (resp.statusCode() == 401) {
            login();
            resp = send(path, cookie);
        }
        if (resp.statusCode() == 404) {
            throw new java.util.NoSuchElementException("mos 侧 404：" + path);
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("mos " + path + " 返回 " + resp.statusCode());
        }
        return resp.body();
    }

    private HttpResponse<String> send(String path, String sessionCookie) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json")
                .header("Cookie", COOKIE_NAME + "=" + sessionCookie)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 口令登录并接管会话 Cookie（auth.py:112 set_cookie）。 */
    private synchronized void login() throws Exception {
        String body = json.writeValueAsString(Map.of("username", username, "password", password));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("mos 登录失败 HTTP " + resp.statusCode()
                    + "（svc 账号 " + username + "，口令是否入 secret 体系？）");
        }
        String token = extractCookie(resp);
        if (token.isBlank()) {
            throw new IllegalStateException("mos 登录 200 但响应无 Set-Cookie " + COOKIE_NAME);
        }
        this.cookie = token;
        log.info("mos 服务账号登录成功（{}）", username);
    }

    private static String extractCookie(HttpResponse<String> resp) {
        return resp.headers().allValues("set-cookie").stream()
                .filter(v -> v.startsWith(COOKIE_NAME + "="))
                .map(v -> v.substring((COOKIE_NAME + "=").length(),
                        v.indexOf(';') >= 0 ? v.indexOf(';') : v.length()))
                .findFirst().orElse("");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(String body) throws Exception {
        return json.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(String body) throws Exception {
        return json.readValue(body, List.class);
    }
}
