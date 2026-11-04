package com.lab.dataworks.mosbridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-2 轮询器三态判定（卡面验收：超时 / 失败 / 幂等）。
 *
 * <p>判定来自行为——内存桩 DAO + 假网关，断言台账/事件/跟踪行状态，
 * 不依赖真实 mos 与数据库。</p>
 *
 * 跑法：{@code mvn -q -Dtest=MosFlywheelServiceTest test}（mosbridge 包）
 */
class MosFlywheelServiceTest {

    private InMemoryDao dao;
    private FakeGateway gateway;
    private MosFlywheelService svc;

    @BeforeEach
    void setUp() {
        dao = new InMemoryDao();
        gateway = new FakeGateway();
        svc = new MosFlywheelService(gateway, dao, 30000);
    }

    // ---------- ② 训练 SUCCESS → 自动登记台账 ----------

    @Test
    void finishedJobRegistersLedgerWithContractFields() {
        MosFlywheelDao.WatchRow w = svc.trackJob("job_A", "t1", 7L, "alice");
        gateway.jobStatus = "finished";
        gateway.jobUpdatedAt = "2026-10-28 03:00:00";
        gateway.model.put("id", "mdl_abc1234567");
        gateway.model.put("name", "订单分类merged");
        gateway.model.put("base", "Qwen3-8B");
        gateway.model.put("method", "export");
        gateway.model.put("dataset_id", "ds_1");
        gateway.model.put("version", "v3");
        gateway.model.put("job_id", "job_A");
        gateway.model.put("status", "candidate");
        gateway.model.put("path", "/work/out/v1");
        gateway.model.put("size_gb", 15.6);
        gateway.manifestRaw = "{\"id\":\"mdl_abc1234567\",\"file_count\":3}";

        assertEquals(1, svc.pollOnce());

        assertEquals(1, dao.ledgers.size());
        MosFlywheelDao.LedgerRow row = dao.ledgers.get(0);
        assertEquals("mdl_abc1234567", row.extId());
        assertEquals("auto:fw2", row.registerSource());
        assertEquals("merged", row.artifactType());
        assertEquals("ds_1@v3", row.datasetRef());
        assertEquals("job_A", row.jobExtId());
        assertEquals(MosArtifactMapper.sha256Hex(gateway.manifestRaw), row.manifestDigest());
        assertEquals(15.6, row.sizeGb());
        assertEquals("t1", row.tenantId());
        assertEquals(7L, row.projectId());
        assertEquals("alice", row.createdBy());

        assertTrue(watchById(w.id()).terminal);
        assertTrue(dao.eventPhases.contains("terminal"));
        assertTrue(dao.eventPhases.contains("register"));
    }

    /** 量化产物映射 awq；训练成功但对方没登记模型行 → 记 WARN 事件、不造空台账。 */
    @Test
    void quantMethodMapsToAwqAndMissingModelRowWarns() {
        svc.trackJob("job_Q", "t1", null, "bob");
        gateway.jobStatus = "finished";
        gateway.model.put("method", "quant");
        gateway.model.put("job_id", "job_Q");
        gateway.model.put("id", "mdl_q1");
        assertEquals(1, svc.pollOnce());
        assertEquals("awq", dao.ledgers.get(0).artifactType());

        gateway.reset();
        gateway.jobStatus = "finished";
        gateway.model.put("job_id", "other"); // 对不上任何 job
        MosFlywheelDao.WatchRow w2 = svc.trackJob("job_N", "t1", null, "bob");
        assertEquals(1, svc.pollOnce());
        assertEquals(1, dao.ledgers.size()); // 没有新增空台账
        assertTrue(dao.events.stream().anyMatch(e ->
                e.watchId == w2.id() && "register".equals(e.phase) && "WARN".equals(e.type)));
    }

    // ---------- 三态：失败 / 超时 / 幂等 ----------

    /** 失败终态：事件入运行事件，不登记台账，跟踪行不再到期。 */
    @Test
    void failedJobEmitsWarnEventAndNoLedger() {
        MosFlywheelDao.WatchRow w = svc.trackJob("job_F", "t1", null, "alice");
        gateway.jobStatus = "failed";
        assertEquals(1, svc.pollOnce());
        assertTrue(watchById(w.id()).terminal);
        assertEquals(0, dao.ledgers.size());
        assertTrue(dao.events.stream().anyMatch(e ->
                e.watchId == w.id() && "terminal".equals(e.phase) && "WARN".equals(e.type)));
        assertTrue(dao.dueWatches(50).isEmpty());
    }

