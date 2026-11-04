package com.lab.dataworks.project;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S12（数仓连接落点）：连接建/删原来零审计——「谁建了个连到外部的 Doris 连接、
 * 谁把唯一的生产连接删了」完全无痕。现在建/删都走 {@code auditAround}：
 * 先记 PENDING（带类型/地址/归属，密码只记「已设置」），失败回填 FAILED。
 */
class WarehouseConnAuditTest {

    private JdbcTemplate biz;
    private JdbcTemplate auditJdbc;
    private WarehouseConnectionController controller;

    @BeforeEach
    void setUp() {
        biz = mock(JdbcTemplate.class);
        auditJdbc = mock(JdbcTemplate.class);
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.encrypt(anyString())).thenReturn("enc");
        controller = new WarehouseConnectionController(biz, cipher, sessions);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "wh_admin");
        http.setRemoteAddr("10.99.99.14");
        return http;
    }

    private List<Object> pendingArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).queryForObject(contains("insert into platform.audit_log"),
                eq(Long.class), args.capture());
        return List.of(args.getValue());
    }

    private List<Object> settledArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).update(contains("update platform.audit_log"), args.capture());
        return List.of(args.getValue());
    }

    private static WarehouseConnectionController.CreateRequest s3Request() {
        return new WarehouseConnectionController.CreateRequest("s3_dev", "s3",
                "http://minio:9000", "access-key", "super-secret", "dw", "minio", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createConnectionAuditsOwnershipInfoAndNeverThePassword() {
        when(biz.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(42L);

        controller.create(s3Request(), request());

        List<Object> pending = pendingArgs();
        assertEquals("wh_admin", pending.get(0));
        assertEquals("WAREHOUSE_CONN_CREATE", pending.get(1));
        assertEquals("s3_dev", pending.get(2));
        String detail = String.valueOf(pending.get(3));
        assertTrue(detail.contains("type=s3") && detail.contains("endpoint=http://minio:9000")
                && detail.contains("password=set"), "detail 要带归属信息: " + detail);
        assertTrue(!detail.contains("super-secret"), "密码绝不进审计");
        assertEquals("PENDING", pending.get(4));
        assertEquals("default", pending.get(6), "S12：tenant_id 不能缺");
        assertTrue(String.valueOf(settledArgs().get(1)).contains("id=42"),
                "成功回填要带上新建连接的 id");
    }

    @Test
    void deleteConnectionAuditsContentBeforeDeleting() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "s3_dev");
        row.put("type", "s3");
        row.put("provider", "minio");
        row.put("endpoint", "http://minio:9000");
        // D48 之后 delete 先查「还有谁在用」：那条查询必须返回空，否则会被引用检查 400 拦下，
        // 本测试就测不到审计包装了（按 SQL 文本分流，而不是一律返回连接行）。
        when(biz.queryForList(anyString(), any(Object[].class))).thenAnswer(inv ->
                ((String) inv.getArgument(0)).contains("from platform.project")
                        ? List.of() : List.of(row));
        when(biz.update(anyString(), any(Object[].class))).thenReturn(1);

        controller.delete(5, request());

        List<Object> pending = pendingArgs();
        assertEquals("WAREHOUSE_CONN_DELETE", pending.get(1));
        assertEquals("s3_dev", pending.get(2));
        String detail = String.valueOf(pending.get(3));
        assertTrue(detail.contains("endpoint=http://minio:9000"),
                "S12：删连接要能回答删的是哪个: " + detail);
        assertEquals("SUCCESS", settledArgs().get(0));
    }

    /** S13：删不存在的连接（并发下 n==0）也要留下 FAILED 痕迹，异常原样上抛。 */
    @Test
    void deleteMissingConnectionStillAuditsFailed() {
        when(biz.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(biz.update(anyString(), any(Object[].class))).thenReturn(0);

        ApiException e = assertThrows(ApiException.class, () -> controller.delete(99, request()));
        assertEquals(404, e.getStatus());

        List<Object> settled = settledArgs();
        assertEquals("FAILED", settled.get(0));
        assertTrue(String.valueOf(settled.get(1)).contains("连接不存在"), String.valueOf(settled.get(1)));
    }
}
