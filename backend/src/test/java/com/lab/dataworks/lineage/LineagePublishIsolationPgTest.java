package com.lab.dataworks.lineage;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.lab.dataworks.lineage.SparkPublishLineageTest.model;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.service;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.task;
import static com.lab.dataworks.lineage.SparkPublishLineageTest.varargsOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-VER-V572-1：发布血缘「逐任务隔离」在真实 PG 事务语义下的建模。
 *
 * <p>v572 现网教训：单测用普通 mock JdbcTemplate，建模不出 PG 的 aborted 语义——
 * 任一语句报 22001（value too long）后整个事务进入 aborted，同事务后续 insert
 * 一律 25006，末尾提交静默变回滚；于是「日志 edges=4、库里 0 行」与 27/27 绿并存。
 * 本桩把这三条语义补上：①22001 置 aborted；②aborted 后一切语句抛 25006；
 * ③只有 rollback(savepoint) 能清掉 aborted 并丢弃坏语句所在任务的部分写入；
 * ④{@link PgTx#commit()} 模拟外层 @Transactional 的提交——仍 aborted 就全部丢弃。
 * 坏样本取现网复现形状：目标表名 306 字符超 varchar(255)。</p>
 */
class LineagePublishIsolationPgTest {

    static final String OK_SQL = "insert into dwd.ok_tgt select id from ods.ok_src";
    /** 现网复现样本：目标表名 306 字符，入库即 PG 22001。 */
    static final String BAD_SQL =
            "insert into m10." + "b".repeat(306) + " select id from ods.src";

    // ------------------------------------------- W3 混排·好任务在前（v572 现网形态）

    @Test
    void badTaskAfterGoodMustNotRollBackTheWholeVersion() {
        PgTx tx = new PgTx();
        int edges = service(jdbcOf(tx)).record(336L, 1, "DEV",
                model(task("t_doris_ok", "DORIS", OK_SQL), task("t_bad_long", "DORIS", BAD_SQL)));

        // 模拟外层 @Transactional 提交：仍 aborted 的话提交静默变回滚——已写边全丢
        assertFalse(tx.aborted, "坏任务的失败必须被隔离掉，事务不能停在 aborted 状态");
        assertTrue(tx.commit(), "提交不能把已写入的边整体吞掉");
        assertTrue(tx.rows.stream().anyMatch(r -> "t_doris_ok".equals(code(r))),
                "好任务的边必须真实落库（红检：现网这里是 0 行）: " + tx.rows);
        assertTrue(tx.rows.stream().noneMatch(r -> "t_bad_long".equals(code(r))),
                "坏任务不留半条边: " + tx.rows);
        assertEquals(tx.rows.size(), edges, "edges= 计数只报实落库数");
    }

    // ------------------------------------------- 坏任务在前：25006 级联不能吞后续任务

    @Test
    void tasksAfterTheFailingOneMustNotBePoisonedByAbortedTransaction() {
        PgTx tx = new PgTx();
        int edges = service(jdbcOf(tx)).record(339L, 1, "DEV",
                model(task("t_bad_long", "DORIS", BAD_SQL), task("t_doris_ok", "DORIS", OK_SQL)));

        assertFalse(tx.aborted, "aborted 必须在坏任务处被保存点清掉，不能级联到下一个任务");
        assertTrue(tx.commit());
        assertTrue(tx.rows.stream().anyMatch(r -> "t_doris_ok".equals(code(r))),
                "坏任务之后的任务不能被 25006 级联吞掉: " + tx.rows);
        assertEquals(tx.rows.size(), edges, "edges= 计数只报实落库数");
    }

    // ---------------------------------------------------------------- PG 语义桩

    /**
     * 一个真 PG 口径的事务桩：行先进「事务暂存」，commit() 决定最终是否留下。
     * JdbcTemplate 的 update 走 {@link #update}，execute(ConnectionCallback) 走
     * {@link #connection()}——正好覆盖「保存点包裹写入」的修复路径。
     */
    static final class PgTx {
        final List<Object[]> rows = new ArrayList<>();
        final Map<Savepoint, Integer> marks = new HashMap<>();
        boolean aborted;
        private int savepointSeq;

        static final String TOO_LONG =
                "PreparedStatementCallback; SQL [insert into platform.lineage_edge ...]; "
                        + "ERROR: value too long for type character varying(255)";
        static final String ABORTED =
                "PreparedStatementCallback; SQL [insert into platform.lineage_edge ...]; "
                        + "ERROR: current transaction is aborted, commands ignored until end of transaction block";

        int update(String sql, Object[] args) {
            if (sql.contains("insert into platform.lineage_edge")) {
                if (aborted) {
                    throw new DataIntegrityViolationException(ABORTED + " (25006)");
                }
                for (Object arg : args) {
                    if (arg instanceof String s && s.length() > 255) {
                        // PG：22001 不止拒绝这一行，整个事务从这条语句起进入 aborted
                        aborted = true;
                        throw new DataIntegrityViolationException(TOO_LONG + " (22001)");
                    }
                }
                rows.add(args);
                return 1;
            }
            if (aborted) {
                throw new DataIntegrityViolationException(ABORTED + " (25006)");
            }
            return 1;
        }

        /** 模拟外层事务提交：仍 aborted 的提交在 PG 里就是回滚，已写边全部丢弃。 */
        boolean commit() {
            if (aborted) {
                rows.clear();
                return false;
            }
            return true;
        }

        Savepoint setSavepoint() throws SQLException {
            if (aborted) {
                throw new SQLException(ABORTED + " (25006)");
            }
            Savepoint sp = new Savepoint() {
                @Override
                public int getSavepointId() {
                    return savepointSeq + 1;
                }

                @Override
                public String getSavepointName() {
                    return "sp" + savepointSeq;
                }
            };
            savepointSeq++;
            marks.put(sp, rows.size());
            return sp;
        }

        void rollbackTo(Savepoint sp) {
            Integer mark = marks.get(sp);
            if (mark != null) {
                rows.subList(mark, rows.size()).clear();
            }
            aborted = false;
        }

        /** Connection 代理：只实现事务桩需要的三个方法，其余返回 JDK 默认值。 */
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAutoCommit" -> false;
                        case "setSavepoint" -> setSavepoint();
                        case "rollback" -> {
                            if (args != null && args.length == 1 && args[0] instanceof Savepoint sp) {
                                rollbackTo(sp);
                            }
                            yield null;
                        }
                        case "toString" -> "PgTxStubConnection";
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class || type == Void.class) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            return 0;
        }
    }

    static JdbcTemplate jdbcOf(PgTx tx) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer((Answer<Integer>) inv ->
                tx.update(inv.getArgument(0), varargsOf(inv)));
        stubExecute(jdbc, tx.connection());
        return jdbc;
    }

    /** 让 mock JdbcTemplate 真的执行 ConnectionCallback（保存点修复走 jdbc.execute 这条路径）。 */
    static void stubExecute(JdbcTemplate jdbc, Connection con) {
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<?> callback = inv.getArgument(0);
            return callback.doInConnection(con);
        });
    }

    /** INSERT 绑定值里的 task_code 位（列序见 SparkPublishLineageTest.toRow 注释）。 */
    static String code(Object[] args) {
        return String.valueOf(args[2]);
    }
}
