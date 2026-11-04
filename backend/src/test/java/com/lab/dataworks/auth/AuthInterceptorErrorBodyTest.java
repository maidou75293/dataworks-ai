package com.lab.dataworks.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.lab.dataworks.common.RequestSizeLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D65（TC-5 用例 T5-GEN-03 vs T5-GEN-04）：未知接口路径的状态码与错误体必须同形。
 *
 * <p>缺陷现场（v539 live）：同一个<b>不存在</b>的路径，
 * {@code GET /api/zz-not-exist} → 404「接口不存在」（正确），
 * {@code POST /api/zz-not-exist} → 403「接口未授权（缺少权限声明）」——
 * 因为拦截器的兜底是「非 GET 且没声明权限一律拒绝」，这条判定发生在 Spring 路由<b>之前</b>，
 * 不存在的 POST 路径根本走不到 {@code NoHandlerFound → 404} 那条通道。
 * 把「URL 写错了」说成「你没权限」，调用方会去查授权而不是查地址。</p>
 *
 * <p>另一半：拦截器（401/403）与请求体上限过滤器（413）自己直写 {@code {status,message}} 两键体，
 * 与 {@code GlobalExceptionHandler} 的 {@code {timestamp,status,message}} 三键契约不同形。</p>
 */
class AuthInterceptorErrorBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionService sessions;
    private PermissionResolver permissions;
    private AuthInterceptor interceptor;

    /** 没有权限注解的写接口（模拟「存在但忘了标注」）。 */
    static class PlainController {
        public Map<String, Object> write() {
            return Map.of();
        }
    }

    /** 带 @RequireAtom 但当前账号没有该权限点的接口（触发 denyAtom 分支）。 */
    static class AnnotatedController {
        @RequireAtom("table:ddl")
        public Map<String, Object> ddl() {
            return Map.of();
        }
    }

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        UserService users = mock(UserService.class);
        permissions = mock(PermissionResolver.class);
        interceptor = new AuthInterceptor(sessions, users, MAPPER, permissions, false,
                mock(InternalTokenService.class));
        when(sessions.validate("tok")).thenReturn(new SessionService.Session("alice", "default"));
        when(users.find("alice")).thenReturn(new UserService.Account(
                1L, "alice", "Alice", "DEVELOPER", "ACTIVE", "local", false, null, null));
        when(permissions.resolveTenant(anyString(), anyString())).thenReturn("default");
        when(permissions.atoms(anyString(), anyString())).thenReturn(Set.of("query:run"));
    }

    private static MockHttpServletRequest authed(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer tok");
        return request;
    }

    private static HandlerMethod handlerOf(Class<?> type, String methodName) throws Exception {
        Object bean = type.getDeclaredConstructor().newInstance();
        Method method = type.getMethod(methodName);
        return new HandlerMethod(bean, method);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        return MAPPER.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private static void assertContract(MockHttpServletResponse response, int expectStatus)
            throws Exception {
        Map<String, Object> json = body(response);
        assertEquals(expectStatus, json.get("status"), String.valueOf(json));
        assertNotNull(json.get("timestamp"), "错误体必须有 timestamp（三键契约）: " + json);
        assertNotNull(json.get("message"), String.valueOf(json));
        assertTrue(((String) json.get("timestamp")).length() > 10, String.valueOf(json));
        // 键顺序与集合都要与 GlobalExceptionHandler 一致（客户端按同一份契约解析）
        List<String> keys = new ArrayList<>(json.keySet());
        assertTrue(keys.indexOf("timestamp") == 0, "timestamp 必须是第一个键（与路由层同形）: " + keys);
        assertEquals("status", keys.get(1));
        assertEquals("message", keys.get(2));
    }

    // ---------------------------------------------------------------- 未知路径 → 交给路由层

    /**
     * handler 不是 HandlerMethod（= 这个路径没有对应的控制器方法）时，
     * 拦截器<b>不判权限</b>也不写任何响应，放行给 Spring → 404「接口不存在」，与 GET 同口径。
     */
    @Test
    void unmappedPathFallsThroughToRouterInsteadOfForbidden() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(authed("POST", "/api/zz-not-exist-path"),
                response, new ResourceHttpRequestHandler());

        assertTrue(passed, "未知路径必须放行给路由层（由它回 404），不能在拦截器里判成 403");
        assertEquals(200, response.getStatus(), "拦截器不该改状态码");
        assertEquals("", response.getContentAsString(), "拦截器不该写错误体");
    }

    /** 兜底放行<b>不能</b>绕过登录：未认证的未知路径依旧 401，不给匿名探路。 */
    @Test
    void fallthroughDoesNotSkipAuthentication() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/zz-not-exist-path"),
                response, new ResourceHttpRequestHandler());

        assertFalse(passed, "未登录仍要被拦下（401）；未知路径的放行只针对已认证请求");
        assertEquals(401, response.getStatus());
        assertContract(response, 401);
    }

    // ---------------------------------------------------------------- 真实接口该拒还得拒

    /** 存在但没声明权限的写接口：默认拒绝（这条不能因为 D65 被一起放掉）。 */
    @Test
    void mappedPostWithoutPermissionDeclarationStillDenied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(authed("POST", "/api/plain/write"),
                response, handlerOf(PlainController.class, "write"));

        assertFalse(passed);
        assertEquals(403, response.getStatus());
        assertContract(response, 403);
    }

    /** 缺权限点的 403：除三键外仍要带 requiredAtoms（TC-3/C 面靠它点名权限点）。 */
    @Test
    void missingAtomKeepsRequiredAtomsAndGainsTimestamp() throws Exception {
        when(permissions.atoms(anyString(), anyString())).thenReturn(Set.of());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(authed("POST", "/api/annotated/ddl"),
                response, handlerOf(AnnotatedController.class, "ddl"));

        assertFalse(passed);
        assertEquals(403, response.getStatus());
        assertContract(response, 403);
        Map<String, Object> json = body(response);
        assertEquals("无权限执行该操作（缺少权限点：table:ddl）", json.get("message"));
        assertEquals(List.of("table:ddl"), json.get("requiredAtoms"));
    }

    // ---------------------------------------------------------------- 413 同形

    /** 请求体上限（非上传路径 2MB）：原来手拼两键 JSON，现在同一份构造。 */
    @Test
    void oversizedRequestBodyErrorBodyHasSameThreeKeys() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/query/doris");
        // 过滤器只看 Content-Length（不读 body），塞 3MB 空内容把长度顶过 2MB 上限
        request.setContent(new byte[3 * 1024 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestSizeLimitFilter().doFilter(request, response, new MockFilterChain());

        assertEquals(413, response.getStatus());
        assertContract(response, 413);
        assertTrue(((String) body(response).get("message")).contains("2MB"),
                "文案要给出限额: " + body(response));
    }

    /** 契约本身：三键由 GlobalExceptionHandler 唯一构造，拦截器/过滤器都用它。 */
    @Test
    void errorBodyIsTheSingleConstructorOfTheContract() {
        Map<String, Object> json = GlobalExceptionHandler.errorBody(404, "接口不存在");

        assertEquals(List.of("timestamp", "status", "message"),
                new ArrayList<>(json.keySet()));
        assertEquals(404, json.get("status"));
        assertEquals("接口不存在", json.get("message"));
    }
}
