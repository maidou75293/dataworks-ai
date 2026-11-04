package com.lab.dataworks.ops;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.ops.OpsController.ActiveDeployment;
import com.lab.dataworks.ops.OpsController.OriginDeployment;
import com.lab.dataworks.ops.OpsController.RerunMode;
import com.lab.dataworks.ops.OpsController.RerunTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D26 回归：发布新版本后，旧版本实例仍要能重跑，且跑的必须是**它自己那一版**。
 *
 * <p>缺陷现场：{@code doPublishLocked()} 发布 v9 时删掉了同环境的旧 Prefect 部署，
 * 实例行上的 {@code dag_id} 成了死指针；重跑拿它 createRun，bridge 回 500，
 * {@code PrefectBridgeClient} 的兜底分支把它抬成 502「Prefect Bridge 连接失败」——
 * 上一版所有失败实例都重跑不了，错误文案还把排查方向带向 bridge/网络。</p>
 *
 * <p>落点决策（v2，纯函数）：生效部署原地重跑；被替换的部署按该实例所属版本的发布快照
 * 复用/重建无调度部署重跑（快照在 {@code workflow_version.model_json}，发布时冻结）；
 * 只有快照都不可用的早期历史数据才降级为「回退当前生效部署 + 明说」；
 * 降级也无可回退时 409；dag_id 对不上号的老实例原样试一次，再由调用方翻译错误。</p>
 *
 * <p>注意与 v1（270f8ee）的差别：v1 把被替换实例回退到「当前生效部署」，跑的是新版本
 * 代码，与「历史实例按发布快照执行」的预期不符；v2 起回退只留给无快照可寻的历史数据。</p>
 */
class OpsInstanceRerunDeploymentTest {

    private static final String OLD_DEP = "9cc5f6b6-old-deployment";
    private static final String NEW_DEP = "d8ab9df8-new-deployment";

    @Test
    void activeDeploymentIsRerunInPlace() {
        // 对照用例：部署还在生效（缺陷里的 31365），行为必须与修复前一致，不能莫名换部署
        RerunTarget target = OpsController.resolveRerunTarget(OLD_DEP,
                new OriginDeployment("DEV", 9, true), false, new ActiveDeployment(NEW_DEP, 10));
        assertEquals(RerunMode.IN_PLACE, target.mode());
        assertEquals(OLD_DEP, target.deploymentId());
        assertNull(target.note(), "原地重跑不该冒出「已回退」的说明");
        assertTrue(!target.dagUnknownToPlatform());
    }

    @Test
    void supersededDeploymentWithSnapshotRerunsItsOwnVersion() {
        // 缺陷复现场景：实例 31193 属于 DEV v7，DEV 已发布 v9（旧部署被删）。
        // 预期是「按发布快照执行，而不是依赖当前版本」→ 必须走快照路径，
        // 即使当前生效部署存在（v9）也不能悄悄拿 v9 的代码去跑 v7 实例。
        RerunTarget target = OpsController.resolveRerunTarget(OLD_DEP,
                new OriginDeployment("DEV", 7, false), true, new ActiveDeployment(NEW_DEP, 9));
        assertEquals(RerunMode.VIA_SNAPSHOT, target.mode(),
                "快照可用时必须按所属版本重跑，不能回退到当前生效部署");
        assertNull(target.note(), "落点还没解析出来，note 由重跑服务给出");
        assertTrue(!target.dagUnknownToPlatform());
    }

    @Test
    void offlineEnvironmentSnapshotRerunStillPossible() {
        // v1 在「环境已无生效部署」时直接 409；v2 只要快照在就该能重跑
        // （历史实例应**始终**可重跑；重建的部署无调度、不影响下线语义）。
        RerunTarget target = OpsController.resolveRerunTarget(OLD_DEP,
                new OriginDeployment("PROD", 3, false), true, null);
        assertEquals(RerunMode.VIA_SNAPSHOT, target.mode());
    }

    @Test
    void supersededWithoutSnapshotFallsBackToCurrentActiveOne() {
        // 降级路径：V41 之前的历史数据没有版本行可寻（snapshotUsable=false），
        // 只能回退当前生效部署，并且必须在 note 里说清「跑的是当前版」。
        RerunTarget target = OpsController.resolveRerunTarget(OLD_DEP,
                new OriginDeployment("DEV", 7, false), false, new ActiveDeployment(NEW_DEP, 9));
        assertEquals(RerunMode.FALLBACK_CURRENT, target.mode());
        assertEquals(NEW_DEP, target.deploymentId(), "无快照时回退到当前生效部署，而不是拿死指针去撞 502");
        assertTrue(!target.dagUnknownToPlatform());
        assertNotNull(target.note(), "换了部署必须告诉用户");
        assertTrue(target.note().contains("DEV v7"), target.note());
        assertTrue(target.note().contains("v9"), target.note());
        assertTrue(target.note().contains("已被新版本替换"), target.note());
        assertTrue(target.note().contains("当前生效部署"), target.note());
    }

    @Test
    void supersededWithoutSnapshotOrCurrentGivesActionableConflict() {
        // 无快照可重放、又无生效部署可回退：必须 4xx 说清下一步，而不是抬成 502
        ApiException e = assertThrows(ApiException.class,
                () -> OpsController.resolveRerunTarget(OLD_DEP,
                        new OriginDeployment("PROD", 3, false), false, null));
        assertEquals(409, e.getStatus(), "修复前是 502，且把根因说成 Bridge 连接失败");
        assertTrue(e.getMessage().contains("重新发布"), e.getMessage());
        assertTrue(e.getMessage().contains("PROD v3"), e.getMessage());
    }

    @Test
    void unknownDagIsTriedOnceAndFlaggedForErrorTranslation() {
        // V43 之前的历史实例 / 从 Prefect 侧手工起的 run：dag_id 在部署表里查不到
        RerunTarget target = OpsController.resolveRerunTarget(OLD_DEP, null, false, null);
        assertEquals(RerunMode.TRY_AS_IS, target.mode());
        assertEquals(OLD_DEP, target.deploymentId(), "查不到就保持原样，不能悄悄换成别的部署");
        assertTrue(target.dagUnknownToPlatform(), "要把「对不上号」告诉调用方，用于翻译 bridge 的 500");
        assertNull(target.note());
    }

    @Test
    void fallbackNoteDoesNotInventAnUnknownVersionNumber() {
        // 兼容回退（版本行指针）拿不到 version_no 时用 -1 表示未知，文案不能写成 v-1
        RerunTarget target = OpsController.resolveRerunTarget(OLD_DEP,
                new OriginDeployment("DEV", 7, false), false, new ActiveDeployment(NEW_DEP, -1));
        assertEquals(RerunMode.FALLBACK_CURRENT, target.mode());
        assertEquals(NEW_DEP, target.deploymentId());
        assertTrue(!target.note().contains("v-1"), target.note());
        assertTrue(target.note().contains("当前生效部署"), target.note());
    }
}
