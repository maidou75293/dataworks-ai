package com.lab.dataworks.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K8s 运维治理动作（REQ-K8S-OPS-BOARD-1）：
 *
 * <p>①幽灵 Pod 清理：卡在 Terminating（deletionTimestamp 已置但对象还在）
 * 或 Failed/Unknown 的 Pod，强制删除（grace=0）。dryRun 先给清单，页面二次确认后
 * 再真删——和节点驱逐同一套交互口径。</p>
 *
 * <p>②SeaTunnel 制品恢复：系统清理 /tmp 后节点上 hostPath
 * （/tmp/seatunnel-connectors 等）丢失，同步 Job 卡 ContainerCreating。
 * 后端<b>不直接跑 shell</b>：这里只做「疑似受害 Pod 探测 + 生成操作单」，
 * 真正的补装走 ops 脚本 scripts/ops/seatunnel-artifacts-restore.sh
 * （与 ansible tasks/25-artifacts.yml 等价链路），双轨落点见卡面 plan。</p>
 *
 * <p>③Registry 旧 tag 清理：私有仓库（deploy/k8s/05-registry.yaml，
 * REGISTRY_STORAGE_DELETE_ENABLED=true）全部走 Docker Registry v2 HTTP API，
 * 不碰 shell。保留最近 10 个数字版本 + 全部在用引用（Pod 实际镜像、
 * 工作负载模板、别名池里的 alias 如 latest）+ 在用 digest；
 * 预览=只读，执行=按 digest 删候选。物理空间回收需要仓库内
 * garbage-collect，这一步以操作单形式给出、不自动执行。</p>
 */
@Service
public class K8sOpsService {

    private static final Logger log = LoggerFactory.getLogger(K8sOpsService.class);

    /** 每个仓库保留最近多少个数字版本（卡面验收：保留最近 10 版）。 */
    static final int KEEP_VERSIONS = 10;

