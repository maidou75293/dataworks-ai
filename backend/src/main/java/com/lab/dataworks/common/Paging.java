package com.lab.dataworks.common;

/**
 * 列表分页契约（v449 P2 定下，D19 收成一处）。
 *
 * <p>约定：{@code page} 从 1 开始、{@code size} 取 1–500，非法值一律 400；
 * 分页响应带 {@code X-Total-Count} 响应头（同时保留 body 里的 total，前端读的是 body）。</p>
 *
 * <p>为什么要收口：项目列表 / 工作流列表 / 数据服务列表各自抄了一份同样的判断，
 * 而资产列表与 UDF 资源列表是「静默夹紧」——{@code page=0} 被改成 1、{@code size=501}
 * 被改成 200/500，客户端以为分页生效其实拿到的是被改写过的参数。同一组非法值
 * 有的 400 有的 200，前端无法用统一的分页组件。</p>
 */
public final class Paging {

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 500;

    private Paging() {
    }

    /**
     * 校验分页参数：非法值 400，不做静默夹紧。
     *
     * @param page 页码，可为 null（＝不指定，按 1 处理）
     * @param size 每页条数，可为 null（＝不指定，由调用方给默认值）
     */
    public static void requireValid(Integer page, Integer size) {
        if (size != null && (size < MIN_SIZE || size > MAX_SIZE)) {
            throw ApiException.badRequest("size 取值 " + MIN_SIZE + "–" + MAX_SIZE);
        }
        if (page != null && page < 1) {
            throw ApiException.badRequest("page 从 1 开始");
        }
    }

    /** 页码规范化：null / 小于 1 都按第 1 页（校验已挡掉非法值）。 */
    public static int pageOf(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    /** 每页条数规范化：null 用调用方默认值（调用前必须先 {@link #requireValid}）。 */
    public static int sizeOf(Integer size, int fallback) {
        return size == null ? fallback : size;
    }

    /**
     * offset 用 long 计算。
     *
     * <p>{@code (page - 1) * size} 按 int 乘会在 {@code page=2147483647} 上溢出成负数，
     * PG 报 {@code integer out of range}，被全局处理器归成 409「违反唯一性或外键约束」，
     * 把调用方引到完全无关的排查方向（D12）。超出总页数应当是 200 + 空列表。</p>
     */
    public static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }
}
