package com.lab.dataworks.query;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.common.TenantGuard;
import com.lab.dataworks.lake.LakeCatalogService;
import com.lab.dataworks.security.DataGuardService;
import com.lab.dataworks.udf.UdfService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D09 / D10：元数据语句的行收口与计数重算。
 *
 * <p>D09 原缺陷：MCP 侧 {@code SHOW / DESC / EXPLAIN} 不能包成子查询（包了必然语法错），
 * 于是直接原样执行；JDBC 侧 {@code setMaxRows} 对 {@code SHOW} 也不保证生效。结果
 * {@code show databases} + {@code maxRows=5} 返回 34 行、{@code show tables from demo_retail}
 * + {@code maxRows=1} 返回 7 行——{@code maxRows} 契约对元数据语句完全失效。</p>
 *
 * <p>D10 原缺陷：非默认租户的 {@code SHOW DATABASES} 过滤了行却沿用执行结果里的过滤前计数，
 * 新建的空租户拿到 {@code {"rows":[],"rowCount":34}}——响应自相矛盾，并把全平台库总数
 * 泄露给没有数据面的租户。</p>
 *
 * <p>前半段直接测纯函数 {@link QueryController#limitMetaRows}，后半段走
 * {@code QueryController.doris()} 锁住响应体里的 {@code rows} / {@code rowCount} 口径。
 * 全部用 Mockito 构造，不起 Spring、不连 Doris。</p>
 */
class QueryControllerMetaRowsTest {

    private static final String OWN_DB = "tenant_b_dev";

    /** 复现证据里的规模：共享 Doris 上 34 个库。 */
    private static final int PLATFORM_DATABASES = 34;

    // ---------- limitMetaRows 纯函数 ----------

    /** D09：{@code show databases} + maxRows=5 → 只返回 5 行，rowCount=5。 */
    @Test
    void showDatabasesIsTruncatedToMaxRows() {
        QueryController.MetaRows out = QueryController.limitMetaRows(
                databaseRows(PLATFORM_DATABASES), 5, null);

        assertEquals(5, out.rows().size(), "maxRows 是硬上限，元数据语句也一样");
        assertEquals(5, out.rowCount(), "rowCount 必须等于真正返回的行数");
        assertEquals(List.of("db_0"), out.rows().get(0));
    }

    /** D09：{@code show tables from demo_retail} + maxRows=1 → 1 行。 */
    @Test
    void showTablesIsTruncatedToOneRow() {
        QueryController.MetaRows out = QueryController.limitMetaRows(
                databaseRows(7), 1, null);

        assertEquals(1, out.rows().size());
        assertEquals(1, out.rowCount());
    }

    /** 行数本来就没超上限：原样返回，rowCount 等于行数（不能因为加了裁剪就少报）。 */
    @Test
    void rowsWithinLimitAreUntouched() {
        List<List<String>> rows = databaseRows(3);

        QueryController.MetaRows out = QueryController.limitMetaRows(rows, 5, null);

        assertEquals(rows, out.rows());
        assertEquals(3, out.rowCount());
    }

    /** D10 复现原文：新建空租户 {@code show databases} + maxRows=5 → rows=[] / rowCount=0。 */
    @Test
    void emptyTenantShowDatabasesRecomputesRowCountToZero() {
        QueryController.MetaRows out = QueryController.limitMetaRows(
                databaseRows(PLATFORM_DATABASES), 5, Set.of());

        assertEquals(List.of(), out.rows());
        assertEquals(0, out.rowCount(),
                "过滤后计数必须重算，不能沿用过滤前的 34（会泄露平台库总数）");
    }

    /** D10：租户有库时，过滤与裁剪同时生效，rowCount 跟过滤+裁剪后的行数一致。 */
    @Test
    void filterAndTruncateCompose() {
        List<List<String>> rows = databaseRows(10);
        rows.set(1, List.of(OWN_DB));
        // 库名大小写在 Doris 侧不敏感：过滤集合是小写，这里的大写行也要能匹配上
        rows.set(2, List.of(OWN_DB.toUpperCase(java.util.Locale.ROOT)));
        rows.set(3, List.of("tenant_b_prd"));

        QueryController.MetaRows out = QueryController.limitMetaRows(
                rows, 2, Set.of(OWN_DB, "tenant_b_prd"));

        assertEquals(2, out.rows().size(), "先过滤再裁剪");
        assertEquals(2, out.rowCount());
        assertEquals(List.of(OWN_DB), out.rows().get(0));
        assertEquals(List.of("TENANT_B_DEV"), out.rows().get(1));
    }

    /** 默认租户 / 非 SHOW DATABASES：owned 传 null 表示不做库级过滤，只裁剪。 */
    @Test
    void nullOwnedDatabasesMeansNoFiltering() {
        QueryController.MetaRows out = QueryController.limitMetaRows(databaseRows(4), 100, null);

        assertEquals(4, out.rows().size());
        assertEquals(4, out.rowCount());
    }

    /** 防御：空行与 null 库名在过滤时丢弃，null 结果集当成 0 行。 */
    @Test
    void malformedRowsAreDroppedWhenFiltering() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of());
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        rows.add(withNull);
        rows.add(List.of(OWN_DB));

        assertEquals(1, QueryController.limitMetaRows(rows, 5, Set.of(OWN_DB)).rowCount());
        QueryController.MetaRows empty = QueryController.limitMetaRows(null, 5, null);
        assertEquals(List.of(), empty.rows());
        assertEquals(0, empty.rowCount());
    }

    // ---------- doris() 响应体口径 ----------

    private DorisQueryService doris;
    private DataGuardService dataGuard;
    private TenantDataScope scope;
    private HttpServletRequest request;

    /**
     * 构造一个最小控制器：分级网关 OFF、脱敏不启用，Doris 侧原样返回 {@code serviceRows}
     * （等价于 MCP 对元数据语句不加 LIMIT 的真实形态）。
     *
     * @param requestedMaxRows 请求里的 maxRows；生产上 {@code DorisQueryService} 会把它
     *                         原样回填到 {@code Result.maxRows}，这里保持同一个形态
     */
    private QueryController controller(List<List<String>> serviceRows, int serviceRowCount,
                                       int requestedMaxRows) {
        doris = mock(DorisQueryService.class);
        dataGuard = mock(DataGuardService.class);
        scope = mock(TenantDataScope.class);
        UdfService udf = mock(UdfService.class);
        TenantGuard guard = mock(TenantGuard.class);
        QueryCapabilities capabilities = mock(QueryCapabilities.class);
        LakeCatalogService lakeCatalogs = mock(LakeCatalogService.class);
        SessionService sessions = mock(SessionService.class);

        when(doris.query(nullable(Long.class), anyString(), anyInt())).thenReturn(
                new DorisQueryService.Result(List.of("Database"), serviceRows, serviceRowCount,
                        requestedMaxRows, 1L, "内置 Doris（10.99.99.11:9030）"));
        when(dataGuard.guardMode(nullable(Long.class))).thenReturn("OFF");
        // TC7-G6：查询网关现在带原始 SQL（回溯结果列来源），stub 跟到 5/6 参重载
        when(dataGuard.maskedColumnNames(anyString(), any(), any(), nullable(Long.class),
                nullable(String.class))).thenReturn(List.of());
        when(dataGuard.tokenizedColumnNames(anyString(), any(), any(), nullable(Long.class),
                nullable(String.class))).thenReturn(List.of());
        // 脱敏不改行数：直接回传第 4 个参数（rows）
        when(dataGuard.mask(anyString(), any(), any(), any(), nullable(Long.class),
                nullable(String.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));

        request = mock(HttpServletRequest.class);
        when(request.getAttribute("username")).thenReturn("bugmt_viewer");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return new QueryController(doris, dataGuard, scope, udf, guard,
                capabilities, lakeCatalogs, sessions);
    }

    private static List<List<String>> databaseRows(int count) {
        List<List<String>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(List.of("db_" + i));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> rowsOf(Map<String, Object> response) {
        return (List<List<String>>) response.get("rows");
    }

    private static int rowCountOf(Map<String, Object> response) {
        return (int) response.get("rowCount");
    }

    /** D09 端到端：默认租户 {@code show databases} + maxRows=5 → 5 行 / rowCount=5。 */
    @Test
    void dorisResponseTruncatesShowDatabases() {
        QueryController controller = controller(
                databaseRows(PLATFORM_DATABASES), PLATFORM_DATABASES, 5);
        when(scope.defaultTenant()).thenReturn(true);

        Map<String, Object> response = controller.doris(
                new QueryController.QueryRequest("show databases", 5, null), request);

        assertEquals(5, rowsOf(response).size(), "maxRows=5 必须只返回 5 行");
        assertEquals(5, rowCountOf(response), "rowCount 与 rows 一致");
        assertEquals(5, response.get("maxRows"));
    }

    /**
     * D10 端到端：新建空租户 {@code show databases} + maxRows=5 →
     * 200 + rows=[] + rowCount=0（原缺陷是 rows=[] / rowCount=34）。
     */
    @Test
    void dorisResponseRecomputesRowCountForFilteredTenant() {
        QueryController controller = controller(
                databaseRows(PLATFORM_DATABASES), PLATFORM_DATABASES, 5);
        when(scope.defaultTenant()).thenReturn(false);
        when(scope.ownedDatabases()).thenReturn(Set.of());

        Map<String, Object> response = controller.doris(
                new QueryController.QueryRequest("show databases", 5, null), request);

        assertTrue(rowsOf(response).isEmpty(), "没有数据面的租户看不到任何库");
        assertEquals(0, rowCountOf(response), "rowCount 必须重算成 0，不能泄露平台库总数");
    }

    /** D09 + D10 组合：租户有库时，先按已授权库过滤，再按 maxRows 裁剪。 */
    @Test
    void dorisResponseFiltersThenTruncates() {
        List<List<String>> rows = databaseRows(8);
        rows.set(0, List.of(OWN_DB));
        rows.set(1, List.of("tenant_b_prd"));
        rows.set(2, List.of("demo_retail"));
        QueryController controller = controller(rows, rows.size(), 1);
        when(scope.defaultTenant()).thenReturn(false);
        when(scope.ownedDatabases()).thenReturn(Set.of(OWN_DB, "tenant_b_prd"));

        Map<String, Object> response = controller.doris(
                new QueryController.QueryRequest("show databases", 1, null), request);

        assertEquals(List.of(List.of(OWN_DB)), rowsOf(response));
        assertEquals(1, rowCountOf(response));
    }

    /** D09：{@code show tables from <库>} + maxRows=1 → 1 行 / rowCount=1。 */
    @Test
    void dorisResponseTruncatesShowTables() {
        QueryController controller = controller(databaseRows(7), 7, 1);
        when(scope.defaultTenant()).thenReturn(true);

        Map<String, Object> response = controller.doris(
                new QueryController.QueryRequest("show tables from demo_retail", 1, null),
                request);

        assertEquals(1, rowsOf(response).size());
        assertEquals(1, rowCountOf(response));
    }

    /**
     * 普通 SELECT 不在元数据收口里：rowCount 仍取执行结果的计数（MCP 已经用 LIMIT 包过），
     * 这条锁住「D09 的裁剪没有改到数据查询的语义」。
     */
    @Test
    void selectPathKeepsServiceRowCount() {
        List<List<String>> rows = List.of(List.of("1"), List.of("2"), List.of("3"));
        QueryController controller = controller(rows, rows.size(), 5);
        // 默认租户：不走「非默认租户按库收口」那条分支，专注验证 rowCount 口径
        when(scope.defaultTenant()).thenReturn(true);
        when(dataGuard.inspect(anyString(), anyString(), nullable(Long.class)))
                .thenReturn(new DataGuardService.Guard("OFF", false, false, List.of(),
                        List.of(), List.of(), false, List.of()));
        when(dataGuard.parsedTables(anyString())).thenReturn(List.of("demo_retail.orders"));

        Map<String, Object> response = controller.doris(
                new QueryController.QueryRequest("select id from demo_retail.orders", 5, null),
                request);

        assertEquals(3, rowsOf(response).size());
        assertEquals(3, rowCountOf(response));
    }
}
