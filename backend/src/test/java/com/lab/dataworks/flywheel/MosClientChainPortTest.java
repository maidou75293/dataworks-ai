package com.lab.dataworks.flywheel;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.mosbridge.MosClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-4 出站实现单测：{@link MosClientChainPort} 对假 mos（python 级 httpserver 不行，
 * 这里是 JDK HttpServer）的解析/炸错口径；顺带钉「会话复用只登一次」「401 恰好重登一次」。
 *
 * <p>不打真实 mos——真实链路归 {@code scripts/e2e/flywheel-loop.sh}（PENDING_DEPLOY 里注明）。</p>
 */
class MosClientChainPortTest {

    private FakeMos mos;
    private MosClient client;
    private MosClientChainPort port;

    @BeforeEach
    void up() throws Exception {
        mos = new FakeMos();
        client = new MosClient(mos.baseUrl(), "svc", "pw", 5000);
        port = new MosClientChainPort(client);
    }

    @AfterEach
    void down() {
        mos.close();
    }

    @Test
    void happyPathsReturnUpstreamIds() {
        assertEquals("ds_new", port.createDataset("alice", "fw-run-1"));
        assertTrue(mos.bodies.get("/api/datasets").contains("fw-run-1"));
        assertEquals("2", port.freezeDataset("alice", "ds_new"));
        String job = port.submitTrainingJob("alice", "ds_new", "sft", Map.of("base", "b1"));
        assertEquals("job_x9", job);
        assertTrue(mos.bodies.get("/api/jobs").contains("\"force\":true"), "查重让位=force 必须带");
        assertTrue(mos.bodies.get("/api/jobs").contains("ds_new"));
        String ev = port.submitEval("alice", "fw-eval", "ds_new",
                List.of(Map.of("name", "m", "base_url", "")), 999);
        assertEquals("ev_x9", ev);
        assertTrue(mos.bodies.get("/api/evals").contains("\"limit\":200"), "limit 钳到对方上限 200");
        port.runEval("alice", ev);
        assertEquals("dep_x9", port.registerDeployment("alice", "model_x1", "notes"));
        port.startDeployment("alice", "dep_x9");
        port.activateDeployment("alice", "dep_x9");
        assertEquals(1, mos.loginCount.get(), "会话代持复用：一串调用只登一次");
    }

    @Test
    void missingKeyFieldsExplode502NotSilentNull() {
        mos.overridePath = "/freeze";
        mos.overrideJson = "{\"ok\":true}";
        ApiException e = assertThrows(ApiException.class, () -> port.freezeDataset("a", "ds_new"));
        assertEquals(502, e.getStatus());
        assertTrue(e.getMessage().contains("version"));

        mos.overridePath = "/api/jobs";
        mos.overrideJson = "{\"status\":\"queued\"}";
        e = assertThrows(ApiException.class,
                () -> port.submitTrainingJob("a", "ds", "sft", Map.of()));
        assertTrue(e.getMessage().contains("id"));
    }

    @Test
    void non2xxBecomes502WithUpstreamExcerpt() {
        mos.forcePath = "/api/jobs";
        mos.forceStatus = 422;
        mos.forceBody = "{\"detail\":\"field required\"}";
        ApiException e = assertThrows(ApiException.class,
                () -> port.submitTrainingJob("a", "ds", "sft", Map.of()));
        assertEquals(502, e.getStatus());
        assertTrue(e.getMessage().contains("422"));
        assertTrue(e.getMessage().contains("field required"), "对方原文必须进摘要（不许只报自家话）");
    }

    @Test
    void auth401PassesThroughAfterExactlyOneRelogin() {
        mos.forcePath = "/api/jobs";
        mos.forceStatus = 401;
        ApiException e = assertThrows(ApiException.class,
                () -> port.submitTrainingJob("a", "ds", "sft", Map.of()));
        assertEquals(401, e.getStatus(), "401/403 原样上抛，不降级成 502 掩盖认证问题");
        assertEquals(2, mos.loginCount.get(), "首次代持 1 次 + 401 重登恰好 1 次");
    }

    @Test
    void forbiddenPassesThrough() {
        mos.forcePath = "/api/deployments";
        mos.forceStatus = 403;
        ApiException e = assertThrows(ApiException.class,
                () -> port.registerDeployment("a", "m", null));
        assertEquals(403, e.getStatus());
    }

    @Test
    void unconfiguredChannelIsExplicit503() {
        MosClient blank = new MosClient(mos.baseUrl(), "svc", "", 5000);
        MosClientChainPort p = new MosClientChainPort(blank);
        assertFalse(p.enabled());
        assertTrue(p.disabledReason().contains("platform.mos.password"));
        ApiException e = assertThrows(ApiException.class, () -> p.createDataset("a", "n"));
        assertEquals(503, e.getStatus());
        assertEquals(0, mos.loginCount.get(), "未配置=不硬试，一发都不出站");
    }

    // ================= 假 mos（JDK HttpServer，零第三方依赖） =================

