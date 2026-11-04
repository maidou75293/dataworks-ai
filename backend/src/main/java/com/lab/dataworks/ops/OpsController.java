package com.lab.dataworks.ops;

import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.prefect.PrefectBridgeClient;
import com.lab.dataworks.workflow.WorkflowMutationService;
import com.lab.dataworks.workflow.WorkflowTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.Locale;
import com.lab.dataworks.common.LikePattern;

@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    private final JdbcTemplate jdbc;
    private final PrefectBridgeClient prefect;
    private final OpsSyncService sync;
    private final com.lab.dataworks.auth.SessionService sessions;
    /** 与「触发/补数/发布」同一把工作流锁：同一业务日期不能并发起两个 run。 */
    private final WorkflowMutationService mutations;
    /**
     * D26：重跑落点按「所属版本快照」解析（复用/重建无调度部署），与手工触发、
     * Prefect 侧判重共用同一个服务；快照不可用时才降级回退当前生效部署。
     */
    private final com.lab.dataworks.workflow.WorkflowTriggerService triggers;

    public OpsController(JdbcTemplate jdbc, PrefectBridgeClient prefect, OpsSyncService sync,
                         com.lab.dataworks.auth.SessionService sessions,
                         WorkflowMutationService mutations,
                         com.lab.dataworks.workflow.WorkflowTriggerService triggers) {
        this.jdbc = jdbc;
        this.prefect = prefect;
        this.sync = sync;
        this.sessions = sessions;
        this.mutations = mutations;
        this.triggers = triggers;
    }

    private static final Set<String> STATES = Set.of(
            "queued", "running", "success", "failed", "cancelled", "upstream_failed", "skipped");

    @GetMapping("/instances")
    public List<Map<String, Object>> instances(@RequestParam(required = false) Long workflowId,
                                               @RequestParam(required = false) Long projectId,
                                               @RequestParam(required = false) String state,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "50") int limit) {
        // 不要在列表请求里同步 Prefect：后台每 15 秒已经同步，页面 10 秒轮询会放大成
        // 每次列表都发上百个 Prefect 请求。
        StringBuilder sql = new StringBuilder("""
                select i.id, i.workflow_id, i.dag_id, i.run_id, i.biz_date, i.state,
                       i.started_at, i.ended_at, i.created_at, i.error_message,
                       -- 同一工作流 + 同一业务日期正在跑/排队的实例：前端据此禁用「重跑」，
                       -- 后端 rerun 也会再校验一次，避免两个 run 同时写同一批目标表
                       -- updated_at 只在同步成功时刷新：Prefect 里已经不存在的
                       -- 僵尸 queued（例如几周前的残留）不能永久禁用「重跑」
                       (select x.id from platform.wf_instance x
                         where x.workflow_id = i.workflow_id
                           and x.biz_date is not distinct from i.biz_date
                           and x.state in ('queued', 'running')
                           and x.updated_at > now() - interval '10 minutes'
                         order by x.created_at desc limit 1) as active_instance_id,
                       w.name as workflow_name, w.code as workflow_code,
                       p.code as project_code, p.name as project_name
                from platform.wf_instance i
                join platform.workflow w on w.id = i.workflow_id
                join platform.project p on p.id = w.project_id
                where p.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        // 归属按项目判定：wf_instance.tenant_id 是派生列，历史行可能还是默认值
        args.add(com.lab.dataworks.auth.TenantScope.current());
        if (workflowId != null) {
            sql.append(" and i.workflow_id = ?");
            args.add(workflowId);
        }
        if (projectId != null) {
            sql.append(" and w.project_id = ?");
            args.add(projectId);
        }
        if (state != null && !state.isBlank()) {
            // 支持 state=running,queued，让「运行中」卡片与列表口径一致
            List<String> states = new ArrayList<>();
            for (String part : state.split(",")) {
                String value = part.trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty()) {
                    continue;
                }
                if (!STATES.contains(value)) {
                    throw ApiException.badRequest("不支持的状态: " + value);
                }
                states.add(value);
            }
            if (!states.isEmpty()) {
                sql.append(" and i.state in (").append(String.join(",",
                        Collections.nCopies(states.size(), "?"))).append(")");
                args.addAll(states);
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = LikePattern.contains(keyword);
            sql.append(" and (w.name ilike ? or w.code ilike ? or i.dag_id ilike ? or i.run_id ilike ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by i.created_at desc limit ?");
        args.add(Math.max(1, Math.min(500, limit)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/instances/{id}")
    public Map<String, Object> instance(@PathVariable long id) {
        // P1-2：详情也按项目归属过滤，不存在与别人的实例统一 404
        ownedInstance(id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select i.*, w.name as workflow_name, w.code as workflow_code,
                       p.code as project_code, p.name as project_name
                from platform.wf_instance i
                join platform.workflow w on w.id = i.workflow_id
                join platform.project p on p.id = w.project_id
                where i.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("实例不存在: " + id);
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("tasks", jdbc.queryForList("""
                select id, task_code, task_name, state, try_number, started_at, ended_at,
                       updated_at, error_message
                from platform.task_instance where wf_instance_id = ? order by id
                """, id));
        return out;
    }

    @GetMapping("/instances/{id}/tasks/{taskCode}/log")
    public Map<String, Object> log(@PathVariable long id, @PathVariable String taskCode) {
        // 任务日志常含 SQL、表名、报错堆栈：跨租户读等于直接看别人的作业内容
        ownedInstance(id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select try_number, log_text, prefect_task_run_id from platform.task_instance
                where wf_instance_id = ? and task_code = ?
                """, id, taskCode);
        int tryNumber = rows.isEmpty() ? 1 : Math.max(1, ((Number) rows.get(0).get("try_number")).intValue());
        String cached = rows.isEmpty() || rows.get(0).get("log_text") == null
                ? null : String.valueOf(rows.get(0).get("log_text"));
        String taskRunId = rows.isEmpty() || rows.get(0).get("prefect_task_run_id") == null
                ? null : String.valueOf(rows.get(0).get("prefect_task_run_id"));
        List<Map<String, Object>> instances = jdbc.queryForList(
                "select run_id from platform.wf_instance where id = ?", id);
        String runId = instances.isEmpty() || instances.get(0).get("run_id") == null
                ? null : String.valueOf(instances.get(0).get("run_id"));
        if (taskRunId != null && runId != null) {
            try {
                String text = prefect.listLogs(runId, taskRunId).stream()
                        .map(l -> String.format("%s %-5s %s", l.timestamp(), level(l.level()), l.message()))
                        .collect(java.util.stream.Collectors.joining("\n"));
                jdbc.update("""
                        update platform.task_instance set log_text = ?, updated_at = now()
                        where wf_instance_id = ? and task_code = ?
                        """, text, id, taskCode);
                return Map.of("taskCode", taskCode, "tryNumber", tryNumber,
                        "log", text.isBlank() ? "（Prefect 暂无日志）" : text,
                        "cached", false);
            } catch (Exception e) {
                // fall through to cached log
            }
        }
        return Map.of("taskCode", taskCode, "tryNumber", tryNumber,
                "log", cached == null ? "（暂无日志）" : cached,
                "cached", cached != null);
    }

    private String level(int level) {
        return switch (level) {
            case 10 -> "DEBUG";
            case 20 -> "INFO";
            case 30 -> "WARN";
            case 40 -> "ERROR";
            case 50 -> "FATAL";
            default -> String.valueOf(level);
        };
    }

    /**
     * 取消运行中的实例（v450 P1-8）。以前只能等它自己结束，慢 SQL/死循环会占住
     * 同业务日期的触发窗口。
     */
    @PostMapping("/instances/{id}/cancel")
    @RequireAtom("ops:stop")
    public Map<String, Object> cancel(@PathVariable long id,
                                      jakarta.servlet.http.HttpServletRequest http) {
        ownedInstance(id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select state, run_id, dag_id from platform.wf_instance where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("实例不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        String state = String.valueOf(row.get("state"));
        if (!activeDb(state)) {
            throw ApiException.badRequest("实例已经结束（" + state + "），不能取消");
        }
        String runId = row.get("run_id") == null ? null : String.valueOf(row.get("run_id"));
        if (runId == null || runId.isBlank()) {
            throw ApiException.badRequest("实例还没有对应的 Prefect Run，无法取消");
        }
        try {
            prefect.cancelRun(runId);
        } catch (RuntimeException e) {
            throw new ApiException(502, "取消请求未送达执行器：" + e.getMessage());
        }
        sessions.audit(String.valueOf(http.getAttribute("username")), "INSTANCE_CANCEL",
                "i" + id, "runId=" + runId + " 原状态=" + state, "SUCCESS",
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        // 立刻同步一次，前端不用等下一轮 15 秒轮询就能看到状态变化
        try {
            sync.syncInstance(String.valueOf(row.get("dag_id")), runId);
        } catch (Exception e) {
            log.warn("cancel: sync instance {} failed: {}", id, e.getMessage());
        }
        String now = jdbc.queryForObject(
                "select state from platform.wf_instance where id = ?", String.class, id);
        return Map.of("ok", true, "id", id, "state", now == null ? state : now);
    }

    /**
     * 重跑的「查冲突 → 调 Prefect → 落库」不是原子的：双击/两人同时点会各看到一个空闲，
     * 同一天跑两遍。
     *
     * <p>以前用 {@code synchronized} 锁整个 Controller，但「触发/补数」用的是
     * WorkflowMutationService 的工作流锁 —— 两把锁互不排斥，触发与重跑并发时仍然
     * 都能查到一个空闲，同一业务日期起两个 run 同时写同一批目标表。现在重跑拿同一把
     * 工作流锁（且与发布/删除互斥）：第二个请求进锁后能看到第一个刚落的实例行，
     * 返回明确冲突。</p>
     */
    @PostMapping("/instances/{id}/rerun")
    @RequireAtom("ops:run")
    public Map<String, Object> rerun(@PathVariable long id,
                                     jakarta.servlet.http.HttpServletRequest http) {
        ownedInstance(id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select workflow_id, dag_id, run_id, biz_date from platform.wf_instance where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("实例不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        long workflowId = ((Number) row.get("workflow_id")).longValue();
        String deploymentId = String.valueOf(row.get("dag_id"));
        String oldRunId = String.valueOf(row.get("run_id"));
        String bizDate = row.get("biz_date") == null ? null : String.valueOf(row.get("biz_date"));
        if (bizDate == null) {
            bizDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        }
        String date = bizDate;
        return mutations.withLock(workflowId,
                () -> doRerun(id, workflowId, deploymentId, oldRunId, date, http));
    }

    /** 在「同一把工作流锁」内完成判重与建 run，与触发/补数串行。 */
    private Map<String, Object> doRerun(long id, long workflowId, String deploymentId,
                                        String oldRunId, String bizDate,
                                        jakarta.servlet.http.HttpServletRequest http) {
        // 先把这条实例从 Prefect 同步一次再判断：库里可能还写着 failed，Prefect 里
        // 它其实已经重新跑起来（或反之），只看库里的旧状态会误放行 / 误拦截。
        try {
            sync.syncInstance(deploymentId, oldRunId);
        } catch (Exception e) {
            log.warn("rerun: sync instance {} failed: {}", id, e.getMessage());
        }
        String state = jdbc.queryForObject(
                "select state from platform.wf_instance where id = ?", String.class, id);
        if (activeDb(state)) {
            throw ApiException.badRequest("实例正在" + stateLabel(state) + "，请等结束后再重跑");
        }

        // D26：发布新版本时同环境的旧 Prefect 部署会被删掉，实例行上的 dag_id 就成了
        // 死指针。拿它 createRun，bridge 回 500，客户端兜底成 502「Prefect Bridge 连接失败」，
        // 排查方向被带向网络，历史失败实例永久无法重跑。这里先按数据库事实把落点解析出来。
        OriginDeployment origin = originDeployment(workflowId, deploymentId);
        // D26 v2：历史实例重跑要执行「它自己那一版」——发布时 workflow_version.model_json
        // 已冻结版本快照，被替换的部署按快照重建（或复用上次重建的）来跑，
        // 而不是回退到当前生效版本（那是另一份代码）。只有快照都找不到的历史数据
        // （V41 之前 / version_no 对不上版本行）才降级为「回退当前生效部署 + 明说」。
        boolean snapshotUsable = origin != null && !origin.active()
                && origin.versionNo() > 0
                && triggers.hasVersionSnapshot(workflowId, origin.versionNo());
        // 只有降级路径才需要「当前生效部署」；只在该实例所属环境里找：
        // DEV 实例不能回退到 PROD 部署（反之同理）
        ActiveDeployment current = (origin == null || snapshotUsable)
                ? null : currentDeployment(workflowId, origin.env());
        RerunTarget target = resolveRerunTarget(deploymentId, origin, snapshotUsable, current);

        // 同一工作流 + 同一业务日期只允许一个 run 在跑：再起一个会同时写同一批
        // 目标表，状态同步和重试也会互相覆盖。这是上一版只判断「当前这一行」漏掉的。
        String blocker = activeRunBlocker(workflowId, bizDate, id, target.deploymentId(), oldRunId);
        if (blocker != null) {
            throw ApiException.badRequest(blocker);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("biz_date", bizDate);
        String effectiveDeployment = target.deploymentId();
        String note = target.note();
        PrefectBridgeClient.Run run;
        if (target.mode() == RerunMode.VIA_SNAPSHOT) {
            // 在锁内按快照定落点并创建 run：候选复用与重建都在服务里完成
            WorkflowTriggerService.SnapshotRun snapshot = triggers.rerunFromSnapshot(
                    workflowId, origin.env(), origin.versionNo(), params, bizDate, oldRunId,
                    String.valueOf(http.getAttribute("username")));
            effectiveDeployment = snapshot.deploymentId();
            note = snapshot.note();
            run = snapshot.run();
        } else {
            run = createRunOrExplain(target, params);
        }
        Long instanceId = jdbc.queryForObject("""
                insert into platform.wf_instance
                    (workflow_id, dag_id, run_id, biz_date, state, tenant_id)
                values (?, ?, ?, ?::date, 'queued',
                        (select tenant_id from platform.workflow where id = ?))
                on conflict (dag_id, run_id) do nothing
                returning id
                """, Long.class, workflowId, effectiveDeployment, run.flowRunId(), bizDate, workflowId);
        if (instanceId == null) {
            List<Long> existing = jdbc.queryForList("""
                    select id from platform.wf_instance where dag_id = ? and run_id = ?
                    """, Long.class, effectiveDeployment, run.flowRunId());
            instanceId = existing.isEmpty() ? -1L : existing.get(0);
        }
        sync.syncInstance(effectiveDeployment, run.flowRunId());
        // v450 P1-7：重试也要进审计（以前只有 TRIGGER 有记录）
        sessions.audit(String.valueOf(http.getAttribute("username")), "INSTANCE_RERUN",
                "i" + id, "newInstance=" + instanceId + " runId=" + run.flowRunId()
                        + " bizDate=" + bizDate + " deployment=" + effectiveDeployment
                        + (note == null ? "" : "（" + note + "）"), "SUCCESS",
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("deploymentId", effectiveDeployment);
        out.put("instanceId", instanceId);
        out.put("runId", run.flowRunId());
        out.put("bizDate", bizDate);
        if (note != null) {
            // 前端把这条 note 显示给用户：重跑用的是哪一版部署（快照重建 / 降级回退）
            out.put("note", note);
        }
        return out;
    }

    /** 实例 dag_id 对应的那一条部署记录（{@code workflow_deployment}）；查不到返回 null。 */
    private OriginDeployment originDeployment(long workflowId, String dagId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select env, version_no, active
                from platform.workflow_deployment
                where workflow_id = ? and prefect_deployment_id = ?
                order by deployed_at desc, id desc limit 1
                """, workflowId, dagId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Object version = row.get("version_no");
        return new OriginDeployment(String.valueOf(row.get("env")),
                version instanceof Number n ? n.intValue() : -1,
                Boolean.TRUE.equals(row.get("active")));
    }

    /**
     * 该工作流在指定环境当前生效的部署：先查部署表，再兼容 V43 之前写在版本行上的指针
     * （兼容分支与手工触发共用 {@link com.lab.dataworks.workflow.WorkflowTriggerService}）。
     */
    private ActiveDeployment currentDeployment(long workflowId, String env) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select prefect_deployment_id, version_no
                from platform.workflow_deployment
                where workflow_id = ? and env = ? and active and prefect_deployment_id is not null
                order by deployed_at desc, id desc limit 1
                """, workflowId, env);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            Object version = row.get("version_no");
            return new ActiveDeployment(String.valueOf(row.get("prefect_deployment_id")),
                    version instanceof Number n ? n.intValue() : -1);
        }
        com.lab.dataworks.workflow.WorkflowTriggerService.DeploymentRef ref =
                triggers.latestDeployment(workflowId, env);
        return ref == null ? null : new ActiveDeployment(ref.id(), -1);
    }

    /**
     * D26：bridge 对「部署不存在」回 500，被 {@link PrefectBridgeClient} 的兜底分支翻成
     * 502「Prefect Bridge 连接失败」。数据库里对不上号的 dag_id（V43 之前的历史行、
     * 或从 Prefect 侧手工起的 run）走这条路，此时按 bridge 可达性区分两种情况：
     * 可达 = 这一笔调用本身失败（部署已没了），报 409 给可执行的下一步；
     * 不可达 = 真故障，保持原来的 502（故障注入用例的口径不变）。
     */
    private PrefectBridgeClient.Run createRunOrExplain(RerunTarget target, Map<String, Object> params) {
        try {
            return prefect.createRun(target.deploymentId(), params);
        } catch (ApiException e) {
            if (e.getStatus() >= 500 && target.dagUnknownToPlatform() && prefect.reachable()) {
                throw ApiException.conflict("该实例所属的 Prefect 部署 " + target.deploymentId()
                        + " 已不可用（多见于发布新版本后旧部署被删除），且它不在平台当前部署记录里："
                        + "请确认该环境已发布生效版本后重新触发，或直接重跑最新实例");
            }
            throw e;
        }
    }

    /** D26：实例 dag_id 在部署表里的形状。 */
    record OriginDeployment(String env, int versionNo, boolean active) {
    }

    /** D26：某环境当前生效的部署（查询已按环境收口，所以不带 env 字段）。 */
    record ActiveDeployment(String deploymentId, int versionNo) {
    }

    /**
     * D26：重跑落点的决策方式。
     * <ul>
     *   <li>{@code IN_PLACE}：实例所属部署仍是本环境生效部署，原地重跑；</li>
     *   <li>{@code VIA_SNAPSHOT}：部署已被同环境新版本替换，但发布快照（workflow_version）
     *       还在 → 按**该实例自己那一版**的快照复用/重建无调度部署重跑，
     *       不回退到当前生效版本（历史实例执行的必须是它所属版本的定义）；</li>
     *   <li>{@code FALLBACK_CURRENT}：连版本行都找不到的历史数据（V41 之前），
     *       降级为回退当前生效部署，note 必须明说；</li>
     *   <li>{@code TRY_AS_IS}：dag_id 在部署表里对不上号（V43 前历史行、Prefect 侧手工 run），
     *       原样试一次，失败由 {@code createRunOrExplain} 按 bridge 可达性翻译。</li>
     * </ul>
     */
    enum RerunMode { IN_PLACE, VIA_SNAPSHOT, FALLBACK_CURRENT, TRY_AS_IS }

    /**
     * D26：重跑最终落到的部署。
     *
     * @param deploymentId       VIA_SNAPSHOT 时这里是实例原 dag 的占位（真实落点由
     *                           {@code rerunFromSnapshot} 在服务内解析后才确定）
     * @param note               非空表示换了落点，要告诉用户用的是哪一版
     * @param dagUnknownToPlatform 实例 dag_id 在部署表里查不到，只能原样试一次
     */
    record RerunTarget(RerunMode mode, String deploymentId, String note, boolean dagUnknownToPlatform) {
    }

    /**
     * D26：重跑落到哪个部署（纯决策，不碰数据库，便于用例覆盖全部分支）。
     *
     * <ol>
     *   <li>实例所属部署仍是本环境生效部署 → 原地重跑；</li>
     *   <li>所属部署已被同环境新版本替换且版本快照可用 → 按快照复用/重建部署重跑
     *       （主流做法：发布时冻结快照、重跑按快照；即使该环境已下线也能重跑）；</li>
     *   <li>快照不可用（V41 之前的历史数据、version_no 对不上版本行）→ 降级回退到
     *       该环境当前生效部署并在返回里明说；该环境已无生效部署 → 409 说清下一步，
     *       而不是把 bridge 的 500 原样抬成 502；</li>
     *   <li>dag_id 在部署表里查不到（历史/手工 run）→ 保持原 dag 直接试一次，
     *       失败由 {@code createRunOrExplain} 翻译。</li>
     * </ol>
     */
    static RerunTarget resolveRerunTarget(String instanceDagId, OriginDeployment origin,
                                          boolean snapshotUsable, ActiveDeployment current) {
        if (origin == null) {
            return new RerunTarget(RerunMode.TRY_AS_IS, instanceDagId, null, true);
        }
        if (origin.active()) {
            return new RerunTarget(RerunMode.IN_PLACE, instanceDagId, null, false);
        }
        if (snapshotUsable) {
            return new RerunTarget(RerunMode.VIA_SNAPSHOT, instanceDagId, null, false);
        }
        if (current == null) {
            throw ApiException.conflict("该实例所属部署已被新版本替换（" + origin.env() + " v"
                    + origin.versionNo() + "），该版本快照不可用（早期历史数据），且 " + origin.env()
                    + " 环境当前没有生效部署（已下线或从未发布）：请先重新发布该工作流，再重跑");
        }
        return new RerunTarget(RerunMode.FALLBACK_CURRENT, current.deploymentId(),
                "该实例所属部署（" + origin.env() + " v" + origin.versionNo()
                        + "）已被新版本替换，且该版本快照不可用，本次改用 " + origin.env()
                        + " 当前生效部署"
                        + (current.versionNo() > 0 ? " v" + current.versionNo() : ""),
                false);
    }

    /** 同工作流 + 同业务日期是否已有在跑/排队的实例；返回给用户看的拦截原因。 */
    private String activeRunBlocker(long workflowId, String bizDate, long excludeId,
                                    String deploymentId, String excludeRunId) {
        // updated_at 只在 Prefect 同步成功时刷新。历史遗留的 queued（Prefect 里
        // 早就没有这个 run）不能拦住重跑，所以只认 10 分钟内被同步过的行。
        // 判重 SQL 与「触发」共用（mutations.activeRun），避免两条入口口径漂移。
        Map<String, Object> active = mutations.activeRun(workflowId, bizDate, null, excludeId, 10);
        if (active != null) {
            return "工作流 " + bizDate + " 已有" + statePhrase(String.valueOf(active.get("state")))
                    + "的实例 #" + active.get("id") + "，请等它结束后再重跑";
        }
        // 库还没同步到的 run（比如直接从 Prefect 触发）：问一次 Prefect 兜底。
        // D26：Prefect 侧判重与「按快照重跑」的候选循环共用服务里的一份口径。
        return triggers.prefectActiveRunBlocker(deploymentId, bizDate, excludeRunId);
    }

    private static boolean activeDb(String state) {
        return "running".equalsIgnoreCase(state) || "queued".equalsIgnoreCase(state);
    }

    private static String stateLabel(String state) {
        if ("running".equalsIgnoreCase(state)) {
            return "运行";
        }
        if ("queued".equalsIgnoreCase(state)) {
            return "排队";
        }
        return state == null ? "" : state;
    }

    private static String statePhrase(String state) {
        if ("running".equalsIgnoreCase(state)) {
            return "运行中";
        }
        if ("queued".equalsIgnoreCase(state)) {
            return "排队中";
        }
        return state == null ? "" : state;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        // 概览数字同样按租户收口：否则 tenant_b 能看到默认租户的运行/失败总量
        String tenant = com.lab.dataworks.auth.TenantScope.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", jdbc.queryForObject(
                "select count(*) from platform.project where tenant_id = ?",
                Integer.class, tenant));
        out.put("workflows", jdbc.queryForObject("""
                select count(*) from platform.workflow w join platform.project p on p.id = w.project_id
                where p.tenant_id = ?
                """, Integer.class, tenant));
        out.put("running", jdbc.queryForObject("""
                select count(*) from platform.wf_instance i
                 join platform.workflow w on w.id = i.workflow_id
                 join platform.project p on p.id = w.project_id
                 where p.tenant_id = ? and i.state in ('queued','running')
                """, Integer.class, tenant));
        out.put("failed", jdbc.queryForObject("""
                select count(*) from platform.wf_instance i
                 join platform.workflow w on w.id = i.workflow_id
                 join platform.project p on p.id = w.project_id
                 where p.tenant_id = ? and i.state = 'failed'
                """, Integer.class, tenant));
        out.put("today", jdbc.queryForObject("""
                select count(*) from platform.wf_instance i
                 join platform.workflow w on w.id = i.workflow_id
                 join platform.project p on p.id = w.project_id
                 where p.tenant_id = ?
                   and i.created_at >= (date_trunc('day', now() at time zone 'Asia/Shanghai')
                                        at time zone 'Asia/Shanghai')
                """, Integer.class, tenant));
        out.put("qualityFailures", jdbc.queryForObject("""
                select count(*) from platform.quality_result r
                 join platform.quality_rule q on q.id = r.rule_id
                 join platform.project p on p.id = q.project_id
                 where p.tenant_id = ? and r.passed = false
                   -- 规则执行失败（exec_status=ERROR）不算质量失败，口径与告警中心一致
                   and r.exec_status = 'OK'
                   and r.biz_date >= ((now() at time zone 'Asia/Shanghai')::date - 7)
                """, Integer.class, tenant));
        return out;
    }

    /**
     * 实例归属：按 workflow → project → tenant 判定，取不到 404。
     * 不存在与不属于本租户返回同一个结果，避免探测。
     */
    private void ownedInstance(long id) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.wf_instance i
                 join platform.workflow w on w.id = i.workflow_id
                 join platform.project p on p.id = w.project_id
                 where i.id = ? and p.tenant_id = ?
                """, Long.class, id, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("实例不存在: " + id);
        }
    }

    @GetMapping("/prefect/health")
    public Object prefectHealth() {
        return prefect.health();
    }
}
