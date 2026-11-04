package com.lab.dataworks.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.mosbridge.AnnotationPushService;
import com.lab.dataworks.mosbridge.MosFlywheelService;
import com.lab.dataworks.query.DorisQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REQ-FW-4 编排状态机：一条 {@link FlywheelRunDao.Run}（V200 flywheel_run）串起
 * 表→标注任务→训练 job→评测→部署→badcase 池→下一圈 relabel。
 *
 * <p>口径（卡面 03:03）：编排以 mos 原生链为主——本类只做三件事：</p>
 * <ol>
 *   <li><b>发起</b>：到点就调现成动作（FW-1 push / {@link MosChainPort} 冻结+job+eval+deploy /
 *       FW-2 trackJob·trackEval / FW-3 tick 观察），不重写链内步骤；</li>
 *   <li><b>记账</b>：每步落 flywheel_run + 事件流水（泳道页数据面）；</li>
 *   <li><b>闸门</b>：只保留「标注完成」「评测准入」两个人工闸，可配 AUTO 自动放行
 *       （评测 AUTO 仍受 eval_min_score 约束：低分转人工，不放行）。</li>
 * </ol>
 *
 * <p>重试安全 = 「每成功一步记一步」：freeze/job/eval/deploy 各自成功后立即落库，
 * 下一圈只补没记上的动作（对方 job 有查重、freeze 后版本非 '0' 即跳过冻结）。
 * 单圈异常不抛出调度线程（FlywheelJob 同口径）：记 last_error + ERROR 事件，下一圈自愈。</p>
 */
