package com.lab.dataworks.project;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D41 回归：项目更新的保存语义（PATCH：请求里出现的字段才更新，显式 null = 清空）。
 *
 * <p>立案症状：旧实现用 {@code ProjectRequest} record + {@code req.xxx() != null} 判断，
 * 「没传」和「传 null」在 record 里无法区分，于是字段永远清不空；而 D40 把重命名收敛成
 * 只发 {@code {"name"}} 之后，又必须保证没发出去的字段不被清空。
 * 两者只能靠「请求里出现了哪些 key」区分 —— 所以 body 改成 Map。</p>
 *
 * <p>逐字段矩阵（字段 × {传值, 传 null, 不传}）见 {@code docs/保存语义-20261026.md}；
 * 本测试覆盖其中会丢数据、会越权、会静默忽略的格子。</p>
 */
class ProjectUpdateFieldsTest {

    private static final long PROJECT_ID = 7L;
    private static final String TENANT = "tenant_a";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private final List<String> executedSql = new ArrayList<>();
    private final List<Object[]> executedArgs = new ArrayList<>();
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        TenantScope.set(TENANT);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        controller = new ProjectController(jdbc, sessions, mock(DorisAdminService.class),
                mock(WarehouseConnectionService.class), tx,
                mock(com.lab.dataworks.workflow.WorkflowMutationService.class),
                mock(DataGuardService.class), mock(WarehouseResolver.class));

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.department")) {
                return List.of(Map.of("code", "gx", "dept_id", "01", "name", "共享",
                        "table_prefix", "gx_"));
            }
            if (sql.contains("from platform.project p")) {
                return List.of(projectRow());
            }
            return List.of();
        });
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> 1);                 // requireConnection：连接存在且类型匹配
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            executedSql.add(inv.getArgument(0));
            Object[] all = inv.getArguments();         // Mockito 把可变参数摊平
            Object[] args = new Object[Math.max(0, all.length - 1)];
            System.arraycopy(all, 1, args, 0, args.length);
            executedArgs.add(args);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    // ---------------------------------------------------------------- 矩阵：不传 = 不动

    @Test
    void onlyName_touchesNothingElse() {
        controller.update(PROJECT_ID, Map.of("name", "新名字"), http);

        String sql = executedSql.get(0);
        assertTrue(sql.contains("set name = ?"), sql);
        for (String column : List.of("description", "department_id", "business",
                "dev_database", "prod_database", "warehouse_datasource_id")) {
            assertFalse(sql.contains(column + " ="), column + " 不该进 set 子句：" + sql);
        }
        assertEquals(Arrays.asList("新名字", PROJECT_ID, TENANT),
                Arrays.asList(executedArgs.get(0)));
    }

    @Test
    void emptyBody_is400() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.update(PROJECT_ID, Map.of(), http));
        assertEquals(400, e.getStatus());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void unknownField_is400_notSilentlyIgnored() {
        // 旧 record 收 13 个字段只写 6 个，其余静默忽略 —— 正是 D40/D41 立案的根因。
        ApiException e = assertThrows(ApiException.class, () -> controller.update(PROJECT_ID,
                Map.of("name", "新名字", "storageType", "lake", "ownerUser", "bob"), http));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("storageType") && e.getMessage().contains("ownerUser"),
                e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void wrongType_is400() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.update(PROJECT_ID, Map.of("name", 123), http));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("必须是字符串"), e.getMessage());
    }

    // ---------------------------------------------------------------- 矩阵：显式 null = 清空

    @Test
    void explicitNullClearsDescription() {
        controller.update(PROJECT_ID, body("description", null), http);

        String sql = executedSql.get(0);
        assertTrue(sql.contains("set description = ?"), sql);
        assertNullArg(0, 0);                            // 绑定值确实是 null（清空），不是漏传
    }

    @Test
    void explicitNullClearsBusinessAndDepartment() {
        controller.update(PROJECT_ID, body("business", null, "departmentId", null), http);

        String sql = executedSql.get(0);
        assertTrue(sql.contains("business = ?") && sql.contains("department_id = ?"), sql);
        assertNullArg(0, 0);
        assertNullArg(0, 1);
    }

    @Test
    void explicitNullOnWarehouseDatasource_returnsToBuiltin() {
        controller.update(PROJECT_ID, body("warehouseDatasourceId", null), http);

        String sql = executedSql.get(0);
        assertTrue(sql.contains("warehouse_datasource_id = ?")
                && sql.contains("doris_connection_id = ?"), sql);
        assertNullArg(0, 0);
        assertNullArg(0, 1);
    }

    // ---------------------------------------------------------------- 矩阵：传值

    @Test
    void departmentAndBusinessAreWrittenWhenPresent() {
        controller.update(PROJECT_ID, Map.of("departmentId", 3L, "business", "gongxiang"), http);

        String sql = executedSql.get(0);
        assertTrue(sql.contains("department_id = ?") && sql.contains("business = ?"), sql);
        List<Object> args = Arrays.asList(executedArgs.get(0));
        assertTrue(args.contains(3L) && args.contains("gongxiang"), "绑定参数：" + args);
    }

    @Test
    void unknownDepartment_is400AndWritesNothing() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.project p")) {
                return List.of(projectRow());
            }
            return List.of();                          // 部门不存在
        });

        ApiException e = assertThrows(ApiException.class,
                () -> controller.update(PROJECT_ID, Map.of("departmentId", 999999L), http));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("部门不存在"), e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void databasesMustComeAsANonNullablePair() {
        ApiException half = assertThrows(ApiException.class,
                () -> controller.update(PROJECT_ID, Map.of("devDatabase", "sales_dev"), http));
        assertEquals(400, half.getStatus());
        assertTrue(half.getMessage().contains("成对"), half.getMessage());

        ApiException nulled = assertThrows(ApiException.class, () -> controller.update(PROJECT_ID,
                body("devDatabase", null, "prodDatabase", "sales_prd"), http));
        assertEquals(400, nulled.getStatus());

        controller.update(PROJECT_ID,
                Map.of("devDatabase", "sales_dev", "prodDatabase", "sales_prd"), http);
        String sql = executedSql.get(0);
        assertTrue(sql.contains("dev_database = ?") && sql.contains("prod_database = ?"), sql);
        List<Object> args = Arrays.asList(executedArgs.get(0));
        assertTrue(args.contains("sales_dev") && args.contains("sales_prd"), "绑定参数：" + args);
    }

    @Test
    void illegalDatabaseName_is400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.update(PROJECT_ID,
                Map.of("devDatabase", "1bad!", "prodDatabase", "sales_prd"), http));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("dev 库名不合法"), e.getMessage());
    }

    @Test
    void invalidBusinessName_is400AndWritesNothing() {
        // D44：业务线是 Doris 库名的派生源，非法值必须显式拒绝（不能静默写进去）
        ApiException e = assertThrows(ApiException.class,
                () -> controller.update(PROJECT_ID, Map.of("business", "销售 One"), http));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("业务英文名"), e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void validBusinessNameIsLowercasedAndWritten() {
        controller.update(PROJECT_ID, Map.of("business", " GongXiang "), http);
        List<Object> args = Arrays.asList(executedArgs.get(0));
        assertTrue(args.contains("gongxiang"), "应写入规范化后的业务名：" + args);
    }

    @Test
    void updateStillTenantScopedAndTransactional() {
        controller.update(PROJECT_ID, Map.of("name", "新名字"), http);
        assertTrue(executedSql.get(0).contains("where id = ? and tenant_id = ?"),
                executedSql.get(0));
    }

    // ---------------------------------------------------------------- 工具

    /** Map.of 不收 null（显式清空恰恰要传 null），所以自己拼一个允许 null 的 Map。 */
    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private void assertNullArg(int call, int index) {
        assertEquals(null, executedArgs.get(call)[index],
                "第 " + index + " 个绑定参数应为 null（显式清空）："
                        + Arrays.toString(executedArgs.get(call)));
    }

    private static Map<String, Object> projectRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", PROJECT_ID);
        row.put("tenant_id", TENANT);
        row.put("code", "gx_01_sales");
        row.put("name", "销售域");
        row.put("description", "描述");
        row.put("owner_user", "admin");
        row.put("department_id", 3L);
        row.put("business", "sales");
        row.put("dev_database", "sales_dev");
        row.put("prod_database", "sales_prd");
        row.put("sql_guard_enabled", true);
        row.put("storage_type", "doris");
        return row;
    }
}
