package com.lab.dataworks.query;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D45 验收口径：查询限额的写入契约。
 *
 * <p>锁住三件事：</p>
 * <ol>
 *   <li>越界值必须 400 并点名字段与区间（旧实现静默 clamp，把 timeoutS=99999 存成 3600，
 *       使用者以为设的是 99999 —— 这类「看着成功其实被改了」必须钉死）；</li>
 *   <li>{@code null＝不改这一项}，界面才能只提交改动字段；</li>
 *   <li>GET 必须把字段含义与合法范围一起吐出来，界面照这份渲染，别在前端再抄一遍常量。</li>
 * </ol>
 *
 * <p>纯 Mockito 单测（不起 Spring）：{@link QueryLimitsService} 被 mock，
 * 只验控制器自己的校验与「改动字段 + 沿用当前值」的合并逻辑。</p>
 */
class QueryLimitsUpdateTest {

    /** 库里当前生效值；刻意与 QueryLimitsService.DEFAULTS 不同，便于分辨取值来自哪一边。 */
    private static final QueryLimitsService.Limits CURRENT =
            new QueryLimitsService.Limits(2048, 120, 50000, 8, 10, 0);

    private final QueryLimitsService service = mock(QueryLimitsService.class);
    private final com.lab.dataworks.common.TenantGuard guard =
            mock(com.lab.dataworks.common.TenantGuard.class);
    private final QueryLimitsController controller = new QueryLimitsController(service, guard);

    @BeforeEach
    void stubService() {
        when(service.resolve(anyString())).thenReturn(CURRENT);
        when(service.view(anyString())).thenAnswer(inv -> viewOf(CURRENT));
        when(service.update(anyString(), any(), anyString()))
                .thenAnswer(inv -> viewOf(inv.getArgument(1)));
    }

    private static Map<String, Object> viewOf(QueryLimitsService.Limits l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", "default");
        m.put("limits", l);
        m.put("inflight", 0);
        return m;
    }

    private static QueryLimitsController.LimitsRequest request(Map<String, Integer> values) {
        return new QueryLimitsController.LimitsRequest(
                values.get("maxMemMb"), values.get("timeoutS"), values.get("maxRows"),
                values.get("maxParallel"), values.get("maxConcurrency"),
                values.get("dailyQueryLimit"));
    }

    private QueryLimitsService.Limits capturedUpdate() {
        ArgumentCaptor<QueryLimitsService.Limits> cap =
                ArgumentCaptor.forClass(QueryLimitsService.Limits.class);
        verify(service).update(anyString(), cap.capture(), anyString());
        return cap.getValue();
    }

    // ---------- 1. 越界必须 400，不能静默夹到边界 ----------

