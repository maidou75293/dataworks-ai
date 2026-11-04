package com.lab.dataworks.mosbridge;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-UNIFY-1 · /api/mos/** 代理通道契约测试（standalone MockMvc + 本地假 mos 上游）。
 *
 * <p>钉死卡面四条 + 一条安全底线：</p>
 * <ol>
 *   <li><b>无权限点=403</b>，且拒了就不碰上游、不写 MOS_ACCESS 审计
 *       （现网 ACCESS_DENIED 由 AuthInterceptor 记，见其 :229 分支）。</li>
 *   <li><b>浏览器拿不到 mos 会话</b>：上游 Set-Cookie 绝不回传（header doesNotExist）。</li>
 *   <li><b>路径映射一条规则</b>：/api/mos/&lt;x&gt; → 上游 /&lt;x&gt;（含 query 原样、
 *       POST 体与 Content-Type 原样）；{@code ..}/{@code %2e} 显式 400，不静默规范化。</li>
 *   <li><b>状态码语义</b>：上游 200/401 透传；连不上 502；口令未配 503——都带审计。</li>
 *   <li><b>每请求审计</b>：operator（JWT 用户名）+ method+path + upstream 状态，
 *       tenant_id 由 SessionService.audit→resolveTenant 口径落库（此处验入参，
 *       落库由 tenant-isolation-test 既有覆盖面兜）。</li>
 * </ol>
 */
class MosProxyControllerTest {

    private static HttpServer mos;
    private static String base;
    private static final AtomicInteger loginHits = new AtomicInteger();
    private static volatile String validToken;

    private SessionService sessions;
    private MockMvc mvc;

