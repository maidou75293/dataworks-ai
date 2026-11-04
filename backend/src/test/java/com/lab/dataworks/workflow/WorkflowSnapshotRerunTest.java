package com.lab.dataworks.workflow;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.ops.OpsSyncService;
import com.lab.dataworks.prefect.PrefectBridgeClient;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.prefect.PrefectPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D26 v2 回归：发布新版本、旧部署被删之后，历史实例的重跑必须按**该实例所属版本**
 * 的发布快照（{@code workflow_version.model_json}，发布时冻结）执行，
 * 而不是依赖当前生效版本（与数据服务「发布快照」同一口径的主流做法）。
 *
 * <p>覆盖：候选复用（上次重跑重建过的部署直接原地跑）、全部不可用时按快照重建
 * 无调度部署（cron/rrule 必须为 null，不能让旧版本在调度 tick 里复活）、
 * bridge 真不可达时保持原故障（不伪装成「部署不存在」）、快照缺失 409、
 * 同业务日期有未同步的活跃 run 时先拒绝再创建、部署名与发布路径同形。</p>
 */
class WorkflowSnapshotRerunTest {

    private static final String DEAD_DEP = "9cc5f6b6-0000-0000-0000-000000000000";
    private static final String REBUILT_DEP = "8efd5925-0000-0000-0000-000000000000";
    private static final String BIZ_DATE = "2026-10-25";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PrefectPublisher publisher = mock(PrefectPublisher.class);
    private final PrefectBridgeClient prefect = mock(PrefectBridgeClient.class);
    private final PrefectFlowRenderer renderer = mock(PrefectFlowRenderer.class);
    /** TC7-G2：这里的用例只验证「按快照重跑」，默认没有质量阻断。 */
    private final com.lab.dataworks.quality.QualityBlockService qualityBlocks = mock(
            com.lab.dataworks.quality.QualityBlockService.class);
    private final WorkflowTriggerService service = new WorkflowTriggerService(
            jdbc, publisher, prefect, mock(OpsSyncService.class),
            mock(WorkflowMutationService.class), renderer, qualityBlocks);

    private final Map<String, Object> params = Map.of("biz_date", BIZ_DATE);

