package com.lab.dataworks.mosbridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-UNIFY-1 · 服务账号客户端（会话代持 + 401 重登）单测。
 *
 * <p>用本地 HttpServer 冒充 ModelOneStation（对方锚点：登录 POST /api/auth/login 发
 * modelonestation_session Cookie，auth.py:33-34；未登录 GET /api/auth/me 401，
 * api/auth.py:124），钉死契约 §6.2 的四件事：</p>
 * <ol>
 *   <li><b>懒代持 + 复用</b>：中台用户首次触发才登录，之后不重复登录；不同中台用户各一份会话。</li>
 *   <li><b>401 恰好重登一次并重放</b>：会话被上游换掉 → 下一次调用先 401、重登、原请求重放成功；
 *       永远 401 的端点不会引发登录风暴（恰好 2 次登录后把 401 原样交回）。</li>
 *   <li><b>登录失败显式报错</b>（不许静默）：上游登录 401 → IOException 带状态码，不吞成空会话。</li>
 *   <li><b>无口令不硬试</b>：platform.mos.password 未配置 → configured=false，调用抛
 *       IllegalStateException（控制器映射 503）。</li>
 * </ol>
 */
class MosClientTest {

    private static HttpServer mos;
    private static String base;

    private static final AtomicInteger loginHits = new AtomicInteger();
    /** 上游当前认可的唯一会话 token（模拟 sessions 表只认最新签发值）。 */
    private static volatile String validToken;
    /** false 时 /api/auth/login 直接 401（口令错误形态）。 */
    private static volatile boolean loginOk = true;

    @BeforeAll
    static void startStub() throws IOException {
        mos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mos.createContext("/api/auth/login", ex -> {
            if (!loginOk) {
                byte[] b = "{\"detail\":\"用户名或密码不对\"}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(401, b.length);
                ex.getResponseBody().write(b);
                ex.close();
                return;
            }
            int n = loginHits.incrementAndGet();
            validToken = "tok" + n;
            // 与对方 api/auth.py:110-113 同形态：httponly + Max-Age + SameSite
            ex.getResponseHeaders().add("Set-Cookie",
                    MosClient.SESSION_COOKIE + "=" + validToken
                            + "; Max-Age=604800; HttpOnly; Path=/; SameSite=lax");
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
        mos.createContext("/api/always401", ex -> {
            byte[] b = "{\"detail\":\"未登录\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, b.length);
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
    @AfterEach
    void reset() {
        loginHits.set(0);
        validToken = null;
        loginOk = true;
    }

    private static MosClient client(String password) {
        return new MosClient(base, "svc-dataworks", password, 5000);
    }

    @Test
    void lazyPerUserSession_reusedNotRelogged() throws Exception {
        MosClient c = client("pw");
        assertEquals(0, c.loginCalls(), "首次调用前不该有登录（懒代持）");

        var r1 = c.exchange("alice", "GET", "/api/auth/me", null, null);
        assertEquals(200, r1.status());
        assertEquals(1, c.loginCalls(), "alice 首次触发建立会话");

        c.exchange("alice", "GET", "/api/auth/me", null, null);
        assertEquals(1, c.loginCalls(), "同一中台用户复用会话，不重复登录");

        c.exchange("bob", "GET", "/api/auth/me", null, null);
        assertEquals(2, c.loginCalls(), "另一个中台用户各建各的（会话代持按人）");
    }

    @Test
    void staleSession_reloginsOnceAndReplaysOriginal() throws Exception {
        MosClient c = client("pw");
        assertEquals(200, c.exchange("alice", "GET", "/api/auth/me", null, null).status());

        validToken = "tok-rotated-by-upstream"; // 上游会话被换掉：alice 手上旧 token 恒 401
        var r = c.exchange("alice", "GET", "/api/auth/me", null, null);
        assertEquals(200, r.status(), "401 后应重登并重放原请求");
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("svc-dataworks"));
        assertEquals(2, loginHits.get(), "一次初登 + 一次 401 重登，不多不少");
    }

    @Test
    void always401_stopsAfterOneRelogin() throws Exception {
        MosClient c = client("pw");
        var r = c.exchange("alice", "GET", "/api/always401", null, null);
        assertEquals(401, r.status(), "重登后仍 401 原样交回，不循环");
        assertEquals(2, loginHits.get(), "登录次数封顶 2（不许登录风暴）");
    }

    @Test
    void loginFailure_surfacesHttpStatus() {
        loginOk = false;
        MosClient c = client("pw");
        IOException e = assertThrows(IOException.class,
                () -> c.exchange("alice", "GET", "/api/auth/me", null, null));
        assertTrue(e.getMessage().contains("HTTP 401"), e.getMessage());
    }

    @Test
    void missingPassword_refusesToTry() {
        MosClient blank = client("");
        assertFalse(blank.configured());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> blank.exchange("alice", "GET", "/api/auth/me", null, null));
        assertTrue(e.getMessage().contains("platform.mos.password"), e.getMessage());
        assertEquals(0, loginHits.get(), "没口令就不该打上游");

        assertFalse(new MosClient(base, "svc-dataworks", null, 5000).configured(), "null 也按未配置");
    }

    @Test
    void setCookieParsing_picksSessionNameOnly() {
        List<String> headers = List.of(
                "theme=dark; Path=/",
                MosClient.SESSION_COOKIE + "=abc123; Max-Age=604800; HttpOnly; Path=/; SameSite=lax");
        assertEquals("abc123", MosClient.extractSessionCookie(headers));
        assertEquals(null, MosClient.extractSessionCookie(List.of("other=1")));
    }
}
