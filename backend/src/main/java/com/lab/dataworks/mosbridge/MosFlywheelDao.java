package com.lab.dataworks.mosbridge;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REQ-FW-2 落库端口（V170 三张表）。
 *
 * <p>与真实 Jdbc 实现（{@link JdbcMosFlywheelDao}）分开，是为了让轮询器的
 * 「超时 / 失败 / 幂等」三态单测跑在内存桩上——判定来自行为，不来自 SQL 字符串断言。</p>
 */
public interface MosFlywheelDao {

    /** 跟踪一单：同一 (kind, ext_id) 重复登记返回原行（幂等，卡面要求）。 */
    WatchRow insertWatch(String kind, String extId, String payloadJson,
                         String tenantId, Long projectId, String createdBy);

    /** 到期且未终态的跟踪行（按 next_poll_at 升序）。 */
    List<WatchRow> dueWatches(int limit);

    /** 观测成功一次：更新状态/对方 updated_at，重置失败计数并排下一次轮询。 */
    void scheduleNext(long watchId, String status, String lastSeen, Instant nextAt);

    /** 取回失败：attempts+1，退避到 nextAt，记 last_error（不许静默）。 */
    void markFailure(long watchId, String error, Instant nextAt);

    /** 进入终态：terminal=true，不再轮询。 */
    void markTerminal(long watchId, String status);

    /** 运行事件。写失败不能影响主流程（与 RunEventService 同构），由实现决定吞不吞。 */
    void emitEvent(long watchId, String extId, String phase, String type,
                   String message, Map<String, Object> metrics);

    /** 台账 upsert（按 ext_id 幂等）：二次登记刷新产物事实，不清掉已写回的评测分数。 */
    void upsertLedger(LedgerRow row);

    /** 评测分数写回：找不到行返回 false（调用方如实记 WARN 事件）。 */
    boolean writeEvalScore(String extId, String evalExtId, Double score, String evalStatus);

    /** 候选名没带映射时按台账 name 兜底反查；多租户下只查本租户。 */
    String findLedgerExtIdByName(String name, String tenantId);

    List<Map<String, Object>> listLedger(String tenantId);

    List<Map<String, Object>> listWatches(String tenantId, boolean openOnly);

    /** 事件列表带租户校验（watch 不属于该租户 = 查不到，防 by-id 越权）。 */
    List<Map<String, Object>> events(long watchId, String tenantId);

    /** 跟踪行。payload 只有 eval 用：[{"candidate":名,"extId":mdl_xxx}]。 */
    record WatchRow(long id, String kind, String extId, String status,
                    int attempts, String payload, String tenantId,
                    Long projectId, String createdBy) {
    }

    /** 台账行（字段=契约 §4.2）。 */
    record LedgerRow(String extId, String name, String base, String method,
                     String datasetRef, String jobExtId, String artifactType,
                     String manifestDigest, Double sizeGb, String mosStatus,
                     String pathOrKey, String registerSource,
                     String tenantId, Long projectId, String createdBy) {
    }
}
