package com.lab.dataworks.system;

import com.lab.dataworks.common.ApiException;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存右调建议器：按「近 7 日实测 P95」给各集群组件出 requests/limits 建议，人点一下才应用。
 *
 * <p>口径（项目负责人 2026-11-03 原话「如何系统有动态优化各个集群内存配置的办法」，
 * 详见 docs/系统监控-内存右调-20261103.md）：
 * <ul>
 *   <li>取数：Prometheus {@code container_memory_working_set_bytes} 7 日 P95
 *       （quantile_over_time 子查询，30 分钟步长），与 kubectl top 同口径；
 *       无序列时 {@code dataAvailable=false} 如实标「样本不足」，绝不编数。</li>
 *   <li>建议：request = P95×1.3、limit = P95×2，均向上取整到 128Mi 步进；
 *       limit 不得低于进程内存声明换算的下限——JVM {@code -Xmx}×1.2（堆外开销）、
 *       Flink {@code *.memory.process.size}×1.0（该口径本身含开销）——禁止「limit &lt; 进程申请」倒挂。</li>
 *   <li>应用：patch requests/limits；新 limit 低于进程声明时<b>同一动作</b>改写
 *       {@code -Xmx} 启动参数或联动登记的 ConfigMap，改不掉就 400 拒绝。
 *       应用前硬闸校验命名空间 ResourceQuota 与节点 allocatable 余量，超闸拒绝。
 *       模板变化自动触发滚动更新。</li>
 * </ul>
 *
 * <p>「动态」= 随时重新实测、随时给人应用；不做无人值守自动改配，避免配置漂移无痕迹。</p>
 */
@Service
public class RightsizingService {

    private static final Logger log = LoggerFactory.getLogger(RightsizingService.class);

    static final long MIB = 1024L * 1024;
    static final long GIB = 1024L * MIB;
    static final long STEP = 128L * MIB;
    static final long MAX_LIMIT = 8L * GIB;
    private static final double REQUEST_FACTOR = 1.3;
    private static final double LIMIT_FACTOR = 2.0;
    /** -Xmx 只是堆；元空间/线程栈/直接缓冲实测约再吃两成。 */
    private static final double JVM_OVERHEAD_FACTOR = 1.2;
    private static final int WINDOW_DAYS = 7;

    private static final String PLATFORM_NAMESPACE = "dw-dev";
    private static final String SPARK_NAMESPACE = "dw-spark";
    private static final String TEST_NAMESPACE_PREFIX = "dataworks-test";

    /**
     * 进程声明写在 ConfigMap 里的组件联动登记：workload 名 → (ConfigMap, data 键, 行键)。
     * flink-conf / flink-prod-conf 都在 data["flink-conf.yaml"] 里存整个配置文件，
     * JM/TM 各认自己那一行。
     */
    record ConfigLink(String namespace, String configMap, String dataKey, String lineKey) {
    }

    private static final Map<String, List<ConfigLink>> LINKAGE = Map.of(
            "flink-taskmanager", List.of(new ConfigLink(PLATFORM_NAMESPACE, "flink-conf",
                    "flink-conf.yaml", "taskmanager.memory.process.size")),
            "flink-prod-taskmanager", List.of(new ConfigLink(PLATFORM_NAMESPACE, "flink-prod-conf",
                    "flink-conf.yaml", "taskmanager.memory.process.size")),
            "flink-jobmanager", List.of(new ConfigLink(PLATFORM_NAMESPACE, "flink-conf",
                    "flink-conf.yaml", "jobmanager.memory.process.size")),
            "flink-prod-jobmanager", List.of(new ConfigLink(PLATFORM_NAMESPACE, "flink-prod-conf",
                    "flink-conf.yaml", "jobmanager.memory.process.size")));

    private static final Pattern XMX = Pattern.compile("-Xmx(\\d+)([kKmMgG])");
    private static final Pattern XMS = Pattern.compile("-Xms(\\d+)([kKmMgG])");

    private final PrometheusClient prometheus;

    public RightsizingService(PrometheusClient prometheus) {
        this.prometheus = prometheus;
    }

    // ================================================================ 纯算法（判据点名测）

    /** 建议结果；{@code insufficient=true} 时不产任何数字。 */
    public record Suggestion(Long requestBytes, Long limitBytes, String request, String limit,
                             String reason, boolean insufficient) {
    }

