package com.lab.dataworks.mosbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * REQ-FW-2 的纯判定逻辑：状态机、产物形态、摘要、退避。没有 IO，全部可单测。
 */
public final class MosArtifactMapper {

    /** job 聚合态（jobs.py:783-794）：queued/running/finished/failed/stopped/unknown。 */
    public static final Set<String> JOB_STATES =
            Set.of("queued", "running", "finished", "failed", "stopped", "unknown");
    /** job 终态（runner.py:474-475 写库只产这三个）。 */
    public static final Set<String> JOB_TERMINAL = Set.of("finished", "failed", "stopped");
    /** eval 终态（services/eval.py:163-176 _finish 只写 done/failed）。 */
    public static final Set<String> EVAL_STATES = Set.of("draft", "running", "done", "failed");
    public static final Set<String> EVAL_TERMINAL = Set.of("done", "failed");

    /** 退避上限：契约 §5 指数退避；15 分钟封顶防止 mos 长时间不可达时排队积压。 */
    static final long BACKOFF_CAP_SECONDS = 900;

    private MosArtifactMapper() {
    }

    /**
     * 对方 models.method → 契约 §2.2/§4.2 产物形态三选一。
     * export=融合导出、quant=AWQ 量化（runner.py:506 只自动登记这两类整目录）；
     * 其余（sft/pt/dpo 直登）视作 LoRA adapter。
     */
    public static String artifactType(String method) {
        String m = method == null ? "" : method.trim().toLowerCase();
        return switch (m) {
            case "quant" -> "awq";
            case "export", "merge" -> "merged";
            default -> "adapter";
        };
    }

    /** dataset_id@version（契约 §4.2）；dataset_id 为空返回 null（不造 "null@" 这种脏值）。 */
    public static String datasetRef(String datasetId, Object version) {
        if (datasetId == null || datasetId.isBlank()) {
            return null;
        }
        String v = version == null ? "" : String.valueOf(version).trim();
        return datasetId + "@" + v;
    }

    /** GET /models/{mid}/manifest 原文的 sha256（十六进制小写）。 */
    public static String sha256Hex(String rawManifest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawManifest.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 不可用", e);
        }
    }

    /** 指数退避：base × 2^attempts，封顶 {@link #BACKOFF_CAP_SECONDS}。attempts 从 1 起。 */
    public static long backoffSeconds(int attempts, long baseSeconds) {
        int exp = Math.min(Math.max(attempts, 1), 20);
        long v = baseSeconds * (1L << (exp - 1));
        return Math.min(v, BACKOFF_CAP_SECONDS);
    }

    public static boolean isTerminal(String kind, String status) {
        return "eval".equals(kind) ? EVAL_TERMINAL.contains(status) : JOB_TERMINAL.contains(status);
    }
}
