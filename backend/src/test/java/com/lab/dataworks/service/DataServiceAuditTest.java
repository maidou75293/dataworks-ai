package com.lab.dataworks.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S12 + S13（数据服务落点）：SERVICE_DELETE / SERVICE_OFFLINE / SERVICE_TOKEN_ROTATE
 * 原来都是「动作做完再记、detail=null」——事后看不出删/下线的是哪个状态哪个版本，
 * 底层一抛异常就一条不留；轮换 Token 这种凭据动作更是无痕。
 *
 * <p>现在统一 {@code auditAround}：先落 PENDING（带租户/IP），成功回填 SUCCESS，
 * 失败回填 FAILED 且异常原样上抛。删除/下线的 detail 必须带发布版本等可定位内容，
 * 轮换的 detail 带新 token 脱敏指纹（绝不带明文）。</p>
 */
class DataServiceAuditTest {

    private JdbcTemplate biz;
    private JdbcTemplate auditJdbc;
    private SecretCipher cipher;
    private DataServiceController controller;

    @BeforeEach
    void setUp() {
        biz = mock(JdbcTemplate.class);
        auditJdbc = mock(JdbcTemplate.class);
        // 真的 SessionService + 假审计 JdbcTemplate：验到最终落库的 SQL 与参数
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        cipher = mock(SecretCipher.class);
        when(cipher.encrypt(anyString())).thenReturn("enc");
        controller = new DataServiceController(biz, sessions, new ObjectMapper(), cipher,
                mock(com.lab.dataworks.security.DataGuardService.class));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "svc_admin");
        http.setRemoteAddr("10.99.99.12");
        return http;
    }

    private static Map<String, Object> serviceRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7L);
        row.put("code", "ds_api1");
        row.put("name", "订单查询");
        row.put("status", "PUBLISHED");
        row.put("version", 3);
        return row;
    }

    private void stubFind() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(serviceRow()));
    }

    /** 取出先记那条 PENDING 审计的绑定参数。 */
    private List<Object> pendingArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).queryForObject(contains("insert into platform.audit_log"),
                eq(Long.class), args.capture());
        return List.of(args.getValue());
    }

    /** 取出回填终态那条 update 的绑定参数：[result, finalDetail, id]。 */
    private List<Object> settledArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).update(contains("update platform.audit_log"), args.capture());
        return List.of(args.getValue());
    }

    @Test
    void deleteServiceAuditsContentAndSettlesSuccess() {
        stubFind();
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        controller.delete(7, request());

        List<Object> pending = pendingArgs();
        assertEquals("svc_admin", pending.get(0));
        assertEquals("SERVICE_DELETE", pending.get(1));
        assertEquals("ds_api1", pending.get(2));
        String detail = String.valueOf(pending.get(3));
        assertTrue(detail.contains("订单查询") && detail.contains("PUBLISHED")
                && detail.contains("version=3"), "S12：删服务必须能回答删的是哪个版本: " + detail);
        assertEquals("PENDING", pending.get(4));
        assertEquals("10.99.99.12", pending.get(5));
        assertEquals("default", pending.get(6), "S12：tenant_id 不能缺，否则租户管理员看不到");
        assertEquals("SUCCESS", settledArgs().get(0));
    }

    @Test
    void deleteServiceFailureStillAudits() {
        stubFind();
        doThrow(new IllegalStateException("pg 连接失败"))
                .when(biz).update(anyString(), any(Object[].class));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> controller.delete(7, request()));
        assertTrue(e.getMessage().contains("pg 连接失败"), "底层错误不能被改掉");

        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("pg 连接失败"),
                "S13：删除失败必须留下 FAILED 与原因");
    }

    @Test
    void offlineServiceAuditsOldStateAndVersion() {
        stubFind();
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        controller.offline(7, request());

        String detail = String.valueOf(pendingArgs().get(3));
        assertTrue(detail.contains("原状态=PUBLISHED") && detail.contains("发布版本=v3"),
                "S12：下线要能从审计看出下的是第几版: " + detail);
        assertEquals("SERVICE_OFFLINE", pendingArgs().get(1));
        assertEquals("SUCCESS", settledArgs().get(0));
    }

    @Test
    void rotateTokenAuditsMaskedFingerprintNotSecret() {
        stubFind();
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> out = controller.rotateToken(7, request());

        String pendingDetail = String.valueOf(pendingArgs().get(3));
        assertEquals("SERVICE_TOKEN_ROTATE", pendingArgs().get(1));
        String settledDetail = String.valueOf(settledArgs().get(1));
        String token = String.valueOf(out.get("token"));
        assertTrue(settledDetail.contains("新 token 指纹="),
                "S12：轮换 Token 要留下可对照的指纹: " + settledDetail);
        assertTrue(settledDetail.contains(token.substring(0, 6)), "指纹要和发出的 token 对得上");
        assertTrue(!pendingDetail.contains(token) && !settledDetail.contains(token),
                "明文 token 绝不进审计");
    }
}
