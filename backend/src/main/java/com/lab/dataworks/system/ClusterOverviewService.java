package com.lab.dataworks.system;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群级组件视图：Doris / SeaTunnel Zeta / MinIO / HDFS，每个集群一张卡、每个节点一行。
 *
 * <p>与「服务资源」（按工作负载）互补：这里按集群组织，指标口径统一为
 * CPU 核数、内存用量/配额、磁盘可用/总量，数据来自 cAdvisor（Pod 级）与
 * 各组件自己的 Prometheus 指标（Doris BE 数据盘、MinIO 集群容量）。</p>
 */
@Service
public class ClusterOverviewService {

    private static final Logger log = LoggerFactory.getLogger(ClusterOverviewService.class);
    private final MonitorNamespaces namespaces;

    /** 集群里的一个节点（一行）。 */
    public record ClusterNode(String name, String ip, String status, boolean healthy,
                              String cpu, String memory, String disk, String extra) {
    }

    /**
     * 一个集群：summary 是合计，nodes 是逐节点明细。
     *
     * <p>{@code scaleKind} 非空表示这个集群可以按副本数扩缩容（改对应工作负载的 replicas）；
     * 为空时 {@code scaleReason} 说明为什么不能——比给一个点了会失败的按钮好。</p>
     */
    /**
     * {@code metricsOk} 为指标口径与 Pod 口径的分离标记：非 null 表示该卡片的服务判定
     * 只看 Pod 实况，Prometheus 只出容量数字；指标抓取失败时为 false，页面单显
     * 「指标采集故障」，不再把「监控瞎了」渲染成「服务坏了」（BUG-MON-POD-FALSEPOS-1）。
     * null = 该卡片不适用（暂未做两口径分离）。
     */
    public record ClusterInfo(String key, String name, String role, String status, boolean healthy,
                              String summary, String note, List<ClusterNode> nodes,
                              String scaleKind, String scaleName, Integer scaleReplicas,
                              String scaleReason, Boolean metricsOk) {
    }

    private final PrometheusClient prom;
    private final String hdfsWebhdfs;

    public ClusterOverviewService(PrometheusClient prom,
            @Value("${platform.storage.hdfs.webhdfs:${HDFS_WEBHDFS_URL:}}") String hdfsWebhdfs,
            MonitorNamespaces namespaces) {
        this.prom = prom;
        this.hdfsWebhdfs = hdfsWebhdfs == null ? "" : hdfsWebhdfs.trim();
        this.namespaces = namespaces;
    }

    public List<ClusterInfo> list() {
        Map<String, Long> memUsage = podMemory();
        Map<String, Double> cpuUsage = podCpu();
        Map<String, Pod> pods = podsByName();
        Map<String, String> nodeIp = nodeIps();

        List<ClusterInfo> out = new ArrayList<>();
        out.add(doris(cpuUsage, memUsage, pods, nodeIp));
        out.add(zeta(cpuUsage, memUsage, pods, nodeIp));
        out.add(minio(cpuUsage, memUsage, pods, nodeIp));
        out.add(hdfs(cpuUsage, memUsage, pods, nodeIp));
        return out;
    }

    // ---------------------------------------------------------------- Doris

