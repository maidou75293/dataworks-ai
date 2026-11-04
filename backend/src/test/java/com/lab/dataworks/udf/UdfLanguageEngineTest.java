package com.lab.dataworks.udf;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.ProjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D59：Python 函数只能用于 Flink，Doris 侧一律拒绝（用户 2026-10-27 01:28 评审确认：
 * 不做 Doris 远程 Python UDF）。
 *
 * <p>三个入口都要挡：注册（create）、改配置（update）、发布（publish，含校验上线前
 * 留下的历史草稿）；被拒绝的发布尝试还要在审计里留一条 FAILED，
 * 否则「谁想发一个注定失败的函数」查不到。</p>
 */
class UdfLanguageEngineTest {

    private JdbcTemplate jdbc;
    private UdfService udf;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        udf = new UdfService(jdbc, mock(DorisAdminService.class), mock(ProjectStorageService.class),
                "build/udf-d59", "http://backend.test", "python3");
    }

    @AfterEach
    void tearDown() {
        com.lab.dataworks.auth.TenantScope.clear();
    }

    private static UdfService.FunctionRequest req(String engine, String language, String className) {
        return new UdfService.FunctionRequest("addudf", engine, "add_one", className,
                "BIGINT", List.of("BIGINT"), language);
    }

    /** 资源表里登记的语言（resolveLanguage 会先按这个校验请求里的 language）。 */
    private void givenResourceLanguage(String language) {
        when(jdbc.queryForList(contains("distinct language from platform.udf_resource"),
                eq(String.class), any(Object[].class))).thenReturn(List.of(language));
        when(jdbc.queryForObject(contains("from platform.project where id = ?"), eq(Long.class),
                any(Object[].class))).thenReturn(1L);
        // validateFunction 末尾还有一次「资源存在」二次校验（防并发删除）
        when(jdbc.queryForObject(contains("from platform.udf_resource"), eq(Integer.class),
                any(Object[].class))).thenReturn(1);
    }

    private void givenFunction(long id, String engine, String language, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("project_id", 5L);
        row.put("status", status);
        row.put("engine", engine);
        row.put("language", language);
        row.put("resource_name", "addudf");
        row.put("name", "add_one");
        row.put("full_name", "p5_add_one");
        when(jdbc.queryForList(contains("from platform.udf_function f"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    // ---------------------------------------------------------- 组合矩阵

    @Test
    void onlyPythonOnDorisIsRejected() {
        assertThrows(ApiException.class,
                () -> UdfService.requireEngineSupportsLanguage("DORIS", "python"));
        assertThrows(ApiException.class,
                () -> UdfService.requireEngineSupportsLanguage("doris", "python"), "大小写都要挡");
        // 其余三种组合必须放行，别把 Java+Flink、Python+Flink 一起误杀
        UdfService.requireEngineSupportsLanguage("FLINK", "python");
        UdfService.requireEngineSupportsLanguage("DORIS", "java");
        UdfService.requireEngineSupportsLanguage("FLINK", "java");
        UdfService.requireEngineSupportsLanguage(null, "java");
    }

    @Test
    void rejectionMessageTellsUserWhatToDoInstead() {
        ApiException error = assertThrows(ApiException.class,
                () -> UdfService.requireEngineSupportsLanguage("DORIS", "python"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("Python 函数仅支持 Flink 引擎"), error.getMessage());
        assertTrue(error.getMessage().contains("Java UDF") || error.getMessage().contains("jar"),
                "要说清替代方案: " + error.getMessage());
    }

    // ------------------------------------------------------------ 注册入口

    @Test
    void registeringPythonFunctionOnDorisIsRejected() {
        givenResourceLanguage("python");

        ApiException error = assertThrows(ApiException.class,
                () -> udf.createFunction(5, req("DORIS", "python", "my_mod.my_func"), "bob"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("Flink"), error.getMessage());
        verify(jdbc, never()).queryForObject(contains("insert into platform.udf_function"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void registeringPythonFunctionOnFlinkIsAllowed() {
        givenResourceLanguage("python");
        when(jdbc.queryForObject(contains("insert into platform.udf_function"), eq(Long.class),
                any(Object[].class))).thenReturn(31L);

        Map<String, Object> out = udf.createFunction(5, req("FLINK", "python", "my_mod.my_func"), "bob");

        assertEquals(31L, out.get("id"));
        assertEquals("python", out.get("language"));
    }

    @Test
    void registeringJavaFunctionOnDorisIsAllowed() {
        givenResourceLanguage("java");
        when(jdbc.queryForObject(contains("insert into platform.udf_function"), eq(Long.class),
                any(Object[].class))).thenReturn(32L);

        Map<String, Object> out = udf.createFunction(5, req("DORIS", "java", "com.example.AddUdf"), "bob");

        assertEquals(32L, out.get("id"));
        assertEquals("java", out.get("language"));
    }

    // -------------------------------------------------------- 修改配置入口

    @Test
    void switchingDraftToPythonOnDorisIsRejected() {
        givenResourceLanguage("python");
        givenFunction(9L, "FLINK", "python", "DRAFT");

        ApiException error = assertThrows(ApiException.class,
                () -> udf.updateFunction(9L, req("DORIS", "python", "my_mod.my_func")));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("Flink"), error.getMessage());
        verify(jdbc, never()).update(contains("update platform.udf_function"), any(Object[].class));
    }

    // ------------------------------------------------------------ 发布入口

    /** 校验上线前留下的「Python + Doris」草稿，发布时仍要挡住，且不改状态。 */
    @Test
    void publishingLegacyPythonDorisDraftIsRejected() {
        givenFunction(9L, "DORIS", "python", "DRAFT");

        ApiException error = assertThrows(ApiException.class, () -> udf.publish(9L, "bob"));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("Python 函数仅支持 Flink 引擎"), error.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // ---------------------------------------------------- 被拒发布要留 FAILED 审计

    @Test
    void rejectedPublishLeavesFailedAuditRow() {
        JdbcTemplate auditJdbc = mock(JdbcTemplate.class);
        SessionService sessions = new SessionService(auditJdbc, 12);
        when(auditJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(auditJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        UdfService service = mock(UdfService.class);
        doThrow(new ApiException(400, UdfService.PYTHON_DORIS_MESSAGE))
                .when(service).publish(9L, "bob");
        UdfController controller = new UdfController(service, mock(UdfFolderService.class),
                mock(JdbcTemplate.class), sessions);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "bob");
        http.setRemoteAddr("10.99.99.20");

        assertThrows(ApiException.class, () -> controller.publish(9L, http));

        ArgumentCaptor<Object[]> pending = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).queryForObject(contains("insert into platform.audit_log"),
                eq(Long.class), pending.capture());
        List<Object> first = java.util.Arrays.asList(pending.getValue());
        assertEquals("UDF_FUNCTION_PUBLISH", first.get(1));
        assertEquals("f9", first.get(2));
        assertEquals("PENDING", first.get(4), "先记后做：拒绝前就得有 PENDING");

        ArgumentCaptor<Object[]> settled = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbc).update(contains("update platform.audit_log"), settled.capture());
        assertEquals("FAILED", java.util.Arrays.asList(settled.getValue()).get(0));
        assertTrue(String.valueOf(java.util.Arrays.asList(settled.getValue()).get(1))
                        .contains("Python 函数仅支持 Flink"),
                "被拒原因要能直接对账: " + settled.getValue()[1]);
    }
}
