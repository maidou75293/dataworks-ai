package com.lab.dataworks.query;

import com.lab.dataworks.auth.RequireAnyAtom;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-TC9-2 验收口径：限额脱敏只读面 {@code GET /api/query-limits/effective}。
 *
 * <p>锁住守卫（{@code scripts/e2e/req-tc9-2-query-limits-test.py}）R01–R06 能在
 * 单测层预检的部分：</p>
 * <ol>
 *   <li>权限声明是 {@code query:run}/{@code project:read} 任一满足，且**不得**挂
 *       {@code system:manage}（R01 的前提——挂错就等于没这个面）；</li>
 *   <li>响应只含 timeoutS/maxRows/maxConcurrency 三数字，其余限额项不外泄，
 *       且正文不含 jdbc/password/secret/token/host/内网 IP 特征（R05）；</li>
 *   <li>每次现取 {@code resolve}，管理面 PUT 后立刻读到新值——「读到的＝在生效的」（R04/R06）；</li>
 *   <li>projectId 按 id 直达路由口径校验归属（跨租户 404），不传则跳过校验（限额本就是租户级）。</li>
 * </ol>
 *
 * <p>纯 Mockito 单测（不起 Spring）；HTTP 层 401/403/404 与现网一致性由守卫脚本
 * 部署后复跑收口（期望 18 PASS / 0 FAIL）。</p>
 */
class QueryLimitsEffectiveTest {

    /** 刻意与 QueryLimitsService.DEFAULTS 不同，便于证明值来自 resolve 而非兜底。 */
    private static final QueryLimitsService.Limits LIVE =
            new QueryLimitsService.Limits(2048, 120, 50000, 8, 10, 300);

    /** 守卫 R05 同款泄露特征。 */
    private static final Pattern LEAK =
            Pattern.compile("jdbc|password|secret|token|host\\b|10\\.10\\.", Pattern.CASE_INSENSITIVE);

    private final QueryLimitsService service = mock(QueryLimitsService.class);
    private final TenantGuard guard = mock(TenantGuard.class);
    private final QueryLimitsController controller = new QueryLimitsController(service, guard);

    // ---------- 1. 权限声明（R01 前提） ----------

    @Test
    void effectiveAllowsQueryRunOrProjectReadAndNeverSystemManage() throws Exception {
        Method m = QueryLimitsController.class
                .getMethod("effective", Long.class);
        RequireAtom atom = m.getAnnotation(RequireAtom.class);
        assertFalse(atom != null, "effective 不得挂 all-of 语义的 @RequireAtom");
        RequireAnyAtom any = m.getAnnotation(RequireAnyAtom.class);
        assertNotNull(any, "effective 必须挂 @RequireAnyAtom");
        Set<String> granted = Set.of(any.value());
        assertTrue(granted.contains("query:run") && granted.contains("project:read"),
                "权限点必须是 query:run / project:read 任一，实得=" + granted);
        assertFalse(granted.contains("system:manage"), "不得要求 system:manage");
    }

    // ---------- 2. 三数字与脱敏（R01/R05） ----------

    @Test
    void effectiveReturnsExactlyTheThreeNumbers() {
        when(service.resolve(anyString())).thenReturn(LIVE);
        Map<String, Object> out = controller.effective(1L);
        assertEquals(Set.of("timeoutS", "maxRows", "maxConcurrency"), out.keySet(),
                "只许回三个数字，其余限额项（maxMemMb/maxParallel/dailyQueryLimit）不外泄");
        assertEquals(120, out.get("timeoutS"));
        assertEquals(50000, out.get("maxRows"));
        assertEquals(10, out.get("maxConcurrency"));
    }

    @Test
    void effectiveBodyCarriesNoConnectionDetails() {
        when(service.resolve(anyString())).thenReturn(LIVE);
        String body = controller.effective(1L).toString();
        assertFalse(LEAK.matcher(body).find(), "可读面正文命中泄露特征: " + body);
    }

    // ---------- 3. 即时跟随，不缓存（R04/R06） ----------

    @Test
    void effectiveReflectsPutImmediatelyWithoutCaching() {
        QueryLimitsService.Limits afterPut =
                new QueryLimitsService.Limits(2048, 10, 50000, 8, 10, 300);
        // 模拟守卫 T02/R06：管理面把 timeoutS 改 10 后，可读面立刻读到 10
        when(service.resolve(anyString())).thenReturn(LIVE, afterPut, LIVE);
        assertEquals(120, controller.effective(1L).get("timeoutS"));
        assertEquals(10, controller.effective(1L).get("timeoutS"));
        assertEquals(120, controller.effective(1L).get("timeoutS"));
        verify(service, org.mockito.Mockito.times(3)).resolve(anyString());
    }

    // ---------- 4. projectId 归属校验（by-id 口径） ----------

    @Test
    void effectiveValidatesProjectOwnershipWhenProjectIdGiven() {
        when(service.resolve(anyString())).thenReturn(LIVE);
        controller.effective(7L);
        verify(guard).requireProject(7L);
    }

    @Test
    void effectiveForeignProjectIsRejectedAsNotFound() {
        doThrow(ApiException.notFound("项目不存在: 999"))
                .when(guard).requireProject(anyLong());
        ApiException e = assertThrows(ApiException.class, () -> controller.effective(999L));
        assertEquals(404, e.getStatus());
    }

    @Test
    void effectiveWithoutProjectIdStillReturnsTenantNumbersAndSkipsGuard() {
        when(service.resolve(anyString())).thenReturn(LIVE);
        Map<String, Object> out = controller.effective(null);
        assertEquals(120, out.get("timeoutS"));
        verify(guard, never()).requireProject(anyLong());
    }
}
