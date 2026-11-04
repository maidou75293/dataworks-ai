package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.lab.dataworks.common.ApiException;

import java.util.Locale;

/**
 * 数据开发节点的脚本引擎。
 *
 * <p>节点模型是 JSON，老数据里没有 {@code engine} 字段（只有 {@code datasource:'DORIS'} 标记），
 * 一律按 DORIS 处理，保证已有工作流行为不变。新模型由前端写入
 * {@code engine}（DORIS / SPARK / FLINK），非法值直接 400，避免静默跑错引擎。</p>
 */
public enum ScriptEngine {

    /** 项目数仓（Doris）；默认值。 */
    DORIS,
    /** 平台常驻 Spark Thrift Server（HiveServer2 协议），同步返回结果。 */
    SPARK,
    /** Flink SQL Gateway，流式异步提交。 */
    FLINK;

    public static final ScriptEngine DEFAULT = DORIS;

    /** 从任务 JSON 读 engine：缺失/空串 → DORIS，非法值 → 400。 */
    public static ScriptEngine fromTask(JsonNode task) {
        if (task == null) {
            return DEFAULT;
        }
        JsonNode node = task.get("engine");
        if (node == null || node.isNull()) {
            return DEFAULT;
        }
        return normalize(node.asText(null));
    }

    /** 大小写不敏感；null/空白视为默认 DORIS。 */
    public static ScriptEngine normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "不支持的执行引擎: " + raw + "（可选 DORIS / SPARK / FLINK）");
        }
    }
}
