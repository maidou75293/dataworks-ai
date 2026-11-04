package com.lab.dataworks.flywheel;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.mosbridge.AnnotationPushService;
import com.lab.dataworks.mosbridge.MosFlywheelService;
import com.lab.dataworks.query.DorisQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-FW-4 编排状态机单测：全内存跑「表→标注→训练→评测→部署→badcase→回环」，
 * 出站全部是假件（MosChainPort/AnnotationPushService/FW-2 watches/Mos 均不打真网络）。
 *
 * <p>钉四件卡面验收的事：① AUTO 一圈贯通并自动续出下一圈（badcase 回环触发二次标注）；
 * ② 人工闸只保留标注/评测两个，放行=执行动作、拒绝=终止本圈、点错闸=400；
 * ③ AUTO≠无条件放行（评测低分仍转人工）；④ 重试安全=每成功一步记一步
 * （freeze 不重复、tick 失败只记错误下一圈自愈）。</p>
 */
class FlywheelOrchestratorTest {

    private static final String TENANT = "t1";
    private static final String USER = "alice";

    // ================= 假件 =================

    /** 内存圈账本：语义镜像 JdbcFlywheelRunDao（transition 三列一次写定、last_error=null 即清除）。 */
    private static final class MemDao implements FlywheelRunDao {
        final Map<Long, Rec> runs = new LinkedHashMap<>();
        final List<Map<String, Object>> eventLog = new ArrayList<>();
        long seq = 0;

        int poolCount = 0;
        RelabelBatch batch;   // findRelabelBatchSince 返回它（测试里不按 since 过滤）

        static final class Rec {
            long id;
            String tenantId;
            Long projectId;
            Long parentRunId;
            String status = "RUNNING";
            String currentStep = FlywheelOrchestrator.STEP_TABLE;
            String gateWait;
            String specJson;
            String dsId, datasetVersion, jobExtId, evalExtId, modelExtId, modelName, deployRef;
            Instant deployTs;
            Long relabelBatchId;
            String relabelTaskRef;
            Long childRunId;
            String lastError;
            String createdBy;
            Instant createdAt = Instant.now();

            Run snap() {
                return new Run(id, tenantId, projectId, parentRunId, status, currentStep, gateWait,
                        specJson, dsId, datasetVersion, jobExtId, evalExtId, modelExtId, modelName,
                        deployRef, deployTs, relabelBatchId, relabelTaskRef, childRunId, lastError, createdBy,
                        createdAt);
            }
        }

        Rec raw(long id) {
            return runs.get(id);
        }

        @Override
        public long insertRun(String tenantId, Long projectId, Long parentRunId,
                              String specJson, String createdBy) {
            Rec r = new Rec();
            r.id = ++seq;
            r.tenantId = tenantId;
            r.projectId = projectId;
            r.parentRunId = parentRunId;
            r.specJson = specJson;
            r.createdBy = createdBy;
            runs.put(r.id, r);
            return r.id;
        }

        @Override
        public Run findRun(long runId, String tenantId) {
            Rec r = runs.get(runId);
            return r == null || !r.tenantId.equals(tenantId) ? null : r.snap();
        }

        @Override
        public List<Run> openRuns() {
            List<Run> out = new ArrayList<>();
            for (Rec r : runs.values()) {
                if ("RUNNING".equals(r.status)) {
                    out.add(r.snap());
                }
            }
            return out;
        }

        @Override
        public void updateRun(long runId, RunPatch p) {
            Rec r = runs.get(runId);
            if (p.dsId != null) r.dsId = p.dsId;
            if (p.datasetVersion != null) r.datasetVersion = p.datasetVersion;
            if (p.jobExtId != null) r.jobExtId = p.jobExtId;
            if (p.evalExtId != null) r.evalExtId = p.evalExtId;
            if (p.modelExtId != null) r.modelExtId = p.modelExtId;
            if (p.modelName != null) r.modelName = p.modelName;
            if (p.deployRef != null) r.deployRef = p.deployRef;
            if (p.deployTs != null) r.deployTs = p.deployTs;
            if (p.relabelBatchId != null) r.relabelBatchId = p.relabelBatchId;
            if (p.relabelTaskRef != null) r.relabelTaskRef = p.relabelTaskRef;
            if (p.childRunId != null) r.childRunId = p.childRunId;
            if (p.lastError != null) r.lastError = p.lastError;
        }

