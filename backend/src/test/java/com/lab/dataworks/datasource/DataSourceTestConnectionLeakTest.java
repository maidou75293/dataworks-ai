package com.lab.dataworks.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.integration.ConnectorCatalog;
import com.lab.dataworks.lake.LakeCatalogService;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D64 的**接线**证据：{@code testConnection} 真的把失败原因送进分类器，而不是原样回显。
 *
 * <p>为什么单独立一个文件：{@code DataSourceConnectionFailureTest} 只测分类函数本身，
 * 哪怕调用点写回 {@code e.getMessage()} 它也全绿 —— 那就是「空检查比没有检查更糟」。
 * 这条用例走公开入口 {@code POST /api/datasources/test} 的实现方法 {@code testUnsaved}，
 * 断的是**出口文案**。</p>
 *
 * <p>不连真库：用一个只在集群内才能解析的 PG 主机名，在测试环境里必然连不上
 * （DNS 解析失败或连接被拒），走的就是「连不上」这条分类分支。
 * 5 秒登录超时 + {@code JdbcTimeouts} 保证不会挂住。</p>
 */
class DataSourceTestConnectionLeakTest {

    private static final String CLUSTER_ONLY_URL =
            "jdbc:postgresql://doris-fe.dw-dev.svc.cluster.local:5499/does_not_matter";

    private DataSourceController controller;

    @BeforeEach
    void setUp() {
        JdbcUrlGuard.resetAllowlist();
        // testUnsaved 不碰库（id 为空 → recordTest 直接 return），所以 jdbc 只要是个 mock 就行
        controller = new DataSourceController(mock(JdbcTemplate.class), mock(SessionService.class),
                mock(SecretCipher.class), new ObjectMapper(), mock(ConnectorCatalog.class),
                mock(WarehouseResolver.class), mock(LakeCatalogService.class),
                mock(GravitinoCatalogSync.class), mock(GravitinoCatalogAdmin.class),
                mock(MetadataReader.class), "dataworks", "");
    }

    @Test
    void unreachableHostReturnsChineseClassifiedError() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "leak_probe", "POSTGRESQL", CLUSTER_ONLY_URL, "some_user", "some_password",
                null, null, null, null, null, null);

        Map<String, Object> out = controller.testUnsaved(req);

        assertEquals(false, out.get("ok"), String.valueOf(out));
        String error = String.valueOf(out.get("error"));
        assertTrue(error.contains("无法连接到数据库地址"), "应当给中文分类: " + error);
        // 三条泄露特征：驱动原文、user@host、集群内端点/端口
        assertFalse(error.contains("Communications link failure"), error);
        assertFalse(error.contains("Connection refused"), error);
        assertFalse(error.contains("some_user@"), error);
        assertFalse(error.toLowerCase().contains("using password"), error);
        // 地址是调用方自己填的，可以出现在响应里；但**驱动原文里夹带的后端源地址**不行
        assertFalse(error.contains("10.99.99."), "后端源地址不许透出: " + error);
    }

    /** 出网守卫仍在分类之前（内网/元数据地址是 400，不是被分类成连接失败）。 */
    @Test
    void guardStillRejectsMetadataAddressBeforeClassifying() {
        DataSourceController.DataSourceRequest req = new DataSourceController.DataSourceRequest(
                "leak_probe2", "POSTGRESQL",
                "jdbc:postgresql://169.254.169.254:5432/x", "u", "p", null, null, null, null, null, null);

        com.lab.dataworks.common.ApiException e = org.junit.jupiter.api.Assertions.assertThrows(
                com.lab.dataworks.common.ApiException.class, () -> controller.testUnsaved(req));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("元数据"), e.getMessage());
    }
}
