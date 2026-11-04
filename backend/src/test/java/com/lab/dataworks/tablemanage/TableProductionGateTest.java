package com.lab.dataworks.tablemanage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-TC4-1：生产库（{@code *_prd}）的建表 / SQL 建表 / 删表必须与 alter 族同一道闸门。
 *
 * <p>立案事实（TC-4 实测）：{@code alter} 打 {@code _prd} 会被要求填原因并挂审批工单，
 * 而 {@code POST /api/tables}、{@code POST /api/tables/sql}、
 * {@code DELETE /api/tables/{db}/{table}} 打同一个库却 200 立即执行 ——
 * DEVELOPER 可以绕过审批在生产库建表删表。</p>
 */
class TableProductionGateTest {

    private static final String DEV_DB = "bug_t4_dev";
    private static final String PROD_DB = "bug_t4_prd";
    private static final String DDL = "create table `bug_t4_prd`.`t_gate` (id int not null)";
    private static final long JOB_ID = 4242L;

    private JdbcTemplate jdbc;
    private DorisAdminService doris;
    private TableDdlService ddl;
    private HttpServletRequest http;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        doris = mock(DorisAdminService.class);
        SessionService sessions = mock(SessionService.class);
        ddl = new TableDdlService(jdbc, doris, sessions, mock(TenantDataScope.class),
                new ObjectMapper(), mock(WarehouseResolver.class),
                mock(com.lab.dataworks.metadata.MetadataReader.class));
        http = mock(HttpServletRequest.class);
        when(http.getAttribute("username")).thenReturn("dev_alice");
        when(http.getRemoteAddr()).thenReturn("10.99.99.30");
        // 没有进行中的工单
        when(jdbc.queryForList(contains("status in ('PENDING_APPROVAL', 'PENDING', 'RUNNING')"),
                any(Object[].class))).thenReturn(List.of());
        // 工单详情（jobView → requireJob）
        when(jdbc.queryForList(contains("from platform.table_ddl_job"), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("status in (")) {
                        return List.of();
                    }
                    return List.of(jobRow());
                });
        when(jdbc.queryForObject(contains("insert into platform.table_ddl_job"), eq(Long.class),
                any(Object[].class))).thenReturn(JOB_ID);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    private static Map<String, Object> jobRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", JOB_ID);
        row.put("tenant_id", "default");
        row.put("database_name", PROD_DB);
        row.put("table_name", "t_gate");
        row.put("env", "PROD");
        row.put("operation", "CREATE_TABLE");
        row.put("status", "PENDING_APPROVAL");
        row.put("version", 0);
        row.put("ddl_sql", DDL);
        row.put("requested_by", "dev_alice");
        return row;
    }

    // ------------------------------------------------------------ 判定本身

    @Test
    void devLandingIsNotGated() {
        TableDdlService.ProductionGate gate = ddl.gateProductionDdl("CREATE_TABLE",
                null, DEV_DB, "t_gate", DDL, null, http);

        assertEquals("DEV", gate.env());
        assertNull(gate.queued(), "开发库照常直接执行，不能挂工单");
        verify(jdbc, never()).queryForObject(contains("insert into platform.table_ddl_job"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void prodWithoutReasonIsRejectedWithExistingWording() {
        ApiException error = assertThrows(ApiException.class,
                () -> ddl.gateProductionDdl("CREATE_TABLE", null, PROD_DB, "t_gate", DDL, null, http));

        assertEquals(400, error.getStatus());
        // 文案与 alter 族完全一致（TC-4 TB-05/06/07 就是按这句话过的）
        assertEquals("生产库变更必须填写原因（5-500 字符）", error.getMessage());
        verify(jdbc, never()).queryForObject(contains("insert into platform.table_ddl_job"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void prodWithTooShortReasonIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> ddl.gateProductionDdl("DROP_TABLE", null, PROD_DB, "t_gate", DDL, "abc", http));

        assertEquals(400, error.getStatus());
    }

    @Test
    void prodWithReasonQueuesApprovalJobAndDoesNotExecute() {
        TableDdlService.ProductionGate gate = ddl.gateProductionDdl("DROP_TABLE",
                null, PROD_DB, "t_gate", "drop table `bug_t4_prd`.`t_gate`",
                "上线遗留空表需要清掉", http);

        assertEquals("PROD", gate.env());
        assertNotNull(gate.queued());
        assertEquals(false, gate.queued().get("executed"), "挂工单就不能已经执行");
        assertEquals(true, gate.queued().get("pendingApproval"));
        assertEquals(JOB_ID, ((Map<?, ?>) gate.queued().get("job")).get("id"));
        assertTrue(String.valueOf(gate.queued().get("message")).contains("审批"),
                String.valueOf(gate.queued().get("message")));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(contains("insert into platform.table_ddl_job"),
                eq(Long.class), args.capture());
        List<Object> values = java.util.Arrays.asList(args.getValue());
        assertEquals(PROD_DB, values.get(4), "database_name");
        assertEquals("t_gate", values.get(5), "table_name");
        assertEquals("DROP_TABLE", values.get(6), "operation 要按入口区分，审批页才看得出是删表");
        assertEquals("上线遗留空表需要清掉", values.get(13), "reason 必须落库");
        assertTrue(String.valueOf(values.get(10)).contains("drop table"),
                "要执行的 DDL 存进工单: " + values.get(10));

        // 关键：一道都没碰 Doris（没有立即执行）
        verify(doris, never()).exec(any(), anyString());
        verify(doris, never()).exec(anyString());
    }

    /** 项目两列解析不出来时按命名约定兜底，闸门不能留空档。 */
    @Test
    void fallsBackToNamingRuleWhenProjectDoesNotOwnTheDatabase() {
        // resolve(projectId=99, bug_t4_prd) 查不到项目 → 404 → 走后缀兜底
        when(jdbc.queryForList(contains("from platform.project"), any(Object[].class)))
                .thenReturn(List.of());

        TableDdlService.ProductionGate gate = ddl.gateProductionDdl("CREATE_TABLE",
                99L, PROD_DB, "t_gate", DDL, "补一份对账用的日表", http);

        assertEquals("PROD", gate.env());
        assertNotNull(gate.queued(), "解析不出项目也要挡住生产库");
    }

    // -------------------------------------------------------- 控制器接线

    @Test
    void createTableEndpointOnProdRequiresReason() throws Exception {
        MockMvc mvc = mockMvc(ddl);
        String body = json(Map.of("database", PROD_DB, "table", "t_gate",
                "columns", List.of(Map.of("name", "id", "type", "INT", "nullable", false)),
                "keys", List.of("id"), "buckets", 1, "replication", 1, "model", "DUPLICATE"));

        MvcResult result = mvc.perform(post("/api/tables").contentType("application/json")
                .content(body)).andReturn();

        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("生产库变更必须填写原因"),
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        verify(doris, never()).exec(any(), anyString());
    }

    @Test
    void createTableEndpointOnProdQueuesApproval() throws Exception {
        MockMvc mvc = mockMvc(ddl);
        String body = json(Map.of("database", PROD_DB, "table", "t_gate",
                "columns", List.of(Map.of("name", "id", "type", "INT", "nullable", false)),
                "keys", List.of("id"), "buckets", 1, "replication", 1, "model", "DUPLICATE",
                "reason", "对账需要新增日表"));

        MvcResult result = mvc.perform(post("/api/tables").contentType("application/json")
                .content(body)).andReturn();

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("\"executed\":false"),
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("PENDING_APPROVAL"),
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        verify(doris, never()).exec(any(), anyString());
    }

    @Test
    void createTableEndpointOnDevStillExecutesAndEchoesEnv() throws Exception {
        MockMvc mvc = mockMvc(ddl);
        String body = json(Map.of("database", DEV_DB, "table", "t_gate",
                "columns", List.of(Map.of("name", "id", "type", "INT", "nullable", false)),
                "keys", List.of("id"), "buckets", 1, "replication", 1, "model", "DUPLICATE"));

        MvcResult result = mvc.perform(post("/api/tables").contentType("application/json")
                .content(body)).andReturn();

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("\"env\":\"DEV\""),
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        verify(doris).exec(any(), anyString());
    }

    private MockMvc mockMvc(TableDdlService service) {
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class),
                mock(SessionService.class), mock(TenantDataScope.class),
                mock(WarehouseResolver.class), mock(MetadataReader.class),
                mock(DataGuardService.class), service);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String json(Map<String, Object> body) {
        try {
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
