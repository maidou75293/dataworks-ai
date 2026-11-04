package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.integration.SyncTaskController.BackfillBody;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D16 回归：补数日期必须严格解析。
 *
 * <p>旧实现 {@code ofPattern("y-M-d")}（ResolverStyle.SMART）会把 2026-02-30
 * 静默夹成 2026-02-28，按错误 bizdate 提交补数且无任何提示。
 * 修复后非法日历日期 → 400「补数日期不合法」，与质量规则/工作流日期入口同口径。</p>
 */
class SyncTaskBackfillDateTest {

    private static ApiException invalid(String raw) {
        ApiException e = assertThrows(ApiException.class,
                () -> SyncTaskController.resolveBackfillDates(
                        new BackfillBody(List.of(raw), null, null)));
        assertEquals(400, e.getStatus(), "应 400: " + raw);
        assertTrue(e.getMessage().startsWith("补数日期不合法"),
                raw + " 应报「补数日期不合法」，实际：" + e.getMessage());
        return e;
    }

    @Test
    void februaryThirtyIsRejectedNotClamped() {
        invalid("2026-02-30");
        invalid("2026-04-31");
        invalid("2025-02-29");   // 平年 2 月 29
        invalid("20260230");     // yyyyMMdd 紧凑写法同样拒绝
        invalid("2026-2-30");    // 单位数月/日也不放宽日历校验
    }

    @Test
    void invalidMonthDayStillRejected() {
        // 修复前只有月份非法会拦，日期非法被夹到月末
        invalid("2026-13-01");
        invalid("2026-12-32");
    }

    @Test
    void validDatesParseToExactDays() {
        List<LocalDate> dates = SyncTaskController.resolveBackfillDates(
                new BackfillBody(List.of("2026-02-28", "2028-02-29", "20260301", "2026-2-3"),
                        null, null));
        assertEquals(List.of(LocalDate.of(2026, 2, 3), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 1), LocalDate.of(2028, 2, 29)), dates);
    }

    @Test
    void rangeExpandsDayByDay() {
        List<LocalDate> dates = SyncTaskController.resolveBackfillDates(
                new BackfillBody(null, "2026-02-27", "2026-03-01"));
        assertEquals(3, dates.size());
        assertEquals(LocalDate.of(2026, 2, 27), dates.get(0));
        assertEquals(LocalDate.of(2026, 3, 1), dates.get(2));
    }

    @Test
    void noSilentClampingOfStartToEndGap() {
        // 修复前 2026-02-30→2026-03-10 与 2026-02-28→2026-03-10 得到同一个「11 天」错误
        // （夹成 02-28 后才算天数）；修复前先报「补数日期不合法」
        ApiException e = assertThrows(ApiException.class,
                () -> SyncTaskController.resolveBackfillDates(
                        new BackfillBody(null, "2026-02-30", "2026-03-10")));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("补数日期不合法"), e.getMessage());

        // 合法但超长的区间才走「最多 10 天」检查
        ApiException gap = assertThrows(ApiException.class,
                () -> SyncTaskController.resolveBackfillDates(
                        new BackfillBody(null, "2026-02-28", "2026-03-10")));
        assertEquals(400, gap.getStatus());
        assertTrue(gap.getMessage().contains("当前 11 天"), gap.getMessage());
    }

    @Test
    void reversedRangeRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> SyncTaskController.resolveBackfillDates(
                        new BackfillBody(null, "2026-03-05", "2026-03-01")));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("不能早于"), e.getMessage());
    }
}
