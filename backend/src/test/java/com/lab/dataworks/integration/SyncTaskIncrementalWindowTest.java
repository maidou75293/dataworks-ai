package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量窗口的构造：下界取已保存水位（upsert 用 {@code >=}），上界取本次运行前
 * 快照的 pending_value；补数不套用上界。修复前 SQL 只有 `> 水位`，作业期间写入的
 * 行会被下一轮跳过（P1-1），同一游标值晚到的行会永久丢失（P1-2）。
 */
class SyncTaskIncrementalWindowTest {

    private static final long TASK = 7L;
    private static final String TABLE = "src.tbl";
    private static final String JOB = "sync-demo-1-abcd";

    @Test
    void appendSinkKeepsStrictLowerBoundAndAddsSnapshotUpperBound() throws Exception {
        String sql = window(false, false, JOB);
        assertTrue(sql.contains("col > '2026-10-01 00:00:00'"), sql);
        assertTrue(sql.contains("col <= '2026-10-02 00:00:00'"), sql);
    }

    @Test
    void upsertSinkUsesInclusiveLowerBound() throws Exception {
        String sql = window(true, false, JOB);
        assertTrue(sql.contains("col >= '2026-10-01 00:00:00'"), sql);
        assertTrue(sql.contains("col <= '2026-10-02 00:00:00'"), sql);
    }

    @Test
    void backfillDoesNotUseSnapshotUpperBound() throws Exception {
        String sql = window(false, true, JOB);
        assertTrue(sql.contains("col > '2026-10-01 00:00:00'"), sql);
        assertFalse(sql.contains("<="), sql);
    }

    @Test
    void upperBoundOnlyAppliesToTheJobThatSnapshottedIt() throws Exception {
        // pending 属于别的作业（例如上一次失败/正在运行），不能用它的上界
        String sql = window(false, false, "another-job");
        assertTrue(sql.contains("col > '2026-10-01 00:00:00'"), sql);
        assertFalse(sql.contains("<="), sql);
    }

    @Test
    void noSnapshotAndNoWatermarkFallsBackToPlainQuery() throws Exception {
        StubJdbc jdbc = new StubJdbc(Map.of(), Map.of());
        String sql = invoke(jdbc, Map.of("incremental", Map.of("column", "col")),
                false, false, JOB);
        assertEquals("select * from src.tbl", sql);
    }

    @Test
    void oracleTimestampUsesToTimestampOnBothBounds() throws Exception {
        StubJdbc jdbc = new StubJdbc();
        Map<String, Object> entry = entry(Map.of("column", "col", "type", "timestamp"));
        String sql = invoke(jdbc, entry, false, false, JOB);
        assertTrue(sql.contains("to_timestamp('2026-10-01 00:00:00'"), sql);
        assertTrue(sql.contains("to_timestamp('2026-10-02 00:00:00'"), sql);
    }

    private String window(boolean upsert, boolean backfill, String job) throws Exception {
        StubJdbc jdbc = new StubJdbc();
        return invoke(jdbc, entry(Map.of("column", "col")), upsert, backfill, job);
    }

    private static Map<String, Object> entry(Map<String, Object> incremental) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("table", TABLE);
        entry.put("incremental", incremental);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static String invoke(StubJdbc jdbc, Map<String, Object> entry,
                                 boolean upsert, boolean backfill, String job) throws Exception {
        SyncTaskService service = new SyncTaskService("dw-dev", "seatunnel-config",
                "10.99.99.11:30500/library/seatunnel:dev",
                jdbc, new ObjectMapper(), null);
        Method m = SyncTaskService.class.getDeclaredMethod("applyIncremental",
                long.class, Map.class, String.class, String.class, Map.class,
                boolean.class, boolean.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, TASK, entry, TABLE, "select * from src.tbl",
                Map.of("url", "jdbc:postgresql://db:5432/kb"), upsert, backfill, job);
    }

    /** 只实现水位读取的水位表桩：watermark_value / pending_value 各一行。 */
    static class StubJdbc extends JdbcTemplate {
        final Map<String, String> watermarks;
        final Map<String, String> pendings;

        StubJdbc() {
            this(Map.of(TASK + "|" + TABLE, "2026-10-01 00:00:00"),
                    Map.of(TASK + "|" + TABLE + "|" + JOB, "2026-10-02 00:00:00"));
        }

        StubJdbc(Map<String, String> watermarks, Map<String, String> pendings) {
            this.watermarks = watermarks;
            this.pendings = pendings;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            Map<String, String> source = sql.contains("pending_value") ? pendings : watermarks;
            String key = sql.contains("pending_value")
                    ? args[0] + "|" + args[1] + "|" + args[2]
                    : args[0] + "|" + args[1];
            String value = source.get(key);
            List<T> out = new ArrayList<>();
            if (value != null) {
                out.add((T) value);
            }
            return out;
        }
    }
}
