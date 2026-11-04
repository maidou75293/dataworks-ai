package com.lab.dataworks.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D28 回归：重复发布同一版本必须幂等，返回同一个 {@code deploymentId}。
 *
 * <p>缺陷现场（docs/功能缺陷清单-20261026.md D28）：
 * {@code POST /api/workflows/257/versions/7/publish {"env":"DEV"}} 连发两次，
 * 分别返回 e8713962-… 与 8efd5925-…，而第一个部署在第二次调用里已被删除。
 * 连点 / 重试 / 脚本重跑的调用方手里都可能拿着一个死 id，线上还有「先删后建」的短空窗。</p>
 *
 * <p>修复后的判定：该环境唯一生效部署就是这一版 + 渲染出的 flow 资产没变 +
 * 调度自那次发布以来没改过 → 复用原部署。三个条件任一不满足才走重建，
 * 重建时把被替换的旧 id 一并回带（{@code replacedDeploymentId}）。</p>
 */
class WorkflowPublishIdempotentTest {

    private static final String FILE = "wf257_demo_wf_v7_dev.py";
    private static final String FLOW = "demo_wf_dev";
    private static final String OLD_DEPLOYMENT = "e8713962-0000-0000-0000-000000000000";

    private static Map<String, Object> row(int versionNo, String deploymentId,
                                           String filename, String flowName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("version_no", versionNo);
        row.put("prefect_deployment_id", deploymentId);
        row.put("prefect_flow_file", filename);
        row.put("prefect_flow_name", flowName);
        return row;
    }

    private static List<Map<String, Object>> active(Map<String, Object>... rows) {
        return new ArrayList<>(List.of(rows));
    }

    @Test
    void republishingTheSameVersionReusesTheSameDeployment() {
        // 缺陷原文：v7 连发两次拿到两个 id，第一次的那个已被删。修复后两次都回同一个 id
        Map<String, Object> hit = WorkflowController.pickReusableDeployment(
                active(row(7, OLD_DEPLOYMENT, FILE, FLOW)), 7, FILE, FLOW, false);
        assertNotNull(hit, "同版本 + 资产一致 + 调度未变，必须幂等复用");
        assertEquals(OLD_DEPLOYMENT, hit.get("prefect_deployment_id"), "deploymentId 不能变");
    }

    @Test
    void publishingANewVersionIsNotIdempotent() {
        // 发 v9 时该环境生效的是 v7：正常走「先建新再删旧」，不能被幂等短路
        assertNull(WorkflowController.pickReusableDeployment(
                active(row(7, OLD_DEPLOYMENT, FILE, FLOW)), 9,
                "wf257_demo_wf_v9_dev.py", FLOW, false));
    }

    @Test
    void scheduleEditedAfterPublishForcesARealRepublish() {
        // 关键护栏：PUT /schedule 只写库，返回里写着「调度变更后需重新发布才会在 Prefect 生效」。
        // 这种情况必须真重建，否则用户改完 cron 重发同版本会「成功但没生效」。
        List<Map<String, Object>> previous = active(row(7, OLD_DEPLOYMENT, FILE, FLOW));
        assertNull(WorkflowController.pickReusableDeployment(previous, 7, FILE, FLOW, true),
                "调度在发布之后改过，必须重建部署");
        assertNotNull(WorkflowController.pickReusableDeployment(previous, 7, FILE, FLOW, false),
                "调度没改过才允许幂等");
    }

    @Test
    void renamedWorkflowMustBeRebuilt() {
        // 改工作流编码会改掉 filename / flowName：资产对不上就不能复用，
        // 否则线上继续挂着旧名字的 flow，平台以为已经换过来了
        List<Map<String, Object>> previous = active(row(7, OLD_DEPLOYMENT, FILE, FLOW));
        assertNull(WorkflowController.pickReusableDeployment(
                previous, 7, "wf257_renamed_wf_v7_dev.py", FLOW, false), "filename 变了要重建");
        assertNull(WorkflowController.pickReusableDeployment(
                previous, 7, FILE, "renamed_wf_dev", false), "flowName 变了要重建");
    }

    @Test
    void rowWithoutDeploymentPointerIsNotReusable() {
        // V41 之前的发布历史行没有部署指针，拿它当「已部署」会让触发撞 404
        assertNull(WorkflowController.pickReusableDeployment(
                active(row(7, null, FILE, FLOW)), 7, FILE, FLOW, false));
        assertNull(WorkflowController.pickReusableDeployment(
                active(row(7, "  ", FILE, FLOW)), 7, FILE, FLOW, false));
    }

    @Test
    void zeroOrMultipleActiveRowsFallBackToNormalPublish() {
        // 0 条 = 这个环境还没发布过；多条 = 生效部署数据异常，都按正常发布流程处理
        assertNull(WorkflowController.pickReusableDeployment(active(), 7, FILE, FLOW, false));
        assertNull(WorkflowController.pickReusableDeployment(active(
                row(7, OLD_DEPLOYMENT, FILE, FLOW),
                row(7, "other-deployment", FILE, FLOW)), 7, FILE, FLOW, false));
    }

    @Test
    void versionColumnMissingIsNeverTreatedAsSameVersion() {
        Map<String, Object> legacy = row(7, OLD_DEPLOYMENT, FILE, FLOW);
        legacy.put("version_no", null);
        assertNull(WorkflowController.pickReusableDeployment(active(legacy), 7, FILE, FLOW, false));
    }

    @Test
    void sameVersionRowLookupIsSharedByBothCallers() {
        // 查库那一步先用同一份判定筛出候选行，再叠加「调度有没有改」，两处口径不能各写一遍
        List<Map<String, Object>> previous = active(row(7, OLD_DEPLOYMENT, FILE, FLOW));
        assertEquals(previous.get(0),
                WorkflowController.sameVersionDeployedRow(previous, 7, FILE, FLOW));
        assertNull(WorkflowController.sameVersionDeployedRow(previous, 8, FILE, FLOW));
        assertTrue(previous.size() == 1);
    }
}
