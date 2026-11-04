package com.lab.dataworks.system;

import com.lab.dataworks.common.ApiException;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统监控页的「服务资源」：工作负载的实际内存/CPU、配额，以及停止/启动/副本/内存设置。
 *
 * <p>覆盖 `dw-dev`、`dw-spark`（Spark Thrift Server 及其 executor）与
 * `dataworks-test*`（连接器测试夹具）：后两者也吃内存，之前在监控页看不到、
 * 也就没法关。`dw-spark` 常驻 driver + 2 executor，约 3–4 GB。</p>
 *
 * <p>内存用量取 Prometheus 的 working_set（与 kubectl top 同口径）；
 * Prometheus 不可用时只返回配额，页面显示「暂无数据」，不伪造 0。
 * 用量是所有副本之和，配额按「每副本 × 副本数」比较。</p>
 */
@Service
public class ServiceResourceService {

    private static final Logger log = LoggerFactory.getLogger(ServiceResourceService.class);
    private static final String NAMESPACE = "dw-dev";
    /** Spark Thrift Server 单独一个命名空间（dw-dev 的 ResourceQuota 已打满）。 */
    private static final String SPARK_NAMESPACE = "dw-spark";
    private static final String TEST_NAMESPACE_PREFIX = "dataworks-test";
    private static final String PREVIOUS = "dw.dev/previous-replicas";

    /**
     * 核心组件判定：**搭建平台自身需要的都是核心；数据采集用的测试数据源不算**。
     * 即 dw-dev / dw-spark 里的一律核心（原因是给人看的说明），
     * dataworks-test* 里的夹具可停可删。
     */
    private static final Map<String, String> CRITICAL = new LinkedHashMap<>();

    static {
        CRITICAL.put("dataworks-backend", "平台 API：停用后页面无法登录与操作");
        CRITICAL.put("postgres", "平台元数据库：停用后所有业务数据不可用");
        CRITICAL.put("prometheus", "监控数据源：停用后内存/CPU 用量无法统计（配额仍可见）");
        CRITICAL.put("prefect-server", "调度服务端：停用后工作流无法触发与同步");
        CRITICAL.put("prefect-worker", "调度执行器：停用后运行中的工作流会失败");
        CRITICAL.put("prefect-pool-worker", "调度执行器：停用后运行中的工作流会失败");
        CRITICAL.put("prefect-bridge", "调度桥接：停用后发布/触发/删除都不可用");
        CRITICAL.put("seatunnel", "同步引擎：停用后正在运行的同步作业会中断");
        CRITICAL.put("spark-thrift", "Spark 引擎：停用后 SPARK 引擎的脚本试跑/查询不可用（executor 由它拉起）");
        CRITICAL.put("nfs-server", "共享存储：停用后依赖卷的服务无法读写");
        CRITICAL.put("minio", "对象存储：停用后数据文件不可读，可能丢数据");
        CRITICAL.put("doris-fe", "数仓 FE：停用后所有 Doris 查询不可用");
        CRITICAL.put("doris-be", "数仓 BE：停用后数据不可读写（DaemonSet 只读展示）");
        CRITICAL.put("hdfs", "HDFS 存储：停用后 hdfs 类型项目的目录不可读写");
        CRITICAL.put("hdfs-datanode", "HDFS DataNode：停用后项目存储不可读写");
    }

    /**
     * 单例组件：副本数固定为 1。
     * 这里说的是「容器实例数」，不是 HDFS 的数据副本数（dfs.replication）。
     */
    private static final Map<String, String> SINGLETON = Map.of(
            "hdfs", "HDFS 是单节点组件，副本数固定为 1；要 3 份数据需部署 3 个 DataNode 并设 dfs.replication=3，不是改 Deployment 副本数",
            "postgres", "PostgreSQL 是单实例，副本数固定为 1；高可用需主从或 CloudNativePG",
            "prometheus", "Prometheus 是单实例，副本数固定为 1");

    private final PrometheusClient prometheus;

    public ServiceResourceService(PrometheusClient prometheus) {
        this.prometheus = prometheus;
    }