    private ClusterInfo doris(Map<String, Double> cpu, Map<String, Long> mem,
                              Map<String, Pod> pods, Map<String, String> nodeIp) {
        double beUp = prom.scalar("sum(up{job=\"doris-be\"})", 0);
        double beTotal = prom.scalar("count(up{job=\"doris-be\"})", 0);
        double feUp = prom.scalar("sum(up{job=\"doris-fe\"})", 0);

        // instance → {avail, total, tablets, up}
        Map<String, double[]> beStats = new LinkedHashMap<>();
        collect(beStats, "doris_be_disks_avail_capacity", 0);
        collect(beStats, "doris_be_disks_total_capacity", 1);
        collect(beStats, "doris_be_tablet_version_num_distribution_count", 2);
        collect(beStats, "up{job=\"doris-be\"}", 3);

        // 每个 K8s 节点一行；Doris 的 FE 在 k8s-node-1 上
        List<ClusterNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> e : nodeIp.entrySet()) {
            String nodeName = e.getKey();
            String ip = e.getValue();
            double[] be = beStats.get(ip + ":8040");
            boolean hasBe = be != null;
            boolean alive = hasBe && be[3] >= 1;
            boolean hasFe = pods.values().stream().anyMatch(p -> "doris-fe".equals(app(p))
                    && nodeName.equals(p.getSpec() == null ? null : p.getSpec().getNodeName()));
            String status = !hasBe ? "未部署 BE"
                    : alive ? (hasFe ? "FE + BE 在线" : "BE 在线") : "心跳异常";
            String disk = hasBe
                    ? String.format("可用 %s / %s", gb(be[0]), gb(be[1]))
                    : "-";
            String extra = hasBe ? String.format("tablet %.0f", be[2]) : "无 BE";
            nodes.add(new ClusterNode(nodeName, ip, status, alive,
                    sumCpu(cpu, pods, nodeName, "doris-"),
                    sumMemory(mem, pods, nodeName, "doris-"),
                    disk, extra));
        }

