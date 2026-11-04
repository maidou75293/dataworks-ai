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
 * BUG-TC6-3：BI 看板按版本回滚。
 *
 * <p>看板的「内容」在快照里（名称/描述/参数/图表），回滚必须把图表整组恢复，
 * 再生成一个新版本；历史版本不覆盖。数据源已被删/移出项目的旧版本一律 409。</p>
 */
class BiRollbackTest {

    private static final String SNAPSHOT = """
            {"projectId":7,"name":"旧看板","description":"d","params":[],
             "charts":[{"chartType":"LINE","name":"趋势","datasourceId":5,
                        "sqlText":"select 1","xColumn":"x","yColumn":"y","seriesColumn":null}]}
            """;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BiController controller = new BiController(jdbc, new ObjectMapper(),
            mock(SecretCipher.class), mock(DataGuardService.class), mock(SessionService.class));

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private Map<String, Object> dashboard() {
        return row("id", 1L, "project_id", 7L, "code", "dash_demo", "name", "新看板",
                "description", "改过了", "params_json", "[]", "status", "PUBLISHED",
                "version", 3, "tenant_id", "default");
    }

    private Map<String, Object> oldRelease() {
        return row("version", 1, "snapshot", SNAPSHOT);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "bi_owner");
        return http;
    }

    @Test
    void rollbackRestoresSnapshotChartsAndCreatesNextVersion() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(dashboard()))       // findForUpdate
                .thenReturn(List.of(oldRelease()))      // 版本快照
                .thenReturn(List.of(dashboard()))       // 恢复后 find
                .thenReturn(List.of(row("chartType", "LINE", "name", "趋势",
                        "datasourceId", 5L, "sqlText", "select 1")));  // charts(id)
        when(jdbc.queryForObject(contains("select count(*) from platform.datasource"),
                eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("coalesce(max(version)"), eq(Integer.class),
                any(Object[].class))).thenReturn(3);

        Map<String, Object> result = controller.rollback(1L, 1, request());

        assertEquals(4, result.get("version"));
        assertEquals(1, result.get("rolledBackTo"));
        // 快照内容恢复到开发态：名称回到旧版本
        ArgumentCaptor<Object[]> restore = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("set name = ?, description = ?, params_json = ?"),
                restore.capture());
        assertEquals("旧看板", restore.getValue()[0]);
        // 新版本插入快照（历史 v1 不被覆盖）
        ArgumentCaptor<Object[]> insert = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into platform.bi_dashboard_release"),
                insert.capture());
        assertEquals(4, insert.getValue()[1]);
    }

    @Test
    void rollbackUnknownVersionIs404() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(dashboard()))
                .thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class,
                () -> controller.rollback(1L, 9, request()));

        assertEquals(404, error.getStatus());
        verify(jdbc, never()).update(contains("insert into platform.bi_dashboard_release"),
                any(Object[].class));
    }

    @Test
    void rollbackRejectedWhenSnapshotDatasourceIsGone() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(dashboard()))
                .thenReturn(List.of(oldRelease()));
        when(jdbc.queryForObject(contains("select count(*) from platform.datasource"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.rollback(1L, 1, request()));

        assertEquals(409, error.getStatus());
        // 事务里不能留下「图表删了没插回去」的半截看板
        verify(jdbc, never()).update(contains("delete from platform.bi_chart"),
                any(Object[].class));
    }
}
