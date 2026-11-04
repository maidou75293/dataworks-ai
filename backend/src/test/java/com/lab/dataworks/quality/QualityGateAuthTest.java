package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.UserService;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D49：{@code POST /api/quality/gate} 的鉴权分支。
 *
 * <p>这个端点的路径在 {@code AuthInterceptor.preHandle} 的**会话豁免**分支里就 return true 了
 * （Prefect Flow 用内部凭证调，没有会话），注解那一段代码根本走不到。
 * 所以权限判定全部收在方法内的 {@code authorizeGate}：
 * 卡面要求「补一个 @RequireAtom」是不成立的——补上去只会得到一个谁都不会拦的装饰。</p>
 *
 * <p>这里把四条分支各钉一条用例，撤掉方法内的判定就会红：</p>
 * <ol>
 *   <li>无凭证 → 401；</li>
 *   <li>账号不可用 / 不属于该租户 → 403；</li>
 *   <li>有会话但没有 {@code quality:run} → 403，且报错点名需要的权限点；</li>
 *   <li>有 {@code quality:run} → 按**该用户租户**执行规则（不是全局、不是默认租户）。</li>
 * </ol>
 *
 * <p>另外钉住 gate 的三种结论码，避免「规则没跑起来」被当成「数据不达标」：
 * 达标 200、不达标 409、执行失败 502。</p>
 */
class QualityGateAuthTest {

