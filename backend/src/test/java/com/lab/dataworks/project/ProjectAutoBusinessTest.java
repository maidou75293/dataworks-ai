package com.lab.dataworks.project;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-DW-1 回归：库名前缀自动生成 + 提交前冲突预览。
 *
 * <p>立案症状：创建项目表单字段太多，前缀/库名要人手填，手填撞库要提交后吃 409。
 * 现在 business 不传 = 后端按「部门编码+部门ID」自动生成（冲突自动顺延），
 * {@code GET /api/projects/business-preview} 让表单在提交前就看到最终编码/库名/避让次数。</p>
 *
 * <p>逐条对应卡里判据：生成规则、避让顺延、用尽 409、非法部门 400、
 * 以及 create() 自动路径落库列（business/code/dev/prod/table_prefix）与预览一致。</p>
 */
class ProjectAutoBusinessTest {

    private static final String TENANT = "tenant_a";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private final WarehouseResolver warehouseResolver = mock(WarehouseResolver.class);
    /** 平台内已被占用的 Doris 库名（模拟别的租户/项目撞库）。 */
    private final Set<String> occupiedDatabases = new HashSet<>();
    /** 本租户内已被占用的项目编码。 */
    private final Set<String> occupiedCodes = new HashSet<>();
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        TenantScope.set(TENANT);
        when(http.getAttribute("username")).thenReturn("alice");
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        controller = new ProjectController(jdbc, mock(SessionService.class),
                mock(DorisAdminService.class), mock(WarehouseConnectionService.class), tx,
                mock(com.lab.dataworks.workflow.WorkflowMutationService.class),
                mock(DataGuardService.class), warehouseResolver);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.department")) {
                Object[] a = inv.getArguments();
                return departmentRows.getOrDefault(String.valueOf(a[a.length - 1]), List.of());
            }
            if (sql.contains("from platform.project")) {
                return List.of(Map.of("id", 99L, "code", "gx_01_gx01",
                        "dev_database", "gx01_dev", "prod_database", "gx01_prd"));
            }
            return List.of();
        });
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenAnswer(inv -> List.of(TENANT));   // ownerTenant：owner 的租户
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    Object[] a = inv.getArguments();
                    if (sql.contains("from platform.app_user")) {
                        return 1;                      // resolveOwner：账号存在且启用
                    }
                    if (sql.contains("where code = ? and tenant_id = ?")) {
                        return occupiedCodes.contains(String.valueOf(a[2])) ? 1 : 0;
                    }
                    if (sql.contains("dev_database in")) {
                        // 参数 = dev, prod, dev, prod；任一命中即占用
                        String dev = String.valueOf(a[2]);
                        String prod = String.valueOf(a[3]);
                        return occupiedDatabases.contains(dev) || occupiedDatabases.contains(prod)
                                ? 1 : 0;
                    }
                    return 0;
                });
        when(warehouseResolver.builtinDatasourceId()).thenReturn(5L);
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    /** id → 部门行；默认放 gx/01。 */
    private final Map<String, List<Map<String, Object>>> departmentRows = new TreeMap<>(Map.of(
            "1", List.of(Map.of("code", "gx", "dept_id", "01", "name", "共享服务部",
                    "table_prefix", "gx_"))));

    // ---------------------------------------------------------------- 预览接口

    @Test
    void preview_defaultRule_firstCandidateFree() {
        Map<String, Object> out = controller.businessPreview(1L);
        assertEquals("gx01", out.get("business"));
        assertEquals("gx_01_gx01", out.get("code"));
        assertEquals("gx01_dev", out.get("devDatabase"));
        assertEquals("gx01_prd", out.get("prodDatabase"));
        assertEquals("gx_", out.get("tablePrefix"));
        assertEquals(0, out.get("skipped"));
    }

    @Test
    void preview_bumpsWhenDatabasesOccupied() {
        // gx01 撞库 → 顺延 gx011；gx011 的 prod 也被占 → 顺延 gx012
        occupiedDatabases.add("gx01_dev");
        occupiedDatabases.add("gx011_prd");
        Map<String, Object> out = controller.businessPreview(1L);
        assertEquals("gx012", out.get("business"));
        assertEquals("gx_01_gx012", out.get("code"));
        assertEquals(2, out.get("skipped"));
    }

    @Test
    void preview_bumpsWhenTenantCodeOccupied() {
        occupiedCodes.add("gx_01_gx01");
        Map<String, Object> out = controller.businessPreview(1L);
        assertEquals("gx011", out.get("business"));
        assertEquals(1, out.get("skipped"));
    }

    @Test
    void preview_allCandidatesOccupied_is409_notSilent() {
        // 塞满 base=gx01 的前 1000 个候选对应的 dev 名：必须显式 409，不许静默返回撞名结果
        for (int n = 0; n < 1000; n++) {
            occupiedDatabases.add(n == 0 ? "gx01_dev" : "gx01" + n + "_dev");
        }
        ApiException e = assertThrows(ApiException.class, () -> controller.businessPreview(1L));
        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("用尽"), e.getMessage());
    }

    @Test
    void preview_unknownDepartment_is400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.businessPreview(77L));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("部门不存在"), e.getMessage());
    }

    @Test
    void preview_departmentCodeCannotFormPrefix_is400() {
        departmentRows.put("2", List.of(Map.of("code", "9x", "dept_id", "03", "name", "非法",
                "table_prefix", "x_")));
        ApiException e = assertThrows(ApiException.class, () -> controller.businessPreview(2L));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("推不出合法库名前缀"), e.getMessage());
    }

    // ---------------------------------------------------------------- create() 自动路径落库

    @Test
    void create_withoutBusiness_insertsGeneratedColumns() throws Exception {
        Map<Integer, Object> insertArgs = captureInsert();

        controller.create(new ProjectController.ProjectRequest(
                null, "共享出行", "描述", 1L, null, null,
                null, null, null, null, null, null, null), http);

        assertEquals("gx_01_gx01", insertArgs.get(1));   // code
        assertEquals("gx01", insertArgs.get(5));          // business 列落生成值（不能是 null/原值）
        assertEquals("gx01_dev", insertArgs.get(6));
        assertEquals("gx01_prd", insertArgs.get(7));
        assertEquals("gx_", insertArgs.get(8));           // table_prefix 继承部门
        assertEquals("alice", insertArgs.get(9));          // owner 默认创建者
        assertEquals(TENANT, insertArgs.get(10));
    }

    @Test
    void create_withoutBusiness_conflictBumpsSameAsPreview() throws Exception {
        occupiedDatabases.add("gx01_dev");
        Map<Integer, Object> insertArgs = captureInsert();

        controller.create(new ProjectController.ProjectRequest(
                null, "共享出行", null, 1L, null, null,
                null, null, null, null, null, null, null), http);

        assertEquals("gx_01_gx011", insertArgs.get(1));
        assertEquals("gx011", insertArgs.get(5));
        assertEquals("gx011_dev", insertArgs.get(6));
    }

    @Test
    void create_explicitBusiness_stillValidated() {
        // 老脚本显式传 business：非法值仍显式 400，不许静默规范化
        ApiException e = assertThrows(ApiException.class, () -> controller.create(
                new ProjectController.ProjectRequest(
                        null, "共享出行", null, 1L, "Bad Name", null,
                        null, null, null, null, null, null, null), http));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("业务英文名"), e.getMessage());
    }

    /** 把 create() 的 insert PreparedStatementCreator 引到 mock Connection 上，捕获 setXxx 实参。 */
    private Map<Integer, Object> captureInsert() throws Exception {
        Map<Integer, Object> insertArgs = new TreeMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(inv -> {
            insertArgs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ps).setString(anyInt(), any());
        doAnswer(inv -> {
            insertArgs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ps).setLong(anyInt(), anyLong());
        doAnswer(inv -> {
            insertArgs.put(inv.getArgument(0), "NULL@" + inv.getArgument(1));
            return null;
        }).when(ps).setNull(anyInt(), anyInt());
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString(), anyInt())).thenReturn(ps);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(inv -> {
            PreparedStatementCreator psc = inv.getArgument(0);
            psc.createPreparedStatement(conn);
            GeneratedKeyHolder keys = inv.getArgument(1);
            keys.getKeyList().add(Map.of("id", 99L));
            return 1;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> 1); // registerDatabase
        return insertArgs;
    }
}
