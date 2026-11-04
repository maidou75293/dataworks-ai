package com.lab.dataworks.access;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC7-G5：审批链策略纯函数——校验（严格 400）、级号推导、应签池（升级并池、剔申请人）、
 * 或签/会签推进。落库/HTTP 面由部署后 e2e 覆盖（本泳道不部署，见卡 evidence）。
 */
class AccessApprovalPolicyTest {

    private static AccessController.PolicyBody body(String level, List<AccessController.StepBody> steps,
                                                    Integer timeout, List<String> escalateTo) {
        return new AccessController.PolicyBody(level, steps, timeout, escalateTo, null);
    }

    private static AccessController.StepBody step(String mode, String... approvers) {
        return new AccessController.StepBody(null, mode, List.of(approvers));
    }

    // ---------- canonicalize：非法输入必须显式 400，合法输入产出规范 JSON ----------

    @Test
    void rejectsIllegalShapesWithExplicitMessages() {
        ApiException e = assertThrows(ApiException.class, () -> ApprovalPolicy.canonicalize(
                body("L5", List.of(step("ANY", "a")), 0, List.of())));
        assertTrue(e.getMessage().contains("L1-L4"), e.getMessage());

        e = assertThrows(ApiException.class, () -> ApprovalPolicy.canonicalize(
                body("L4", List.of(step("ANY", "a"), step("ANY", "b"), step("ANY", "c")), 0, List.of())));
        assertTrue(e.getMessage().contains("最多 2 级"), e.getMessage());

        e = assertThrows(ApiException.class, () -> ApprovalPolicy.canonicalize(
                body("L4", List.of(step("MAJORITY", "a")), 0, List.of())));
        assertTrue(e.getMessage().contains("ANY(或签)/ALL(会签)"), e.getMessage());

        e = assertThrows(ApiException.class, () -> ApprovalPolicy.canonicalize(
                body("L4", List.of(step("ALL")), 0, List.of())));
        assertTrue(e.getMessage().contains("不能为空"), e.getMessage());

        // 超时升级不配升级池 = 静默无效配置，必须拒
        e = assertThrows(ApiException.class, () -> ApprovalPolicy.canonicalize(
                body("L4", List.of(step("ALL", "a")), 3, List.of())));
        assertTrue(e.getMessage().contains("escalateTo"), e.getMessage());

        e = assertThrows(ApiException.class, () -> ApprovalPolicy.canonicalize(
                body("L4", List.of(step("ANY", "a")), 400, List.of("boss"))));
        assertTrue(e.getMessage().contains("0-365"), e.getMessage());
    }

    @Test
    void canonicalDraftIsStableAndDeduplicated() {
        ApprovalPolicy.Draft d = ApprovalPolicy.canonicalize(body("l4",
                List.of(new AccessController.StepBody(" ", "any", List.of("alice", "alice", "bob"))),
                3, List.of("dept-head")));
        assertEquals("L4", d.dataLevel());
        assertEquals("[{\"name\":\"或签\",\"mode\":\"ANY\",\"approvers\":[\"alice\",\"bob\"]}]",
                d.stepsJson());
        assertEquals(3, d.timeoutDays());
        assertEquals("[\"dept-head\"]", d.escalateToJson());
    }

    // ---------- 级号推导 / 应签池 ----------

    @Test
    void stepDerivedFromExistingStatus() {
        assertEquals(0, ApprovalPolicy.stepOf("PENDING"));
        assertEquals(1, ApprovalPolicy.stepOf("PENDING_SECOND"));
        assertEquals(1, ApprovalPolicy.stepOf("pending_second"));
    }

    @Test
    void poolAddsEscalationAndDropsRequester() {
        ApprovalPolicy.Snapshot snap = ApprovalPolicy.parse(
                "{\"steps\":[{\"mode\":\"ALL\",\"approvers\":[\"alice\",\"boss\"]}],"
                        + "\"timeoutDays\":3,\"escalateTo\":[\"dept\",\"boss\"]}");
        // 未升级：只算策略池（去重）；升级后：并入 escalateTo（dept 可签）
        assertEquals(List.of("alice", "boss"),
                ApprovalPolicy.eligibleApprovers(snap, 0, false, "carol"));
        assertEquals(List.of("alice", "boss", "dept"),
                ApprovalPolicy.eligibleApprovers(snap, 0, true, "carol"));
        assertFalse(ApprovalPolicy.eligibleApprovers(snap, 0, true, "dept").contains("dept"));
        assertTrue(ApprovalPolicy.escalatedAt(0, 0));
        assertFalse(ApprovalPolicy.escalatedAt(1, 0));
        assertFalse(ApprovalPolicy.escalatedAt(null, 0));
    }

