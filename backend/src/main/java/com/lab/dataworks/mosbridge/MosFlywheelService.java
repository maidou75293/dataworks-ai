package com.lab.dataworks.mosbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REQ-FW-2 飞轮②产物回环：轮询「由中台发起」的 mos job/eval，把产物自动登记进中台台账。
 *
 * <p>三条回路（卡面目标）：</p>
 * <ol>
 *   <li>job 终态事件入运行事件（模式参照 integration/RunEventService）；</li>
 *   <li>finished → 读对方 models（按 job_id 反查）+ manifest → 台账 upsert，
 *       register_source = auto:fw2；</li>
 *   <li>eval done → result.summary 各候选 avg 写回台账行。</li>
 * </ol>
 *
 * <p>对方无回调（契约 §5：outbound 0 命中），一切以轮询 GET 为准；轮询失败不丢跟踪行，
 * 指数退避后重试，错误落库可查。幂等两层：跟踪行终态后不再处理；台账按 ext_id upsert。</p>
 */
@Service
public class MosFlywheelService {

    private static final Logger log = LoggerFactory.getLogger(MosFlywheelService.class);
    static final String REGISTER_SOURCE = "auto:fw2";
    private static final int POLL_BATCH = 20;

    private final MosGateway gateway;
    private final MosFlywheelDao dao;
    private final ObjectMapper json = new ObjectMapper();
    private final long pollIntervalSeconds;

    public MosFlywheelService(MosGateway gateway, MosFlywheelDao dao,
                              @Value("${platform.mos.flywheel.interval-ms:30000}") long intervalMs) {
        this.gateway = gateway;
        this.dao = dao;
        // 契约 §5：轮询间隔 ≥30s，配小了按 30s 执行
        this.pollIntervalSeconds = Math.max(30, Math.round(intervalMs / 1000.0));
    }

    // ---------- 发起（由中台调用；job 提交本身走 REQ-UNIFY-1 代理通道） ----------

    /** 登记一个「由中台发起」的训练 job 进轮询；重复登记返回原行（幂等）。 */
    public MosFlywheelDao.WatchRow trackJob(String jobId, String tenantId,
                                            Long projectId, String createdBy) {
        require(jobId, "jobId");
        return dao.insertWatch("job", jobId.trim(), null, tenantId, projectId, createdBy);
    }

    /** 登记评测；mappings 可空 = 按候选名对台账 name 兜底反查。 */
    public MosFlywheelDao.WatchRow trackEval(String evalId, List<Map<String, String>> mappings,
                                             String tenantId, Long projectId, String createdBy) {
        require(evalId, "evalId");
        String payload = null;
        if (mappings != null && !mappings.isEmpty()) {
            List<Map<String, String>> clean = new ArrayList<>();
            for (Map<String, String> m : mappings) {
                String candidate = m == null ? null : m.get("candidate");
                String extId = m == null ? null : m.get("extId");
                if (candidate == null || candidate.isBlank() || extId == null || extId.isBlank()) {
                    throw new IllegalArgumentException("mappings 每项必须含 candidate 与 extId");
                }
                clean.add(Map.of("candidate", candidate.trim(), "extId", extId.trim()));
            }
            try {
                payload = json.writeValueAsString(clean);
            } catch (Exception e) {
                throw new IllegalArgumentException("mappings 序列化失败", e);
            }
        }
        return dao.insertWatch("eval", evalId.trim(), payload, tenantId, projectId, createdBy);
    }

    // ---------- 轮询 ----------

