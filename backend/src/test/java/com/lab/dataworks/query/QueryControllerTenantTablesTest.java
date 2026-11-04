package com.lab.dataworks.query;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.common.TenantGuard;
import com.lab.dataworks.lake.LakeCatalogService;
import com.lab.dataworks.security.DataGuardService;
import com.lab.dataworks.udf.UdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D05：{@code /api/query/tables} 的租户收口。
 *
 * <p>原缺陷：租户库归属过滤只写在 {@code projectId == null} 分支，带显式 projectId 时
 * 只校验「项目属于当前租户」，于是补全清单把共享 Doris 上<b>所有租户</b>的库、表、
 * 字段名与类型都列了出来（实测 11 张表 / 4 个库，含另一个租户的 {@code bug_b_prd}）。</p>
 *
 * <p>口径与 {@code DataGuardService.usesBuiltinDoris} 一致：只有内置共享 Doris 的库名由
 * 本平台项目声明归属；项目引用外部 Doris 时整个实例都是这个项目的，不能再按库名收口，
 * 否则项目自己的表会被全部藏掉（过度过滤同样是缺陷）。三种组合都要锁住。</p>
 *
 * <p>全部用 Mockito 直接构造，不起 Spring、不连 Doris。</p>
 */
class QueryControllerTenantTablesTest {

    /** 共享 Doris 上的库分布：本租户项目库 / 另一个租户的项目库 / 系统库。 */
    private static final String OWN_DB = "tenant_b_dev";
    private static final String OTHER_DB = "other_tenant_prd";
    private static final String SYSTEM_DB = "information_schema";

    private static final long PROJECT_ID = 42L;

    private DorisQueryService doris;
    private DataGuardService dataGuard;
    private TenantDataScope scope;
    private UdfService udf;
    private TenantGuard guard;
    private QueryCapabilities capabilities;
    private LakeCatalogService lakeCatalogs;
    private SessionService sessions;
    private QueryController controller;

    @BeforeEach
    void setUp() {
        doris = mock(DorisQueryService.class);
        dataGuard = mock(DataGuardService.class);
        scope = mock(TenantDataScope.class);
        udf = mock(UdfService.class);
        guard = mock(TenantGuard.class);
        capabilities = mock(QueryCapabilities.class);
        lakeCatalogs = mock(LakeCatalogService.class);
        sessions = mock(SessionService.class);
        controller = new QueryController(doris, dataGuard, scope, udf, guard,
                capabilities, lakeCatalogs, sessions);
        // 元数据语句本身不返回业务行，这里造 information_schema.columns 的四列结果
        when(doris.rawQuery(nullable(Long.class), anyString(), anyInt()))
                .thenReturn(new DorisQueryService.Result(
                        List.of("table_schema", "table_name", "column_name", "data_type"),
                        columnRows(), columnRows().size(), 20000, 1L, "内置 Doris"));
        when(capabilities.notQueryableAliases(nullable(Long.class))).thenReturn(Set.of());
        when(udf.usable(anyLong())).thenReturn(List.of());
    }

