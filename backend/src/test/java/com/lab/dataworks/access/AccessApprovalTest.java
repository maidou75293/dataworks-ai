package com.lab.dataworks.access;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 申请审批状态机（4b）：L4 双人审批、同一人不能连批两次、普通资源单审。
 */
class AccessApprovalTest {

    @Test
    void l4RequiresTwoDifferentApprovers() {
        AccessController.ApprovalStep first =
                AccessController.approvalStep("APPROVED", "PENDING", "L4", "alice", null);
        assertEquals("PENDING_SECOND", first.status());
        assertEquals("APPROVE_1", first.event());
        assertFalse(first.setsExpiry());

        // 一审人不能自己完成二审
        assertThrows(ApiException.class, () -> AccessController.approvalStep(
                "APPROVED", "PENDING_SECOND", "L4", "alice", "alice"));

        AccessController.ApprovalStep second =
                AccessController.approvalStep("APPROVED", "PENDING_SECOND", "L4", "bob", "alice");
        assertEquals("APPROVED", second.status());
        assertEquals("APPROVE_2", second.event());
        assertTrue(second.setsExpiry());
        assertEquals(30, second.grantDays());

        assertThrows(ApiException.class, () -> AccessController.approvalStep(
                "APPROVED", "PENDING_SECOND", "L4", "bob", "bob"));
    }

    @Test
    void nonL4NeedsSingleApproval() {
        AccessController.ApprovalStep step =
                AccessController.approvalStep("APPROVED", "PENDING", "L3", "alice", null);
        assertEquals("APPROVED", step.status());
        assertEquals("APPROVE", step.event());
        assertTrue(step.setsExpiry());
        assertEquals(90, step.grantDays());
    }

    @Test
    void rejectFromEitherStageEndsTheRequest() {
        AccessController.ApprovalStep first =
                AccessController.approvalStep("REJECTED", "PENDING", "L3", "alice", null);
        assertEquals("REJECTED", first.status());
        assertEquals("REJECT", first.event());

        AccessController.ApprovalStep second =
                AccessController.approvalStep("REJECTED", "PENDING_SECOND", "L4", "bob", "alice");
        assertEquals("REJECTED", second.status());
        assertEquals("REJECT", second.event());

        // 一审人同样不能自己驳回二审（避免一人走完两道门）
        assertThrows(ApiException.class, () -> AccessController.approvalStep(
                "REJECTED", "PENDING_SECOND", "L4", "alice", "alice"));
    }

    @Test
    void alreadyDecidedRequestsAreRejected() {
        assertThrows(ApiException.class, () -> AccessController.approvalStep(
                "APPROVED", "APPROVED", "L3", "alice", null));
        assertThrows(ApiException.class, () -> AccessController.approvalStep(
                "APPROVED", "REJECTED", "L3", "alice", null));
    }
}
