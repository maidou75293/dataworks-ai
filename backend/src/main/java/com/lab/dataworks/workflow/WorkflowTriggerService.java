package com.lab.dataworks.workflow;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.ops.OpsSyncService;
import com.lab.dataworks.prefect.PrefectBridgeClient;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.prefect.PrefectPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作流单次运行的唯一入口：手工触发、批量补数的每一天都走这里。
 *
 * <p>以前这段逻辑写在 {@code WorkflowController#doTrigger}；补数需要按日期复用同一条
 * 路径（而不是另写一套执行器），因此抽成服务。锁语义保持不变：查判重 → 等 Flow 文件
 * → Prefect createRun → 落 wf_instance，整个过程在 {@link WorkflowMutationService}
 * 的工作流锁内，与发布/下线/删除互斥。</p>
 */
@Service
public class WorkflowTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerService.class);

    private final JdbcTemplate jdbc;
    private final PrefectPublisher publisher;
    private final PrefectBridgeClient prefect;
    private final OpsSyncService sync;
    private final WorkflowMutationService mutations;
    /** D26：按版本发布快照重建部署时要复用发布同款渲染器。 */
    private final PrefectFlowRenderer renderer;
    /** TC7-G2：上游强规则未通过的工作流不允许创建新的运行。 */
    private final com.lab.dataworks.quality.QualityBlockService qualityBlocks;

    public WorkflowTriggerService(JdbcTemplate jdbc, PrefectPublisher publisher,
                                  PrefectBridgeClient prefect, OpsSyncService sync,
                                  WorkflowMutationService mutations,
                                  PrefectFlowRenderer renderer,
                                  com.lab.dataworks.quality.QualityBlockService qualityBlocks) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.prefect = prefect;
        this.sync = sync;
        this.mutations = mutations;
        this.renderer = renderer;
        this.qualityBlocks = qualityBlocks;
    }

    public record DeploymentRef(String id, String file, long publishedEpochMs) {
    }

    /**
     * 部署名由「工作流编码 + 版本 + 环境」决定。发布（WorkflowController）与
     * D26 的按快照重建共用这一份口径，避免两处公式漂移后重建出对不上号的部署。
     */
    public static String deploymentName(String workflowCode, String env, int versionNo) {
        String raw = workflowCode + "_v" + versionNo + "_" + env.toLowerCase(Locale.ROOT);
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * 指定环境当前生效的部署。先看 workflow_deployment，再兼容 V43 之前写在
     * workflow_version 上的指针；两者都没有就是「未发布」。
     */
    public DeploymentRef latestDeployment(long workflowId, String env) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.prefect_deployment_id, d.prefect_flow_file,
                       coalesce(extract(epoch from d.deployed_at) * 1000, 0) as published_ms
                from platform.workflow_deployment d
                where d.workflow_id = ? and d.env = ? and d.active
                  and d.prefect_deployment_id is not null
                order by d.deployed_at desc, d.id desc limit 1
                """, workflowId, env);
        if (rows.isEmpty()) {
            // 兼容 V43 之前未回填到的历史数据
            rows = jdbc.queryForList("""
                    select v.prefect_deployment_id, v.prefect_flow_file,
                           coalesce(extract(epoch from v.published_at) * 1000, 0) as published_ms
                    from platform.workflow_version v
                    where v.workflow_id = ?
                      and v.status = 'PUBLISHED'
                      and v.prefect_deployment_id is not null
                      and v.prefect_env = ?
                    order by v.version_no desc limit 1
                    """, workflowId, env);
        }
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new DeploymentRef(String.valueOf(row.get("prefect_deployment_id")),
                row.get("prefect_flow_file") == null ? null : String.valueOf(row.get("prefect_flow_file")),
                row.get("published_ms") == null ? 0L : ((Number) row.get("published_ms")).longValue());
    }

    /**
     * 按业务日期触发一次运行。调用方已完成租户校验与日期格式校验。
     *
     * @param kind {@code manual} / {@code backfill}，只用于返回值与审计可读性
     */
    public Map<String, Object> trigger(long workflowId, String env, String bizDate,
                                       Map<String, Object> params, String kind, String username) {
        return mutations.withLock(workflowId,
                () -> doTrigger(workflowId, env, bizDate, params, kind, username));
    }

    private Map<String, Object> doTrigger(long workflowId, String env, String bizDate,
                                          Map<String, Object> params, String kind, String username) {
        DeploymentRef deployment = latestDeployment(workflowId, env);
        if (deployment == null) {
            throw ApiException.badRequest("请先把该工作流发布到 " + env);
        }
        // 同一工作流+环境+业务日期只允许一个未结束实例：连续点两次触发会起两个
        // 写同一张目标表的 run。只拦「30 分钟内同步过」的活跃实例，避免 Prefect
        // 侧长期未开始的幽灵 run 永久占住业务日期（可在运行实例页处理）。
        // 判重 SQL 与「重跑」共用（mutations.activeRun），两边不会各自漂移。
        Map<String, Object> active = mutations.activeRun(workflowId, bizDate, deployment.id(), -1, 30);
        if (active != null) {
            throw ApiException.conflict("该业务日期已有运行中的实例 #" + active.get("id")
                    + "（" + active.get("state") + "），请等待结束，或到运行实例页重跑");
        }
        // TC7-G2：上游表的强规则当天没通过，就不给这个下游工作流创建运行。
        // 定时调度由 Prefect 直接建 run，不经过这里，生成的 Flow 开头还有一道同样的检查
        // （两处共用 QualityBlockService，口径不会漂移）。
        List<com.lab.dataworks.quality.QualityBlockService.Blocker> blocks =
                qualityBlocks.forWorkflow(workflowId, env, parseBizDate(bizDate));
        if (!blocks.isEmpty()) {
            String reasons = blocks.stream()
                    .map(com.lab.dataworks.quality.QualityBlockService.Blocker::message)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining("；"));
            throw ApiException.conflict("上游强规则未通过，本次不调度：" + reasons);
        }
        // 发布后 flow 文件由 ConfigMap 卷异步挂到 worker（25–60 秒）。
        // 由 bridge 读同一个卷做就绪探测，避免 exec（k8s 1.36 下 WebSocket 握手失败）
        // 和「一律等 60 秒」的固定等待。
        if (deployment.file() != null) {
            publisher.awaitFlowFile(deployment.file(), deployment.publishedEpochMs());
        }
        Map<String, Object> conf = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        conf.put("biz_date", bizDate);
        PrefectBridgeClient.Run run;
        try {
            run = prefect.createRun(deployment.id(), conf);
        } catch (RuntimeException e) {
            // 固定等待仍有极端情况（kubelet 同步慢）：把「文件不存在」翻译成可重试的 503，
            // 而不是让用户看到 500 和一段 Prefect 堆栈。
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.contains("FileNotFound") || detail.contains("No such file")) {
                throw new ApiException(503, "Flow 文件尚未同步到执行器，请稍后重试");
            }
            throw e;
        }
        String state = normalizeState(run.state());
        jdbc.update("""
                insert into platform.wf_instance
                    (workflow_id, dag_id, run_id, biz_date, state, tenant_id)
                values (?, ?, ?, ?::date, ?,
                        (select tenant_id from platform.workflow where id = ?))
                on conflict (dag_id, run_id) do update set
                    state = excluded.state, updated_at = now()
                """, workflowId, deployment.id(), run.flowRunId(), bizDate, state, workflowId);
        // 运行已经真的创建了：同步失败不能把接口变成 502，否则用户重试会
        // 在同一业务日期上再起一个 run、重复写目标表。由实例同步定时任务兜底。
        try {
            sync.syncInstance(deployment.id(), run.flowRunId());
        } catch (RuntimeException e) {
            log.warn("trigger created run but sync failed workflow={} run={}: {}",
                    workflowId, run.flowRunId(), e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dagId", deployment.id());
        out.put("deploymentId", deployment.id());
        out.put("runId", run.flowRunId());
        out.put("flowRunId", run.flowRunId());
        out.put("bizDate", bizDate);
        out.put("kind", kind);
        return out;
    }

    /**
     * 业务日期解析。调用方已校验过格式，这里仍显式报错而不是抛
     * {@code DateTimeParseException}（500），否则补数里一个坏日期会变成服务端错误。
     */
    private static java.time.LocalDate parseBizDate(String bizDate) {
        if (bizDate == null || bizDate.isBlank()) {
            throw ApiException.badRequest("业务日期不能为空");
        }
        try {
            return java.time.LocalDate.parse(bizDate.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("业务日期格式应为 yyyy-MM-dd: " + bizDate);
        }
    }

    private String normalizeState(String state) {
        if (state == null) {
            return "queued";
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "success";
            case "FAILED", "CRASHED" -> "failed";
            case "CANCELLED" -> "cancelled";
            case "RUNNING" -> "running";
            default -> "queued";
        };
    }

    /**
     * D26：发布新版本时同环境的旧 Prefect 部署会被删除，历史实例行上的 {@code dag_id}
     * 成了死指针；拿它 createRun，bridge 回 500，被客户端兜成 502「连接失败」。
     *
     * <p>主流做法（与本项目「数据服务发布快照」同一口径）：发布时已在
     * {@code workflow_version.model_json} 冻结了版本快照，重跑就按**该实例所属版本**
     * 执行，而不是悄悄换成当前生效版本。本方法在该实例自己的环境里：</p>
     * <ol>
     *   <li>先按 (workflow, env, version) 找最近一次留下的部署 id 逐个试跑——上一次按快照
     *       重跑已重建过部署的直接原地复用，一次成功即返回；</li>
     *   <li>全部不可用（原部署已被发布删除）→ 用版本快照重新渲染 flow，
     *       重建一个**无调度**部署（active=false，不参与该环境的生效指针、
     *       不挂 cron，不会让旧版本在调度 tick 里复活），登记进 workflow_deployment 后重跑；</li>
     *   <li>每个候选部署创建前先做 Prefect 侧判重（{@link #prefectActiveRunBlocker}），
     *       同一业务日期有未同步的活跃 run 就拒绝，不重复起第二个。</li>
     * </ol>
     *
     * <p>调用方必须持有 {@link WorkflowMutationService#withLock}（与发布/触发/下线互斥），
     * 本方法与它们共用同一把工作流锁内的事实：不会有并发发布把刚重建的部署名占掉半程。</p>
     *
     * @return 实际创建了 run 的部署与给用户看的说明
     * @throws ApiException 400=同业务日期已有活跃 run；409=快照缺失/损坏；
     *                      5xx=bridge 真不可达或发布失败（保持原故障口径，不吞）
     */
    public SnapshotRun rerunFromSnapshot(long workflowId, String env, int versionNo,
                                         Map<String, Object> params, String bizDate,
                                         String excludeRunId, String username) {
        List<String> candidates = snapshotDeploymentCandidates(workflowId, env, versionNo);
        for (String candidate : candidates) {
            String busy = prefectActiveRunBlocker(candidate, bizDate, excludeRunId);
            if (busy != null) {
                throw ApiException.badRequest(busy);
            }
            try {
                PrefectBridgeClient.Run run = prefect.createRun(candidate, params);
                log.info("rerun snapshot reuse workflow={} env={} v{} deployment={}",
                        workflowId, env, versionNo, candidate);
                return new SnapshotRun(candidate,
                        "该实例原部署已被新版本替换，本次按 " + env + " v" + versionNo
                                + " 的发布快照部署原地重跑（无调度，不会恢复定时触发）",
                        run);
            } catch (ApiException e) {
                if (!prefect.reachable()) {
                    throw e; // bridge 真故障：保持原错误，不能伪装成「部署不存在」
                }
                log.warn("rerun snapshot candidate gone workflow={} env={} v{} deployment={}: {}",
                        workflowId, env, versionNo, candidate, e.getMessage());
            }
        }
        return rebuildSnapshotDeployment(workflowId, env, versionNo, params, username);
    }

    /** 该版本在该环境留下过的部署 id，最近的在前（上次按快照重跑重建的会被优先复用）。 */
    List<String> snapshotDeploymentCandidates(long workflowId, String env, int versionNo) {
        List<String> rows = jdbc.queryForList("""
                select prefect_deployment_id
                from platform.workflow_deployment
                where workflow_id = ? and env = ? and version_no = ?
                  and prefect_deployment_id is not null
                order by deployed_at desc, id desc limit 5
                """, String.class, workflowId, env, versionNo);
        List<String> out = new ArrayList<>();
        for (String id : rows) {
            if (id != null && !id.isBlank() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * D26：该版本是否有可用的发布快照（{@code workflow_version.model_json}）。
     * 没有快照的历史数据（version_no 对不上任何版本行）只能退回「当前生效部署」的降级路径。
     */
    public boolean hasVersionSnapshot(long workflowId, int versionNo) {
        if (versionNo <= 0) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from platform.workflow_version
                where workflow_id = ? and version_no = ?
                  and model_json is not null and length(model_json::text) > 2
                """, Integer.class, workflowId, versionNo);
        return count != null && count > 0;
    }

    /**
     * Prefect 侧判重（触发/重跑共用一份口径，与 {@link WorkflowMutationService#activeRun}
     * 的数据库侧互补）：库里没同步到的活跃 run（例如直接从 Prefect 起的）也算冲突。
     * 没有 biz_date 参数的老 run 也算同一天——同一部署并发跑风险一样。
     */
    public String prefectActiveRunBlocker(String deploymentId, String bizDate, String excludeRunId) {
        try {
            for (PrefectBridgeClient.Run candidate : prefect.listRuns(deploymentId, 50)) {
                if (candidate.flowRunId().equals(excludeRunId) || !activePrefect(candidate.state())) {
                    continue;
                }
                Object param = candidate.parameters() == null
                        ? null : candidate.parameters().get("biz_date");
                if (param != null && !bizDate.equals(String.valueOf(param))) {
                    continue;
                }
                return "工作流 " + bizDate + " 已有运行中的实例（Prefect run "
                        + candidate.flowRunId() + "），请等它结束后再重跑";
            }
        } catch (Exception e) {
            log.warn("rerun: list prefect runs failed deployment={}: {}", deploymentId, e.getMessage());
        }
        return null;
    }

    private static boolean activePrefect(String state) {
        if (state == null) {
            return false;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "RUNNING", "PENDING", "SCHEDULED", "LATE", "CANCELLING", "PAUSED" -> true;
            default -> false;
        };
    }

    /** 用版本快照重建一个无调度部署并创建 run（顺序与发布一致：先外部资产、后写库、失败补偿删除）。 */
    private SnapshotRun rebuildSnapshotDeployment(long workflowId, String env, int versionNo,
                                                  Map<String, Object> params, String username) {
        Map<String, Object> header = jdbc.queryForList("""
                select w.code, w.name, p.code as project_code
                from platform.workflow w join platform.project p on p.id = w.project_id
                where w.id = ?
                """, workflowId).stream().findFirst().orElse(null);
        if (header == null) {
            throw ApiException.notFound("工作流不存在: " + workflowId);
        }
        List<Map<String, Object>> versionRows = jdbc.queryForList("""
                select model_json::text as model
                from platform.workflow_version
                where workflow_id = ? and version_no = ?
                """, workflowId, versionNo);
        if (versionRows.isEmpty() || versionRows.get(0).get("model") == null) {
            throw ApiException.conflict("版本 v" + versionNo + " 的发布快照不存在，无法按快照重跑："
                    + "请重新发布该工作流后再触发");
        }
        String projectCode = String.valueOf(header.get("project_code"));
        String workflowCode = String.valueOf(header.get("code"));
        String workflowName = String.valueOf(header.get("name"));
        // 渲染必须用与发布同形的名字：flow 文件名/entrypoint 由它派生，
        // 同一版本重建复用同一个文件，不会让 ConfigMap 无限膨胀。
        String deploymentName = deploymentName(workflowCode, env, versionNo);
        // Prefect 部署名要唯一：重建出的快照部署长期保留（供后续重跑复用），若与发布名同名，
        // 之后重新发布同一版本时会撞 ObjectAlreadyExists（发布路径只删 active 行，删不到它）。
        String rerunDeploymentName = deploymentName + "_r" + System.currentTimeMillis() / 1000;
        PrefectFlowRenderer.RenderedFlow flow;
        try {
            // cron 传 null：按快照重跑只需要能被手动触发，不恢复任何定时调度，
            // 否则旧版本会在每个 tick 再跑一遍、写同一批目标表（发布路径删旧部署的原因）。
            flow = renderer.render(workflowId, projectCode, workflowCode, workflowName,
                    env, String.valueOf(versionRows.get(0).get("model")), null, versionNo,
                    deploymentName);
        } catch (ApiException e) {
            throw ApiException.conflict("按 v" + versionNo + " 的快照重建部署失败（快照内容不可渲染）："
                    + e.getMessage());
        }
        List<String> tags = List.of("dataworks", projectCode, env.toLowerCase(Locale.ROOT));
        PrefectBridgeClient.Deployment deployment;
        try {
            deployment = publisher.publish(flow.filename(), flow.python(), flow.flowName(),
                    rerunDeploymentName, flow.entrypoint(), null, null, tags,
                    workflowName + " " + env + " v" + versionNo + "（按快照重跑，无调度）");
        } catch (ApiException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Prefect Bridge 错误")
                    && e.getStatus() >= 500) {
                throw ApiException.conflict("按 v" + versionNo + " 的快照重建部署失败："
                        + e.getMessage() + "；请重试，仍失败请重新发布该版本");
            }
            throw e;
        }
        try {
            jdbc.update("""
                    insert into platform.workflow_deployment
                        (workflow_id, env, version_no, deployed_by,
                         prefect_deployment_id, prefect_flow_file, prefect_flow_name, active)
                    values (?, ?, ?, ?, ?, ?, ?, false)
                    """, workflowId, env, versionNo, username, deployment.deploymentId(),
                    flow.filename(), deployment.flowName());
        } catch (RuntimeException e) {
            // 与发布同款补偿：库里没记住它，外部部署就成了「平台看不见的孤儿」
            // （没有 DB 行就没有后续复用，会一直挂着）。尽力删掉再抛。
            try {
                publisher.delete(flow.filename(), deployment.deploymentId());
            } catch (RuntimeException cleanup) {
                log.error("rerun rebuild compensation failed workflow={} deployment={}: {}",
                        workflowId, deployment.deploymentId(), cleanup.getMessage());
            }
            throw e;
        }
        // ConfigMap 卷同步到 worker 需要时间（与手工触发同口径，避免首跑 FileNotFound）。
        publisher.awaitFlowFile(flow.filename(), System.currentTimeMillis());
        PrefectBridgeClient.Run run;
        try {
            run = prefect.createRun(deployment.deploymentId(), params);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.contains("FileNotFound") || detail.contains("No such file")) {
                throw new ApiException(503, "Flow 文件尚未同步到执行器，请稍后重试（快照部署已保留）");
            }
            throw e;
        }
        log.info("rerun snapshot rebuilt workflow={} env={} v{} deployment={}",
                workflowId, env, versionNo, deployment.deploymentId());
        return new SnapshotRun(deployment.deploymentId(),
                "该实例原部署已被新版本替换，已按 " + env + " v" + versionNo
                        + " 的发布快照重建无调度部署重跑（执行的是该版本自己的定义，"
                        + "不会恢复定时触发）",
                run);
    }

    /** D26：按快照重跑的结果——实际落点部署、给用户看的说明、创建出的 run。 */
    public record SnapshotRun(String deploymentId, String note, PrefectBridgeClient.Run run) {
    }
}