    private static final String BIZ = "2026-10-26";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final QualityService quality = mock(QualityService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final UserService users = mock(UserService.class);
    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private final InternalTokenService internalTokens = mock(InternalTokenService.class);
    // CONFLICT-WM1：合流后 QualityController 多了第 8 个依赖（BUG-TC4-4 落点闸）。
    // 本卡只测 /gate 的鉴权路径，不触达 environments，mock 空实例即可。
    private final QualityController controller = new QualityController(
            jdbc, quality, new ObjectMapper(), sessions, users, permissions, internalTokens,
            mock(com.lab.dataworks.project.ProjectEnvironmentService.class));

    private static MockHttpServletRequest request(String bearer, String tenantHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/quality/gate");
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        if (tenantHeader != null) {
            request.addHeader("X-Tenant-Id", tenantHeader);
        }
        return request;
    }

    private static UserService.Account account(String status) {
        return new UserService.Account(7, "qa_user", "质量值班", "OPERATOR", status,
                "local", false, null, null);
    }

    private void givenSessionWithAtoms(Set<String> atoms) {
        when(internalTokens.configured()).thenReturn(false);
        when(sessions.validate("t1")).thenReturn(new SessionService.Session("qa_user", "default"));
        when(users.find("qa_user")).thenReturn(account("ACTIVE"));
        when(permissions.resolveTenant(eq("qa_user"), any())).thenReturn("tenant_a");
        when(permissions.atoms("qa_user", "tenant_a")).thenReturn(atoms);
    }

    private Map<String, Object> evaluation(boolean passed, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleCode", "dwd_order_rowcnt");
        m.put("passed", passed);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    private ApiException gate(String headerToken, MockHttpServletRequest request, String ruleCode) {
        return assertThrows(ApiException.class, () -> controller.gate(
                new QualityController.GateRequest(ruleCode, BIZ), headerToken, request));
    }

    // ---------- 鉴权 ----------

    @Test
    void noCredentialIsRejectedWith401() {
        when(internalTokens.configured()).thenReturn(false);
        when(sessions.validate(null)).thenReturn(null);

        ApiException e = gate(null, request(null, null), "dwd_order_rowcnt");

        assertEquals(401, e.getStatus());
        assertTrue(e.getMessage().contains("未登录"), e.getMessage());
        // 鉴权没过就不该去执行规则
        verify(quality, never()).evaluateByCode(anyString(), any(), any());
    }

    /** 内部凭证（Flow 调用）走全局语义：tenant=null，且不去查会话。 */
    @Test
    void internalTokenBypassesSessionAndIsNotTenantScoped() {
        when(internalTokens.configured()).thenReturn(true);
        when(internalTokens.matches("flow-token")).thenReturn(true);
        when(quality.evaluateByCode(eq("dwd_order_rowcnt"), any(LocalDate.class), eq(null)))
                .thenReturn(evaluation(true, "OK", "row_count=25, min=1"));

        Map<String, Object> out = controller.gate(
                new QualityController.GateRequest("dwd_order_rowcnt", BIZ), "flow-token",
                request(null, null));

        assertEquals(true, out.get("passed"));
        verify(sessions, never()).validate(any());
    }

    /** 内部凭证开关开着但 token 不对：不能当成会话放行。 */
    @Test
    void wrongInternalTokenFallsThroughToSessionAndIs401() {
        when(internalTokens.configured()).thenReturn(true);
        when(internalTokens.matches("bogus")).thenReturn(false);
        when(sessions.validate(null)).thenReturn(null);

        ApiException e = gate("bogus", request(null, null), "dwd_order_rowcnt");

        assertEquals(401, e.getStatus());
        verify(quality, never()).evaluateByCode(anyString(), any(), any());
    }

    @Test
    void disabledAccountIsRejectedWith403() {
        when(internalTokens.configured()).thenReturn(false);
        when(sessions.validate("t1")).thenReturn(new SessionService.Session("qa_user", "default"));
        when(users.find("qa_user")).thenReturn(account("DISABLED"));

        ApiException e = gate(null, request("t1", null), "dwd_order_rowcnt");

        assertEquals(403, e.getStatus());
        assertTrue(e.getMessage().contains("账号不可用"), e.getMessage());
    }

    @Test
    void userNotBelongingToTenantIsRejectedWith403() {
        when(internalTokens.configured()).thenReturn(false);
        when(sessions.validate("t1")).thenReturn(new SessionService.Session("qa_user", "default"));
        when(users.find("qa_user")).thenReturn(account("ACTIVE"));
        when(permissions.resolveTenant(eq("qa_user"), any())).thenReturn(null);

        ApiException e = gate(null, request("t1", "tenant_x"), "dwd_order_rowcnt");

        assertEquals(403, e.getStatus());
        assertTrue(e.getMessage().contains("不属于该租户"), e.getMessage());
    }

    /** 核心一条：有会话但没 quality:run → 403，并把需要的权限点写在报错里。 */
    @Test
    void sessionWithoutQualityRunAtomIsRejectedWith403() {
        givenSessionWithAtoms(Set.of("quality:read", "query:run"));

        ApiException e = gate(null, request("t1", null), "dwd_order_rowcnt");

        assertEquals(403, e.getStatus());
        assertTrue(e.getMessage().contains(QualityController.GATE_ATOM),
                "报错要点名需要的权限点，实际：" + e.getMessage());
        verify(quality, never()).evaluateByCode(anyString(), any(), any());
    }

    /** 有权限时按调用者租户执行；内部凭证才是全局（这条挡住「租户过滤被删掉」的回归）。 */
    @Test
    void sessionCallerIsScopedToItsOwnTenant() {
        givenSessionWithAtoms(Set.of("quality:run"));
        when(quality.evaluateByCode(eq("dwd_order_rowcnt"), any(LocalDate.class), eq("tenant_a")))
                .thenReturn(evaluation(true, "OK", "row_count=25, min=1"));

        Map<String, Object> out = controller.gate(
                new QualityController.GateRequest("dwd_order_rowcnt", BIZ), null,
                request("t1", null));

        assertEquals(true, out.get("passed"));
        verify(permissions).atoms("qa_user", "tenant_a");
    }

    /** 判定顺序：先鉴权再校验参数（v449 P2-7），否则无凭证方能拿到 400 探接口形状。 */
    @Test
    void blankRuleCodeIs400OnlyAfterAuth() {
        givenSessionWithAtoms(Set.of("quality:run"));

        ApiException e = gate(null, request("t1", null), "  ");

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("ruleCode"), e.getMessage());
    }

    @Test
    void unauthenticatedBlankRuleCodeIsStill401() {
        when(internalTokens.configured()).thenReturn(false);
        when(sessions.validate(null)).thenReturn(null);

        ApiException e = gate(null, request(null, null), "  ");

        assertEquals(401, e.getStatus());
    }

    // ---------- 结论码 ----------

    @Test
    void notPassedIs409WithTheEvidence() {
        givenSessionWithAtoms(Set.of("quality:run"));
        when(quality.evaluateByCode(eq("dwd_order_rowcnt"), any(LocalDate.class), eq("tenant_a")))
                .thenReturn(evaluation(false, "OK", "row_count=0, min=1"));

        ApiException e = gate(null, request("t1", null), "dwd_order_rowcnt");

        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("质量闸门未通过"), e.getMessage());
        assertTrue(e.getMessage().contains("row_count=0, min=1"),
                "拒绝必须带上实测值与阈值，否则没人知道为什么被拦：" + e.getMessage());
    }

    /**
     * 规则自己跑挂了（数据源不可达/SQL 报错）不是「数据不达标」，要 422 分开口径。
     *
     * <p>D56 起状态码从 502 改为 422：502 会被网关/调用方当成自己的错重试，
     * 且旧响应把数据库原文（含 SQL/连接信息）直接回显。本断言曾滞后于主代码
     * （TC7-G1 / REQ-DW-7 两泳道 clean 复跑各自暴露），现与 {@code QualityController.gate} 口径对齐。</p>
     */
    @Test
    void evaluationErrorIs422Not409() {
        givenSessionWithAtoms(Set.of("quality:run"));
        when(quality.evaluateByCode(eq("dwd_order_rowcnt"), any(LocalDate.class), eq("tenant_a")))
                .thenReturn(evaluation(false, "ERROR", "执行失败: connection refused"));

        ApiException e = gate(null, request("t1", null), "dwd_order_rowcnt");

        assertEquals(422, e.getStatus());
        assertTrue(e.getMessage().contains("执行失败"), e.getMessage());
    }
}
