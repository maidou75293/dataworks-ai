package com.lab.dataworks.service;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据服务对外契约：BOOL 参数校验（D11）与声明 method 校验（D23）。
 *
 * <p>D11：{@code coerce(value, "BOOL")} 原来用 {@code Boolean.valueOf}，任何非法字符串
 * 都静默变成 {@code false}（{@code flag=not-a-bool} → false），调用方拿到「过滤条件全关」
 * 的结果集却以为参数生效了；INT / DATE 分支本来就是非法值 400，BOOL 必须对齐。</p>
 *
 * <p>D23：路由同时接受 GET/POST，原来不看发布快照声明的 method，声明 GET 的服务
 * 用 POST 也 200，对外契约与快照不一致。</p>
 */
class DataServiceParamContractTest {

    // ------------------------------------------------------------------ D11

    @Test
    void boolAcceptsCanonicalTrueForms() {
        for (String value : new String[]{"true", "TRUE", "True", " true ", "on", "y", "yes", "1"}) {
            assertEquals(Boolean.TRUE, DataServiceController.coerce(value, "BOOL"),
                    "应识别为 true: " + value);
        }
    }

    @Test
    void boolAcceptsCanonicalFalseForms() {
        for (String value : new String[]{"false", "FALSE", "False", " false ", "off", "n", "no", "0"}) {
            assertEquals(Boolean.FALSE, DataServiceController.coerce(value, "BOOL"),
                    "应识别为 false: " + value);
        }
    }

    @Test
    void boolAcceptsJsonBoolean() {
        assertEquals(Boolean.TRUE, DataServiceController.coerce(Boolean.TRUE, "BOOL"));
        assertEquals(Boolean.FALSE, DataServiceController.coerce(Boolean.FALSE, "BOOL"));
    }

    @Test
    void boolRejectsGarbageInsteadOfSilentFalse() {
        // 修复前这些全部静默变成 false（Boolean.valueOf 的语义）
        for (String value : new String[]{"not-a-bool", "真", "maybe", "2", "-1", "null", "nil",
                "enabled", "TRUE!", "01", "yes/no"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> DataServiceController.coerce(value, "BOOL"),
                    "非法布尔值必须 400，不能静默当 false: " + value);
            assertEquals(400, e.getStatus(), "状态码应是 400: " + value);
            assertTrue(e.getMessage().contains("BOOL"),
                    "提示要说明是 BOOL 参数: " + e.getMessage());
            assertTrue(e.getMessage().contains(value.trim()),
                    "提示要回显调用方传的值: " + e.getMessage());
        }
    }

    @Test
    void boolRejectsEmptyString() {
        // 空串在 execute() 里会先走「必填参数缺失」，但 coerce 本身也不能把它当 false
        assertThrows(ApiException.class, () -> DataServiceController.coerce("", "BOOL"));
    }

    @Test
    void otherTypesStillRejectIllegalValues() {
        // 对照：INT / DATE 的「格式不对就 400」没有被这次改动带偏
        assertEquals(400, assertThrows(ApiException.class,
                () -> DataServiceController.coerce("abc", "INT")).getStatus());
        assertEquals(400, assertThrows(ApiException.class,
                () -> DataServiceController.coerce("abc", "DATE")).getStatus());
        assertEquals(7, DataServiceController.coerce("7", "INT"));
    }

    /**
     * 记下实测事实（本次不修，交主控派单）：{@code java.sql.Date.valueOf("2026-02-30")}
     * 不抛异常，会宽松解析成 2026-02-28 —— 与 D16「补数日期被静默纠正」同一类根因
     * （宽松日期解析），但入口是数据服务的 DATE 参数。
     */
    @Test
    void knownGapDateTypeStillAcceptsImpossibleCalendarDate() {
        assertNotNull(DataServiceController.coerce("2026-02-30", "DATE"),
                "已知缺口：DATE 参数目前不校验日历合法性");
    }

    @Test
    void nullValueStaysNullForEveryType() {
        assertNull(DataServiceController.coerce(null, "BOOL"));
        assertNull(DataServiceController.coerce(null, "INT"));
    }

    // ------------------------------------------------------------------ D23

    @Test
    void declaredGetRejectsPost() {
        String reason = DataServiceController.methodMismatch("GET", "POST");
        assertNotNull(reason, "声明 GET 的服务不能用 POST 调用");
        assertTrue(reason.contains("GET") && reason.contains("POST"), reason);
    }

    @Test
    void declaredPostRejectsGet() {
        String reason = DataServiceController.methodMismatch("POST", "GET");
        assertNotNull(reason, "声明 POST 的服务不能用 GET 调用");
        assertTrue(reason.contains("POST") && reason.contains("GET"), reason);
    }

    @Test
    void sameMethodPassesIgnoringCaseAndSpace() {
        assertNull(DataServiceController.methodMismatch("GET", "GET"));
        assertNull(DataServiceController.methodMismatch("post", "POST"));
        assertNull(DataServiceController.methodMismatch(" GET ", "get"));
    }

    @Test
    void missingDeclaredMethodIsNotBlocked() {
        // 历史发布记录没有 method 值：不拦，避免把存量服务打断
        assertNull(DataServiceController.methodMismatch(null, "POST"));
        assertNull(DataServiceController.methodMismatch("  ", "GET"));
    }
}
