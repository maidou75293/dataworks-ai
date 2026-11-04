package com.lab.dataworks.workflow;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.ops.OpsSyncService;
import com.lab.dataworks.prefect.PrefectBridgeClient;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.prefect.PrefectPublisher;
import com.lab.dataworks.quality.QualityBlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC7-G2：手工触发/补数走同一个入口，上游强规则没过时必须在 createRun 之前拒绝，
 * 否则会先起一个必然失败的 run（用户重试还会撞「已有活跃实例」）。
 */
class WorkflowTriggerQualityBlockTest {

    private static final String BIZ_DATE = "2026-10-27";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PrefectPublisher publisher = mock(PrefectPublisher.class);
    private final PrefectBridgeClient prefect = mock(PrefectBridgeClient.class);
    private final WorkflowMutationService mutations = mock(WorkflowMutationService.class);
    private final QualityBlockService blocks = mock(QualityBlockService.class);
    private final WorkflowTriggerService service = new WorkflowTriggerService(
            jdbc, publisher, prefect, mock(OpsSyncService.class), mutations,
            mock(PrefectFlowRenderer.class), blocks);

    @BeforeEach
    void wire() {
        // withLock 在测试里直接执行
        when(mutations.withLock(anyLong(), any())).thenAnswer(inv -> {
            java.util.function.Supplier<?> action = inv.getArgument(1);
            return action.get();
        });
        when(mutations.activeRun(anyLong(), anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(null);
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("prefect_deployment_id", "dep-1");
        deployment.put("prefect_flow_file", "wf1_dev.py");
        deployment.put("published_ms", 1L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(deployment));
    }

    private static QualityBlockService.Blocker blocker(String ruleCode, String table, long workflowId) {
        return new QualityBlockService.Blocker(workflowId, "wf_dwd", "下游工作流",
                ruleCode, ruleCode + " 名称", table, BIZ_DATE, 1L, "wf_ods", "上游工作流");
    }

    @Test
    void blockedTriggerIsRejectedBeforeCreatingRun() {
        when(blocks.forWorkflow(eq(42L), eq("DEV"), eq(LocalDate.parse(BIZ_DATE))))
                .thenReturn(List.of(blocker("q_orders", "ods_orders", 42L)));

        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(42L, "DEV", BIZ_DATE, Map.of(), "manual", "u_bug_t1"));

        assertEquals(409, error.getStatus());
        assertTrue(error.getMessage().contains("上游强规则未通过"), error.getMessage());
        assertTrue(error.getMessage().contains("q_orders"), "要带上规则编码: " + error.getMessage());
        verify(prefect, never()).createRun(anyString(), any());
    }

    @Test
    void malformedBizDateIs400NotServerError() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(42L, "DEV", "2026/10/27", Map.of(), "manual", "u_bug_t1"));

        assertEquals(400, error.getStatus());
        verify(prefect, never()).createRun(anyString(), any());
    }
}
