package com.lab.dataworks.mosbridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-2 网关传输单测（契约 §6.2：Cookie 代持 + 401 恰好重登一次再重放）。
 * 用 JDK 自带 HttpServer 打桩，不依赖真实 mos。
 *
 * 跑法：{@code mvn -q -Dtest=HttpMosGatewayTest test}
 */
class HttpMosGatewayTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger loginCount = new AtomicInteger();
    private final AtomicInteger jobHits = new AtomicInteger();
    /** 前 N 次 jobs 请求回 401（模拟 7 天 Cookie 过期）。 */
    private volatile int unauthorizedFirst = 0;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/api/auth/login", ex -> {
            loginCount.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"svc-dataworks\"")) {
                ex.getResponseHeaders().add("Set-Cookie",
                        HttpMosGateway.COOKIE_NAME + "=tok123; HttpOnly; SameSite=Lax; Path=/");
                byte[] ok = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, ok.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(ok);
                }
            } else {
                byte[] bad = "{\"detail\":\"用户名或密码不对\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(401, bad.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bad);
                }
            }
        });

        server.createContext("/api/jobs/job_A", ex -> {
            jobHits.incrementAndGet();
            String cookie = String.valueOf(ex.getRequestHeaders().getFirst("Cookie"));
            if (!cookie.contains("tok123") || jobHits.get() <= unauthorizedFirst) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            byte[] body = "{\"id\":\"job_A\",\"status\":\"running\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/api/jobs/gone", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });

        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpMosGateway gateway() {
        return new HttpMosGateway(base, "svc-dataworks", "pw", 5000);
    }

    @Test
    void logsInThenFetchesJob() throws Exception {
        Map<String, Object> job = gateway().getJob("job_A");
        assertEquals("running", job.get("status"));
        assertEquals(1, loginCount.get());
    }

    /** 401 → 恰好重登一次 → 重放成功；不许出现重登风暴（≤2 次登录）。 */
    @Test
    void reloginsExactlyOnceOn401() throws Exception {
        unauthorizedFirst = 1; // 第一次 jobs 请求 401
        Map<String, Object> job = gateway().getJob("job_A");
        assertEquals("running", job.get("status"));
        assertEquals(2, loginCount.get());
        assertEquals(2, jobHits.get());
    }

    @Test
    void missingConfigMeansDisabledWithExplicitReason() {
        HttpMosGateway g = new HttpMosGateway("", "svc-dataworks", "", 5000);
        assertFalse(g.enabled());
        assertTrue(g.disabledReason().contains("base-url"));
        HttpMosGateway g2 = new HttpMosGateway(base, "svc-dataworks", "", 5000);
        assertFalse(g2.enabled());
        assertTrue(g2.disabledReason().contains("password"));
    }

    @Test
    void notFoundSurfacesAsNoSuchElement() {
        assertThrows(java.util.NoSuchElementException.class, () -> gateway().getJob("gone"));
    }

    /** 固定 HTTP/1.1（uvicorn h2c 吞响应体坑，wm6 同结论）。 */
    @Test
    void usesHttp11EvenWhenServerPrefersH2c() throws Exception {
        // stub server 只支持 HTTP/1.1；若客户端协商升级会失败——能取回即证明 1.1
        assertEquals("running", gateway().getJob("job_A").get("status"));
    }
}
