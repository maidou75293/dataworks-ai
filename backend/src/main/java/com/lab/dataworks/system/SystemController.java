package com.lab.dataworks.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 系统监控：平台页面总览、Prometheus 指标端点、Alertmanager Webhook。
 */
@RestController
@RequestMapping("/api")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final SystemMonitorService monitor;
    private final K8sSnapshotService k8s;
    private final ServiceResourceService services;
    private final DorisNodeService dorisNodes;
    private final K8sNodeService k8sNodes;
    private final K8sOpsService k8sOps;
    private final RightsizingService rightsizing;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SessionService sessions;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;

    public SystemController(SystemMonitorService monitor, K8sSnapshotService k8s,
                            ServiceResourceService services, DorisNodeService dorisNodes,
                            K8sNodeService k8sNodes, K8sOpsService k8sOps,
                            RightsizingService rightsizing,
                            JdbcTemplate jdbc, ObjectMapper json, SessionService sessions,
                            com.lab.dataworks.auth.InternalTokenService internalTokens) {
        this.monitor = monitor;
        this.k8s = k8s;
        this.services = services;
        this.dorisNodes = dorisNodes;
        this.k8sNodes = k8sNodes;
        this.k8sOps = k8sOps;
        this.rightsizing = rightsizing;
        this.jdbc = jdbc;
        this.json = json;
        this.sessions = sessions;
        this.internalTokens = internalTokens;
    }

    @GetMapping("/system/overview")
    @RequireAtom("system:manage")
    public Map<String, Object> overview() {
        return monitor.overview();
    }

    /**
     * 服务资源：每个 Deployment / StatefulSet 的实际内存、CPU 用量与配额。
     * 内存统一用 G、保留两位小数（页面与接口同一口径）。
     */
    @GetMapping("/system/services")
    @RequireAtom("system:manage")
    public Map<String, Object> services() {
        var rows = services.list();
        return Map.of("services", rows,
                "usageAvailable", rows.stream().anyMatch(r -> r.usageAvailable()));
    }

    // ---------------------------------------------------------------- 内存右调建议器（REQ-SYS-RIGHTSIZE-1）

    /**
     * 右调建议清单：每工作负载每容器一行「现值 / 近 7 日 P95 实测 / 进程声明 / 建议值 + 依据」。
     * Prometheus 无数据时如实标「样本不足」，不产数字。
     */
    @GetMapping("/system/rightsizing")
    @RequireAtom("system:manage")
    public Map<String, Object> rightsizingList() {
        return rightsizing.list();
    }

    /** 建议算法试算器（同一函数、可喂任意 P95/声明，判据点名测这条规则链）。 */
    public record EvaluateRequest(Long p95Bytes, Long declaredBytes, String declaredKind) {
    }

    @PostMapping("/system/rightsizing/evaluate")
    @RequireAtom("system:manage")
    public RightsizingService.Suggestion rightsizingEvaluate(@RequestBody EvaluateRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请提供 p95Bytes / declaredBytes / declaredKind");
        }
        long p95 = req.p95Bytes() == null ? -1 : req.p95Bytes();
        long declared = req.declaredBytes() == null ? 0 : req.declaredBytes();
        boolean xmx = !"PROCESS_SIZE".equalsIgnoreCase(req.declaredKind() == null ? "" : req.declaredKind());
        return RightsizingService.suggest(p95, declared, declared > 0 && xmx);
    }

    /**
     * 应用右调：不带 request/limit 时应用服务端现算建议；dryRun 缺省 true（只算账不动集群）。
     * 硬闸（ResourceQuota / 节点余量）与倒挂联写在 RightsizingService。
     */
    @PostMapping("/system/rightsizing/apply")
    @RequireAtom("system:manage")
    public Map<String, Object> rightsizingApply(@RequestBody RightsizingService.ApplyRequest req,
                                                jakarta.servlet.http.HttpServletRequest http) {
        String name = req == null || req.name() == null ? "?" : req.name();
        return audited(http, "SERVICE_RIGHTSIZE", name,
                (req == null ? "" : req.namespace() + "/" + req.kind())
                        + " request=" + req.request() + " limit=" + req.limit()
                        + " dryRun=" + (req.dryRun() == null || req.dryRun()),
                () -> rightsizing.apply(req));
    }

    /** 停止服务（副本数置 0，原副本数记住）；核心组件拒绝，见 ServiceResourceService。 */
    @PostMapping("/system/services/{namespace}/{kind}/{name}/stop")
    @RequireAtom("system:manage")
    public Map<String, Object> stopService(@PathVariable String namespace,
                                           @PathVariable String kind, @PathVariable String name,
                                           jakarta.servlet.http.HttpServletRequest http) {
        requireKind(kind);
        return audited(http, "SERVICE_STOP", name, kind, () -> {
            var row = services.stop(namespace, kind, name);
            return Map.of("ok", true, "service", row);
        });
    }

    /** 启动服务：恢复停止前的副本数。 */
    @PostMapping("/system/services/{namespace}/{kind}/{name}/start")
    @RequireAtom("system:manage")
    public Map<String, Object> startService(@PathVariable String namespace,
                                            @PathVariable String kind, @PathVariable String name,
                                            jakarta.servlet.http.HttpServletRequest http) {
        requireKind(kind);
        return audited(http, "SERVICE_START", name, kind, () -> {
            var row = services.start(namespace, kind, name);
            return Map.of("ok", true, "service", row);
        });
    }

    public record ReplicaRequest(Integer replicas) {
    }

    public record MemoryRequest(String limit) {
    }

    /** 运维手填副本数：0 = 停止，1-N = 启动并扩容。 */
    @PostMapping("/system/services/{namespace}/{kind}/{name}/replicas")
    @RequireAtom("system:manage")
    public Map<String, Object> setReplicas(@PathVariable String namespace,
                                           @PathVariable String kind, @PathVariable String name,
                                           @RequestBody ReplicaRequest req,
                                           jakarta.servlet.http.HttpServletRequest http) {
        requireKind(kind);
        if (req == null || req.replicas() == null) {
            throw ApiException.badRequest("请提供 replicas");
        }
        return audited(http, "SERVICE_SCALE", name, kind + " replicas=" + req.replicas(), () -> {
            var row = services.scale(namespace, kind, name, req.replicas());
            return Map.of("ok", true, "service", row);
        });
    }

    /** 扩容/缩容内存（memory limit），如 2G / 3072Mi。 */
    @PostMapping("/system/services/{namespace}/{kind}/{name}/memory")
    @RequireAtom("system:manage")
    public Map<String, Object> setMemory(@PathVariable String namespace,
                                         @PathVariable String kind, @PathVariable String name,
                                         @RequestBody MemoryRequest req,
                                         jakarta.servlet.http.HttpServletRequest http) {
        requireKind(kind);
        if (req == null || req.limit() == null || req.limit().isBlank()) {
            throw ApiException.badRequest("请提供 limit，例如 2G");
        }
        return audited(http, "SERVICE_MEMORY", name, kind + " limit=" + req.limit(), () -> {
            var row = services.resizeMemory(namespace, kind, name, req.limit());
            return Map.of("ok", true, "service", row);
        });
    }

    /** 删除服务（工作负载本体）。核心组件拒绝；测试夹具可用它彻底清掉。 */
    @DeleteMapping("/system/services/{namespace}/{kind}/{name}")
    @RequireAtom("system:manage")
    public Map<String, Object> deleteService(@PathVariable String namespace,
                                             @PathVariable String kind, @PathVariable String name,
                                             jakarta.servlet.http.HttpServletRequest http) {
        requireKind(kind);
        return audited(http, "SERVICE_DELETE", name, namespace + "/" + kind, () -> {
            services.delete(namespace, kind, name);
            return Map.of("ok", true);
        });
    }

    // ---------------------------------------------------------------- k3s 节点运维（扩容/缩容）

    public record CordonRequest(Boolean schedulable) {
    }

    public record DrainRequest(Boolean dryRun) {
    }

    /**
     * 封锁/解封节点：封锁后不再往这台机器调度新 Pod。
     *
     * <p>D21：方法体本来就按「空 body = 默认值」处理（{@code schedulable=true} /
     * {@code dryRun=false}），注解却仍是必填，导致运维脚本发空 POST 直接被
     * 「请求体不是合法的 JSON」拦在方法体之前。注解改成可选，行为与注释一致。</p>
     */
    @PostMapping("/system/nodes/{node}/cordon")
    @RequireAtom("system:manage")
    public Map<String, Object> cordonNode(@PathVariable String node,
                                          @RequestBody(required = false) CordonRequest req,
                                          jakarta.servlet.http.HttpServletRequest http) {
        boolean schedulable = req == null || req.schedulable() == null || req.schedulable();
        return audited(http, "NODE_CORDON", node, "schedulable=" + schedulable,
                () -> k8sNodes.setSchedulable(node, schedulable));
    }

    /** 驱逐节点上的业务 Pod（跳过 DaemonSet）；dryRun=true 只看清单不动手。空 body＝正式驱逐。 */
    @PostMapping("/system/nodes/{node}/drain")
    @RequireAtom("system:manage")
    public Map<String, Object> drainNode(@PathVariable String node,
                                         @RequestBody(required = false) DrainRequest req,
                                         jakarta.servlet.http.HttpServletRequest http) {
        boolean dryRun = req != null && Boolean.TRUE.equals(req.dryRun());
        // 驱逐清单只有动作完成才知道：先按「请求参数」落 PENDING 审计，成功后再把
        // pods 清单补进 detail（S13：动作抛异常时这条记录回填 FAILED，不再无痕）
        return audited(http, "NODE_DRAIN", node, "dryRun=" + dryRun,
                () -> k8sNodes.drain(node, dryRun), out -> "pods=" + out.get("pods"));
    }

    /** 从集群移除节点：必须已封锁且已驱逐，集群至少还剩一台。 */
    @DeleteMapping("/system/nodes/{node}")
    @RequireAtom("system:manage")
    public Map<String, Object> removeNode(@PathVariable String node,
                                          jakarta.servlet.http.HttpServletRequest http) {
        return audited(http, "NODE_REMOVE", node, "delete node", () -> k8sNodes.remove(node));
    }

    /** 新节点加入命令（一次性 token 由 server 上执行 k3s token create 获取）。 */
    @GetMapping("/system/nodes/join-command")
    @RequireAtom("system:manage")
    public Map<String, Object> joinCommand() {
        return k8sNodes.joinCommand();
    }

    // ---------------------------------------------------------------- 集群扩缩容

    /** 哪些集群能按副本数扩缩容：key → {kind, 工作负载名}。 */
    private static final Map<String, String[]> SCALABLE_CLUSTERS = Map.of(
            // Zeta 是 StatefulSet，加副本 = 加节点，Hazelcast 会自动把新节点加入集群
            "zeta", new String[]{"statefulset", "seatunnel"},
            // HDFS：扩缩的是 DataNode；数据副本由 dfs.replication 控制
            "hdfs", new String[]{"statefulset", "hdfs-datanode"});

    /** 集群扩缩容：改对应工作负载的副本数（副作用与「服务资源」里的副本设置一致）。 */
    @PostMapping("/system/clusters/{key}/replicas")
    @RequireAtom("system:manage")
    public Map<String, Object> scaleCluster(@PathVariable String key,
                                            @RequestBody ReplicaRequest req,
                                            jakarta.servlet.http.HttpServletRequest http) {
        if (req == null || req.replicas() == null) {
            throw ApiException.badRequest("请提供 replicas");
        }
        String[] target = SCALABLE_CLUSTERS.get(key);
        if (target == null) {
            throw ApiException.badRequest("「" + key + "」不支持按副本数扩缩容，见该集群卡片上的说明");
        }
        return audited(http, "CLUSTER_SCALE", key, target[1] + " replicas=" + req.replicas(), () -> {
            var row = services.scale("dw-dev", target[0], target[1], req.replicas());
            return Map.of("ok", true, "service", row);
        });
    }

    // ---------------------------------------------------------------- K8s 运维治理（REQ-K8S-OPS-BOARD-1）

    public record GhostPodsRequest(Boolean dryRun) {
    }

    /**
     * 清理幽灵 Pod（Terminating 卡住 / Failed）：dryRun=true 只回清单。
     * 页面交互与节点驱逐一致：先 dryRun 看清单，二次确认后真删。
     */
    @PostMapping("/system/k8s/ghost-pods/cleanup")
    @RequireAtom("system:manage")
    public Map<String, Object> cleanupGhostPods(@RequestBody(required = false) GhostPodsRequest req,
                                                jakarta.servlet.http.HttpServletRequest http) {
        boolean dryRun = req != null && Boolean.TRUE.equals(req.dryRun());
        return audited(http, "K8S_GHOST_POD_CLEANUP", "ghost-pods", "dryRun=" + dryRun,
                () -> k8sOps.cleanupGhostPods(dryRun),
                out -> "deleted=" + out.get("deletedCount"));
    }

    /**
     * SeaTunnel 制品恢复：后端不跑 shell，返回「疑似受害 Pod 探测 + 操作单」，
     * 真正执行走 ops 脚本（与 ansible tasks/25-artifacts.yml 等价链路）。
     */
    @PostMapping("/system/k8s/seatunnel-artifacts/restore")
    @RequireAtom("system:manage")
    public Map<String, Object> seatunnelArtifactsRestore(jakarta.servlet.http.HttpServletRequest http) {
        return audited(http, "K8S_SEATUNNEL_RESTORE", "seatunnel-artifacts", "operation ticket",
                () -> k8sOps.seatunnelArtifactsTicket(),
                out -> "suspected=" + out.get("suspectedCount"));
    }

    /** Registry 旧 tag 清理预览（只读）：保留最近 10 版 + 在用 tag/digest。 */
    @PostMapping("/system/k8s/registry/gc-preview")
    @RequireAtom("system:manage")
    public Map<String, Object> registryGcPreview() {
        return k8sOps.registryGcPreview();
    }

    public record RegistryGcRequest(Boolean confirm) {
    }

    /** Registry 旧 tag 清理执行：必须带 confirm=true，防脚本误触。 */
    @PostMapping("/system/k8s/registry/gc-run")
    @RequireAtom("system:manage")
    public Map<String, Object> registryGcRun(@RequestBody(required = false) RegistryGcRequest req,
                                              jakarta.servlet.http.HttpServletRequest http) {
        if (req == null || !Boolean.TRUE.equals(req.confirm())) {
            throw ApiException.badRequest("危险操作：请携带 confirm=true 明确确认删除旧 tag");
        }
        return audited(http, "K8S_REGISTRY_GC", "registry", "gc-run confirm=true",
                () -> k8sOps.registryGcRun(),
                out -> "deleted=" + out.get("deletedCount"));
    }

    // ---------------------------------------------------------------- Doris 节点扩缩容
    // BE 是 DaemonSet：一节点一实例，所以扩/缩的对象是 K8s 节点而不是 Pod 副本。

    @GetMapping("/system/doris/nodes")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisNodeList() {
        return dorisNodes.list();
    }

    /** 加入 Doris：打节点标签（DaemonSet 起 BE）+ ADD BACKEND。 */
    @PostMapping("/system/doris/nodes/{node}/join")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisNodeJoin(@PathVariable String node,
                                             jakarta.servlet.http.HttpServletRequest http) {
        return audited(http, "DORIS_NODE_JOIN", node, "add backend", () -> {
            var row = dorisNodes.join(node);
            return Map.of("ok", true, "node", row);
        });
    }

    public record ReplicationRequest(Integer replicas) {
    }

    /** 副本迁移进度（页面进度条）：每张表离 3 副本还差多少。 */
    @GetMapping("/system/doris/replication-progress")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisReplicationProgress() {
        return dorisNodes.replicationProgress();
    }

    /** 退役前的副本体检：单副本表在迁移窗口内没有冗余（Doris 仍能把它们克隆到别的 BE）。 */
    @GetMapping("/system/doris/replication-check")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisReplicationCheck() {
        return dorisNodes.replicationCheck();
    }

    /** 缩容前置：把单副本表调到 2/3 副本，tablet 才有第二份可以迁走。 */
    @PostMapping("/system/doris/replication")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisRaiseReplication(@RequestBody ReplicationRequest req,
                                                     jakarta.servlet.http.HttpServletRequest http) {
        if (req == null || req.replicas() == null) {
            throw ApiException.badRequest("请提供 replicas（2 或 3）");
        }
        return audited(http, "DORIS_REPLICATION", "doris", "target=" + req.replicas(),
                () -> dorisNodes.raiseReplication(req.replicas()));
    }

    /** 缩容第一步：DECOMMEND，Doris 自动把 tablet 迁到其它 BE。 */
    @PostMapping("/system/doris/nodes/{node}/decommission")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisNodeDecommission(@PathVariable String node,
                                                     jakarta.servlet.http.HttpServletRequest http) {
        return audited(http, "DORIS_NODE_DECOMMISSION", node, "recommend backend", () -> {
            var row = dorisNodes.decommission(node);
            return Map.of("ok", true, "node", row);
        });
    }

    @PostMapping("/system/doris/nodes/{node}/cancel-decommission")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisNodeCancelDecommission(@PathVariable String node,
                                                           jakarta.servlet.http.HttpServletRequest http) {
        return audited(http, "DORIS_NODE_CANCEL", node, "cancel decommission", () -> {
            var row = dorisNodes.cancelDecommission(node);
            return Map.of("ok", true, "node", row);
        });
    }

    /** 缩容第二步：tablet 迁空后 DROP BACKEND，并移除节点标签。 */
    @PostMapping("/system/doris/nodes/{node}/drop")
    @RequireAtom("system:manage")
    public Map<String, Object> dorisNodeDrop(@PathVariable String node,
                                             jakarta.servlet.http.HttpServletRequest http) {
        return audited(http, "DORIS_NODE_DROP", node, "drop backend", () -> {
            var row = dorisNodes.drop(node);
            return Map.of("ok", true, "node", row);
        });
    }

    private static void requireKind(String kind) {
        if (!"deployment".equals(kind) && !"statefulset".equals(kind)
                && !"daemonset".equals(kind)) {
            throw ApiException.badRequest("kind 只能是 deployment / statefulset / daemonset");
        }
    }

    /**
     * S12 + S13：运维动作的审计统一走 {@link SessionService}。
     *
     * <p>之前这里是控制器自己的私有 helper，直接 {@code insert into platform.audit_log
     * (username, action, target, detail)}：不带 {@code tenant_id}，而安全中心的列表按
     * {@code (? or tenant_id = ?)} 过滤——{@code NULL = ?} 永远为假，租户管理员于是
     * 永远看不到 {@code SERVICE_DELETE / NODE_DRAIN / DORIS_NODE_DROP} 这些运维级记录。
     * 同时它是「动作做完再记」且 {@code catch} 后只 {@code log.warn}，动作一抛异常
     * 就一条都不留。现在：先记后做（PENDING → 回填 SUCCESS / FAILED），
     * 并带上租户 / IP / 主体类型，与其它审计同一口径。</p>
     */
    private <T> T audited(jakarta.servlet.http.HttpServletRequest http, String action,
                          String target, String detail, Supplier<T> work) {
        return sessions.auditAround(user(http), action, target, detail,
                http == null ? null : AuthInterceptor.clientIp(http), work);
    }

    /** 同上，成功时用 {@code outcomeDetail} 把只有结果才知道的信息追加进审计 detail。 */
    private <T> T audited(jakarta.servlet.http.HttpServletRequest http, String action,
                          String target, String detail, Supplier<T> work,
                          java.util.function.Function<T, String> outcomeDetail) {
        return sessions.auditAround(user(http), action, target, detail,
                http == null ? null : AuthInterceptor.clientIp(http), work, outcomeDetail);
    }

    private static String user(jakarta.servlet.http.HttpServletRequest http) {
        Object value = http == null ? null : http.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    @GetMapping(value = "/internal/metrics/k8s", produces = "text/plain;version=0.0.4")
    public String k8sMetrics(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireInternal(token, authorization);
        return k8s.prometheusText();
    }

    /**
     * Alertmanager Webhook：firing 写入告警中心，resolved 自动关闭。
     *
     * <p>按指纹去重：Prometheus 周期性重发同一条 firing 不会刷屏。</p>
     */
    @PostMapping("/internal/alerts/alertmanager")
    public Map<String, Object> alertmanager(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody String body) {
        requireInternal(token, authorization);
        int firing = 0;
        int resolved = 0;
        try {
            JsonNode root = json.readTree(body == null ? "{}" : body);
            for (JsonNode alert : root.path("alerts")) {
                String status = alert.path("status").asText("firing");
                JsonNode labels = alert.path("labels");
                JsonNode annotations = alert.path("annotations");
                String alertname = labels.path("alertname").asText("SystemAlert");
                String severity = "critical".equalsIgnoreCase(labels.path("severity").asText())
                        ? "CRITICAL" : "WARNING";
                String summary = annotations.path("summary").asText("");
                String description = annotations.path("description").asText("");
                String title = summary.isBlank() ? alertname : summary;
                if (title.length() > 255) {
                    title = title.substring(0, 255);
                }
                StringBuilder detail = new StringBuilder(description);
                for (var it = labels.fields(); it.hasNext(); ) {
                    var e = it.next();
                    if (!"alertname".equals(e.getKey()) && !"severity".equals(e.getKey())) {
                        detail.append(detail.isEmpty() ? "" : "，")
                                .append(e.getKey()).append('=').append(e.getValue().asText());
                    }
                }
                String key = "system:" + fingerprint(alert, labels, alertname);
                if ("resolved".equalsIgnoreCase(status)) {
                    resolved += jdbc.update("""
                            update platform.alert_event
                            set status = 'CLOSED', closed_by = 'alertmanager', closed_at = now()
                            where source = 'SYSTEM' and event_key = ? and status <> 'CLOSED'
                            """, key);
                } else {
                    firing += jdbc.update("""
                            insert into platform.alert_event
                                (rule_name, rule_type, severity, title, detail, event_key, source, status)
                            values ('系统监控', 'SYSTEM', ?, ?, ?, ?, 'SYSTEM', 'OPEN')
                            on conflict (event_key) where source = 'SYSTEM'
                            do update set severity = excluded.severity,
                                          title = excluded.title,
                                          detail = excluded.detail,
                                          rule_name = excluded.rule_name,
                                          -- 同一告警再次 firing 必须重新打开：
                                          -- 否则 ACK/CLOSED 之后永远收不到通知。
                                          -- firing_seq + 1 让上一轮的 SENT 投递记录
                                          -- 不再挡住本轮通知（P1-15）
                                          firing_seq = case when alert_event.status = 'CLOSED'
                                              then alert_event.firing_seq + 1
                                              else alert_event.firing_seq end,
                                          status = 'OPEN',
                                          acked_by = null, acked_at = null,
                                          closed_by = null, closed_at = null
                            """, severity, title, detail.toString(), key);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("alertmanager webhook parse failed: {}", e.getMessage());
            throw ApiException.badRequest("告警内容格式不正确");
        }
        return Map.of("ok", true, "firing", firing, "resolved", resolved);
    }

    private void requireInternal(String token, String authorization) {
        if (!internalTokens.configured()) {
            throw new ApiException(503, "内部接口未启用（PLATFORM_INTERNAL_TOKEN 未配置）");
        }
        String supplied = token;
        if ((supplied == null || supplied.isBlank()) && authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            supplied = authorization.substring(7).trim();
        }
        if (!internalTokens.matches(supplied)) {
            throw new ApiException(401, "内部凭证无效");
        }
    }

    private static String fingerprint(JsonNode alert, JsonNode labels, String alertname) {
        String fp = alert.path("fingerprint").asText("");
        if (!fp.isBlank()) {
            return fp;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(alertname);
            for (var it = labels.fields(); it.hasNext(); ) {
                var e = it.next();
                sb.append('|').append(e.getKey()).append('=').append(e.getValue().asText());
            }
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return alertname;
        }
    }
}
