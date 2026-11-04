package com.lab.dataworks.datasource;

import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D64（TC-5 用例 T5-DS-03 / T5-DS-06）：连接失败的 {@code error} 字段不得透传驱动原文。
 *
 * <p>缺陷现场（v539 live）：口令错误时接口 200 返回
 * {@code {"ok":false,"error":"Access denied for user 'bug_a_user@10.99.99.13'
 * (using password: YES)"}}。{@code 10.99.99.13} 是<b>后端 Pod 在集群内的源地址</b>
 * （调用方从没提供过），{@code (using password: YES)} 还泄露「服务端收到了密码」。
 * 这条还会被 {@code recordTest} 存进 {@code last_test_message} 并在列表回显，
 * 于是一次失败口令 = 长期挂在页面上的内部拓扑。</p>
 *
 * <p>要钉住的是：<b>分类给中文可执行文案，原文只进日志</b>；
 * 归不了类也不许把原文（含 {@code 'user'@'host'}、IP、SQLState 文本）放进响应。</p>
 */
class DataSourceConnectionFailureTest {

    /** 卡面原文（MySQL/Doris 1045 家族）。 */
    private static final SQLException ACCESS_DENIED = new SQLException(
            "Access denied for user 'bug_a_user@10.99.99.13' (using password: YES)", "28000", 1045);

    @Test
    void wrongPasswordBecomesChineseWithoutTopology() {
        String message = DataSourceController.connectionFailure(ACCESS_DENIED);

        assertEquals("账号或密码不正确", message);
        assertFalse(message.contains("10.99.99.13"), message);
        assertFalse(message.contains("@"), message);
        assertFalse(message.toLowerCase().contains("using password"), message);
    }

    /** PostgreSQL 的口令错误只有 SQLState 28P01，文本里没有 "access denied"。 */
    @Test
    void pgAuthFailureIsClassifiedBySqlState() {
        SQLException e = new SQLException(
                "FATAL: password authentication failed for user \"bug_a_user\"", "28P01");
        assertTrue(DataSourceController.connectionFailure(e).contains("账号或密码不正确"),
                DataSourceController.connectionFailure(e));
    }

    @Test
    void connectivityFailuresSayCheckHostPortNetwork() {
        for (Exception e : java.util.List.of(
                new SQLException("Communications link failure\n\nThe last packet sent "
                        + "successfully to the server was 0 milliseconds ago.", "08S01"),
                new SQLException("java.net.ConnectException: Connection refused (connect refused)", "08001"),
                new RuntimeException("connect timed out after 5000 milliseconds"))) {
            String message = DataSourceController.connectionFailure(e);
            assertTrue(message.contains("无法连接到数据库地址"), e.getMessage() + " → " + message);
            assertFalse(message.contains("Connection refused"), message);
        }
    }

    @Test
    void unknownHostIsClassified() {
        UnknownHostException e = new UnknownHostException("nope.svc.cluster.local");
        String message = DataSourceController.connectionFailure(e);
        assertTrue(message.contains("无法连接到数据库地址"), message);
        assertFalse(message.contains("nope.svc"), message);
    }

    @Test
    void missingSchemaIsActionable() {
        SQLException e = new SQLException("Unknown database 'bug_a_none'", "42000");
        assertEquals("JDBC 地址里的库（database/schema）不存在，请检查地址",
                DataSourceController.connectionFailure(e));
    }

    /** 兜底也必须消毒：未归类的原文一个字都不许进响应。 */
    @Test
    void unclassifiedNeverLeaksRawText() {
        SQLException e = new SQLException(
                "internal weirdness at host 10.42.0.7:5432 for 'u'@'h'", "99999");

        String message = DataSourceController.connectionFailure(e);

        assertFalse(message.contains("10.42.0.7"), message);
        assertFalse(message.contains("'u'@'h'"), message);
        assertFalse(message.contains("internal weirdness"), message);
        assertTrue(message.contains("连接失败"), message);
    }

    /** 文案要能指向下一步动作，不能只剩「失败」两个字（否则用户只能猜）。 */
    @Test
    void everyBranchPointsToAnAction() {
        for (Exception e : java.util.List.of(
                ACCESS_DENIED,
                new SQLException("Communications link failure", "08S01"),
                new SQLException("Unknown database 'x'", "42000"),
                new SQLException("SSL connection error: certificate unknown", "08001"),
                new SQLException("something nobody has seen", "01000"))) {
            String message = DataSourceController.connectionFailure(e);
            assertTrue(message.length() >= 6, message);
            assertFalse(message.isBlank());
            // 中文文案，不是驱动短语
            assertTrue(message.chars().anyMatch(c -> c > 0x2E80),
                    "应当是中文： " + message);
        }
    }
}
