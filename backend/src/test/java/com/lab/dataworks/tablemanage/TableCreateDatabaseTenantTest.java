package com.lab.dataworks.tablemanage;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.security.DataGuardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-TC3-1（TC-3 现网用例 G2-6）：跨租户建库不能再回 403 + 回显库名。
 *
 * <p>缺陷现场（v539 实测）：第二租户管理员对 <b>default 租户项目的库名</b> 发
 * {@code POST /api/tables/databases}，拿到
 * {@code 403 {"message":"无权操作该库: bug_a_db1_037935_dev"}}——状态码本身与平台口径
 * （docs/byid-tenant-audit.md「不存在与跨租户统一 404」）不一致，更要紧的是
 * 「403 + 把库名原样说出来」等于向跨租户调用方<b>确认该库真实存在且归属他人</b>。</p>
 *
 * <p>同一个 {@code TableController} 的数据面（列表/结构/预览/建表/删表）都走
 * {@code scope.requireDatabase()}，所以这里锁两件事：</p>
 * <ol>
 *   <li>建库与数据面的状态码与文案<b>完全一致</b>（不是各写一份 404）；</li>
 *   <li>改成 404 之后<b>没有把正向建库挡掉</b>——本租户项目登记的库仍 200。</li>
 * </ol>
 *
 * <p>口径用<b>真的</b> {@link TenantDataScope}（只 mock 它的 JdbcTemplate）：
 * 若把 scope 也 mock 掉，{@code requireDatabase()} 默认什么都不做，
 * 这条用例就变成空检查（比没有检查更糟）。不起 Spring、不连 Doris/PG。</p>
 */
class TableCreateDatabaseTenantTest {

    /** 当前租户（t2）项目已登记的库。 */
    private static final String OWN_DB = "bug_a_db2_037935_dev";
    /** default 租户项目的库——跨租户目标，就是 TC-3 里那条 G2-6。 */
    private static final String OTHER_DB = "bug_a_db1_037935_dev";

