package com.lab.dataworks.project;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D57：项目 DEV/PROD 环境。
 *
 * <p>立案事实是「dev_database / prod_database 可以是空的」：项目照常用，
 * 但试跑走 dev、发布走 prd、表管理按库判 env 这些约束全部落空。
 * 这里锁住三件事：库名怎么补、开发面能不能碰生产库、空壳库怎么被发现与清理。</p>
 */
class ProjectEnvironmentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DorisAdminService doris = mock(DorisAdminService.class);
    private final WarehouseResolver warehouses = mock(WarehouseResolver.class);

    private ProjectEnvironmentService service;

    @BeforeEach
    void setUp() {
        TenantScope.set(TenantScope.DEFAULT);
        service = new ProjectEnvironmentService(jdbc, doris, warehouses);
    }

    private static Map<String, Object> project(long id, String code, String dev, String prod) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("code", code);
        row.put("name", code + " 项目");
        row.put("dev_database", dev);
        row.put("prod_database", prod);
        return row;
    }

    // ------------------------------------------------------------ 库名推导

    @Test
    void derivesDatabasePairFromProjectCode() {
        String[] pair = ProjectEnvironmentService.deriveDatabases("demo");
        assertNotNull(pair);
        assertEquals("demo_dev", pair[0]);
        assertEquals("demo_prd", pair[1]);

        String[] mixed = ProjectEnvironmentService.deriveDatabases("FM-Orders");
        assertEquals("fm_orders_dev", mixed[0]);
        assertEquals("fm_orders_prd", mixed[1]);
    }

    /** 推不出合法库名就返回 null，让调用方拒绝——不能塞一个 Doris 不认的名字。 */
    @Test
    void refusesToDeriveIllegalDatabaseNames() {
        assertNull(ProjectEnvironmentService.deriveDatabases(null));
        assertNull(ProjectEnvironmentService.deriveDatabases("  "));
        assertNull(ProjectEnvironmentService.deriveDatabases("123"));
        assertNull(ProjectEnvironmentService.deriveDatabases("---"));
    }

    @Test
    void keepsDerivedNamesWithinDorisLength() {
        String[] pair = ProjectEnvironmentService.deriveDatabases("x".repeat(80));
        assertTrue(pair[0].length() <= 64, pair[0]);
        assertTrue(pair[0].endsWith("_dev"), pair[0]);
    }

    // -------------------------------------------------------------- 环境闸门

    @Test
    void devWorkbenchRejectsExplicitProductionDatabase() {
        ProjectEnvironmentService.Env env =
                new ProjectEnvironmentService.Env(1L, "demo", "演示", "demo_dev", "demo_prd");

        ApiException error = assertThrows(ApiException.class,
                () -> service.requireDevWorkbench(env, Set.of("demo_prd", "demo_dev")));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("demo_prd"), error.getMessage());
        assertTrue(error.getMessage().contains("demo_dev"), "要告诉他该查哪个库: " + error.getMessage());
    }

    /** 只挡「明确写本项目生产库」：dev 库、别的库、不带前缀都不能误杀。 */
    @Test
    void devWorkbenchAllowsEverythingElse() {
        ProjectEnvironmentService.Env env =
                new ProjectEnvironmentService.Env(1L, "demo", "演示", "demo_dev", "demo_prd");

        service.requireDevWorkbench(env, Set.of("DEMO_DEV"));           // 大小写不敏感，dev 放行
        service.requireDevWorkbench(env, Set.of("other_project_dev"));  // 别的库归租户/分级闸门管
        service.requireDevWorkbench(env, Set.of());
        service.requireDevWorkbench(env, null);
        // 项目本身没配 prod 库（D57 之前的历史行）：没有可比对的目标，不能凭空拒绝
        service.requireDevWorkbench(
                new ProjectEnvironmentService.Env(2L, "old", "老项目", null, null), Set.of("anything"));
    }

    @Test
    void unqualifiedRefsOnlyPicksBareTableNames() {
        assertEquals(List.of("ods_order"),
                ProjectEnvironmentService.unqualifiedRefs(List.of("demo_retail.ods_order", "ods_order")));
        assertEquals(List.of(), ProjectEnvironmentService.unqualifiedRefs(null));
        assertEquals(List.of(), ProjectEnvironmentService.unqualifiedRefs(List.of("internal.cat.t")));
    }

    // ---------------------------------------------------------------- 回填

    @Test
    void backfillFillsMissingNamesAndReportsConflicts() {
        when(jdbc.queryForList(contains("dev_database is null or dev_database = ''"),
                any(Object[].class)))
                .thenReturn(List.of(project(1L, "demo", null, null), project(2L, "retail", "taken_dev", null)));
        // demo 的两个名字没人用；retail_prd 已被别的项目占用（V49 部分唯一索引）
        when(jdbc.queryForObject(contains("count(*) from platform.project"), eq(Long.class),
                any(Object[].class))).thenAnswer(inv -> {
            // 参数顺序：sql, Long.class, exceptProjectId, database, database（varargs 展开）
            String database = String.valueOf(inv.getArguments()[3]);
            return "taken_dev".equals(database) || "retail_prd".equals(database) ? 1L : 0L;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> out = service.backfill(false);

        assertEquals(2, out.get("scanned"));
        assertEquals(1, out.get("filled"));
        assertEquals(false, out.get("dorisCreated"), "默认不动数仓，只补元数据");
        assertEquals(1, ((List<?>) out.get("conflicts")).size(), "库名被占用要单独报出来");
        verify(doris, never()).ensureDatabase(any(), anyString());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("set dev_database = ?"), args.capture());
        assertEquals("demo_dev", args.getValue()[0]);
        assertEquals("demo_prd", args.getValue()[1]);
    }

    /** create=true 才真的在 Doris 建库，而且用的是这个项目的连接（外部 Doris 不能建到内置实例上）。 */
    @Test
    void backfillCanCreateDatabasesOnTheProjectWarehouse() {
        when(jdbc.queryForList(contains("dev_database is null or dev_database = ''"), any(Object[].class)))
                .thenReturn(List.of(project(7L, "demo", null, null)));
        when(jdbc.queryForObject(contains("count(*) from platform.project"), eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(warehouses.connectionIdForProject(7L)).thenReturn(55L);

        Map<String, Object> out = service.backfill(true);

        assertEquals(true, out.get("dorisCreated"));
        verify(doris).ensureDatabase(55L, "demo_dev");
        verify(doris).ensureDatabase(55L, "demo_prd");
    }

    // -------------------------------------------------------------- 空壳库巡检

    @Test
    void inventoryMarksOnlyUnreferencedEmptyDatabasesAsShells() {
        when(doris.queryAdmin(eq(null), contains("show databases"))).thenReturn(List.of(
                Map.of("Database", "information_schema"),
                Map.of("Database", "demo_dev"),
                Map.of("Database", "old_run_20261020")));
        when(doris.queryAdmin(eq(null), contains("group by table_schema"))).thenReturn(List.of(
                Map.of("table_schema", "demo_dev", "table_count", 3L),
                Map.of("table_schema", "information_schema", "table_count", 12L)));
        when(jdbc.queryForList(contains("select code, dev_database, prod_database")))
                .thenReturn(List.of(project(1L, "demo", "demo_dev", "demo_prd")));

        Map<String, Object> out = service.inventory(null);

        assertEquals("内置 Doris", out.get("target"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("databases");
        Map<String, Object> shell = byName(items, "old_run_20261020");
        Map<String, Object> referenced = byName(items, "demo_dev");
        Map<String, Object> system = byName(items, "information_schema");
        assertEquals(true, shell.get("emptyShell"), "0 表且无项目引用才是空壳库");
        assertEquals(false, referenced.get("emptyShell"), "有项目引用的库不能算空壳");
        assertEquals(List.of("demo"), referenced.get("referencedBy"));
        assertEquals(false, system.get("emptyShell"), "系统库不是待清理的空壳库");
        assertEquals(1, out.get("emptyShells"));
    }

    // ---------------------------------------------------------- 删项目时的清理

    /** 库里还有表就不能删；只有「0 张表且没人引用」的库才 DROP（D57 第 4 条的保守口径）。 */
    @Test
    void dropsOnlyEmptyUnreferencedDatabases() {
        givenNoProjectReferences();
        givenTableCounts();

        List<Map<String, Object>> actions = service.dropEmptyDatabases(null,
                List.of("demo_dev", "demo_prd"));

        assertEquals("KEPT", action(actions, "demo_dev").get("action"));
        assertTrue(String.valueOf(action(actions, "demo_dev").get("reason")).contains("还有 2 张表"),
                String.valueOf(action(actions, "demo_dev")));
        assertEquals("DROPPED", action(actions, "demo_prd").get("action"));
        verify(doris).execAdmin(null, "drop database if exists `demo_prd`");
        verify(doris, times(1)).execAdmin(any(), anyString());
    }

    @Test
    void keepsDatabaseStillReferencedByAnotherProject() {
        when(jdbc.queryForList(contains("select code, dev_database, prod_database")))
                .thenReturn(List.of(project(9L, "other", "shared_dev", "shared_prd")));
        givenTableCounts();

        List<Map<String, Object>> actions = service.dropEmptyDatabases(null, List.of("shared_dev"));

        assertEquals("KEPT", action(actions, "shared_dev").get("action"));
        assertTrue(String.valueOf(action(actions, "shared_dev").get("reason")).contains("other"),
                String.valueOf(actions));
        verify(doris, never()).execAdmin(any(), anyString());
    }

    /** 表数读不到（账号看不到 / 库不在）时按「不删」处理，不猜。 */
    @Test
    void keepsDatabaseWhenTableCountCannotBeRead() {
        givenNoProjectReferences();
        when(doris.queryAdmin(eq(null), contains("where lower(table_schema)")))
                .thenReturn(List.of());

        List<Map<String, Object>> actions = service.dropEmptyDatabases(null, List.of("ghost_db"));

        assertEquals("KEPT", action(actions, "ghost_db").get("action"));
        verify(doris, never()).execAdmin(any(), anyString());
    }

    /** 库名是用户可写字段：进任何语句之前先过白名单，连查表数都不给拼进去。 */
    @Test
    void illegalDatabaseNameIsRefusedBeforeAnySql() {
        givenNoProjectReferences();

        assertThrows(ApiException.class, () -> service.dropEmptyDatabases(null, List.of("a`b")));
        ApiException backtick = assertThrows(ApiException.class,
                () -> service.dropEmptyDatabases(null, List.of("a`b")));
        assertEquals(400, backtick.getStatus());
        assertThrows(ApiException.class, () -> service.dropEmptyDatabases(null, List.of("bad-name")));
        verify(doris, never()).queryAdmin(any(), anyString());
    }

    private void givenNoProjectReferences() {
        when(jdbc.queryForList(contains("select code, dev_database, prod_database")))
                .thenReturn(List.of());
    }

    /** 逐库单查的桩：demo_dev 有 2 张表，demo_prd 是真空库，其它库读不到。 */
    private void givenTableCounts() {
        when(doris.queryAdmin(eq(null), contains("where lower(table_schema)"))).thenAnswer(inv -> {
            String sql = inv.getArgument(1);
            if (sql.contains("'demo_dev'")) {
                return List.of(Map.of("table_count", 2L));
            }
            if (sql.contains("'demo_prd'")) {
                return List.of(Map.of("table_count", 0L));
            }
            return List.of();
        });
    }

    // ---------------------------------------------------------------- 环境读取

    @Test
    void requireReturnsEnvOrNotFoundForOtherTenant() {
        when(jdbc.queryForList(contains("where id = ? and tenant_id = ?"), any(Object[].class)))
                .thenReturn(List.of(project(1L, "demo", "demo_dev", "demo_prd")));

        ProjectEnvironmentService.Env env = service.require(1L);

        assertEquals("demo_dev", env.workbenchDatabase());
        assertFalse(env.missing());

        when(jdbc.queryForList(contains("where id = ? and tenant_id = ?"), any(Object[].class)))
                .thenReturn(List.of());
        ApiException error = assertThrows(ApiException.class, () -> service.require(999L));
        assertEquals(404, error.getStatus());
    }

    @Test
    void missingFlagTellsProjectsThatStillNeedBackfill() {
        assertTrue(new ProjectEnvironmentService.Env(1L, "demo", "n", null, "demo_prd").missing());
        assertTrue(new ProjectEnvironmentService.Env(1L, "demo", "n", "demo_dev", "").missing());
        assertFalse(new ProjectEnvironmentService.Env(1L, "demo", "n", "demo_dev", "demo_prd").missing());
    }

    private static Map<String, Object> byName(List<Map<String, Object>> items, String database) {
        return items.stream().filter(row -> database.equals(row.get("database"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> action(List<Map<String, Object>> actions, String database) {
        return actions.stream().filter(row -> database.equals(row.get("database")))
                .findFirst().orElse(Map.of("action", "MISSING"));
    }

    // ------------------------------ BUG-TC4-2 / TC4-3：开发态落点只能落开发库

    /** TC-4 判据用的库名抽取：只看显式带库前缀的引用，湖 Catalog 取中间那段。 */
    @Test
    void extractsQualifiedDatabasesOnly() {
        assertEquals(java.util.Set.of("bug_a_t4_env_prd"),
                ProjectEnvironmentService.referencedDatabases(
                        "select count(*) as c from bug_a_t4_env_prd.bug_a_t4_t1"));
        assertEquals(java.util.Set.of("demo_dev", "other_db"),
                ProjectEnvironmentService.referencedDatabases(
                        "select 1 from demo_dev.t a join other_db.u b on a.id = b.id"));
        assertEquals(java.util.Set.of(), ProjectEnvironmentService.referencedDatabases("select 1"));
        assertEquals(java.util.Set.of(), ProjectEnvironmentService.referencedDatabases(
                "select * from (select id from x) y"), "子查询别名不该被当成库名");
        assertEquals(java.util.Set.of("lake"),
                ProjectEnvironmentService.referencedDatabases("select * from iceberg.lake.t"));
    }

    @Test
    void devLandingBlocksOwnProductionDatabase() {
        when(jdbc.queryForList(contains("select id, code, name, dev_database, prod_database"),
                any(Object[].class)))
                .thenReturn(List.of(project(1L, "demo", "demo_dev", "demo_prd")));

        ApiException error = assertThrows(ApiException.class, () -> service.requireDevLanding(
                1L, java.util.Set.of("demo_prd"), "试跑"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("demo_prd"), error.getMessage());
        assertTrue(error.getMessage().contains("demo_dev"), "要告诉他该落哪个库: " + error.getMessage());
        // 自己的开发库、别人的开发库、不带前缀都不挡
        service.requireDevLanding(1L, java.util.Set.of("demo_dev"), "试跑");
        service.requireDevLanding(1L, java.util.Set.of(), "试跑");
    }

    /** 跨项目读开发库是允许的（由租户与分级网关判），但别的项目的生产库必须挡。 */
    @Test
    void devLandingBlocksOtherProjectsProductionDatabase() {
        when(jdbc.queryForList(contains("select id, code, name, dev_database, prod_database"),
                any(Object[].class)))
                .thenReturn(List.of(project(1L, "demo", "demo_dev", "demo_prd")));
        givenProductionOwner("retail_prd", "零售项目");

        service.requireDevLanding(1L, java.util.Set.of("retail_dev"), "采集任务 sink");   // 别人开发库：放行
        ApiException error = assertThrows(ApiException.class, () -> service.requireDevLanding(
                1L, java.util.Set.of("retail_prd"), "采集任务 sink"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("零售项目"), error.getMessage());
        assertTrue(error.getMessage().contains("发布"), error.getMessage());
    }

    /** 谁都不登记的 *_prd（历次测试留下的空壳生产库）也要挡——否则闸门留空档。 */
    @Test
    void devLandingBlocksUnregisteredProductionNamedDatabase() {
        when(jdbc.queryForList(contains("select id, code, name, dev_database, prod_database"),
                any(Object[].class)))
                .thenReturn(List.of(project(1L, "demo", "demo_dev", "demo_prd")));
        givenProductionOwner(null, null);
        givenUnownedProductionSuffix(true);

        ApiException error = assertThrows(ApiException.class, () -> service.requireDevLanding(
                1L, java.util.Set.of("bug_a_t4_env_prd"), "试跑"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("命名约定"), error.getMessage());
    }

    @Test
    void landingEnvOnlyReportsAndNeverBlocks() {
        when(jdbc.queryForList(contains("select id, code, name, dev_database, prod_database"),
                any(Object[].class)))
                .thenReturn(List.of(project(1L, "demo", "demo_dev", "demo_prd")));
        givenProductionOwner(null, null);
        givenUnownedProductionSuffix(false);

        assertEquals("DEV", service.landingEnv(1L, "demo_dev"));
        assertEquals("PROD", service.landingEnv(1L, "demo_prd"));
        assertEquals("PROD", service.landingEnv(1L, "ghost_prd"));
        assertEquals("OTHER", service.landingEnv(1L, "some_other_dev"));
        assertEquals(null, service.landingEnv(1L, "  "));
    }

    /**
     * 「这个库是谁的生产库」的桩：查询是 queryForList(sql, tenant, database)，
     * 所以按 arguments[2] 的库名决定命中与否。
     */
    private void givenProductionOwner(String hitDatabase, String projectName) {
        when(jdbc.queryForList(contains("lower(coalesce(prod_database, '')) = lower(?)"),
                any(Object[].class))).thenAnswer(inv ->
                hitDatabase != null && hitDatabase.equals(inv.getArguments()[2])
                        ? List.of(Map.of("name", projectName)) : List.of());
    }

    /** 没有任何项目登记这个库（*_prd 命名兜底那条分支才会走到）。 */
    private void givenUnownedProductionSuffix(boolean owned) {
        if (!owned) {
            givenProductionOwner(null, null);
        }
    }
}
