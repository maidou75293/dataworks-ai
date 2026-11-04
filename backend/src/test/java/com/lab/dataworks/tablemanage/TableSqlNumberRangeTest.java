package com.lab.dataworks.tablemanage;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.security.DataGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D14：SQL 模式 buckets / replication_allocation 的数值越界必须 400，不再 500。
 *
 * <p>缺陷现场：{@code buckets 2147483648} / {@code tag.location.default:
 * 99999999999999999999} 走 {@code Integer.parseInt} 抛未捕获 NumberFormatException，
 * {@code /api/tables/sql/preview} 与 {@code /api/tables/sql} 都回 500
 * 「服务器内部错误」。用户输入的越界数值是请求问题：400 + 明确合法范围，
 * preview 与 execute 两条链路都要挡。</p>
 *
 * <p>边界口径：buckets 合法 1–{@value TableSqlParser#MAX_BUCKETS}（工程上限，
 * 溢出走同一条 400），表单范围仍 1-64（65–4096 保持旧的 unsupported + 夹紧语义，
 * 不能因为补 500 把老行为改没）；副本数合法 1–{@value TableSqlParser#MAX_REPLICATION}。</p>
 */
class TableSqlNumberRangeTest {

    private static final String OK_TAIL = " properties (\"replication_allocation\""
            + "=\"tag.location.default: 1\")";

    private static String sqlWithBuckets(String buckets) {
        return "create table `db1`.`t` (`id` bigint not null) engine=olap "
                + "duplicate key(`id`) distributed by hash(`id`) buckets " + buckets + OK_TAIL;
    }

    private static String sqlWithReplication(String value) {
        return "create table `db1`.`t` (`id` bigint not null) engine=olap "
                + "duplicate key(`id`) distributed by hash(`id`) buckets 8 "
                + "properties (\"replication_allocation\"=\"tag.location.default: " + value + "\")";
    }

    private static String sqlWithReplicationNum(String value) {
        return "create table `db1`.`t` (`id` bigint not null) engine=olap "
                + "duplicate key(`id`) distributed by hash(`id`) buckets 8 "
                + "properties (\"replication_num\"=\"" + value + "\")";
    }

    private static ApiException badRequest(String sql) {
        ApiException e = assertThrows(ApiException.class,
                () -> TableSqlParser.parse("db1", sql, null));
        assertEquals(400, e.getStatus(), e.getMessage());
        return e;
    }

    // ------------------------------------------------------------ buckets

    @Test
    void bucketsOverflowIsBadRequestWithRangeInMessage() {
        // 缺陷复现的三个值：int 溢出、long 都装不下、非数字由正则挡到「未写」分支（不是 500）
        for (String raw : List.of("2147483648", "99999999999999999999", "4097")) {
            ApiException e = badRequest(sqlWithBuckets(raw));
            assertTrue(e.getMessage().contains("分桶数 buckets"), e.getMessage());
            assertTrue(e.getMessage().contains("1-4096"),
                    "文案必须给出合法范围，收到: " + raw + " 时回：" + e.getMessage());
            assertTrue(e.getMessage().contains(raw), "文案要回显收到的值: " + e.getMessage());
        }
    }

    @Test
    void bucketsNonPositiveIsBadRequest() {
        // Doris buckets 为正整数：0 / 负数以前被静默当「未写 BUCKETS」或直接下发，现在明确 400
        for (String raw : List.of("0", "-1", "-99999999999999999999")) {
            ApiException e = badRequest(sqlWithBuckets(raw));
            assertTrue(e.getMessage().contains("正整数"), e.getMessage());
        }
    }

    @Test
    void bucketsWithinHardLimitKeepLegacySoftClamp() {
        // 65–4096：不是越界，维持旧口径（200 + 「超出表单范围 1-64」unsupported + 模型夹紧），
        // 保证 SQL 模式照常执行原样 DDL
        TableSqlParser.Result result = TableSqlParser.parse("db1", sqlWithBuckets("100"), null);
        assertEquals("t", result.table());
        assertTrue(result.unsupported().stream().anyMatch(s -> s.contains("超出表单范围 1-64")),
                String.valueOf(result.unsupported()));
        assertEquals(Integer.valueOf(64), result.model().buckets());
        // 边界值 1 与 4096 都必须放行
        assertEquals(Integer.valueOf(1), TableSqlParser.parse("db1", sqlWithBuckets("1"), null)
                .model().buckets());
        assertTrue(TableSqlParser.parse("db1", sqlWithBuckets("4096"), null)
                .unsupported().stream().anyMatch(s -> s.contains("4096")));
    }

    // ------------------------------------------------------------ replication

    @Test
    void replicationAllocationOverflowIsBadRequest() {
        for (String raw : List.of("99999999999999999999", "2147483648", "100", "0")) {
            ApiException e = badRequest(sqlWithReplication(raw));
            assertTrue(e.getMessage().contains("副本数 replication_allocation"), e.getMessage());
            assertTrue(e.getMessage().contains("1-99"),
                    "文案必须给出合法范围，收到: " + raw + " 时回：" + e.getMessage());
        }
    }

    @Test
    void replicationNumOverflowIsBadRequestButNonNumericStaysSoft() {
        // 与 replication_allocation 同口径：数字但越界/溢出 → 400
        ApiException e = badRequest(sqlWithReplicationNum("99999999999999999999"));
        assertTrue(e.getMessage().contains("replication_num"), e.getMessage());
        assertTrue(e.getMessage().contains("1-99"), e.getMessage());
        // 非数字仍是旧的 unsupported（表单不认这个属性值，但语句本身不执行，不该 500 也不该硬拒）
        TableSqlParser.Result result = TableSqlParser.parse("db1",
                sqlWithReplicationNum("abc"), null);
        assertTrue(result.unsupported().stream().anyMatch(s -> s.contains("不是整数")),
                String.valueOf(result.unsupported()));
    }

    @Test
    void replicationWithinHardLimitKeepsLegacySoftClamp() {
        // 11–99：旧口径是 unsupported + 模型夹紧到 10，不能因为 D14 改成 400
        TableSqlParser.Result result = TableSqlParser.parse("db1", sqlWithReplication("50"), null);
        assertEquals(Integer.valueOf(10), result.model().replication());
        assertTrue(result.unsupported().stream().anyMatch(s -> s.contains("超出表单范围 1-10")),
                String.valueOf(result.unsupported()));
        // 合法值不受影响
        assertEquals(Integer.valueOf(3),
                TableSqlParser.parse("db1", sqlWithReplication("3"), null).model().replication());
    }

    // ------------------------------------------------------------ 两条 HTTP 链路

    private DorisAdminService doris;
    private com.lab.dataworks.auth.SessionService sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(com.lab.dataworks.auth.SessionService.class);
        // BUG-TC4-1：建表/SQL 建表/删表现在先问生产库闸门；本类只验数字范围，一律按 DEV 放行
        TableDdlService ddlGate = mock(TableDdlService.class);
        when(ddlGate.gateProductionDdl(anyString(), any(), anyString(), anyString(),
                anyString(), any(), any()))
                .thenReturn(new TableDdlService.ProductionGate("DEV", null));
        TableController controller = new TableController(
                mock(org.springframework.jdbc.core.JdbcTemplate.class), doris,
                mock(DorisQueryService.class), sessions,
                mock(com.lab.dataworks.common.TenantDataScope.class),
                mock(WarehouseResolver.class), mock(MetadataReader.class),
                mock(DataGuardService.class), ddlGate);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void previewEndpointReturns400ForHugeBuckets() throws Exception {
        // 缺陷现场：以前回 500 {"message":"服务器内部错误"}；现在 400 + 合法范围
        mvc.perform(post("/api/tables/sql/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"database\":\"db1\",\"sql\":\""
                                + sqlWithBuckets("2147483648").replace("\"", "\\\"") + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("1-4096")));
        verifyNoInteractions(doris);
    }

    @Test
    void executeEndpointRejectsHugeReplicationBeforeDoris() throws Exception {
        mvc.perform(post("/api/tables/sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"database\":\"db1\",\"sql\":\""
                                + sqlWithReplication("99999999999999999999")
                                .replace("\"", "\\\"") + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("1-99")));
        // 越界数值不能到得了 Doris：parse 在 exec 之前挡住
        verifyNoInteractions(doris);
    }

    @Test
    void legalNumbersStillGoThroughBothEndpoints() throws Exception {
        String sql = sqlWithBuckets("8").replace("\"", "\\\"");
        mvc.perform(post("/api/tables/sql/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"database\":\"db1\",\"sql\":\"" + sql + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").value("t"));

        mvc.perform(post("/api/tables/sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"database\":\"db1\",\"sql\":\"" + sql + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        verify(doris).exec(any(), anyString());
        verify(sessions).audit(any(), anyString(), anyString(), anyString());
    }
}
