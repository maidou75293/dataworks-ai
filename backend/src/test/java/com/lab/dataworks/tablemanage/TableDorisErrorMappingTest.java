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
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D58：表管理这条链路上，Doris 报错必须按语义映射，且原文只进审计/日志。
 *
 * <p>卡面复现原文：{@code DELETE /api/tables/bug_a_env_1793035231/t_1793035231}
 * → 502 {@code {"message":"Doris 执行失败: errCode = 2, detailMessage = Unknown table ..."}}。
 * 表不存在是调用方能自己改对的问题，502 让前端只会显示「服务异常」，
 * 原文还把内部库名与 SQL 片段暴露出去。</p>
 */
class TableDorisErrorMappingTest {

    private DorisAdminService doris;
    private SessionService sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        // 表清单接口会先过 MetadataReader，这里让它直接走「回退到直连 Doris」的 supplier，
        // 这样被测的正是 tablesDirect 里的错误映射
        MetadataReader metadata = mock(MetadataReader.class);
        when(metadata.tables(any(), anyString(), any())).thenAnswer(inv -> {
            java.util.function.Supplier<java.util.List<java.util.Map<String, Object>>> fallback =
                    inv.getArgument(2);
            return new MetadataReader.Read<>(fallback.get(), MetadataReader.SOURCE_DORIS, false, false);
        });
        // BUG-TC4-1：删表现在先问生产库闸门；这些用例只验审计/错误映射，一律按 DEV 落点放行
        TableDdlService ddlGate = mock(TableDdlService.class);
        when(ddlGate.gateProductionDdl(anyString(), any(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(new TableDdlService.ProductionGate("DEV", null));
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class), sessions,
                mock(TenantDataScope.class), mock(WarehouseResolver.class),
                metadata, mock(DataGuardService.class), ddlGate);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 删表失败时：状态码按语义、响应不带原文、审计里仍能看到原文。 */
    private String dropFailingWith(String rawError, int expectedStatus) throws Exception {
        doThrow(new ApiException(502, rawError)).when(doris).exec(any(), anyString());

        MvcResult result = mvc.perform(delete("/api/tables/bug_a_env/t_x").param("confirm", "t_x"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(any(), eq("TABLE_DROP"), eq("bug_a_env.t_x"), detail.capture(),
                eq("FAILED"), any());
        assertTrue(detail.getValue().contains("dropError="),
                "失败审计要留原文以便排查: " + detail.getValue());
        return body;
    }

    @Test
    void droppingUnknownTableIs404WithReadableMessage() throws Exception {
        String body = dropFailingWith(
                "Doris 执行失败: errCode = 2, detailMessage = Unknown table 't_x' in bug_a_env", 404);

        assertTrue(body.contains("表不存在: bug_a_env.t_x"), body);
        assertTrue(!body.contains("errCode") && !body.contains("detailMessage"),
                "响应不能回显数据库原文: " + body);
    }

    @Test
    void syntaxErrorIs400() throws Exception {
        String body = dropFailingWith(
                "Doris 执行失败: errCode = 2, detailMessage = err_parse. Syntax error in line 1", 400);

        assertTrue(body.contains("SQL"), body);
        assertTrue(!body.contains("err_parse"), body);
    }

    @Test
    void permissionErrorIs403() throws Exception {
        String body = dropFailingWith(
                "Doris 执行失败: errAccess, Access denied for user 'reader'@'10.42.0.9'", 403);

        assertTrue(body.contains("权限"), body);
        assertTrue(!body.contains("reader") && !body.contains("10.42.0.9"),
                "账号与内网地址不能进响应: " + body);
    }

    /** 集群真不可达仍然是 502（语义没变），只是不再把驱动原文带出去。 */
    @Test
    void unreachableClusterStays502WithoutRawText() throws Exception {
        String body = dropFailingWith(
                "Doris 执行失败: CommunicationsException: Communications link failure, "
                        + "connection refused to 10.99.99.11:9030", 502);

        assertTrue(body.contains("连不上"), body);
        assertTrue(!body.contains("Communications") && !body.contains("10.99.99.11"), body);
    }

    /** 表不存在是 4xx：不该被当成「平台不可用」让调用方重试。 */
    @Test
    void statusIsNot502ForClientFixableFailures() throws Exception {
        for (String raw : new String[]{
                "Doris 执行失败: errCode = 2, detailMessage = Unknown table 't_x' in bug_a_env",
                "Doris 执行失败: err_parse. Syntax error",
                "Doris 执行失败: Access denied for user 'x'@'y'",
                "Doris 执行失败: Table already exists"}) {
            doThrow(new ApiException(502, raw)).when(doris).exec(any(), anyString());
            MvcResult result = mvc.perform(delete("/api/tables/bug_a_env/t_x").param("confirm", "t_x"))
                    .andReturn();
            int status = result.getResponse().getStatus();
            assertTrue(status >= 400 && status < 500,
                    "客户端能改对的错误必须是 4xx，实际 " + status + " / " + raw);
        }
    }

    /** 查表清单时库不存在 → 404（原来只认两种写法，其余 502 带原文）。 */
    @Test
    void unknownDatabaseOnTableListIs404() throws Exception {
        doThrow(new ApiException(502,
                "Doris 查询失败: errCode = 2, detailMessage = Unknown database 'ghost_db'"))
                .when(doris).queryAs(any(), contains("show tables from"));

        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/tables")
                        .param("database", "ghost_db").param("direct", "true"))
                .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(status == 404 && body.contains("库不存在: ghost_db"), status + " " + body);
    }

}