    /** 跑一轮到期轮询；返回本轮处理条数。网关未配置时返回 -1（调用方打日志，不静默）。 */
    public int pollOnce() {
        if (!gateway.enabled()) {
            return -1;
        }
        List<MosFlywheelDao.WatchRow> due = dao.dueWatches(POLL_BATCH);
        int handled = 0;
        for (MosFlywheelDao.WatchRow w : due) {
            try {
                if ("eval".equals(w.kind())) {
                    pollEval(w);
                } else {
                    pollJob(w);
                }
                handled++;
            } catch (Exception e) {
                // 取不回状态 ≠ 任务失败：保持跟踪、退避重试、错误落库（不许静默丢弃）
                log.warn("mos 轮询失败 watch={} kind={} ext={}: {}", w.id(), w.kind(), w.extId(), e.toString());
                dao.markFailure(w.id(), e.toString(),
                        Instant.now().plusSeconds(MosArtifactMapper.backoffSeconds(
                                w.attempts() + 1, pollIntervalSeconds)));
            }
        }
        return handled;
    }

    private void pollJob(MosFlywheelDao.WatchRow w) throws Exception {
        Map<String, Object> job = gateway.getJob(w.extId());
        String status = string(job.get("status"));
        if (!MosArtifactMapper.JOB_STATES.contains(status)) {
            throw new IllegalStateException("mos job 非法状态: " + status);
        }
        String lastSeen = string(job.get("updated_at"));
        if (!status.equals(w.status())) {
            dao.emitEvent(w.id(), w.extId(), "progress", "INFO",
                    "状态 " + w.status() + " → " + status, null);
        }
        if (!MosArtifactMapper.JOB_TERMINAL.contains(status)) {
            dao.scheduleNext(w.id(), status, lastSeen, Instant.now().plusSeconds(pollIntervalSeconds));
            return;
        }
        dao.markTerminal(w.id(), status);
        dao.emitEvent(w.id(), w.extId(), "terminal",
                "finished".equals(status) ? "INFO" : "WARN",
                "训练终态 " + status, Map.of("jobId", w.extId()));
        if ("finished".equals(status)) {
            registerArtifacts(w, job);
        }
    }

    /** 训练成功 → 读对方产物 → 台账 upsert（auto:fw2）。找不到模型行也如实记 WARN（不造空台账）。 */
    private void registerArtifacts(MosFlywheelDao.WatchRow w, Map<String, Object> job) throws Exception {
        List<Map<String, Object>> models = gateway.listModels();
        List<Map<String, Object>> mine = new ArrayList<>();
        String ownDir = string(job.get("own_dir"));
        for (Map<String, Object> m : models) {
            boolean byJob = w.extId().equals(string(m.get("job_id")));
            boolean byPath = !ownDir.isBlank() && ownDir.equals(string(m.get("path")));
            if (byJob || byPath) {
                mine.add(m);
            }
        }
        if (mine.isEmpty()) {
            dao.emitEvent(w.id(), w.extId(), "register", "WARN",
                    "训练 finished 但对方模型台账没有该 job 的产物行（可能登记失败或被删除）", null);
            return;
        }
        for (Map<String, Object> m : mine) {
            String mid = string(m.get("id"));
            if (mid.isBlank()) {
                continue;
            }
            Map<String, Object> detail = gateway.getModel(mid);
            String manifestRaw = gateway.getManifestRaw(mid);
            dao.upsertLedger(new MosFlywheelDao.LedgerRow(
                    mid,
                    firstNonBlank(string(detail.get("name")), string(m.get("name"))),
                    firstNonBlank(string(detail.get("base")), string(m.get("base"))),
                    firstNonBlank(string(detail.get("method")), string(m.get("method"))),
                    MosArtifactMapper.datasetRef(
                            firstNonBlank(string(detail.get("dataset_id")), string(m.get("dataset_id"))),
                            detail.get("version")),
                    w.extId(),
                    MosArtifactMapper.artifactType(
                            firstNonBlank(string(detail.get("method")), string(m.get("method")))),
                    MosArtifactMapper.sha256Hex(manifestRaw),
                    toDouble(firstNonBlankOr(detail.get("size_gb"), m.get("size_gb"))),
                    firstNonBlank(string(detail.get("status")), string(m.get("status"))),
                    firstNonBlank(string(detail.get("path")), string(m.get("path"))),
                    REGISTER_SOURCE,
                    w.tenantId(), w.projectId(), w.createdBy()));
            dao.emitEvent(w.id(), w.extId(), "register", "INFO",
                    "产物自动登记台账 " + mid,
                    Map.of("extId", mid, "manifestBytes", manifestRaw.length()));
        }
    }

