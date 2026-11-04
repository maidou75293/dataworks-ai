package com.lab.dataworks.flink;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.udf.UdfService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 流任务写路径：停止失败不能报已停止；PROD 并发上线的取消必须打在 PROD 集群。 */
class FlinkSqlControllerTest {

    private static final String SQL =
            "CREATE TABLE s(id INT) WITH ('connector'='datagen');"
            + "CREATE TABLE t(id INT) WITH ('connector'='print');"
            + "INSERT INTO t SELECT id FROM s;";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FlinkSqlService flink = mock(FlinkSqlService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final UdfService udf = mock(UdfService.class);
    private final FlinkSqlController controller =
            new FlinkSqlController(jdbc, flink, sessions, udf);

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private Map<String, Object> jobRow() {
        return row("id", 1L, "project_id", 1L, "code", "demo_job", "name", "demo",
                "sql_text", SQL, "status", "RUNNING", "flink_job_id", "job-1",
                "last_flink_job_id", "job-1", "session_mode", "ISOLATED",
                "session_handle", "sess-1", "prod_status", "STOPPED", "prod_job_id", null);
    }

    private void givenJob(Map<String, Object> row) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        when(udf.flinkPreamble(anyLong(), anyString())).thenReturn("");
    }

    private void asProdPassThrough() {
        when(flink.asProd(any(Supplier.class))).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(0);
            return supplier.get();
        });
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(flink).asProd(any(Runnable.class));
    }

    @Test
    void stopRevertsWhenRemoteCancelFails() {
        givenJob(jobRow());
        when(flink.jobStatus("job-1")).thenReturn(Map.of("state", "RUNNING"));
        when(jdbc.update(contains("set status = 'STOPPED', stopped_at = now()"),
                any(Object[].class))).thenReturn(1);
        doThrow(new ApiException(502, "flink down")).when(flink).cancel("job-1");

        ApiException error = assertThrows(ApiException.class,
                () -> controller.stop(1L, new MockHttpServletRequest()));

        assertEquals(502, error.getStatus());
        // 回滚必须把句柄和原状态写回去，下一轮对账/用户重试才有对象可停
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(contains("set status = ?, flink_job_id = ?"),
                args.capture());
        Object[] values = args.getValue();
        assertEquals("RUNNING", values[0]);
        assertEquals("job-1", values[1]);
    }

    @Test
    void prodUpCancelsLosingJobInProdContext() {
        Map<String, Object> row = jobRow();
        row.put("status", "STOPPED");
        row.put("flink_job_id", null);
        row.put("session_handle", null);
        row.put("version", 1);
        givenJob(row);
        asProdPassThrough();
        when(flink.submit(anyString(), anyBoolean()))
                .thenReturn(Map.of("jobId", "prod-job-1", "session", "prod-sess"));
        // 占位失败（另一个请求已经上线）：这次起的 PROD 作业必须被取消
        when(jdbc.update(contains("prod_job_id is null"), any(Object[].class))).thenReturn(0);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.prodUp(1L, new MockHttpServletRequest()));

        assertEquals(409, error.getStatus());
        verify(flink).cancel("prod-job-1");
        verify(flink).closeSession("prod-sess");
    }

    @Test
    void rollbackCreatesNextVersionFromOldSnapshotWithoutSmokeGate() {
        Map<String, Object> row = jobRow();
        // smoke_hash 与回滚内容无关：回滚的是历史已发布版本，不该被 DEV 跑通闸门拦住
        row.put("smoke_hash", "stale-hash");
        Map<String, Object> old = row("version", 2, "sql_text", "OLD SQL", "session_mode", "SHARED");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row))
                .thenReturn(List.of(old));
        when(jdbc.queryForObject(contains("coalesce(max(version)"), eq(Integer.class),
                any(Object[].class))).thenReturn(5);

        Map<String, Object> result = controller.rollback(1L, 2, new MockHttpServletRequest());

        assertEquals(6, result.get("version"));
        assertEquals(2, result.get("rolledBackTo"));
        assertEquals(false, result.get("prodRestartRequired"));
        // 新版本用旧快照内容，历史版本不被覆盖
        ArgumentCaptor<Object[]> insert = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into platform.flink_sql_release"), insert.capture());
        assertEquals("OLD SQL", insert.getValue()[2]);
        assertEquals("SHARED", insert.getValue()[3]);
        // 草稿同步成回滚内容，页面上「已发布 v6」才和编辑器一致
        ArgumentCaptor<Object[]> update = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("set sql_text = ?, session_mode = ?, published_version = ?"),
                update.capture());
        assertEquals("OLD SQL", update.getValue()[0]);
        assertEquals(6, update.getValue()[2]);
    }

    @Test
    void rollbackUnknownVersionIs404() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(jobRow()))
                .thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class,
                () -> controller.rollback(1L, 9, new MockHttpServletRequest()));

        assertEquals(404, error.getStatus());
        verify(jdbc, never()).update(contains("insert into platform.flink_sql_release"),
                any(Object[].class));
    }

    @Test
    void rollbackFlagsProdRestartWhenOnlineJobActive() {
        Map<String, Object> row = jobRow();
        row.put("prod_status", "RUNNING");
        row.put("prod_job_id", "prod-job-9");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row))
                .thenReturn(List.of(row("version", 1, "sql_text", "OLD", "session_mode", "ISOLATED")));
        when(jdbc.queryForObject(contains("coalesce(max(version)"), eq(Integer.class),
                any(Object[].class))).thenReturn(1);

        Map<String, Object> result = controller.rollback(1L, 1, new MockHttpServletRequest());

        // 回滚只切快照，不偷偷重启线上作业：由前端提示先停止线上再重新上线
        assertEquals(true, result.get("prodRestartRequired"));
    }

    @Test
    void submitRejectedWhileRestarting() {
        Map<String, Object> row = jobRow();
        row.put("status", "RESTARTING");
        givenJob(row);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.submit(1L, new MockHttpServletRequest()));

        assertEquals(409, error.getStatus());
        verify(flink, never()).submit(anyString(), anyBoolean());
    }
}