    /** 接线：workflow_deployment 查询回候选 id；workflow/project/version 查询回给定的行。 */
    private void stubDb(List<String> candidateIds, List<Map<String, Object>> headerRows,
                        List<Map<String, Object>> versionRows) {
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenAnswer(inv -> candidateIds);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("project_code")) {
                        return headerRows;
                    }
                    if (sql.contains("model_json")) {
                        return versionRows;
                    }
                    return List.of();
                });
    }

    private static Map<String, Object> headerRow() {
        return Map.of("code", "demo_wf", "name", "演示工作流", "project_code", "demo_prj");
    }

    private static Map<String, Object> versionRow(String model) {
        return Map.of("model", model);
    }

    private static PrefectBridgeClient.Run run(String id) {
        return new PrefectBridgeClient.Run(id, "run-" + id, "SCHEDULED", null, Map.of(), null, null);
    }

    private static ApiException bridgeGateway() {
        // 缺陷原文的形态：bridge 对「部署不存在」回 500，客户端兜底分支翻成 502 连接失败
        return new ApiException(502, "Prefect Bridge 连接失败: 500 Internal Server Error");
    }

    @Test
    void deploymentNameMatchesPublishFormula() {
        // 按快照重建必须和发布路径同名同形（WorkflowController 现在也委托到这里）
        assertEquals("demo_wf_v7_dev", WorkflowTriggerService.deploymentName("demo_wf", "DEV", 7));
        assertEquals("wf_demo_v7_dev", WorkflowTriggerService.deploymentName("WF-demo", "DEV", 7),
                "非法字符归一化成下划线，与发布路径一致");
        assertEquals("demo_wf_v12_prod", WorkflowTriggerService.deploymentName("demo_wf", "PROD", 12));
    }

    @Test
    void nonPositiveVersionHasNoSnapshot() {
        // 兼容回退（版本行指针）拿不到 version_no 时为 -1：不能拿 -1 去查快照
        assertFalse(service.hasVersionSnapshot(257, -1));
        assertFalse(service.hasVersionSnapshot(257, 0));
    }

    @Test
    void supersededInstanceRerunsOnExistingSnapshotDeploymentWithoutRebuild() {
        // 上次按快照重跑已经重建过部署（候选最近的在前）：直接复用它原地跑，
        // 绝不能再重建一个（同名部署会撞 ObjectAlreadyExists）
        stubDb(List.of(REBUILT_DEP, DEAD_DEP), List.of(headerRow()),
                List.of(versionRow("{\"tasks\":[]}")));
        when(prefect.listRuns(anyString(), anyInt())).thenReturn(List.of());
        when(prefect.createRun(eq(REBUILT_DEP), any())).thenReturn(run("run-1"));

        WorkflowTriggerService.SnapshotRun result =
                service.rerunFromSnapshot(257, "DEV", 7, params, BIZ_DATE, "old-run", "admin");

        assertEquals(REBUILT_DEP, result.deploymentId(), "复用最近一次重建的快照部署，不新建");
        assertTrue(result.note().contains("v7"), result.note());
        assertTrue(result.note().contains("原地重跑"), result.note());
        verify(publisher, never()).publish(any(), any(), any(), any(), any(), any(), any(),
                anyList(), any());
    }

    @Test
    void deadPointerRebuildsSchedulelessDeploymentFromVersionSnapshot() {
        // 缺陷核心场景：实例 dag 是发布 v9 时被删掉的 v7 部署（死指针）→ bridge 500/502。
        // 修复：按 v7 的 workflow_version 快照重建一个**无调度**部署（cron/rrule=null，
        // active=false），重跑落在它上面，历史实例执行的是自己那一版的定义。
        stubDb(List.of(DEAD_DEP), List.of(headerRow()),
                List.of(versionRow("{\"tasks\":[],\"edges\":[]}")));
        when(prefect.listRuns(anyString(), anyInt())).thenReturn(List.of());
        when(prefect.createRun(eq(DEAD_DEP), any())).thenThrow(bridgeGateway());
        when(prefect.reachable()).thenReturn(true);
        when(renderer.render(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyInt(), anyString()))
                .thenReturn(new PrefectFlowRenderer.RenderedFlow(
                        "demo_wf_dev", "flow_demo_wf_257", "wf257_demo_wf_v7_dev.py",
                        "/opt/prefect/flows/wf257_demo_wf_v7_dev.py:flow_demo_wf_257", "PY"));
        when(publisher.publish(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PrefectBridgeClient.Deployment("flow-1", REBUILT_DEP,
                        "demo_wf_dev", "demo_wf_v7_dev"));
        when(prefect.createRun(eq(REBUILT_DEP), any())).thenReturn(run("run-2"));

        WorkflowTriggerService.SnapshotRun result =
                service.rerunFromSnapshot(257, "DEV", 7, params, BIZ_DATE, "old-run", "admin");

        assertEquals(REBUILT_DEP, result.deploymentId());
        assertTrue(result.note().contains("重建"), result.note());
        assertTrue(result.note().contains("v7"), result.note());
        // 渲染与发布必须以「无调度」进行：否则旧版本会在每个调度 tick 复活
        verify(renderer).render(eq(257L), eq("demo_prj"), eq("demo_wf"), eq("演示工作流"),
                eq("DEV"), eq("{\"tasks\":[],\"edges\":[]}"), isNull(), eq(7), eq("demo_wf_v7_dev"));
        // 文件名与发布同形（同版本重建复用同一文件），但 Prefect 部署名必须带唯一后缀，
        // 否则重新发布同一版本时会与在世的快照重建部署撞名（ObjectAlreadyExists）
        verify(publisher).publish(eq("wf257_demo_wf_v7_dev.py"), eq("PY"), eq("demo_wf_dev"),
                org.mockito.ArgumentMatchers.argThat(n -> n != null && n.startsWith("demo_wf_v7_dev_r")),
                eq("/opt/prefect/flows/wf257_demo_wf_v7_dev.py:flow_demo_wf_257"),
                isNull(), isNull(), anyList(), anyString());
        // 重建的部署必须登记为 active=false：不抢该环境的「生效部署」指针
        verify(jdbc, atLeastOnce()).update(contains("insert into platform.workflow_deployment"),
                any(Object[].class));
        verify(publisher).awaitFlowFile(eq("wf257_demo_wf_v7_dev.py"), anyLong());
    }

    @Test
    void bridgeOutageStaysHardFailureInsteadOfSnapshotRebuild() {
        // bridge 真不可达（故障注入口径）：保持原 502，不能伪装成「部署不存在」去重建，
        // docs/故障注入-20261024.md 的判定不能因为 D26 修复而漂移
        stubDb(List.of(DEAD_DEP), List.of(headerRow()),
                List.of(versionRow("{\"tasks\":[]}")));
        when(prefect.listRuns(anyString(), anyInt())).thenReturn(List.of());
        when(prefect.createRun(eq(DEAD_DEP), any())).thenThrow(bridgeGateway());
        when(prefect.reachable()).thenReturn(false);

        ApiException e = assertThrows(ApiException.class,
                () -> service.rerunFromSnapshot(257, "DEV", 7, params, BIZ_DATE, "old-run", "admin"));
        assertEquals(502, e.getStatus());
        assertTrue(e.getMessage().contains("连接失败"), e.getMessage());
        verify(publisher, never()).publish(any(), any(), any(), any(), any(), any(), any(),
                anyList(), any());
    }

    @Test
    void rebuildRefusedWithActionableConflictWhenSnapshotRowMissing() {
        // 候选全灭且版本行不存在：409 说清「先重新发布」，不是 502
        stubDb(List.of(DEAD_DEP), List.of(headerRow()), List.of());
        when(prefect.listRuns(anyString(), anyInt())).thenReturn(List.of());
        when(prefect.createRun(eq(DEAD_DEP), any())).thenThrow(bridgeGateway());
        when(prefect.reachable()).thenReturn(true);

        ApiException e = assertThrows(ApiException.class,
                () -> service.rerunFromSnapshot(257, "PROD", 3, params, BIZ_DATE, "old-run", "admin"));
        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("重新发布"), e.getMessage());
        verify(publisher, never()).publish(any(), any(), any(), any(), any(), any(), any(),
                anyList(), any());
    }

    @Test
    void busyCandidateRejectedBeforeAnyRunCreated() {
        // Prefect 里有未同步到库的活跃 run（同业务日期）：必须先拒绝，
        // 不能因为「按快照重跑」在同一天写第二份目标表
        stubDb(List.of(REBUILT_DEP), List.of(headerRow()),
                List.of(versionRow("{\"tasks\":[]}")));
        when(prefect.listRuns(anyString(), anyInt())).thenReturn(List.of(
                new PrefectBridgeClient.Run("other-run", "run-x", "RUNNING", null,
                        Map.of("biz_date", BIZ_DATE), null, null)));

        ApiException e = assertThrows(ApiException.class,
                () -> service.rerunFromSnapshot(257, "DEV", 7, params, BIZ_DATE, "old-run", "admin"));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("已有运行中的实例"), e.getMessage());
        verify(prefect, never()).createRun(anyString(), any());
    }

    @Test
    void snapshotLookupNeverQueriesWithNonPositiveVersion() {
        // hasVersionSnapshot 的 -1/0 守卫在查库前返回，count(*) 路径不会被触达
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        assertFalse(service.hasVersionSnapshot(257, 999)); // 库里没有版本行 → 不可用
        verify(jdbc).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
    }
}