    /** 超时（网关取不回）：不判死、attempts+1、退避到未来、错误落库；恢复后可正常收敛。 */
    @Test
    void gatewayTimeoutKeepsTrackingWithBackoffAndRecovers() {
        MosFlywheelDao.WatchRow w = svc.trackJob("job_T", "t1", null, "alice");
        gateway.throwOnGet = new java.net.SocketTimeoutException("read timed out");
        assertEquals(0, svc.pollOnce());
        InMemoryDao.Watch mutable = dao.watch(w.id());
        assertEquals(1, mutable.attempts);
        assertFalse(mutable.terminal);
        assertTrue(mutable.nextPollAt.isAfter(Instant.now().minusSeconds(1)));
        assertNotNull(mutable.lastError);
        assertTrue(mutable.lastError.contains("timed out"));

        // 第二次轮询还没到期——不该再打网关
        gateway.throwOnGet = null;
        assertEquals(0, svc.pollOnce());
        assertEquals(1, dao.watch(w.id()).attempts);

        // 到期后恢复：改回 running 观测
        dao.watch(w.id()).nextPollAt = Instant.now().minusSeconds(1);
        gateway.jobStatus = "running";
        assertEquals(1, svc.pollOnce());
        assertEquals("running", dao.watch(w.id()).status);
        assertEquals(0, dao.watch(w.id()).attempts); // 成功一轮重置计数
    }

    /** 幂等：finished 连轮两次只登记一次；同 (kind,ext) 重复 track 返回原行。 */
    @Test
    void repeatedPollingAndTrackingIsIdempotent() {
        MosFlywheelDao.WatchRow first = svc.trackJob("job_I", "t1", null, "alice");
        MosFlywheelDao.WatchRow again = svc.trackJob("job_I", "t1", null, "alice");
        assertEquals(first.id(), again.id());

        gateway.jobStatus = "finished";
        gateway.model.put("id", "mdl_i");
        gateway.model.put("job_id", "job_I");
        assertEquals(1, svc.pollOnce());
        assertEquals(1, svc.ledger("t1").size());

        // 强行再喂一轮到期（模拟并发/手动催）：终态跟踪行不再出现在 due，台账仍 1 行
        dao.watch(first.id()).nextPollAt = Instant.now().minusSeconds(1);
        assertEquals(0, svc.pollOnce());
        assertEquals(1, svc.ledger("t1").size());
        // 只有第一次状态迁移有 progress 事件（queued→finished），第二次没有
        long progresses = dao.events.stream()
                .filter(e -> "progress".equals(e.phase)).count();
        assertEquals(1, progresses);
    }

    // ---------- ③ 评测完成 → 分数写回 ----------

    @Test
    void evalDoneWritesScoresViaMappingAndNameFallback() {
        svc.trackJob("job_E", "t1", null, "alice");
        gateway.jobStatus = "finished";
        gateway.model.put("id", "mdl_e1");
        gateway.model.put("name", "候选甲");
        gateway.model.put("job_id", "job_E");
        svc.pollOnce();
        assertEquals(1, dao.ledgers.size());

        // 候选乙没有台账行，按名兜底反查会落空 → WARN，不报错
        svc.trackEval("ev_1", List.of(Map.of("candidate", "候选甲", "extId", "mdl_e1")),
                "t1", null, "alice");
        gateway.evalStatus = "done";
        gateway.evalSummary = List.of(
                summary("候选甲", 8.25),
                summary("候选乙", 5.0));

        assertEquals(1, svc.pollOnce());

        Map<String, Object> row = dao.listLedger("t1").get(0);
        assertEquals(8.25, ((Number) row.get("evalScore")).doubleValue(), 1e-9);
        assertEquals("ev_1", row.get("evalExtId"));
        assertEquals("done", row.get("evalStatus"));
        assertTrue(dao.events.stream().anyMatch(e ->
                "eval".equals(e.phase) && "WARN".equals(e.type)
                        && e.message.contains("候选乙")));
    }

