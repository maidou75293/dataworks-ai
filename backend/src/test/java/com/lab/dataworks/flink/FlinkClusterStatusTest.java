package com.lab.dataworks.flink;

import com.lab.dataworks.auth.RequireAtom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * BUG-TC6-12：流开发页的最小集群状态。
 *
 * <p>验收三件事：① 只回 ready / reason / sessionMode（作业数、槽位、TaskManager
 * 明细不能从侧门漏出去）；② 三种「不能提交」的原因要能分辨（没有 TaskManager /
 * 没有 Slot / 槽位满要排队 / 集群连不上）；③ 连不上时的文案不回显内网地址。</p>
 */
class FlinkClusterStatusTest {

    private static final String REST = "http://flink-core:8081";

    private MockRestServiceServer server;
    private FlinkSqlService flink;

    @BeforeEach
    void setUp() {
        RestTemplate http = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(1))
                .setReadTimeout(Duration.ofSeconds(1))
                .build();
        server = MockRestServiceServer.bindTo(http).build();
        flink = new FlinkSqlService(mock(JdbcTemplate.class), http,
                "http://flink-gw:8083", REST, "", "",
                "127.0.0.1", 9030, "dataworks", "secret-pass");
    }

    private void overview(String body) {
        server.expect(requestTo(REST + "/overview"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void readyWhenClusterHasFreeSlot() {
        overview("{\"taskmanagers\":2,\"slots-total\":4,\"slots-available\":3,"
                + "\"jobs-running\":2,\"jobs-finished\":9}");

        Map<String, Object> out = flink.clusterStatus();

        assertEquals(true, out.get("ready"));
        assertEquals("", out.get("reason"));
        assertEquals("SESSION", out.get("sessionMode"));
        // 契约固定三键：作业数/槽位数/worker 明细都属于平台画像，不能出现在这里
        assertEquals(Set.of("ready", "reason", "sessionMode"), out.keySet());
        server.verify();
    }

    @Test
    void notReadyWhenNoTaskManager() {
        overview("{\"taskmanagers\":0,\"slots-total\":0,\"slots-available\":0}");

        Map<String, Object> out = flink.clusterStatus();

        assertEquals(false, out.get("ready"));
        assertTrue(String.valueOf(out.get("reason")).contains("TaskManager"),
                "reason 应说明没有 TaskManager: " + out.get("reason"));
    }

    @Test
    void notReadyWhenSlotsFullButSaysQueued() {
        // 槽位满不是失败：作业提交后排队，文案必须说「排队」，别说成提交失败
        overview("{\"taskmanagers\":1,\"slots-total\":2,\"slots-available\":0}");

        Map<String, Object> out = flink.clusterStatus();

        assertEquals(false, out.get("ready"));
        String reason = String.valueOf(out.get("reason"));
        assertTrue(reason.contains("排队"), "reason 应说明会排队: " + reason);
        assertFalse(reason.contains("失败"), "槽位满不是失败: " + reason);
    }

    @Test
    void unreachableClusterDoesNotLeakInternalAddress() {
        server.expect(requestTo(REST + "/overview"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        Map<String, Object> out = flink.clusterStatus();

        assertEquals(false, out.get("ready"));
        String reason = String.valueOf(out.get("reason"));
        assertTrue(reason.contains("服务未就绪"), "reason 应说明服务未就绪: " + reason);
        assertFalse(reason.contains("http"), "reason 不能回显 URL: " + reason);
        assertFalse(reason.contains("flink-core"), "reason 不能回显内网主机名: " + reason);
        assertFalse(reason.contains("secret-pass"), "reason 不能回显口令: " + reason);
    }

    @Test
    void connectionFailureDoesNotLeakAddress() {
        // 没有服务监听的端口：RestTemplate 的 I/O 异常消息里带完整 URL，
        // 这条消息绝不能直接进 reason（数据开发者能读这个接口）
        RestTemplate down = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(300))
                .setReadTimeout(Duration.ofMillis(300))
                .build();
        FlinkSqlService off = new FlinkSqlService(mock(JdbcTemplate.class), down,
                "http://127.0.0.1:9", "http://127.0.0.1:9", "", "",
                "127.0.0.1", 9030, "dataworks", "secret-pass");

        Map<String, Object> out = off.clusterStatus();

        assertEquals(false, out.get("ready"));
        String reason = String.valueOf(out.get("reason"));
        assertFalse(reason.contains("http"), "reason 不能回显 URL: " + reason);
        assertFalse(reason.contains("127.0.0.1"), "reason 不能回显内网地址: " + reason);
        assertTrue(reason.contains("连不上"), "reason 要说明连不上: " + reason);
    }

    @Test
    void controllerExposesMinimalStatusUnderFlinkRun() throws Exception {
        // 新面挂 flink:run（数据开发者有、VIEWER 没有）；平台画像接口保持 system:manage
        RequireAtom status = FlinkClusterController.class.getMethod("status")
                .getAnnotation(RequireAtom.class);
        RequireAtom cluster = FlinkClusterController.class.getMethod("cluster")
                .getAnnotation(RequireAtom.class);

        assertEquals(java.util.List.of("flink:run"), java.util.List.of(status.value()));
        assertEquals(java.util.List.of("system:manage"), java.util.List.of(cluster.value()));
    }
}