@Service
public class FlywheelOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlywheelOrchestrator.class);

    public static final String STEP_TABLE = "TABLE";
    public static final String STEP_ANNOTATE = "ANNOTATE";
    public static final String STEP_TRAIN = "TRAIN";
    public static final String STEP_EVAL = "EVAL";
    public static final String STEP_DEPLOY = "DEPLOY";
    public static final String STEP_BADCASE = "BADCASE";
    public static final String STEP_RELABEL = "RELABEL";
    public static final List<String> STEPS =
            List.of(STEP_TABLE, STEP_ANNOTATE, STEP_TRAIN, STEP_EVAL, STEP_DEPLOY, STEP_BADCASE, STEP_RELABEL);

    public static final String GATE_ANNOTATE = "ANNOTATE";
    public static final String GATE_EVAL = "EVAL";

    private final FlywheelRunDao dao;
    private final OrchestratorSettings settings;
    private final MosChainPort chain;
    private final MosFlywheelService flywheel;
    private final AnnotationPushService pusher;
    private final DorisQueryService doris;
    private final BadcaseCollectorService collector;
    private final RelabelTriggerService trigger;
    private final ObjectMapper json = new ObjectMapper();

    public FlywheelOrchestrator(FlywheelRunDao dao, OrchestratorSettings settings, MosChainPort chain,
                                MosFlywheelService flywheel, AnnotationPushService pusher,
                                DorisQueryService doris, BadcaseCollectorService collector,
                                RelabelTriggerService trigger) {
        this.dao = dao;
        this.settings = settings;
        this.chain = chain;
        this.flywheel = flywheel;
        this.pusher = pusher;
        this.doris = doris;
        this.collector = collector;
        this.trigger = trigger;
    }

    // ================= 发起 =================

    /**
     * 新开一圈。mode=QUERY：sql+qColumn+aColumn 必填，先跑 FW-1 入环；
     * mode=RELABEL（父圈续出的子圈）：dsId 必填，直接进标注段。
     */
    public FlywheelRunDao.Run startRun(String tenantId, Long projectId, String operator,
                                       Map<String, Object> spec) {
        if (spec == null) {
            throw ApiException.badRequest("spec 不能为空（sql+qColumn/aColumn，或 dsId 二选一）");
        }
        String sql = text(spec, "sql");
        String dsId = text(spec, "dsId");
        if (sql == null && dsId == null) {
            throw ApiException.badRequest("必须给 sql（选表入环）或 dsId（重标注数据集直达）");
        }
        if (sql != null && (text(spec, "qColumn") == null || text(spec, "aColumn") == null)) {
            throw ApiException.badRequest("sql 模式必须带 qColumn/aColumn（契约 §3.1 映射列）");
        }
        Map<String, Object> clean = new LinkedHashMap<>(spec);
        clean.put("mode", sql != null ? "QUERY" : "RELABEL");
        String specJson;
        try {
            specJson = json.writeValueAsString(clean);
        } catch (Exception e) {
            throw ApiException.badRequest("spec 序列化失败：" + e.getMessage());
        }
        long id = dao.insertRun(tenantId, projectId, longOf(spec.get("parentRunId")), specJson, operator);
        dao.emit(id, STEP_TABLE, "INFO", "开圈 mode=" + clean.get("mode"), null);
        FlywheelRunDao.Run run = dao.findRun(id, tenantId);
        advance(run, operator);
        return dao.findRun(id, tenantId);
    }

    public FlywheelRunDao.Run requireRun(long runId, String tenantId) {
        FlywheelRunDao.Run run = dao.findRun(runId, tenantId);
        if (run == null) {
            throw new ApiException(404, "第 " + runId + " 圈不存在（或不属于当前租户）");
        }
        return run;
    }

    // ================= 闸门 =================

    /**
     * 人工放行质量闸（标注完成/评测准入）。只有停在对应闸上的圈可放行。
     *
     * <p>放行=直接执行该闸的动作段（同 AUTO 路径同一代码），不能回头再走
     * {@code doAnnotate/doEval}——那里按 MANUAL 配置会再次置回 WAIT_GATE，人工点了个寂寞。</p>
     */
    public FlywheelRunDao.Run approveGate(long runId, String tenantId, String gate, String operator) {
        FlywheelRunDao.Run run = requireRun(runId, tenantId);
        requireWaitedGate(run, gate);
        dao.emit(run.id(), run.currentStep(), "GATE", "人工放行 " + gateLabel(gate) + "（" + operator + "）", null);
        dao.transition(run.id(), "RUNNING", run.currentStep(), null, null);
        FlywheelRunDao.Run r = dao.findRun(runId, tenantId);
        r = GATE_ANNOTATE.equals(gate) ? passAnnotate(r, operator) : passEval(r, operator);
        if ("RUNNING".equals(r.status())) {
            advance(r, operator);
        }
        return dao.findRun(runId, tenantId);
    }

    /** 人工拒绝=终止本圈（不做「拒绝后自动重标」这类链内动作，那是下一圈的事）。 */
    public FlywheelRunDao.Run rejectGate(long runId, String tenantId, String gate, String operator, String reason) {
        FlywheelRunDao.Run run = requireRun(runId, tenantId);
        requireWaitedGate(run, gate);
        String why = reason == null || reason.isBlank() ? "未填原因" : reason.trim();
        dao.transition(run.id(), "FAILED", run.currentStep(), null, "闸门拒绝：" + why);
        dao.emit(run.id(), run.currentStep(), "GATE",
                "人工拒绝 " + gateLabel(gate) + "（" + operator + "）：" + why, null);
        return dao.findRun(runId, tenantId);
    }

    private void requireWaitedGate(FlywheelRunDao.Run run, String gate) {
        if (!GATE_ANNOTATE.equals(gate) && !GATE_EVAL.equals(gate)) {
            throw ApiException.badRequest("闸门只认 ANNOTATE / EVAL（收到：" + gate + "）");
        }
        if (!"WAIT_GATE".equals(run.status()) || !gate.equals(run.gateWait())) {
            throw ApiException.badRequest("第 " + run.id() + " 圈当前没停在「" + gateLabel(gate)
                    + "」闸上（状态=" + run.status() + "，等待=" + run.gateWait() + "）");
        }
    }

    // ================= 推进 =================

    /** 定时/手动各调一次：把全部 RUNNING 的圈尽量往前推（一圈内可连过多个 AUTO 段）。 */
    public int advanceOpenRuns() {
        List<FlywheelRunDao.Run> runs = dao.openRuns();
        int progressed = 0;
        for (FlywheelRunDao.Run run : runs) {
            try {
                advance(run, run.createdBy() == null ? "system" : run.createdBy());
                progressed++;
            } catch (Exception e) {
                // 旁路编排不能一抖一死（FW-3 同口径）：记错误、保持原步，下一圈重试
                log.warn("flywheel 圈 {} 推进失败：{}", run.id(), e.toString());
                dao.updateRun(run.id(), errPatch(e));
                dao.emit(run.id(), run.currentStep(), "ERROR", "推进失败（下一圈重试）：" + e.getMessage(), null);
            }
        }
        return progressed;
    }

    /** 手动催一整圈（回归/排障口）：FW-2 轮询 + FW-3 采集/封批 + 编排推进，全走现成方法。 */
    public Map<String, Object> manualPump() {
        int polled = flywheel.pollOnce();
        BadcaseCollectorService.CollectStats stats = collector.collectOnce();
        int batches = trigger.triggerOnce();
        int progressed = advanceOpenRuns();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("polled", polled);
        out.put("badcaseInserted", stats.inserted());
        out.put("relabelBatches", batches);
        out.put("runsAdvanced", progressed);
        return out;
    }

    /** 一圈推进循环：按 current_step 逐段执行；某段没有产生步进（等外部状态/缺件）就退出等下一圈。 */
    void advance(FlywheelRunDao.Run run, String operator) {
        FlywheelRunDao.Run r = run;
        while ("RUNNING".equals(r.status())) {
            String before = r.currentStep();
            switch (r.currentStep()) {
                case STEP_TABLE -> r = doTable(r, operator);
                case STEP_ANNOTATE -> r = doAnnotate(r, operator);
                case STEP_TRAIN -> r = doTrain(r, operator);
                case STEP_EVAL -> r = doEval(r, operator);
                case STEP_DEPLOY -> r = doDeploy(r, operator);
                case STEP_BADCASE -> r = doBadcase(r, operator);
                case STEP_RELABEL -> r = doRelabel(r, operator);
                default -> {
                    return;
                }
            }
            if (r.currentStep().equals(before)) {
                break; // 本段没有步进（等轮询/等封批/等台账写回），下一圈再看
            }
        }
    }

    // ---------- 各段 ----------

    private FlywheelRunDao.Run doTable(FlywheelRunDao.Run run, String operator) {
        Map<String, Object> spec = parseSpec(run);
        String dsId = text(spec, "dsId");
        if ("RELABEL".equals(spec.get("mode"))) {
            if (dsId == null) {
                throw ApiException.badRequest("RELABEL 圈缺 dsId（父圈续出的重标注数据集）");
            }
            FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
            p.dsId = dsId;
            dao.updateRun(run.id(), p);
            dao.emit(run.id(), STEP_TABLE, "INFO", "重标注数据集直达：ds=" + dsId, null);
            dao.transition(run.id(), "RUNNING", STEP_ANNOTATE, null, null);
            return dao.findRun(run.id(), run.tenantId());
        }
        if (dsId == null) {
            dsId = chain.createDataset(operator, "fw-run-" + run.id());
        }
        String sql = text(spec, "sql");
        int maxRows = intOf(spec.get("maxRows"), 1000);
        DorisQueryService.Result result = doris.query(run.projectId(), sql, maxRows);
        AnnotationPushService.PushReport rep = pusher.push(run.tenantId(), operator, sql, dsId, result,
                text(spec, "qColumn"), text(spec, "aColumn"), text(spec, "refColumn"));
        FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
        p.dsId = dsId;
        p.datasetVersion = rep.version();
        dao.updateRun(run.id(), p);
        dao.emit(run.id(), STEP_TABLE, "INFO",
                "表→标注集 ds=" + dsId + " version=" + rep.version() + " inserted=" + rep.inserted(), null);
        dao.transition(run.id(), "RUNNING", STEP_ANNOTATE, null, null);
        return dao.findRun(run.id(), run.tenantId());
    }

    private FlywheelRunDao.Run doAnnotate(FlywheelRunDao.Run run, String operator) {
        Map<String, Object> spec = parseSpec(run);
        boolean night = nightMode(spec);
        if (!"AUTO".equals(settings.annotationGate()) && !night) {
            dao.transition(run.id(), "WAIT_GATE", STEP_ANNOTATE, GATE_ANNOTATE, null);
            dao.emit(run.id(), STEP_ANNOTATE, "GATE", "等待人工确认标注完成（配置 MANUAL）", null);
            return dao.findRun(run.id(), run.tenantId());
        }
        dao.emit(run.id(), STEP_ANNOTATE, "GATE", "标注完成闸自动放行（"
                + (night ? "夜间学习圈强制 AUTO" : "配置 AUTO") + "）", null);
        return passAnnotate(run, operator);
    }

    /** 闸门 A 放行动作：冻结（FW-1 台账口径读回版本）→ 发起训练 → 交 FW-2 轮询。 */
    private FlywheelRunDao.Run passAnnotate(FlywheelRunDao.Run run, String operator) {
        Map<String, Object> spec = parseSpec(run);
        String dsId = run.dsId() == null ? text(spec, "dsId") : run.dsId();
        if (dsId == null) {
            throw ApiException.badRequest("本圈没有数据集，无法冻结出卷");
        }
        if (run.jobExtId() == null) {
            // 冻结幂等：版本已不是 '0'/空 = 已冻结过（重试圈不重复升版）
            if (run.datasetVersion() == null || "0".equals(run.datasetVersion())) {
                String ver = chain.freezeDataset(operator, dsId);
                FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
                p.datasetVersion = ver;
                dao.updateRun(run.id(), p);
                dao.emit(run.id(), STEP_ANNOTATE, "INFO", "冻结出卷 version=" + ver, null);
            }
            Map<String, Object> params = new LinkedHashMap<>();
            String base = text(spec, "base");
            if (base != null) {
                params.put("base", base);
            }
            if (boolOf(spec.get("chainExport"))) {
                params.put("chain_export", true);
            }
            if (boolOf(spec.get("chainQuant"))) {
                params.put("chain_quant", true);
            }
            String method = text(spec, "method") == null ? "sft" : text(spec, "method");
            String jobId = chain.submitTrainingJob(operator, dsId, method, params);
            FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
            p.jobExtId = jobId;
            dao.updateRun(run.id(), p);
            flywheel.trackJob(jobId, run.tenantId(), run.projectId(), run.createdBy());
            dao.emit(run.id(), STEP_ANNOTATE, "INFO", "训练已发起 job=" + jobId + "（原生链自动走阶段）", null);
        }
        dao.transition(run.id(), "RUNNING", STEP_TRAIN, null, null);
        return dao.findRun(run.id(), run.tenantId());
    }

    private FlywheelRunDao.Run doTrain(FlywheelRunDao.Run run, String operator) {
        Map<String, Object> watch = findWatch(run.tenantId(), "job", run.jobExtId());
        if (watch == null) {
            // 跟踪行丢了（手工清库等）就补登记，不另造第二条轮询
            flywheel.trackJob(run.jobExtId(), run.tenantId(), run.projectId(), run.createdBy());
            return run;
        }
        String status = String.valueOf(watch.get("status"));
        if ("failed".equals(status) || "stopped".equals(status)) {
            dao.transition(run.id(), "FAILED", STEP_TRAIN, null, "训练终态 " + status);
            dao.emit(run.id(), STEP_TRAIN, "ERROR", "训练未成功（" + status + "），本圈终止", null);
            return dao.findRun(run.id(), run.tenantId());
        }
        if (!"finished".equals(status)) {
            return run; // 还在跑：下一圈再看
        }
        Map<String, Object> model = ledgerRowByJob(run.tenantId(), run.jobExtId());
        if (model == null) {
            if (run.lastError() == null) {
                FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
                p.lastError = "训练 finished 但台账还没有产物行（等 auto:fw2 登记）";
                dao.updateRun(run.id(), p);
                dao.emit(run.id(), STEP_TRAIN, "WARN", p.lastError, null);
            }
            return dao.findRun(run.id(), run.tenantId());
        }
        String extId = String.valueOf(model.get("extId"));
        String name = model.get("name") == null ? extId : String.valueOf(model.get("name"));
        FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
        p.modelExtId = extId;
        p.modelName = name;
        p.lastError = "";
        dao.updateRun(run.id(), p);
        if (run.evalExtId() == null) {
            Map<String, Object> spec = parseSpec(run);
            String candBase = text(spec, "candidateBaseUrl");
            List<Map<String, String>> candidates = List.of(Map.of(
                    "name", name,
                    "base_url", candBase == null ? "" : candBase));
            String evalId = chain.submitEval(operator, "fw-eval-run-" + run.id(), run.dsId(),
                    candidates, intOf(spec.get("evalLimit"), 10));
            chain.runEval(operator, evalId);
            FlywheelRunDao.RunPatch ep = new FlywheelRunDao.RunPatch();
            ep.evalExtId = evalId;
            dao.updateRun(run.id(), ep);
            flywheel.trackEval(evalId, List.of(Map.of("candidate", name, "extId", extId)),
                    run.tenantId(), run.projectId(), run.createdBy());
            dao.emit(run.id(), STEP_TRAIN, "INFO", "产物登记 " + extId + "，评测已发起 ev=" + evalId, null);
        }
        dao.transition(run.id(), "RUNNING", STEP_EVAL, null, null);
        return dao.findRun(run.id(), run.tenantId());
    }

    private FlywheelRunDao.Run doEval(FlywheelRunDao.Run run, String operator) {
        Map<String, Object> watch = findWatch(run.tenantId(), "eval", run.evalExtId());
        if (watch == null) {
            return run;
        }
        String status = String.valueOf(watch.get("status"));
        if ("failed".equals(status)) {
            dao.transition(run.id(), "FAILED", STEP_EVAL, null, "评测失败");
            dao.emit(run.id(), STEP_EVAL, "ERROR", "评测失败，本圈终止", null);
            return dao.findRun(run.id(), run.tenantId());
        }
        if (!"done".equals(status)) {
            return run;
        }
        Map<String, Object> model = ledgerRow(run.tenantId(), run.modelExtId());
        Object score = model == null ? null : model.get("evalScore");
        if (score == null) {
            if (run.lastError() == null) {
                FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
                p.lastError = "评测 done 但分数还没写回台账";
                dao.updateRun(run.id(), p);
                dao.emit(run.id(), STEP_EVAL, "WARN", p.lastError, null);
            }
            return dao.findRun(run.id(), run.tenantId());
        }
        double avg = ((Number) score).doubleValue();
        Map<String, Object> spec = parseSpec(run);
        boolean night = nightMode(spec);
        String gate = night ? "AUTO" : settings.evalGate();
        // 夜间线取岗位配置（卡面「eval_min_score 用岗位配置」），缺省/非法回全局键
        double min = night ? nightEvalMinScore(spec, settings.evalMinScore()) : settings.evalMinScore();
        if ("AUTO".equals(gate) && avg >= min) {
            dao.emit(run.id(), STEP_EVAL, "GATE",
                    "评测准入自动放行（" + avg + " ≥ " + min + "）" + (night ? "·夜间学习圈" : ""),
                    Map.of("score", avg));
            return passEval(run, operator);
        }
        dao.transition(run.id(), "WAIT_GATE", STEP_EVAL, GATE_EVAL, null);
        dao.emit(run.id(), STEP_EVAL, "GATE", "AUTO".equals(gate)
                ? "评测 " + avg + " 低于自动放行线 " + min + "，转人工"
                : "等待评测准入（配置 MANUAL，分数=" + avg + "）", Map.of("score", avg));
        return dao.findRun(run.id(), run.tenantId());
    }

    /** 闸门 B 放行动作：登记部署 → 起实例 → 上岗（activate 后 badcase 采集面才指向新模型）。 */
    private FlywheelRunDao.Run doDeploy(FlywheelRunDao.Run run, String operator) {
        return passEval(run, operator);
    }

    private FlywheelRunDao.Run passEval(FlywheelRunDao.Run run, String operator) {
        String depId = run.deployRef();
        if (depId == null) {
            depId = chain.registerDeployment(operator, run.modelExtId(),
                    "flywheel-run-" + run.id());
            FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
            p.deployRef = depId;
            dao.updateRun(run.id(), p);
            dao.emit(run.id(), STEP_DEPLOY, "INFO", "登记部署 dep=" + depId, null);
        }
        chain.startDeployment(operator, depId);
        chain.activateDeployment(operator, depId);
        FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
        p.deployTs = Instant.now();
        p.lastError = "";
        dao.updateRun(run.id(), p);
        dao.emit(run.id(), STEP_DEPLOY, "INFO", "模型上岗（activate）" + run.modelName(), null);
        dao.transition(run.id(), "RUNNING", STEP_BADCASE, null, null);
        return dao.findRun(run.id(), run.tenantId());
    }

    private FlywheelRunDao.Run doBadcase(FlywheelRunDao.Run run, String operator) {
        Instant since = run.deployTs();
        if (since == null) {
            throw new IllegalStateException("BADCASE 段没有 deploy_ts（状态机异常）");
        }
        int pool = dao.countBadcaseSince(run.tenantId(), since);
        FlywheelRunDao.RelabelBatch batch = dao.findRelabelBatchSince(run.tenantId(), since);
        if (batch == null) {
            if (run.lastError() == null && pool > 0) {
                FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
                p.lastError = "池中 " + pool + " 条，未达封批阈值";
                dao.updateRun(run.id(), p);
            }
            return run;
        }
        FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
        p.relabelBatchId = batch.id();
        p.lastError = "";
        dao.updateRun(run.id(), p);
        dao.emit(run.id(), STEP_BADCASE, "INFO",
                "badcase 封批 " + batch.ledgerKey() + "（" + batch.itemCount() + " 条，池计 " + pool + "）",
                Map.of("poolCount", pool, "batchId", batch.id()));
        dao.transition(run.id(), "RUNNING", STEP_RELABEL, null, null);
        return dao.findRun(run.id(), run.tenantId());
    }

    private FlywheelRunDao.Run doRelabel(FlywheelRunDao.Run run, String operator) {
        FlywheelRunDao.RelabelBatch batch = run.relabelBatchId() == null ? null
                : dao.findRelabelBatchSince(run.tenantId(),
                run.deployTs() == null ? Instant.EPOCH : run.deployTs());
        if (batch == null || !"PUSHED".equals(batch.status()) || batch.mosTaskRef() == null) {
            return run; // 等 FW-3 出环（桥接不可用时保持 READY，口径不变）
        }
        FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
        p.relabelTaskRef = batch.mosTaskRef();
        dao.updateRun(run.id(), p);
        dao.emit(run.id(), STEP_RELABEL, "INFO",
                "回环出环 " + batch.ledgerKey() + " → " + batch.mosTaskRef(), null);
        if (settings.autoRenew() && run.childRunId() == null) {
            String newDs = batch.mosTaskRef().split("@", 2)[0];
            Map<String, Object> childSpec = new LinkedHashMap<>();
            childSpec.put("mode", "RELABEL");
            childSpec.put("dsId", newDs);
            inherit(childSpec, parseSpec(run), "base", "method", "chainExport", "chainQuant",
                    "evalLimit", "candidateBaseUrl");
            long childId = dao.insertRun(run.tenantId(), run.projectId(), run.id(),
                    toJson(childSpec), run.createdBy());
            dao.emit(childId, STEP_TABLE, "INFO", "下一圈自动续出（auto_renew）", null);
            FlywheelRunDao.RunPatch cp = new FlywheelRunDao.RunPatch();
            cp.childRunId = childId;
            dao.updateRun(run.id(), cp);
            dao.emit(run.id(), STEP_RELABEL, "INFO", "已开下一圈 run=" + childId + "（ds=" + newDs + "）", null);
        }
        dao.transition(run.id(), "CLOSED", STEP_RELABEL, null, null);
        dao.emit(run.id(), STEP_RELABEL, "INFO", "本圈闭环", null);
        return dao.findRun(run.id(), run.tenantId());
    }

    // ================= 查询面（控制器/页面用） =================

    public List<Map<String, Object>> listRuns(String tenantId, boolean openOnly) {
        return dao.listRuns(tenantId, openOnly, 100);
    }

    public List<Map<String, Object>> runEvents(long runId, String tenantId) {
        return dao.events(runId, tenantId);
    }

    // ================= 小工具 =================

    private Map<String, Object> findWatch(String tenantId, String kind, String extId) {
        if (extId == null) {
            return null;
        }
        for (Map<String, Object> w : flywheel.watches(tenantId, false)) {
            if (kind.equals(w.get("kind")) && extId.equals(w.get("extId"))) {
                return w;
            }
        }
        return null;
    }

    private Map<String, Object> ledgerRowByJob(String tenantId, String jobExtId) {
        if (jobExtId == null) {
            return null;
        }
        for (Map<String, Object> row : flywheel.ledger(tenantId)) {
            if (jobExtId.equals(row.get("jobExtId"))) {
                return row;
            }
        }
        return null;
    }

    private Map<String, Object> ledgerRow(String tenantId, String extId) {
        if (extId == null) {
            return null;
        }
        for (Map<String, Object> row : flywheel.ledger(tenantId)) {
            if (extId.equals(row.get("extId"))) {
                return row;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSpec(FlywheelRunDao.Run run) {
        try {
            return json.readValue(run.specJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("圈 " + run.id() + " spec 不是合法 JSON", e);
        }
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("spec 序列化失败", e);
        }
    }

    private static void inherit(Map<String, Object> to, Map<String, Object> from, String... keys) {
        for (String k : keys) {
            if (from.get(k) != null) {
                to.put(k, from.get(k));
            }
        }
    }

    private static FlywheelRunDao.RunPatch errPatch(Exception e) {
        FlywheelRunDao.RunPatch p = new FlywheelRunDao.RunPatch();
        p.lastError = e.getMessage() == null ? e.toString() : e.getMessage();
        return p;
    }

    private static String gateLabel(String gate) {
        return GATE_ANNOTATE.equals(gate) ? "标注完成" : "评测准入";
    }

    /**
     * REQ-NIGHT-LEARN-1 夜间学习圈标记：开圈 spec 带 {@code night=true}（cc-ctrld 夜环按岗位发起）。
     *
     * <p>夜圈两个闸按「夜间强制 auto」走，但<b>评测准入线照旧生效</b>——分不够就停在评测闸
     * 等晨复核：全自动 ≠ 无条件放行，质量闸不能因为「夜里没人看」就被绕过去。</p>
     *
     * <p>口径只走 run spec，<b>不改全局 {@code flywheel.orch.*}</b>：夜环若去写闸门设置，白天的
     * 人工闸会被悄悄改成 AUTO。auto_renew 续出的下一圈也不继承 night（{@link #inherit} 的键表
     * 里没有它），因此夜里开的圈不会把白天那圈也变成免检。</p>
     */
    static boolean nightMode(Map<String, Object> spec) {
        return spec != null && boolOf(spec.get("night"));
    }

    /** 夜间评测准入线：岗位配置 {@code spec.evalMinScore} 优先，缺省或非法回全局键。 */
    static double nightEvalMinScore(Map<String, Object> spec, double fallback) {
        Object raw = spec == null ? null : spec.get("evalMinScore");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(String.valueOf(raw).trim());
            if (!(d >= 0) || d > 10) {
                return fallback;      // 越界（比如把百分制当 0–10 配）不拿它判准入，回全局线
            }
            return d;
        } catch (NumberFormatException e) {
            return fallback;          // 圈已在夜里跑完，不为一个配错的数字把整圈判死
        }
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intOf(Object v, int dflt) {
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("数值参数不可解析：" + v);
        }
    }

    private static Long longOf(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("数字参数不可解析：" + v);
        }
    }

    private static boolean boolOf(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v).trim());
    }
}
