package com.lab.dataworks.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DL-F2（DL-1 发现 2）回归：失败原因必须优先给出「真因行」，而不是栈帧行。
 *
 * <p>旧实现单趟从底部向上做小写包含匹配，栈帧
 * {@code at ...QueryExecutorImpl.receiveErrorResponse(...)} 因含子串 {@code error}
 * 且位于 {@code Caused by:} 真因行下方被抢先命中，界面/摘要只能看到栈帧，
 * 看不到「表不存在」。修复后 {@link SyncTaskService#lastErrorLine(String)}
 * 先找含 caused by / psqlexception / does not exist 且非 at 开头的行，
 * 找不到才回退旧行为；状态词本身不变。</p>
 */
class SyncTaskLastErrorTest {

    /** DL-1 现场：栈帧在底、真因行在其上方，应返回 PSQLException 真因行。 */
    @Test
    void stackFrameDoesNotMaskRootCause() {
        String log = String.join("\n",
                "2026-10-30 10:00:01,000 ERROR Client - job executed failed",
                "java.lang.RuntimeException: submit job failed",
                "\tat com.lab.seatunnel.Client.execute(Client.java:88)",
                "Caused by: org.postgresql.util.PSQLException: ERROR: relation \"public.no_such_table_zzz\" does not exist",
                "\tat org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2993)",
                "\tat org.postgresql.jdbc.PgStatement.executeInternal(PgStatement.java:542)");
        String line = SyncTaskService.lastErrorLine(log);
        assertTrue(line.startsWith("Caused by: org.postgresql.util.PSQLException"),
                "应取真因行而不是栈帧，实际：" + line);
        assertTrue(line.contains("does not exist"), "真因行应包含语义，实际：" + line);
    }

    /** 异常链有多条 Caused by 时取最深一条（根因）。 */
    @Test
    void deepestCauseWins() {
        String log = String.join("\n",
                "java.lang.RuntimeException: outer wrapper",
                "\tat A.a(A.java:1)",
                "Caused by: java.io.IOException: middle failure",
                "\tat B.b(B.java:2)",
                "Caused by: org.postgresql.util.PSQLException: ERROR: relation \"t_dim\" does not exist",
                "\tat C.c(C.java:3)");
        String line = SyncTaskService.lastErrorLine(log);
        assertTrue(line.contains("PSQLException") && line.contains("relation \"t_dim\""),
                "应取最深 Caused by，实际：" + line);
    }

    /** 不含 caused by/PSQL/does not exist 语义、但以 at 开头的栈帧不算真因。 */
    @Test
    void atPrefixedLineNeverCountsAsRootCause() {
        String log = String.join("\n",
                "something happened",
                "\tat com.x.does not exist here.PSQLException(y)",
                "\tat com.foo.ErrorHandler.handle(ErrorHandler.java:5)");
        // 无真因行 → 回退旧行为：底部向上第一条命中 error 的行（即 ErrorHandler 栈帧）
        String line = SyncTaskService.lastErrorLine(log);
        assertTrue(line.startsWith("at com.foo.ErrorHandler"),
                "回退行为应与旧实现一致，实际：" + line);
    }

    /** 无真因标记时回退原收敛行为（failed/error 等任一命中）。 */
    @Test
    void fallbackKeepsLegacyBehavior() {
        String log = String.join("\n",
                "2026-10-30 10:00:00 INFO start",
                "Job 'sync-7' finished with exit code 1",
                "task failed, please check the job");
        String line = SyncTaskService.lastErrorLine(log);
        assertEquals("task failed, please check the job", line,
                "回退应仍是底部向上第一条命中行");
    }

    /** 真因行超 300 字符时截断，与旧实现口径一致。 */
    @Test
    void rootCauseLineTruncatedTo300() {
        String longLine = "Caused by: org.postgresql.util.PSQLException: ERROR: " + "x".repeat(400);
        String line = SyncTaskService.lastErrorLine("header\n" + longLine + "\n\tat A.a(A.java:1)");
        assertEquals(300, line.length(), "真因行同样受 300 字符截断");
    }

    /** 空日志返回 null，调用方据此保留 k8s condition 原文。 */
    @Test
    void blankLogReturnsNull() {
        assertNull(SyncTaskService.lastErrorLine(null));
        assertNull(SyncTaskService.lastErrorLine("   \n  \n"));
    }

    /** 仅含 psqlexception 关键字（无 caused by 前缀、无 does not exist）也算真因行。 */
    @Test
    void psqlExceptionKeywordAloneMatches() {
        String log = String.join("\n",
                "org.postgresql.util.PSQLException: FATAL: password authentication failed",
                "\tat org.postgresql.core.AuthRegistry.login(AuthRegistry.java:1)");
        String line = SyncTaskService.lastErrorLine(log);
        assertTrue(line.startsWith("org.postgresql.util.PSQLException"),
                "应优先取 PSQLException 行，实际：" + line);
    }
}
