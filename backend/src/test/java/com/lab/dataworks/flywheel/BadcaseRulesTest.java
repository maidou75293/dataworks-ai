package com.lab.dataworks.flywheel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * REQ-FW-3①采集规则纯逻辑单测：三条低质规则 + 优先级 + 阈值可调（默认不做死）。
 */
class BadcaseRulesTest {

    @Test
    void noAnswerWinsEvenWhenOtherSignalsPresent() {
        // result_rows=0 且同时点踩、低置信度：只归 NO_ANSWER（客观缺失优先于人工/自评信号）
        assertEquals(BadcaseSignal.NO_ANSWER, BadcaseRules.match(0, "DOWN", 0.1, 0.6));
    }

    @Test
    void downvoteIsCaseAndSpaceInsensitive() {
        assertEquals(BadcaseSignal.DISLIKE, BadcaseRules.match(10, "down", null, 0.6));
        assertEquals(BadcaseSignal.DISLIKE, BadcaseRules.match(10, " DOWN ", null, 0.6));
        assertEquals(BadcaseSignal.DISLIKE, BadcaseRules.match(10, "Down", 0.9, 0.6)); // 点踩优先于高置信
    }

    @Test
    void lowConfidenceOnlyWhenReportedAndBelowThreshold() {
        assertEquals(BadcaseSignal.LOW_CONFIDENCE, BadcaseRules.match(5, "UP", 0.42, 0.6));
        assertNull(BadcaseRules.match(5, null, null, 0.6), "未上报置信度不参与判定");
        assertNull(BadcaseRules.match(5, null, 0.6, 0.6), "恰好压线不算低置信");
        assertNull(BadcaseRules.match(5, "UP", 0.9, 0.6));
    }

    @Test
    void thresholdIsParameterNotHardcoded() {
        // sys_config 把阈值从 0.6 调到 0.5：同一事件判定结果必须跟着变（卡面「默认不做死」）
        assertEquals(BadcaseSignal.LOW_CONFIDENCE, BadcaseRules.match(5, null, 0.55, 0.6));
        assertNull(BadcaseRules.match(5, null, 0.55, 0.5));
    }

    @Test
    void feedbackVocabulary() {
        assertTrue(BadcaseRules.knownFeedback(null));
        assertTrue(BadcaseRules.knownFeedback("DOWN"));
        assertTrue(BadcaseRules.knownFeedback("up "));
        assertFalse(BadcaseRules.knownFeedback("BAD"));
    }
}
