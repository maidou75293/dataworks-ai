package com.lab.dataworks.mosbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.query.DorisQueryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-FW-1 单测：映射（契约 §3.1）、upload 报文形态、会话复用/401 恰好重登一次、
 * 失败显式 502/503（不许静默）、台账 upsert 参数（version 归一化 '0'）。
 *
 * <p>mos 用本地 stub server 顶替（形态按真实响应：auth.py:33 Set-Cookie、
 * datasets.py:1114 _insert_pairs 返回、:301 裸行）；JdbcTemplate 用 Mockito，
 * 台账 SQL 与参数逐一钉住。</p>
 */
class AnnotationPushServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private JdbcTemplate jdbc;
    private AnnotationPushService service;

    // ---- stub 状态 ----
    private final AtomicInteger logins = new AtomicInteger();
    private volatile String validToken;
    private volatile boolean enforceCookie = true;
    private volatile String datasetVersion = null;      // null → 响应里 version:null（freeze 前真实形态）
    private volatile int uploadStatus = 200;
    private volatile boolean simulateAllDuplicate = false;
    private final AtomicReference<String> lastUploadBody = new AtomicReference<>("");
    private final AtomicReference<String> lastUploadContentType = new AtomicReference<>("");
    private final AtomicReference<String> lastUploadCookie = new AtomicReference<>("");
    private final AtomicReference<Object[]> ledgerArgs = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        jdbc = mock(JdbcTemplate.class);
        // 9 个占位参数 + SQL：捕获实参供断言
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ledgerArgs.set(inv.getArguments());
                    return 1;
                });
        service = new AnnotationPushService(baseUrl, "svc-test", "pw-test", 5000, jdbc);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        byte[] body = ex.getRequestBody().readAllBytes();
        try {
            if (path.equals("/api/auth/login")) {
                int n = logins.incrementAndGet();
                validToken = "TK" + n;
                ex.getResponseHeaders().add("Set-Cookie",
                        AnnotationPushService.SESSION_COOKIE + "=" + validToken + "; Path=/; HttpOnly");
                respond(ex, 200, "{\"ok\":true}");
                return;
            }
            if (path.equals("/api/datasets") && method.equals("POST")) {
                respond(ex, 200, "{\"id\":\"ds_abc1234567\",\"name\":\"x\"}");
                return;
            }
            if (path.endsWith("/items/upload")) {
                lastUploadBody.set(new String(body, StandardCharsets.UTF_8));
                lastUploadContentType.set(String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
                lastUploadCookie.set(String.valueOf(ex.getRequestHeaders().getFirst("Cookie")));
                if (enforceCookie && !validToken.equals(cookieValue(lastUploadCookie.get()))) {
                    respond(ex, 401, "{\"detail\":\"未登录\"}");
                    return;
                }
                if (uploadStatus != 200) {
                    respond(ex, uploadStatus, "boom");
                    return;
                }
                int total = countOccurrences(lastUploadBody.get(), "\"messages\"");
                String json = simulateAllDuplicate
                        ? "{\"ok\":true,\"inserted\":0,\"duplicated\":" + total + ",\"empty\":0,\"skipped\":"
                          + total + ",\"total\":" + total + "}"
                        : "{\"ok\":true,\"inserted\":" + total + ",\"duplicated\":0,\"empty\":0,\"skipped\":0,\"total\":"
                          + total + "}";
                respond(ex, 200, json);
                return;
            }
            if (path.startsWith("/api/datasets/") && method.equals("GET")) {
                String ver = datasetVersion == null ? "null" : "\"" + datasetVersion + "\"";
                respond(ex, 200, "{\"id\":\"ds_abc1234567\",\"version\":" + ver + ",\"n_items\":10}");
                return;
            }
            respond(ex, 404, "{\"detail\":\"stub 无此路由: " + path + "\"}");
        } finally {
            ex.close();
        }
    }

    private static String cookieValue(String header) {
        if (header == null || header.contains("=") == false) {
            return null;
        }
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(AnnotationPushService.SESSION_COOKIE + "=")) {
                return p.substring(AnnotationPushService.SESSION_COOKIE.length() + 1);
            }
        }
        return null;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    private static int countOccurrences(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    private static DorisQueryService.Result result(List<String> columns, List<List<String>> rows) {
        return new DorisQueryService.Result(columns, rows, rows.size(), 1000, 1L, "stub");
    }

    private static List<List<String>> qaRows(int n) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            rows.add(List.of("问题" + i, "答案" + i, "pk-" + i));
        }
        return rows;
    }

    // ---------------- §3.1 映射 ----------------

    @Test
    void mappingProducesMessagesJsonlPerContract31() throws Exception {
        String jsonl = AnnotationPushService.toMessagesJsonl(
                List.of("question", "answer", "row_key"), qaRows(2), "question", "answer", "row_key");
        String[] lines = jsonl.strip().split("\n");
        assertThat(lines).hasSize(2);
        JsonNode first = M.readTree(lines[0]);
        assertThat(first.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(first.path("messages").get(0).path("content").asText()).isEqualTo("问题1");
        assertThat(first.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(first.path("messages").get(1).path("content").asText()).isEqualTo("答案1");
        assertThat(first.path("source_ref").asText()).isEqualTo("pk-1");
    }

    @Test
    void columnNamesAreCaseInsensitiveAndOmittedRefDropsField() throws Exception {
        String jsonl = AnnotationPushService.toMessagesJsonl(
                List.of("Question", "ANSWER"), List.of(List.of("q1", "a1")), "question", "answer", null);
        JsonNode node = M.readTree(jsonl.strip());
        assertThat(node.path("messages").get(0).path("content").asText()).isEqualTo("q1");
        assertThat(node.has("source_ref")).isFalse();
    }

    @Test
    void unknownColumnFailsLoudWithActualColumns() {
        assertThatThrownBy(() -> AnnotationPushService.toMessagesJsonl(
                List.of("question", "answer"), qaRows(1), "quesion", "answer", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("quesion").contains("实际列");
                });
    }

    @Test
    void blankQuestionOrAnswerRejectsWholeBatchWithRowNumbers() {
        List<List<String>> rows = Arrays.asList(
                List.of("q1", "a1", "r1"), List.of("", "a2", "r2"), List.of("q3", "  ", "r3"));
        assertThatThrownBy(() -> AnnotationPushService.toMessagesJsonl(
                List.of("question", "answer", "pk"), rows, "question", "answer", "pk"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    // 不许静默：行号 2、3 都要点名（对方是静默跳过，中台侧必须炸）
                    assertThat(e.getMessage()).contains("第2行").contains("第3行");
                });
    }

    @Test
    void emptyResultRejected() {
        assertThatThrownBy(() -> AnnotationPushService.toMessagesJsonl(
                List.of("question", "answer"), List.of(), "question", "answer", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ---------------- upload / 会话 ----------------

    @Test
    void pushUploadsMultipartAndRegistersLedgerVersionZeroWhenMosHasNone() {
        // freeze 前对方 version=JSON null → 台账必须落 '0'（NULL 不进唯一键，否则幂等破防）
        datasetVersion = null;
        AnnotationPushService.PushReport rep = service.push(
                "acme", "alice", "select question, answer, row_key from t",
                "ds_abc1234567", result(List.of("question", "answer", "row_key"), qaRows(3)),
                "question", "answer", "row_key");
        assertThat(rep.inserted()).isEqualTo(3);
        assertThat(rep.total()).isEqualTo(3);
        assertThat(rep.version()).isEqualTo("0");
        assertThat(lastUploadContentType.get()).contains("multipart/form-data; boundary=");
        assertThat(lastUploadBody.get())
                .contains("Content-Disposition: form-data; name=\"file\"")
                .contains("\"role\":\"user\"")
                .contains("问题1");
        Object[] args = ledgerArgs.get();
        assertThat(Arrays.toString(args)).contains("annotation_push_ledger").contains("ON CONFLICT");
        // args[0]=SQL；参数序: tenant_code, ds_id, version, source_desc, q, a, ref, row_count, pushed_by
        assertThat(args[1]).isEqualTo("acme");
        assertThat(args[2]).isEqualTo("ds_abc1234567");
        assertThat(args[3]).isEqualTo("0");
        assertThat(args[8]).isEqualTo(3);
        assertThat(args[9]).isEqualTo("alice");
    }

    @Test
    void ledgerUsesFrozenVersionWhenPresent() {
        datasetVersion = "7";
        AnnotationPushService.PushReport rep = service.push(
                "acme", "alice", "select 1", "ds_abc1234567",
                result(List.of("question", "answer"), qaRows(1)), "question", "answer", null);
        assertThat(rep.version()).isEqualTo("7");
        assertThat(ledgerArgs.get()[3]).isEqualTo("7");
    }

    @Test
    void sessionReusedAcrossPushesAndReLoginOnlyOnceOn401() {
        service.push("acme", "alice", "select 1", "ds_abc1234567",
                result(List.of("question", "answer"), qaRows(1)), "question", "answer", null);
        assertThat(service.loginCalls()).isEqualTo(1);
        // 二次推送复用会话，不再登录
        service.push("acme", "alice", "select 1", "ds_abc1234567",
                result(List.of("question", "answer"), qaRows(1)), "question", "answer", null);
        assertThat(service.loginCalls()).isEqualTo(1);
        // 服务端把会话换掉 → 下一次先 401，恰好重登一次并重放
        validToken = "TK99-FRESH";
        AnnotationPushService.PushReport rep = service.push("acme", "alice", "select 1", "ds_abc1234567",
                result(List.of("question", "answer"), qaRows(1)), "question", "answer", null);
        assertThat(service.loginCalls()).isEqualTo(2);
        assertThat(rep.inserted()).isEqualTo(1);
    }

    @Test
    void duplicatePushReportsZeroInsertedWithTenDuplicated() {
        simulateAllDuplicate = true;
        AnnotationPushService.PushReport rep = service.push("acme", "alice", "select 1", "ds_abc1234567",
                result(List.of("question", "answer"), qaRows(10)), "question", "answer", null);
        assertThat(rep.inserted()).isZero();
        assertThat(rep.duplicated()).isEqualTo(10);
        assertThat(rep.total()).isEqualTo(10);
    }

    @Test
    void uploadNon2xxSurfaces502WithUpstreamStatus() {
        uploadStatus = 500;
        assertThatThrownBy(() -> service.push("acme", "alice", "select 1", "ds_abc1234567",
                result(List.of("question", "answer"), qaRows(1)), "question", "answer", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("500");
                });
    }

    @Test
    void unconfiguredPasswordIs503NotSilentSkip() {
        AnnotationPushService unconfigured =
                new AnnotationPushService(baseUrl, "svc-test", "", 5000, jdbc);
        assertThatThrownBy(() -> unconfigured.push("acme", "alice", "select 1", "ds_abc1234567",
                result(List.of("question", "answer"), qaRows(1)), "question", "answer", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("platform.mos.password");
                });
    }

    @Test
    void blankDsIdRejectedBeforeAnyHttp() {
        assertThatThrownBy(() -> service.push("acme", "alice", "select 1", "  ",
                result(List.of("question", "answer"), qaRows(1)), "question", "answer", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThat(logins.get()).isZero();
    }

    @Test
    void createDatasetReturnsMosId() {
        assertThat(service.createDataset("fw1-test")).isEqualTo("ds_abc1234567");
    }

    @Test
    void itemCountReadsNItemsFromDatasetRow() {
        assertThat(service.itemCount("ds_abc1234567")).isEqualTo(10);
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
