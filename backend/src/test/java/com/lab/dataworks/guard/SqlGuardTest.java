package com.lab.dataworks.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1 判定验收（inbox/008 的 4 条硬性用例 + 注释/大小写/多语句边界）。
 */
class SqlGuardTest {

    @Test
    void dropOnTmpTablePasses() {
        var r = SqlGuard.check("DROP TABLE tmp_a");
        assertFalse(r.needReview());
        assertEquals(List.of("tmp_a"), r.tables());
    }

    @Test
    void dropOnRealTableNeedsReview() {
        var r = SqlGuard.check("DROP TABLE orders");
        assertTrue(r.needReview());
        assertEquals(List.of("orders"), r.tables());
    }

    @Test
    void selectPasses() {
        assertFalse(SqlGuard.check("SELECT * FROM t").needReview());
    }

    @Test
    void keywordInsideStringLiteralIsIgnored() {
        assertFalse(SqlGuard.check("select 'DROP TABLE x'").needReview());
    }

    @Test
    void deleteNeedsReviewEvenWithWhere() {
        var r = SqlGuard.check("DELETE FROM t1 WHERE id=1");
        assertTrue(r.needReview());
        assertEquals(List.of("t1"), r.tables());
    }

    @Test
    void truncateOnTempTablePasses() {
        assertFalse(SqlGuard.check("TRUNCATE TABLE temp_x").needReview());
    }

    @Test
    void commentsDoNotHideRealTable() {
        var r = SqlGuard.check("DROP /* c */ TABLE -- x\n orders");
        assertTrue(r.needReview());
        assertEquals(List.of("orders"), r.tables());
    }

    @Test
    void insertOverwriteNeedsReview() {
        var r = SqlGuard.check("INSERT OVERWRITE TABLE dw.orders SELECT * FROM tmp_a");
        assertTrue(r.needReview());
        assertEquals(List.of("dw.orders", "tmp_a"), r.tables());
    }

    @Test
    void onlyDestructiveStatementInMultiCounts() {
        assertTrue(SqlGuard.check("SELECT 1; DROP TABLE orders;").needReview());
    }

    @Test
    void lowercaseIsStillMatched() {
        assertFalse(SqlGuard.check("drop table tmp_x").needReview());
    }

    @Test
    void explainPrefixIsSkipped() {
        assertTrue(SqlGuard.check("EXPLAIN DELETE FROM t1").needReview());
    }

    @Test
    void alterTmpTablePasses() {
        assertFalse(SqlGuard.check("ALTER TABLE tmp_a ADD COLUMN c INT").needReview());
    }
}