    @Test
    void brokenSnapshotFailsClosed() {
        assertThrows(ApiException.class, () -> ApprovalPolicy.parse("{oops"));
        assertThrows(ApiException.class, () -> ApprovalPolicy.parse(
                "{\"steps\":[{\"mode\":\"BOSS\",\"approvers\":[\"a\"]}]}"));
        assertThrows(ApiException.class, () -> ApprovalPolicy.parse("{\"steps\":[]}"));
    }

    // ---------- advance：或签/会签推进 ----------

    private static final ApprovalPolicy.Snapshot TWO_STEP = ApprovalPolicy.parse(
            "{\"steps\":["
                    + "{\"mode\":\"ALL\",\"approvers\":[\"sec\",\"biz\"]},"
                    + "{\"mode\":\"ANY\",\"approvers\":[\"admin1\",\"admin2\"]}],"
                    + "\"timeoutDays\":3,\"escalateTo\":[\"dept\"]}");

    @Test
    void counterSignWaitsUntilWholePoolSignedThenAdvances() {
        ApprovalPolicy.Outcome first = ApprovalPolicy.advance(
                TWO_STEP, true, 0, List.of(), "sec", false, "carol");
        assertEquals(ApprovalPolicy.WAIT, first.kind());
        assertEquals(List.of("sec"), first.signed());

        ApprovalPolicy.Outcome second = ApprovalPolicy.advance(
                TWO_STEP, true, 0, List.of("sec"), "biz", false, "carol");
        assertEquals(ApprovalPolicy.ADVANCED, second.kind());
        assertEquals(1, second.nextStep());
        assertEquals(List.of(), second.signed()); // 进下一级清空会签记录

        // 同一人不能在同一级会签里重复签
        assertThrows(ApiException.class, () -> ApprovalPolicy.advance(
                TWO_STEP, true, 0, List.of("sec"), "sec", false, "carol"));
    }

    @Test
    void orSignStepCompletesOnFirstSignatureAndChainEndApproves() {
        ApprovalPolicy.Outcome out = ApprovalPolicy.advance(
                TWO_STEP, true, 1, List.of(), "admin2", false, "carol");
        assertEquals(ApprovalPolicy.COMPLETED, out.kind());
        assertEquals(1, out.nextStep());

        ApprovalPolicy.Snapshot single = ApprovalPolicy.parse(
                "{\"steps\":[{\"mode\":\"ANY\",\"approvers\":[\"x\"]}],\"timeoutDays\":0,\"escalateTo\":[]}");
        assertEquals(ApprovalPolicy.COMPLETED,
                ApprovalPolicy.advance(single, true, 0, List.of(), "x", false, "u").kind());
    }

    @Test
    void escalationEnlargesCounterSignObligation() {
        // 升级后 dept 进入应签池：sec/biz 签完仍差 dept → WAIT；dept 补签才 ADVANCED
        ApprovalPolicy.Outcome before = ApprovalPolicy.advance(
                TWO_STEP, true, 0, List.of("sec"), "biz", true, "carol");
        assertEquals(ApprovalPolicy.WAIT, before.kind());
        ApprovalPolicy.Outcome after = ApprovalPolicy.advance(
                TWO_STEP, true, 0, List.of("sec", "biz"), "dept", true, "carol");
        assertEquals(ApprovalPolicy.ADVANCED, after.kind());
    }

    @Test
    void poolOnlyRequesterDoesNotDeadlockPlatformAdmin() {
        ApprovalPolicy.Snapshot snap = ApprovalPolicy.parse(
                "{\"steps\":[{\"mode\":\"ALL\",\"approvers\":[\"me\"]}],\"timeoutDays\":0,\"escalateTo\":[]}");
        // 应签池只剩申请人＝空集：有权签的人（如平台管理员）签一次即视为齐
        assertEquals(ApprovalPolicy.COMPLETED,
                ApprovalPolicy.advance(snap, true, 0, List.of(), "root", false, "me").kind());
    }

    @Test
    void rejectAtAnyLevelEndsRequest() {
        assertEquals("REJECTED", ApprovalPolicy.advance(
                TWO_STEP, false, 0, List.of("sec"), "biz", false, "carol").kind());
        assertEquals("REJECTED", ApprovalPolicy.advance(
                TWO_STEP, false, 1, List.of(), "admin1", false, "carol").kind());
    }

    @Test
    void signedListRoundTrips() {
        assertEquals(List.of(), ApprovalPolicy.signedList(null));
        assertEquals(List.of(), ApprovalPolicy.signedList(""));
        assertEquals(List.of("a", "b"), ApprovalPolicy.signedList("[\"a\",\"b\"]"));
        assertEquals("[\"a\",\"b\"]", ApprovalPolicy.signedJson(List.of("a", "b")));
    }
}
