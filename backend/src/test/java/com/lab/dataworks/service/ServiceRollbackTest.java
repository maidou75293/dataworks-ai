package com.lab.dataworks.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.security.DataGuardService;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-TC6-3：数据服务按版本回滚。
 *
 * <p>三条语义钉死：① 回滚 = 以旧快照内容生成 max+1 的新版本（历史版本不覆盖）；
 * ② 回滚内容是历史上已发布过的版本，不该被「先在 DEV 跑通」闸门拦；
 * ③ 快照引用的数据源若已被删/移出项目，必须 409 拒绝，不能造出跑不起来的线上服务。</p>
 */
class ServiceRollbackTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final DataServiceController controller = new DataServiceController(jdbc, sessions,
            new ObjectMapper(), mock(SecretCipher.class), mock(DataGuardService.class));

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private Map<String, Object> serviceRow() {
        return row("id", 1L, "project_id", 7L, "code", "svc_demo", "name", "demo",
                "method", "GET", "datasource_id", 5L, "sql_text", "select * from t",
                "params_json", "[]", "status", "PUBLISHED", "version", 2);
    }

    private Map<String, Object> oldRelease() {
        return row("service_id", 1L, "version", 1, "method", "POST", "datasource_id", 5L,
                "sql_text", "select 1 as old", "params_json", "[{\"name\":\"day\"}]");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "svc_owner");
        return http;
    }

    @Test
    void rollbackCreatesNextVersionFromOldSnapshot() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(serviceRow()))
                .thenReturn(List.of(oldRelease()));
        when(jdbc.queryForObject(contains("select count(*) from platform.datasource"),
                eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("coalesce(max(version)"), eq(Integer.class),
                any(Object[].class))).thenReturn(4);
        when(jdbc.update(contains("update platform.data_service"), any(Object[].class)))
                .thenReturn(1);

        Map<String, Object> result = controller.rollback(1L, 1, request());

        assertEquals(5, result.get("version"));
        assertEquals(1, result.get("rolledBackTo"));
        ArgumentCaptor<Object[]> insert = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into platform.data_service_release"),
                insert.capture());
        assertEquals(5, insert.getValue()[1]);
        assertEquals("POST", insert.getValue()[2]);
        assertEquals("select 1 as old", insert.getValue()[4]);
        // 草稿同步成回滚内容，并且状态回到已发布
        ArgumentCaptor<Object[]> update = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("set method = ?, datasource_id = ?, sql_text = ?"),
                update.capture());
        assertEquals("POST", update.getValue()[0]);
        assertEquals("select 1 as old", update.getValue()[2]);
        assertEquals(5, update.getValue()[4]);
    }

    @Test
    void rollbackUnknownVersionIs404() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(serviceRow()))
                .thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class,
                () -> controller.rollback(1L, 99, request()));

        assertEquals(404, error.getStatus());
        verify(jdbc, never()).update(contains("insert into platform.data_service_release"),
                any(Object[].class));
    }

    @Test
    void rollbackRejectedWhenSnapshotDatasourceIsGone() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(serviceRow()))
                .thenReturn(List.of(oldRelease()));
        when(jdbc.queryForObject(contains("select count(*) from platform.datasource"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.rollback(1L, 1, request()));

        assertEquals(409, error.getStatus());
        verify(jdbc, never()).update(contains("insert into platform.data_service_release"),
                any(Object[].class));
    }
}