    /**
     * 建议算法本体（无 IO）：request=ceil(P95×1.3)、limit=ceil(P95×2)，128Mi 步向上取整；
     * limit 按进程声明换算下限抬底；封顶 8Gi。{@code declaredBytes<=0} 表示无进程声明。
     */
    public static Suggestion suggest(long p95Bytes, long declaredBytes, boolean declaredIsXmx) {
        if (p95Bytes <= 0) {
            return new Suggestion(null, null, null, null,
                    "样本不足（Prometheus 近 " + WINDOW_DAYS + " 日无该容器内存序列），不产建议", true);
        }
        long request = roundUpStep(Math.round(p95Bytes * REQUEST_FACTOR));
        long limit = roundUpStep(Math.round(p95Bytes * LIMIT_FACTOR));
        StringBuilder reason = new StringBuilder(String.format(
                "近%d日P95=%s：request=P95×1.3、limit=P95×2，128Mi步向上取整", WINDOW_DAYS, mi(p95Bytes)));
        if (declaredBytes > 0) {
            long floor = declaredFloor(declaredBytes, declaredIsXmx);
            if (floor > limit) {
                reason.append(String.format("；进程声明%s=%s%s → limit 抬到 %s 防倒挂",
                        declaredIsXmx ? "(-Xmx)" : "(process.size)", mi(declaredBytes),
                        declaredIsXmx ? "×1.2(堆外)" : "", mi(floor)));
                limit = floor;
            } else {
                reason.append(String.format("；进程声明%s=%s 未超建议，limit 不抬",
                        declaredIsXmx ? "(-Xmx)" : "(process.size)", mi(declaredBytes)));
            }
        }
        if (request > limit) {
            request = limit; // 恒等式 request ≤ limit
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
            if (request > limit) {
                request = limit;
            }
            reason.append("；触顶8Gi（单容器边界，先扩节点再谈这个组件）");
        }
        return new Suggestion(request, limit, mi(request), mi(limit), reason.toString(), false);
    }

    /** 128Mi 步向上取整（至少一步）。 */
    static long roundUpStep(long bytes) {
        return Math.max(1, (bytes + STEP - 1) / STEP) * STEP;
    }

    /** 进程声明换算成 limit 下限：-Xmx 按 ×1.2（堆外），process.size 按 ×1.0（含自身开销）。 */
    static long declaredFloor(long declaredBytes, boolean isXmx) {
        if (declaredBytes <= 0) {
            return 0;
        }
        return roundUpStep(isXmx ? Math.round(declaredBytes * JVM_OVERHEAD_FACTOR) : declaredBytes);
    }

    /** 新 limit 能罩住的 -Xmx 上限：limit÷1.2 向下取整到 Mi（JVM 允许非 128Mi 步的堆声明）。 */
    static long fitXmx(long newLimitBytes) {
        return Math.max(MIB, (long) (newLimitBytes / JVM_OVERHEAD_FACTOR / MIB) * MIB);
    }

    // ================================================================ GET 建议清单

    /** 单容器账目：现值 / 7日P95 / 进程声明 / 建议 / 倒挂标志。 */
    public record ContainerAdvice(String container,
                                  String currentRequest, long currentRequestBytes,
                                  String currentLimit, long currentLimitBytes,
                                  String p95, long p95Bytes, int podSamples, boolean dataAvailable,
                                  String declared, long declaredBytes, String declaredKind,
                                  String declaredSource,
                                  String suggestedRequest, long suggestedRequestBytes,
                                  String suggestedLimit, long suggestedLimitBytes,
                                  String reason, boolean inversion) {
    }

    public record Row(String namespace, String kind, String name, int replicas,
                      boolean applyable, List<ContainerAdvice> containers) {
    }

    /** Prometheus 单序列（按 pod+container）；pod 归属工作负载用前缀匹配（与服务资源同口径）。 */
    private record P95Sample(String namespace, String pod, String container, long bytes) {
    }

