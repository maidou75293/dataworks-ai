package com.lab.dataworks.mosbridge;

import com.lab.dataworks.auth.AtomCheck;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * REQ-UNIFY-1 · {@code /api/mos/**} 服务端代理通道。
 *
 * <p>浏览器永不直连 ModelOneStation：请求经中台鉴权（JWT + 权限点 {@code mos:access}，
 * 由 AuthInterceptor 对 /api/** 统一判定）后，由 {@link MosClient} 用<b>本用户代持的
 * mos 会话</b>转发到对方平台（契约 §6.2）。上游 Set-Cookie 不回传浏览器；
 * 每个请求按 tenant_id + 操作人落中台审计（merge-plan §2.4 步骤 3「审计归中台」）。</p>
 *
 * <p>路径映射：{@code /api/mos/<x>} → {@code <base-url>/<x>}。对方业务路由全在
 * {@code /api} 前缀下（mos/platform/backend/main.py:165），SPA 静态壳在 {@code /docs/api}
 * （现网探针 200），所以浏览器侧完整路径形如 {@code /api/mos/api/auth/me}——
 * 不做「自动补 /api」的二义性映射，映射规则只有一条，可审计、可单测钉死。</p>
 */
@RestController
@RequestMapping("/api/mos")
@RequireAtom({"mos:access"})
public class MosProxyController {

    private static final String PREFIX = "/api/mos";

    private final MosClient mos;
    private final SessionService sessions;

    /** UX-MEDIAURL-7：本通道「自签发」媒体绝对地址 host:port 集（收口重写用，构造期定死）。 */
    private final java.util.Set<String> mediaSelfHosts;

    public MosProxyController(MosClient mos, SessionService sessions) {
        this.mos = mos;
        this.sessions = sessions;
        this.mediaSelfHosts = MosMediaUrls.allowedHostPorts(
                mos.upstreamBaseUrl(), System.getenv("PLATFORM_MEDIA_SELF_HOSTS"));
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException, InterruptedException {
        // 双保险：现网 AuthInterceptor 先按 @RequireAtom 判 403（并记 ACCESS_DENIED）；
        // 这里再判一次，防止本方法将来被挂到不过拦截器的路径上，也让「无权限点=403」
        // 能在 standalone MockMvc（不走拦截器）里被单测钉住。不在此分支重复审计，
        // 否则现网同一次拒绝会落两条 ACCESS_DENIED。
        if (!AtomCheck.has(request, "mos:access")) {
            throw new ApiException(403, "无权使用 ModelOneStation 桥接通道（缺少权限点 mos:access）");
        }
        String username = (String) request.getAttribute("username");
        String operator = username == null || username.isBlank() ? "unknown" : username;

        // getRequestURI 是未解码原始路径：含 %2e（.）或 .. 一律显式 400，
        // 不做「静默规范化」——把猜路径藏在代理层里，出错时审计日志对不上真实意图（不许静默）。
        String uri = request.getRequestURI();
        String tail = uri.length() > PREFIX.length() ? uri.substring(PREFIX.length()) : "/";
        if (!tail.startsWith("/")) {
            tail = "/" + tail;
        }
        if (tail.contains("..") || tail.toLowerCase().contains("%2e") || tail.matches(".*\\s.*")) {
            throw ApiException.badRequest("桥接路径不合法（不允许 ..、%2e 与空白字符）");
        }
        // REQ-UNIFY-7：媒体库管理面（/api/media 及其子路径：列表/检索/上传/缩略图）另需 mos:media。
        // 静态读穿 /media/** 不收紧：数据集详情的媒体预览（mosMediaUrl）早已依赖它，口径仍是 mos:access。
        // 与类级 mos:access 同构的双保险：standalone 测试不过拦截器也能把「无 mos:media=403」钉住；
        // 不在此重复审计——现网拒绝由 AuthInterceptor 统一记 ACCESS_DENIED（见上方注释）。
        if (isMediaLibraryApi(tail) && !AtomCheck.has(request, "mos:media")) {
            throw new ApiException(403, "无权访问媒体库（缺少权限点 mos:media）");
        }
        String query = request.getQueryString();
        String path = query == null || query.isEmpty() ? tail : tail + "?" + query;

        byte[] body = null;
        if (request.getContentLength() > 0 || "chunked".equalsIgnoreCase(request.getHeader("Transfer-Encoding"))) {
            body = request.getInputStream().readAllBytes();
        }
        String target = request.getMethod() + " " + tail;
        String ip = AuthInterceptor.clientIp(request);

        MosClient.Upstream up;
        try {
            up = mos.exchange(operator, request.getMethod(), path, body, request.getContentType());
        } catch (IllegalStateException e) {
            // 口令未配置/配置残缺：明确 503 说缺什么，不回落成上游 401 让人误判账号问题
            sessions.audit(operator, "MOS_ACCESS", target, "503 " + e.getMessage(), "FAILED", ip);
            throw new ApiException(503, "ModelOneStation 桥接不可用：" + e.getMessage());
        } catch (IOException e) {
            sessions.audit(operator, "MOS_ACCESS", target, "502 无法连接 ModelOneStation", "FAILED", ip);
            throw new ApiException(502, "无法连接 ModelOneStation：" + e.getMessage());
        }
        String result = up.status() < 400 ? "SUCCESS" : "FAILED";
        sessions.audit(operator, "MOS_ACCESS", target, "upstream=" + up.status(), result, ip);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(up.status());
        if (up.contentType() != null) {
            try {
                builder.contentType(MediaType.parseMediaType(up.contentType()));
            } catch (InvalidMediaTypeException ignored) {
                // 上游给了个不合法的 Content-Type：不 500 挂整个响应，按原样头名透传一次即可
                builder.header(HttpHeaders.CONTENT_TYPE, up.contentType());
            }
        }
        if (up.location() != null) {
            // UX-MEDIAURL-7：3xx Location 同源收口——上游跳转自址媒体绝对地址时剥成相对
            builder.header(HttpHeaders.LOCATION,
                    MosMediaUrls.relativizeLocation(up.location(), mediaSelfHosts));
        }
        // 注意：不复制上游任何 Set-Cookie —— mos 会话只在中台服务端代持（卡目标②）。
        // UX-MEDIAURL-7：JSON 响应体统一把「本平台自签发」的绝对媒体地址
        // （127.0.0.1/localhost/::1/0.0.0.0/172.17.0.1×8090 及桥接上游 host:port）
        // 重写成相对 /media/**——存量任务里 _ls_url 的 S3 回退绝对形态不再透传进浏览器；
        // 外部 CDN/运维故意配置的对外基址原样保留（判定表见 MosMediaUrls 类注释）。
        byte[] outBody = MosMediaUrls.isJson(up.contentType()) ? MosMediaUrls.relativize(up.body(), mediaSelfHosts) : up.body();
        return builder.body(outBody);
    }

    /** 媒体库 API 族判定（mos 侧路由见 api/media.py；挂在 /api 下，main.py:165）。 */
    static boolean isMediaLibraryApi(String tail) {
        return tail.equals("/api/media") || tail.startsWith("/api/media/");
    }
}
