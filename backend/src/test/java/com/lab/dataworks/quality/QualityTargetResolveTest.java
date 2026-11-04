package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisTargetService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D56：质量规则的数据源归属。
 *
 * <p>旧口径「不传 datasourceId 就写 PG」让 Doris 表的门禁必然执行失败，还回显数据库原文。
 * 这里覆盖新口径的三条主线：按表名解析（含跟随项目数仓）、判不出来 400、绑错数据源 400。</p>
 */
class QualityTargetResolveTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DorisTargetService targets = mock(DorisTargetService.class);
    private final WarehouseResolver warehouses = mock(WarehouseResolver.class);

    /** 探针结果由测试决定：真实连接由 live 脚本验，单测不碰网络。 */
    private static class ProbingService extends QualityService {
        String warehouseHit = "orders";
        String boundHit = "orders";
        RuntimeException warehouseFailure;
        RuntimeException boundFailure;

        ProbingService(JdbcTemplate jdbc, DorisTargetService targets, WarehouseResolver warehouses) {
            super(jdbc, new ObjectMapper(), new org.springframework.boot.web.client.RestTemplateBuilder(),
                    "http://mcp", "", mock(SecretCipher.class), targets, warehouses,
                    mock(com.lab.dataworks.project.ProjectEnvironmentService.class));
        }

        @Override
        String probeValue(DorisTargetService.Target target, String sql) {
            if (warehouseFailure != null) {
                throw warehouseFailure;
            }
            return warehouseHit;
        }

        @Override
        String probeValueOnBoundDatasource(long datasourceId, String sql) {
            if (boundFailure != null) {
                throw boundFailure;
            }
            return boundHit;
        }
    }

    private ProbingService service() {
        return new ProbingService(jdbc, targets, warehouses);
    }

    private static Map<String, Object> projectRow(String devDb, String prodDb, Long warehouseDsId,
                                                  String dsType, String dsName, boolean builtin) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dev_database", devDb);
        row.put("prod_database", prodDb);
        row.put("warehouse_datasource_id", warehouseDsId);
        row.put("datasource_type", dsType);
        row.put("datasource_name", dsName);
        row.put("datasource_builtin", String.valueOf(builtin));
        return row;
    }

    private void givenProject(String devDb, String prodDb, Long warehouseDsId,
                              String dsType, String dsName, boolean builtin) {
        when(jdbc.queryForList(contains("where p.id = ?"), any(Object[].class)))
                .thenReturn(List.of(projectRow(devDb, prodDb, warehouseDsId, dsType, dsName, builtin)));
    }

    /** 本租户里另有项目管着这个库（库名在 V49 上有唯一索引，最多一行）。 */
    private void givenDatabaseOwnedByProject(String name, Long dsId, boolean builtin) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 77L);
        row.put("name", name);
        row.put("warehouse_datasource_id", dsId);
        row.put("builtin", String.valueOf(builtin));
        when(jdbc.queryForList(contains("p.tenant_id = ?"), any(Object[].class))).thenReturn(List.of(row));
    }

    private void givenBuiltinDatasource(long id) {
        when(warehouses.builtinDatasourceId()).thenReturn(id);
    }

    private void givenPgSchema(String schema, boolean exists) {
        when(jdbc.queryForObject(contains("information_schema.schemata"), eq(Integer.class),
                any(Object[].class))).thenReturn(exists ? 1 : 0);
    }

    private void givenBuiltinTarget() {
        when(targets.forProject(any())).thenReturn(
                new DorisTargetService.Target(false, "内置 Doris（10.99.99.11:9030）", null, null, null));
    }

    // ------------------------------------------------------------- 按表名解析

    @Test
    void dorisTableWithoutDatasourceResolvesToDoris() {
        givenProject("demo_retail", "demo_retail_prd", 19L, "DORIS", "内置 Doris", true);
        givenBuiltinDatasource(19L);

        QualityService.RuleTarget target = service()
                .bindTarget(1, "demo_retail.ods_order", null, null);

        assertEquals("DORIS", target.engine(), "Doris 项目的表不能再默认 PG");
        assertEquals(19L, target.datasourceId(), "datasource_id 要指向 Doris 数据源");
        assertEquals("demo_retail", target.database());
    }

    /** 裸表名跟随项目数仓（平台口径：查询、元数据、质量、试跑都按项目路由）。 */
    @Test
    void bareTableNameFollowsProjectWarehouse() {
        givenProject("demo_retail", null, null, null, null, false);
        givenBuiltinDatasource(19L);

        QualityService.RuleTarget target = service().bindTarget(1, "ods_order", null, null);

        assertEquals("DORIS", target.engine());
        assertEquals(19L, target.datasourceId());
    }

    /** 项目没接数仓时才退回平台库，保持历史规则可执行。 */
    @Test
    void bareTableNameFallsBackToPlatformWhenProjectHasNoWarehouse() {
        givenProject(null, null, null, null, null, false);

        QualityService.RuleTarget target = service().bindTarget(1, "demo_metric", null, null);

        assertEquals("PG", target.engine());
        assertEquals(null, target.datasourceId());
    }

    /** 库名不是任何项目的数仓库、但在内置数仓里真实存在（测试自建库这类）→ Doris。 */
    @Test
    void unregisteredDatabaseFoundByProbeResolvesToDoris() {
        givenProject("demo_retail", null, null, null, null, false);
        givenBuiltinDatasource(19L);
        givenPgSchema("fm_orders_911144", false);
        givenBuiltinTarget();

        QualityService.RuleTarget target = service()
                .bindTarget(1, "fm_orders_911144.orders", null, null);

        assertEquals("DORIS", target.engine());
        assertEquals(19L, target.datasourceId());
    }

    @Test
    void platformSchemaStillResolvesToPg() {
        givenProject("demo_retail", null, 19L, "DORIS", "内置 Doris", true);
        givenPgSchema("platform", true);

        QualityService.RuleTarget target = service().bindTarget(1, "platform.workflow", null, null);

        assertEquals("PG", target.engine());
        assertEquals(null, target.datasourceId(), "平台库模式没有对应数据源行，保持按引擎执行");
    }

    /** 表管理里登记成 Doris 对象的表，不必连数仓也能定归属。 */
    @Test
    void managedDorisTableResolvesWithoutProbing() {
        givenProject("demo_retail", null, null, null, null, false);
        givenBuiltinDatasource(19L);
        givenPgSchema("warehouse_x", false);
        when(jdbc.queryForObject(contains("platform.managed_table"), eq(Integer.class),
                any(Object[].class))).thenReturn(1);
        ProbingService service = service();
        service.warehouseHit = null; // 探针不该被用到：登记信息已经够定归属

        QualityService.RuleTarget target = service.bindTarget(1, "warehouse_x.t_day", null, null);

        assertEquals("DORIS", target.engine());
    }

    // ------------------------------------------------------ 判不出来就 400 说清

    @Test
    void unknownDatabaseIsRejectedWithActionableMessage() {
        givenProject("demo_retail", null, null, null, null, false);
        givenPgSchema("no_such_db", false);
        givenBuiltinTarget();
        ProbingService service = service();
        service.warehouseHit = null; // 数仓里也确实没有这张表

        ApiException error = assertThrows(ApiException.class,
                () -> service.bindTarget(1, "no_such_db.orders", null, null));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("no_such_db.orders"), error.getMessage());
        assertTrue(error.getMessage().contains("数据源管理") || error.getMessage().contains("表名"),
                "要告诉用户下一步做什么: " + error.getMessage());
    }

    /** 数仓连不上时不能说「表不存在」，也不能悄悄落 PG——400 并写明是确认失败。 */
    @Test
    void probeFailureIsReportedAsUnconfirmedNotAsMissing() {
        givenProject("demo_retail", null, null, null, null, false);
        givenPgSchema("fm_orders_911144", false);
        givenBuiltinTarget();
        ProbingService service = service();
        service.warehouseFailure = new RuntimeException(
                "Could not open connection: java.sql.SQLException: Connection refused");

        ApiException error = assertThrows(ApiException.class,
                () -> service.bindTarget(1, "fm_orders_911144.orders", null, null));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("连不上"), error.getMessage());
        assertTrue(!error.getMessage().contains("Connection refused"), "不要把驱动原文回显出去");
    }

    // --------------------------------------------------------- 指定错误数据源

    /** 卡面「指定错误数据源 → 400」：把 Doris 表绑到 PostgreSQL 数据源上。 */
    @Test
    void bindingDorisTableToPostgresDatasourceIsRejected() {
        givenDatabaseOwnedByProject("零售项目", 19L, true);
        when(jdbc.queryForList(contains("where d.id = ? and d.project_id = ?"), any(Object[].class)))
                .thenReturn(List.of(datasourceRow(7L, "订单库", "POSTGRESQL")));

        ApiException error = assertThrows(ApiException.class,
                () -> service().bindTarget(1, "demo_retail.ods_order", 7L, null));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("Doris"), error.getMessage());
        assertTrue(error.getMessage().contains("demo_retail.ods_order"), error.getMessage());
    }

    /** 显式写老字段 datasource=PG 也是「指定错误数据源」，同样 400，不能留给执行去炸。 */
    @Test
    void explicitLegacyPgOnDorisTableIsRejected() {
        givenDatabaseOwnedByProject("零售项目", 19L, true);

        ApiException error = assertThrows(ApiException.class,
                () -> service().bindTarget(1, "demo_retail.ods_order", null, "PG"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("Doris"), error.getMessage());
    }

    /** 数据源不属于当前项目 = 不能绑（沿用旧口径，别把规则挂到别人的连接上）。 */
    @Test
    void datasourceOfAnotherProjectIsRejected() {
        when(jdbc.queryForList(contains("where d.id = ? and d.project_id = ?"), any(Object[].class)))
                .thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class,
                () -> service().bindTarget(1, "demo_retail.ods_order", 999L, null));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("999"), error.getMessage());
    }

    /** 绑了 Doris 数据源但库不在本项目名下：只有该连接里真有这张表才放行。 */
    @Test
    void boundDorisDatasourceMustActuallyContainTheTable() {
        givenProject("demo_retail", null, 21L, "DORIS", "外部 Doris", false);
        when(jdbc.queryForList(contains("where d.id = ? and d.project_id = ?"), any(Object[].class)))
                .thenReturn(List.of(datasourceRow(21L, "外部 Doris", "DORIS")));
        ProbingService service = service();
        service.boundHit = null;

        ApiException error = assertThrows(ApiException.class,
                () -> service.bindTarget(1, "other_db.t_x", 21L, null));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("other_db.t_x"), error.getMessage());

        service.boundHit = "t_x";
        assertEquals("DORIS", service.bindTarget(1, "other_db.t_x", 21L, null).engine());
        assertEquals(21L, service.bindTarget(1, "other_db.t_x", 21L, null).datasourceId());
    }

    /** 非法的老字段取值仍然 400（不许悄悄当默认值）。 */
    @Test
    void unknownLegacyEngineValueIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service().bindTarget(1, "t", null, "ORACLE"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("PG 或 DORIS"), error.getMessage());
    }

    // ------------------------------------------------------------- 名字拆解

    @Test
    void splitsDatabaseAndTableWithoutAssumingDepth() {
        assertEquals("fm_orders_911144", QualityService.databaseOf("fm_orders_911144.orders"));
        assertEquals("orders", QualityService.bareTableOf("fm_orders_911144.orders"));
        assertEquals("catalog.internal", QualityService.databaseOf("catalog.internal.t"));
        assertEquals("t", QualityService.bareTableOf("catalog.internal.t"));
        assertEquals(null, QualityService.databaseOf("t_order"));
        assertEquals("t_order", QualityService.bareTableOf("t_order"));
    }

    private static Map<String, Object> datasourceRow(long id, String name, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("ds_type", type);
        row.put("builtin", "false");
        return row;
    }
}