    public Map<String, Object> list() {
        List<P95Sample> samples = p95Samples();
        Map<String, Object> out = new LinkedHashMap<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<Row> rows = new ArrayList<>();
            for (String ns : scopedNamespaces(client)) {
                for (Deployment d : client.apps().deployments().inNamespace(ns).list().getItems()) {
                    rows.add(row(client, samples, ns, "deployment", d.getMetadata().getName(),
                            d.getSpec() == null || d.getSpec().getReplicas() == null
                                    ? 0 : d.getSpec().getReplicas(),
                            containersOf(d.getSpec() == null ? null : d.getSpec().getTemplate())));
                }
                for (StatefulSet s : client.apps().statefulSets().inNamespace(ns).list().getItems()) {
                    rows.add(row(client, samples, ns, "statefulset", s.getMetadata().getName(),
                            s.getSpec() == null || s.getSpec().getReplicas() == null
                                    ? 0 : s.getSpec().getReplicas(),
                            containersOf(s.getSpec() == null ? null : s.getSpec().getTemplate())));
                }
                for (DaemonSet ds : client.apps().daemonSets().inNamespace(ns).list().getItems()) {
                    int desired = ds.getStatus() == null
                            || ds.getStatus().getDesiredNumberScheduled() == null
                            ? 0 : ds.getStatus().getDesiredNumberScheduled();
                    rows.add(row(client, samples, ns, "daemonset", ds.getMetadata().getName(), desired,
                            containersOf(ds.getSpec() == null ? null : ds.getSpec().getTemplate())));
                }
            }
            rows.sort(Comparator.comparing(Row::namespace).thenComparing(Row::name));
            out.put("rows", rows);
            out.put("quota", quotaSnapshot(client));
            out.put("allocatable", allocatableSnapshot(client));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "读取集群右调数据失败: " + e.getMessage());
        }
        out.put("generatedAt", Instant.now().toString());
        out.put("windowDays", WINDOW_DAYS);
        out.put("stepMi", STEP / MIB);
        out.put("namespaces", List.of(PLATFORM_NAMESPACE, SPARK_NAMESPACE, TEST_NAMESPACE_PREFIX + "*"));
        return out;
    }

    private Row row(KubernetesClient client, List<P95Sample> samples, String ns,
                    String kind, String name, int replicas, List<Container> containers) {
        List<ContainerAdvice> advices = new ArrayList<>();
        for (Container c : containers) {
            String cname = c.getName();
            long reqBytes = resource(c, true);
            long limBytes = resource(c, false);
            long p95 = -1;
            Set<String> pods = new LinkedHashSet<>();
            for (P95Sample s : samples) {
                if (s.namespace().equals(ns) && s.container().equals(cname) && belongsTo(s.pod(), name)) {
                    p95 = Math.max(p95, s.bytes());
                    pods.add(s.pod());
                }
            }
            Declared declared = declaredOf(client, name, c);
            Suggestion s = suggest(p95, declared == null ? 0 : declared.bytes(),
                    declared != null && declared.xmx());
            boolean inversion = declared != null && limBytes > 0
                    && limBytes < declaredFloor(declared.bytes(), declared.xmx());
            advices.add(new ContainerAdvice(cname,
                    reqBytes > 0 ? mi(reqBytes) : "未设置", reqBytes,
                    limBytes > 0 ? mi(limBytes) : "未设置", limBytes,
                    p95 > 0 ? mi(p95) : null, p95, pods.size(), p95 > 0,
                    declared == null ? null : mi(declared.bytes()),
                    declared == null ? 0 : declared.bytes(),
                    declared == null ? "NONE" : (declared.xmx() ? "XMX" : "PROCESS_SIZE"),
                    declared == null ? null : declared.source(),
                    s.request(), s.requestBytes() == null ? 0 : s.requestBytes(),
                    s.limit(), s.limitBytes() == null ? 0 : s.limitBytes(),
                    s.reason(), inversion));
        }
        return new Row(ns, kind, name, replicas, true, advices);
    }

    /** 近 7 日 per 容器 P95；Prometheus 不可用时退空表，各行标「样本不足」。 */
    private List<P95Sample> p95Samples() {
        String nsRegex = PLATFORM_NAMESPACE + "|" + SPARK_NAMESPACE + "|" + TEST_NAMESPACE_PREFIX + ".*";
        List<P95Sample> out = new ArrayList<>();
        for (PrometheusClient.Sample sample : prometheus.query(
                "quantile_over_time(0.95, container_memory_working_set_bytes{"
                        + "container!=\"\",container!=\"POD\",namespace=~\"" + nsRegex + "\"}[7d:30m])")) {
            String ns = sample.labels().get("namespace");
            String pod = sample.labels().get("pod");
            String container = sample.labels().get("container");
            if (ns == null || pod == null || container == null || sample.value() <= 0) {
                continue;
            }
            out.add(new P95Sample(ns, pod, container, (long) sample.value()));
        }
        return out;
    }

    private static boolean belongsTo(String podName, String workload) {
        return podName.equals(workload) || podName.startsWith(workload + "-");
    }

    private Map<String, Map<String, String>> quotaSnapshot(KubernetesClient client) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String ns : scopedNamespaces(client)) {
            for (var q : client.resourceQuotas().inNamespace(ns).list().getItems()) {
                Map<String, String> one = new LinkedHashMap<>();
                if (q.getSpec() != null && q.getSpec().getHard() != null) {
                    one.put("requestsMemoryHard", str(q.getSpec().getHard().get("requests.memory")));
                    one.put("limitsMemoryHard", str(q.getSpec().getHard().get("limits.memory")));
                }
                if (q.getStatus() != null && q.getStatus().getUsed() != null) {
                    one.put("requestsMemoryUsed", str(q.getStatus().getUsed().get("requests.memory")));
                    one.put("limitsMemoryUsed", str(q.getStatus().getUsed().get("limits.memory")));
                }
                out.put(ns + "/" + q.getMetadata().getName(), one);
            }
        }
        return out;
    }

    private Map<String, Object> allocatableSnapshot(KubernetesClient client) {
        long total = 0;
        int nodes = 0;
        for (Node n : client.nodes().list().getItems()) {
            Quantity q = n.getStatus() == null || n.getStatus().getAllocatable() == null
                    ? null : n.getStatus().getAllocatable().get("memory");
            if (q != null) {
                total += numeric(q);
                nodes++;
            }
        }
        long used = 0;
        for (Pod p : client.pods().inAnyNamespace().list().getItems()) {
            used += effectiveRequest(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("totalBytes", total);
        out.put("usedRequestBytes", used);
        out.put("total", mi(total));
        out.put("usedRequest", mi(used));
        return out;
    }

    // ================================================================ POST 应用

    /** 应用请求：不带 request/limit = 应用服务端现算的建议；dryRun 缺省 true（显式 false 才动手）。 */
    public record ApplyRequest(String namespace, String kind, String name,
                               String request, String limit, Boolean dryRun) {
    }

    public Map<String, Object> apply(ApplyRequest req) {
        if (req == null || blank(req.namespace()) || blank(req.kind()) || blank(req.name())) {
            throw ApiException.badRequest("请提供 namespace / kind / name");
        }
        String kind = req.kind().toLowerCase();
        if (!kind.equals("deployment") && !kind.equals("statefulset") && !kind.equals("daemonset")) {
            throw ApiException.badRequest("kind 只能是 deployment / statefulset / daemonset");
        }
        boolean dryRun = req.dryRun() == null || req.dryRun();
        boolean overrideMode = !blank(req.request()) || !blank(req.limit());

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Workload w = load(client, req.namespace(), kind, req.name());
            List<P95Sample> samples = overrideMode ? List.of() : p95Samples();

            Map<String, long[]> targets = new LinkedHashMap<>();
            List<Map<String, Object>> containerPlans = new ArrayList<>();
            List<String> rewritePlan = new ArrayList<>();

            for (Container c : w.containers()) {
                String cname = c.getName();
                long oldReq = resource(c, true);
                long oldLim = resource(c, false);
                Declared declared = declaredOf(client, w.name(), c);
                long newReq;
                long newLim;
                if (overrideMode) {
                    newLim = blank(req.limit()) ? oldLim : parseSize(req.limit(), "limit");
                    newReq = blank(req.request()) ? oldReq : parseSize(req.request(), "request");
                    if (newLim < STEP || newReq < STEP || newLim > MAX_LIMIT || newReq > MAX_LIMIT) {
                        throw ApiException.badRequest("内存需在 128Mi - 8Gi 之间");
                    }
                } else {
                    long p95 = -1;
                    for (P95Sample s : samples) {
                        if (s.namespace().equals(w.namespace()) && s.container().equals(cname)
                                && belongsTo(s.pod(), w.name())) {
                            p95 = Math.max(p95, s.bytes());
                        }
                    }
                    Suggestion s = suggest(p95, declared == null ? 0 : declared.bytes(),
                            declared != null && declared.xmx());
                    if (s.insufficient()) {
                        throw ApiException.badRequest("「" + w.name() + "/" + cname + "」样本不足，"
                                + s.reason() + "——不得凭编造值应用");
                    }
                    newReq = s.requestBytes();
                    newLim = s.limitBytes();
                }
                if (newReq > newLim) {
                    throw ApiException.badRequest("request(" + mi(newReq) + ") 不能大于 limit("
                            + mi(newLim) + ")");
                }

                // 倒挂检查与联动改写规划
                if (declared != null && newLim < declaredFloor(declared.bytes(), declared.xmx())) {
                    if (declared.xmx() && declared.inTemplate() && xmxRewritable(c)) {
                        long newXmx = fitXmx(newLim);
                        rewritePlan.add(cname + ": " + declared.source() + " " + declared.rawToken()
                                + " → -Xmx" + (newXmx / MIB) + "m（同一动作联动改写，防倒挂）");
                    } else if (!declared.xmx() && LINKAGE.containsKey(w.name())) {
                        for (ConfigLink link : LINKAGE.get(w.name())) {
                            rewritePlan.add(cname + ": configmap " + link.configMap() + "/" + link.lineKey()
                                    + " " + mi(declared.bytes()) + " → " + mi(newLim) + "（同一动作联动改写）");
                        }
                    } else {
                        throw ApiException.badRequest("倒挂拒绝：新 limit=" + mi(newLim)
                                + " < 进程声明 " + mi(declared.bytes()) + "（" + declared.source()
                                + "），且该声明位置不可安全改写（启动脚本模式串或无联动登记）；"
                                + "拒绝把 limit 压到声明之下，请调大 limit 或先人工处理配置");
                    }
                }
                // Flink 联动登记组件：无论扩缩，process.size 都跟 limit 对齐（构造上排除未来倒挂）
                if (declared != null && !declared.xmx() && LINKAGE.containsKey(w.name())
                        && declared.bytes() != newLim) {
                    for (ConfigLink link : LINKAGE.get(w.name())) {
                        String entry = cname + ": configmap " + link.configMap() + "/" + link.lineKey()
                                + " " + mi(declared.bytes()) + " → " + mi(newLim) + "（与 limit 对齐）";
                        if (!rewritePlan.contains(entry)) {
                            rewritePlan.add(entry);
                        }
                    }
                }
                targets.put(cname, new long[]{newReq, newLim});
                Map<String, Object> cp = new LinkedHashMap<>();
                cp.put("container", cname);
                cp.put("oldRequest", oldReq > 0 ? mi(oldReq) : "未设置");
                cp.put("oldLimit", oldLim > 0 ? mi(oldLim) : "未设置");
                cp.put("newRequest", mi(newReq));
                cp.put("newLimit", mi(newLim));
                cp.put("declared", declared == null ? null : mi(declared.bytes()));
                containerPlans.add(cp);
            }

            // ---- 硬闸 ①：命名空间 ResourceQuota（request 增量 × 含 surge 副本）
            long deltaPerReplica = 0;
            for (Container c : w.containers()) {
                long[] t = targets.get(c.getName());
                long oldEffective = resource(c, true) > 0 ? resource(c, true) : resource(c, false);
                deltaPerReplica += t[0] - oldEffective;
            }
            int surge = "deployment".equals(kind) ? 1 : 0; // sts/ds 滚动不额外起 surge 副本
            long deltaTotal = deltaPerReplica * (Math.max(w.replicas(), 1) + surge);
            List<String> gates = new ArrayList<>();
            for (var q : client.resourceQuotas().inNamespace(w.namespace()).list().getItems()) {
                Quantity hardQ = q.getSpec() == null || q.getSpec().getHard() == null
                        ? null : q.getSpec().getHard().get("requests.memory");
                Quantity usedQ = q.getStatus() == null || q.getStatus().getUsed() == null
                        ? null : q.getStatus().getUsed().get("requests.memory");
                if (hardQ == null || usedQ == null) {
                    continue;
                }
                long hard = numeric(hardQ);
                long used = numeric(usedQ);
                gates.add("配额 " + q.getMetadata().getName() + " requests.memory：现用 " + mi(used)
                        + "，应用后约 " + mi(Math.max(0, used + deltaTotal)) + "，硬限 " + mi(hard));
                if (used + deltaTotal > hard) {
                    throw ApiException.badRequest("超配额拒绝：" + w.namespace() + " 的 "
                            + q.getMetadata().getName() + " requests.memory 已用 " + mi(used)
                            + "/硬限 " + mi(hard) + "，本次净增 " + mi(deltaTotal) + "，放不下");
                }
            }
            // ---- 硬闸 ②：节点 allocatable 合计
            long allocTotal = 0;
            for (Node n : client.nodes().list().getItems()) {
                Quantity q = n.getStatus() == null || n.getStatus().getAllocatable() == null
                        ? null : n.getStatus().getAllocatable().get("memory");
                if (q != null) {
                    allocTotal += numeric(q);
                }
            }
            long usedReq = 0;
            for (Pod p : client.pods().inAnyNamespace().list().getItems()) {
                usedReq += effectiveRequest(p);
            }
            gates.add("节点 allocatable 合计 " + mi(allocTotal) + "：全集群已申请 " + mi(usedReq)
                    + "，应用后约 " + mi(usedReq + deltaTotal));
            if (usedReq + deltaTotal > allocTotal) {
                throw ApiException.badRequest("超节点余量拒绝：节点 allocatable 合计 " + mi(allocTotal)
                        + "，已申请 " + mi(usedReq) + "，本次净增 " + mi(deltaTotal) + "，Pod 会 Pending");
            }

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("namespace", w.namespace());
            plan.put("kind", w.kind());
            plan.put("name", w.name());
            plan.put("dryRun", dryRun);
            plan.put("mode", overrideMode ? "override" : "suggestion");
            plan.put("containers", containerPlans);
            plan.put("gates", gates);
            if (dryRun) {
                plan.put("rewritePlan", rewritePlan);
                plan.put("applied", false);
                return plan;
            }

            // ---- 真应用顺序：先联动 ConfigMap，再 patch 工作负载（模板变更自动滚动）
            applyConfigMapLinks(client, w.name(), targets);
            patchWorkload(client, w, targets, rewritePlan);
            log.info("rightsizing applied ns={} kind={} name={} containers={} rewrites={}",
                    w.namespace(), w.kind(), w.name(), targets.size(), rewritePlan.size());
            plan.put("rewritePlan", rewritePlan);
            plan.put("applied", true);
            return plan;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "应用右调建议失败: " + e.getMessage());
        }
    }

    private void applyConfigMapLinks(KubernetesClient client, String workload, Map<String, long[]> targets) {
        if (targets.isEmpty()) {
            return;
        }
        long newLim = targets.values().iterator().next()[1];
        for (ConfigLink link : LINKAGE.getOrDefault(workload, List.of())) {
            ConfigMap cm = client.configMaps().inNamespace(link.namespace()).withName(link.configMap()).get();
            if (cm == null || cm.getData() == null || !cm.getData().containsKey(link.dataKey())) {
                continue;
            }
            String data = cm.getData().get(link.dataKey());
            String replacement = (newLim / MIB) + "m";
            String updated = data.replaceAll(
                    "(?m)^(" + Pattern.quote(link.lineKey()) + ":\\s*)\\d+[kKmMgG]",
                    "$1" + Matcher.quoteReplacement(replacement));
            if (!updated.equals(data)) {
                client.configMaps().inNamespace(link.namespace()).withName(link.configMap()).edit(m -> {
                    m.getData().put(link.dataKey(), updated);
                    return m;
                });
            }
        }
    }

    private void patchWorkload(KubernetesClient client, Workload w, Map<String, long[]> targets,
                               List<String> rewriteLog) {
        switch (w.kind()) {
            case "statefulset" -> client.apps().statefulSets()
                    .inNamespace(w.namespace()).withName(w.name()).edit(s -> {
                        applyTemplate(s.getSpec().getTemplate(), targets, rewriteLog);
                        return s;
                    });
            case "daemonset" -> client.apps().daemonSets()
                    .inNamespace(w.namespace()).withName(w.name()).edit(d -> {
                        applyTemplate(d.getSpec().getTemplate(), targets, rewriteLog);
                        return d;
                    });
            default -> client.apps().deployments()
                    .inNamespace(w.namespace()).withName(w.name()).edit(d -> {
                        applyTemplate(d.getSpec().getTemplate(), targets, rewriteLog);
                        return d;
                    });
        }
    }

    private static void applyTemplate(io.fabric8.kubernetes.api.model.PodTemplateSpec tpl,
                                      Map<String, long[]> targets, List<String> rewriteLog) {
        if (tpl == null || tpl.getSpec() == null || tpl.getSpec().getContainers() == null) {
            return;
        }
        for (Container c : tpl.getSpec().getContainers()) {
            long[] t = targets.get(c.getName());
            if (t == null) {
                continue;
            }
            applyPair(c, new Quantity(t[0] / MIB + "Mi"), new Quantity(t[1] / MIB + "Mi"));
            rewriteTemplateXmx(c, t[1], rewriteLog);
        }
    }

    /**
     * -Xmx 可改写判定：command/args 里含 -Xmx 的条目必须是「纯开关」（整项就是一个
     * -XmxNNNNm）。doris-fe 的 sed 模式串 `s/-Xmx8192m -Xms8192m/-Xmx4096m .../` 里，
     * 搜索位的 8192m 必须原样保留才匹配容器内 fe.conf 默认值——这类字符串就地改写会
     * 把守卫改瞎，所以判定为不可改写，上层对倒挂直接拒绝而不是硬改。
     */
    static boolean xmxRewritable(Container c) {
        if (c.getCommand() != null) {
            for (String s : c.getCommand()) {
                if (s != null && XMX.matcher(s).find() && !s.trim().matches("-Xmx\\d+[kKmMgG]")) {
                    return false;
                }
            }
        }
        if (c.getArgs() != null) {
            for (String s : c.getArgs()) {
                if (s != null && XMX.matcher(s).find() && !s.trim().matches("-Xmx\\d+[kKmMgG]")) {
                    return false;
                }
            }
        }
        return true; // env 值是平台自己的配置，就地改写没有模式串语义
    }

    /**
     * 模板内 -Xmx 就地改写：把所有解析值「超过新上限换算的 -Xmx」的纯开关条目与 env 值
     * 压到 {@code -XmxN m}；-Xms 超过新 -Xmx 时一并压低（Xms ≤ Xmx 是 JVM 启动硬约束）。
     */
    static void rewriteTemplateXmx(Container c, long newLimitBytes, List<String> rewriteLog) {
        Declared declared = templateDeclared(c);
        if (declared == null || !declared.xmx()
                || newLimitBytes >= declaredFloor(declared.bytes(), true)) {
            return;
        }
        if (!xmxRewritable(c)) {
            return; // apply() 已对不可改写位置先行拒绝；这里兜底绝不留半途改写
        }
        long newXmx = fitXmx(newLimitBytes);
        String xmxNew = "-Xmx" + (newXmx / MIB) + "m";
        boolean changed = false;
        if (c.getCommand() != null) {
            List<String> rewritten = rewriteFlags(c.getCommand(), newXmx);
            changed |= !rewritten.equals(c.getCommand());
            c.setCommand(rewritten);
        }
        if (c.getArgs() != null) {
            List<String> rewritten = rewriteFlags(c.getArgs(), newXmx);
            changed |= !rewritten.equals(c.getArgs());
            c.setArgs(rewritten);
        }
        if (c.getEnv() != null) {
            for (EnvVar e : c.getEnv()) {
                if (e.getValue() == null || !XMX.matcher(e.getValue()).find()) {
                    continue;
                }
                String after = rewriteEnvValue(e.getValue(), newXmx);
                if (!after.equals(e.getValue())) {
                    e.setValue(after);
                    changed = true;
                }
            }
        }
        if (changed) {
            rewriteLog.add(c.getName() + ": 模板超过 " + xmxNew + " 的 -Xmx/-Xms 已压低"
                    + "（limit=" + mi(newLimitBytes) + "）");
        }
    }

    /** 纯开关条目：-Xmx 超新值压到 newXmx；-Xms 超 -Xmx 跟着压。 */
    static List<String> rewriteFlags(List<String> list, long newXmxBytes) {
        if (list == null) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            if (s == null) {
                out.add(null);
                continue;
            }
            String t = s.trim();
            if (t.matches("-Xmx\\d+[kKmMgG]") && jvmToken(s, XMX) > newXmxBytes) {
                out.add("-Xmx" + (newXmxBytes / MIB) + "m");
            } else if (t.matches("-Xms\\d+[kKmMgG]") && jvmToken(s, XMS) > newXmxBytes) {
                out.add("-Xms" + (newXmxBytes / MIB) + "m");
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** env 值里的 JVM 开关串：把所有超限 -Xmx 换成新值，再压超限 -Xms。 */
    static String rewriteEnvValue(String text, long newXmxBytes) {
        Matcher m = XMX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long v = jvmSize(m.group(1), m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    v > newXmxBytes ? "-Xmx" + (newXmxBytes / MIB) + "m" : m.group()));
        }
        m.appendTail(sb);
        return clampXms(sb.toString(), newXmxBytes);
    }

    private static long jvmToken(String s, Pattern p) {
        Matcher m = p.matcher(s.trim());
        return m.find() ? jvmSize(m.group(1), m.group(2)) : 0;
    }

    /** -Xms 超过上限的压到上限；不动其它内容。 */
    static String clampXms(String text, long capBytes) {
        Matcher m = XMS.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long ms = jvmSize(m.group(1), m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    ms <= capBytes ? m.group() : "-Xms" + (capBytes / MIB) + "m"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ================================================================ 进程声明扫描

    /** rawToken 保留原写法（-Xmx3g / -Xmx1024m），改写时只替换等值 token 才安全。 */
    record Declared(long bytes, boolean xmx, String source, boolean inTemplate,
                    String number, String unit) {

        String rawToken() {
            return "-Xmx" + number + unit;
        }
    }

    private Declared declaredOf(KubernetesClient client, String workload, Container c) {
        Declared best = templateDeclared(c);
        for (ConfigLink link : LINKAGE.getOrDefault(workload, List.of())) {
            ConfigMap cm = client.configMaps().inNamespace(link.namespace())
                    .withName(link.configMap()).get();
            if (cm == null || cm.getData() == null) {
                continue;
            }
            String data = cm.getData().get(link.dataKey());
            if (data == null) {
                continue;
            }
            Matcher m = Pattern.compile("(?m)^" + Pattern.quote(link.lineKey())
                    + ":\\s*(\\d+)([kKmMgG])").matcher(data);
            if (m.find()) {
                Declared d = new Declared(jvmSize(m.group(1), m.group(2)), false,
                        "configmap " + link.configMap() + "/" + link.lineKey(), false, null, null);
                if (best == null || d.bytes() > best.bytes()) {
                    best = d;
                }
            }
        }
        return best;
    }

    /**
     * 扫 command/args/env 字符串里的 -Xmx。取每个字符串<b>最后一个</b>匹配：
     * sed 表达式 `s/-Xmx8192m -Xms8192m/-Xmx4096m -Xms2048m/` 里前一个是「搜索模式位」的
     * 旧默认值，后一个才是实际生效值。跨字符串取最大者（同组件多处声明以高者为准）。
     */
    static Declared templateDeclared(Container c) {
        Declared best = null;
        List<Object[]> sources = new ArrayList<>();
        if (c.getCommand() != null) {
            sources.add(new Object[]{"command", String.join(" ", c.getCommand())});
        }
        if (c.getArgs() != null) {
            sources.add(new Object[]{"args", String.join(" ", c.getArgs())});
        }
        if (c.getEnv() != null) {
            for (EnvVar e : c.getEnv()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    sources.add(new Object[]{"env " + e.getName(), e.getValue()});
                }
            }
        }
        for (Object[] s : sources) {
            String text = (String) s[1];
            Matcher m = XMX.matcher(text);
            String number = null;
            String unit = null;
            while (m.find()) {
                number = m.group(1);
                unit = m.group(2);
            }
            if (number == null) {
                continue;
            }
            Declared d = new Declared(jvmSize(number, unit), true, s[0] + "(-Xmx)", true, number, unit);
            if (best == null || d.bytes() > best.bytes()) {
                best = d;
            }
        }
        return best;
    }

    /** JVM/Flink 尺寸单位（1024 进制）：k=Ki、m=Mi、g=Gi。 */
    static long jvmSize(String number, String unit) {
        long n = Long.parseLong(number);
        return switch (unit.toLowerCase()) {
            case "k" -> n * 1024L;
            case "g" -> n * GIB;
            default -> n * MIB;
        };
    }

    // ================================================================ K8s 小工具

    record Workload(String namespace, String kind, String name, int replicas, List<Container> containers) {
    }

    private Workload load(KubernetesClient client, String ns, String kind, String name) {
        switch (kind) {
            case "statefulset" -> {
                StatefulSet s = client.apps().statefulSets().inNamespace(ns).withName(name).get();
                if (s == null) {
                    throw ApiException.notFound("服务不存在: " + ns + "/" + name);
                }
                return new Workload(ns, kind, name,
                        s.getSpec().getReplicas() == null ? 0 : s.getSpec().getReplicas(),
                        containersOf(s.getSpec().getTemplate()));
            }
            case "daemonset" -> {
                DaemonSet d = client.apps().daemonSets().inNamespace(ns).withName(name).get();
                if (d == null) {
                    throw ApiException.notFound("服务不存在: " + ns + "/" + name);
                }
                int desired = d.getStatus() == null || d.getStatus().getDesiredNumberScheduled() == null
                        ? 1 : d.getStatus().getDesiredNumberScheduled();
                return new Workload(ns, kind, name, desired, containersOf(d.getSpec().getTemplate()));
            }
            default -> {
                Deployment d = client.apps().deployments().inNamespace(ns).withName(name).get();
                if (d == null) {
                    throw ApiException.notFound("服务不存在: " + ns + "/" + name);
                }
                return new Workload(ns, kind, name,
                        d.getSpec().getReplicas() == null ? 0 : d.getSpec().getReplicas(),
                        containersOf(d.getSpec().getTemplate()));
            }
        }
    }

    private static List<String> scopedNamespaces(KubernetesClient client) {
        return client.namespaces().list().getItems().stream()
                .map(n -> n.getMetadata().getName())
                .filter(n -> PLATFORM_NAMESPACE.equals(n) || SPARK_NAMESPACE.equals(n)
                        || n.startsWith(TEST_NAMESPACE_PREFIX))
                .sorted().toList();
    }

    private static List<Container> containersOf(io.fabric8.kubernetes.api.model.PodTemplateSpec tpl) {
        return tpl == null || tpl.getSpec() == null || tpl.getSpec().getContainers() == null
                ? List.of() : tpl.getSpec().getContainers();
    }

    private static long resource(Container c, boolean request) {
        ResourceRequirements rr = c.getResources();
        if (rr == null) {
            return 0;
        }
        Map<String, Quantity> map = request ? rr.getRequests() : rr.getLimits();
        if (map == null || map.get("memory") == null) {
            return 0;
        }
        return map.get("memory").getNumericalAmount().longValue();
    }

    private static void applyPair(Container c, Quantity request, Quantity limit) {
        if (c.getResources() == null) {
            c.setResources(new ResourceRequirements());
        }
        if (c.getResources().getRequests() == null) {
            c.getResources().setRequests(new LinkedHashMap<>());
        }
        if (c.getResources().getLimits() == null) {
            c.getResources().setLimits(new LinkedHashMap<>());
        }
        c.getResources().getRequests().put("memory", request);
        c.getResources().getLimits().put("memory", limit);
    }

    /** 调度器口径的 Pod 有效内存申请：容器 requests 之和 vs init 容器最大值取大；终态 Pod 不计。 */
    static long effectiveRequest(Pod p) {
        if (p.getSpec() == null) {
            return 0;
        }
        String phase = p.getStatus() == null || p.getStatus().getPhase() == null
                ? "" : p.getStatus().getPhase();
        if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
            return 0;
        }
        long sum = 0;
        for (Container c : p.getSpec().getContainers() == null
                ? List.<Container>of() : p.getSpec().getContainers()) {
            long r = resource(c, true);
            sum += r > 0 ? r : resource(c, false);
        }
        long initMax = 0;
        for (Container c : p.getSpec().getInitContainers() == null
                ? List.<Container>of() : p.getSpec().getInitContainers()) {
            long r = resource(c, true);
            initMax = Math.max(initMax, r > 0 ? r : resource(c, false));
        }
        return Math.max(sum, initMax);
    }

    private static long numeric(Quantity q) {
        return q == null ? 0 : q.getNumericalAmount().longValue();
    }

    private static String str(Quantity q) {
        return q == null ? "" : q.toString();
    }

    /** 接受 512Mi / 2Gi / 2G / 2048m（m/g 按 1024 进制解释，与 JVM/Flink 口径一致）；裸数字按 Mi。 */
    static long parseSize(String raw, String what) {
        String value = raw == null ? "" : raw.trim();
        Matcher m = Pattern.compile("^(\\d{1,6}(?:\\.\\d{1,3})?)(Mi|Gi|Ki|G|g|M|m|T|t|K|k)?$").matcher(value);
        if (!m.matches()) {
            throw ApiException.badRequest(what + " 格式应为 512Mi / 2G / 2048m 这样的值");
        }
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "Mi" : m.group(2).toLowerCase();
        return switch (unit) {
            case "k", "ki" -> (long) (n * 1024);
            case "g", "gi" -> (long) (n * GIB);
            case "t", "ti" -> (long) (n * 1024 * GIB);
            default -> (long) (n * MIB);
        };
    }

    private static String mi(long bytes) {
        return (bytes / MIB) + "Mi";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
