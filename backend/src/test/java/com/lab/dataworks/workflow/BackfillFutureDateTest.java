package com.lab.dataworks.workflow;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D30 回归：补数不接受未来业务日期。
 *
 * <p>缺陷现场（docs/功能缺陷清单-20261026.md D30）：
 * {@code POST /api/workflows/257/backfill {"startDate":"2027-05-01","endDate":"2027-05-01"}}
 * 返回 200 并当场建出 2027-05-01 的实例执行。反向区间 / 非法日期 / 超一年都有 400，
 * 唯独未来日期漏了。修复后：结束日期（或起始日期）晚于今天 → 400。</p>
 *
 * <p>口径：今天允许（与手工触发默认业务日期 = 今天一致），今天 +1 天起拒绝。</p>
 */
class BackfillFutureDateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private static ApiException rejected(LocalDate start, LocalDate end) {
        ApiException e = assertThrows(ApiException.class,
                () -> BackfillService.requireBizDatesNotFuture(start, end, TODAY));
        assertEquals(400, e.getStatus(), "未来日期应 400: " + start + ".." + end);
        return e;
    }

    @Test
    void defectReproSingleFutureDayIsRejected() {
        // 缺陷原文两个用例：2027-05-01 与 2027-06-01 单日补数
        ApiException e = rejected(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 1));
        assertTrue(e.getMessage().contains("不能晚于今天"), e.getMessage());
        rejected(LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 1));
    }

    @Test
    void messageNamesTheBoundaryAndTheReceivedRange() {
        // 只说「日期非法」用户不知道能填到哪一天：文案必须带今天与收到的区间
        ApiException e = rejected(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27));
        assertTrue(e.getMessage().contains(TODAY.toString()),
                "应给出允许边界（今天）: " + e.getMessage());
        assertTrue(e.getMessage().contains("2026-10-27"),
                "应回显收到的结束日期: " + e.getMessage());
    }

    @Test
    void futureStartWithPastEndIsRejectedToo() {
        // 反向写法（起未来、止未来更早）也不能绕过：两端各查一次
        rejected(TODAY.plusDays(1), TODAY.plusDays(5));
        rejected(TODAY.plusDays(3), TODAY.plusDays(3));
    }

    @Test
    void nextDayIsTheBoundaryAndFails() {
        // 边界：今天 +1 天就拒，不是「差几周才算未来」
        rejected(TODAY.plusDays(1), TODAY.plusDays(1));
    }

    @Test
    void todayAndPastRangesStayAccepted() {
        // 正向对照：补到今天、补昨天、补一整月都不受影响（修复不能把补数整体打死）
        assertDoesNotThrow(() -> BackfillService.requireBizDatesNotFuture(TODAY, TODAY, TODAY));
        assertDoesNotThrow(() -> BackfillService.requireBizDatesNotFuture(
                TODAY.minusDays(1), TODAY, TODAY));
        assertDoesNotThrow(() -> BackfillService.requireBizDatesNotFuture(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), TODAY));
        // 跨年历史区间（缺陷里那种 2025 的补数）不受影响
        assertDoesNotThrow(() -> BackfillService.requireBizDatesNotFuture(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), TODAY));
    }
}
