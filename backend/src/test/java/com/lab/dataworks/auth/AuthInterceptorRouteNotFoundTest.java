package com.lab.dataworks.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-TC6-7：路由不存在必须是 404，不能一律报 403「接口未授权（缺少权限声明）」。
 *
 * <p>Spring 把未映射的 {@code /api/**} 交给静态资源处理器（{@code ResourceHttpRequestHandler}，
 * 不是 {@code HandlerMethod}）；拦截器的兜底分支以前把「没有权限注解」当成「没有权限」，
 * 于是同一个事实（路由缺失）按请求方法回出 403 / 404 两种口径。现在无映射直接放行，
 * 由资源处理器抛 {@code NoResourceFoundException} → {@code GlobalExceptionHandler} 回 404。</p>
 *
 * <p>反向用例同样重要：**已映射**的接口漏标权限注解时仍要按默认拒绝（403），
 * 否则这次修复会把「新增写接口裸奔」的防线一起拆掉。</p>
 */
class AuthInterceptorRouteNotFoundTest {

    private static final String TOKEN = "session-token";

    private SessionService sessions;
    private PermissionResolver permissions;
    private AuthInterceptor interceptor;

    /** 已映射但没有任何权限注解的控制器方法：模拟「新写接口忘了标注」。 */
    static class UnguardedController {
        public void write() {
        }
    }

    /** 已映射且声明了权限点的控制器方法：验证 403 分支没被影响。 */
    static class GuardedController {
        @RequireAtom({"change:approve"})
        public void approve() {
        }
    }

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        UserService users = mock(UserService.class);
        permissions = mock(PermissionResolver.class);
        interceptor = new AuthInterceptor(sessions, users, new ObjectMapper(), permissions,
                false, new InternalTokenService(TOKEN, "", 5000));

        when(sessions.validate(TOKEN)).thenReturn(new SessionService.Session("u_bug_t1", "default"));
        when(users.find("u_bug_t1")).thenReturn(new UserService.Account(
                1L, "u_bug_t1", "TC6 测试账号", "DEVELOPER", "ACTIVE", "LOCAL", false, null, null));
        when(permissions.resolveTenant("u_bug_t1", "default")).thenReturn("default");
        when(permissions.atoms("u_bug_t1", "default")).thenReturn(Set.of("query:run"));
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    private MockHttpServletRequest request(String method, String path, boolean withToken) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (withToken) {
            request.addHeader("Authorization", "Bearer " + TOKEN);
        }
        return request;
    }

    private HandlerMethod handler(Object controller, String method) throws Exception {
        return new HandlerMethod(controller, controller.getClass().getMethod(method));
    }

    @Test
    void unmappedPostPathIsNotReportedAs403() throws Exception {
        // 卡面原例：POST /api/changes/{id}/execute 并不存在，以前回 403「缺少权限声明」
        MockHttpServletRequest request = request("POST", "/api/changes/42/execute", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new ResourceHttpRequestHandler());

        assertTrue(allowed, "无映射路径应放行给资源处理器，由其抛 NoResourceFoundException → 404");
        assertEquals(200, response.getStatus(), "拦截器不得写成 403");
        assertEquals("", response.getContentAsString(), "拦截器不应写错误体");
    }

    @Test
    void unmappedGetPathPassesThroughToo() throws Exception {
        // GET 以前只是因为默认 READ 权限点通过才碰巧走到 404，口径与 POST 不一致
        MockHttpServletRequest request = request("GET", "/api/flink/jobs/7/versions", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new ResourceHttpRequestHandler()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void unmappedPathDoesNotRequireLogin() throws Exception {
        // 路由存在性由 handler 映射决定，先于身份：没有 token 的重放请求也返回同一个 404，
        // 不会因为「拦截器先鉴权」把 404 又变回 401（Spring 对非 /api 路径本来就是 404）。
        MockHttpServletRequest request = request("POST", "/api/changes/42/execute", false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new ResourceHttpRequestHandler()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void mappedEndpointWithoutAnnotationIsStillDenied() throws Exception {
        // 反向卡口：有映射但漏标权限注解的写接口必须继续默认拒绝
        MockHttpServletRequest request = request("POST", "/api/some/write", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response,
                handler(new UnguardedController(), "write"));

        assertFalse(allowed, "已映射但未声明权限的接口不能被放行");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("接口未授权（缺少权限声明）"),
                "默认拒绝的文案/语义保持不变");
    }

    @Test
    void mappedEndpointMissingAtomStillReturns403WithRequiredAtoms() throws Exception {
        // 有映射 + 有权限点声明：缺权限点仍是 403 + requiredAtoms，不能被改成 404
        MockHttpServletRequest request = request("POST", "/api/changes/9/approve", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response,
                handler(new GuardedController(), "approve"));

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("requiredAtoms"), "缺权限点必须回 requiredAtoms，便于前端指引: " + body);
        assertTrue(body.contains("change:approve"), body);
    }
}
