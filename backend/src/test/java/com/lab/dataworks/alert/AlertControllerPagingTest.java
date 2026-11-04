package com.lab.dataworks.alert;

import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D19：{@code GET /api/alerts} 的分页契约。
 *
 * <p>原状：page/size 被完全忽略，{@code ?page=1&size=1} 仍按 limit（默认 200）全量返回，
 * 也不带 X-Total-Count。修复后与平台契约一致（common/Paging，UDF/资产同口径）：
 * 传了 page/size 任一即从严分页（page 从 1、size 1–500、非法 400、响应带 X-Total-Count）；
 * 两者都不传保持旧结构（数组 + limit），兼容告警中心前端。</p>
 */
class AlertControllerPagingTest {

    private JdbcTemplate jdbc;
    private AlertController controller;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        controller = new AlertController(jdbc, mock(com.lab.dataworks.auth.SessionService.class),
                mock(AlertService.class));
        response = mock(HttpServletResponse.class);
    }

    private Object call(Integer page, Integer size, int limit) {
        return controller.events(null, null, null, null, null, null, null, page, size, limit, response);
    }

    // ------------------------------------------------------------------ 非法参数

    @Test
    void illegalPageOrSizeIs400BeforeAnyQuery() {
        for (Integer[] pair : new Integer[][]{{0, 10}, {-1, 10}, {1, 0}, {1, 501}, {null, -5}}) {
            ApiException e = assertThrows(ApiException.class, () -> call(pair[0], pair[1], 200),
                    "非法分页必须 400: page=" + pair[0] + " size=" + pair[1]);
            assertEquals(400, e.getStatus());
        }
        // 校验在任何 SQL 发出之前——不能先查了再报错
        verifyNoInteractions(jdbc);
    }

    @Test
    void errorMessagesMatchPlatformContract() {
        ApiException page = assertThrows(ApiException.class, () -> call(0, 10, 200));
        assertTrue(page.getMessage().contains("page 从 1 开始"), page.getMessage());
        ApiException size = assertThrows(ApiException.class, () -> call(1, 501, 200));
        assertTrue(size.getMessage().contains("size 取值 1–500"), size.getMessage());
    }

    // ------------------------------------------------------------------ 分页模式

    @Test
    @SuppressWarnings("unchecked")
    void pagedModeReturnsSliceWithTotalAndHeader() {
        List<Map<String, Object>> page1 = List.of(Map.of("id", 9), Map.of("id", 8));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(57L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(page1);

        Object out = call(1, 2, 200);

        Map<String, Object> body = assertInstanceOf(Map.class, out);
        assertEquals(57L, body.get("total"));
        assertEquals(1, body.get("page"));
        assertEquals(2, body.get("size"));
        assertEquals(page1, body.get("items"));
        verify(response).setHeader("X-Total-Count", "57");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("limit ? offset ?"), sql.getValue());
        assertEquals(2, args.getValue()[args.getValue().length - 2], "size 参数");
        assertEquals(0L, args.getValue()[args.getValue().length - 1], "offset 必须是 long");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageTwoOffsetUsesLongMath() {
        // D12 同源边界：(page-1)*size 必须按 long 算，page=2147483647 不能溢出成负数
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Object out = call(2147483647, 10, 200);

        Map<String, Object> body = assertInstanceOf(Map.class, out);
        assertEquals(0L, body.get("total"));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), args.capture());
        Object offset = args.getValue()[args.getValue().length - 1];
        assertEquals((long) (2147483647 - 1) * 10, offset);
    }

    @Test
    void pageSizeDefaultsWhenOnlyPageGiven() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> body = assertInstanceOf(Map.class, call(2, null, 200));
        assertEquals(20, body.get("size"), "只传 page 时每页 20（与 UDF 列表同口径）");
    }

    @Test
    void filtersApplyToCountAndSliceIdentically() {
        // 回归防线：count 与列表必须共用同一份 where/args，否则 total 与 items 口径漂移
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("id", 7)));

        controller.events("OPEN", null, null, 5L, "磁盘", null, null, 1, 10, 200, response);

        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), eq(Long.class), countArgs.capture());
        ArgumentCaptor<Object[]> listArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), listArgs.capture());
        // 列表参数 = count 参数 + (size, offset)
        List<Object> count = List.of(countArgs.getValue());
        List<Object> list = List.of(listArgs.getValue());
        assertEquals(list.subList(0, list.size() - 2), count);
        assertTrue(count.stream().anyMatch(a -> String.valueOf(a).contains("磁盘")),
                "keyword 的 LIKE 参数要同时进 count 与列表: " + count);
    }

    // ------------------------------------------------------------------ 兼容模式

    @Test
    void legacyCallWithoutPagingKeepsArrayShapeAndLimit() {
        List<Map<String, Object>> rows = List.of(Map.of("id", 1));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

        Object out = call(null, null, 200);

        List<Map<String, Object>> list = assertInstanceOf(List.class, out);
        assertEquals(rows, list);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("order by e.id desc limit ?"), sql.getValue());
        assertTrue(!sql.getValue().contains("offset"), sql.getValue());
        assertEquals(200, args.getValue()[args.getValue().length - 1]);
        verify(response, org.mockito.Mockito.never())
                .setHeader(eq("X-Total-Count"), anyString());
    }

    @Test
    void legacyLimitStillClampedToOneToFiveHundred() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        call(null, null, 0);
        call(null, null, 99999);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).queryForList(anyString(), args.capture());
        List<List<Object>> all = args.getAllValues().stream().map(List::of).toList();
        assertEquals(1, all.get(0).get(all.get(0).size() - 1), "limit<0 旧行为：夹到 1");
        assertEquals(500, all.get(1).get(all.get(1).size() - 1), "limit>500 旧行为：夹到 500");
    }
}
