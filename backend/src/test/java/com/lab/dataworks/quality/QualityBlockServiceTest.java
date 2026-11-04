package com.lab.dataworks.quality;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC7-G2：强规则失败阻断跨工作流下游调度。
 *
 * <p>覆盖的口径：只有 STRONG + 当天最后一条 passed=false + exec_status=OK 才阻断；
 * 下游按血缘做传递闭包；产出该表的工作流自己不算下游。</p>
 */
class QualityBlockServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-27");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final QualityBlockService service = new QualityBlockService(jdbc);

    private static Map<String, Object> rule(String code, String table, String severity,
                                            Boolean passed, String execStatus) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("code", code);
        row.put("name", code + " 名称");
        row.put("table_name", table);
        row.put("severity", severity);
        row.put("passed", passed);
        row.put("exec_status", execStatus);
        return row;
    }

    private static Map<String, Object> edge(long workflowId, String code, String source, String target) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workflow_id", workflowId);
        row.put("workflow_code", code);
        row.put("workflow_name", code + " 名称");
        row.put("source_table", source);
        row.put("target_table", target);
        return row;
    }

    private void stub(List<Map<String, Object>> rules, List<Map<String, Object>> edges) {
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of(1L));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("lineage_edge") ? edges : rules;
        });
    }

    /** ODS(规则表) → DWD → DWS 三段血缘：下游是读 ODS/DWD/DWS 的其它工作流。 */
    private static List<Map<String, Object>> threeHopEdges() {
        return List.of(
                edge(1L, "wf_ods", "raw_orders", "ods_orders"),
                edge(2L, "wf_dwd", "ods_orders", "dwd_orders"),
                edge(3L, "wf_dws", "dwd_orders", "dws_orders"));
    }

    @Test
    void strongRuleFailureBlocksEveryDownstreamWorkflow() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", false, "OK")), threeHopEdges());

        List<QualityBlockService.Blocker> blockers = service.forProject(7L, "DEV", DAY);

        assertEquals(List.of("wf_dwd", "wf_dws"),
                blockers.stream().map(QualityBlockService.Blocker::workflowCode).toList());
    }

    @Test
    void producingWorkflowIsNotBlocked() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", false, "OK")), threeHopEdges());

        List<QualityBlockService.Blocker> blockers = service.forProject(7L, "DEV", DAY);

        assertTrue(blockers.stream().noneMatch(b -> "wf_ods".equals(b.workflowCode())),
                "自己产出这张表的工作流不是下游，重跑它是修复手段");
    }

    @Test
    void blockerCarriesProducerAndReadableMessage() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", false, "OK")), threeHopEdges());

        QualityBlockService.Blocker blocker = service.forProject(7L, "DEV", DAY).get(0);

        assertEquals("q_orders", blocker.ruleCode());
        assertEquals("ods_orders", blocker.tableName());
        assertEquals("wf_ods", blocker.upstreamWorkflowCode());
        assertEquals(1L, blocker.upstreamWorkflowId());
        assertEquals("2026-10-27", blocker.bizDate());
        String message = blocker.message();
        assertTrue(message.contains("q_orders") && message.contains("ods_orders")
                        && message.contains("2026-10-27") && message.contains("wf_dwd"),
                "运行实例里要能直接看出哪条规则、哪张表、挡了谁: " + message);
    }

    @Test
    void weakRuleNeverBlocks() {
        stub(List.of(rule("q_orders", "ods_orders", "WEAK", false, "OK")), threeHopEdges());

        assertTrue(service.forProject(7L, "DEV", DAY).isEmpty());
    }

    @Test
    void missingSeverityIsTreatedAsWeak() {
        stub(List.of(rule("q_orders", "ods_orders", null, false, "OK")), threeHopEdges());

        assertTrue(service.forProject(7L, "DEV", DAY).isEmpty(),
                "存量规则没有 severity，升级后不能突然停掉别人的链路");
    }

    @Test
    void executionErrorIsNotADataFailure() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", false, "ERROR")), threeHopEdges());

        assertTrue(service.forProject(7L, "DEV", DAY).isEmpty(),
                "规则没跑起来不等于数据不达标，不能据此阻断下游");
    }

    @Test
    void notRunTodayIsUnknownNotFailure() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", null, null)), threeHopEdges());

        assertTrue(service.forProject(7L, "DEV", DAY).isEmpty());
    }

    @Test
    void passedRuleDoesNotBlock() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", true, "OK")), threeHopEdges());

        assertTrue(service.forProject(7L, "DEV", DAY).isEmpty());
    }

    @Test
    void tableNameSuffixMatchingHandlesQualifiedNames() {
        assertTrue(QualityBlockService.sameTable("ods_orders", "doris.dw.ods_orders"));
        assertTrue(QualityBlockService.sameTable("DW.ods_orders", "ods_orders"));
        assertTrue(QualityBlockService.sameTable("ods_orders", " ODS_ORDERS "));
        assertFalse(QualityBlockService.sameTable("ods_orders", "dws_orders"));
        assertFalse(QualityBlockService.sameTable("ods_orders", ""));
    }

    @Test
    void reachableFromFollowsChainsAndIgnoresOtherBranches() {
        List<QualityBlockService.Edge> edges = List.of(
                new QualityBlockService.Edge(1L, "wf_ods", "wf_ods", "raw_orders", "ods_orders"),
                new QualityBlockService.Edge(2L, "wf_dwd", "wf_dwd", "ods_orders", "dwd_orders"),
                new QualityBlockService.Edge(9L, "wf_other", "wf_other", "ads_x", "ads_y"));

        var reached = QualityBlockService.reachableFrom("ods_orders", edges);

        assertTrue(reached.contains("ods_orders"));
        assertTrue(reached.contains("dwd_orders"));
        assertFalse(reached.contains("ads_y"), "无关分支不能被算成下游");
    }

    @Test
    void forWorkflowOnlyReturnsItsOwnBlockers() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", false, "OK")), threeHopEdges());

        List<QualityBlockService.Blocker> blockers = service.forWorkflow(2L, "DEV", DAY);

        assertEquals(1, blockers.size());
        assertEquals("wf_dwd", blockers.get(0).workflowCode());
    }

    @Test
    void forWorkflowWithoutAnyBlockIsEmpty() {
        stub(List.of(rule("q_orders", "ods_orders", "STRONG", false, "OK")), threeHopEdges());

        assertTrue(service.forWorkflow(1L, "DEV", DAY).isEmpty());
    }

    @Test
    void unknownWorkflowIs404NotSilentPass() {
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class,
                () -> service.forWorkflow(404L, "DEV", DAY));
        assertEquals(404, error.getStatus());
    }

    @Test
    void sqlArgsKeepDateBeforeProject() {
        stub(List.of(), List.of());

        service.forProject(7L, "DEV", DAY);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).queryForList(anyString(), args.capture());
        Object[] values = args.getAllValues().get(0);
        assertEquals(DAY, values[0], "RULES_SQL 第一个参数必须是业务日期");
        assertEquals(7L, values[1], "RULES_SQL 第二个参数必须是项目");
    }

    @Test
    void invalidEnvIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.forProject(7L, "STAGING", DAY));
        assertEquals(400, error.getStatus());
    }
}
