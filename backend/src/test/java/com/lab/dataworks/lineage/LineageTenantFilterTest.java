package com.lab.dataworks.lineage;

import com.lab.dataworks.auth.TenantScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D08 回归：血缘接口不得泄漏其他租户的工作流清单与版本号。
 *
 * <p>同文件的 edges/tables 主查询从基线起就带 {@code e.tenant_id}；泄漏点在三处
 * 子查询/清单：{@code /api/lineage} 的 versions（workflowId 分支与 focus 分支）、
 * {@code /api/lineage/tables} 的 workflows 清单。本测试不连数据库，直接钉住
 * 发给 JDBC 的 SQL：凡是从 {@code platform.lineage_edge} 取数的语句都必须带
 * tenant 条件，且参数里绑定了当前租户。</p>
 */
class LineageTenantFilterTest {

    private static final String TENANT = "bugp2t";

    /** 每次 JDBC 查询记一条：[SQL, 拍平后的绑定参数]。 */
    private final List<String> sqls = new ArrayList<>();
    private final List<List<Object>> boundArgs = new ArrayList<>();

    private JdbcTemplate jdbc;
    private LineageController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        controller = new LineageController(jdbc);
        // 两个重载都走同一个收集器（versions 查询是 (String, Class, Object...) 版）
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(inv -> collect(inv));
        when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> collect(inv));
        TenantScope.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    private List<Map<String, Object>> collect(InvocationOnMock inv) {
        Object[] raw = inv.getArguments();
        sqls.add(String.valueOf(raw[0]));
        List<Object> flat = new ArrayList<>();
        for (int i = 1; i < raw.length; i++) {
            if (raw[i] instanceof Object[] array) {
                flat.addAll(Arrays.asList(array));   // varargs 原样传数组时
            } else {
                flat.add(raw[i]);                     // Mockito 已拍平时
            }
        }
        boundArgs.add(flat);
        return List.of();
    }

    /** 断言：每条 lineage_edge 查询都带租户条件且绑定当前租户。 */
    private void assertAllEdgeQueriesTenantScoped() {
        boolean any = false;
        for (int i = 0; i < sqls.size(); i++) {
            if (!sqls.get(i).contains("platform.lineage_edge")) {
                continue;
            }
            any = true;
            assertTrue(sqls.get(i).contains("tenant_id"),
                    "lineage_edge 查询必须按租户过滤: " + sqls.get(i));
            assertTrue(boundArgs.get(i).contains(TENANT),
                    "参数必须绑定当前租户: " + boundArgs.get(i) + " SQL=" + sqls.get(i));
        }
        assertTrue(any, "应至少发出一条 lineage_edge 查询");
    }

    @Test
    void listVersionsAreTenantScoped() {
        controller.list(2L, null, null, null, null, null, 3, "DEV", false, 500);
        assertAllEdgeQueriesTenantScoped();
        assertTrue(sqls.stream().anyMatch(s -> s.contains("distinct version_no")),
                "workflowId 分支应发出 versions 查询（D08 泄漏点）");
    }

    @Test
    void focusVersionsAreTenantScoped() {
        controller.list(null, null, null, null, null, "doris.demo_retail.t1",
                3, "DEV", false, 500);
        assertAllEdgeQueriesTenantScoped();
        List<String> versionQueries = sqls.stream()
                .filter(s -> s.contains("distinct version_no")).toList();
        assertTrue(!versionQueries.isEmpty(), "focus 分支应发出 versions 查询（D08 泄漏点）");
        for (String statement : versionQueries) {
            assertTrue(statement.contains("tenant_id"),
                    "按表聚焦的 versions 必须按租户过滤: " + statement);
        }
    }

    @Test
    void tablesWorkflowListIsTenantScoped() {
        controller.tables(null, null, null, "DEV", false);
        assertAllEdgeQueriesTenantScoped();
        assertTrue(sqls.stream().anyMatch(s -> s.contains("select distinct w.id")),
                "workflows 清单查询应被发出且已收口（D08 泄漏点）");
    }

    @Test
    void collectorSeesEveryQuery() {
        // 防呆：若 stub 没接住调用，sqls 会是空的，上面的断言就全变成「找不到泄漏点」假绿
        controller.list(null, null, null, null, null, null, 3, "DEV", false, 500);
        assertEquals(1, sqls.size(), "无 workflowId/无 focus 时只发主查询");
        Map<String, Object> out = controller.list(null, null, null, null, null, null,
                3, "DEV", false, 500);
        assertEquals(List.of(), out.get("edges"));
    }
}