    @Test
    void evalFailedEmitsWarnAndWritesNothing() {
        svc.trackJob("job_E2", "t1", null, "alice");
        gateway.jobStatus = "finished";
        gateway.model.put("id", "mdl_e2");
        gateway.model.put("job_id", "job_E2");
        svc.pollOnce();

        MosFlywheelDao.WatchRow w = svc.trackEval("ev_bad", null, "t1", null, "alice");
        gateway.evalStatus = "failed";
        gateway.evalError = "裁判端点不可达";
        assertEquals(1, svc.pollOnce());
        assertTrue(watchById(w.id()).terminal);
        assertNull(dao.listLedger("t1").get(0).get("evalScore"));
        assertTrue(dao.events.stream().anyMatch(e ->
                e.watchId == w.id() && "eval".equals(e.phase)
                        && "WARN".equals(e.type) && e.message.contains("裁判端点不可达")));
    }

    /** 对方回了状态机之外的值：不许静默——按失败退避处理并留痕。 */
    @Test
    void illegalStatusIsTreatedAsFailureNotSilentlyStored() {
        MosFlywheelDao.WatchRow w = svc.trackJob("job_X", "t1", null, "alice");
        gateway.jobStatus = "exploded";
        assertEquals(0, svc.pollOnce());
        InMemoryDao.Watch watch = dao.watch(w.id());
        assertFalse(watch.terminal);
        assertEquals(1, watch.attempts);
        assertTrue(watch.lastError.contains("非法状态"));
    }

    /** 网关未配置：轮询整轮跳过并报原因（pollOnce=-1），不许匿名打过去。 */
    @Test
    void disabledGatewaySkipsEntireRound() {
        gateway.enabled = false;
        svc.trackJob("job_D", "t1", null, "alice");
        assertEquals(-1, svc.pollOnce());
        assertEquals(0, gateway.calls);
    }

    // ---------- 桩 ----------

    private InMemoryDao.Watch watchById(long id) {
        return dao.watch(id);
    }

    private static Map<String, Object> summary(String name, double avg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("avg", avg);
        m.put("win", 1);
        m.put("n", 10);
        return m;
    }

    private static final class FakeGateway implements MosGateway {
        boolean enabled = true;
        int calls;
        Exception throwOnGet;
        String jobStatus = "queued";
        String jobUpdatedAt = "2026-10-28 03:00:00";
        final Map<String, Object> model = new HashMap<>();
        String manifestRaw = "{}";
        String evalStatus = "draft";
        String evalError;
        List<Map<String, Object>> evalSummary = List.of();

        void reset() {
            model.clear();
            evalSummary = List.of();
            evalStatus = "draft";
            evalError = null;
        }

        @Override public boolean enabled() { return enabled; }
        @Override public String disabledReason() { return "test"; }

        private void check() throws Exception {
            calls++;
            if (throwOnGet != null) {
                throw throwOnGet;
            }
        }

        @Override
        public Map<String, Object> getJob(String jobId) throws Exception {
            check();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", jobId);
            m.put("status", jobStatus);
            m.put("updated_at", jobUpdatedAt);
            m.put("own_dir", "");
            return m;
        }

        @Override
        public List<Map<String, Object>> listModels() throws Exception {
            check();
            return model.isEmpty() ? List.of() : List.of(new LinkedHashMap<>(model));
        }

        @Override
        public Map<String, Object> getModel(String modelId) throws Exception {
            check();
            return new LinkedHashMap<>(model);
        }

        @Override
        public String getManifestRaw(String modelId) throws Exception {
            check();
            return manifestRaw;
        }

        @Override
        public Map<String, Object> getEval(String evalId) throws Exception {
            check();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", evalId);
            m.put("status", evalStatus);
            m.put("updated_at", "2026-10-28 03:10:00");
            if (evalError != null) {
                m.put("error", evalError);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", evalSummary);
            m.put("result", result);
            return m;
        }
    }

    /** 行为断言用的内存 DAO：只实现接口语义，不管 SQL。 */
    private static final class InMemoryDao implements MosFlywheelDao {
        final List<Watch> watches = new ArrayList<>();
        final List<LedgerRow> ledgers = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final List<String> eventPhases = new ArrayList<>();
        long seq = 1;

        static final class Watch {
            long id;
            String kind, extId, status, payload, tenantId, createdBy, lastSeenAt, lastError;
            Long projectId;
            int attempts;
            boolean terminal;
            Instant nextPollAt = Instant.now().minusSeconds(1);
        }

        static final class Event {
            long watchId;
            String phase, type, message;
        }

