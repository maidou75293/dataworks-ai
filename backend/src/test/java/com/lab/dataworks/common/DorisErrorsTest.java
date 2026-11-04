package com.lab.dataworks.common;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D58：Doris / JDBC 报错的统一映射。
 *
 * <p>卡面四类必须全覆盖：库表不存在 → 404、权限不足 → 403、语法错 → 400、连接失败 → 502，
 * 且四类都不得回显 {@code errCode / detailMessage / bad SQL grammar} 这类内部原文。</p>
 */
class DorisErrorsTest {

    /** D58 卡面的复现原文（Doris 报「表不存在」，以前被一律包成 502 + 原文）。 */
    private static final String REPRO =
            "Doris 执行失败: errCode = 2, detailMessage = Unknown table 't_1793035231' in bug_a_env_1793035231";

    @Test
    void unknownTableMapsTo404AndDropsRawText() {
        ApiException error = DorisErrors.toApiException(new RuntimeException(REPRO),
                "删除表 bug_a_env_1793035231.t_1793035231");

        assertEquals(404, error.getStatus());
        assertTrue(error.getMessage().contains("表不存在"), error.getMessage());
        assertTrue(error.getMessage().contains("bug_a_env_1793035231.t_1793035231"),
                "要说清是哪个对象: " + error.getMessage());
        assertFalse(error.getMessage().contains("errCode"), "不能回显 Doris 原文: " + error.getMessage());
        assertFalse(error.getMessage().contains("detailMessage"), error.getMessage());
    }

    @Test
    void unknownDatabaseIsCalledDatabaseNotTable() {
        ApiException error = DorisErrors.toApiException(
                new RuntimeException("Doris 执行失败: errCode = 2, detailMessage = Unknown database 'ghost_db'"),
                "查看库 ghost_db 的表清单");

        assertEquals(404, error.getStatus());
        assertTrue(error.getMessage().contains("库不存在"), error.getMessage());
        assertTrue(error.getMessage().contains("ghost_db"), error.getMessage());
    }

    @Test
    void accessDeniedMapsTo403() {
        ApiException error = DorisErrors.toApiException(
                new RuntimeException("Doris 执行失败: errAccess, Access denied for user 'reader'@'%'"),
                "建表 demo_retail.t_new");

        assertEquals(403, error.getStatus());
        assertTrue(error.getMessage().contains("权限"), error.getMessage());
        assertFalse(error.getMessage().contains("reader"), "账号名不能进响应: " + error.getMessage());
    }

    @Test
    void syntaxErrorMapsTo400() {
        ApiException error = DorisErrors.toApiException(
                new RuntimeException("Doris 执行失败: errCode = 2, detailMessage = err_parse. "
                        + "Syntax error in line 1, syntax like \"CREATE TABLE\""),
                "按 SQL 建表 demo_retail.t_new");

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("SQL"), error.getMessage());
        assertFalse(error.getMessage().contains("err_parse"), error.getMessage());
    }

    @Test
    void connectionFailureStays502ButWithoutRawText() {
        ApiException error = DorisErrors.toApiException(
                new RuntimeException("Doris 执行失败: CommunicationsException: Communications link failure, "
                        + "connection refused to 10.99.99.11:9030"),
                "删除表 demo_retail.t_old");

        assertEquals(502, error.getStatus());
        assertTrue(error.getMessage().contains("连不上"), error.getMessage());
        assertFalse(error.getMessage().contains("Communications link failure"), error.getMessage());
        assertFalse(error.getMessage().contains("10.99.99.11"), "内网地址不能进响应: " + error.getMessage());
    }

    @Test
    void timeoutIsDistinguishedFromUnreachable() {
        assertEquals(DorisErrors.Cause.TIMEOUT, DorisErrors.classify(
                new RuntimeException("Doris 查询失败: query exceeded timeout, canceling statement due to")));
        assertEquals(504, DorisErrors.httpStatus(DorisErrors.Cause.TIMEOUT));
    }

    @Test
    void alreadyExistsMapsTo409() {
        ApiException error = DorisErrors.toApiException(
                new RuntimeException("Doris 执行失败: Table demo_retail.t_new already exists"),
                "建表 demo_retail.t_new");

        assertEquals(409, error.getStatus());
        assertTrue(error.getMessage().contains("已存在"), error.getMessage());
    }

    @Test
    void sqlStateWinsBeforeKeywordGuessing() {
        assertEquals(DorisErrors.Cause.MISSING_OBJECT,
                DorisErrors.classify(new SQLException("relation \"x\" does not exist", "42P01", 0)));
        assertEquals(DorisErrors.Cause.UNREACHABLE,
                DorisErrors.classify(new SQLException("could not connect", "08001", 0)));
        assertEquals(DorisErrors.Cause.DENIED,
                DorisErrors.classify(new SQLException("permission denied", "42501", 0)));
    }

    /** 平台自己抛的业务 4xx（无权操作该库之类）不能被改写掉。 */
    @Test
    void ownApiExceptionsPassThroughUnchanged() {
        ApiException own = new ApiException(403, "无权操作该库: secret_db");

        assertSame(own, DorisErrors.toApiException(own, "建库 secret_db"));
    }

    /** 但 502 + 引擎原文的那种 ApiException（DorisAdminService 的老口径）要被映射掉。 */
    @Test
    void legacyDorisApiExceptionIsRemapped() {
        ApiException mapped = DorisErrors.toApiException(new ApiException(502, REPRO), "删除表 x.y");

        assertEquals(404, mapped.getStatus());
        assertFalse(mapped.getMessage().contains("errCode"), mapped.getMessage());
    }

    /** 未归类的内部错误保持 502，但不带原文。 */
    @Test
    void unknownInternalErrorStays502WithoutRawText() {
        ApiException error = DorisErrors.toApiException(
                new IllegalStateException("host=10.0.0.1 user=admin password=hunter2"), "执行表变更");

        assertEquals(502, error.getStatus());
        assertFalse(error.getMessage().contains("hunter2"), error.getMessage());
        assertFalse(error.getMessage().contains("10.0.0.1"), error.getMessage());
    }

    @Test
    void objectHintOnlyAcceptsSafeNames() {
        assertEquals("bug_a_env_1793035231.t_1793035231",
                DorisErrors.objectHint(new RuntimeException(REPRO)));
        String hostile = DorisErrors.objectHint(
                new RuntimeException("Unknown table 'a`;drop table x--' in db"));
        assertTrue(hostile == null || hostile.matches("[A-Za-z0-9_.]+"),
                "带引号/分号/破折号的片段不能进响应: " + hostile);
        assertNull(DorisErrors.objectHint(new RuntimeException("Doris 查询失败: 未知错误")));
    }

    @Test
    void rawKeepsWholeChainForLogsAndAudit() {
        Throwable root = new SQLException("errCode = 2, detailMessage = Unknown table 't' in 'db'");
        Throwable wrapped = new RuntimeException("Doris 执行失败: " + root.getMessage(), root);

        String raw = DorisErrors.raw(wrapped);

        assertTrue(raw.contains("SQLException"), raw);
        assertTrue(raw.contains("Unknown table"), "原文要能进日志/审计: " + raw);
        assertNotNull(DorisErrors.objectHint(wrapped));
    }
}