    private static List<List<String>> columnRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(OWN_DB, "t1", "id", "int"));
        rows.add(List.of(OWN_DB, "t1", "name", "varchar"));
        rows.add(List.of(OTHER_DB, "secret_table", "id", "int"));
        rows.add(List.of(SYSTEM_DB, "tables", "table_name", "varchar"));
        return rows;
    }

    /** 非默认租户：只有本租户项目名下的库属于当前租户。 */
    private void stubNonDefaultTenant() {
        when(scope.defaultTenant()).thenReturn(false);
        when(scope.owns(OWN_DB)).thenReturn(true);
        when(scope.owns(OTHER_DB)).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("tables");
    }

    private static Set<String> databases(List<Map<String, Object>> tables) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Map<String, Object> table : tables) {
            out.add(String.valueOf(table.get("database")));
        }
        return out;
    }

    /**
     * D05 主体：带显式 projectId（内置共享 Doris）时，别租户的库不进补全清单。
     * 修复前这里返回 {@code tenant_b_dev + other_tenant_prd} 两个库。
     */
    @Test
    void explicitProjectOnBuiltinDorisIsScopedToTenantDatabases() {
        stubNonDefaultTenant();
        when(doris.usesBuiltinDoris(PROJECT_ID)).thenReturn(true);

        Map<String, Object> response = controller.tables(PROJECT_ID);

        List<Map<String, Object>> tables = tables(response);
        assertEquals(Set.of(OWN_DB), databases(tables), "带 projectId 也不能列出其它租户的库");
        assertFalse(databases(tables).contains(OTHER_DB));
        assertFalse(databases(tables).contains(SYSTEM_DB), "系统库始终不进补全");
        // 库归属过滤确实在「显式 projectId」分支上被调用过（不是只有 projectId==null 才过滤）
        verify(scope, atLeastOnce()).owns(OWN_DB);
        verify(scope, atLeastOnce()).owns(OTHER_DB);
        verify(guard).requireProject(PROJECT_ID);
    }

    /**
     * 字段级证据：只留下本租户表自己的列，别租户表的列一个都不出现。
     */
    @Test
    void explicitProjectDoesNotLeakOtherTenantColumnNames() {
        stubNonDefaultTenant();
        when(doris.usesBuiltinDoris(PROJECT_ID)).thenReturn(true);

        List<Map<String, Object>> tables = tables(controller.tables(PROJECT_ID));

        assertEquals(1, tables.size());
        Map<String, Object> own = tables.get(0);
        assertEquals(OWN_DB, own.get("database"));
        assertEquals("t1", own.get("table"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> columns = (List<Map<String, String>>) own.get("columns");
        assertEquals(List.of(Map.of("name", "id", "type", "int"),
                Map.of("name", "name", "type", "varchar")), columns);
    }

    /**
     * 项目引用外部 Doris 时不按本平台库名收口：外部实例整库都是这个项目的，
     * 过度过滤会让项目的补全清单直接变空（V98 项目级数仓路由的正常用法）。
     */
    @Test
    void externalWarehouseProjectKeepsItsOwnDatabases() {
        stubNonDefaultTenant();
        when(doris.usesBuiltinDoris(PROJECT_ID)).thenReturn(false);
        when(doris.rawQuery(nullable(Long.class), anyString(), anyInt()))
                .thenReturn(new DorisQueryService.Result(
                        List.of("table_schema", "table_name", "column_name", "data_type"),
                        List.of(List.of("ext_warehouse", "orders", "id", "bigint")),
                        1, 20000, 1L, "外部 Doris"));

        List<Map<String, Object>> tables = tables(controller.tables(PROJECT_ID));

        assertEquals(Set.of("ext_warehouse"), databases(tables),
                "外部数仓的库不应被本平台库名规则藏掉");
    }

    /**
     * 不带 projectId（内置 Doris 直查）的原行为保持不变：非默认租户仍只看自己的库。
     * 这条是「D05 修复没有把旧分支改坏」的回归闸。
     */
    @Test
    void noProjectStillScopedToTenantDatabases() {
        stubNonDefaultTenant();
        when(doris.usesBuiltinDoris(null)).thenReturn(true);

        List<Map<String, Object>> tables = tables(controller.tables(null));

        assertEquals(Set.of(OWN_DB), databases(tables));
        // 不带 projectId 时不查 UDF（没有项目上下文）
        verify(udf, org.mockito.Mockito.never()).usable(anyLong());
    }

    /**
     * 默认租户（平台自身）保持全局视图：补全清单不被租户库规则削掉。
     */
    @Test
    void defaultTenantSeesWholeBuiltinWarehouse() {
        when(scope.defaultTenant()).thenReturn(true);
        when(scope.owns(anyString())).thenReturn(true);
        when(doris.usesBuiltinDoris(PROJECT_ID)).thenReturn(true);

        Map<String, Object> response = controller.tables(PROJECT_ID);
        List<Map<String, Object>> tables = tables(response);

        assertTrue(databases(tables).contains(OTHER_DB));
        assertTrue(databases(tables).contains(OWN_DB));
        assertFalse(databases(tables).contains(SYSTEM_DB), "系统库对谁都不进补全");
        assertTrue(response.get("cachedAt") instanceof Long, "cachedAt 仍按墙上时钟返回");
    }
}
