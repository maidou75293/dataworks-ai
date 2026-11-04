package com.lab.dataworks.usage;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC7-G4 计量口径单测（不起 Spring、不连库，锁契约 docs/测试-TC7-G4.md §五的纯逻辑面）。
 *
 * <p>仓库无 Mockito（见 pom），沿用 common/query 包「测包级静态方法」的样板。</p>
 */
class UsageMeteringTest {

    // -------------------------------------------------- 校验面（守卫 R05 的 400 本体）

    @Test
    void groupByAcceptsProjectAndTenant() {
        assertEquals("project", UsageMeteringService.checkGroupBy("project"));
        assertEquals("tenant", UsageMeteringService.checkGroupBy("Tenant"));
        assertEquals("project", UsageMeteringService.checkGroupBy(" project "));
    }

    @Test
    void groupByRejectsUnknownAndMissing() {
        ApiException unknown = assertThrows(ApiException.class,
                () -> UsageMeteringService.checkGroupBy("bogus"));
        assertEquals(400, unknown.getStatus());
        assertTrue(unknown.getMessage().contains("project"), "message 必须可读并指出合法值");
        assertThrows(ApiException.class, () -> UsageMeteringService.checkGroupBy(null));
        assertThrows(ApiException.class, () -> UsageMeteringService.checkGroupBy("  "));
    }

    @Test
    void dayParsesIsoOrRejectsWithReadableMessage() {
        assertEquals(LocalDate.of(2026, 10, 27),
                UsageMeteringService.checkDay("2026-10-27", "from"));
        assertEquals(LocalDate.of(2026, 10, 27),
                UsageMeteringService.checkDay(" 2026-10-27 ", "to"));
        ApiException bad = assertThrows(ApiException.class,
                () -> UsageMeteringService.checkDay("2026/10/27", "from"));
        assertEquals(400, bad.getStatus());
        assertTrue(bad.getMessage().contains("YYYY-MM-DD"));
        assertThrows(ApiException.class, () -> UsageMeteringService.checkDay(null, "to"));
    }

    @Test
    void windowRejectsInvertedButAllowsEmpty() {
        // from > to → 400（守卫 R05）
        assertThrows(ApiException.class, () -> UsageMeteringService.checkWindow(
                LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26)));
        // from == to 合法：闭开区间 [d, d) = 空窗（守卫 R04 依赖这一点判「真生效」）
        UsageMeteringService.checkWindow(
                LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 1));
    }

    // -------------------------------------------------- 行契约（守卫 R03 的 8 键）

    @Test
    void rowCarriesExactlyContractKeys() {
        Map<String, Object> r = UsageMeteringService.row(
                "demo", "演示项目", 0, 0, 0, 0, 0);
        assertEquals(UsageMeteringService.CONTRACT_KEYS, r.keySet(),
                "8 键多一键少一键都算违约（scannedRows 不入第一版）");
        assertEquals("demo", r.get("groupKey"));
        assertEquals(0L, r.get("queryCount"));
    }

    // -------------------------------------------------- share 口径（0–1 占比）

    @Test
    void shareOfZeroTotalIsZero() {
        assertEquals(0.0, UsageMeteringService.shareOf(0, 0));
        assertEquals(0.0, UsageMeteringService.shareOf(5, 0));
    }

    @Test
    void shareOfIsBoundedAndRounded() {
        assertEquals(0.5, UsageMeteringService.shareOf(1, 2));
        assertEquals(0.3333, UsageMeteringService.shareOf(1, 3));
        assertEquals(1.0, UsageMeteringService.shareOf(7, 7));
    }

    @Test
    void attachSharesUsesEventCountWeight() {
        List<Map<String, Object>> rows = List.of(
                UsageMeteringService.row("a", "A", 3, 999, 1, 50, 12345),
                UsageMeteringService.row("b", "B", 1, 0, 0, 0, 0));
        UsageMeteringService.attachShares(rows);
        // 权重 = queryCount + taskInstanceCount：a=4, b=1, total=5
        assertEquals(0.8, rows.get(0).get("share"));
        assertEquals(0.2, rows.get(1).get("share"));
    }

    @Test
    void attachSharesAllZeroWindowYieldsZeroShare() {
        // 空窗（守卫 R04 场景）：计数全 0 时 share 不抛、不给 NaN
        List<Map<String, Object>> rows = List.of(
                UsageMeteringService.row("a", "A", 0, 0, 0, 0, 0));
        UsageMeteringService.attachShares(rows);
        assertEquals(0.0, rows.get(0).get("share"));
    }

    // -------------------------------------------------- 存储行过滤（Java 侧收口口径）

    @Test
    void sumBytesFiltersCaseInsensitiveAndSkipsJunk() {
        // 含 null 元素只能用 Arrays.asList（List.of 直接 NPE）
        List<List<String>> rows = java.util.Arrays.asList(
                List.of("Demo_Dev", "1000"),
                List.of("demo_dev", "2000"),
                List.of("other_db", "999999"),
                java.util.Arrays.asList("demo_prd", null),
                List.of("demo_prd", "  "),
                List.of("demo_prd", "not_a_number"),
                List.of("demo_prd", "500"),
                null,
                List.of("single_column_only"));
        long total = UsageMeteringService.sumBytes(rows, Set.of("demo_dev", "demo_prd"));
        assertEquals(3500L, total, "只合计本项目的库；NULL/空/非数字按不计数");
    }

    @Test
    void sumBytesEmptyInputsYieldZero() {
        assertEquals(0L, UsageMeteringService.sumBytes(null, Set.of("x")));
        assertEquals(0L, UsageMeteringService.sumBytes(List.of(List.of("x", "1")), Set.of()));
    }
}
