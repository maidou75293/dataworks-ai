package com.lab.dataworks.integration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运行校验与日志处理：行数不一致必须判失败，密码必须被脱敏。 */
class RunVerifyTest {

    @Test
    void rowsMatchPasses() {
        var out = SyncTaskService.verify("ROWS", "SUCCESS", 10000L, 10000L);
        assertEquals("SUCCESS", out.status());
        assertEquals("PASSED", out.verifyResult());
        assertNull(out.reason());
    }

    @Test
    void rowsMismatchFails() {
        var out = SyncTaskService.verify("ROWS", "SUCCESS", 10000L, 9999L);
        assertEquals("FAILED", out.status());
        assertEquals("FAILED", out.verifyResult());
        assertTrue(out.reason().contains("10000"));
        assertTrue(out.reason().contains("9999"));
    }

    @Test
    void missingMetricsAreSkippedNotFailed() {
        var out = SyncTaskService.verify("ROWS", "SUCCESS", null, null);
        assertEquals("SUCCESS", out.status());
        assertEquals("SKIPPED", out.verifyResult());
    }

    @Test
    void noneModeNeverFails() {
        var out = SyncTaskService.verify("NONE", "SUCCESS", 3L, 2L);
        assertEquals("SUCCESS", out.status());
        assertEquals("SKIPPED", out.verifyResult());
    }

    @Test
    void alreadyFailedIsUntouched() {
        var out = SyncTaskService.verify("ROWS", "FAILED", 3L, 2L);
        assertEquals("FAILED", out.status());
        assertNull(out.verifyResult());
    }

    @Test
    void metricsParsedFromSeatunnelSummary() {
        String log = """
                Total Time(s)             :                   7
                Total Read Count          :                 10000
                Total Write Count         :                 10000
                Total Failed Count        :                     0
                """;
        Map<String, Long> m = SyncTaskService.parseMetrics(log);
        assertEquals(10000L, m.get("rowsRead"));
        assertEquals(10000L, m.get("rowsWritten"));
        assertEquals(7L, m.get("durationSeconds"));
    }

    @Test
    void secretsAreRedacted() {
        String log = """
                {"password":"test123","user":"postgres"}
                {"secretKey":"abc","accessKey":"dwadmin"}
                jdbc:postgresql://h/db?user=u&password=p%40123&ssl=true
                """;
        String out = SyncTaskService.redact(log);
        assertTrue(out.contains("\"password\":\"***\""));
        assertTrue(out.contains("\"secretKey\":\"***\""));
        assertTrue(out.contains("password=***"));
        assertTrue(out.contains("&ssl=true"), "URL 的其它参数不能被吃掉");
        assertTrue(!out.contains("test123"));
        assertTrue(!out.contains("p%40123"));
    }
}
