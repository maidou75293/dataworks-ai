package com.lab.dataworks.tablemanage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D19（表管理落点）：{@code GET /api/tables/ddl-jobs} 按平台分页契约执行——
 * page 从 1、size 1–500、非法值 400（不静默夹紧）、分页响应带 X-Total-Count、
 * body 用 {@code {items,total,page,size}}；不带 page/size 时维持原数组（前端兼容）。
 *
 * <p>缺陷现场：列表只认 {@code limit}，{@code ?page=1&size=1} 被整个忽略、照旧回 20 条，
 * 也没有 X-Total-Count，与项目/工作流/UDF/告警列表口径不一致。</p>
 *
 * <p>同时钉住 D29 不许回归：非法 {@code status} 仍然 400 + 合法值清单
 * （分页改造不能把「静默 200 空数组」改回来）。</p>
 */
class TableDdlJobsPagingTest {

    private JdbcTemplate jdbc;
    private TableDdlController controller;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        TableDdlService service = new TableDdlService(jdbc, mock(DorisAdminService.class),
                mock(SessionService.class), mock(TenantDataScope.class), new ObjectMapper(),
                mock(WarehouseResolver.class), mock(com.lab.dataworks.metadata.MetadataReader.class));
        controller = new TableDdlController(service);
        response = mock(HttpServletResponse.class);
    }

    private Object call(String database, String table, String status, int limit,
                        Integer page, Integer size) {
        return controller.jobs(database, table, status, limit, page, size, response);
    }

    private static Map<String, Object> jobRow(long id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("tenant_id", "default");
        row.put("database_name", "demo");
        row.put("table_name", "t" + id);
        row.put("status", "FINISHED");
        return row;
    }

    // ---------------------------------------------------------------- 非法参数 400

    @Test
    void illegalPagingParamsAreBadRequestBeforeAnySql() {
        // 缺陷复现值：?page=0 / ?size=501 / ?size=0 以前被静默忽略，现在 400（与告警/UDF 列表同文案）
        ApiException page = assertThrows(ApiException.class, () -> call(null, null, null, 100, 0, 10));
        assertEquals(400, page.getStatus());
        assertTrue(page.getMessage().contains("page 从 1 开始"), page.getMessage());

        ApiException size = assertThrows(ApiException.class, () -> call(null, null, null, 100, 1, 501));
        assertEquals(400, size.getStatus());
        assertTrue(size.getMessage().contains("size 取值 1–500"), size.getMessage());

        assertThrows(ApiException.class, () -> call(null, null, null, 100, 1, 0));
        // 校验在任何 SQL 发出之前——非法参数不能先打库再报错
        verifyNoInteractions(jdbc);
    }

    @Test
    void illegalStatusStillBadRequestWithPagingToo() {
        // D29 回归防线：分页参数合法但 status 拼错 → 依旧 400 + 合法值清单，不许回 200 空集
        ApiException e = assertThrows(ApiException.class,
                () -> call(null, null, "NOPE", 100, 1, 10));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().startsWith("status 只能是"), e.getMessage());
        verifyNoInteractions(jdbc);
    }

    @Test
    void lowercaseStatusStillAccepted() {
        // D29 的另一半：大小写不敏感不能被分页改造打死
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        assertInstanceOf(Map.class, call(null, null, "finished", 100, 1, 10));
    }

    // ---------------------------------------------------------------- 分页模式

    @Test
    void pagedModeHonorsSizeAndEmitsTotalCountHeader() {
        List<Map<String, Object>> page1 = List.of(jobRow(9));
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(9L));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(7);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(page1);

        Object out = call(null, null, null, 100, 1, 1);

        Map<?, ?> body = assertInstanceOf(Map.class, out);
        assertEquals(7, body.get("total"));
        assertEquals(1, body.get("page"));
        assertEquals(1, body.get("size"), "缺陷现场 ?page=1&size=1 回 20 条；必须按 size 切");
        assertEquals(1, ((List<?>) body.get("items")).size());
        verify(response).setHeader("X-Total-Count", "7");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), eq(Long.class), args.capture());
        assertTrue(sql.getValue().contains("limit ? offset ?"), sql.getValue());
        Object[] a = args.getValue();
        assertEquals(1, a[a.length - 2], "size 参数");
        assertEquals(0L, a[a.length - 1], "offset 必须是 long");
    }

    @Test
    void pageTwoOffsetUsesLongMath() {
        // D12 同源边界：(page-1)*size 溢出必须按 long 算，越界页 = 200 空列表而不是 409/500
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        Object out = call(null, null, null, 100, 2147483647, 10);

        assertInstanceOf(Map.class, out);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), eq(Long.class), args.capture());
        Object[] a = args.getValue();
        assertEquals((long) (2147483647 - 1) * 10, a[a.length - 1]);
    }

    @Test
    void sizeDefaultsToTwentyWhenOnlyPageGiven() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(3);

        Map<?, ?> body = assertInstanceOf(Map.class, call(null, null, null, 100, 2, null));
        assertEquals(20, body.get("size"), "只传 page 时每页 20（与 UDF/告警列表同口径）");
    }

    @Test
    void filtersApplyToCountAndSliceIdentically() {
        // total 与 items 必须共用同一份 where/args，否则分页总数与列表口径漂移
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(7L));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(jobRow(7)));

        controller.jobs("demo", "t7", "FINISHED", 100, 1, 10, response);

        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), eq(Integer.class), countArgs.capture());
        ArgumentCaptor<Object[]> listArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), eq(Long.class), listArgs.capture());
        List<Object> count = List.of(countArgs.getValue());
        List<Object> list = List.of(listArgs.getValue());
        assertEquals(list.subList(0, list.size() - 2), count, "列表 = count 参数 + (size, offset)");
        assertTrue(count.contains("FINISHED"), "status 过滤要同时进 count 与列表: " + count);
        assertTrue(count.contains("demo") && count.contains("t7"),
                "database/table 过滤要同时进 count 与列表: " + count);
    }

    // ---------------------------------------------------------------- 兼容模式

    @Test
    void legacyCallWithoutPagingKeepsArrayShapeAndHeaderAbsent() {
        List<Map<String, Object>> rows = List.of(jobRow(1));
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(1L));
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(rows);

        Object out = call(null, null, null, 100, null, null);

        List<?> list = assertInstanceOf(List.class, out, "不带 page/size 必须仍是数组（前端兼容）");
        assertEquals(1, list.size());
        assertEquals("t1", ((Map<?, ?>) list.get(0)).get("table"));
        verify(response, never()).setHeader(eq("X-Total-Count"), anyString());
        // 无分页 = 不发 count 查询
        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), any(Object[].class));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), eq(Long.class), args.capture());
        assertTrue(sql.getValue().contains("limit ?"), sql.getValue());
        assertTrue(!sql.getValue().contains("offset"), sql.getValue());
        assertEquals(100, args.getValue()[args.getValue().length - 1]);
    }

    @Test
    void legacyLimitStillClamped() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());

        call(null, null, null, 0, null, null);
        call(null, null, null, 99999, null, null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .queryForList(anyString(), eq(Long.class), args.capture());
        List<Object> first = List.of(args.getAllValues().get(0));
        List<Object> second = List.of(args.getAllValues().get(1));
        assertEquals(1, first.get(first.size() - 1), "limit<1 旧行为：夹到 1");
        assertEquals(500, second.get(second.size() - 1), "limit>500 旧行为：夹到 500");
    }
}
