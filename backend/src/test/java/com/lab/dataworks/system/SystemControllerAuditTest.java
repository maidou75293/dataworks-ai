package com.lab.dataworks.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S12 + S13：运维级审计（删服务 / 驱逐节点 / DROP BACKEND）。
 *
 * <p>S12：{@code SystemController} 以前用自己的私有 helper 直接
 * {@code insert into platform.audit_log (username, action, target, detail)}——
 * 不写 {@code tenant_id}，而安全中心按 {@code (? or tenant_id = ?)} 过滤，
 * {@code NULL = ?} 永远为假，租户管理员于是<b>永远</b>看不到这些记录。
 * 现在统一走 {@link SessionService}（带租户 / IP）。</p>
 *
 * <p>S13：同一个 helper 还是「动作做完再记」且 {@code catch} 后只 {@code log.warn}，
 * 动作抛异常就一条都不留。现在先记（PENDING）再做，失败回填 {@code FAILED}。</p>
 */
class SystemControllerAuditTest {

    private JdbcTemplate jdbc;
    private ServiceResourceService services;
    private K8sNodeService k8sNodes;
    private DorisNodeService dorisNodes;
    private SystemController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // 真的 SessionService + 假 JdbcTemplate：这样能验到「最终落库的 SQL 与参数」，
        // 而不是只验控制器调了哪个方法
        SessionService sessions = new SessionService(jdbc, 12);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        services = mock(ServiceResourceService.class);
        k8sNodes = mock(K8sNodeService.class);
        dorisNodes = mock(DorisNodeService.class);
        controller = new SystemController(mock(SystemMonitorService.class),
                mock(K8sSnapshotService.class), services, dorisNodes, k8sNodes,
                mock(K8sOpsService.class), mock(RightsizingService.class),
                jdbc, new ObjectMapper(), sessions, mock(InternalTokenService.class));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "tenant_admin");
        http.setRemoteAddr("10.99.99.11");
        return http;
    }

    /** 取出先记那条 PENDING 审计的参数。 */
    private List<Object> pendingArgs() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), args.capture());
        assertTrue(sql.getValue().contains("tenant_id"),
                "S12：运维审计必须带 tenant_id，否则租户管理员看不到: " + sql.getValue());
        return List.of(args.getValue());
    }

    @Test
    void deleteServiceAuditCarriesTenantAndIp() {
        controller.deleteService("dw-dev", "deployment", "doris-fe", request());

        List<Object> bound = pendingArgs();
        assertEquals("tenant_admin", bound.get(0));
        assertEquals("SERVICE_DELETE", bound.get(1));
        assertEquals("doris-fe", bound.get(2));
        assertEquals("PENDING", bound.get(4));
        assertEquals("10.99.99.11", bound.get(5), "S12：旧 helper 连 ip 都没写");
        assertEquals("default", bound.get(6), "tenant_id 不能是 null");
    }

    @Test
    void deleteServiceSettlesSuccessAndKeepsResponseShape() {
        Map<String, Object> out = controller.deleteService("dw-dev", "deployment", "doris-fe", request());

        assertEquals(true, out.get("ok"));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update platform.audit_log"), args.capture());
        assertEquals("SUCCESS", List.of(args.getValue()).get(0));
    }

    /** S13 的核心：k8s 侧报错时，审计不能只是 log.warn，要留下一条 FAILED。 */
    @Test
    void failedOpIsAuditedAsFailedAndErrorStillPropagates() {
        org.mockito.Mockito.doThrow(new IllegalStateException("k8s api 不可达"))
                .when(services).delete("dw-dev", "deployment", "boom");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> controller.deleteService("dw-dev", "deployment", "boom", request()));
        assertTrue(e.getMessage().contains("k8s api 不可达"), "底层错误不能被改掉");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update platform.audit_log"), args.capture());
        List<Object> bound = List.of(args.getValue());
        assertEquals("FAILED", bound.get(0));
        assertTrue(String.valueOf(bound.get(1)).contains("失败："), String.valueOf(bound.get(1)));
        assertTrue(String.valueOf(bound.get(1)).contains("k8s api 不可达"), String.valueOf(bound.get(1)));
    }

    @Test
    void rejectedKindIsNotAuditedAtAll() {
        // 参数校验在动作之前：连 PENDING 都不该写，避免审计被无效请求灌满
        assertThrows(ApiException.class,
                () -> controller.deleteService("dw-dev", "pod", "x", request()));
        verify(jdbc, org.mockito.Mockito.never())
                .queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void drainAuditsOutcomeAfterTheAction() {
        when(k8sNodes.drain("kNode1", false)).thenReturn(Map.of("pods", List.of("a", "b")));

        Map<String, Object> out = controller.drainNode("kNode1", null, request());

        assertEquals(List.of("a", "b"), out.get("pods"));
        List<Object> bound = pendingArgs();
        assertEquals("NODE_DRAIN", bound.get(1));
        assertEquals("kNode1", bound.get(2));
        assertEquals("dryRun=false", bound.get(3), "先记时只有请求参数，pods 清单在终态里补");

        ArgumentCaptor<Object[]> settle = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update platform.audit_log"), settle.capture());
        List<Object> done = List.of(settle.getValue());
        assertEquals("SUCCESS", done.get(0));
        assertTrue(String.valueOf(done.get(1)).contains("pods=[a, b]"), String.valueOf(done.get(1)));
    }

    @Test
    void nodeRemoveIsAuditedWithTenant() {
        when(k8sNodes.remove("kNode2")).thenReturn(Map.of("ok", true));

        controller.removeNode("kNode2", request());

        List<Object> bound = pendingArgs();
        assertEquals("NODE_REMOVE", bound.get(1));
        assertEquals("kNode2", bound.get(2));
        assertTrue(bound.get(6) != null, "运维级动作也要对租户管理员可见（tenant_id 不能为 null）");
    }
}
