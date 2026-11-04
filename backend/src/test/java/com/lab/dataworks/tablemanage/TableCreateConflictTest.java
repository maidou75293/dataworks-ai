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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D60（TC-2 用例 TB-07b）：重复建表必须是 4xx 冲突，不是 502。
 *
 * <p>缺陷现场（v539 live 实测，r2/r3 两轮都复现）：同库第二次创建同名表拿到
 * {@code 502 {"message":"Doris 执行失败: errCode = 2, detailMessage = Table
 * 'bug_a_t037329' already exists"}}——纯客户端冲突被上报成网关故障，
 * 前端只能显示「上游执行失败」，用户不知道该换表名。</p>
 *
 * <p>要钉住的口径：</p>
 * <ol>
 *   <li>同名表 → <b>409</b> + 中文「表已存在: db.table」，且不执行任何 DDL；</li>
 *   <li>并发窗口（预检过了、Doris 那边已经存在）→ 也还原成 409；</li>
 *   <li><b>其它</b> 502（连不上、语法错）状态码不许动，不许借「冲突」吞异常；</li>
 *   <li>入参不合法仍是 400——预检排在 DDL 生成之后，冲突不能抢在参数校验前面；</li>
 *   <li>预检语句本身跑不动（外部 Doris）不能把正常建表一起拖死；</li>
 *   <li>Doris 表名大小写不敏感，只差大小写也算已存在。</li>
 * </ol>
 *
 * <p>全部 mock：不起 Spring、不连 Doris。</p>
 */
class TableCreateConflictTest {

    private static final String DB = "audtze9k_db";
    private static final String TABLE = "bug_a_t037329";

    private DorisAdminService doris;
    private SessionService sessions;
    private MetadataReader metadata;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        metadata = mock(MetadataReader.class);
        // CONFLICT-WM1：合流后建表入口多了 BUG-TC4-1 的生产闸（第 9 个依赖 TableDdlService）。
        // 本卡用例的库都不是 _prd，按闸门口径拿 DEV 直通结果即可，冲突判定仍由 D60 逻辑负责。
        TableDdlService ddl = mock(TableDdlService.class);
        when(ddl.gateProductionDdl(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TableDdlService.ProductionGate("DEV", null));
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class), sessions,
                mock(TenantDataScope.class), mock(WarehouseResolver.class), metadata,
                mock(DataGuardService.class), ddl);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 卡面 repro 的请求体（TC-2 原文）。 */
    private static String body(String table, String columnsJson) {
        return "{\"projectId\":1,\"database\":\"" + DB + "\",\"table\":\"" + table
                + "\",\"columns\":" + columnsJson + "}";
    }

    private static final String OK_COLUMNS =
            "[{\"name\":\"id\",\"type\":\"BIGINT\",\"nullable\":false},"
                    + "{\"name\":\"name\",\"type\":\"VARCHAR(64)\"}]";

    /** SHOW TABLES … LIKE 的一行（列名是 Doris 的 Tables_in_<db>）。 */
    private static List<Map<String, Object>> showTablesRow(String name) {
        return List.of(Map.of("Tables_in_" + DB, name));
    }

    private String message(MvcResult result) throws Exception {
        return new ObjectMapper().readValue(rawBody(result), Map.class).get("message").toString();
    }

    /** 响应原文（UTF-8）：MockHttpServletResponse 默认按 ISO-8859-1 解码，中文会变问号。 */
    private String rawBody(MvcResult result) throws java.io.UnsupportedEncodingException {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void duplicateTableIsConflictNotBadGateway() throws Exception {
        when(doris.queryAs(any(), startsWith("show tables from")))
                .thenReturn(showTablesRow(TABLE));

        MvcResult result = mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TABLE, OK_COLUMNS)))
                .andExpect(status().isConflict())
                .andReturn();

        String message = message(result);
        assertTrue(message.contains("表已存在"), "要指明冲突对象: " + message);
        assertTrue(message.contains(DB + "." + TABLE), "要带库表全名可定位: " + message);
        // 关键：没有真的再执行一次 DDL
        verify(doris, never()).exec(any(), anyString());
        verify(sessions, never()).audit(any(), eq("TABLE_CREATE"), any(), any());
        verify(metadata, never()).invalidateProject(any());
    }

    /** 预检没查到、但 Doris 在执行时拒绝（并发建表窗口）→ 同样还原 409。 */
    @Test
    void concurrentCreateIsAlsoConflict() throws Exception {
        when(doris.queryAs(any(), startsWith("show tables from"))).thenReturn(List.of());
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = Table '"
                + TABLE + "' already exists"))
                .when(doris).exec(any(), contains("create table"));

        MvcResult result = mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TABLE, OK_COLUMNS)))
                .andExpect(status().isConflict())
                .andReturn();

        assertTrue(message(result).contains("表已存在"), message(result));
        verify(sessions, never()).audit(any(), eq("TABLE_CREATE"), any(), any());
    }

    /**
     * 不许借「冲突」把别的 502 的状态码改掉：连不上就是连不上（502 保留）。
     *
     * <p>本用例原来断言「原文要保留」（{@code Connection refused} + 内部端点）。
     * D63（TC-5 T5-TB-*）把这条口径推翻了：**状态码保留，但响应文案必须消毒**——
     * 外部连接分支会把 {@code doris-fe:9030} 这类集群内端点拼进 message。
     * 所以这里改成：502 不变 + 原文与端点都不出现（原文进日志）。</p>
     */
    @Test
    void otherExecutionFailuresKeepTheirStatusCodeButNotTheirRawText() throws Exception {
        when(doris.queryAs(any(), startsWith("show tables from"))).thenReturn(List.of());
        doThrow(new ApiException(502, "Doris 执行失败（外部连接 doris-fe.dw-dev:9030）: Connection refused"))
                .when(doris).exec(any(), anyString());

        MvcResult result = mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TABLE, OK_COLUMNS)))
                .andExpect(status().isBadGateway())
                .andReturn();

        String body = rawBody(result);
        assertFalse(body.contains("Connection refused"), "驱动原文不许透传: " + body);
        assertFalse(body.contains("doris-fe"), "内部端点不许透出: " + body);
        assertTrue(message(result).contains("无法连接到数仓"), message(result));
    }

    /** 顺序口径：参数不合法仍是 400，不能被同名冲突抢在前面。 */
    @Test
    void invalidColumnsStayBadRequestBeforeAnyProbe() throws Exception {
        when(doris.queryAs(any(), startsWith("show tables from"))).thenReturn(showTablesRow(TABLE));

        MvcResult result = mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TABLE, "[]")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertTrue(message(result).contains("至少需要一个字段"), message(result));
        verify(doris, never()).queryAs(any(), startsWith("show tables from"));
    }

    /** 预检语句在外部 Doris 上跑不动时，正常建表不许被拖死（记 warn 继续 exec）。 */
    @Test
    void probeFailureDoesNotBlockCreate() throws Exception {
        when(doris.queryAs(any(), startsWith("show tables from")))
                .thenThrow(new ApiException(502, "Doris 查询失败: 权限不足"));

        mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TABLE, OK_COLUMNS)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(doris).exec(any(), ddl.capture());
        assertTrue(ddl.getValue().startsWith("create table "), ddl.getValue());
        verify(metadata).invalidateProject(1L);
    }

    /** 全新表（预检空结果）仍是 200 + 审计 + 缓存放行——修复不许误伤正向路径。 */
    @Test
    void freshTableIsStillCreated() throws Exception {
        when(doris.queryAs(any(), startsWith("show tables from"))).thenReturn(List.of());

        mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TABLE, OK_COLUMNS)))
                .andExpect(status().isOk());

        verify(doris).exec(any(), contains("create table "));
        verify(sessions).audit(eq("unknown"), eq("TABLE_CREATE"), eq(DB + "." + TABLE),
                contains("columns=2"));
    }

    /** Doris 表名大小写不敏感：只改了大小写也算已存在。 */
    @Test
    void caseInsensitiveNameIsAlsoConflict() throws Exception {
        when(doris.queryAs(any(), startsWith("show tables from")))
                .thenReturn(showTablesRow("BUG_A_T037329"));

        mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                        .content(body(TABLE, OK_COLUMNS)))
                .andExpect(status().isConflict());

        verify(doris, never()).exec(any(), anyString());
    }

    /**
     * SQL 模式同一个 502 口径问题：只还原状态码，<b>不做预检</b>——
     * 用户 SQL 里写了 {@code create table if not exists} 时预检会把它误判成冲突。
     */
    @Test
    void sqlModeConflictIsMappedToo() throws Exception {
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = Table"
                + " 'sql_t' already exists"))
                .when(doris).exec(any(), anyString());
        String sql = "create table `" + DB + "`.`sql_t` (`id` bigint not null) engine=olap"
                + " duplicate key(`id`) distributed by hash(`id`) buckets 1"
                + " properties (\"replication_allocation\"=\"tag.location.default: 1\")";
        String json = new ObjectMapper().writeValueAsString(
                Map.of("projectId", 1, "database", DB, "sql", sql));

        MvcResult result = mvc.perform(post("/api/tables/sql").contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();

        assertEquals(409, result.getResponse().getStatus(),
                "SQL 模式重复建表应 409，响应原文: " + rawBody(result));
        assertTrue(message(result).contains("表已存在"), message(result));
        // SQL 模式没有预检这一步（见注释）
        verify(doris, never()).queryAs(any(), startsWith("show tables from"));
    }
}
