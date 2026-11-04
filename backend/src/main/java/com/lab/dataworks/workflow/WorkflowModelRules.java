package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作流模型的引擎约束（保存路径）。
 *
 * <ul>
 *   <li><b>新写入</b>：SQL 节点显式带了 {@code engine} 就必须是合法值，不能是空串；
 *       老模型没有这个字段时保持缺省 DORIS，不被这里拦下。</li>
 *   <li><b>FLINK 不绑定数据源</b>：Flink 走 SQL Gateway，绑定关系只对 Doris / Spark 有意义。
 *       保存时就拒绝，而不是等试跑才 400。</li>
 *   <li><b>创建后只读</b>：更新工作流时，同一任务编码的引擎不允许变化；要换引擎只能新建脚本，
 *       避免模型里出现「运行中是 FLINK、下次试跑变 DORIS」。</li>
 * </ul>
 */
public final class WorkflowModelRules {

    private WorkflowModelRules() {
    }

    /** 保存前校验：SQL 节点的 engine 不得为空/非法；FLINK 不得绑定数据源。 */
    public static void validateEngines(List<?> taskList) {
        for (Object item : taskList) {
            if (!(item instanceof Map<?, ?> task)) {
                continue;
            }
            String code = text(task.get("code"), "task");
            String type = text(task.get("type"), "SQL").toUpperCase(Locale.ROOT);
            if (!"SQL".equals(type)) {
                continue;
            }
            Object rawEngine = task.get("engine");
            // 显式写了 engine（包括 null / 空串）就不能是空值；字段整体缺省才是老任务
            if (task.containsKey("engine")
                    && (rawEngine == null || String.valueOf(rawEngine).isBlank())) {
                throw ApiException.badRequest("SQL 节点的执行引擎不能为空：任务 " + code
                        + " 请选择 DORIS / SPARK / FLINK；老任务可以保留 engine 缺省以按 DORIS 执行");
            }
            // S1（扫描 P0）：datasource=PG 会让渲染出的 Flow 用平台元数据库账号执行节点脚本，
            // 保存这一层先拦住，发布/渲染再兜一层。
            String reason = com.lab.dataworks.common.PlatformInternalDb
                    .reservedAliasReason(code, task.get("datasource") == null
                            ? null : String.valueOf(task.get("datasource")));
            if (reason != null) {
                throw ApiException.badRequest(reason);
            }
            ScriptEngine engine = ScriptEngine.normalize(
                    rawEngine == null ? null : String.valueOf(rawEngine));
            if (engine == ScriptEngine.FLINK && task.get("datasourceId") != null) {
                throw ApiException.badRequest("FLINK 引擎不支持绑定数据源：任务 " + code
                        + "。Flink 通过 CREATE TABLE ... WITH ('connector'=...) 声明连接，"
                        + "请先解除绑定");
            }
        }
    }

    /**
     * 引擎创建后只读：把已存模型与本次提交的模型按任务编码对比，
     * 同一任务 SQL→SQL 的引擎变化直接 400。缺省字段两侧都按 DORIS 归一化。
     */
    public static void ensureEnginesUnchanged(ObjectMapper mapper, String storedModelJson,
                                              Object incomingModel) {
        if (storedModelJson == null || storedModelJson.isBlank() || incomingModel == null) {
            return;
        }
        JsonNode stored;
        try {
            stored = mapper.readTree(storedModelJson);
        } catch (Exception e) {
            // 已存模型解析不了时不要在保存路径上再造一个错误：形状校验会兜底
            return;
        }
        Map<String, ScriptEngine> before = engineByCode(stored.path("tasks"));
        if (before.isEmpty() || !(incomingModel instanceof Map<?, ?> model)) {
            return;
        }
        Object rawTasks = model.get("tasks");
        if (!(rawTasks instanceof List<?> tasks)) {
            return;
        }
        for (Object item : tasks) {
            if (!(item instanceof Map<?, ?> task)) {
                continue;
            }
            String code = text(task.get("code"), "");
            String type = text(task.get("type"), "SQL").toUpperCase(Locale.ROOT);
            // 改成非 SQL 类型时引擎不再适用（字段会被移除），不算改引擎
            if (code.isBlank() || !"SQL".equals(type) || !before.containsKey(code)) {
                continue;
            }
            Object rawEngine = task.get("engine");
            ScriptEngine after = ScriptEngine.normalize(
                    rawEngine == null ? null : String.valueOf(rawEngine));
            ScriptEngine old = before.get(code);
            if (old != after) {
                throw ApiException.badRequest("任务 " + code + " 的执行引擎创建后不可修改（"
                        + old.name() + " → " + after.name()
                        + "）；如需更换引擎请新建一个脚本");
            }
        }
    }

    private static Map<String, ScriptEngine> engineByCode(JsonNode tasks) {
        Map<String, ScriptEngine> out = new HashMap<>();
        for (JsonNode task : tasks) {
            String code = task.path("code").asText("");
            String type = task.path("type").asText("SQL").toUpperCase(Locale.ROOT);
            if (code.isBlank() || !"SQL".equals(type)) {
                continue;
            }
            try {
                out.put(code, ScriptEngine.fromTask(task));
            } catch (RuntimeException e) {
                // 存量模型里的非法值交给运行期 400；这里不因它阻断其他修改的保存
            }
        }
        return out;
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank()
                ? fallback : String.valueOf(value).trim();
    }
}
