package com.lab.dataworks.alert;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifyServiceTest {

    @Test
    void blocksLoopbackAndMetadataTargets() {
        for (String host : new String[]{
                "http://127.0.0.1/x", "http://127.0.0.2/x", "http://0.0.0.0/x",
                "http://169.254.169.254/x", "http://metadata/x",
                "http://localhost.:25/x", "http://[::1]/x", "http://169.254.10.10/x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> NotifyService.ensureSafeHost(host), host);
        }
    }

    @Test
    void allowsNormalAndInternalWebhooks() {
        assertDoesNotThrow(() -> NotifyService.ensureSafeHost("https://oapi.dingtalk.com/robot/send"));
        assertDoesNotThrow(() -> NotifyService.ensureSafeHost("http://10.99.99.11:8080/hook"));
        assertDoesNotThrow(() -> NotifyService.ensureSafeHost(null));
    }

    @Test
    void validatesEmailHostFromConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> NotifyService.validateTarget("EMAIL", null, Map.of("host", "127.0.0.1")));
        assertDoesNotThrow(() -> NotifyService.validateTarget("EMAIL", null,
                Map.of("host", "smtp.example.com")));
    }

    @Test
    void providerBusinessErrorsAreFailures() {
        assertTrue(NotifyService.providerSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        assertFalse(NotifyService.providerSuccess("{\"errcode\":310000,\"errmsg\":\"sign not match\"}"));
        assertTrue(NotifyService.providerSuccess("{\"code\":0,\"msg\":\"success\"}"));
        assertFalse(NotifyService.providerSuccess("{\"code\":19021,\"msg\":\"sign match fail\"}"));
        assertTrue(NotifyService.providerSuccess("{\"StatusCode\":0,\"StatusMessage\":\"success\"}"));
        assertFalse(NotifyService.providerSuccess("{\"StatusCode\":9499,\"StatusMessage\":\"Bad Request\"}"));
        // 自建 webhook 没有业务码：按 HTTP 状态判断
        assertTrue(NotifyService.providerSuccess("{\"ok\":true}"));
        assertTrue(NotifyService.providerSuccess("not-json"));
        assertTrue(NotifyService.providerSuccess(""));
    }
}
