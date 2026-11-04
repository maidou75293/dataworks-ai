package com.lab.dataworks.flink;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 对账状态机：取消失败保留句柄、重启收敛、STARTING 不再卡死。 */
class FlinkJobReconcilerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FlinkSqlService flink = mock(FlinkSqlService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final FlinkJobReconciler reconciler = new FlinkJobReconciler(jdbc, flink, sessions);

    /** Map.of 不收 null，行里有 last_error=null，必须用可变 Map。 */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private void rows(List<Map<String, Object>> dev, List<Map<String, Object>> prod) {
        when(jdbc.queryForList(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("prod_job_id") ? prod : dev;
        });
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

    private Object[] capturedArgs(String sqlFragment) {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(contains(sqlFragment), captor.capture());
        return captor.getValue();
    }

    @Test
    void failedJobKeepsHandleWhenCancelFailsSoNextRoundRetries() {
        rows(List.of(row("id", 1, "flink_job_id", "abc", "status", "RUNNING",
                "last_error", "old", "restarting_since", new Timestamp(0))), List.of());
        when(flink.jobStatus("abc")).thenReturn(Map.of("state", "FAILED"));
        when(flink.jobException("abc")).thenReturn("boom");
        org.mockito.Mockito.doThrow(new ApiException(502, "flink down"))
                .when(flink).cancel("abc");

        reconciler.reconcile();

        Object[] args = capturedArgs("flink_job_id = case when ? then null else flink_job_id end");
        // 取消没成功：三个「清句柄/关会话/记停止时间」的标志位都必须是 false
        assertEquals(Boolean.FALSE, args[2], "取消失败不能丢句柄");
        assertEquals(Boolean.FALSE, args[3], "取消失败不能丢会话");
        assertEquals("boom", args[0]);
        verify(flink).cancel("abc");
    }

    @Test
    void failedJobClearsHandleWhenCancelSucceeds() {
        rows(List.of(row("id", 1, "flink_job_id", "abc", "status", "RUNNING",
                "last_error", null, "restarting_since", null)), List.of());
        when(flink.jobStatus("abc")).thenReturn(Map.of("state", "FAILED"));
        when(flink.jobException("abc")).thenReturn("");

        reconciler.reconcile();

        Object[] args = capturedArgs("flink_job_id = case when ? then null else flink_job_id end");
        assertEquals(Boolean.TRUE, args[2]);
        assertEquals(Boolean.TRUE, args[3]);
        assertTrue(String.valueOf(args[0]).contains("失败"));
    }

    @Test
    void restartingIsPersistedWithTimestamp() {
        rows(List.of(row("id", 5, "flink_job_id", "r1", "status", "RUNNING",
                "last_error", null, "restarting_since", null)), List.of());
        when(flink.jobStatus("r1")).thenReturn(Map.of("state", "RESTARTING"));

        reconciler.reconcile();

        verify(jdbc, atLeastOnce()).update(
                contains("status = 'RESTARTING', restarting_since = now()"), any(Object[].class));
    }

    @Test
    void prodRestartBeyondLimitConvergesToFailed() {
        asProdPassThrough();
        rows(List.of(), List.of(row("id", 9, "prod_job_id", "p1", "prod_status", "RUNNING",
                "prod_restarting_since",
                new Timestamp(System.currentTimeMillis() - 180_000L))));
        when(flink.jobStatus("p1")).thenReturn(Map.of("state", "RESTARTING"));

        reconciler.reconcile();

        verify(flink).cancel("p1");
        Object[] args = capturedArgs("prod_job_id = case when ? then null else prod_job_id end");
        assertEquals(Boolean.TRUE, args[0], "取消成功后应清掉 PROD 句柄");
        assertEquals(Boolean.TRUE, args[1]);
    }

    @Test
    void startingStateFollowsRemoteInitializing() {
        rows(List.of(row("id", 7, "flink_job_id", "s1", "status", "RUNNING",
                "last_error", null, "restarting_since", null)), List.of());
        when(flink.jobStatus("s1")).thenReturn(Map.of("state", "INITIALIZING"));

        reconciler.reconcile();

        verify(jdbc, atLeastOnce()).update(contains("set status = ?"),
                eq("STARTING"), eq(7L), eq("s1"));
    }
}