        Watch watch(long id) {
            return watches.stream().filter(w -> w.id == id).findFirst().orElseThrow();
        }

        @Override
        public WatchRow insertWatch(String kind, String extId, String payloadJson,
                                    String tenantId, Long projectId, String createdBy) {
            return watches.stream().filter(w -> w.kind.equals(kind) && w.extId.equals(extId))
                    .findFirst().map(InMemoryDao::toRow).orElseGet(() -> {
                        Watch w = new Watch();
                        w.id = seq++;
                        w.kind = kind;
                        w.extId = extId;
                        w.status = "queued";
                        w.payload = payloadJson;
                        w.tenantId = tenantId;
                        w.projectId = projectId;
                        w.createdBy = createdBy;
                        watches.add(w);
                        return toRow(w);
                    });
        }

        private static WatchRow toRow(Watch w) {
            return new WatchRow(w.id, w.kind, w.extId, w.status, w.attempts,
                    w.payload, w.tenantId, w.projectId, w.createdBy);
        }

        @Override
        public List<WatchRow> dueWatches(int limit) {
            Instant now = Instant.now();
            return watches.stream()
                    .filter(w -> !w.terminal && !w.nextPollAt.isAfter(now))
                    .limit(limit).map(InMemoryDao::toRow).toList();
        }

        @Override
        public void scheduleNext(long watchId, String status, String lastSeen, Instant nextAt) {
            Watch w = watch(watchId);
            w.status = status;
            w.lastSeenAt = lastSeen;
            w.attempts = 0;
            w.lastError = null;
            w.nextPollAt = nextAt;
        }

        @Override
        public void markFailure(long watchId, String error, Instant nextAt) {
            Watch w = watch(watchId);
            w.attempts++;
            w.lastError = error;
            w.nextPollAt = nextAt;
        }

        @Override
        public void markTerminal(long watchId, String status) {
            Watch w = watch(watchId);
            w.terminal = true;
            w.status = status;
        }

        @Override
        public void emitEvent(long watchId, String extId, String phase, String type,
                              String message, Map<String, Object> metrics) {
            Event e = new Event();
            e.watchId = watchId;
            e.phase = phase;
            e.type = type;
            e.message = message;
            events.add(e);
            eventPhases.add(phase);
        }

        @Override
        public void upsertLedger(LedgerRow row) {
            ledgers.removeIf(l -> l.extId().equals(row.extId()));
            ledgers.add(row);
        }

        /** 评测写回的列值（产物事实仍在 ledgers 里，两半在 listLedger 合并）。 */
        final Map<String, Map<String, Object>> evalWrites = new HashMap<>();

        @Override
        public boolean writeEvalScore(String extId, String evalExtId, Double score, String evalStatus) {
            if (ledgers.stream().noneMatch(l -> l.extId().equals(extId))) {
                return false;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("evalExtId", evalExtId);
            m.put("evalScore", score);
            m.put("evalStatus", evalStatus);
            evalWrites.put(extId, m);
            return true;
        }

        @Override
        public String findLedgerExtIdByName(String name, String tenantId) {
            return ledgers.stream()
                    .filter(l -> l.name().equals(name) && l.tenantId().equals(tenantId))
                    .map(LedgerRow::extId).reduce((a, b) -> b).orElse(null);
        }

        @Override
        public List<Map<String, Object>> listLedger(String tenantId) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (LedgerRow l : ledgers) {
                if (!l.tenantId().equals(tenantId)) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("extId", l.extId());
                m.put("name", l.name());
                m.put("registerSource", l.registerSource());
                Map<String, Object> eval = evalWrites.get(l.extId());
                if (eval != null) {
                    m.putAll(eval);
                }
                out.add(m);
            }
            return out;
        }

        @Override
        public List<Map<String, Object>> listWatches(String tenantId, boolean openOnly) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Watch w : watches) {
                if (w.tenantId.equals(tenantId) && (!openOnly || !w.terminal)) {
                    out.add(Map.of("id", w.id, "kind", w.kind, "extId", w.extId));
                }
            }
            return out;
        }

        @Override
        public List<Map<String, Object>> events(long watchId, String tenantId) {
            return events.stream().filter(e -> e.watchId == watchId).map(e -> {
                Map<String, Object> m = new HashMap<>();
                m.put("phase", e.phase);
                m.put("type", e.type);
                return m;
            }).toList();
        }
    }
}