    public record ServiceResource(String namespace, String kind, String name,
                                  int replicas, int ready,
                                  String cpuUsage, String memoryUsage,
                                  String memoryRequest, String memoryLimit,
                                  String memoryLimitTotal, String stopBlockedReason,
                                  boolean stopped, boolean stoppable, boolean usageAvailable) {
    }

    public List<ServiceResource> list() {
        Map<String, Long> memByPod = podMetricMemory();
        Map<String, Double> cpuByPod = podMetricCpu();
        boolean usageAvailable = !memByPod.isEmpty();

        List<ServiceResource> out = new ArrayList<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<String> namespaces = client.namespaces().list().getItems().stream()
                    .map(n -> n.getMetadata().getName())
                    .filter(n -> NAMESPACE.equals(n) || SPARK_NAMESPACE.equals(n)
                            || n.startsWith(TEST_NAMESPACE_PREFIX))
                    .sorted()
                    .toList();
            for (String ns : namespaces) {
                for (Deployment d : client.apps().deployments().inNamespace(ns).list().getItems()) {
                    String name = d.getMetadata().getName();
                    out.add(resource(ns, "deployment", name,
                            intOrZero(d.getSpec() == null ? null : d.getSpec().getReplicas()),
                            intOrZero(d.getStatus() == null ? null : d.getStatus().getReadyReplicas()),
                            d.getSpec() == null || d.getSpec().getTemplate() == null ? null
                                    : d.getSpec().getTemplate().getSpec().getContainers(),
                            annotation(d.getMetadata()), memByPod, cpuByPod, usageAvailable));
                }
                for (io.fabric8.kubernetes.api.model.apps.DaemonSet ds
                        : client.apps().daemonSets().inNamespace(ns).list().getItems()) {
                    String name = ds.getMetadata().getName();
                    int desired = ds.getStatus() == null
                            || ds.getStatus().getDesiredNumberScheduled() == null
                            ? 0 : ds.getStatus().getDesiredNumberScheduled();
                    int ready = ds.getStatus() == null || ds.getStatus().getNumberReady() == null
                            ? 0 : ds.getStatus().getNumberReady();
                    out.add(resource(ns, "daemonset", name, desired, ready,
                            ds.getSpec() == null || ds.getSpec().getTemplate() == null ? null
                                    : ds.getSpec().getTemplate().getSpec().getContainers(),
                            annotation(ds.getMetadata()), memByPod, cpuByPod, usageAvailable));
                }
                for (StatefulSet s : client.apps().statefulSets().inNamespace(ns).list().getItems()) {
                    String name = s.getMetadata().getName();
                    out.add(resource(ns, "statefulset", name,
                            intOrZero(s.getSpec() == null ? null : s.getSpec().getReplicas()),
                            intOrZero(s.getStatus() == null ? null : s.getStatus().getReadyReplicas()),
                            s.getSpec() == null || s.getSpec().getTemplate() == null ? null
                                    : s.getSpec().getTemplate().getSpec().getContainers(),
                            annotation(s.getMetadata()), memByPod, cpuByPod, usageAvailable));
                }
            }
        } catch (Exception e) {
            throw new ApiException(502, "读取集群工作负载失败: " + e.getMessage());
        }
        out.sort(Comparator.comparing(ServiceResource::namespace)
                .thenComparing(ServiceResource::kind)
                .thenComparing(ServiceResource::name));
        return out;
    }

    /** 停止：副本数置 0，原副本数记入注解；核心组件拒绝。 */
    public ServiceResource stop(String namespace, String kind, String name) {
        if ("daemonset".equals(kind)) {
            throw ApiException.badRequest("DaemonSet 不支持一键停止（会按节点重新拉起）");
        }
        requireNotCritical(namespace, name);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            current(namespace, kind, name, client);
            int replicas = replicasOf(namespace, kind, name, client);
            if (replicas == 0) {
                return find(namespace, kind, name);
            }
            patch(namespace, kind, name, client, 0, replicas);
            log.info("service stopped ns={} kind={} name={} previous={}",
                    namespace, kind, name, replicas);
        }
        return find(namespace, kind, name);
    }

    /** 启动：恢复停止前的副本数（默认 1）。 */
    public ServiceResource start(String namespace, String kind, String name) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            current(namespace, kind, name, client);
            String previous = annotationOf(namespace, kind, name, client);
            int target = 1;
            if (previous != null) {
                try {
                    target = Math.max(1, Integer.parseInt(previous));
                } catch (NumberFormatException ignored) {
                    target = 1;
                }
            }
            patch(namespace, kind, name, client, target, null);
            log.info("service started ns={} kind={} name={} replicas={}",
                    namespace, kind, name, target);
        }
        return find(namespace, kind, name);
    }

    /** 直接设置副本数（0 = 停止；运维手填）。 */
    public ServiceResource scale(String namespace, String kind, String name, int replicas) {
        if ("daemonset".equals(kind)) {
            throw ApiException.badRequest("DaemonSet 按节点部署，副本数由节点数决定，不能手动设置");
        }
        String singletonWhy = SINGLETON.get(name);
        if (singletonWhy != null && replicas != 1) {
            throw ApiException.badRequest(singletonWhy);
        }
        if (replicas < 0 || replicas > 20) {
            throw ApiException.badRequest("副本数需在 0-20 之间");
        }
        if (replicas == 0) {
            requireNotCritical(namespace, name);
        }
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            current(namespace, kind, name, client);
            int before = replicasOf(namespace, kind, name, client);
            patch(namespace, kind, name, client, replicas, replicas == 0 ? before : null);
            log.info("service scale ns={} kind={} name={} {} -> {}",
                    namespace, kind, name, before, replicas);
        }
        return find(namespace, kind, name);
    }

    /**
     * 内存设置（按单个副本）：改容器 memory limit，request 不超过 limit。
     * 只填数字时按 G 处理（2 / 2.5），也接受 3072Mi。
     */
    public ServiceResource resizeMemory(String namespace, String kind, String name, String limit) {
        String value = limit == null ? "" : limit.trim();
        if (value.matches("\\d{1,6}(\\.\\d{1,2})?")) {
            value = value + "G";
        }
        if (!value.matches("\\d{1,6}(\\.\\d{1,2})?(Mi|Gi|G|M|Ti)")) {
            throw ApiException.badRequest("内存格式应为 512Mi / 2 / 2.5G 这样的值");
        }
        Quantity quantity = new Quantity(value);
        long bytes = quantity.getNumericalAmount().longValue();
        if (bytes < 128L * 1024 * 1024 || bytes > 8L * 1024 * 1024 * 1024) {
            throw ApiException.badRequest("内存需在 128Mi - 8Gi 之间");
        }
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            current(namespace, kind, name, client);
            if ("daemonset".equals(kind)) {
                client.apps().daemonSets().inNamespace(namespace).withName(name).edit(ds -> {
                    applyMemory(ds.getSpec().getTemplate().getSpec().getContainers(), quantity);
                    return ds;
                });
            } else if ("statefulset".equals(kind)) {
                client.apps().statefulSets().inNamespace(namespace).withName(name).edit(st -> {
                    applyMemory(st.getSpec().getTemplate().getSpec().getContainers(), quantity);
                    return st;
                });
            } else {
                client.apps().deployments().inNamespace(namespace).withName(name).edit(d -> {
                    applyMemory(d.getSpec().getTemplate().getSpec().getContainers(), quantity);
                    return d;
                });
            }
            log.info("service memory ns={} kind={} name={} limit={}", namespace, kind, name, value);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "调整内存失败: " + e.getMessage());
        }
        return find(namespace, kind, name);
    }

    /** 删除工作负载（Deployment/StatefulSet 本体）；核心组件与 DaemonSet 拒绝。 */
    public void delete(String namespace, String kind, String name) {
        if ("daemonset".equals(kind)) {
            // 以前这里落到 deployments().delete()：对象不存在时 fabric8 返回 false 不抛异常，
            // 接口却回 200 ok，等于「说删了其实没删」。
            throw ApiException.badRequest("DaemonSet 按节点部署，不支持在这里删除；请先缩容节点");
        }
        requireNotCritical(namespace, name);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            current(namespace, kind, name, client);
            boolean deleted = "statefulset".equals(kind)
                    ? Boolean.TRUE.equals(client.apps().statefulSets()
                            .inNamespace(namespace).withName(name).delete())
                    : Boolean.TRUE.equals(client.apps().deployments()
                            .inNamespace(namespace).withName(name).delete());
            if (!deleted) {
                throw new ApiException(502, "删除服务失败：K8s 未确认删除 " + namespace + "/" + kind + "/" + name);
            }
            log.info("service deleted ns={} kind={} name={}", namespace, kind, name);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "删除服务失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部

    private ServiceResource find(String namespace, String kind, String name) {
        return list().stream()
                .filter(s -> s.namespace().equals(namespace)
                        && s.kind().equals(kind) && s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("服务不存在: " + namespace + "/" + name));
    }

    private void requireNotCritical(String namespace, String name) {
        // 平台自身的组件（dw-dev）都不允许一键停/删；dataworks-test* 的采集夹具可以
        if (!isTestNamespace(namespace)) {
            String reason = CRITICAL.getOrDefault(name,
                    "平台组件：属于平台自身的一部分，停用会影响平台功能");
            throw ApiException.badRequest("「" + name + "」是核心组件，不能一键停止：" + reason);
        }
    }

    private static boolean isTestNamespace(String namespace) {
        return namespace != null && namespace.startsWith(TEST_NAMESPACE_PREFIX);
    }

    private ServiceResource resource(String namespace, String kind, String name, int desired,
                                     int ready, List<Container> containers, String previous,
                                     Map<String, Long> memByPod, Map<String, Double> cpuByPod,
                                     boolean usageAvailable) {
        String prefix = namespace + "|";
        long memoryUsage = 0;
        double cpuUsage = 0;
        for (Map.Entry<String, Long> entry : memByPod.entrySet()) {
            if (entry.getKey().startsWith(prefix)
                    && belongsTo(entry.getKey().substring(prefix.length()), name)) {
                memoryUsage += entry.getValue();
            }
        }
        for (Map.Entry<String, Double> entry : cpuByPod.entrySet()) {
            if (entry.getKey().startsWith(prefix)
                    && belongsTo(entry.getKey().substring(prefix.length()), name)) {
                cpuUsage += entry.getValue();
            }
        }
        long requestBytes = 0;
        long limitBytes = 0;
        boolean hasRequest = false;
        boolean hasLimit = false;
        if (containers != null) {
            for (Container c : containers) {
                if (c.getResources() == null) {
                    continue;
                }
                Map<String, Quantity> req = c.getResources().getRequests();
                Map<String, Quantity> lim = c.getResources().getLimits();
                if (req != null && req.get("memory") != null) {
                    requestBytes += req.get("memory").getNumericalAmount().longValue();
                    hasRequest = true;
                }
                if (lim != null && lim.get("memory") != null) {
                    limitBytes += lim.get("memory").getNumericalAmount().longValue();
                    hasLimit = true;
                }
            }
        }
        int scale = Math.max(desired, 0);
        String limitTotal = hasLimit && scale > 0 ? humanBytes(limitBytes * scale) : "-";
        return new ServiceResource(namespace, kind, name, desired, ready,
                cpuUsage > 0 ? Math.round(cpuUsage * 1000) + "m" : "-",
                usageAvailable ? humanBytes(memoryUsage) : "-",
                hasRequest ? humanBytes(requestBytes) : "-",
                hasLimit ? humanBytes(limitBytes) : "-",
                limitTotal,
                isTestNamespace(namespace) ? ""
                        : CRITICAL.getOrDefault(name, "平台组件：属于平台自身的一部分，停用会影响平台功能"),
                desired == 0,
                isTestNamespace(namespace) && !"daemonset".equals(kind),
                usageAvailable);
    }

    private static boolean belongsTo(String podName, String workload) {
        return podName.equals(workload) || podName.startsWith(workload + "-");
    }

    /** 统一按 G 显示，保留两位小数（内存与磁盘同一口径）。 */
    private static String humanBytes(long bytes) {
        return String.format("%.2fG", Math.max(0, bytes) / (double) (1L << 30));
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String annotation(ObjectMeta meta) {
        return meta == null || meta.getAnnotations() == null
                ? null : meta.getAnnotations().get(PREVIOUS);
    }

    private Map<String, Long> podMetricMemory() {
        Map<String, Long> out = new HashMap<>();
        for (PrometheusClient.Sample sample : prometheus.query(
                "sum by (namespace,pod) (container_memory_working_set_bytes"
                        + "{container!=\"\",container!=\"POD\"})")) {
            String pod = sample.labels().get("pod");
            if (pod != null) {
                out.merge(key(sample, pod), (long) sample.value(), Long::sum);
            }
        }
        return out;
    }

    private Map<String, Double> podMetricCpu() {
        Map<String, Double> out = new HashMap<>();
        for (PrometheusClient.Sample sample : prometheus.query(
                "sum by (namespace,pod) (rate(container_cpu_usage_seconds_total"
                        + "{container!=\"\",container!=\"POD\"}[5m]))")) {
            String pod = sample.labels().get("pod");
            if (pod != null) {
                out.merge(key(sample, pod), sample.value(), Double::sum);
            }
        }
        return out;
    }

    private static String key(PrometheusClient.Sample sample, String pod) {
        String ns = sample.labels().get("namespace");
        return (ns == null ? NAMESPACE : ns) + "|" + pod;
    }

    // ---------------------------------------------------------------- k8s 读写

    private void current(String namespace, String kind, String name, KubernetesClient client) {
        boolean exists = switch (kind) {
            case "daemonset" ->
                    client.apps().daemonSets().inNamespace(namespace).withName(name).get() != null;
            case "statefulset" ->
                    client.apps().statefulSets().inNamespace(namespace).withName(name).get() != null;
            default -> client.apps().deployments().inNamespace(namespace).withName(name).get() != null;
        };
        if (!exists) {
            throw ApiException.notFound("服务不存在: " + namespace + "/" + name);
        }
    }

    private int replicasOf(String namespace, String kind, String name, KubernetesClient client) {
        Integer replicas = "statefulset".equals(kind)
                ? client.apps().statefulSets().inNamespace(namespace).withName(name).get()
                        .getSpec().getReplicas()
                : client.apps().deployments().inNamespace(namespace).withName(name).get()
                        .getSpec().getReplicas();
        return replicas == null ? 0 : replicas;
    }

    private String annotationOf(String namespace, String kind, String name, KubernetesClient client) {
        return "statefulset".equals(kind)
                ? annotation(client.apps().statefulSets().inNamespace(namespace).withName(name)
                        .get().getMetadata())
                : annotation(client.apps().deployments().inNamespace(namespace).withName(name)
                        .get().getMetadata());
    }

    private void patch(String namespace, String kind, String name, KubernetesClient client,
                       int replicas, Integer previous) {
        try {
            if ("statefulset".equals(kind)) {
                client.apps().statefulSets().inNamespace(namespace).withName(name).edit(st -> {
                    st.getSpec().setReplicas(replicas);
                    setPrevious(st.getMetadata(), previous);
                    return st;
                });
            } else {
                client.apps().deployments().inNamespace(namespace).withName(name).edit(d -> {
                    d.getSpec().setReplicas(replicas);
                    setPrevious(d.getMetadata(), previous);
                    return d;
                });
            }
        } catch (Exception e) {
            throw new ApiException(502, "调整副本数失败: " + e.getMessage());
        }
    }

    private static void setPrevious(ObjectMeta meta, Integer previous) {
        if (meta.getAnnotations() == null) {
            meta.setAnnotations(new LinkedHashMap<>());
        }
        if (previous != null) {
            meta.getAnnotations().put(PREVIOUS, String.valueOf(previous));
        } else {
            meta.getAnnotations().remove(PREVIOUS);
        }
    }

    private static void applyMemory(List<Container> containers, Quantity limit) {
        for (Container c : containers) {
            if (c.getResources() == null) {
                c.setResources(new io.fabric8.kubernetes.api.model.ResourceRequirements());
            }
            Map<String, Quantity> limits = c.getResources().getLimits();
            if (limits == null) {
                limits = new LinkedHashMap<>();
                c.getResources().setLimits(limits);
            }
            limits.put("memory", limit);
            Map<String, Quantity> requests = c.getResources().getRequests();
            if (requests == null) {
                requests = new LinkedHashMap<>();
                c.getResources().setRequests(requests);
            }
            Quantity request = requests.get("memory");
            if (request == null
                    || request.getNumericalAmount().compareTo(limit.getNumericalAmount()) > 0) {
                requests.put("memory", limit);
            }
        }
    }
}
