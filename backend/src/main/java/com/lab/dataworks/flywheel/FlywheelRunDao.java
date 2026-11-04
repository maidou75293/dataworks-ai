package com.lab.dataworks.flywheel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REQ-FW-4 编排记账端口（V200 两表 + FW-3 池/批次的只读观察）。
 *
 * <p>与 {@code MosFlywheelDao}（FW-2）同构：判定逻辑跑在内存桩上，生产实现走
 * {@link JdbcFlywheelRunDao}。by-id 读写一律带 tenant 条件（by-id-tenant-audit 口径，
 * 越权=查不到，不 500）。</p>
 */
public interface FlywheelRunDao {

    /** 新建一圈（status=RUNNING, current_step=TABLE）。spec 为发起参数 JSON。 */
    long insertRun(String tenantId, Long projectId, Long parentRunId, String specJson, String createdBy);

    /** 按 id + 租户取一圈；不属于该租户返回 null（调用方按 404 处理）。 */
    Run findRun(long runId, String tenantId);

    /** 进行中的一圈（RUNNING；WAIT_GATE 等人工、不在推进列）。跨租户——定时圈等价于 FW-3 的全租户 tick。 */
    List<Run> openRuns();

    /** 局部更新：只写非 null 字段（每个动作成功一步记一步——重试安全的前提）。 */
    void updateRun(long runId, RunPatch patch);

    /** 状态/步推进（status+step+gate_wait 一次写定；gate_wait 传 null=清除等待）。 */
    void transition(long runId, String status, String step, String gateWait, String lastError);

    /** 步事件。写失败不影响主流程（FW-2 同构口径），由实现决定吞不吞。 */
    void emit(long runId, String step, String type, String message, Map<String, Object> metrics);

    List<Map<String, Object>> events(long runId, String tenantId);

    /** 列表视图（泳道页数据）：租户过滤，含终态；limit 防全表拖页。 */
    List<Map<String, Object>> listRuns(String tenantId, boolean openOnly, int limit);

    // ---------- FW-3 只读观察面（不写、不改 FW-3 的表语义） ----------

    /** 部署后进入池子的低质样本数（观察窗下界 = run.deploy_ts）。 */
    int countBadcaseSince(String tenantId, Instant since);

    /** 部署后封出的最近一个回环批次（READY 或 PUSHED）；没有返回 null。 */
    RelabelBatch findRelabelBatchSince(String tenantId, Instant since);

    /** 一圈的当前状态视图（字段=V200 表列，泳道页直接渲染）。 */
    record Run(long id, String tenantId, Long projectId, Long parentRunId, String status,
               String currentStep, String gateWait, String specJson, String dsId, String datasetVersion,
               String jobExtId, String evalExtId, String modelExtId, String modelName, String deployRef,
               Instant deployTs, Long relabelBatchId, String relabelTaskRef, Long childRunId,
               String lastError, String createdBy, Instant createdAt) {
    }

    /** 回环批次观察行（flywheel_relabel_batch 的只读投影）。 */
    record RelabelBatch(long id, String ledgerKey, String status, String mosTaskRef, int itemCount) {
    }

    /** 可空局部更新包；null=不改该列。 */
    final class RunPatch {
        public String dsId;
        public String datasetVersion;
        public String jobExtId;
        public String evalExtId;
        public String modelExtId;
        public String modelName;
        public String deployRef;
        public Instant deployTs;
        public Long relabelBatchId;
        public String relabelTaskRef;
        public Long childRunId;
        public String lastError;
    }
}
