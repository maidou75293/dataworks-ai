package com.lab.dataworks.tablemanage;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D63（TC-5 用例 T5-TB-01 / 02 / 05b / 06b）：Doris 执行错误不能一律 502 并透传上游原文。
 *
 * <p>四条变体同一根因（v539 live 原文）：</p>
 * <ul>
 *   <li>删不存在的表 → 502 {@code … errCode = 2, detailMessage = Unknown table 'x' in db}</li>
 *   <li>删不存在库里的表 → 502 {@code … Unknown database 'y'}</li>
 *   <li>SQL 模式建表列类型非法 → 502 {@code … no viable alternative at input …}</li>
 *   <li>并发删除后到者 → 502（应按 404 收敛）</li>
 * </ul>
 *
 * <p>映射口径（{@link TableController#dorisFailure} 的一张表）：
 * already exists→409 / Unknown table→404 / Unknown database→404 / 语法与类型→400 /
 * 连不上与其它→保留 502，但 message 不得含 {@code errCode =}、{@code detailMessage}、
 * 内部端点（外部连接分支原来会把 {@code c.endpoint()} 拼进文案）。原文进日志。</p>
 *
 * <p>顺带把 D60 的两条旧期望改到新口径上：其它 502 的**状态码保留**，但原文不再透传。</p>
 */
class TableDorisFailureTest {

    private DorisAdminService doris;
    private SessionService sessions;
    private TableDdlService ddl;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        ddl = mock(TableDdlService.class);
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class), sessions,
                mock(TenantDataScope.class), mock(WarehouseResolver.class),
                mock(MetadataReader.class), mock(DataGuardService.class), ddl);
        // BUG-TC4-1：SQL 建表会先过生产库闸门；表族用例默认走「非生产库、无需工单」
        when(ddl.gateProductionDdl(anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new TableDdlService.ProductionGate("DEV", null));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String message(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class)
                .get("message").toString();
    }

    private static String raw(MvcResult result) throws UnsupportedEncodingException {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 映射表本体

    @Test
    void mappingTableIsOneTableNotScatteredIfs() {
        ApiException exists = TableController.dorisFailure(
                new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = Table 't' already exists"),
                "db1", "t");
        assertEquals(409, exists.getStatus());
        assertTrue(exists.getMessage().contains("表已存在: db1.t"), exists.getMessage());

        ApiException unknownTable = TableController.dorisFailure(
                new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = Unknown table 't' in db1"),
                "db1", "t");
        assertEquals(404, unknownTable.getStatus());
        assertEquals("表不存在: db1.t", unknownTable.getMessage());

        ApiException unknownDb = TableController.dorisFailure(
                new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = Unknown database 'db9'"),
                "db9", "t");
        assertEquals(404, unknownDb.getStatus());
        // 库名取上游给的那个，不靠请求参数猜（并发时参数已被改过会指错对象）
        assertEquals("库不存在: db9", unknownDb.getMessage());

        ApiException syntax = TableController.dorisFailure(
                new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = "
                        + "no viable alternative at input 'decimal(3'"), "db1", "t");
        assertEquals(400, syntax.getStatus());
        assertTrue(syntax.getMessage().contains("SQL 语法或字段类型不正确"), syntax.getMessage());

        ApiException connectivity = TableController.dorisFailure(
                new ApiException(502, "Doris 执行失败（外部连接 doris-fe.dw-dev:9030）: Connection refused"),
                "db1", "t");
        assertEquals(502, connectivity.getStatus());
        assertFalse(connectivity.getMessage().contains("doris-fe"), "内部端点不许出现在响应: "
                + connectivity.getMessage());
        assertFalse(connectivity.getMessage().contains("Connection refused"),
                "驱动原文不许透传: " + connectivity.getMessage());

        ApiException other = TableController.dorisFailure(
                new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = some internal thing"),
                "db1", "t");
        assertEquals(502, other.getStatus());
        assertFalse(other.getMessage().contains("errCode"), "未归类也不许带内部错误格式: "
                + other.getMessage());
        assertFalse(other.getMessage().contains("detailMessage"), other.getMessage());
    }

    /** 表名为空时（建库侧）文案只带库名，不能拼出 "null"。 */
    @Test
    void nullTableFallsBackToDatabaseOnly() {
        ApiException e = TableController.dorisFailure(
                new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = "
                        + "Access violation to database db9"), "db9", null);
        assertEquals(502, e.getStatus());
        assertFalse(e.getMessage().contains("null"), e.getMessage());
    }

    // ---------------------------------------------------------------- HTTP 层

    @Test
    void dropUnknownTableIs404Not502() throws Exception {
        when(doris.queryAs(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = "
                + "Unknown table 'gone' in audtze9k_db"))
                .when(doris).exec(any(), contains("drop table"));

        MvcResult result = mvc.perform(delete("/api/tables/audtze9k_db/gone")
                        .param("confirm", "gone").param("projectId", "1"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertEquals("表不存在: audtze9k_db.gone", message(result));
        // 失败审计仍然要留原文（S12 口径），只是响应体消毒了
        verify(sessions).audit(any(), org.mockito.ArgumentMatchers.eq("TABLE_DROP"),
                org.mockito.ArgumentMatchers.eq("audtze9k_db.gone"),
                org.mockito.ArgumentMatchers.argThat(text -> text != null
                        && text.contains("dropError=")), any(), any());
    }

    @Test
    void dropUnknownDatabaseIs404() throws Exception {
        when(doris.queryAs(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = "
                + "Unknown database 'ghost_db'"))
                .when(doris).exec(any(), contains("drop table"));

        MvcResult result = mvc.perform(delete("/api/tables/ghost_db/anytable")
                        .param("confirm", "anytable"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertEquals("库不存在: ghost_db", message(result));
    }

    /** 并发删除：后到者拿到 404（不是 502），也不需要为它单开一条分支。 */
    @Test
    void concurrentDropLoserIs404() throws Exception {
        when(doris.queryAs(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = "
                + "Unknown table 'race' in demo"))
                .when(doris).exec(any(), contains("drop table"));

        mvc.perform(delete("/api/tables/demo/race").param("confirm", "race"))
                .andExpect(status().isNotFound());
    }

    /**
     * SQL 模式：语法错必须归 400 且不带上游原文。
     *
     * <p>这里给的是**解析器能过、但 Doris 会拒**的 DDL（写个非法列类型），
     * 让异常真的从 {@code doris.exec} 回来 —— 这样测的是映射表本身。
     * 如果随手写一条括号不闭合的 SQL，会被 {@code TableSqlParser} 先 400 掉，
     * 用例看着绿，实际没碰到 Doris 那条通道（假测试）。</p>
     */
    @Test
    void sqlModeSyntaxErrorIs400AndLeaksNothing() throws Exception {
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = "
                + "no viable alternative at input 'decimal(3'"))
                .when(doris).exec(any(), anyString());
        String sql = "create table `demo`.`syntax_t` (`id` decimal(3)) engine=olap"
                + " duplicate key(`id`) distributed by hash(`id`) buckets 1"
                + " properties (\"replication_allocation\"=\"tag.location.default: 1\")";

        MvcResult result = mvc.perform(post("/api/tables/sql").contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                Map.of("projectId", 1, "database", "demo", "sql", sql))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertTrue(message(result).contains("SQL 语法或字段类型不正确"), message(result));
        assertFalse(raw(result).contains("no viable alternative"), "上游原文不许出现在响应: " + raw(result));
        assertFalse(raw(result).contains("detailMessage"), raw(result));
    }

    /** 解析器先拒的也算清楚：括号不闭合是 400，且同样不带内部格式。 */
    @Test
    void parserRejectedSqlStays400() throws Exception {
        MvcResult result = mvc.perform(post("/api/tables/sql").contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                Map.of("projectId", 1, "database", "demo",
                                        "sql", "create table `demo`.`t` (`id` decimal(3"))))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertTrue(message(result).contains("括号不匹配"), message(result));
        assertFalse(raw(result).contains("errCode"), raw(result));
        verify(doris, org.mockito.Mockito.never()).exec(any(), anyString());
    }

    @Test
    void unclassifiedFailureKeeps502ButSanitizesBody() throws Exception {
        when(doris.queryAs(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        doThrow(new ApiException(502, "Doris 执行失败（外部连接 doris-fe.dw-dev.svc:9030）: "
                + "errCode = 2, detailMessage = some BE fault"))
                .when(doris).exec(any(), contains("drop table"));

        MvcResult result = mvc.perform(delete("/api/tables/demo/t1").param("confirm", "t1"))
                .andExpect(status().isBadGateway())
                .andReturn();
        assertFalse(raw(result).contains("doris-fe.dw-dev"), "内部端点不许透出: " + raw(result));
        assertFalse(raw(result).contains("errCode"), raw(result));
    }

    @Test
    void createDatabaseFailureIsMappedToo() throws Exception {
        when(doris.databaseExists(any(), anyString())).thenReturn(false);
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = "
                + "Access denied; you need CREATE PRIVILEGE"))
                .when(doris).exec(any(), contains("create database"));

        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":1,\"database\":\"my_db\"}"))
                .andExpect(status().isBadGateway())
                .andReturn();
        assertFalse(raw(result).contains("errCode"), raw(result));
    }
}
