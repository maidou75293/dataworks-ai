package com.lab.dataworks.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 内部接口鉴权：Prometheus / Alertmanager 只能用 Authorization: Bearer，
 * 拦截器必须两种凭证都接受，否则 dw_* 指标与系统告警全链路 401。
 */
class AuthInterceptorInternalTest {

    private static final String TOKEN = "internal-token-for-test";

    private AuthInterceptor interceptor() {
        return new AuthInterceptor(mock(SessionService.class), mock(UserService.class),
                new ObjectMapper(), mock(PermissionResolver.class), false,
                new InternalTokenService(TOKEN, "", 5000));
    }

    private boolean check(AuthInterceptor interceptor, String header, String value) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/metrics/k8s");
        if (header != null) {
            request.addHeader(header, value);
        }
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void acceptsBearerTokenForPrometheus() throws Exception {
        assertTrue(check(interceptor(), "Authorization", "Bearer " + TOKEN));
    }

    @Test
    void stillAcceptsXInternalToken() throws Exception {
        assertTrue(check(interceptor(), "X-Internal-Token", TOKEN));
    }

    @Test
    void rejectsWrongBearerAndMissingCredentials() throws Exception {
        AuthInterceptor interceptor = interceptor();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/alerts/alertmanager");
        request.addHeader("Authorization", "Bearer wrong-token");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());

        assertFalse(check(interceptor(), null, null));
    }
}