    static final class FakeMos implements AutoCloseable {
        HttpServer server;
        final AtomicInteger loginCount = new AtomicInteger();
        final AtomicInteger datasetCreateCount = new AtomicInteger();
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        /** 完整请求 URI（path+query）流水——allow_empty_a 这类查询参只有这里看得到（BUG-CHAIN-1）。 */
        final List<String> uris = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile String overridePath, overrideJson;
        volatile String forcePath;
        volatile int forceStatus;
        volatile String forceBody = "{\"detail\":\"forced\"}";
        volatile String datasetGetJson = "{\"version\":null}";
        volatile String datasetsListJson = "[]";
        volatile String uploadJson = "{\"inserted\":3,\"duplicated\":1,\"empty\":0,\"total\":4}";

        FakeMos() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange ex) throws java.io.IOException {
            String p = ex.getRequestURI().getPath();
            uris.add(ex.getRequestURI().toString());   // 含 query，供 allow_empty_a 断言
            byte[] req = ex.getRequestBody().readAllBytes();
            if (req.length > 0) {
                bodies.put(p, new String(req, StandardCharsets.UTF_8));
            }
            if (p.equals("/api/auth/login")) {
                loginCount.incrementAndGet();
                ex.getResponseHeaders().add("Set-Cookie",
                        MosClient.SESSION_COOKIE + "=tok1; HttpOnly; Max-Age=604800; Path=/");
                respond(ex, 200, "{\"ok\":true}");
                return;
            }
            // 只认带 Cookie 的调用（对方会话 Cookie 口径）
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            if (cookie == null || !cookie.contains(MosClient.SESSION_COOKIE)) {
                respond(ex, 401, "{\"detail\":\"not logged in\"}");
                return;
            }
            if (forcePath != null && p.contains(forcePath) && forceStatus != 0) {
                respond(ex, forceStatus, forceBody);
                return;
            }
            if (overridePath != null && p.contains(overridePath) && overrideJson != null) {
                respond(ex, 200, overrideJson);
                return;
            }
            String method = ex.getRequestMethod();
            if (p.equals("/api/datasets") && method.equals("POST")) {
                datasetCreateCount.incrementAndGet();
                respond(ex, 200, "{\"id\":\"ds_new\",\"name\":\"x\"}");
            } else if (p.equals("/api/datasets") && method.equals("GET")) {
                respond(ex, 200, datasetsListJson);   // BUG-CHAIN-1 复用查名的第一跳
            } else if (p.endsWith("/items/upload")) {
                respond(ex, 200, uploadJson);
            } else if (p.startsWith("/api/datasets/") && method.equals("GET")) {
                respond(ex, 200, datasetGetJson);
            } else if (p.endsWith("/freeze")) {
                respond(ex, 200, "{\"ok\":true,\"version\":\"2\",\"lines\":5}");
            } else if (p.equals("/api/jobs")) {
                respond(ex, 200, "{\"id\":\"job_x9\",\"status\":\"queued\"}");
            } else if (p.equals("/api/evals")) {
                respond(ex, 200, "{\"id\":\"ev_x9\"}");
            } else if (p.endsWith("/run")) {
                respond(ex, 200, "{\"ok\":true}");
            } else if (p.equals("/api/deployments")) {
                respond(ex, 200, "{\"id\":\"dep_x9\"}");
            } else if (p.endsWith("/start") || p.endsWith("/activate")) {
                respond(ex, 200, "{\"ok\":true}");
            } else {
                respond(ex, 404, "{\"detail\":\"no route\"}");
            }
        }