    /** manifest 的 Accept：docker v2 + OCI，缺一不可，否则 registry 返回 404 manifest unknown。 */
    private static final String MANIFEST_ACCEPT = String.join(",",
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.oci.image.index.v1+json");

    private final MonitorNamespaces namespaces;
    private final ObjectMapper json;
    private final String registryUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public K8sOpsService(MonitorNamespaces namespaces, ObjectMapper json,
                         @Value("${platform.registry-url:http://registry.registry:5000}") String registryUrl) {
        this.namespaces = namespaces;
        this.json = json;
        this.registryUrl = (registryUrl == null || registryUrl.isBlank()
                ? "http://registry.registry:5000" : registryUrl.trim()).replaceAll("/+$", "");
    }

    // ---------------------------------------------------------------- 幽灵 Pod

    /**
     * 清理幽灵 Pod：Terminating 卡住（deletionTimestamp 非空）或 Failed/Unknown。
     *
     * @param dryRun true=只返回清单不动手（页面二次确认弹窗的数据源）
     */
    public Map<String, Object> cleanupGhostPods(boolean dryRun) {
        List<Map<String, Object>> ghosts = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (String ns : namespaces.all()) {
                for (Pod pod : client.pods().inNamespace(ns).list().getItems()) {
                    String name = pod.getMetadata().getName();
                    String delTs = pod.getMetadata().getDeletionTimestamp();
                    boolean terminating = delTs != null && !delTs.isBlank();
                    String phase = pod.getStatus() == null || pod.getStatus().getPhase() == null
                            ? "Unknown" : pod.getStatus().getPhase();
                    boolean dead = "Failed".equals(phase) || "Unknown".equals(phase);
                    if (!terminating && !dead) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("namespace", ns);
                    row.put("name", name);
                    row.put("phase", phase);
                    row.put("terminating", terminating);
                    row.put("node", pod.getSpec() == null ? "" : pod.getSpec().getNodeName());
                    ghosts.add(row);
                    if (dryRun) {
                        continue;
                    }
                    try {
                        client.pods().inNamespace(ns).withName(name)
                                .withGracePeriod(0).delete();
                        deleted.add(ns + "/" + name);
                    } catch (Exception e) {
                        // 逐个记失败，不中断：一个删不掉不能假装全部都没删
                        failed.add(ns + "/" + name + ": " + e.getMessage());
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "读取/清理幽灵 Pod 失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", failed.isEmpty());
        out.put("dryRun", dryRun);
        out.put("scope", namespaces.all());
        out.put("ghosts", ghosts);
        out.put("deleted", deleted);
        out.put("deletedCount", deleted.size());
        out.put("failed", failed);
        if (!dryRun) {
            log.info("ghost pod cleanup: {} 个幽灵 Pod，删除 {}，失败 {}",
                    ghosts.size(), deleted.size(), failed.size());
        }
        return out;
    }

    /** Pod 创建至今的秒数；解析不了按 0（宁可漏报不误报成「卡了很久」）。 */
    private static long ageSeconds(String created) {
        try {
            return Math.max(0, java.time.Instant.now().getEpochSecond()
                    - java.time.Instant.parse(created).getEpochSecond());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- SeaTunnel 制品恢复（操作单）

    /**
     * 生成 SeaTunnel 制品恢复操作单。
     *
     * <p>后端禁止直接跑 shell：本方法只做两件事——
     * ①探测疑似受 hostPath 缺失影响的 Pod（seatunnel/sync Job 卡 Pending 或
     * 容器等待原因含挂载/镜像错误）；②给出等价于 ansible tasks/25-artifacts.yml 的
     * ops 命令。执行在运维通道（控制机/本机 Git Bash）完成。</p>
     */
    public Map<String, Object> seatunnelArtifactsTicket() {
        List<Map<String, Object>> suspected = new ArrayList<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (String ns : namespaces.all()) {
                for (Pod pod : client.pods().inNamespace(ns).list().getItems()) {
                    String name = pod.getMetadata().getName();
                    // SeaTunnel 相关 = Zeta 集群 Pod（seatunnel-*）+ 同步 Job（sync-*），
                    // 两者挂的都是 /tmp/seatunnel-* hostPath（与 SystemMonitorService 前缀口径一致）
                    if (!name.startsWith("seatunnel-") && !name.startsWith("sync-")) {
                        continue;
                    }
                    PodStatus st = pod.getStatus();
                    String phase = st == null || st.getPhase() == null ? "Unknown" : st.getPhase();
                    String reason = "";
                    if (st != null && st.getContainerStatuses() != null) {
                        for (ContainerStatus cs : st.getContainerStatuses()) {
                            if (cs.getState() != null && cs.getState().getWaiting() != null
                                    && cs.getState().getWaiting().getReason() != null) {
                                reason = cs.getState().getWaiting().getReason();
                            }
                        }
                    }
                    // 刚建的 Job 短暂 Pending 属正常生命周期；超过 60 秒还起不来才疑似制品缺失
                    boolean longPending = "Pending".equals(phase)
                            && ageSeconds(pod.getMetadata().getCreationTimestamp()) > 60;
                    boolean mountError = reason.contains("CreateContainerConfigError")
                            || reason.contains("InvalidImageName");
                    if (!longPending && !mountError) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("namespace", ns);
                    row.put("name", name);
                    row.put("phase", phase);
                    row.put("reason", reason);
                    suspected.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("seatunnel 疑似受害 Pod 探测失败: {}", e.getMessage());
        }

        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("title", "SeaTunnel 制品恢复（等价 ansible tasks/25-artifacts.yml）");
        ticket.put("steps", List.of(
                "1. 在本仓库根目录（Git Bash）执行下面的恢复命令：脚本会把发行包与连接器 tgz 重新铺到各节点的 hostPath，并做完整性校验。",
                "2. 等 K8s 自动重试挂载；卡住的 seatunnel / 同步 Job Pod 会自行恢复，无需手动删 Pod。",
                "3. 回到本页刷新，「异常 Pod」与疑似清单应归零；仍有残留时按报错点名到对应节点核对 /tmp/seatunnel-connectors。"));
        ticket.put("commands", List.of(
                "bash scripts/ops/seatunnel-artifacts-restore.sh"));
        ticket.put("manual", List.of(
                "应急手工等价链（控制机 ops-controller）：ansible-playbook -i inventory/lab.yml artifacts.yml",
                "目标目录：/tmp/seatunnel-2.3.13、/tmp/seatunnel-connectors、/tmp/seatunnel-connectors/jdbc-lib"));
        ticket.put("note", "系统清理 /tmp 会删掉 SeaTunnel 的 hostPath 制品（历史事故见 docs/运维-k3s扩容与SeaTunnel恢复-20261020.md）；"
                + "后端不直接执行远程命令，本操作单+仓内 ops 脚本双轨执行，全程留审计。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("suspected", suspected);
        out.put("suspectedCount", suspected.size());
        out.put("ticket", ticket);
        return out;
    }

    // ---------------------------------------------------------------- Registry GC

    /** 预览：可删的旧 tag 清单（纯只读，不记审计）。 */
    public Map<String, Object> registryGcPreview() {
        GcPlan plan = buildGcPlan();
        return plan.toMap(false);
    }

    /** 执行：按 digest 删除候选 tag 的 manifest；物理空间回收另行给操作单。 */
    public Map<String, Object> registryGcRun() {
        GcPlan plan = buildGcPlan();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (RepoPlan rp : plan.repos) {
            for (TagRef t : rp.candidates) {
                try {
                    HttpResponse<Void> resp = http.send(
                            HttpRequest.newBuilder(URI.create(registryUrl + "/v2/" + t.repo
                                    + "/manifests/" + t.digest)).DELETE().build(),
                            HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() == 202) {
                        deleted.add(t.repo + ":" + t.tag);
                    } else {
                        failed.add(t.repo + ":" + t.tag + " -> HTTP " + resp.statusCode());
                    }
                } catch (Exception e) {
                    failed.add(t.repo + ":" + t.tag + ": " + e.getMessage());
                }
            }
        }
        Map<String, Object> out = plan.toMap(true);
        out.put("deleted", deleted);
        out.put("deletedCount", deleted.size());
        out.put("failed", failed);
        out.put("gcNote", "manifest 已删；磁盘物理空间需要仓库内 garbage-collect 才回收（操作单）："
                + "kubectl -n registry exec deploy/registry -- registry garbage-collect --delete-untagged"
                + " /etc/docker/registry/config.yml");
        if (!deleted.isEmpty()) {
            log.info("registry gc: 删除 {} 个旧 tag，失败 {}", deleted.size(), failed.size());
        }
        return out;
    }

    // ---------------------------------------------------------------- GC 计算核心

    static class TagRef {
        final String repo;
        final String tag;
        String digest = "";
        String keepReason = "";

        TagRef(String repo, String tag) {
            this.repo = repo;
            this.tag = tag;
        }
    }

    static class RepoPlan {
        String name;
        int tagTotal;
        final List<TagRef> kept = new ArrayList<>();
        final List<TagRef> candidates = new ArrayList<>();
    }

    static class GcPlan {
        final List<RepoPlan> repos = new ArrayList<>();
        int candidateCount;
        List<String> usedRefs = new ArrayList<>();
        String registry;

        Map<String, Object> toMap(boolean executed) {
            List<Map<String, Object>> repoRows = new ArrayList<>();
            for (RepoPlan rp : repos) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rp.name);
                row.put("tagTotal", rp.tagTotal);
                row.put("keptCount", rp.kept.size());
                List<Map<String, Object>> cand = new ArrayList<>();
                for (TagRef t : rp.candidates) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("tag", t.tag);
                    c.put("digest", t.digest);
                    cand.add(c);
                }
                row.put("candidates", cand);
                List<Map<String, Object>> keptRows = new ArrayList<>();
                for (TagRef t : rp.kept) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("tag", t.tag);
                    c.put("reason", t.keepReason);
                    keptRows.add(c);
                }
                row.put("kept", keptRows);
                repoRows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("executed", executed);
            out.put("registry", registry);
            out.put("keepPolicy", "每个仓库保留最近 " + KEEP_VERSIONS + " 个数字版本 + 全部在用 tag/digest + 别名 tag");
            out.put("repos", repoRows);
            out.put("candidateCount", candidateCount);
            out.put("usedRefs", usedRefs);
            return out;
        }
    }

    private GcPlan buildGcPlan() {
        JsonNode catalog = registryGetJson("/v2/_catalog");
        if (catalog == null) {
            throw new ApiException(502, "Registry 不可达：" + registryUrl + "/v2/_catalog");
        }
        Set<String> catalogRepos = new LinkedHashSet<>();
        for (JsonNode n : catalog.path("repositories")) {
            catalogRepos.add(n.asText());
        }

        // 在用引用：所有命名空间的 Pod（实际镜像 + imageID digest）+ 工作负载模板
        // （replicas=0 停着的服务其模板仍引用 tag，删了下次启动就拉不到）。
        Set<String> usedTags = new HashSet<>();     // repo:tag
        Set<String> usedDigests = new HashSet<>();  // digest（sha256:...）
        List<String> usedRefs = new ArrayList<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (Pod pod : client.pods().inAnyNamespace().list().getItems()) {
                collectPodRefs(pod, catalogRepos, usedTags, usedDigests, usedRefs);
            }
            for (Deployment d : client.apps().deployments().inAnyNamespace().list().getItems()) {
                collectTemplate(d.getSpec() == null || d.getSpec().getTemplate() == null
                        ? null : d.getSpec().getTemplate().getSpec(),
                        d.getMetadata().getName(), catalogRepos, usedTags, usedRefs);
            }
            for (StatefulSet s : client.apps().statefulSets().inAnyNamespace().list().getItems()) {
                collectTemplate(s.getSpec() == null || s.getSpec().getTemplate() == null
                        ? null : s.getSpec().getTemplate().getSpec(),
                        s.getMetadata().getName(), catalogRepos, usedTags, usedRefs);
            }
            for (DaemonSet ds : client.apps().daemonSets().inAnyNamespace().list().getItems()) {
                collectTemplate(ds.getSpec() == null || ds.getSpec().getTemplate() == null
                        ? null : ds.getSpec().getTemplate().getSpec(),
                        ds.getMetadata().getName(), catalogRepos, usedTags, usedRefs);
            }
        } catch (Exception e) {
            throw new ApiException(502, "扫描在用镜像失败: " + e.getMessage());
        }

        GcPlan plan = new GcPlan();
        plan.registry = registryUrl;
        plan.usedRefs = usedRefs;
        for (String repo : catalogRepos) {
            JsonNode tagsNode = registryGetJson("/v2/" + repo + "/tags/list");
            List<String> tags = new ArrayList<>();
            if (tagsNode != null) {
                for (JsonNode n : tagsNode.path("tags")) {
                    tags.add(n.asText());
                }
            }
            RepoPlan rp = new RepoPlan();
            rp.name = repo;
            rp.tagTotal = tags.size();
            // 数字版本按版本序从新到旧；无数字的（latest 等）视为别名
            List<String> numeric = new ArrayList<>();
            List<String> aliases = new ArrayList<>();
            for (String t : tags) {
                if (versionKey(t).isEmpty()) {
                    aliases.add(t);
                } else {
                    numeric.add(t);
                }
            }
            numeric.sort((t1, t2) -> compareKeys(versionKey(t2), versionKey(t1)));
            int keptNumeric = 0;
            for (String t : numeric) {
                boolean inUse = usedTags.contains(repo + ":" + t);
                if (keptNumeric < KEEP_VERSIONS || inUse) {
                    TagRef tr = new TagRef(repo, t);
                    tr.keepReason = inUse ? "在用 tag" : "最近 " + KEEP_VERSIONS + " 版";
                    rp.kept.add(tr);
                    if (!inUse) {
                        keptNumeric++;
                    }
                } else {
                    rp.candidates.add(new TagRef(repo, t));
                }
            }
            for (String t : aliases) {
                TagRef tr = new TagRef(repo, t);
                tr.keepReason = "别名 tag";
                rp.kept.add(tr);
            }
            // 候选还要按 digest 复核在用集合：删 tag 不删正在跑的镜像
            for (TagRef cand : new ArrayList<>(rp.candidates)) {
                String digest = manifestDigest(repo, cand.tag);
                if (digest != null && usedDigests.contains(digest)) {
                    cand.digest = digest;
                    cand.keepReason = "在用 digest";
                    rp.kept.add(cand);
                    rp.candidates.remove(cand);
                } else if (digest == null) {
                    // 拿不到 digest 就绝不能删：保持完整（不放进候选）
                    cand.keepReason = "digest 读取失败，保守保留";
                    rp.kept.add(cand);
                    rp.candidates.remove(cand);
                } else {
                    cand.digest = digest;
                }
            }
            if (!rp.candidates.isEmpty() || !rp.kept.isEmpty()) {
                plan.repos.add(rp);
                plan.candidateCount += rp.candidates.size();
            }
        }
        return plan;
    }

    private void collectPodRefs(Pod pod, Set<String> catalogRepos, Set<String> usedTags,
                                Set<String> usedDigests, List<String> usedRefs) {
        if (pod.getSpec() != null) {
            collectTemplate(pod.getSpec(), pod.getMetadata().getName(), catalogRepos, usedTags, usedRefs);
        }
        PodStatus st = pod.getStatus();
        if (st == null || st.getContainerStatuses() == null) {
            return;
        }
        for (ContainerStatus cs : st.getContainerStatuses()) {
            String imageID = cs.getImageID() == null ? "" : cs.getImageID();
            int at = imageID.indexOf('@');
            if (at >= 0) {
                String digest = imageID.substring(at + 1);
                String repo = repoPath(imageID.substring(0, at));
                if (catalogRepos.contains(repo)) {
                    usedDigests.add(digest);
                    if (usedRefs.size() < 200) {
                        usedRefs.add(repo + "@" + digest);
                    }
                }
            }
        }
    }

    private void collectTemplate(PodSpec spec, String owner, Set<String> catalogRepos,
                                 Set<String> usedTags, List<String> usedRefs) {
        if (spec == null || spec.getContainers() == null) {
            return;
        }
        List<Container> all = new ArrayList<>(spec.getContainers());
        if (spec.getInitContainers() != null) {
            all.addAll(spec.getInitContainers());
        }
        for (Container c : all) {
            String img = c.getImage() == null ? "" : c.getImage();
            String repo = repoPath(img);
            String tag = tagOf(img);
            if (catalogRepos.contains(repo) && !tag.isEmpty()) {
                usedTags.add(repo + ":" + tag);
                if (usedRefs.size() < 200) {
                    usedRefs.add(repo + ":" + tag + " ← " + owner);
                }
            }
        }
    }

    /** 镜像引用 → 仓库路径：去 tag/digest，去 registry host 前缀。 */
    static String repoPath(String image) {
        String s = image;
        int at = s.indexOf('@');
        if (at >= 0) {
            s = s.substring(0, at);
        }
        int lastSlash = s.lastIndexOf('/');
        int colon = s.lastIndexOf(':');
        if (colon > lastSlash) {
            s = s.substring(0, colon);
        }
        int firstSlash = s.indexOf('/');
        if (firstSlash > 0) {
            String host = s.substring(0, firstSlash);
            if (host.contains(".") || host.contains(":") || host.equals("localhost")) {
                s = s.substring(firstSlash + 1);
            }
        }
        return s;
    }

    static String tagOf(String image) {
        String s = image;
        int at = s.indexOf('@');
        if (at >= 0) {
            return "";
        }
        int lastSlash = s.lastIndexOf('/');
        int colon = s.lastIndexOf(':');
        return colon > lastSlash ? s.substring(colon + 1) : "";
    }

    /** 两个版本 key 的先后：逐段比数字，相等时更长（更多段）者更新。 */
    static int compareKeys(List<Long> a, List<Long> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Long.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /** 数字版本 key：v130 → [130]；1.20.1 → [1,20,1]；latest → 空（别名）。 */
    static List<Long> versionKey(String tag) {
        List<Long> out = new ArrayList<>();
        StringBuilder digits = new StringBuilder();
        for (char ch : tag.toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (digits.length() > 0) {
                out.add(Long.parseLong(digits.toString()));
                digits.setLength(0);
            }
        }
        if (digits.length() > 0) {
            out.add(Long.parseLong(digits.toString()));
        }
        return out;
    }

    // ---------------------------------------------------------------- registry HTTP helpers

    private JsonNode registryGetJson(String path) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(registryUrl + path))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return json.readTree(resp.body());
        } catch (Exception e) {
            log.warn("registry get {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    private String manifestDigest(String repo, String tag) {
        try {
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(URI.create(registryUrl + "/v2/" + repo + "/manifests/" + tag))
                            .timeout(Duration.ofSeconds(5))
                            .header("Accept", MANIFEST_ACCEPT)
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200) {
                return null;
            }
            return resp.headers().firstValue("Docker-Content-Digest").orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
