package com.lab.dataworks.prefect;

import com.lab.dataworks.common.ApiException;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PrefectPublisher {

    private static final Logger log = LoggerFactory.getLogger(PrefectPublisher.class);

    private final PrefectBridgeClient bridge;
    private final String namespace;
    private final String configMapName;
    private final String workerDeployment;

    public PrefectPublisher(PrefectBridgeClient bridge,
                            @Value("${prefect.namespace:dw-dev}") String namespace,
                            @Value("${prefect.flows-configmap:prefect-flows}") String configMapName,
                            @Value("${prefect.worker-deployment:prefect-pool-worker}") String workerDeployment) {
        this.bridge = bridge;
        this.namespace = namespace;
        this.configMapName = configMapName;
        this.workerDeployment = workerDeployment;
    }

    public PrefectBridgeClient.Deployment publish(String filename, String python,
                                                  String flowName, String deploymentName,
                                                  String entrypoint, String cron, String rrule,
                                                  List<String> tags, String description) {
        writeFlow(filename, python);
        // 不在发布路径重启 worker，也不阻塞发布：等待放到 trigger，
        // 只有「发布后立刻触发」才需要等文件同步，发布本身保持快速。
        PrefectBridgeClient.Deployment deployment = bridge.createDeployment(
                flowName, deploymentName, entrypoint, cron, rrule, tags, description);
        log.info("published prefect flow file={} flow={} deployment={} id={}",
                filename, flowName, deploymentName, deployment.deploymentId());
        return deployment;
    }

    /** 一个版本对应的 Prefect 资产：部署 id 与 ConfigMap 里的 flow 文件名。 */
    public record FlowAsset(String filename, String deploymentId) {
    }

    public void delete(String filename, String deploymentId) {
        deleteAll(List.of(new FlowAsset(filename, deploymentId)));
    }

    /**
     * 批量删除。不重启 worker：删除只需保证「部署已删、文件已从 ConfigMap 移除」，
     * 卷会在 kubelet 同步周期内更新，不会再有新运行。重启共享 worker 反而会
     * 打断其他工作流正在跑的任务；之前逐版本调用还会重启 N 次。
     */
    public void deleteAll(List<FlowAsset> assets) {
        int deleted = 0;
        for (FlowAsset asset : assets) {
            if (asset == null) {
                continue;
            }
            if (asset.deploymentId() != null && !asset.deploymentId().isBlank()) {
                bridge.deleteDeployment(asset.deploymentId());
                deleted++;
            }
            if (deleteFlow(asset.filename())) {
                deleted++;
            }
        }
        log.info("deleted prefect assets={} items={}", assets.size(), deleted);
    }

    /**
     * ConfigMap 是整份 read-modify-write：两个工作流同时发布时，
     * 后写的会覆盖先写的，丢掉另一个 flow 文件。进程内串行化即可。
     */
    private static final Object CONFIGMAP_LOCK = new Object();

    private void writeFlow(String filename, String python) {
        synchronized (CONFIGMAP_LOCK) {
            writeFlowLocked(filename, python);
        }
    }

    private void writeFlowLocked(String filename, String python) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            ConfigMap existing = client.configMaps().inNamespace(namespace).withName(configMapName).get();
            Map<String, String> data = existing == null || existing.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.getData());
            data.put(filename, python);
            ConfigMap target = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(configMapName)
                    .withNamespace(namespace)
                    .addToLabels("app", "prefect")
                    .endMetadata()
                    .withData(data)
                    .build();
            client.configMaps().inNamespace(namespace).resource(target).createOrReplace();
        } catch (Exception e) {
            log.error("failed to write prefect flow file={}", filename, e);
            throw new ApiException(502, "发布 Prefect Flow 失败: " + e.getMessage());
        }
    }

    private boolean deleteFlow(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        synchronized (CONFIGMAP_LOCK) {
            return deleteFlowLocked(filename);
        }
    }

    private boolean deleteFlowLocked(String filename) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            ConfigMap existing = client.configMaps().inNamespace(namespace).withName(configMapName).get();
            if (existing == null || existing.getData() == null || !existing.getData().containsKey(filename)) {
                return false;
            }
            Map<String, String> data = new LinkedHashMap<>(existing.getData());
            data.remove(filename);
            client.configMaps().inNamespace(namespace).withName(configMapName)
                    .edit(cm -> new ConfigMapBuilder(cm).withData(data).build());
            return true;
        } catch (Exception e) {
            log.warn("failed to remove prefect flow file={}: {}", filename, e.getMessage());
            return false;
        }
    }

    /**
     * 等 ConfigMap 卷同步到 worker 容器（kubelet 默认分钟级）。
     *
     * <p>以前靠重启 worker 强制挂载，代价是打断所有正在运行的流程。
     * 现在由 trigger 在真正要跑之前等文件可见，发布本身不再阻塞。</p>
     *
     * @return 90 秒内是否可见；false 时调用方应返回「稍后重试」
     */
    /**
     * 等 Flow 文件真正可见再触发：
     * 1) 新 bridge 暴露 /flows/{file}（与 worker 同节点同卷），2 秒一次探测，最多 90 秒；
     * 2) 老 bridge 没有该接口时，退回「发布时间满 60 秒」的时间兜底。
     * 这样发布很久的流程触发是零等待，只有刚发布的才等，且是按真实就绪而不是固定时长。
     */
    public void awaitFlowFile(String filename, long publishedEpochMs) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        long deadline = System.currentTimeMillis() + 90_000L;
        boolean probed = false;
        while (System.currentTimeMillis() < deadline) {
            Boolean ready = bridge.flowReady(filename);
            if (ready == null) {
                // 探测接口不可用有两种原因：老版本 bridge 没有 /flows（按时间兜底等），
                // 或 bridge/Prefect 连不上。后者等满 60 秒也一定会失败——实测触发
                // 白等 57 秒才报 502。这里先确认可达性，不可达就直接返回。
                if (!bridge.reachable()) {
                    log.warn("prefect bridge unreachable, skip flow file wait: {}", filename);
                    return;
                }
                break;
            }
            probed = true;
            if (ready) {
                // bridge 与 worker 各自从 kubelet 同步 ConfigMap：bridge 先看到文件，
                // 不代表 worker 的挂载已经更新（实测偶发首触发 FileNotFound）。
                // 发布后 5 分钟内的首次触发补一个宽限期，之后不再等待。
                long age = publishedEpochMs > 0 ? System.currentTimeMillis() - publishedEpochMs : -1;
                if (age >= 0 && age < 300_000L) {
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    log.info("flow file grace wait done: {}", filename);
                }
                log.info("flow file ready in worker volume: {}", filename);
                return;
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!probed && publishedEpochMs > 0) {
            long waitMs = 60_000L - (System.currentTimeMillis() - publishedEpochMs);
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 旧实现：exec 进 worker 查文件，在 k8s 1.36 + fabric8 6.x 下 WebSocket 握手失败，
     * 会让所有触发恒返回 503。保留代码仅供升级客户端后参考，不要再调用。
     */
    @Deprecated
    public boolean waitForFlowFile(String filename) {
        // 实测 ConfigMap 卷同步 25–50 秒，kubelet 最坏约 1 分钟，留足余量
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            if (workerHasFlowFile(filename)) {
                log.info("flow file visible in worker: {}", filename);
                return true;
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("flow file not visible in worker after 90s: {}", filename);
        return false;
    }

    private boolean workerHasFlowFile(String filename) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (Pod pod : client.pods().inNamespace(namespace)
                    .withLabel("app", workerDeployment).list().getItems()) {
                if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
                    continue;
                }
                String container = pod.getSpec() == null || pod.getSpec().getContainers().isEmpty()
                        ? null : pod.getSpec().getContainers().get(0).getName();
                try (ExecWatch exec = client.pods().inNamespace(namespace)
                        .withName(pod.getMetadata().getName())
                        .inContainer(container)
                        .writingOutput(OutputStream.nullOutputStream())
                        .writingError(OutputStream.nullOutputStream())
                        // Prefect 镜像很精简，没有 ls/test；python3 一定存在
                        .exec("python3", "-c",
                                "import os,sys; sys.exit(0 if os.path.exists(sys.argv[1]) else 1)",
                                "/opt/prefect/flows/" + filename)) {
                    Integer code = exec.exitCode().get(10, TimeUnit.SECONDS);
                    if (code != null && code == 0) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("flow file check failed: {}", e.getMessage());
        }
        return false;
    }
}