        private static void respond(HttpExchange ex, int status, String body) throws java.io.IOException {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

/**
 * {@link MosClientRelabelGateway}（REQ-FW-4 补的 FW-3 出环 bean；BUG-CHAIN-1 修口径+幂等+计数校验）
 * 同一假 mos 下的行为。
 */
class MosClientRelabelGatewayTest {

    private MosClientChainPortTest.FakeMos mos;

    @org.junit.jupiter.api.BeforeEach
    void up() throws Exception {
        mos = new MosClientChainPortTest.FakeMos();
    }

    @org.junit.jupiter.api.AfterEach
    void down() {
        mos.close();
    }

    private MosClientRelabelGateway gw(String password) {
        return new MosClientRelabelGateway(
                new MosClient(mos.baseUrl(), "svc", password, 5000));
    }

    /** 计数与载荷行数吻合的默认 upload 回包（一一对上 verifyCounts 的三道校验）。 */
    private void uploadMatches(int lines) {
        mos.uploadJson = "{\"inserted\":" + lines + ",\"duplicated\":0,\"empty\":0,\"total\":" + lines + "}";
    }

    @Test
    void createsDatasetUploadsPoolAndReturnsRef() {
        uploadMatches(2);
        String pool = "q1\t\nq2\t\n";   // BUG-CHAIN-1 口径：每行 问题<TAB>，答案空=待标注
        String ref = gw("pw").createRelabelTask("badcase:t1:1", "回环批次", pool);

        assertEquals("ds_new@0", ref, "freeze 前对方 version=NULL → 归一化 '0'（FW-1 同口径）");
        assertEquals(1, mos.datasetCreateCount.get(), "查无同名才建");
        assertTrue(mos.bodies.get("/api/datasets").contains("relabel-badcase:t1:1"), "数据集名=台账键");
        String upload = mos.bodies.get("/api/datasets/ds_new/items/upload");
        assertTrue(upload.contains("name=\"file\""), "multipart 字段名 file（datasets.py:1040）");
        assertTrue(upload.contains("relabel-badcase:t1:1.tsv"), "载荷形态=TSV，文件名如实");
        assertTrue(upload.contains("q1") && upload.contains("q2"), "池内容必须原样进 upload");
        assertTrue(mos.uris.stream().anyMatch(u -> u.endsWith("/items/upload?allow_empty_a=1")),
                "空答案放行必须显式带参（默认口径不许静默丢行）：" + mos.uris);
        assertEquals("text/tab-separated-values",
                upload.replaceAll("(?s).*Content-Type: ([^\\r]+).*", "$1"), "part 类型如实=TAB 分隔");
    }

    @Test
    void retryReusesExistingDatasetByNameWithoutRecreating() {
        // BUG-CHAIN-1 根因 3 回归锚：失败重试（下一圈再来）不得再建同名集
        mos.datasetsListJson = "[{\"id\":\"ds_old\",\"name\":\"relabel-badcase:t1:1\"},"
                + "{\"id\":\"ds_zzz\",\"name\":\"别的\"}]";
        mos.uploadJson = "{\"inserted\":0,\"duplicated\":2,\"empty\":0,\"total\":2}";
        String ref = gw("pw").createRelabelTask("badcase:t1:1", "回环批次重试", "q1\t\nq2\t\n");

        assertEquals(0, mos.datasetCreateCount.get(), "查回同名=复用，零新建（旧实现每圈 +1 空集）");
        assertEquals("ds_old@0", ref, "引用回写复用的既有数据集");
        assertTrue(mos.uris.stream().anyMatch(u -> u.startsWith("/api/datasets/ds_old/items/upload")),
                "upload 打到复用数据集：" + mos.uris);
        assertTrue(!mos.bodies.containsKey("/api/datasets/ds_new/items/upload"), "不得往新建口推");
    }

    @Test
    void versionEchoedByGetIsKeptAsIs() {
        uploadMatches(1);
        mos.datasetGetJson = "{\"version\":\"3\"}";
        assertEquals("ds_new@3", gw("pw").createRelabelTask("k", "d", "q\t\n"));
    }

    @Test
    void uploadWithoutCountersFailsLoudly() {
        mos.uploadJson = "{\"inserted\":1}";
        ApiException e = assertThrows(ApiException.class,
                () -> gw("pw").createRelabelTask("k", "d", "q\t\n"));
        assertEquals(502, e.getStatus());
        assertTrue(e.getMessage().contains("inserted/total"));
    }

    @Test
    void uploadTotalNotMatchingPayloadExplodes() {
        // 问题以 { 开头撞对方 JSON 分支丢行的现形处：total=1 < 载荷 2 行，禁止静默出环
        mos.uploadJson = "{\"inserted\":1,\"duplicated\":0,\"empty\":0,\"total\":1}";
        ApiException e = assertThrows(ApiException.class,
                () -> gw("pw").createRelabelTask("k", "d", "{甲\t\n乙\t\n"));
        assertEquals(502, e.getStatus());
        assertTrue(e.getMessage().contains("行数不吻合"), e.getMessage());
    }

    @Test
    void uploadDiscardingEmptyAnswersExplodes() {
        // allow_empty_a 在对方失效（闸口径再变）时必须炸，不许静默进空集
        mos.uploadJson = "{\"inserted\":0,\"duplicated\":0,\"empty\":2,\"total\":2}";
        ApiException e = assertThrows(ApiException.class,
                () -> gw("pw").createRelabelTask("k", "d", "q1\t\nq2\t\n"));
        assertEquals(502, e.getStatus());
        assertTrue(e.getMessage().contains("allow_empty_a"), e.getMessage());
    }

    @Test
    void uploadCountNotClosedExplodes() {
        mos.uploadJson = "{\"inserted\":1,\"duplicated\":1,\"empty\":0,\"total\":3}";
        ApiException e = assertThrows(ApiException.class,
                () -> gw("pw").createRelabelTask("k", "d", "q1\t\nq2\t\nq3\t\n"));
        assertEquals(502, e.getStatus());
        assertTrue(e.getMessage().contains("计数不闭合"), e.getMessage());
    }

    @Test
    void unconfiguredStays503SoBatchRemainsReady() {
        ApiException e = assertThrows(ApiException.class,
                () -> gw("").createRelabelTask("k", "d", "q\t\n"));
        assertEquals(503, e.getStatus(), "桥接不可用=保持 READY 语义的来源（RelabelTrigger 会吃这个异常）");
        assertEquals(0, mos.loginCount.get(), "未配置=一发都不出站");
    }
}
