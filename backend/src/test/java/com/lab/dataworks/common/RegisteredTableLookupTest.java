package com.lab.dataworks.common;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-TC4-5：403 要能分清「未授权」与「未纳管」。
 *
 * <p>TC-4 实测：查询未纳管表 → 403「如需访问请提交权限申请」，
 * 但按文案去 {@code POST /api/access/requests} 申请同一张表 → 404「资源不存在或未纳管」，
 * 指了一条走不通的路。分流的前提就是「这张表在不在纳管清单里」。</p>
 */
class RegisteredTableLookupTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantDataScope scope = new TenantDataScope(jdbc);

    @Test
    void registeredWhenTableOrWholeDatabaseIsNagged() {
        when(jdbc.queryForObject(contains("from platform.managed_table"), eq(Integer.class),
                any(Object[].class))).thenReturn(1);

        assertTrue(scope.registeredTable("demo_retail", "ods_order"));
        // 库里任一张表走「库级纳管」（table_name='*'）也算已纳管，由 SQL 的 or 分支兜住
        verifyCountArgs("demo_retail", "ods_order");
    }

    @Test
    void notRegisteredWhenNoRow() {
        when(jdbc.queryForObject(contains("from platform.managed_table"), eq(Integer.class),
                any(Object[].class))).thenReturn(0);

        assertFalse(scope.registeredTable("bug_a_t4_env_prd", "bug_a_t4_t1"));
    }

    /** 缺参数不能拼一次没意义的查询（也避免把 null 当成「已纳管」）。 */
    @Test
    void blankArgumentsAreNotRegistered() {
        assertFalse(scope.registeredTable(null, "t"));
        assertFalse(scope.registeredTable("db", " "));
    }

    @Test
    void queryIsTenantScopedByKeyOnly() {
        when(jdbc.queryForObject(contains("from platform.managed_table"), eq(Integer.class),
                any(Object[].class))).thenAnswer(inv -> {
            // 两个占位符：库名、表名（大小写不敏感在 SQL 里做）
            // 展开后参数是 [sql, Integer.class, 库名, 表名]
            assertEquals("DB_Mixed", inv.getArguments()[2]);
            assertEquals("T_Mixed", inv.getArguments()[3]);
            return 1;
        });

        assertTrue(scope.registeredTable("DB_Mixed", "T_Mixed"));
    }

    private void verifyCountArgs(String database, String table) {
        when(jdbc.queryForObject(contains("from platform.managed_table"), eq(Integer.class),
                any(Object[].class))).thenAnswer(inv -> {
            assertEquals(database, inv.getArguments()[2]);
            assertEquals(table, inv.getArguments()[3]);
            return 1;
        });
        assertTrue(scope.registeredTable(database, table));
    }
}