    private DorisAdminService doris;
    private SessionService sessions;
    private MetadataReader metadata;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        metadata = mock(MetadataReader.class);
        // 真 scope：ownedDatabases() 读 platform.project 的 dev/prd 库，这里只替掉这一条 SQL
        JdbcTemplate scopeJdbc = mock(JdbcTemplate.class);
        when(scopeJdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of(OWN_DB));
        TenantDataScope scope = new TenantDataScope(scopeJdbc);
        // CONFLICT-WM1：合流后 TableController 多了第 9 个依赖（BUG-TC4-1 生产闸）。
        // 本卡只打 /databases 建库入口，不触达闸，mock 空实例即可。
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class), sessions,
                scope, mock(WarehouseResolver.class), metadata, mock(DataGuardService.class),
                mock(TableDdlService.class));
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        TenantScope.set("buga_t2_037935");
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    private static String createDatabaseBody(String database) {
        return "{\"projectId\":173,\"database\":\"" + database + "\"}";
    }

    @Test
    void crossTenantCreateDatabaseIsNotFound() throws Exception {
        mvc.perform(post("/api/tables/databases").contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody(OTHER_DB)))
                .andExpect(status().isNotFound());
    }

    /**
     * 存在性泄露的判据不是「状态码等于 404」这一条：403 换 404 但文案写「库属于他人」
     * 一样是泄露。所以锁死文案里不许出现「无权/归属/其他租户」这类确认存在性的措辞。
     */
    @Test
    void crossTenantResponseDoesNotConfirmTheDatabaseExists() throws Exception {
        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody(OTHER_DB)))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("库不存在"), "沿用数据面统一文案: " + body);
        assertFalse(body.contains("无权"), "不能再是「无权操作该库」: " + body);
        assertFalse(body.contains("归属"), "不能透露归属: " + body);
        assertFalse(body.contains("其他租户") || body.contains("其它租户")
                || body.contains("跨租户"), "不能确认该库属于他人: " + body);
    }

    /**
     * 口径一致 = 同一条库名，建库（写面）与表列表（数据面）的状态码与 message 一字不差。
     * 这是「只有 createDatabase 这一条没复用 requireDatabase」的直接反证。
     * 错误体里的 timestamp 每次都变，所以只比 status/message 两个字段。
     */
    @Test
    void createDatabaseAndDataPlaneShareTheExactSameVerdict() throws Exception {
        MvcResult create = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody(OTHER_DB)))
                .andReturn();
        MvcResult listing = mvc.perform(get("/api/tables").param("database", OTHER_DB)).andReturn();

        assertEquals(404, create.getResponse().getStatus(), "建库应 404");
        assertEquals(statusOf(listing), statusOf(create),
                "建库与数据面状态码必须一致（G2-3/4/5/9 已是 404）");
        assertEquals(messageOf(listing), messageOf(create), "错误文案口径必须完全一致");
        assertEquals("库不存在: " + OTHER_DB, messageOf(create), "对照数据面原文案: "
                + listing.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void unknownDatabaseIsAlsoNotFoundAndNothingIsExecuted() throws Exception {
        // 没有任何归属证据的库名：非默认租户同样不能建，状态码与跨租户一致（都 404）
        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody("bug_a_never_registered")))
                .andExpect(status().isNotFound())
                .andReturn();
        assertTrue(messageOf(result).startsWith("库不存在: "), messageOf(result));

        verify(doris, never()).exec(any(), anyString());
        verify(doris, never()).databaseExists(any(), anyString());
        verify(sessions, never()).audit(any(), anyString(), any(), any());
        verify(metadata, never()).invalidateProject(any());
    }

    @Test
    void ownProjectDatabaseIsStillCreated() throws Exception {
        when(doris.databaseExists(any(), eq(OWN_DB))).thenReturn(false);

        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody(OWN_DB)))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("\"created\":true"),
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        verify(doris).exec(any(), eq("create database if not exists `" + OWN_DB + "`"));
        verify(sessions).audit(eq("unknown"), eq("TABLE_CREATE_DATABASE"), eq(OWN_DB), eq("created"));
        verify(metadata).invalidateProject(173L);
    }

    @Test
    void existingOwnDatabaseIsReusedNotRecreated() throws Exception {
        when(doris.databaseExists(any(), eq(OWN_DB))).thenReturn(true);

        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody(OWN_DB)))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("\"created\":false"),
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        verify(doris, never()).exec(any(), anyString());
        verify(sessions).audit(eq("unknown"), eq("TABLE_CREATE_DATABASE"), eq(OWN_DB),
                eq("already exists"));
    }

    /** 平台默认租户不受库名归属限制（改口径不能顺手把管理员挡在门外）。 */
    @Test
    void defaultTenantCanCreateAnyDatabase() throws Exception {
        TenantScope.clear();   // current() 回落到 default

        mvc.perform(post("/api/tables/databases").contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody(OTHER_DB)))
                .andExpect(status().isOk());

        verify(doris).exec(any(), eq("create database if not exists `" + OTHER_DB + "`"));
    }

    /** 非法库名仍是 400，不该被新的 404 口径吃掉（顺序：先验参数，再判归属）。 */
    @Test
    void illegalNameStaysBadRequest() throws Exception {
        mvc.perform(post("/api/tables/databases").contentType(MediaType.APPLICATION_JSON)
                        .content(createDatabaseBody("1-bad-name")))
                .andExpect(status().isBadRequest());
    }

    /** 错误体形如 {"timestamp":…,"status":404,"message":"库不存在: x"}（GlobalExceptionHandler）。 */
    private static Map<String, Object> errorBody(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), Map.class);
    }

    private static int statusOf(MvcResult result) throws Exception {
        Object status = errorBody(result).get("status");
        return status instanceof Number number ? number.intValue() : -1;
    }

    private static String messageOf(MvcResult result) throws Exception {
        return String.valueOf(errorBody(result).get("message"));
    }
}
