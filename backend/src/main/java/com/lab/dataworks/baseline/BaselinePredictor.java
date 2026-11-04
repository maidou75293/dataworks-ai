package com.lab.dataworks.baseline;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基线预测的纯计算部分：依赖图上的关键路径剩余耗时，以及迟到分钟数。
 *
 * <p>不做 IO，方便单测；数据库取数与兜底逻辑在 {@link BaselineService}。</p>
 */
public final class BaselinePredictor {

    private BaselinePredictor() {
    }

    /**
     * 一个任务节点。{@code durationSeconds} 是「从现在开始还需要多久」：
     * 已完成的任务传 0；正在运行的任务传入「预计耗时 - 已耗时」；未开始的传预计耗时。
     */
    public record Node(String code, double durationSeconds) {
    }

    /**
     * 计算从当前时刻到整条 DAG 全部完成的最短剩余时间（关键路径）。
     *
     * @param nodes 所有任务节点（已完成节点的 durationSeconds 应为 0）
     * @param deps  任务 → 前置任务列表；缺失等价于没有前置
     */
    public static double remainingSeconds(List<Node> nodes, Map<String, List<String>> deps) {
        if (nodes == null || nodes.isEmpty()) {
            return 0;
        }
        Map<String, Double> duration = new HashMap<>();
        for (Node node : nodes) {
            duration.put(node.code(), Math.max(0, node.durationSeconds()));
        }
        Map<String, List<String>> upstream = deps == null ? Map.of() : deps;
        Map<String, Double> memo = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        double max = 0;
        for (Node node : nodes) {
            max = Math.max(max, remaining(node.code(), duration, upstream, memo, visiting));
        }
        return max;
    }

    private static double remaining(String code, Map<String, Double> duration,
                                    Map<String, List<String>> upstream,
                                    Map<String, Double> memo, Set<String> visiting) {
        Double cached = memo.get(code);
        if (cached != null) {
            return cached;
        }
        double own = duration.getOrDefault(code, 0.0);
        if (own <= 0) {
            // 已完成的任务不再给下游增加等待时间
            memo.put(code, 0.0);
            return 0.0;
        }
        if (!visiting.add(code)) {
            // 模型已经过发布校验不会成环；这里防御脏数据导致递归不返回
            return 0.0;
        }
        double wait = 0;
        for (String dep : upstream.getOrDefault(code, List.of())) {
            if (duration.containsKey(dep)) {
                wait = Math.max(wait, remaining(dep, duration, upstream, memo, visiting));
            }
        }
        double value = own + wait;
        visiting.remove(code);
        memo.put(code, value);
        return value;
    }

    /** 预测完成时间相对承诺时间的迟到分钟数；负数表示提前完成。 */
    public static Integer lateMinutes(Instant predictedFinish, Instant deadlineAt) {
        if (predictedFinish == null || deadlineAt == null) {
            return null;
        }
        long seconds = predictedFinish.getEpochSecond() - deadlineAt.getEpochSecond();
        return (int) Math.round(seconds / 60.0);
    }
}