    @Test
    void outOfRangeTimeoutIsRejectedWith400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.update(
                new QueryLimitsController.LimitsRequest(null, 99999, null, null, null, null)));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("timeoutS"), e.getMessage());
        assertTrue(e.getMessage().contains("1-3600"), e.getMessage());
        assertTrue(e.getMessage().contains("99999"), e.getMessage());
        // 被拒的请求一个字都不能落库
        verify(service, never()).update(anyString(), any(), anyString());
    }

    @Test
    void everyFieldRejectsValuesOutsideItsRange() {
        Map<String, Integer> bad = new LinkedHashMap<>();
        bad.put("maxMemMb", 127);
        bad.put("timeoutS", 3601);
        bad.put("maxRows", 0);
        bad.put("maxParallel", 65);
        bad.put("maxConcurrency", 201);
        bad.put("dailyQueryLimit", -1);
        // 每个字段的上侧越界
        Map<String, Integer> badHigh = new LinkedHashMap<>();
        badHigh.put("maxMemMb", 65537);
        badHigh.put("timeoutS", 99999);
        badHigh.put("maxRows", 1_000_001);
        badHigh.put("maxParallel", 64 + 1);
        badHigh.put("maxConcurrency", 200 + 1);

        for (Map<String, Integer> one : List.of(bad, badHigh)) {
            for (String key : one.keySet()) {
                ApiException e = assertThrows(ApiException.class,
                        () -> controller.update(request(Map.of(key, one.get(key)))));
                assertEquals(400, e.getStatus(), key);
                assertTrue(e.getMessage().contains(key),
                        key + " 的报错要点名字段，实际：" + e.getMessage());
            }
        }
        verify(service, never()).update(anyString(), any(), anyString());
    }

    /** 0 只允许出现在「每日查询次数」（0＝不限）；其它字段 0 都是非法。 */
    @Test
    void zeroIsLegalOnlyForDailyQueryLimit() {
        ApiException e = assertThrows(ApiException.class, () -> controller.update(
                new QueryLimitsController.LimitsRequest(null, null, null, null, 0, null)));
        assertEquals(400, e.getStatus());

        controller.update(new QueryLimitsController.LimitsRequest(null, null, null, null, null, 0));
        assertEquals(0, capturedUpdate().dailyQueryLimit());
    }

    // ---------- 2. 合法区间（含两端）照单收下 ----------

    @Test
    void boundaryValuesAreAcceptedOnBothEnds() {
        for (QueryLimitsController.LimitField f : QueryLimitsController.FIELDS) {
            controller.update(request(Map.of(f.key(), f.min())));
            controller.update(request(Map.of(f.key(), f.max())));
        }
        // 上面 12 次 PUT 全部通过（没有抛异常即成立），逐次比对落库值
        ArgumentCaptor<QueryLimitsService.Limits> cap =
                ArgumentCaptor.forClass(QueryLimitsService.Limits.class);
        verify(service, org.mockito.Mockito.times(12)).update(anyString(), cap.capture(), anyString());
        List<QueryLimitsService.Limits> saved = cap.getAllValues();
        assertEquals(QueryLimitsController.FIELDS.size() * 2, saved.size());
        int i = 0;
        for (QueryLimitsController.LimitField f : QueryLimitsController.FIELDS) {
            assertEquals(f.min(), value(saved.get(i++), f));
            assertEquals(f.max(), value(saved.get(i++), f));
        }
    }

    private static int value(QueryLimitsService.Limits l, QueryLimitsController.LimitField f) {
        return switch (f.key()) {
            case "maxMemMb" -> l.maxMemMb();
            case "timeoutS" -> l.timeoutS();
            case "maxRows" -> l.maxRows();
            case "maxParallel" -> l.maxParallel();
            case "maxConcurrency" -> l.maxConcurrency();
            default -> l.dailyQueryLimit();
        };
    }

    // ---------- 3. null＝不改 ----------

    @Test
    void nullFieldsKeepCurrentValue() {
        controller.update(new QueryLimitsController.LimitsRequest(null, 30, null, null, null, null));

        QueryLimitsService.Limits saved = capturedUpdate();
        assertEquals(30, saved.timeoutS());
        assertEquals(CURRENT.maxMemMb(), saved.maxMemMb());
        assertEquals(CURRENT.maxRows(), saved.maxRows());
        assertEquals(CURRENT.maxParallel(), saved.maxParallel());
        assertEquals(CURRENT.maxConcurrency(), saved.maxConcurrency());
        assertEquals(CURRENT.dailyQueryLimit(), saved.dailyQueryLimit());
    }

    @Test
    void emptyOrNullBodyChangesNothing() {
        Map<String, Object> empty = controller.update(
                new QueryLimitsController.LimitsRequest(null, null, null, null, null, null));
        assertEquals(CURRENT, empty.get("limits"));

        Map<String, Object> nullBody = controller.update(null);
        assertEquals(CURRENT, nullBody.get("limits"));
    }

    /** 改完立刻回显新值，界面保存后靠这个重新渲染，不用再发一次 GET。 */
    @Test
    void updateResponseEchoesEffectiveLimits() {
        Map<String, Object> resp = controller.update(request(Map.of("maxRows", 500)));

        assertEquals(500, ((QueryLimitsService.Limits) resp.get("limits")).maxRows());
        assertEquals(CURRENT.maxConcurrency(),
                ((QueryLimitsService.Limits) resp.get("limits")).maxConcurrency());
    }

    // ---------- 4. GET 把范围一起给出去（界面不再抄常量） ----------

    @Test
    @SuppressWarnings("unchecked")
    void viewExposesFieldLabelsAndRanges() {
        Map<String, Object> resp = controller.view();

        List<Map<String, Object>> fields = (List<Map<String, Object>>) resp.get("fields");
        assertEquals(QueryLimitsController.FIELDS.size(), fields.size());
        Map<String, String> ranges = new LinkedHashMap<>();
        for (Map<String, Object> f : fields) {
            ranges.put((String) f.get("key"), f.get("min") + "-" + f.get("max"));
        }
        // 卡面上点名的三条范围：1-3600 秒 / 1-1000000 行 / 1-200 并发
        assertEquals("1-3600", ranges.get("timeoutS"));
        assertEquals("1-1000000", ranges.get("maxRows"));
        assertEquals("1-200", ranges.get("maxConcurrency"));
        assertEquals("128-65536", ranges.get("maxMemMb"));
        assertEquals("1-64", ranges.get("maxParallel"));
        assertEquals("0-1000000", ranges.get("dailyQueryLimit"));
        // 当前生效值、兜底默认值、实时并发都得在场，否则界面没法显示「生效值 / 默认值 / 在跑几条」
        assertEquals(CURRENT, resp.get("limits"));
        assertEquals(QueryLimitsService.DEFAULTS, resp.get("defaults"));
        assertEquals(0, resp.get("inflight"));
    }

    /**
     * dailyQueryLimit 没有消费点（存了不生效），必须在契约里标出来；
     * 界面靠这个标记显示「未生效」，不能让人以为改了就有用。
     */
    @Test
    @SuppressWarnings("unchecked")
    void notEnforcedFieldIsMarkedInContract() {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) controller.view().get("fields");
        for (Map<String, Object> f : fields) {
            if ("dailyQueryLimit".equals(f.get("key"))) {
                assertEquals(false, f.get("enforced"), "该字段没有消费点，必须标未生效");
            } else {
                assertEquals(true, f.get("enforced"), f.get("key") + " 是真生效的，不该被标成未生效");
            }
        }
    }

    /** 校验区间与 Limits record 的字段不能各写各的：少一个字段就没法配。 */
    @Test
    void fieldsCoverExactlyTheLimitsRecord() {
        List<String> keys = QueryLimitsController.FIELDS.stream()
                .map(QueryLimitsController.LimitField::key).toList();
        assertEquals(List.of("maxMemMb", "timeoutS", "maxRows", "maxParallel",
                "maxConcurrency", "dailyQueryLimit"), keys);
    }
}
