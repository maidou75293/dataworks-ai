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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

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
 * REQ-DW-6：表管理「附加库」。非默认租户的新库名不再一律 404——过命名规则
 * （业务英文名_后缀，docs/部门与项目建库-20261023.md 同族）就放行，并在
 * {@code platform.project_database}（V120）登记归属；跨租户名字仍 404（BUG-TC3-1
 * 口径不破，见 {@link TableCreateDatabaseTenantTest}）。
 *
 * <p>scope 用<b>真的</b> {@link TenantDataScope}（只 mock 它的 JdbcTemplate），
 * 和同族用例同一基座；不起 Spring、不连 Doris/PG。</p>
 */
class TableCreateExtraDatabaseTest {

    private DorisAdminService doris;
    private JdbcTemplate controllerJdbc;
    private JdbcTemplate scopeJdbc;
    private SessionService sessions;
    private MetadataReader metadata;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        metadata = mock(MetadataReader.class);
        controllerJdbc = mock(JdbcTemplate.class);
        // scope 的两条归属查询默认返回空列表 → 目标名一律走「附加库」新分支
        scopeJdbc = mock(JdbcTemplate.class);
        TenantDataScope scope = new TenantDataScope(scopeJdbc);
        TableController controller = new TableController(
                controllerJdbc, doris, mock(DorisQueryService.class), sessions,
                scope, mock(WarehouseResolver.class), metadata, mock(DataGuardService.class),
                mock(TableDdlService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        TenantScope.set("t2");
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    /** 让 platform.project 查得到业务英文名；null = 历史项目（行在、business 为空）。 */
    private void projectBusiness(String business) {
        List<Map<String, Object>> rows = business == null
                ? List.of(Map.of())                      // get("business") → null
                : List.of(Map.of("business", business));
        when(controllerJdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    private static String body(String database) {
        return "{\"projectId\":9,\"database\":\"" + database + "\"}";
    }

    private static String messageOf(MvcResult result) throws Exception {
        Map<?, ?> error = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                        Map.class);
        return String.valueOf(error.get("message"));
    }

    @Test
    void businessPrefixedExtraDatabaseIsCreatedAndRegistered() throws Exception {
        projectBusiness("crm");
        when(controllerJdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON).content(body("crm_report")))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"created\":true"));
        verify(doris).exec(any(), eq("create database if not exists `crm_report`"));
        // 登记行 (tenant, project, database) 落 platform.project_database
        verify(controllerJdbc).update(contains("platform.project_database"),
                eq("t2"), eq(9L), eq("crm_report"));
        verify(sessions).audit(any(), eq("TABLE_CREATE_DATABASE"), eq("crm_report"),
                contains("附加库登记"));
        verify(metadata).invalidateProject(9L);
    }

    @Test
    void extraDatabaseWithoutBusinessPrefixIsRejected() throws Exception {
        projectBusiness("crm");
        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON).content(body("ops_report")))
                .andExpect(status().isBadRequest())
                .andReturn();
        // 文案必须把规则本身讲出来（用户一眼知道该改成什么）
        assertTrue(messageOf(result).contains("crm_"), messageOf(result));
        verify(doris, never()).exec(any(), anyString());
        verify(controllerJdbc, never()).update(anyString(), any(), any(), any());
    }

    /** 前缀对但后缀为空（库名恰好等于 "crm_"）同样是坏名字。 */
    @Test
    void emptySuffixRejected() throws Exception {
        projectBusiness("crm");
        mvc.perform(post("/api/tables/databases").contentType(MediaType.APPLICATION_JSON)
                        .content(body("crm_")))
                .andExpect(status().isBadRequest());
        verify(doris, never()).exec(any(), anyString());
    }

    /** 跨租户占用中的名字：仍是 404 + 数据面统一文案，命名规则都不给提示（不泄露存在性）。 */
    @Test
    void extraDatabaseOwnedByOtherTenantStays404() throws Exception {
        // 两个绑定参数的项目归属查询 → byProject 命中，归属=default ≠ t2
        when(scopeJdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("default"));
        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON).content(body("crm_report")))
                .andExpect(status().isNotFound())
                .andReturn();
        assertTrue(messageOf(result).startsWith("库不存在: "), messageOf(result));
        verify(doris, never()).exec(any(), anyString());
        verify(controllerJdbc, never()).update(anyString(), any(), any(), any());
    }

    /** 历史项目没有 business：派生不出规则，明说是历史项目而不是含糊 404。 */
    @Test
    void legacyProjectWithoutBusinessCannotDeriveNaming() throws Exception {
        projectBusiness(null);
        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON).content(body("crm_report")))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertTrue(messageOf(result).contains("业务英文名"), messageOf(result));
        verify(doris, never()).exec(any(), anyString());
    }

    /** 不带 projectId：附加库无从派生命名，400（默认租户的老口径不受影响）。 */
    @Test
    void extraDatabaseRequiresProject() throws Exception {
        mvc.perform(post("/api/tables/databases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"database\":\"crm_report\"}"))
                .andExpect(status().isBadRequest());
        verify(doris, never()).exec(any(), anyString());
    }

    /** 并发撞名：唯一索引挡下第二条登记（影响 0 行）→ 409，不能谎报建库成功。 */
    @Test
    void registryNameTakenReturns409() throws Exception {
        projectBusiness("crm");
        when(controllerJdbc.update(anyString(), any(), any(), any())).thenReturn(0);
        MvcResult result = mvc.perform(post("/api/tables/databases")
                        .contentType(MediaType.APPLICATION_JSON).content(body("crm_report")))
                .andExpect(status().isConflict())
                .andReturn();
        assertTrue(messageOf(result).contains("库名已被占用"), messageOf(result));
        verify(metadata, never()).invalidateProject(any());
    }
}