    @BeforeAll
    static void startStub() throws IOException {
        mos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mos.createContext("/api/auth/login", ex -> {
            validToken = "tok" + loginHits.incrementAndGet();
            ex.getResponseHeaders().add("Set-Cookie",
                    MosClient.SESSION_COOKIE + "=" + validToken + "; HttpOnly; Path=/; SameSite=lax");
            byte[] b = "{\"username\":\"svc-dataworks\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/api/auth/me", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            boolean ok = cookie != null && validToken != null
                    && cookie.contains(MosClient.SESSION_COOKIE + "=" + validToken);
            byte[] b = (ok ? "{\"username\":\"svc-dataworks\"}" : "{\"detail\":\"未登录或登录已过期\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(ok ? 200 : 401, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/docs/api", ex -> {
            byte[] b = "<!doctype html><html>mos-shell</html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/api/echo", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            String out = String.format(
                    "{\"method\":\"%s\",\"uri\":\"%s\",\"ct\":\"%s\",\"hasCookie\":%b,\"body\":%s}",
                    ex.getRequestMethod(), ex.getRequestURI(), ct == null ? "" : ct,
                    cookie != null, body.isEmpty() ? "null" : "\"" + body.replace("\"", "\\\"") + "\"");
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/api/always401", ex -> {
            byte[] b = "{\"detail\":\"未登录\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/api/media", ex -> {
            // 媒体库列表族（REQ-UNIFY-7）：前缀匹配同时服务 /api/media 与 /api/media/upload 等子路径
            byte[] b = "{\"items\":[],\"total\":0,\"counts\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/media/", ex -> {
            // 静态媒体读穿（main.py:169 挂根）：预览二进制通道
            byte[] b = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/api/abs-media", ex -> {
            // UX-MEDIAURL-7 判定表样本：上游自址（base host:port）、自址族×8090、
            // 同机他端口、外部 CDN、相对形态、非 /media 路径、百分号编码
            String json = "{\"up\":\"" + base + "/media/up.png\","
                    + "\"a\":\"http://127.0.0.1:8090/media/x.png\","
                    + "\"b\":\"http://localhost:8090/media/y.png\","
                    + "\"c\":\"http://172.17.0.1:8090/media/z.png\","
                    + "\"v6\":\"http://[::1]:8090/media/v6.png\","
                    + "\"keepPort\":\"http://127.0.0.1:9000/media/w.png\","
                    + "\"keepCdn\":\"https://cdn.example.com/media/cat.jpg\","
                    + "\"keepRel\":\"/media/rel.png\","
                    + "\"keepPath\":\"http://127.0.0.1:8090/data/other\","
                    + "\"pct\":\"http://localhost:8090/media/%E4%B8%AD%20a%23b.png\"}";
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/api/plaintext-media", ex -> {
            byte[] b = "see http://127.0.0.1:8090/media/p.png".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.createContext("/api/redirect-media", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:8090/media/t.png?x=1");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        mos.createContext("/api/setcookie", ex -> {
            ex.getResponseHeaders().add("Set-Cookie",
                    MosClient.SESSION_COOKIE + "=LEAK-MUST-NOT-REACH-BROWSER; HttpOnly; Path=/");
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mos.start();
        base = "http://127.0.0.1:" + mos.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        mos.stop(0);
    }

    @BeforeEach
    void setUp() {
        loginHits.set(0);
        validToken = null;
        sessions = mock(SessionService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new MosProxyController(new MosClient(base, "svc-dataworks", "pw", 5000), sessions))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 模拟 AuthInterceptor 已放行的请求（standalone 不跑拦截器；username/atoms 就是拦截器写入的属性）。 */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bridged(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.requestAttr("username", "alice").requestAttr("atoms", Set.of("mos:access"));
    }

    @Test
    void whoami_proxiedWithHeldSession_andAudited() throws Exception {
        org.springframework.test.web.servlet.MvcResult r =
                mvc.perform(bridged(get("/api/mos/api/auth/me"))).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, r.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                r.getResponse().getContentAsString().contains("svc-dataworks"));
        // 浏览器响应里绝不能出现 mos 会话 Cookie
        org.junit.jupiter.api.Assertions.assertNull(
                r.getResponse().getCookie("modelonestation_session"));
        verify(sessions).audit(eq("alice"), eq("MOS_ACCESS"), eq("GET /api/auth/me"),
                contains("upstream=200"), eq("SUCCESS"), any());
        org.junit.jupiter.api.Assertions.assertEquals(1, loginHits.get(), "whoami 走代持会话=恰好登录一次");

        // 第二次调用复用会话：审计再来一条，但不再登录
        mvc.perform(bridged(get("/api/mos/api/auth/me"))).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(1, loginHits.get(), "同一用户复用代持会话不重登");
        verify(sessions, org.mockito.Mockito.times(2)).audit(eq("alice"), eq("MOS_ACCESS"),
                eq("GET /api/auth/me"), contains("upstream=200"), eq("SUCCESS"), any());
    }

    @Test
    void noAtom_forbiddenAndUpstreamUntouched() throws Exception {
        mvc.perform(get("/api/mos/api/auth/me").requestAttr("username", "bob").requestAttr("atoms", Set.of("query:read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("mos:access")));
        org.junit.jupiter.api.Assertions.assertEquals(0, loginHits.get(), "被拒的请求不该打上游");
        verify(sessions, never()).audit(any(), eq("MOS_ACCESS"), any(), any(), any(), any());
    }

    @Test
    void spaPath_mapsWithoutApiPrefixInsertion() throws Exception {
        // /api/mos/docs/api → 上游 /docs/api（契约 §1 探针口径：200 text/html）
        mvc.perform(bridged(get("/api/mos/docs/api")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("mos-shell")));
    }

    @Test
    void queryAndBody_passThroughVerbatim() throws Exception {
        // query 用普通字符：MockMvc 会对 queryString 重编码（a%20b→a%2520b），
        // 那是测试框架口径而非本代理行为；真实透传语义由 live 脚本 M2/M4 覆盖。
        mvc.perform(bridged(post("/api/mos/api/echo?x=1&y=ab"))
                        .contentType("application/json")
                        .content("{\"k\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.uri").value("/api/echo?x=1&y=ab"))
                .andExpect(jsonPath("$.ct").value("application/json"))
                .andExpect(jsonPath("$.hasCookie").value(true))
                .andExpect(jsonPath("$.body").value("{\"k\":1}"));
    }

    @Test
    void traversal_rejected400() throws Exception {
        mvc.perform(bridged(get("/api/mos/a..b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不合法")));
    }

    @Test
    void upstream401_returnedAndAuditedFailed() throws Exception {
        mvc.perform(bridged(get("/api/mos/api/always401")))
                .andExpect(status().isUnauthorized());
        verify(sessions).audit(eq("alice"), eq("MOS_ACCESS"), eq("GET /api/always401"),
                contains("upstream=401"), eq("FAILED"), any());
    }

    @Test
    void setCookie_neverLeaksToBrowser() throws Exception {
        MockHttpServletResponse resp = mvc.perform(bridged(get("/api/mos/api/setcookie")))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        resp.getHeaders("Set-Cookie").forEach(h -> org.junit.jupiter.api.Assertions
                .assertFalse(h.contains("modelonestation_session"), "mos 会话泄漏给浏览器: " + h));
    }

    // ---- UX-MEDIAURL-7：桥接层媒体绝对地址收口 ----

    @Test
    void mediaUrls_selfIssuedAbsolute_rewrittenToRelative_othersUntouched() throws Exception {
        mvc.perform(bridged(get("/api/mos/api/abs-media")))
                .andExpect(status().isOk())
                // 上游自址（桥接 base host:port）与自址族×8090 → 相对 /media/**
                .andExpect(jsonPath("$.up").value("/media/up.png"))
                .andExpect(jsonPath("$.a").value("/media/x.png"))
                .andExpect(jsonPath("$.b").value("/media/y.png"))
                .andExpect(jsonPath("$.c").value("/media/z.png"))
                .andExpect(jsonPath("$.v6").value("/media/v6.png"))
                .andExpect(jsonPath("$.pct").value("/media/%E4%B8%AD%20a%23b.png"))
                // 同机他端口=别的服务；外部 CDN 透传；相对形态原样；非 /media 路径不动
                .andExpect(jsonPath("$.keepPort").value("http://127.0.0.1:9000/media/w.png"))
                .andExpect(jsonPath("$.keepCdn").value("https://cdn.example.com/media/cat.jpg"))
                .andExpect(jsonPath("$.keepRel").value("/media/rel.png"))
                .andExpect(jsonPath("$.keepPath").value("http://127.0.0.1:8090/data/other"));
    }

    @Test
    void mediaUrls_nonJsonBody_notTouched() throws Exception {
        // text/plain 不在收口面内：改坏非 JSON 响应比留旧形态更糟（类注释口径）
        mvc.perform(bridged(get("/api/mos/api/plaintext-media")))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("http://127.0.0.1:8090/media/p.png")));
    }

    @Test
    void mediaUrls_redirectLocation_selfIssuedRewritten() throws Exception {
        mvc.perform(bridged(get("/api/mos/api/redirect-media")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/media/t.png?x=1"));
    }

    @Test
    void mediaUrls_staticMediaBytes_verbatim() throws Exception {
        // 静态读穿（image/png）不走 JSON 收口：字节原样，防重写器误伤二进制
        mvc.perform(bridged(get("/api/mos/media/x.png")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
    }

    @Test
    void upstreamDown_502WithAudit() throws Exception {
        // 起一个再停掉，拿到一个确定没人监听的端口（Windows 上端口 1 未必是 refused）
        HttpServer gone = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gone.start();
        String deadBase = "http://127.0.0.1:" + gone.getAddress().getPort();
        gone.stop(0);

        MockMvc down = MockMvcBuilders.standaloneSetup(
                        new MosProxyController(new MosClient(deadBase, "svc-dataworks", "pw", 3000), sessions))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        down.perform(bridged(get("/api/mos/api/auth/me")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("无法连接")));
        verify(sessions).audit(eq("alice"), eq("MOS_ACCESS"), eq("GET /api/auth/me"),
                contains("502"), eq("FAILED"), any());
    }

    @Test
    void missingServicePassword_503() throws Exception {
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(
                        new MosProxyController(new MosClient(base, "svc-dataworks", "", 5000), sessions))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        unconfigured.perform(bridged(get("/api/mos/api/auth/me")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("platform.mos.password")));
        assertEqualsNoLogin();
    }

    // ---- REQ-UNIFY-7：媒体库权限点 mos:media ----

    @Test
    void mediaLibraryApi_requiresMosMediaAtom() throws Exception {
        // 只有 mos:access：媒体库管理面 403（且不放行到上游，不建会话）
        mvc.perform(get("/api/mos/api/media?limit=1")
                        .requestAttr("username", "alice")
                        .requestAttr("atoms", Set.of("mos:access")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("mos:media")));
        // 补上 mos:media：同一路径透传（上游 200）
        mvc.perform(get("/api/mos/api/media?limit=1")
                        .requestAttr("username", "alice")
                        .requestAttr("atoms", Set.of("mos:access", "mos:media")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void staticMediaFile_keepsMosAccessOnly() throws Exception {
        // 数据集详情的媒体预览走 /media/**：口径保持 mos:access，不随 mos:media 收紧（bridged 即无 mos:media）
        mvc.perform(bridged(get("/api/mos/media/demo/a%20b.png")))
                .andExpect(status().isOk());
    }

    private static void assertEqualsNoLogin() {
        org.junit.jupiter.api.Assertions.assertEquals(0, loginHits.get(), "未配置就不该登录上游");
    }
}
