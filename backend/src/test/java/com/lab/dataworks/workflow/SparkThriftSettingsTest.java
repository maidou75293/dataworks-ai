package com.lab.dataworks.workflow;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** spark.thrift.* 配置解析：默认值、超时夹取、地址守卫，全程不连外部。 */
class SparkThriftSettingsTest {

    @Test
    void blanksFallBackToDefaults() {
        SparkThriftSettings settings = SparkThriftSettings.of(null, null, null, 0);
        assertEquals(SparkThriftSettings.DEFAULT_URL, settings.url());
        assertEquals("spark", settings.username());
        assertEquals("", settings.password());
        assertEquals(120, settings.queryTimeoutSeconds());
    }

    @Test
    void customValuesAreTrimmedAndKept() {
        SparkThriftSettings settings = SparkThriftSettings.of(
                "  jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/default  ",
                " etl ", "secret", 30);
        assertEquals("jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/default",
                settings.url());
        assertEquals("etl", settings.username());
        assertEquals("secret", settings.password());
        assertEquals(30, settings.queryTimeoutSeconds());
    }

    @Test
    void timeoutIsClamped() {
        assertEquals(3600, SparkThriftSettings.of(null, null, null, 99999)
                .queryTimeoutSeconds());
        assertEquals(120, SparkThriftSettings.of(null, null, null, -5)
                .queryTimeoutSeconds());
    }

    @Test
    void connectionFailuresAreRecognizedAcrossTheCauseChain() {
        RuntimeException wrapped = new RuntimeException("SPARK 试跑失败："
                + "Could not open client transport with JDBC Uri: "
                + "jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/",
                new java.net.ConnectException("Connection refused: connect"));
        assertTrue(SparkThriftSettings.unavailable(wrapped));
        assertTrue(SparkThriftSettings.unavailable(
                new java.net.UnknownHostException("spark-thrift.dw-spark.svc.cluster.local")));
        // SQL 语法错误不能被误判成「未就绪」
        assertFalse(SparkThriftSettings.unavailable(
                new RuntimeException("Error 1064: syntax error near 'selec'")));
        assertFalse(SparkThriftSettings.unavailable(
                new RuntimeException("只允许只读查询")));
    }

    @Test
    void unavailableHintCarriesAddressReasonAndNextStep() {
        String hint = SparkThriftSettings.unavailableHint(
                SparkThriftSettings.DEFAULT_URL,
                new RuntimeException("SPARK 试跑失败：Could not open client transport",
                        new java.net.ConnectException("Connection refused: connect")));

        assertTrue(hint.contains("Spark Thrift 未就绪"));
        assertTrue(hint.contains(SparkThriftSettings.DEFAULT_URL));
        assertTrue(hint.contains("Connection refused"));
        assertTrue(hint.contains("spark.thrift.url"));
        assertTrue(hint.contains("DORIS"));
    }

    @Test
    void unsafeUrlIsRejected() {
        // 本机 / 元数据地址即便写进配置也不能连
        assertThrows(ApiException.class,
                () -> SparkThriftSettings.of("jdbc:hive2://localhost:10000/", "spark", "", 30));
        assertThrows(ApiException.class,
                () -> SparkThriftSettings.of("jdbc:hive2://169.254.169.254:10000/", "spark", "", 30));
    }
}
