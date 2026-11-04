package com.lab.dataworks.system;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 读取 K8s 实时状态，并渲染成 Prometheus 文本指标。
 *
 * <p>没有引入 kube-state-metrics：DEV 环境拉不到 registry.k8s.io 镜像，
 * 而这里用后端已有的 ServiceAccount 直接读 pods/deployments/nodes，
 * 输出的指标名与常用 KSM 指标保持接近（dw_pod_* / dw_deployment_*）。</p>
 */
@Service
public class K8sSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(K8sSnapshotService.class);

    private final MonitorNamespaces namespaces;

    public K8sSnapshotService(MonitorNamespaces namespaces) {
        this.namespaces = namespaces;
    }

    public record NodeInfo(String name, boolean ready, boolean schedulable,
                           String cpuCapacity, String memoryCapacity, int podCount, String internalIp) {
    }

    /**
     * deleting=true 表示 Pod 已打上删除标记（Terminating）：服务已由新副本接手，
     * 旧 Pod 只是还没从 etcd 消失。判异常时必须排除，否则滚动升级期间
     * 卡住的 Terminating 残影会一直挂在「异常 Pod」列表里——新 Pod 明明正常。
     */
    public record PodInfo(String namespace, String name, String phase, String node, boolean ready,
                          int restarts, String reason, String startTime,
                          String readyTransitionTime, boolean deleting) {
    }

    public record DeploymentInfo(String namespace, String name, int desired, int available,
                                 int unavailable) {
    }

    /**
     * @param ok false 表示这次读取 K8s API 失败（返回的是空快照）。调用方要据此
     *           区分「集群真的没有 Pod」和「监控瞎了」，否则所有 dw_* 序列静默消失。
     */
    public record Snapshot(List<NodeInfo> nodes, List<PodInfo> pods,
                           List<DeploymentInfo> deployments, boolean ok) {
        public static Snapshot failed() {
            return new Snapshot(List.of(), List.of(), List.of(), false);
        }
    }

    public Snapshot snapshot() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            // 多个命名空间一起读（dw-dev + dw-spark …），Pods 里带命名空间标签
            List<Pod> pods = new ArrayList<>();
            for (String ns : namespaces.all()) {
                pods.addAll(client.pods().inNamespace(ns).list().getItems());
            }
            List<NodeInfo> nodes = new ArrayList<>();
            for (Node node : client.nodes().list().getItems()) {
                boolean ready = false;
                if (node.getStatus() != null && node.getStatus().getConditions() != null) {
                    for (NodeCondition c : node.getStatus().getConditions()) {
                        if ("Ready".equals(c.getType())) {
                            ready = "True".equalsIgnoreCase(c.getStatus());
                        }
                    }
                }
                String ip = "";
                if (node.getStatus() != null && node.getStatus().getAddresses() != null) {
                    ip = node.getStatus().getAddresses().stream()
                            .filter(a -> "InternalIP".equals(a.getType()))
                            .map(a -> a.getAddress()).findFirst().orElse("");
                }
                int podCount = (int) pods.stream()
                        .filter(p -> node.getMetadata().getName().equals(p.getSpec().getNodeName()))
                        .count();
                nodes.add(new NodeInfo(
                        node.getMetadata().getName(),
                        ready,
                        node.getSpec() == null || !Boolean.TRUE.equals(node.getSpec().getUnschedulable()),
                        quantity(node, "cpu"),
                        quantity(node, "memory"),
                        podCount,
                        ip));
            }
            nodes.sort(Comparator.comparing(NodeInfo::name));

            List<PodInfo> podInfos = new ArrayList<>();
            for (Pod pod : pods) {
                String phase = pod.getStatus() == null || pod.getStatus().getPhase() == null
                        ? "Unknown" : pod.getStatus().getPhase();
                boolean ready = "Running".equals(phase) && podReady(pod);
                int restarts = 0;
                String reason = "";
                if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                    for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                        if (cs.getRestartCount() != null) {
                            restarts += cs.getRestartCount();
                        }
                        if (reason.isBlank() && cs.getState() != null) {
                            if (cs.getState().getWaiting() != null) {
                                reason = cs.getState().getWaiting().getReason();
                            } else if (cs.getState().getTerminated() != null
                                    && !"Completed".equals(cs.getState().getTerminated().getReason())) {
                                reason = cs.getState().getTerminated().getReason();
                            }
                        }
                    }
                }
                podInfos.add(new PodInfo(
                        pod.getMetadata().getNamespace(),
                        pod.getMetadata().getName(),
                        phase,
                        pod.getSpec() == null ? "" : pod.getSpec().getNodeName(),
                        ready,
                        restarts,
                        reason == null ? "" : reason,
                        pod.getMetadata().getCreationTimestamp(),
                        readyTransitionTime(pod),
                        pod.getMetadata().getDeletionTimestamp() != null));
            }
            podInfos.sort(Comparator.comparing(PodInfo::namespace).thenComparing(PodInfo::name));

            List<DeploymentInfo> deployments = new ArrayList<>();
            for (String ns : namespaces.all()) {
                for (Deployment d : client.apps().deployments().inNamespace(ns).list().getItems()) {
                    int desired = d.getSpec() == null || d.getSpec().getReplicas() == null
                            ? 0 : d.getSpec().getReplicas();
                    int available = d.getStatus() == null || d.getStatus().getAvailableReplicas() == null
                            ? 0 : d.getStatus().getAvailableReplicas();
                    int unavailable = d.getStatus() == null || d.getStatus().getUnavailableReplicas() == null
                            ? 0 : d.getStatus().getUnavailableReplicas();
                    deployments.add(new DeploymentInfo(ns, d.getMetadata().getName(),
                            desired, available, unavailable));
                }
            }
            deployments.sort(Comparator.comparing(DeploymentInfo::namespace)
                    .thenComparing(DeploymentInfo::name));
            return new Snapshot(nodes, podInfos, deployments, true);
        } catch (Exception e) {
            log.warn("k8s snapshot failed: {}", e.getMessage());
            return Snapshot.failed();
        }
    }

    /**
     * Ready 条件最近一次变化的时间。判断「未就绪多久了」必须用它，
     * 不能用 Pod 的创建时间：跑了三天的 Pod 一次探活失败会被误判成长期异常。
     */
    private static String readyTransitionTime(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return null;
        }
        for (PodCondition c : pod.getStatus().getConditions()) {
            if ("Ready".equals(c.getType())) {
                return c.getLastTransitionTime();
            }
        }
        return null;
    }

    /** Prometheus 文本格式，供 /api/internal/metrics/k8s 使用。 */
    public String prometheusText() {
        Snapshot s = snapshot();
        StringBuilder out = new StringBuilder(4096);
        // 抓取成功 ≠ 数据有效：K8s API 读失败时这里给 0，否则 dw_* 全部消失而 up 仍是 1。
        out.append("# HELP dw_snapshot_ok 最近一次 K8s 快照是否成功\n# TYPE dw_snapshot_ok gauge\n");
        out.append("dw_snapshot_ok ").append(s.ok() ? 1 : 0).append('\n');
        out.append("# HELP dw_node_ready 节点 Ready 状态\n# TYPE dw_node_ready gauge\n");
        for (NodeInfo n : s.nodes()) {
            out.append("dw_node_ready{node=\"").append(esc(n.name())).append("\"} ")
                    .append(n.ready() ? 1 : 0).append('\n');
        }
        out.append("# HELP dw_pod_phase Pod 当前阶段\n# TYPE dw_pod_phase gauge\n");
        for (PodInfo p : s.pods()) {
            out.append("dw_pod_phase{namespace=\"").append(esc(p.namespace()))
                    .append("\",pod=\"").append(esc(p.name()))
                    .append("\",node=\"").append(esc(p.node()))
                    .append("\",phase=\"").append(esc(p.phase())).append("\"} 1\n");
        }
        out.append("# HELP dw_pod_ready Pod 是否全部容器就绪\n# TYPE dw_pod_ready gauge\n");
        for (PodInfo p : s.pods()) {
            out.append("dw_pod_ready{namespace=\"").append(esc(p.namespace()))
                    .append("\",pod=\"").append(esc(p.name()))
                    .append("\",node=\"").append(esc(p.node())).append("\"} ")
                    .append(p.ready() ? 1 : 0).append('\n');
        }
        out.append("# HELP dw_pod_restarts Pod 容器累计重启次数\n# TYPE dw_pod_restarts gauge\n");
        for (PodInfo p : s.pods()) {
            out.append("dw_pod_restarts{namespace=\"").append(esc(p.namespace()))
                    .append("\",pod=\"").append(esc(p.name())).append("\"} ")
                    .append(p.restarts()).append('\n');
        }
        out.append("# HELP dw_deployment_desired Deployment 期望副本数\n# TYPE dw_deployment_desired gauge\n");
        for (DeploymentInfo d : s.deployments()) {
            out.append("dw_deployment_desired{namespace=\"").append(esc(d.namespace()))
                    .append("\",deployment=\"").append(esc(d.name())).append("\"} ")
                    .append(d.desired()).append('\n');
        }
        out.append("# HELP dw_deployment_available Deployment 可用副本数\n# TYPE dw_deployment_available gauge\n");
        for (DeploymentInfo d : s.deployments()) {
            out.append("dw_deployment_available{namespace=\"").append(esc(d.namespace()))
                    .append("\",deployment=\"").append(esc(d.name())).append("\"} ")
                    .append(d.available()).append('\n');
        }
        out.append("# HELP dw_deployment_unavailable Deployment 不可用副本数\n# TYPE dw_deployment_unavailable gauge\n");
        for (DeploymentInfo d : s.deployments()) {
            out.append("dw_deployment_unavailable{namespace=\"").append(esc(d.namespace()))
                    .append("\",deployment=\"").append(esc(d.name())).append("\"} ")
                    .append(d.unavailable()).append('\n');
        }
        return out.toString();
    }

    private static boolean podReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        for (PodCondition c : pod.getStatus().getConditions()) {
            if ("Ready".equals(c.getType())) {
                return "True".equalsIgnoreCase(c.getStatus());
            }
        }
        return false;
    }

    private static String quantity(Node node, String resource) {
        if (node.getStatus() == null || node.getStatus().getCapacity() == null
                || node.getStatus().getCapacity().get(resource) == null) {
            return "";
        }
        return node.getStatus().getCapacity().get(resource).toString();
    }

    private static String esc(String s) {
        return (s == null ? "" : s).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
