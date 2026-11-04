package com.lab.dataworks.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisTargetService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D45 部署前无法验证、部署后被现网打脸的那一段：**内置（MCP）通道的限额落地**。
 *
 * <p>现网 v535 实测：租户 maxRows 改成 10，内置库查询仍返回 1000 行——
 * 因为限额只在外部 Doris 的 jdbcQuery 下发，mcpQuery 把请求里的 maxRows 原样透传。
 * 卡面验收「修改后跑一条超大查询，证明新上限生效」在默认路径上必然红。</p>
 *
 * <p>这里用本地 HttpServer 冒充 MCP，钉住三件事：</p>
 * <ol>
 *   <li>下发给 MCP 的 {@code max_rows} ＝ min(请求行数, 租户 maxRows)，且回显生效上限；</li>
 *   <li>租户超时小于通道读超时时，到点中断并显式 400（消息点名「超时上限」），
 *       而不是等 60 秒通道超时变成「查询服务暂时不可用」；</li>
 *   <li>没有限额上下文时行为不变（透传请求行数）。</li>
 * </ol>
 */
class QueryLimitsMcpEnforceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 收到请求的 max_rows；null=还没收到。 */
    private static final AtomicReference<Integer> LAST_MAX_ROWS = new AtomicReference<>();
    /** 响应延迟毫秒数；测试用来模拟慢查询。 */
    private static final AtomicInteger RESPONSE_DELAY_MS = new AtomicInteger();
    private static HttpServer mcp;
    private static int mcpPort;

    private DorisQueryService service;

    @BeforeAll
    static void startFakeMcp() throws Exception {
        mcp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcp.createContext("/tools/doris.sql_query", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode params = MAPPER.readTree(body).path("params");
            LAST_MAX_ROWS.set(params.path("max_rows").asInt(-1));
            int delay = RESPONSE_DELAY_MS.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return; // 客户端已断开，不回复
                }
            }
            byte[] resp = MAPPER.writeValueAsBytes(Map.of("result", Map.of(
                    "columns", List.of("c"),
                    "rows", List.of(List.of("1")))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        mcp.start();
        mcpPort = mcp.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeMcp() {
        mcp.stop(0);
    }

    @BeforeEach
    void setUp() {
        DorisTargetService targets = mock(DorisTargetService.class);
        when(targets.forProject(any())).thenReturn(
                new DorisTargetService.Target(false, "内置 Doris（fake:9030）", null, null, null));
        service = new DorisQueryService(new RestTemplateBuilder(),
                "http://127.0.0.1:" + mcpPort, "", targets,
                "127.0.0.1", 9030, "dataworks", "");
        LAST_MAX_ROWS.set(null);
        RESPONSE_DELAY_MS.set(0);
    }

    @AfterEach
    void clearThreadLocal() {
        QueryLimitsService.clearCurrent();
    }

    @Test
    void tenantMaxRowsCapsWhatMcpIsAskedFor() {
        QueryLimitsService.setCurrent(new QueryLimitsService.Limits(4096, 60, 10, 4, 5, 0));
        DorisQueryService.Result r = service.query(null, "select * from t", 1000);
        assertEquals(10, LAST_MAX_ROWS.get(), "MCP 收到的 max_rows 必须是 min(1000, 租户 10)");
        assertEquals(10, r.maxRows(), "响应回显实际生效的上限，界面/脚本据此判断限额落地");
    }

    @Test
    void requestBelowTenantCapPassesThrough() {
        QueryLimitsService.setCurrent(new QueryLimitsService.Limits(4096, 60, 100000, 4, 5, 0));
        DorisQueryService.Result r = service.query(null, "select * from t", 100);
        assertEquals(100, LAST_MAX_ROWS.get());
        assertEquals(100, r.maxRows());
    }

    @Test
    void noLimitsKeepsOldBehavior() {
        // 控制器没有 setCurrent（非查询页入口调用）时不该被限额逻辑改变行为
        DorisQueryService.Result r = service.query(null, "select * from t", 1000);
        assertEquals(1000, LAST_MAX_ROWS.get());
        assertEquals(1000, r.maxRows());
    }

    @Test
    void tenantTimeoutInterruptsSlowMcpCallWithExplicitError() {
        RESPONSE_DELAY_MS.set(5_000);
        QueryLimitsService.setCurrent(new QueryLimitsService.Limits(4096, 1, 100000, 4, 5, 0));
        long start = System.nanoTime();
        ApiException e = assertThrows(ApiException.class,
                () -> service.query(null, "select sleep(5)", 10));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(e.getMessage().contains("超时上限 1"), "消息要点名租户超时上限: " + e.getMessage());
        assertTrue(e.getMessage().contains("中断"), "要说清是被中断，不是查询服务坏了: " + e.getMessage());
        // 掐表在 1s；通道读超时 60s——差一个量级，才能证明不是 RestTemplate 兜底报 502
        assertTrue(elapsedMs < 15_000, "应在租户超时附近中断，实测 " + elapsedMs + "ms");
    }

    @Test
    void timeoutAtChannelLevelKeepsDirectCallPath() {
        // timeoutS >= 通道读超时(60) 时不额外起线程——行为与改动前完全一致
        QueryLimitsService.setCurrent(new QueryLimitsService.Limits(4096, 60, 100000, 4, 5, 0));
        DorisQueryService.Result r = service.query(null, "select 1", 100);
        assertEquals(100, r.maxRows());
    }
}
