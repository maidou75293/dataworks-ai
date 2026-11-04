package com.lab.dataworks.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.integration.ConnectorCatalog;
import com.lab.dataworks.lake.LakeCatalogService;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D62（TC-2 用例 DS-04）：数据源类型是必填，缺失必须 400，不许静默默认成 POSTGRESQL。
 *
 * <p>缺陷现场（v539 live 实测）：{@code POST /api/projects/1/datasources} 不带 {@code type}
 * 时返回 {@code 200 {"ok":true,"id":66}}，落库 {@code ds_type=POSTGRESQL}。用户得到一个
 * 类型错的数据源，测试连接与同步任务全按 PG 方言解析，错乱点被推到更下游。
 * 违反 acceptance-rules 第 4 条「必填字段缺失必须显式报错，禁止 setDefault 掩盖」。</p>
 *
 * <p>要同时锁住「不要改坏」：类型的大小写/首尾空白照常宽容、未知类型仍是原来那句 400、
 * 未保存测试的既有豁免只针对 <b>name</b>（先测后存），类型不在豁免范围。</p>
 */
class DataSourceTypeRequiredTest {

    private JdbcTemplate jdbc;
    private DataSourceController controller;

    @BeforeEach
    void setUp() {
        JdbcUrlGuard.resetAllowlist();
        jdbc = mock(JdbcTemplate.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.auditAround(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(5)).get());
        ConnectorCatalog catalog = mock(ConnectorCatalog.class);
        controller = new DataSourceController(jdbc, sessions, mock(SecretCipher.class),
                new ObjectMapper(), catalog, mock(WarehouseResolver.class),
                mock(LakeCatalogService.class), mock(GravitinoCatalogSync.class),
                mock(GravitinoCatalogAdmin.class), mock(MetadataReader.class), "dataworks", "");

        when(jdbc.queryForObject(contains("from platform.project where id"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("from platform.datasource where project_id"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("insert into platform.datasource"), eq(Long.class),
                any(Object[].class))).thenReturn(66L);
        when(jdbc.update(contains("update platform.datasource"), any(Object[].class)))
                .thenReturn(1);
    }

    private static DataSourceController.DataSourceRequest req(String type) {
        // 卡面 repro 的原文：只有 name / jdbcUrl / username / password，没有 type
        return new DataSourceController.DataSourceRequest(
                "bug_ads037329_4", type,
                "jdbc:postgresql://pg.dw-dev.svc.cluster.local:5432/bug_a_none",
                "bug_a_user", "NotARealPassword1!", null, null, null, null, null, null);
    }

    private static void assertNoInsert(JdbcTemplate jdbc) {
        verify(jdbc, never()).queryForObject(contains("insert into platform.datasource"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void missingTypeOnCreateIs400NotSilentPostgresql() {
        ApiException e = assertThrows(ApiException.class, () -> controller.create(1L, req(null), null));

        assertEquals(400, e.getStatus(), "必填字段缺失不能 200");
        assertTrue(e.getMessage().contains("数据源类型不能为空"), e.getMessage());
        assertNoInsert(jdbc);
    }

    @Test
    void blankTypeOnCreateIs400Too() {
        for (String type : List.of("", "   ", "\t\n")) {
            ApiException e = assertThrows(ApiException.class,
                    () -> controller.create(1L, req(type), null), "type=[" + type + "]");
            assertEquals(400, e.getStatus(), "type=[" + type + "]");
            assertTrue(e.getMessage().contains("数据源类型不能为空"), e.getMessage());
        }
        assertNoInsert(jdbc);
    }

    @Test
    void missingTypeOnUpdateIs400() {
        stubFind("POSTGRESQL");

        ApiException e = assertThrows(ApiException.class, () -> controller.update(11L, req(null), null));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("数据源类型不能为空"), e.getMessage());
        verify(jdbc, never()).update(contains("update platform.datasource"), any(Object[].class));
    }

    /** 未保存的连接测试：豁免的是 name（先测后存），类型仍是必填。 */
    @Test
    void missingTypeOnTestUnsavedIs400() {
        DataSourceController.DataSourceRequest withoutName =
                new DataSourceController.DataSourceRequest(null, null,
                        "jdbc:postgresql://pg.dw-dev.svc.cluster.local:5432/x",
                        "u", "p", null, null, null, null, null, null);

        ApiException e = assertThrows(ApiException.class, () -> controller.testUnsaved(withoutName));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("数据源类型不能为空"), e.getMessage());
    }

    /** 修 BUG 不能顺手把合法输入判死：大小写与首尾空白照旧宽容，并真按归一后的类型落库。 */
    @Test
    void typeIsStillCaseAndSpaceTolerant() {
        controller.create(1L, spark("  SPaRk  "), null);

        // insert 参数序：project_id, name, ds_type, jdbc_url, username, password,
        //               config, tenant, engine, queryable, env(V116)
        verify(jdbc).queryForObject(contains("insert into platform.datasource"), eq(Long.class),
                eq(1L), eq("spark_ok"), eq("SPARK"),
                any(), any(), any(), any(), any(), eq("jdbc"), eq(true), eq("DEV"));
    }

    /** 未知类型的原有 400 文案不许被新分支替换掉（那句会列出全部合法取值，是有用的）。 */
    @Test
    void unknownTypeKeepsItsOwnMessage() {
        ApiException e = assertThrows(ApiException.class, () -> controller.create(1L, req("NOPE"), null));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("数据源类型只能是"), e.getMessage());
        assertNoInsert(jdbc);
    }

    // ---------------------------------------------------------------- fixtures

    private static DataSourceController.DataSourceRequest spark(String type) {
        return new DataSourceController.DataSourceRequest("spark_ok", type,
                "jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/default",
                "hive", null, null, null, null, null, null, null);
    }

    private void stubFind(String type) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 11L);
        row.put("project_id", 1L);
        row.put("name", "old_name");
        row.put("ds_type", type);
        row.put("jdbc_url", "jdbc:postgresql://pg.dw-dev.svc.cluster.local:5432/x");
        row.put("username", "u");
        row.put("password", "cipher-text");
        row.put("config_json", "{}");
        row.put("tenant_id", "default");
        when(jdbc.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null
                        && sql.contains("join platform.project p")), any(Object[].class)))
                .thenReturn(List.of(row));
    }
}
