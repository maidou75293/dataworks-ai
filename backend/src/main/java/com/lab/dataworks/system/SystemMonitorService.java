package com.lab.dataworks.system;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 系统监控总览：节点、Pod、工作负载、核心组件、告警数量。
 *
 * <p>节点 CPU/内存/磁盘来自 node-exporter；Pod 与 Deployment 状态来自
 * K8s API。Prometheus 不可用时不报错，只是对应百分比为空。</p>
 */
@Service
public class SystemMonitorService {

    private final K8sSnapshotService k8s;
    private final PrometheusClient prom;
    private final JdbcTemplate jdbc;
    private final ClusterOverviewService clusters;
    private final MonitorNamespaces namespaces;

    public SystemMonitorService(K8sSnapshotService k8s, PrometheusClient prom, JdbcTemplate jdbc,
                                ClusterOverviewService clusters, MonitorNamespaces namespaces) {
        this.k8s = k8s;
        this.prom = prom;
        this.jdbc = jdbc;
        this.clusters = clusters;
        this.namespaces = namespaces;
    }

    public Map<String, Object> overview() {
        K8sSnapshotService.Snapshot snap = k8s.snapshot();

        Map<String, Double> cpu = prom.byLabel("""
                100 - (avg by (node) (rate(node_cpu_seconds_total{job="node-exporter",mode="idle"}[5m])) * 100)
                """, "node");
        Map<String, Double> mem = prom.byLabel("""
                100 * (1 - node_memory_MemAvailable_bytes{job="node-exporter"}
                        / node_memory_MemTotal_bytes{job="node-exporter"})
                """, "node");
        Map<String, Double> disk = prom.byLabel("""
                100 * (1 - node_filesystem_avail_bytes{job="node-exporter",mountpoint="/",fstype!~"tmpfs|overlay|squashfs"}
                        / node_filesystem_size_bytes{job="node-exporter",mountpoint="/",fstype!~"tmpfs|overlay|squashfs"})
                """, "node");

        // 磁盘/内存的真实容量与可用量：页面要显示「还差多少」而不只是百分比
        Map<String, Double> diskSize = prom.byLabel("""
                node_filesystem_size_bytes{job="node-exporter",mountpoint="/",fstype!~"tmpfs|overlay|squashfs"}
                """, "node");
        Map<String, Double> diskAvail = prom.byLabel("""
                node_filesystem_avail_bytes{job="node-exporter",mountpoint="/",fstype!~"tmpfs|overlay|squashfs"}
                """, "node");
        Map<String, Double> memTotal = prom.byLabel("""
                node_memory_MemTotal_bytes{job="node-exporter"}
                """, "node");
        Map<String, Double> memAvail = prom.byLabel("""
                node_memory_MemAvailable_bytes{job="node-exporter"}
                """, "node");

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (K8sSnapshotService.NodeInfo n : snap.nodes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", n.name());
            row.put("ready", n.ready());
            row.put("schedulable", n.schedulable());
            row.put("internalIp", n.internalIp());
            row.put("cpuCapacity", n.cpuCapacity());
            row.put("memoryCapacity", n.memoryCapacity());
            row.put("pods", n.podCount());
            row.put("cpuPercent", round(cpu.get(n.name())));
            row.put("memoryPercent", round(mem.get(n.name())));
            row.put("diskPercent", round(disk.get(n.name())));
            row.put("diskTotalGb", gb(diskSize.get(n.name())));
            row.put("diskFreeGb", gb(diskAvail.get(n.name())));
            row.put("memoryTotalGb", gb(memTotal.get(n.name())));
            row.put("memoryUsedGb", gb(memTotal.containsKey(n.name()) && memAvail.containsKey(n.name())
                    ? memTotal.get(n.name()) - memAvail.get(n.name()) : null));
            nodes.add(row);
        }

        List<Map<String, Object>> abnormal = new ArrayList<>();
        int running = 0;
        for (K8sSnapshotService.PodInfo p : snap.pods()) {
            if ("Running".equals(p.phase())) {
                running++;
            }
            // Terminating 残影不算异常：滚动升级后旧副本只是还没从 etcd 消失
            boolean bad = isAbnormal(p) && !p.deleting();
            if (bad) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("namespace", p.namespace());
                row.put("name", p.name());
                row.put("phase", p.phase());
                row.put("node", p.node());
                row.put("ready", p.ready());
                row.put("restarts", p.restarts());
                row.put("reason", p.reason());
                row.put("startTime", p.startTime());
                abnormal.add(row);
            }
        }
        abnormal.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));

        List<Map<String, Object>> deployments = new ArrayList<>();
        int unavailableDeployments = 0;
        for (K8sSnapshotService.DeploymentInfo d : snap.deployments()) {
            if (d.unavailable() > 0) {
                unavailableDeployments++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("namespace", d.namespace());
            row.put("name", d.name());
            row.put("desired", d.desired());
            row.put("available", d.available());
            row.put("unavailable", d.unavailable());
            deployments.add(row);
        }

        List<Map<String, Object>> components = components(snap);

        Map<String, Object> summary = new LinkedHashMap<>();
        // false = 读 K8s API 失败，页面上的 0 个节点/0 个 Pod 不是「真的没有」
        summary.put("k8sSnapshotOk", snap.ok());
        // 异常 Pod 的覆盖面要可机验：页面/判据据此确认 kube-system 在册（项目负责人 11-03 报①）
        summary.put("coveredNamespaces", namespaces.all());
        summary.put("nodesReady", snap.nodes().stream().filter(K8sSnapshotService.NodeInfo::ready).count());
        summary.put("nodesTotal", snap.nodes().size());
        summary.put("podsRunning", running);
        summary.put("podsAbnormal", abnormal.size());
        summary.put("deploymentsUnavailable", unavailableDeployments);
        summary.put("maxCpuPercent", round(max(cpu)));
        summary.put("maxMemoryPercent", round(max(mem)));
        summary.put("maxDiskPercent", round(max(disk)));
        summary.put("maxCpuNode", maxNode(cpu));
        summary.put("maxMemoryNode", maxNode(mem));
        summary.put("maxDiskNode", maxNode(disk));
        try {
            Map<String, Object> alerts = jdbc.queryForMap("""
                    select count(*) filter (where status = 'OPEN') as open,
                           count(*) filter (where status = 'OPEN' and rule_type = 'SYSTEM') as system_open
                    from platform.alert_event
                    """);
            summary.put("alertsOpen", num(alerts.get("open")));
            summary.put("systemAlertsOpen", num(alerts.get("system_open")));
        } catch (Exception e) {
            summary.put("alertsOpen", 0);
            summary.put("systemAlertsOpen", 0);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("summary", summary);
        out.put("nodes", nodes);
        out.put("abnormalPods", abnormal);
        out.put("deployments", deployments);
        out.put("components", components);
        out.put("seatunnel", seatunnel(snap));
        // 集群级组件（Doris / Zeta / MinIO / HDFS）：页面放在最下面一张表
        out.put("clusters", clusters.list());
        return out;
    }

    /**
     * SeaTunnel Zeta 集群视图：节点 Pod、Hazelcast 自报节点数、运行中的同步作业。
     * Prometheus 不可用时 nodeCount 为 0，页面仍可打开。
     */
    private Map<String, Object> seatunnel(K8sSnapshotService.Snapshot snap) {
        // 磁盘/内存的真实容量与可用量：页面要显示「还差多少」而不只是百分比
        Map<String, Double> diskSize = prom.byLabel("""
                node_filesystem_size_bytes{job="node-exporter",mountpoint="/",fstype!~"tmpfs|overlay|squashfs"}
                """, "node");
        Map<String, Double> diskAvail = prom.byLabel("""
                node_filesystem_avail_bytes{job="node-exporter",mountpoint="/",fstype!~"tmpfs|overlay|squashfs"}
                """, "node");
        Map<String, Double> memTotal = prom.byLabel("""
                node_memory_MemTotal_bytes{job="node-exporter"}
                """, "node");
        Map<String, Double> memAvail = prom.byLabel("""
                node_memory_MemAvailable_bytes{job="node-exporter"}
                """, "node");

        List<Map<String, Object>> nodes = new ArrayList<>();
        var pods = snap.pods().stream()
                .filter(p -> p.name().startsWith("seatunnel-"))
                .sorted(Comparator.comparing(K8sSnapshotService.PodInfo::name))
                .toList();
        long ready = 0;
        for (K8sSnapshotService.PodInfo p : pods) {
            if (p.ready()) {
                ready++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.name());
            row.put("node", p.node());
            row.put("ready", p.ready());
            row.put("phase", p.phase());
            row.put("restarts", p.restarts());
            nodes.add(row);
        }
        long runningJobs = snap.pods().stream()
                .filter(p -> p.name().startsWith("sync-"))
                .filter(p -> "Running".equals(p.phase()))
                .count();
        double nodeCount = prom.scalar("max(node_count{job=\"seatunnel\"})", 0);

        Map<String, Long> jobs = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : jdbc.queryForList("""
                    select coalesce(last_status, 'NEVER') as status, count(*) as cnt
                    from platform.sync_task group by 1
                    """)) {
                jobs.put(String.valueOf(row.get("status")), num(row.get("cnt")));
            }
        } catch (Exception ignored) {
            // 表不存在时不影响系统监控页
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusterNodeCount", nodeCount);
        out.put("desiredNodes", 3);
        out.put("readyNodes", ready);
        out.put("nodes", nodes);
        out.put("runningJobs", runningJobs);
        out.put("jobs", jobs);
        return out;
    }

    private List<Map<String, Object>> components(K8sSnapshotService.Snapshot snap) {
        List<Map<String, Object>> out = new ArrayList<>();
        addPodComponent(out, snap, "平台后端", "dataworks-backend");
        addPodComponent(out, snap, "PostgreSQL", "postgres-");
        addPodComponent(out, snap, "Prefect Server", "prefect-server");
        addPodComponent(out, snap, "Prefect Worker", "prefect-worker");
        addPodComponent(out, snap, "Prefect Pool Worker", "prefect-pool-worker");
        addPodComponent(out, snap, "Doris FE", "doris-fe");
        addPodComponent(out, snap, "Doris BE", "doris-be");
        addPodComponent(out, snap, "MinIO", "minio-");
        addPodComponent(out, snap, "Keycloak", "keycloak");
        addPodComponent(out, snap, "Gravitino", "gravitino");
        addPodComponent(out, snap, "SeaTunnel", "seatunnel");
        // Spark Thrift Server 在 dw-spark（dw-dev 配额打满后迁出去的），前缀匹配即可
        addPodComponent(out, snap, "Spark Thrift", "spark-thrift");
        addPodComponent(out, snap, "Prometheus", "prometheus");
        addPodComponent(out, snap, "Grafana", "grafana");
        addPodComponent(out, snap, "Alertmanager", "alertmanager");
        addPodComponent(out, snap, "node-exporter", "node-exporter");
        return out;
    }

    private void addPodComponent(List<Map<String, Object>> out, K8sSnapshotService.Snapshot snap,
                                 String label, String prefix) {
        Predicate<K8sSnapshotService.PodInfo> match = p -> p.name().startsWith(prefix);
        List<K8sSnapshotService.PodInfo> pods = snap.pods().stream().filter(match).toList();
        // 组件整体消失（副本被删到 0 / 工作负载被删）不能从清单里悄悄消失：
        // 少一行和「本来就没这个组件」在页面上分不出来，等于漏报。
        long ready = pods.stream().filter(K8sSnapshotService.PodInfo::ready).count();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", label);
        row.put("ready", ready);
        row.put("total", pods.size());
        row.put("detail", pods.isEmpty() ? "未部署 / Pod 已消失" : ready + "/" + pods.size() + " Ready");
        if (pods.isEmpty()) {
            row.put("missing", true);
        }
        out.add(row);
    }

    private static double max(Map<String, Double> values) {
        return values.values().stream().max(Double::compare).orElse(0d);
    }

    private static String maxNode(Map<String, Double> values) {
        return values.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");
    }

    /** 字节 → G，保留两位小数（与「服务资源」同一口径）。 */
    private static String gb(Double bytes) {
        if (bytes == null) {
            return "-";
        }
        return String.format("%.2fG", bytes / (double) (1L << 30));
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10d) / 10d;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 启动中的 Pod 不算异常：SeaTunnel Job 刚创建时短暂 Pending、
     * 容器刚启动时 Ready=false 都是正常生命周期。只有失败态，
     * 或启动明显超时仍不可用，才计入异常，避免总览误报。
     */
    private static boolean isAbnormal(K8sSnapshotService.PodInfo p) {
        String reason = p.reason() == null ? "" : p.reason();
        if (reason.contains("ImagePullBackOff") || reason.contains("ErrImagePull")
                || reason.contains("CrashLoopBackOff") || reason.contains("CreateContainerConfigError")
                || "Failed".equals(p.phase()) || "Unknown".equals(p.phase())) {
            return true;
        }
        if ("Pending".equals(p.phase())) {
            // startTime 缺省时回退 Ready 条件时间：ageSeconds 对解析失败按 MAX 处理
            // （宁可多报），若两个时间都拿不到会把刚创建的 Pod 立刻判异常。
            String since = p.startTime() == null || p.startTime().isBlank()
                    ? p.readyTransitionTime() : p.startTime();
            return ageSeconds(since) > 300; // 调度/拉镜像超过 5 分钟才算卡住
        }
        if ("Running".equals(p.phase()) && !p.ready()) {
            // 用 Ready 条件的变化时间，而不是 Pod 创建时间：跑了三天的 Pod 一次探活
            // 失败就按创建时长判异常，会把瞬时抖动误报成「长期未就绪」。
            String since = p.readyTransitionTime() == null ? p.startTime() : p.readyTransitionTime();
            return ageSeconds(since) > 120; // 连续 2 分钟未就绪才算异常
        }
        return false;
    }

    private static long ageSeconds(String startTime) {
        try {
            return Math.max(0, Instant.now().getEpochSecond()
                    - Instant.parse(startTime).getEpochSecond());
        } catch (Exception e) {
            return Long.MAX_VALUE; // 解析不了按异常处理，宁可多报不漏报
        }
    }
}
