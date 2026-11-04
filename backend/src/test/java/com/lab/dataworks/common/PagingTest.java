package com.lab.dataworks.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D19：分页契约收口——{@code page} 从 1 开始、{@code size} 1–500，非法值 400（不静默夹紧），
 * offset 用 long 计算（D12 同根因：int 溢出会让 PG 报 integer out of range，被归成 409）。
 */
class PagingTest {

    @Test
    void rejectsPageBelowOne() {
        for (int page : new int[]{0, -1, Integer.MIN_VALUE}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> Paging.requireValid(page, 20), "page 必须 >= 1: " + page);
            assertEquals(400, e.getStatus());
            assertTrue(e.getMessage().contains("page"), e.getMessage());
        }
    }

    @Test
    void rejectsSizeOutOfRange() {
        for (int size : new int[]{0, -5, 501, 100000}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> Paging.requireValid(1, size), "size 必须在 1–500: " + size);
            assertEquals(400, e.getStatus());
            assertTrue(e.getMessage().contains("size"), e.getMessage());
        }
    }

    @Test
    void acceptsContractRange() {
        Paging.requireValid(1, 1);
        Paging.requireValid(1, 500);
        Paging.requireValid(Integer.MAX_VALUE, 500);
        // 两个参数都缺省＝「不指定分页」，由调用方决定默认值
        Paging.requireValid(null, null);
        Paging.requireValid(null, 20);
        Paging.requireValid(3, null);
    }

    @Test
    void normalizesMissingValues() {
        assertEquals(1, Paging.pageOf(null));
        assertEquals(7, Paging.pageOf(7));
        assertEquals(20, Paging.sizeOf(null, 20));
        assertEquals(500, Paging.sizeOf(500, 20));
    }

    @Test
    void offsetUsesLongMathAndDoesNotOverflow() {
        assertEquals(0L, Paging.offset(1, 500));
        assertEquals(500L, Paging.offset(2, 500));
        // 修复前 (page - 1) * size 按 int 乘会溢出成负数 → PG integer out of range → 409
        assertEquals(1_073_741_823_000L, Paging.offset(Integer.MAX_VALUE, 500));
        assertTrue(Paging.offset(Integer.MAX_VALUE, 500) > 0,
                "超大 page 的 offset 不能是负数");
    }
}
