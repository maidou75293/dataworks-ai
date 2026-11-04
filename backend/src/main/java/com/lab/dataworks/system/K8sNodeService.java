package com.lab.dataworks.system;

import com.lab.dataworks.common.ApiException;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k3s 节点级运维：封锁 / 驱逐 / 移除 / 加入命令。
 *
 * <p>缩容的标准顺序和 kubectl 一致：<b>cordon → drain → delete node</b>。
 * 平台只做这三步；「加机器」在宿主机层（新 VM 或物理机），页面给出加入命令。</p>
 *
 * <p>移除节点有两条硬约束：节点上还有非 DaemonSet 的 Pod 时拒绝（本地盘 PV 会丢数据），
 * 集群只剩一台时拒绝（没有地方可迁）。</p>
 */
@Service
public class K8sNodeService {

    private static final Logger log = LoggerFactory.getLogger(K8sNodeService.class);

    /** 封锁 / 解封：node.spec.unschedulable。 */
    public Map<String, Object> setSchedulable(String nodeName, boolean schedulable) {
        Node node = requireNode(nodeName);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            client.nodes().withName(nodeName).edit(n -> new NodeBuilder(n)
                    .editOrNewSpec()
                    .withUnschedulable(!schedulable)
                    .endSpec()
                    .build());
        } catch (Exception e) {
            throw new ApiException(502, "修改节点调度状态失败: " + e.getMessage());
        }
        log.info("k8s node {} {}", nodeName, schedulable ? "uncordon" : "cordon");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("node", nodeName);
        out.put("schedulable", schedulable);
        out.put("ip", internalIp(node));
        return out;
    }

    /**
     * 驱逐节点上的业务 Pod（跳过 DaemonSet 与静态镜像 Pod）。
     *
     * <p>{@code dryRun=true} 只列出会被驱逐的 Pod，不做任何动作——页面先用它给用户看清单。</p>
     */
    public Map<String, Object> drain(String nodeName, boolean dryRun) {
        requireNode(nodeName);
        List<Pod> victims = new ArrayList<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (Pod pod : client.pods().inAnyNamespace().list().getItems()) {
                if (pod.getSpec() == null || !nodeName.equals(pod.getSpec().getNodeName())) {
                    continue;
                }
                if (isDaemonSetPod(pod) || isMirrorPod(pod)) {
                    continue;
                }
                if (pod.getStatus() != null && "Succeeded".equals(pod.getStatus().getPhase())) {
                    continue;
                }
                victims.add(pod);
            }
            List<String> names = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (Pod pod : victims) {
                String ref = pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName();
                names.add(ref);
                if (dryRun) {
                    continue;
                }
                try {
                    client.pods().inNamespace(pod.getMetadata().getNamespace())
                            .withName(pod.getMetadata().getName()).evict();
                } catch (Exception e) {
                    // 有 PDB / 本地存储的 Pod 会被拒绝，如实报出来而不是假装成功
                    failed.add(ref + ": " + rootMessage(e));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", failed.isEmpty());
            out.put("node", nodeName);
            out.put("dryRun", dryRun);
            out.put("pods", names);
            out.put("failed", failed);
            if (!dryRun) {
                log.info("k8s node {} drain: 驱逐 {} 个 Pod，失败 {}", nodeName, names.size(), failed.size());
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "驱逐失败: " + e.getMessage());
        }
    }

    /** 从集群移除节点：必须已封锁且无业务 Pod，且集群不止一台。 */
    public Map<String, Object> remove(String nodeName) {
        Node node = requireNode(nodeName);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<Node> nodes = client.nodes().list().getItems();
            if (nodes.size() <= 1) {
                throw ApiException.conflict("这是集群里最后一台节点，移除后集群不可用");
            }
            boolean schedulable = node.getSpec() == null
                    || !Boolean.TRUE.equals(node.getSpec().getUnschedulable());
            if (schedulable) {
                throw ApiException.conflict("请先「封锁」该节点，再驱逐 Pod，最后移除");
            }
            List<String> remaining = new ArrayList<>();
            for (Pod pod : client.pods().inAnyNamespace().list().getItems()) {
                if (pod.getSpec() != null && nodeName.equals(pod.getSpec().getNodeName())
                        && !isDaemonSetPod(pod) && !isMirrorPod(pod)
                        && (pod.getStatus() == null
                        || !"Succeeded".equals(pod.getStatus().getPhase()))) {
                    remaining.add(pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName());
                }
            }
            if (!remaining.isEmpty()) {
                throw ApiException.conflict("节点上还有 " + remaining.size()
                        + " 个业务 Pod（" + String.join(", ", remaining.subList(0, Math.min(5, remaining.size())))
                        + "），先执行「驱逐」再移除，否则本地盘数据会丢");
            }
            client.nodes().withName(nodeName).delete();
            log.info("k8s node {} removed", nodeName);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("node", nodeName);
            out.put("note", "节点对象已移除；如果这台机器还在，请在机器上执行 "
                    + "/usr/local/bin/k3s-agent-uninstall.sh 清理");
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "移除节点失败: " + e.getMessage());
        }
    }

    /** 新节点加入命令：机器要运维自己准备，token 由 server 上的一条命令生成。 */
    public Map<String, Object> joinCommand() {
        String server = "";
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (Node node : client.nodes().list().getItems()) {
                boolean controlPlane = node.getMetadata().getLabels() != null
                        && node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/control-plane");
                if (controlPlane) {
                    server = internalIp(node);
                    break;
                }
            }
        } catch (Exception e) {
            throw new ApiException(502, "读取节点失败: " + e.getMessage());
        }
        if (server.isBlank()) {
            throw new ApiException(503, "没有找到 control-plane 节点的内网 IP");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverUrl", "https://" + server + ":6443");
        out.put("tokenCommand", "sudo k3s token create --ttl 1h");
        out.put("joinCommand", "curl -sfL https://get.k3s.io | K3S_URL=https://" + server
                + ":6443 K3S_TOKEN=<上一步输出的一次性 token> sh -");
        out.put("steps", List.of(
                "在宿主机/云上准备一台同网段机器（建议 ≥4 核 8G，磁盘 ≥80G）",
                "在任一 control-plane 节点执行下面的命令，拿一次性 token",
                "在新机器上执行下面的加入命令（把占位符替换成上一步的 token）",
                "回到本页刷新，新节点出现在节点表；再到 Doris「节点管理」把它加入 Doris"));
        out.put("note", "token 是一次性短期凭据，平台不保存也不展示历史 token");
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private Node requireNode(String nodeName) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Node node = client.nodes().withName(nodeName).get();
            if (node == null) {
                throw ApiException.notFound("节点不存在: " + nodeName);
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "读取节点失败: " + e.getMessage());
        }
    }

    private static String internalIp(Node node) {
        if (node.getStatus() == null || node.getStatus().getAddresses() == null) {
            return "";
        }
        return node.getStatus().getAddresses().stream()
                .filter(a -> "InternalIP".equals(a.getType()))
                .map(a -> a.getAddress()).findFirst().orElse("");
    }

    private static boolean isDaemonSetPod(Pod pod) {
        if (pod.getMetadata().getOwnerReferences() == null) {
            return false;
        }
        return pod.getMetadata().getOwnerReferences().stream()
                .anyMatch(ref -> "DaemonSet".equals(ref.getKind()));
    }

    private static boolean isMirrorPod(Pod pod) {
        return pod.getMetadata().getAnnotations() != null
                && pod.getMetadata().getAnnotations().containsKey("kubernetes.io/config.mirror");
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