    private void pollEval(MosFlywheelDao.WatchRow w) throws Exception {
        Map<String, Object> ev = gateway.getEval(w.extId());
        String status = string(ev.get("status"));
        if (!MosArtifactMapper.EVAL_STATES.contains(status)) {
            throw new IllegalStateException("mos eval 非法状态: " + status);
        }
        String lastSeen = string(ev.get("updated_at"));
        if (!status.equals(w.status())) {
            dao.emitEvent(w.id(), w.extId(), "progress", "INFO",
                    "评测状态 " + w.status() + " → " + status, null);
        }
        if (!MosArtifactMapper.EVAL_TERMINAL.contains(status)) {
            dao.scheduleNext(w.id(), status, lastSeen, Instant.now().plusSeconds(pollIntervalSeconds));
            return;
        }
        dao.markTerminal(w.id(), status);
        if ("failed".equals(status)) {
            dao.emitEvent(w.id(), w.extId(), "eval", "WARN",
                    "评测失败：" + string(ev.get("error")), null);
            return;
        }
        writeBackScores(w, ev);
    }

    /** 评测完成 → summary 各候选 avg 写回台账行。映射优先 payload，其次按候选名查台账。 */
    private void writeBackScores(MosFlywheelDao.WatchRow w, Map<String, Object> ev) throws Exception {
        Object result = ev.get("result");
        Map<String, Object> resultMap = result instanceof Map<?, ?> map
                ? castMap(map) : Map.of();
        List<Object> summary = resultMap.get("summary") instanceof List<?> list
                ? new ArrayList<>(list) : List.of();
        Map<String, String> extByCandidate = parsePayload(w.payload());
        int written = 0;
        for (Object item : summary) {
            if (!(item instanceof Map<?, ?> s)) {
                continue;
            }
            String candidate = string(s.get("name"));
            Double avg = toDouble(s.get("avg"));
            String extId = extByCandidate.get(candidate);
            if (extId == null) {
                extId = dao.findLedgerExtIdByName(candidate, w.tenantId());
            }
            if (extId == null || avg == null) {
                dao.emitEvent(w.id(), w.extId(), "eval", "WARN",
                        "候选「" + candidate + "」无法写回台账（台账无此名或 avg 非数字）", null);
                continue;
            }
            if (dao.writeEvalScore(extId, w.extId(), avg, "done")) {
                written++;
                dao.emitEvent(w.id(), w.extId(), "eval", "INFO",
                        "分数写回 " + extId + " avg=" + avg,
                        Map.of("extId", extId, "avg", avg));
            } else {
                dao.emitEvent(w.id(), w.extId(), "eval", "WARN",
                        "ext_id " + extId + " 不在台账，分数无处写回", null);
            }
        }
        if (written == 0 && !summary.isEmpty()) {
            log.warn("eval {} 完成但没有任何分数写回台账", w.extId());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parsePayload(String payload) throws Exception {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        List<Map<String, String>> list = json.readValue(payload, List.class);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> m : list) {
            out.put(m.get("candidate"), m.get("extId"));
        }
        return out;
    }

    // ---------- 查询 ----------

    public List<Map<String, Object>> ledger(String tenantId) {
        return dao.listLedger(tenantId);
    }

    public List<Map<String, Object>> watches(String tenantId, boolean openOnly) {
        return dao.listWatches(tenantId, openOnly);
    }

    public List<Map<String, Object>> events(long watchId, String tenantId) {
        return dao.events(watchId, tenantId);
    }

    // ---------- 小工具 ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String string(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private static Object firstNonBlankOr(Object a, Object b) {
        return a != null ? a : b;
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
