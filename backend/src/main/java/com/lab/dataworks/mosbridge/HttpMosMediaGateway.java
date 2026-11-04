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
import java.util.Map;

/**
 * mos 媒体上传的 HTTP 实现（REQ-FW-5）。
 *
 * <p>认证与 {@link HttpMosGateway} 同一套对方口径（契约 §6.2 / auth.py:77-115）：
 * 服务账号口令登录、Cookie {@code modelonestation_session}、401 恰好重登一次再重放、
 * 固定 HTTP/1.1（uvicorn h2c 吞体坑）。配置键同源：{@code platform.mos.*}。</p>
 *
 * <p>multipart 手工编码（java.net.http 无内建 multipart）：字段名对齐 mos
 * {@code api/media.py:659 upload_media(file, kind, folder)}。空文件名会被对方
 * {@code _safe_name} 兜底，但这里仍传内容寻址名 {@code <sha12>.<ext>}，
 * 让 mos 侧对象名可读且不与原文件名冲突（uuid8 前缀由对方加）。</p>
 */
@Component
public class HttpMosMediaGateway implements MosMediaGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpMosMediaGateway.class);
    private static final String BOUNDARY = "----mosbridgeMediaBoundary";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final int timeoutMs;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String cookie;

    public HttpMosMediaGateway(@Value("${platform.mos.base-url:}") String baseUrl,
                               @Value("${platform.mos.username:svc-dataworks}") String username,
                               @Value("${platform.mos.password:}") String password,
                               @Value("${platform.mos.timeout-ms:15000}") int timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
        this.timeoutMs = Math.max(timeoutMs, 3000);
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
    public Map<String, Object> uploadMedia(byte[] bytes, String filename, String folder) throws Exception {
        if (!enabled()) {
            throw new IllegalStateException("mos 网关未配置：" + disabledReason());
        }
        if (bytes == null || bytes.length == 0) {
            // 对方对 0 字节直接 400 并清理（media.py:693-697）；中台不许静默，先在自己这层拒。
            throw new IllegalArgumentException("媒体内容为空，不搬运");
        }
        if (cookie == null) {
            login();
        }
        HttpResponse<String> resp = sendMultipart(bytes, filename, folder, cookie);
        if (resp.statusCode() == 401) {
            login();
            resp = sendMultipart(bytes, filename, folder, cookie);
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("mos /api/media/upload 返回 " + resp.statusCode()
                    + " body=" + truncate(resp.body()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = json.readValue(resp.body(), Map.class);
        if (meta.get("path") == null || String.valueOf(meta.get("path")).isBlank()) {
            throw new IllegalStateException("mos 上传 200 但响应无 path（媒体库相对路径）");
        }
        return meta;
    }

    private HttpResponse<String> sendMultipart(byte[] bytes, String filename, String folder,
                                               String sessionCookie) throws Exception {
        byte[] body = multipartBody(bytes, filename, folder);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/media/upload"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Cookie", HttpMosGateway.COOKIE_NAME + "=" + sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static byte[] multipartBody(byte[] bytes, String filename, String folder) {
        StringBuilder head = new StringBuilder();
        part(head, "kind", "auto");
        if (folder != null && !folder.isBlank()) {
            part(head, "folder", folder);
        }
        head.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename == null ? "media" : filename).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        StringBuilder tail = new StringBuilder("\r\n--").append(BOUNDARY).append("--\r\n");
        byte[] h = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] t = tail.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + bytes.length + t.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(bytes, 0, out, h.length, bytes.length);
        System.arraycopy(t, 0, out, h.length + bytes.length, t.length);
        return out;
    }

    private static void part(StringBuilder sb, String name, String value) {
        sb.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    /** 口令登录并接管会话 Cookie（与 HttpMosGateway.login 同口径，auth.py:112 set_cookie）。 */
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
        String token = resp.headers().allValues("set-cookie").stream()
                .filter(v -> v.startsWith(HttpMosGateway.COOKIE_NAME + "="))
                .map(v -> v.substring((HttpMosGateway.COOKIE_NAME + "=").length(),
                        v.indexOf(';') >= 0 ? v.indexOf(';') : v.length()))
                .findFirst().orElse("");
        if (token.isBlank()) {
            throw new IllegalStateException("mos 登录 200 但响应无 Set-Cookie " + HttpMosGateway.COOKIE_NAME);
        }
        this.cookie = token;
        log.info("mos 媒体上传通道登录成功（{}）", username);
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 200));
    }
}
