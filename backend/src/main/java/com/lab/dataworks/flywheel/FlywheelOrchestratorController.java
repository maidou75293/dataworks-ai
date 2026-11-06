package com.lab.dataworks.flywheel;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REQ-FW-4 编排入口：开圈/看圈/过闸/催一圈/编排设置/badcase 信号回写。
 *
 * <p>闸门=权限点 mos:flywheel（V170；语义「中台发起+登记回环」正好覆盖编排——
 * 编排 = 把发起/记账/闸门串起来，不另立权限点）。by-id 一律带租户条件
 * （{@link FlywheelRunDao#findRun}，越权=404，by-id-tenant-audit 口径）。</p>
 *
 * <p>{@code POST /badcase-signal} 是 V180 注释承诺、FW-3 卡面边界未做的「部署服务/助手回写」
 * 接点：只补 usage_query_event 的信号列写入，判定与封批仍归 FW-3 现成代码。</p>
 */
@RestController
@RequestMapping("/api/flywheel")
@RequireAtom("mos:flywheel")
public class FlywheelOrchestratorController {

    private final FlywheelOrchestrator orchestrator;
    private final FlywheelRunDao dao;
    private final OrchestratorSettings settings;
    private final SessionService sessions;
    private final TenantGuard guard;
    private final JdbcTemplate jdbc;
    private final FlywheelNightService night;

    public FlywheelOrchestratorController(FlywheelOrchestrator orchestrator, FlywheelRunDao dao,
                                          OrchestratorSettings settings, SessionService sessions,
                                          TenantGuard guard, JdbcTemplate jdbc,
                                          FlywheelNightService night) {
        this.orchestrator = orchestrator;
        this.dao = dao;
        this.settings = settings;
        this.sessions = sessions;
        this.guard = guard;
        this.jdbc = jdbc;
        this.night = night;
    }

    // ---------- 圈 ----------

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "true") boolean openOnly,
                                          HttpServletRequest http) {
        return orchestrator.listRuns(TenantScope.of(http), openOnly);
    }

    @PostMapping("/runs")
    public Map<String, Object> start(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest http) {
        if (body == null) {
            throw ApiException.badRequest("请求体不能为空（sql+qColumn/aColumn 或 dsId）");
        }
        Long projectId = longOf(body.get("projectId"));
        if (projectId != null) {
            guard.requireProject(projectId);
        }
        String operator = user(http);
        FlywheelRunDao.Run run = orchestrator.startRun(TenantScope.of(http), projectId,
                operator, body);
        sessions.audit(operator, "FLYWHEEL_RUN_START", "run=" + run.id(),
                "step=" + run.currentStep() + " status=" + run.status()
                        + " ds=" + run.dsId(), "SUCCESS", AuthInterceptor.clientIp(http));
        return runView(run);
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> detail(@PathVariable long id, HttpServletRequest http) {
        FlywheelRunDao.Run run = orchestrator.requireRun(id, TenantScope.of(http));
        Map<String, Object> out = new LinkedHashMap<>(runView(run));
        out.put("events", orchestrator.runEvents(id, TenantScope.of(http)));
        return out;
    }

    @PostMapping("/runs/{id}/gate")
    public Map<String, Object> gate(@PathVariable long id,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest http) {
        String gate = upper(body == null ? null : str(body.get("gate")));
        String decision = lower(body == null ? null : str(body.get("decision")));
        if (gate == null || decision == null) {
            throw ApiException.badRequest("gate（ANNOTATE/EVAL）与 decision（approve/reject）必填");
        }
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw ApiException.badRequest("decision 只认 approve/reject（收到：" + body.get("decision") + "）");
        }
        String tenant = TenantScope.of(http);
        String operator = user(http);
        FlywheelRunDao.Run run;
        if ("approve".equals(decision)) {
            run = orchestrator.approveGate(id, tenant, gate, operator);
        } else {
            run = orchestrator.rejectGate(id, tenant, gate, operator, str(body.get("reason")));
        }
        sessions.audit(operator, "FLYWHEEL_GATE", "run=" + id + " gate=" + gate,
                decision + " → status=" + run.status(), "SUCCESS", AuthInterceptor.clientIp(http));
        return runView(run);
    }

    /** 手动催一整圈（回归/排障）：轮询+采集+封批+推进一次跑齐。 */
    @PostMapping("/tick")
    public Map<String, Object> tick(HttpServletRequest http) {
        Map<String, Object> out = orchestrator.manualPump();
        sessions.audit(user(http), "FLYWHEEL_TICK", "-", String.valueOf(out));
        return out;
    }

    // ---------- 设置 ----------

    @GetMapping("/settings")
    public Map<String, Object> settingsView() {
        return settings.snapshot();
    }

    @PutMapping("/settings")
    public Map<String, Object> settingsUpdate(@RequestBody(required = false) Map<String, Object> body,
                                              HttpServletRequest http) {
        if (body == null || body.isEmpty()) {
            throw ApiException.badRequest("请求体不能为空（键值对，白名单见设置页）");
        }
        for (Map.Entry<String, Object> e : body.entrySet()) {
            settings.write(e.getKey(), str(e.getValue()));
        }
        sessions.audit(user(http), "FLYWHEEL_SETTINGS", "PUT", body.keySet().toString());
        return settings.snapshot();
    }

    // ---------- badcase 信号回写（V180 承诺的写入点） ----------

    @PostMapping("/badcase-signal")
    public Map<String, Object> badcaseSignal(@RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest http) {
        if (body == null) {
            throw ApiException.badRequest("请求体不能为空（question 必填）");
        }
        String question = str(body.get("question"));
        if (question == null || question.isBlank()) {
            throw ApiException.badRequest("question 不能为空（V180 采集面按它成池，缺问题=不可成池）");
        }
        String feedback = normalizeFeedback(body.get("feedback"));
        BigDecimal confidence = parseConfidence(body.get("confidence"));
        int resultRows = parseResultRows(body.get("resultRows"));
        Long projectId = longOf(body.get("projectId"));
        if (projectId != null) {
            guard.requireProject(projectId);
        }
        String tenant = TenantScope.of(http);
        String username = user(http);
        Long elapsed = longOf(body.get("elapsedMs"));
        if (body.get("elapsedMs") != null && elapsed == null) {
            throw ApiException.badRequest("elapsedMs 必须是整数（收到：" + body.get("elapsedMs") + "）");
        }
        long elapsedMs = elapsed == null ? 0L : elapsed;
        jdbc.update("""
                insert into platform.usage_query_event
                    (tenant_id, project_id, username, result_rows, elapsed_ms,
                     question, feedback, confidence, answer_model)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenant, projectId, username, resultRows, elapsedMs,
                question.trim(), feedback, confidence, str(body.get("answerModel")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recorded", true);
        out.put("note", "下一采集圈判定入池（规则/阈值见 flywheel.badcase.* 设置）");
        return out;
    }

    // ---------- 学习（REQ-NIGHT-LEARN-1） ----------
    //
    // 权限沿用类级 mos:flywheel（卡面「权限复用」），不另立点位；岗位键=标注项目号，
    // 平台项目号走 guard.requireProject（by-id 租户收口，同 /runs 口径）。

    /** 夜环取岗位清单（含未启用的，环侧按 enabled 自过滤），页面回显同源。 */
    @GetMapping("/night/posts")
    public List<Map<String, Object>> nightPosts(HttpServletRequest http) {
        return night.listPosts(TenantScope.of(http));
    }

    /** 标注项目页保存岗位配置：非法窗/准入分/列映射当场 400 报当前值（别等夜里记假异常）。 */
    @PutMapping("/night/posts/{labelProjectId}")
    public Map<String, Object> nightPostUpsert(@PathVariable long labelProjectId,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest http) {
        if (body == null) {
            throw ApiException.badRequest("请求体不能为空（enabled/windowStart/windowEnd/evalMinScore/featureSql/…）");
        }
        Long platformProjectId = longOf(body.get("platformProjectId"));
        if (platformProjectId != null) {
            guard.requireProject(platformProjectId);
        }
        String operator = user(http);
        Map<String, Object> post = night.upsertPost(TenantScope.of(http), labelProjectId, body, operator);
        sessions.audit(operator, "FLYWHEEL_NIGHT_POST", "post=" + labelProjectId,
                "enabled=" + post.get("enabled") + " window=" + post.get("windowStart") + "-"
                        + post.get("windowEnd") + " min=" + post.get("evalMinScore"),
                "SUCCESS", AuthInterceptor.clientIp(http));
        return post;
    }

    /** 夜环写一岗一夜晨报（业务键 upsert：重报覆盖，一夜一岗只一行）。 */
    @PostMapping("/night/report")
    public Map<String, Object> nightReportWrite(@RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest http) {
        if (body == null) {
            throw ApiException.badRequest("请求体不能为空（nightDate+labelProjectId 必给）");
        }
        Map<String, Object> out = night.writeReport(TenantScope.of(http), body);
        sessions.audit(user(http), "FLYWHEEL_NIGHT_REPORT",
                "night=" + out.get("nightDate") + " post=" + body.get("labelProjectId"),
                "runs=" + body.get("runs") + " passed=" + body.get("passed")
                        + " abnormal=" + body.get("abnormal"),
                "SUCCESS", AuthInterceptor.clientIp(http));
        return out;
    }

    /** 主页晨报聚合：date 缺省=本租户最新一夜，days=往回带几夜（上限 31）。 */
    @GetMapping("/night/reports")
    public Map<String, Object> nightReports(@RequestParam(required = false) String date,
                                            @RequestParam(defaultValue = "1") int days,
                                            HttpServletRequest http) {
        return night.reports(TenantScope.of(http), date, days);
    }

    /** badcase TOP 计数（晨报第三项数据源）；sinceMs 必传，无界全表扫=拒。 */
    @GetMapping("/badcase/top")
    public List<Map<String, Object>> badcaseTop(@RequestParam(required = false) Long sinceMs,
                                                @RequestParam(required = false) Long projectId,
                                                @RequestParam(defaultValue = "10") int limit,
                                                HttpServletRequest http) {
        if (projectId != null) {
            guard.requireProject(projectId);
        }
        return night.badcaseTop(TenantScope.of(http), projectId, sinceMs, limit);
    }

    // ---------- 校验（包级静态：不起 Spring 的回归用例直接钉，同 FW-1 requireReadOnly 口径） ----------

    /** feedback 只认 UP/DOWN（大小写不敏感），返回大写或 null；其余显式 400。 */
    static String normalizeFeedback(Object raw) {
        String s = lower(raw);
        if (s == null) {
            return null;
        }
        if (!"down".equals(s) && !"up".equals(s)) {
            throw ApiException.badRequest("feedback 只认 UP/DOWN（收到：" + raw + "）");
        }
        return s.toUpperCase(Locale.ROOT);
    }

    static BigDecimal parseConfidence(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        BigDecimal d;
        try {
            d = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("confidence 必须是数字（收到：" + raw + "）");
        }
        if (d.doubleValue() < 0 || d.doubleValue() > 1) {
            throw ApiException.badRequest("confidence 允许 0–1（收到：" + raw + "）");
        }
        return d;
    }

    /**
     * result_rows 列 NOT NULL default 0 只对「省略列」生效；0=「无答案」坏样本信号——
     * 缺省按「有答案」记（1），要造 NO_ANSWER 样本必须显式传 0，不默认成坏样本。
     */
    static int parseResultRows(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return 1;
        }
        long n;
        try {
            n = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("resultRows 必须是非负整数（收到：" + raw + "）");
        }
        if (n < 0) {
            throw ApiException.badRequest("resultRows 必须是非负整数（收到：" + raw + "）");
        }
        return (int) n;
    }

    // ---------- 小工具 ----------

    /**
     * BUG-FW6-2：deployTsMs/createdMs 曾在视图漏输出，七步 e2e STEP4 与前端「上岗」时间戳全瞎。
     * 包级静态让不起 Spring 的回归用例直钉键集合（口径同 normalizeFeedback）——凡
     * api/flywheel.ts FlywheelRun 声明的字段，这里必须齐。
     */
    static Map<String, Object> runView(FlywheelRunDao.Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("status", r.status());
        m.put("currentStep", r.currentStep());
        m.put("gateWait", r.gateWait());
        m.put("dsId", r.dsId());
        m.put("datasetVersion", r.datasetVersion());
        m.put("jobExtId", r.jobExtId());
        m.put("evalExtId", r.evalExtId());
        m.put("modelExtId", r.modelExtId());
        m.put("modelName", r.modelName());
        m.put("deployRef", r.deployRef());
        m.put("relabelBatchId", r.relabelBatchId());
        m.put("relabelTaskRef", r.relabelTaskRef());
        m.put("childRunId", r.childRunId());
        m.put("parentRunId", r.parentRunId());
        m.put("lastError", r.lastError());
        m.put("createdBy", r.createdBy());
        m.put("createdMs", r.createdAt() == null ? null : r.createdAt().toEpochMilli());
        m.put("deployTsMs", r.deployTs() == null ? null : r.deployTs().toEpochMilli());
        return m;
    }

    private String user(HttpServletRequest request) {
        Object v = request.getAttribute("username");
        return v == null || String.valueOf(v).isBlank() ? "system" : String.valueOf(v);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String lower(Object v) {
        String s = str(v);
        return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String upper(Object v) {
        String s = str(v);
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static Long longOf(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("数字参数不可解析：" + v);
        }
    }
}