        double memUsed = sumAll(mem, pods, "doris-");
        boolean healthy = beUp > 0 && feUp > 0 && beUp >= beTotal;
        return new ClusterInfo("doris", "Doris 集群", "FE + BE（数仓存储底座）",
                healthy ? "健康" : "降级", healthy,
                String.format("%.0f BE / %.0f FE 在线 · 内存 %s · CPU %s",
                        beUp, feUp, gb(memUsed), cores(sumAllCpu(cpu, pods, "doris-"))),
                "缩容顺序：开始退役 → tablet 迁空 → 下掉 BE（详见「服务资源 → doris-be → 节点管理」）",
                nodes, null, null, null,
                "BE 跟着 K8s 节点走：用「服务资源 → doris-be → 节点管理」加/退节点；FE 单副本不可扩",
                null);
    }

    // ---------------------------------------------------------------- Zeta

    private ClusterInfo zeta(Map<String, Double> cpu, Map<String, Long> mem,
                             Map<String, Pod> pods, Map<String, String> nodeIp) {
        double zetaUp = prom.scalar("sum(up{job=\"seatunnel\"})", 0);
        double engineNodes = prom.scalar("max(node_count{job=\"seatunnel\"})", 0);
        List<ClusterNode> nodes = new ArrayList<>();
        for (Pod pod : sortedPods(pods, "seatunnel-")) {
            nodes.add(new ClusterNode(pod.getMetadata().getName(),
                    nodeAddr(pod, nodeIp), ready(pod) ? "在线" : phase(pod), ready(pod),
                    oneCpu(cpu, pod), oneMemory(mem, pod), "不落盘",
                    "Zeta 节点 · " + nodeName(pod)));
        }
        boolean healthy = zetaUp > 0 && engineNodes > 0;
        return new ClusterInfo("zeta", "SeaTunnel Zeta 集群", "数据集成引擎（同步作业跑在这里）",
                healthy ? "健康" : "降级", healthy,
                String.format("引擎自报 %.0f 节点 · 运行中作业 %d", engineNodes, runningJobs()),
                "扩缩容直接改 seatunnel StatefulSet 副本数，新节点会自动加入 Zeta 集群",
                nodes, "statefulset", "seatunnel", zetaReplicas(), null, null);
    }

    // ---------------------------------------------------------------- MinIO

    private ClusterInfo minio(Map<String, Double> cpu, Map<String, Long> mem,
                              Map<String, Pod> pods, Map<String, String> nodeIp) {
        // 曾经用 sum(up{job="minio"}) 判健康：抓取 token 一失效（实测 403），
        // 三个节点全 Ready 的 MinIO 被永远标成「降级」——「监控瞎了」被渲染成
        // 「服务坏了」。现在健康以 Pod 实况为准，Prometheus 只出容量数字，
        // 指标口径单独放 metricsOk，缺了不改状态（BUG-MON-POD-FALSEPOS-1）。
        double up = prom.scalar("sum(up{job=\"minio\"})", 0);
        double upCount = prom.scalar("count(up{job=\"minio\"})", 0);
        double used = prom.scalar("sum(minio_cluster_usage_total_bytes)", 0);
        double free = prom.scalar("sum(minio_cluster_capacity_usable_free_bytes)", 0);
        List<Pod> mp = sortedPods(pods, "minio-");
        long readyCnt = mp.stream().filter(ClusterOverviewService::ready).count();
        MinioView v = minioView(!mp.isEmpty(), mp.size(), readyCnt, used, free, up, upCount);
        List<ClusterNode> nodes = new ArrayList<>();
        for (Pod pod : mp) {
            nodes.add(new ClusterNode(pod.getMetadata().getName(), nodeAddr(pod, nodeIp),
                    ready(pod) ? "在线" : phase(pod), ready(pod),
                    oneCpu(cpu, pod), oneMemory(mem, pod),
                    // 纠删码把对象打散在多个 pod 的盘上，单节点盘量没有意义
                    v.totalBytes() > 0 ? "见合计（纠删码打散）" : "-",
                    "S3 节点 · " + nodeName(pod)));
        }
        return new ClusterInfo("minio", "MinIO 对象存储", "S3 后端（项目存储 / 冷热分离远端）",
                v.status(), v.healthy(), v.capacity(),
                "纠删码把对象切块分散到各节点，单节点磁盘没有独立意义；bucket warehouse",
                nodes, null, null, null,
                "MinIO 分布式模式不能改副本数：加节点要新增 server pool 并重启全部节点，缩容会丢数据",
                v.metricsOk());
    }

    /**
     * MinIO 两口径判定（纯函数，单测可打点）：
     * healthy 只看 Pod 实况；metricsOk 只看 Prometheus 抓取（全目标 up 才算采集正常）；
     * 指标故障不改状态，只在容量文案里说明未采集。
     */
    record MinioView(boolean healthy, boolean metricsOk, String status, String capacity,
                     double totalBytes) {
    }

    static MinioView minioView(boolean deployed, long podCount, long readyCount,
                               double usedBytes, double freeBytes, double up, double upCount) {
        boolean metricsOk = upCount >= 1 && up >= upCount;
        double total = usedBytes + freeBytes;
        if (!deployed) {
            return new MinioView(false, metricsOk, "未部署", "未部署 / Pod 已消失", total);
        }
        boolean healthy = readyCount == podCount;
        String capacity = total > 0
                ? String.format("集群容量 已用 %s / %s（%.1f%%）",
                        gb(usedBytes), gb(total), usedBytes / total * 100)
                : (metricsOk ? "容量采集中，暂无数据"
                        : "容量指标未采集（Prometheus 抓取失败，不影响服务判定）");
        return new MinioView(healthy, metricsOk, healthy ? "健康" : "降级", capacity, total);
    }

    // ---------------------------------------------------------------- HDFS

    private ClusterInfo hdfs(Map<String, Double> cpu, Map<String, Long> mem,
                             Map<String, Pod> pods, Map<String, String> nodeIp) {
        Pod nn = null;
        List<Pod> dns = new ArrayList<>();
        for (Pod pod : pods.values()) {
            String app = pod.getMetadata().getLabels() == null ? ""
                    : pod.getMetadata().getLabels().getOrDefault("app", "");
            if ("hdfs-namenode".equals(app)) {
                nn = pod;
            } else if ("hdfs-datanode".equals(app)) {
                dns.add(pod);
            }
        }
        dns.sort(Comparator.comparing(p -> p.getMetadata().getName()));
        List<ClusterNode> nodes = new ArrayList<>();
        if (nn != null) {
            nodes.add(new ClusterNode(nn.getMetadata().getName(), nodeAddr(nn, nodeIp),
                    ready(nn) ? "在线" : phase(nn), ready(nn),
                    oneCpu(cpu, nn), oneMemory(mem, nn), "-",
                    "NameNode · " + nodeName(nn)));
        }
        for (Pod dn : dns) {
            nodes.add(new ClusterNode(dn.getMetadata().getName(), nodeAddr(dn, nodeIp),
                    ready(dn) ? "在线" : phase(dn), ready(dn),
                    oneCpu(cpu, dn), oneMemory(mem, dn), "-",
                    "DataNode · " + nodeName(dn)));
        }
        if (nn == null && !hdfsWebhdfs.isEmpty()) {
            return hdfsExternal();
        }
        boolean deployed = nn != null;
        long readyDns = dns.stream().filter(ClusterOverviewService::ready).count();
        boolean healthy = deployed && ready(nn) && !dns.isEmpty() && readyDns == dns.size();
        Integer replicas = deployed ? hdfsDatanodeReplicas() : null;
        return new ClusterInfo("hdfs", "HDFS 集群", "项目存储后端（三选一）",
                deployed ? (healthy ? "健康" : "降级") : "未部署", healthy,
                String.format("NameNode %d · DataNode %d（就绪 %d）",
                        nn == null ? 0 : 1, dns.size(), readyDns),
                "扩缩容改 hdfs-datanode 副本数；dfs.replication=3，DataNode 达到 3 个时数据自动补到 3 份",
                nodes,
                deployed ? "statefulset" : null,
                deployed ? "hdfs-datanode" : null,
                replicas,
                deployed ? null : "HDFS 未部署",
                null);
    }

    /** K8s 外的 HDFS（三节点原生或客户已有）：通过 WebHDFS/JMX 探测。 */
    private ClusterInfo hdfsExternal() {
        String base = hdfsWebhdfs.replaceAll("/+$", "");
        boolean reachable = false;
        int liveDns = -1;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(base + "/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            reachable = resp.statusCode() == 200;
            if (reachable) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode beans = mapper.readTree(resp.body()).path("beans");
                if (beans.isArray() && beans.size() > 0) {
                    JsonNode bean = beans.get(0);
                    liveDns = bean.path("NumLiveDataNodes").asInt(-1);
                    String live = bean.path("LiveNodes").asText("");
                    if (liveDns < 0 && !live.isBlank()) {
                        try {
                            liveDns = mapper.readTree(live).size();
                        } catch (Exception ignored) {
                            // ignore
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("HDFS 外部探测失败 {}: {}", base, e.getMessage());
        }
        List<ClusterNode> nodes = new ArrayList<>();
        nodes.add(new ClusterNode("hdfs-namenode", base,
                reachable ? "在线" : "不可达", reachable,
                "-", "-", "-",
                "外部 HDFS · " + (liveDns >= 0 ? liveDns + " DataNode" : "DataNode 未知")));
        boolean healthy = reachable && liveDns > 0;
        return new ClusterInfo("hdfs", "HDFS 集群", "项目存储后端（三选一）",
                healthy ? "健康" : (reachable ? "降级" : "不可达"), healthy,
                "NameNode " + base + " · DataNode " + (liveDns >= 0 ? liveDns : "?"),
                "HDFS 在 K8s 外（三节点原生或客户已有），平台只通过 WebHDFS 访问；扩缩容由 Ansible/客户维护",
                nodes, null, null, null,
                "HDFS 不在 K8s 内，平台不提供副本扩缩；用 deploy.sh hdfs 或客户 HDFS 运维",
                null);
    }

    private Integer hdfsDatanodeReplicas() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (String ns : namespaces.all()) {
                var sts = client.apps().statefulSets().inNamespace(ns)
                        .withName("hdfs-datanode").get();
                if (sts != null && sts.getSpec() != null) {
                    return sts.getSpec().getReplicas();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("读取 hdfs-datanode 副本失败: {}", e.getMessage());
            return null;
        }
    }

    /** Zeta 的期望副本数从 StatefulSet 读；读不到时退化为当前 Pod 数。 */
    private int zetaReplicas() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (String ns : namespaces.all()) {
                var sts = client.apps().statefulSets().inNamespace(ns).withName("seatunnel").get();
                if (sts != null && sts.getSpec() != null && sts.getSpec().getReplicas() != null) {
                    return sts.getSpec().getReplicas();
                }
            }
        } catch (Exception e) {
            log.debug("读取 seatunnel StatefulSet 失败: {}", e.getMessage());
        }
        return 0;
    }

    // ---------------------------------------------------------------- 指标

    private void collect(Map<String, double[]> target, String promql, int index) {
        for (PrometheusClient.Sample s : prom.query(promql)) {
            String instance = s.labels().get("instance");
            if (instance == null) {
                continue;
            }
            target.computeIfAbsent(instance, k -> new double[4])[index] = s.value();
        }
    }

    private Map<String, Long> podMemory() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (PrometheusClient.Sample s : prom.query(
                "sum by (pod) (container_memory_working_set_bytes"
                        + "{namespace=~\"" + namespaces.regex() + "\",container!=\"\",container!=\"POD\"})")) {
            String pod = s.labels().get("pod");
            if (pod != null) {
                out.merge(pod, (long) s.value(), Long::sum);
            }
        }
        return out;
    }

    private Map<String, Double> podCpu() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (PrometheusClient.Sample s : prom.query(
                "sum by (pod) (rate(container_cpu_usage_seconds_total"
                        + "{namespace=~\"" + namespaces.regex() + "\",container!=\"\",container!=\"POD\"}[5m]))")) {
            String pod = s.labels().get("pod");
            if (pod != null) {
                out.merge(pod, s.value(), Double::sum);
            }
        }
        return out;
    }

    private Map<String, Pod> podsByName() {
        Map<String, Pod> out = new LinkedHashMap<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (String ns : namespaces.all()) {
                for (Pod pod : client.pods().inNamespace(ns).list().getItems()) {
                    out.put(pod.getMetadata().getName(), pod);
                }
            }
        } catch (Exception e) {
            log.warn("读取 Pod 失败: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, String> nodeIps() {
        Map<String, String> out = new LinkedHashMap<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (Node node : client.nodes().list().getItems()) {
                String ip = node.getStatus() == null || node.getStatus().getAddresses() == null
                        ? "" : node.getStatus().getAddresses().stream()
                        .filter(a -> "InternalIP".equals(a.getType()))
                        .map(a -> a.getAddress()).findFirst().orElse("");
                out.put(node.getMetadata().getName(), ip);
            }
        } catch (Exception e) {
            log.warn("读取节点失败: {}", e.getMessage());
        }
        return out;
    }

    private static List<Pod> sortedPods(Map<String, Pod> pods, String prefix) {
        return pods.values().stream()
                .filter(p -> p.getMetadata().getName().startsWith(prefix))
                .sorted((a, b) -> a.getMetadata().getName().compareTo(b.getMetadata().getName()))
                .toList();
    }

    private static String app(Pod pod) {
        return pod.getMetadata().getLabels() == null
                ? "" : pod.getMetadata().getLabels().getOrDefault("app", "");
    }

    /** 节点内网 IP（按 Pod 所在 K8s 节点查），查不到退化为 Pod IP。 */
    private static String nodeAddr(Pod pod, Map<String, String> nodeIp) {
        String ip = nodeIp.get(nodeName(pod));
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        return pod.getStatus() == null ? "" : String.valueOf(pod.getStatus().getPodIP());
    }

    private static String nodeName(Pod pod) {
        return pod.getSpec() == null || pod.getSpec().getNodeName() == null
                ? "-" : pod.getSpec().getNodeName();
    }

    private static String phase(Pod pod) {
        return pod.getStatus() == null ? "Unknown" : pod.getStatus().getPhase();
    }

    private static boolean ready(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return false;
        }
        return pod.getStatus().getContainerStatuses().stream().allMatch(c -> Boolean.TRUE.equals(c.getReady()));
    }

    private static String oneCpu(Map<String, Double> cpu, Pod pod) {
        return cores(cpu.getOrDefault(pod.getMetadata().getName(), 0d));
    }

    private static String oneMemory(Map<String, Long> mem, Pod pod) {
        long used = mem.getOrDefault(pod.getMetadata().getName(), 0L);
        double limit = memoryLimit(pod);
        if (used == 0 && limit == 0) {
            return "暂无数据";
        }
        if (limit == 0) {
            return gb(used) + "（未设上限）";
        }
        return String.format("%s / %s（%.0f%%）", gb(used), gb(limit),
                Math.min(100, used / limit * 100));
    }

    private static String sumCpu(Map<String, Double> cpu, Map<String, Pod> pods,
                                 String nodeName, String prefix) {
        double total = 0;
        for (Pod pod : pods.values()) {
            if (pod.getMetadata().getName().startsWith(prefix)
                    && pod.getSpec() != null && nodeName.equals(pod.getSpec().getNodeName())) {
                total += cpu.getOrDefault(pod.getMetadata().getName(), 0d);
            }
        }
        return cores(total);
    }

    private static String sumMemory(Map<String, Long> mem, Map<String, Pod> pods,
                                    String nodeName, String prefix) {
        long used = 0;
        double limit = 0;
        for (Pod pod : pods.values()) {
            if (pod.getMetadata().getName().startsWith(prefix)
                    && pod.getSpec() != null && nodeName.equals(pod.getSpec().getNodeName())) {
                used += mem.getOrDefault(pod.getMetadata().getName(), 0L);
                limit += memoryLimit(pod);
            }
        }
        if (used == 0 && limit == 0) {
            return "-";
        }
        if (limit == 0) {
            return gb(used) + "（未设上限）";
        }
        return String.format("%s / %s（%.0f%%）", gb(used), gb(limit),
                Math.min(100, used / limit * 100));
    }

    private static double sumAll(Map<String, Long> mem, Map<String, Pod> pods, String prefix) {
        long used = 0;
        for (Pod pod : pods.values()) {
            if (pod.getMetadata().getName().startsWith(prefix)) {
                used += mem.getOrDefault(pod.getMetadata().getName(), 0L);
            }
        }
        return used;
    }

    private static double sumAllCpu(Map<String, Double> cpu, Map<String, Pod> pods, String prefix) {
        double total = 0;
        for (Pod pod : pods.values()) {
            if (pod.getMetadata().getName().startsWith(prefix)) {
                total += cpu.getOrDefault(pod.getMetadata().getName(), 0d);
            }
        }
        return total;
    }

    private long runningJobs() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            long total = 0;
            for (String ns : namespaces.all()) {
                total += client.pods().inNamespace(ns).list().getItems().stream()
                        .filter(p -> p.getMetadata().getName().startsWith("sync-"))
                        .filter(p -> p.getStatus() != null
                                && "Running".equals(p.getStatus().getPhase()))
                        .count();
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Pod 的容器内存 limit 合计（字节）。 */
    private static double memoryLimit(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return 0;
        }
        double total = 0;
        for (Container c : pod.getSpec().getContainers()) {
            if (c.getResources() != null && c.getResources().getLimits() != null) {
                total += bytes(String.valueOf(c.getResources().getLimits().get("memory")));
            }
        }
        return total;
    }

    private static String cores(double value) {
        return String.format("%.2f 核", Math.max(0, value));
    }

    private static String gb(double bytes) {
        return String.format("%.2fG", Math.max(0, bytes) / (double) (1L << 30));
    }

    /** K8s 数量：Gi/Mi/Ki → 字节；字符串里可能带 {@code Quantity{...}} 包装。 */
    private static long bytes(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return 0;
        }
        String v = raw.trim();
        int brace = v.indexOf('}');
        if (brace >= 0) {
            v = v.substring(brace + 1);
        }
        String[] units = {"Ki", "Mi", "Gi", "Ti"};
        long[] factors = {1L << 10, 1L << 20, 1L << 30, 1L << 40};
        try {
            for (int i = 0; i < units.length; i++) {
                if (v.endsWith(units[i])) {
                    return (long) (Double.parseDouble(v.substring(0, v.length() - 2)) * factors[i]);
                }
            }
            return (long) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
