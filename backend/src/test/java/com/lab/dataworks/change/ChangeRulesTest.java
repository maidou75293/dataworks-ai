package com.lab.dataworks.change;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变更单 v1 纯规则：拆句、动词、备份计划、备份表名、审批前置判定
 */
class ChangeRulesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 26, 15, 30, 0);

    // ---------------------------------------------------------------- 拆句

    @Test
    void splitsOnTopLevelSemicolonsOnly() {
        List<String> stmts = ChangeRules.statements(
                "DROP TABLE t1; SELECT 'a;b' AS x; -- 注释里的分号 ;\nselect 2");
        assertEquals(3, stmts.size());
        assertEquals("DROP TABLE t1", stmts.get(0));
        assertTrue(stmts.get(1).contains("'a;b'"));
        // 注释属于下一条语句（执行时无副作用），拆句只认顶层分号
        assertTrue(stmts.get(2).endsWith("select 2"));
    }

    @Test
    void blankAndCommentOnlyStatementsAreDropped() {
        assertTrue(ChangeRules.statements(" ; -- nothing\n /* x */ ; ").isEmpty());
        assertTrue(ChangeRules.statements(null).isEmpty());
    }

    // ---------------------------------------------------------------- 动词

    @Test
    void verbSkipsCommentsAndPrefixWords() {
        assertEquals("DROP", ChangeRules.verb("-- 这是注释\nDROP TABLE t1"));
        assertEquals("TRUNCATE", ChangeRules.verb("/* x */ truncate table t1"));
        assertEquals("SELECT", ChangeRules.verb("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertEquals("UPDATE", ChangeRules.verb("EXPLAIN UPDATE t SET a = 1"));
        assertNull(ChangeRules.verb("-- 只有注释"));
    }

    // ---------------------------------------------------------------- 备份计划

    @Test
    void dropSingleTableNeedsBackup() {
        ChangeRules.BackupPlan plan = ChangeRules.backupPlan("DROP TABLE t1");
        assertEquals(ChangeRules.BackupKind.REQUIRED, plan.kind());
        assertEquals("t1", plan.table());
    }

    @Test
    void dropQualifiedAndIfExistsTableNeedsBackup() {
        assertEquals("db.t1", ChangeRules.backupPlan("DROP TABLE IF EXISTS db.t1").table());
        assertEquals("t1", ChangeRules.backupPlan("DROP TABLE IF EXISTS `t1`").table());
    }

    @Test
    void truncateSingleTableNeedsBackup() {
        ChangeRules.BackupPlan plan = ChangeRules.backupPlan("TRUNCATE TABLE t1");
        assertEquals(ChangeRules.BackupKind.REQUIRED, plan.kind());
        assertEquals("t1", plan.table());
    }

    @Test
    void multiTableDropIsRejectedInsteadOfPartialBackup() {
        ChangeRules.BackupPlan plan = ChangeRules.backupPlan("DROP TABLE a, b");
        assertEquals(ChangeRules.BackupKind.UNSUPPORTED, plan.kind());
        assertTrue(plan.message().contains("单表"));
    }

    @Test
    void nonDropStatementsNoBackup() {
        assertEquals(ChangeRules.BackupKind.NOT_NEEDED,
                ChangeRules.backupPlan("DELETE FROM t1 WHERE id = 1").kind());
        assertEquals(ChangeRules.BackupKind.NOT_NEEDED,
                ChangeRules.backupPlan("UPDATE t1 SET a = 1").kind());
        assertEquals(ChangeRules.BackupKind.NOT_NEEDED,
                ChangeRules.backupPlan("INSERT OVERWRITE TABLE t1 SELECT * FROM t2").kind());
        // ALTER 要审批，但 v1 不备份
        assertEquals(ChangeRules.BackupKind.NOT_NEEDED,
                ChangeRules.backupPlan("ALTER TABLE t1 ADD COLUMN c INT").kind());
    }

    @Test
    void backupNameAppendsTimestampToLastSegment() {
        assertEquals("db.t_bak_20261026153000", ChangeRules.backupName("db.t", AT));
        assertEquals("t_bak_20261026153000", ChangeRules.backupName("t", AT));
    }

    @Test
    void backupNameRejectsInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> ChangeRules.backupName("t; DROP TABLE x", AT));
        assertThrows(IllegalArgumentException.class,
                () -> ChangeRules.backupName("t`x", AT));
    }

    // ---------------------------------------------------------------- 审批前置

    @Test
    void requesterCannotApproveOwnRequest() {
        assertEquals("不能审批自己提交的变更单",
                ChangeRules.reviewGuard("alice", "alice", "PENDING"));
    }

    @Test
    void onlyPendingCanBeReviewed() {
        assertEquals("该变更单已经处理过了",
                ChangeRules.reviewGuard("alice", "bob", "DONE"));
        assertNull(ChangeRules.reviewGuard("alice", "bob", "PENDING"));
    }

    // ---------------------------------------------------------------- 执行通道

    @Test
    void onlyDorisAndSparkAreExecutable() {
        assertTrue(ChangeRules.executable("DORIS"));
        assertTrue(ChangeRules.executable("spark"));
        assertFalse(ChangeRules.executable("MYSQL"));
        assertFalse(ChangeRules.executable(null));
    }

    // ------------------------------------------------- D53：反向生成（四类语句）

    @Test
    void addColumnInverseIsDropColumn() {
        ChangeRules.InversePlan plan = ChangeRules.inversePlan(
                "ALTER TABLE db.t ADD COLUMN c1 INT NULL", null);
        assertFalse(plan.irreversible());
        assertEquals("ALTER TABLE db.t DROP COLUMN c1", plan.inverseSql());
    }

    @Test
    void addColumnWithoutKeywordAndIfNotExistsInverse() {
        assertEquals("ALTER TABLE t DROP COLUMN c1",
                ChangeRules.inversePlan("ALTER TABLE t ADD c1 BIGINT", null).inverseSql());
        assertEquals("ALTER TABLE t DROP COLUMN c1",
                ChangeRules.inversePlan(
                        "ALTER TABLE t ADD COLUMN IF NOT EXISTS c1 INT", null).inverseSql());
    }

    @Test
    void addColumnListInverseIsOneDropPerColumn() {
        ChangeRules.InversePlan plan = ChangeRules.inversePlan(
                "ALTER TABLE db.t ADD COLUMN (a INT, b VARCHAR(10))", null);
        assertFalse(plan.irreversible());
        assertEquals("ALTER TABLE db.t DROP COLUMN a;\nALTER TABLE db.t DROP COLUMN b",
                plan.inverseSql());
    }

    @Test
    void addColumnWithDecimalDefaultKeepsSingleColumn() {
        // DECIMAL(10,2) 里的逗号不能被当成多列
        assertEquals("ALTER TABLE t DROP COLUMN amount",
                ChangeRules.inversePlan(
                        "ALTER TABLE t ADD COLUMN amount DECIMAL(10,2) NULL", null)
                        .inverseSql());
    }

    @Test
    void addIndexIsNotAddColumnAndStaysIrreversible() {
        assertTrue(ChangeRules.inversePlan(
                "ALTER TABLE t ADD INDEX idx_a (a)", null).irreversible());
        assertTrue(ChangeRules.inversePlan(
                "ALTER TABLE t ADD KEY idx_a (a)", null).irreversible());
        assertTrue(ChangeRules.inversePlan(
                "ALTER TABLE t ADD PRIMARY KEY (id)", null).irreversible());
    }

    @Test
    void createTableInverseIsDropTableWithConfirmNote() {
        ChangeRules.InversePlan plan = ChangeRules.inversePlan(
                "CREATE TABLE db.t (id INT) ...", null);
        assertFalse(plan.irreversible());
        assertEquals("DROP TABLE db.t", plan.inverseSql());
        assertTrue(plan.note().contains("需确认"));
        // IF NOT EXISTS / AS SELECT 同样识别
        assertEquals("DROP TABLE db.t2", ChangeRules.inversePlan(
                "CREATE TABLE IF NOT EXISTS db.t2 AS SELECT * FROM src", null).inverseSql());
    }

    @Test
    void dropAndTruncateWithBackupInverseIsInsertFromBackup() {
        String backup = "db.t_bak_20261026153000";
        // TRUNCATE：表结构还在，直接回灌（卡面口径）
        assertEquals("INSERT INTO db.t SELECT * FROM " + backup,
                ChangeRules.inversePlan("TRUNCATE TABLE db.t", backup).inverseSql());
        // DROP：表结构也没了，只 INSERT 必然失败 → 先 LIKE 备份表重建结构再回灌
        ChangeRules.InversePlan drop = ChangeRules.inversePlan("DROP TABLE db.t", backup);
        assertEquals("CREATE TABLE IF NOT EXISTS db.t LIKE " + backup + ";\n"
                + "INSERT INTO db.t SELECT * FROM " + backup, drop.inverseSql());
        assertTrue(drop.note().contains("重建"));
    }

    @Test
    void dropWithoutBackupUsesPlaceholder() {
        ChangeRules.InversePlan plan = ChangeRules.inversePlan("DROP TABLE db.t", null);
        assertFalse(plan.irreversible());
        assertTrue(plan.inverseSql().contains(ChangeRules.BACKUP_PLACEHOLDER));
        assertEquals("CREATE TABLE IF NOT EXISTS db.t LIKE " + ChangeRules.BACKUP_PLACEHOLDER
                        + ";\nINSERT INTO db.t SELECT * FROM " + ChangeRules.BACKUP_PLACEHOLDER,
                plan.inverseSql());
    }

    @Test
    void dropColumnAndModifyColumnAreIrreversible() {
        assertTrue(ChangeRules.inversePlan(
                "ALTER TABLE db.t DROP COLUMN c1", null).irreversible());
        assertTrue(ChangeRules.inversePlan(
                "ALTER TABLE db.t MODIFY COLUMN c1 BIGINT", null).irreversible());
        assertTrue(ChangeRules.inversePlan(
                "ALTER TABLE db.t CHANGE COLUMN c1 c2 BIGINT", null).irreversible());
    }

    @Test
    void deleteUpdateInsertAreIrreversible() {
        assertTrue(ChangeRules.inversePlan("DELETE FROM t WHERE id = 1", null).irreversible());
        assertTrue(ChangeRules.inversePlan("UPDATE t SET a = 1", null).irreversible());
        assertTrue(ChangeRules.inversePlan("INSERT INTO t VALUES (1)", null).irreversible());
    }

    @Test
    void unknownAlterShapesFailClosedToIrreversible() {
        assertTrue(ChangeRules.inversePlan("ALTER TABLE t RENAME t2", null).irreversible());
        assertTrue(ChangeRules.inversePlan("ALTER VIEW v AS SELECT 1", null).irreversible());
        // 混合操作：ADD COLUMN + DROP COLUMN → 整句不可逆
        assertTrue(ChangeRules.inversePlan(
                "ALTER TABLE t ADD COLUMN a INT, DROP COLUMN b", null).irreversible());
        // 注释里的假动词不影响判定
        assertFalse(ChangeRules.inversePlan(
                "-- drop table x\nALTER TABLE t ADD COLUMN a INT", null).irreversible());
    }

    @Test
    void rollbackPlanReversesStatementOrder() {
        ChangeRules.RollbackPlan plan = ChangeRules.rollbackPlan(
                "CREATE TABLE db.a (id INT); ALTER TABLE db.b ADD COLUMN c INT", List.of());
        assertFalse(plan.irreversible());
        // 逆序：先撤销 ADD COLUMN，再撤销 CREATE TABLE
        assertEquals("ALTER TABLE db.b DROP COLUMN c;\nDROP TABLE db.a", plan.inverseSql());
        assertTrue(plan.note().contains("需确认"));
    }

    @Test
    void rollbackPlanConsumesBackupsInStatementOrder() {
        ChangeRules.RollbackPlan plan = ChangeRules.rollbackPlan(
                "DROP TABLE db.a; TRUNCATE TABLE db.b",
                List.of("db.a_bak_20261026153000", "db.b_bak_20261026153100"));
        assertFalse(plan.irreversible());
        // 逆序：先恢复 b（后执行的），再恢复 a；DROP 的回滚带重建结构
        assertEquals("INSERT INTO db.b SELECT * FROM db.b_bak_20261026153100;\n"
                + "CREATE TABLE IF NOT EXISTS db.a LIKE db.a_bak_20261026153000;\n"
                + "INSERT INTO db.a SELECT * FROM db.a_bak_20261026153000", plan.inverseSql());
    }

    @Test
    void rollbackPlanAnyIrreversibleMakesWholePlanIrreversible() {
        ChangeRules.RollbackPlan plan = ChangeRules.rollbackPlan(
                "ALTER TABLE db.a ADD COLUMN c INT; ALTER TABLE db.b DROP COLUMN d", List.of());
        assertTrue(plan.irreversible());
        // 可反向的那句仍然给出，供人工参考
        assertEquals("ALTER TABLE db.a DROP COLUMN c", plan.inverseSql());
    }

    @Test
    void rollbackPlanSkipsReadOnlyStatements() {
        ChangeRules.RollbackPlan plan = ChangeRules.rollbackPlan(
                "SET x = 1; SELECT * FROM t; TRUNCATE TABLE db.a",
                List.of("db.a_bak_20261026153000"));
        assertFalse(plan.irreversible());
        assertEquals("INSERT INTO db.a SELECT * FROM db.a_bak_20261026153000", plan.inverseSql());
    }

    // ------------------------------------------------- D53：备份表名工具

    @Test
    void isBackupNameOnlyAcceptsPlatformShape() {
        assertTrue(ChangeRules.isBackupName("db.t_bak_20261026153000"));
        assertTrue(ChangeRules.isBackupName("t_bak_20261026153000"));
        assertFalse(ChangeRules.isBackupName("db.t"));
        assertFalse(ChangeRules.isBackupName("db.t_bak_abc"));
        // 注入形态一律拒绝
        assertFalse(ChangeRules.isBackupName("t_bak_20261026153000; DROP TABLE x"));
        assertFalse(ChangeRules.isBackupName(null));
    }

    @Test
    void backupTimeAndSourceOfRoundTrip() {
        assertEquals(AT, ChangeRules.backupTime("db.t_bak_20261026153000"));
        assertEquals("db.t", ChangeRules.sourceOf("db.t_bak_20261026153000"));
        assertNull(ChangeRules.backupTime("db.t"));
        assertNull(ChangeRules.backupTime("db.t_bak_20261026999999")); // 非法时间
        assertEquals("db.t", ChangeRules.sourceOf("db.t")); // 非备份名原样返回
    }
}