        @Override
        public void transition(long runId, String status, String step, String gateWait, String lastError) {
            Rec r = runs.get(runId);
            r.status = status;
            r.currentStep = step;
            r.gateWait = gateWait;
            r.lastError = lastError;
        }

        @Override
        public void emit(long runId, String step, String type, String message, Map<String, Object> metrics) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("runId", runId);
            e.put("step", step);
            e.put("type", type);
            e.put("message", message);
            e.put("metrics", metrics);
            eventLog.add(e);
        }

        @Override
        public List<Map<String, Object>> events(long runId, String tenantId) {
            return eventLog;
        }

        @Override
        public List<Map<String, Object>> listRuns(String tenantId, boolean openOnly, int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Rec r : runs.values()) {
                if (r.tenantId.equals(tenantId) && (!openOnly || "RUNNING".equals(r.status))) {
                    out.add(Map.of("id", r.id, "status", r.status, "currentStep", r.currentStep));
                }
            }
            return out;
        }

        @Override
        public int countBadcaseSince(String tenantId, Instant since) {
            return poolCount;
        }

        @Override
        public RelabelBatch findRelabelBatchSince(String tenantId, Instant since) {
            return batch;
        }

        boolean hasEvent(String step, String type, String contains) {
            return eventLog.stream().anyMatch(e -> step.equals(e.get("step"))
                    && type.equals(e.get("type"))
                    && String.valueOf(e.get("message")).contains(contains));
        }
    }

    /** 假 mos 原生链：只记「发起了什么」，不打网络；可指定某方法下一次抛错。 */
    private static final class FakeChain implements MosChainPort {
        int createCount, freezeCount, jobCount, evalSubmitCount, evalRunCount, depCount, startCount, activateCount;
        String freezeVersion = "2";
        Map<String, Object> lastJobParams;
        List<Map<String, String>> lastEvalCandidates;
        String failOnce;

        private void maybeFail(String key) {
            if (key.equals(failOnce)) {
                failOnce = null;
                throw new ApiException(502, key + " 模拟出站失败");
            }
        }

        @Override
        public String createDataset(String operator, String name) {
            maybeFail("createDataset");
            createCount++;
            return "ds_fw1";
        }

        @Override
        public String freezeDataset(String operator, String dsId) {
            maybeFail("freezeDataset");
            freezeCount++;
            return freezeVersion;
        }

        @Override
        public String submitTrainingJob(String operator, String datasetId, String method,
                                        Map<String, Object> params) {
            maybeFail("submitTrainingJob");
            jobCount++;
            lastJobParams = params;
            return "job_x1";
        }

        @Override
        public String submitEval(String operator, String name, String datasetId,
                                 List<Map<String, String>> candidates, int limit) {
            maybeFail("submitEval");
            evalSubmitCount++;
            lastEvalCandidates = candidates;
            return "ev_x1";
        }

        @Override
        public void runEval(String operator, String evalId) {
            maybeFail("runEval");
            evalRunCount++;
        }

        @Override
        public String registerDeployment(String operator, String modelExtId, String notes) {
            maybeFail("registerDeployment");
            depCount++;
            return "dep_x1";
        }

        @Override
        public void startDeployment(String operator, String depId) {
            maybeFail("startDeployment");
            startCount++;
        }

        @Override
        public void activateDeployment(String operator, String depId) {
            maybeFail("activateDeployment");
            activateCount++;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String disabledReason() {
            return "";
        }
    }

    /** FW-1 推送桩：进环=报「已推 5 条、版本 0（未冻结）」。 */
    private static final class FakePusher extends AnnotationPushService {
        int pushCount;

        FakePusher() {
            super("http://mos.invalid", "svc", "pw", 1500, new JdbcTemplate());
        }

        @Override
        public PushReport push(String tenantCode, String operator, String sourceDesc, String dsId,
                               DorisQueryService.Result result, String qColumn, String aColumn,
                               String refColumn) {
            pushCount++;
            return new PushReport(dsId, "0", 5, 0, 0, 5);
        }
    }

    private static final class FixedSettings extends OrchestratorSettings {
        String annotationGate = "MANUAL";
        String evalGate = "MANUAL";
        double evalMinScore = 7.0;
        boolean autoRenew = true;

        FixedSettings() {
            super(new JdbcTemplate());
        }

        @Override
        public String annotationGate() {
            return annotationGate;
        }

        @Override
        public String evalGate() {
            return evalGate;
        }

        @Override
        public double evalMinScore() {
            return evalMinScore;
        }

        @Override
        public boolean autoRenew() {
            return autoRenew;
        }
    }

    /** FW-2 观察面假世界：job/eval 状态 + 台账产物行，随测试推进可变。 */
    private static final class World {
        String jobStatus = "running";
        String evalStatus = "running";
        Double evalScore;          // null=分数还没写回

        private static Map<String, Object> watch(String kind, String extId, String status) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("extId", extId);
            m.put("status", status);
            return m;
        }

        List<Map<String, Object>> watches() {
            List<Map<String, Object>> out = new ArrayList<>();
            out.add(watch("job", "job_x1", jobStatus));
            out.add(watch("eval", "ev_x1", evalStatus));
            return out;
        }

        List<Map<String, Object>> ledgerRows() {
            if (evalScore == null && !("finished".equals(jobStatus))) {
                return List.of();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("extId", "model_x1");
            row.put("jobExtId", "job_x1");
            row.put("name", "fw-model");
            row.put("evalScore", evalScore);
            return List.of(row);
        }
    }

    private MemDao dao;
    private FakeChain chain;
    private FakePusher pusher;
    private FixedSettings settings;
    private World world;
    private MosFlywheelService flywheel;
    private BadcaseCollectorService collector;
    private RelabelTriggerService trigger;
    private FlywheelOrchestrator orch;

    private FlywheelOrchestrator build() {
        dao = new MemDao();
        chain = new FakeChain();
        pusher = new FakePusher();
        settings = new FixedSettings();
        world = new World();
        flywheel = mock(MosFlywheelService.class);
        when(flywheel.watches(anyString(), anyBoolean())).thenAnswer(i -> world.watches());
        when(flywheel.ledger(anyString())).thenAnswer(i -> world.ledgerRows());
        DorisQueryService doris = mock(DorisQueryService.class);
        when(doris.query(any(), anyString(), anyInt())).thenReturn(new DorisQueryService.Result(
                List.of("question", "answer"), List.of(List.of("q1", "a1")), 1, 100, 3, "builtin"));
        collector = mock(BadcaseCollectorService.class);
        trigger = mock(RelabelTriggerService.class);
        orch = new FlywheelOrchestrator(dao, settings, chain, flywheel, pusher, doris, collector, trigger);
        return orch;
    }

    private static Map<String, Object> querySpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("sql", "select question, answer from demo.qa");
        spec.put("qColumn", "question");
        spec.put("aColumn", "answer");
        spec.put("base", "qwen-base");
        spec.put("method", "sft");
        spec.put("chainExport", true);
        spec.put("chainQuant", true);
        spec.put("evalLimit", 5);
        spec.put("candidateBaseUrl", "http://fake/v1");
        return spec;
    }

    // ================= ① AUTO 全圈贯通 =================

    @Test
    void autoGatesRunWholeCircleAndRenewNextRun() {
        FlywheelOrchestrator o = build();
        settings.annotationGate = "AUTO";
        settings.evalGate = "AUTO";
        world.jobStatus = "finished";
        world.evalStatus = "done";
        world.evalScore = 8.5;
        dao.batch = new FlywheelRunDao.RelabelBatch(500, "badcase:t1:1", "PUSHED", "ds_new@0", 2);
        dao.poolCount = 3;

        FlywheelRunDao.Run run = o.startRun(TENANT, null, USER, querySpec());

        assertEquals("CLOSED", run.status(), "预设全绿时一圈就该闭合");
        assertEquals(FlywheelOrchestrator.STEP_RELABEL, run.currentStep());
        assertEquals("ds_fw1", run.dsId());
        assertEquals("2", run.datasetVersion(), "冻结版本必须记账（重试不重冻）");
        assertEquals("job_x1", run.jobExtId());
        assertEquals("ev_x1", run.evalExtId());
        assertEquals("model_x1", run.modelExtId());
        assertEquals("fw-model", run.modelName());
        assertEquals("dep_x1", run.deployRef());
        assertNotNull(run.deployTs(), "deploy_ts=badcase 观察窗下界");
        assertEquals(500L, run.relabelBatchId());
        assertEquals("ds_new@0", run.relabelTaskRef());
        assertTrue(run.lastError() == null || run.lastError().isEmpty(),
                "全程无故障：last_error 应为空，实际=" + run.lastError());

        assertEquals(1, pusher.pushCount, "表→标注集=FW-1 现成推送，不重写");
        assertEquals(1, chain.freezeCount);
        assertEquals(1, chain.jobCount);
        assertEquals(1, chain.evalSubmitCount);
        assertEquals(1, chain.depCount);
        assertEquals(1, chain.startCount);
        assertEquals(1, chain.activateCount, "activate=上岗动作，缺它 badcase 面还指向旧模型");
        assertEquals("qwen-base", chain.lastJobParams.get("base"));
        assertEquals(Boolean.TRUE, chain.lastJobParams.get("chain_export"));
        assertEquals("fw-model", chain.lastEvalCandidates.get(0).get("name"));
        assertEquals("http://fake/v1", chain.lastEvalCandidates.get(0).get("base_url"));

        assertNotNull(run.childRunId(), "auto_renew=true 必须续出下一圈");
        FlywheelRunDao.Run child = dao.findRun(run.childRunId(), TENANT);
        assertEquals("RUNNING", child.status());
        assertEquals(FlywheelOrchestrator.STEP_TABLE, child.currentStep(), "子圈留给下一 tick");
        assertEquals(run.id(), child.parentRunId());
        assertTrue(child.specJson().contains("\"mode\":\"RELABEL\""), "子圈=重标注数据集圈");
        assertTrue(child.specJson().contains("\"dsId\":\"ds_new\""), "ref ds@version 取 ds 部分");
        assertTrue(child.specJson().contains("qwen-base"), "训练参数继承父圈");

        for (String step : FlywheelOrchestrator.STEPS) {
            assertTrue(dao.eventLog.stream().anyMatch(e -> step.equals(e.get("step"))),
                    "七步每步都要有事件流水（泳道页数据面）: " + step);
        }
        assertTrue(dao.hasEvent(FlywheelOrchestrator.STEP_EVAL, "GATE", "自动放行"));
    }

    // ================= ② 人工闸 =================

    @Test
    void manualGatesHoldApproveReleasesAndRejectTerminates() {
        FlywheelOrchestrator o = build();
        FlywheelRunDao.Run run = o.startRun(TENANT, null, USER, querySpec());

        assertEquals("WAIT_GATE", run.status());
        assertEquals(FlywheelOrchestrator.STEP_ANNOTATE, run.currentStep());
        assertEquals(FlywheelOrchestrator.GATE_ANNOTATE, run.gateWait());
        assertEquals(0, chain.freezeCount, "闸前不许出环");

        // 点错闸=400（停在标注闸却调评测准入）
        final long runId = run.id();
        assertThrows(ApiException.class,
                () -> o.approveGate(runId, TENANT, FlywheelOrchestrator.GATE_EVAL, USER));

        run = o.approveGate(runId, TENANT, FlywheelOrchestrator.GATE_ANNOTATE, USER);
        assertEquals("RUNNING", run.status());
        assertEquals(FlywheelOrchestrator.STEP_TRAIN, run.currentStep());
        assertEquals(1, chain.freezeCount);
        assertEquals(1, chain.jobCount);
        assertTrue(dao.hasEvent(FlywheelOrchestrator.STEP_ANNOTATE, "GATE", "人工放行"));

        // 训练完成 → 评测发起；评测还没完，停在 EVAL 等下一圈
        world.jobStatus = "finished";
        o.advanceOpenRuns();
        run = dao.findRun(run.id(), TENANT);
        assertEquals(FlywheelOrchestrator.STEP_EVAL, run.currentStep());
        assertEquals("ev_x1", run.evalExtId());

        // 评测 done+出分（MANUAL）→ 停评测准入
        world.evalStatus = "done";
        world.evalScore = 9.0;
        o.advanceOpenRuns();
        run = dao.findRun(run.id(), TENANT);
        assertEquals("WAIT_GATE", run.status());
        assertEquals(FlywheelOrchestrator.GATE_EVAL, run.gateWait());

        // 无批次于手：放行后停在 BADCASE 等封批
        run = o.approveGate(run.id(), TENANT, FlywheelOrchestrator.GATE_EVAL, USER);
        assertEquals("RUNNING", run.status());
        assertEquals(FlywheelOrchestrator.STEP_BADCASE, run.currentStep());
        assertEquals("dep_x1", run.deployRef());

        // badcase 封批出环 → CLOSED + 续子圈
        dao.batch = new FlywheelRunDao.RelabelBatch(501, "badcase:t1:1", "PUSHED", "ds_new@0", 2);
        o.advanceOpenRuns();
        run = dao.findRun(run.id(), TENANT);
        assertEquals("CLOSED", run.status());
        assertNotNull(run.childRunId());
    }

    @Test
    void rejectGateTerminatesRunWithoutTouchingChain() {
        FlywheelOrchestrator o = build();
        FlywheelRunDao.Run run = o.startRun(TENANT, null, USER, querySpec());
        assertEquals("WAIT_GATE", run.status());
        final long runId = run.id();

        run = o.rejectGate(runId, TENANT, FlywheelOrchestrator.GATE_ANNOTATE, USER, "标注质量差");

        assertEquals("FAILED", run.status());
        assertTrue(run.lastError().contains("标注质量差"));
        assertEquals(0, chain.freezeCount, "拒绝=终止，不发起后续动作");
        assertTrue(dao.hasEvent(FlywheelOrchestrator.STEP_ANNOTATE, "GATE", "人工拒绝"));

        assertThrows(ApiException.class, () -> o.approveGate(runId, TENANT,
                FlywheelOrchestrator.GATE_ANNOTATE, USER), "已终止的圈不能再放行");
    }

    // ================= ③ AUTO≠无条件放行 =================

    @Test
    void autoEvalGateStillStopsBelowMinLine() {
        FlywheelOrchestrator o = build();
        settings.annotationGate = "AUTO";
        settings.evalGate = "AUTO";
        settings.evalMinScore = 7.0;
        world.jobStatus = "finished";
        world.evalStatus = "done";
        world.evalScore = 6.2;

        FlywheelRunDao.Run run = o.startRun(TENANT, null, USER, querySpec());

        assertEquals("WAIT_GATE", run.status());
        assertEquals(FlywheelOrchestrator.GATE_EVAL, run.gateWait());
        assertEquals(0, chain.depCount, "低分不许自动上岗");
        assertTrue(dao.hasEvent(FlywheelOrchestrator.STEP_EVAL, "GATE", "低于自动放行线"));
    }

    // ================= ④ 重试安全 / 故障旁路 =================

    @Test
    void jobFailureTerminatesRun() {
        FlywheelOrchestrator o = build();
        settings.annotationGate = "AUTO";
        world.jobStatus = "failed";

        FlywheelRunDao.Run run = o.startRun(TENANT, null, USER, querySpec());

        assertEquals("FAILED", run.status());
        assertEquals(FlywheelOrchestrator.STEP_TRAIN, run.currentStep());
        assertTrue(run.lastError().contains("failed"));
    }

    @Test
    void retryAfterPartialFailureNeverRefreezes() {
        FlywheelOrchestrator o = build();
        settings.annotationGate = "AUTO";
        chain.failOnce = "submitTrainingJob";

        // startRun 里 job 出站炸：异常上抛（发起口不许静默），但 freeze 已记账
        assertThrows(ApiException.class, () -> o.startRun(TENANT, null, USER, querySpec()));
        assertEquals(1, chain.freezeCount);
        assertEquals("2", dao.raw(1).datasetVersion, "成功一步已记一步");
        assertEquals(FlywheelOrchestrator.STEP_ANNOTATE, dao.raw(1).currentStep);

        // 下一 tick：不重冻，只补没记上的 job
        o.advanceOpenRuns();
        assertEquals(1, chain.freezeCount, "重试圈绝不重复冻结升版");
        assertEquals(1, chain.jobCount);
        assertEquals(FlywheelOrchestrator.STEP_TRAIN, dao.raw(1).currentStep);
    }

    @Test
    void tickFailureRecordsErrorAndSelfHealsNextTick() {
        FlywheelOrchestrator o = build();
        chain.failOnce = "createDataset";

        assertThrows(ApiException.class, () -> o.startRun(TENANT, null, USER, querySpec()));
        assertEquals("RUNNING", dao.raw(1).status, "发起炸了圈还得在，不能吞掉记录");
        assertEquals(FlywheelOrchestrator.STEP_TABLE, dao.raw(1).currentStep);

        // 表段再炸一次（走 tick：异常必须被吃成 last_error + ERROR 事件，不放大）
        chain.failOnce = "createDataset";
        o.advanceOpenRuns();
        assertTrue(String.valueOf(dao.raw(1).lastError).contains("createDataset"));
        assertTrue(dao.hasEvent(FlywheelOrchestrator.STEP_TABLE, "ERROR", "推进失败"));

        o.advanceOpenRuns();
        assertEquals(FlywheelOrchestrator.STEP_ANNOTATE, dao.raw(1).currentStep, "下一圈自愈");
    }

    // ================= 发起校验 / 直达模式 =================

    @Test
    void startRunValidatesSpec() {
        FlywheelOrchestrator o = build();
        assertThrows(ApiException.class, () -> o.startRun(TENANT, null, USER, null));
        assertThrows(ApiException.class, () -> o.startRun(TENANT, null, USER, Map.of()));
        ApiException e = assertThrows(ApiException.class, () -> o.startRun(TENANT, null, USER,
                Map.of("sql", "select 1")));
        assertTrue(e.getMessage().contains("qColumn"));
        assertEquals(0, dao.runs.size(), "校验不过不许留圈");
    }

    @Test
    void relabelSpecGoesStraightToAnnotateWithoutPush() {
        FlywheelOrchestrator o = build();
        FlywheelRunDao.Run run = o.startRun(TENANT, null, USER, Map.of("dsId", "ds_re"));

        assertEquals("WAIT_GATE", run.status());
        assertEquals("ds_re", run.dsId());
        assertEquals(0, chain.createCount, "给了 dsId 不再建集");
        assertEquals(0, pusher.pushCount, "RELABEL 圈不过表段");
    }

    @Test
    void autoRenewFalseClosesWithoutChild() {
        FlywheelOrchestrator o = build();
        settings.annotationGate = "AUTO";
        settings.evalGate = "AUTO";
        settings.autoRenew = false;
        world.jobStatus = "finished";
        world.evalStatus = "done";
        world.evalScore = 9.0;
        dao.batch = new FlywheelRunDao.RelabelBatch(502, "badcase:t1:1", "PUSHED", "ds_new@0", 2);

        FlywheelRunDao.Run run = o.startRun(TENANT, null, USER, querySpec());

        assertEquals("CLOSED", run.status());
        assertNull(run.childRunId(), "auto_renew=false 只闭不续");
        assertEquals(1, dao.runs.size());
    }

    // ================= 手动催一圈 =================

    @Test
    void manualPumpDrivesAllStagesViaExistingMethods() {
        build();
        when(flywheel.pollOnce()).thenReturn(7);
        when(collector.collectOnce()).thenReturn(new BadcaseCollectorService.CollectStats(10, 2, 1, 0, 99));
        when(trigger.triggerOnce()).thenReturn(1);

        Map<String, Object> out = orch.manualPump();

        assertEquals(7, out.get("polled"), "FW-2 轮询=现成方法");
        assertEquals(2, out.get("badcaseInserted"), "FW-3 采集=现成方法");
        assertEquals(1, out.get("relabelBatches"), "FW-3 封批=现成方法");
        assertEquals(0, out.get("runsAdvanced"), "无进行中圈时为 0");
    }
}
