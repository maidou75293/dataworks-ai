package com.lab.dataworks.flywheel;

/**
 * 采集规则纯逻辑（REQ-FW-3①）。不起 Spring、不连库，阈值由调用方从
 * {@link FlywheelSettings} 读入后传入——便于单测把「默认不做死」钉住。
 *
 * <p>优先级（一行事件只归一个信号，取最先命中）：无答案 &gt; 点踩 &gt; 低置信度。
 * 理由：0 行是客观缺失，点踩是显式人工负反馈，置信度只是模型自评。</p>
 */
final class BadcaseRules {

    /** 点踩的取值字面量（与 V180 注释的 feedback 词汇表一致）。 */
    static final String FEEDBACK_DOWN = "DOWN";

    private BadcaseRules() {
    }

    /**
     * @param resultRows 事件结果行数（usage_query_event.result_rows）
     * @param feedback   人工反馈（'DOWN'/'UP'/null；大小写与首尾空白不敏感）
     * @param confidence 模型自评置信度（可空=未上报，不参与判定）
     * @param threshold  低置信度阈值（sys_config 可调）
     * @return 命中的信号；都不命中返回 null（正常样本，不入池）
     */
    static BadcaseSignal match(long resultRows, String feedback, Double confidence, double threshold) {
        if (resultRows <= 0) {
            return BadcaseSignal.NO_ANSWER;
        }
        if (isDownvote(feedback)) {
            return BadcaseSignal.DISLIKE;
        }
        if (confidence != null && confidence < threshold) {
            return BadcaseSignal.LOW_CONFIDENCE;
        }
        return null;
    }

    /** 反馈词表校验用：除 DOWN/UP/空 外视为非法（采集侧跳过并计数，不静默吞）。 */
    static boolean knownFeedback(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return true;
        }
        String v = feedback.trim();
        return v.equalsIgnoreCase(FEEDBACK_DOWN) || v.equalsIgnoreCase("UP");
    }

    private static boolean isDownvote(String feedback) {
        return feedback != null && FEEDBACK_DOWN.equalsIgnoreCase(feedback.trim());
    }
}
