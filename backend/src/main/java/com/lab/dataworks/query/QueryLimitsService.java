package com.lab.dataworks.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 查询限额（D4）：共用一个 Doris 账号，按租户动态下发会话参数。
 *
 * <p>为什么不在 Doris 里建用户：租户/用户一多就是几十个账号，运维成本与账号同步风险都高。
 * Doris 的 {@code SET exec_mem_limit / query_timeout / sql_select_limit /
 * parallel_fragment_exec_instance_num} 是会话级变量，逐次下发即可。</p>
 *
 * <p>与并发的关系：SET 是连接级、并发查询各用各的连接，不会互相覆盖；真正会串的是
 * 「连接池复用且跳过 SET」，所以这里要求**每次无条件 SET**（见 {@link #apply}），
 * 并且查询结束不依赖 RESET——因为下次进来还会重新 SET。</p>
 */
@Service
public class QueryLimitsService {

    private static final Logger log = LoggerFactory.getLogger(QueryLimitsService.class);

    /** 未配置时的兜底值，保持与 014 任务单一致。 */
    public static final Limits DEFAULTS = new Limits(4096, 60, 100000, 4, 5, 0);

    public record Limits(int maxMemMb, int timeoutS, int maxRows, int maxParallel,
                         int maxConcurrency, int dailyQueryLimit) {
    }

    /** 当前线程正在执行的查询限额；由控制器在下发查询前设置，Doris 执行时读取。 */
    private static final ThreadLocal<Limits> CURRENT = new ThreadLocal<>();

    public static void setCurrent(Limits limits) {
        CURRENT.set(limits);
    }

    public static Limits current() {
        return CURRENT.get();
    }

    public static void clearCurrent() {
        CURRENT.remove();
    }

    private final JdbcTemplate jdbc;
    private final Map<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

    public QueryLimitsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 用户级 > 租户级 > 系统默认。 */
    public Limits resolve(String tenant) {
        String t = tenant == null || tenant.isBlank()
                ? com.lab.dataworks.auth.TenantScope.DEFAULT : tenant;
        try {
            var rows = jdbc.queryForList("""
                    select max_mem_mb, timeout_s, max_rows, max_parallel,
                           max_concurrency, daily_query_limit
                      from platform.query_limits
                     where tenant_id = ? and user_id is null and enabled
                     limit 1
                    """, t);
            if (rows.isEmpty()) {
                return DEFAULTS;
            }
            Map<String, Object> r = rows.get(0);
            return new Limits(
                    ((Number) r.get("max_mem_mb")).intValue(),
                    ((Number) r.get("timeout_s")).intValue(),
                    ((Number) r.get("max_rows")).intValue(),
                    ((Number) r.get("max_parallel")).intValue(),
                    ((Number) r.get("max_concurrency")).intValue(),
                    ((Number) r.get("daily_query_limit")).intValue());
        } catch (Exception e) {
            log.debug("resolve query limits failed, fallback to defaults: {}", e.getMessage());
            return DEFAULTS;
        }
    }

    /** 并发闸：超过 maxConcurrency 直接拒，不把请求丢给 Doris。 */
    public boolean tryAcquire(String tenant, Limits limits) {
        if (limits.maxConcurrency() <= 0) {
            return true;
        }
        AtomicInteger counter = inflight.computeIfAbsent(key(tenant), k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= limits.maxConcurrency()) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release(String tenant) {
        AtomicInteger counter = inflight.get(key(tenant));
        if (counter != null) {
            counter.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }
    }

    public int queueDepth(String tenant) {
        AtomicInteger counter = inflight.get(key(tenant));
        return counter == null ? 0 : counter.get();
    }

    /**
     * 把限额下发到当前连接。必须每次查询都调用（不能只在连池时调用），
     * 否则连接复用会把上一个租户的限制带给下一个租户。
     */
    public static void apply(Connection conn, Limits limits) {
        if (conn == null || limits == null) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("set exec_mem_limit = " + (long) limits.maxMemMb() * 1024 * 1024);
            st.execute("set query_timeout = " + limits.timeoutS());
            st.execute("set sql_select_limit = " + limits.maxRows());
            st.execute("set parallel_fragment_exec_instance_num = " + limits.maxParallel());
        } catch (SQLException e) {
            // 限流不能反过来把查询打挂：失败只记日志，查询继续按服务端默认值跑
            log.warn("apply query limits failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> view(String tenant) {
        Limits l = resolve(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenant);
        out.put("limits", l);
        out.put("inflight", queueDepth(tenant));
        return out;
    }

    public Map<String, Object> update(String tenant, Limits limits, String updatedBy) {
        Limits value = limits == null ? DEFAULTS : limits;
        jdbc.update("""
                insert into platform.query_limits
                    (tenant_id, user_id, max_mem_mb, timeout_s, max_rows, max_parallel,
                     max_concurrency, daily_query_limit, updated_by, updated_at)
                values (?, null, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id, coalesce(user_id, 0)) do update
                   set max_mem_mb = excluded.max_mem_mb,
                       timeout_s = excluded.timeout_s,
                       max_rows = excluded.max_rows,
                       max_parallel = excluded.max_parallel,
                       max_concurrency = excluded.max_concurrency,
                       daily_query_limit = excluded.daily_query_limit,
                       updated_by = excluded.updated_by,
                       updated_at = now()
                """, tenant, value.maxMemMb(), value.timeoutS(), value.maxRows(),
                value.maxParallel(), value.maxConcurrency(), value.dailyQueryLimit(), updatedBy);
        return view(tenant);
    }

    private static String key(String tenant) {
        return tenant == null || tenant.isBlank()
                ? com.lab.dataworks.auth.TenantScope.DEFAULT : tenant;
    }
}
