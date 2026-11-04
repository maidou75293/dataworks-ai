package com.lab.dataworks.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件归档范围：只处理「本次作业登记过」的文件（P1-5）。
 * 修复前按 task_id 取全部 PENDING，并发运行 A 成功时会把 B 未读的文件一起搬走。
 */
class FileIncrementScopeTest {

    @Test
    void pendingSelectionIsScopedToTheCurrentJob() {
        CaptureJdbc jdbc = new CaptureJdbc();
        FileIncrementService service = new FileIncrementService(jdbc);

        service.pendingForJob(7L, "sync-file-1-abcd");

        assertNotNull(jdbc.sql);
        assertTrue(jdbc.sql.contains("job_name = ?"), jdbc.sql);
        assertTrue(jdbc.sql.contains("status = 'PENDING'"), jdbc.sql);
        assertEquals(List.of(7L, "sync-file-1-abcd"), jdbc.args);
    }

    @Test
    void bindBatchMarksThePreparedBatchWithTheJobName() {
        CaptureJdbc jdbc = new CaptureJdbc();
        FileIncrementService service = new FileIncrementService(jdbc);

        service.bindBatch(7L, "batch-1", "sync-file-1-abcd");

        assertNotNull(jdbc.sql);
        assertTrue(jdbc.sql.contains("batch_id = ?"), jdbc.sql);
        assertEquals(List.of("sync-file-1-abcd", 7L, "batch-1"), jdbc.args);
    }

    @Test
    void archiveWithoutJobNameDoesNothing() {
        CaptureJdbc jdbc = new CaptureJdbc();
        FileIncrementService service = new FileIncrementService(jdbc);
        Map<String, Object> task = Map.of("sourceConfig", Map.of("file_incremental", true));

        int moved = service.archive(7L, task, null);

        assertEquals(0, moved);
        // 没有作业名时连查询都不该发：否则就会退回「按任务搬走全部 PENDING」
        assertNull(jdbc.sql);
    }

    static class CaptureJdbc extends JdbcTemplate {
        String sql;
        List<Object> args;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            this.args = new ArrayList<>(List.of(args));
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = new ArrayList<>(List.of(args));
            return 1;
        }
    }
}
