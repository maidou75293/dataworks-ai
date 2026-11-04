package com.lab.dataworks.system;

import com.lab.dataworks.common.ApiException;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doris BE 节点级扩缩容。
 *
 * <p>BE 以 DaemonSet 部署，一个节点一个实例，所以「扩节点 / 缩节点」的操作对象是
 * <b>K8s 节点</b>，不是 Pod 副本：
 * <ul>
 *   <li>扩容 = 节点打上 {@code dataworks.io/doris-be=true}（DaemonSet 在该节点起 BE）
 *       + {@code ALTER SYSTEM ADD BACKEND}；</li>
 *   <li>缩容 = {@code ALTER SYSTEM DECOMMEND BACKEND}（Doris 自动把 tablet 迁移到其它 BE）
 *       → 等 TabletNum 归零 → {@code ALTER SYSTEM DROP BACKEND} → 去掉节点标签。</li>
 * </ul>
 *
 * <p>管理语句走「进入 FE Pod 用 127.0.0.1 的 root 执行」：root@% 的密码不在平台手上，
 * 而 Doris FE 对来自本机回环的连接允许 root 免密登录，这与手工登录 FE 的官方做法一致。
 */
@Service
public class DorisNodeService {

    private static final Logger log = LoggerFactory.getLogger(DorisNodeService.class);
    private static final String NAMESPACE = "dw-dev";
    /** DaemonSet 的节点选择标签；打上 = 该节点跑 BE，去掉 = 该节点退出 BE。 */
    private static final String BE_NODE_LABEL = "dataworks.io/doris-be";
    private static final String BE_LABEL = "doris-be";
    private static final String FE_LABEL = "doris-fe";
    private static final int HEARTBEAT_PORT = 9050;
    /** 平台的副本目标：3 份，能容忍掉一台机器。 */
    private static final int TARGET_REPLICATION = 3;
    private static final Pattern REPLICATION =
            Pattern.compile("\"replication_allocation\"\\s*=\\s*\"tag\\.location\\.default:\\s*(\\d+)");

    /** 管理语句统一走 FE 容器内的 root@127.0.0.1（见 DorisFeSqlService）。 */
    private final DorisFeSqlService feSql;

    /** 副本迁移进度缓存：19 张表的 SHOW TABLETS 要 2 次 exec，5 秒内复用。 */
    private volatile Map<String, Object> progressCache;
    private volatile long progressCacheAt;

    public DorisNodeService(DorisFeSqlService feSql) {
        this.feSql = feSql;
    }

    /** 一个节点的 BE 状态：K8s 侧 + Doris 侧合并。 */
    public record DorisNodeRow(String name, String ip, String role, boolean schedulable,
                               boolean bePodRunning, boolean registered, boolean alive,
                               boolean decommissioned, long tablets, String dataUsed,
                               String usedPct, String capacity, String note) {
    }

    /** Doris 侧一条 BE 记录。 */
    private record Backend(String backendId, String host, boolean alive, boolean decommissioned,
                           long tabletNum, String dataUsed, String usedPct, String capacity) {
    }

    public Map<String, Object> list() {
        List<Node> nodes = k8sNodes();
        List<Backend> backends = backends();
        Map<String, Pod> bePods = bePodsByNode();
        List<DorisNodeRow> rows = new ArrayList<>();
        int joined = 0;
        for (Node node : nodes) {
            String name = node.getMetadata().getName();
            String ip = internalIp(node);
            Backend be = backends.stream().filter(b -> b.host().equals(ip)).findFirst().orElse(null);
            Pod pod = bePods.get(name);
            boolean beRunning = pod != null && pod.getStatus() != null
                    && "Running".equals(pod.getStatus().getPhase());
            if (be != null) {
                joined++;
            }
            rows.add(new DorisNodeRow(name, ip, role(node),
                    !Boolean.TRUE.equals(node.getSpec() != null
                            && node.getSpec().getUnschedulable() != null
                            && node.getSpec().getUnschedulable()),
                    beRunning, be != null, be != null && be.alive(),
                    be != null && be.decommissioned(),
                    be == null ? 0 : be.tabletNum(),
                    be == null ? "-" : be.dataUsed(),
                    be == null ? "-" : be.usedPct(),
                    be == null ? "-" : be.capacity(),
                    note(node, be, beRunning)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", rows);
        // 集群只有这些机器：要真正「加节点」得先在宿主机层加一台并 join 到 k3s
        result.put("clusterNodeCount", nodes.size());
        result.put("joinedNodeCount", joined);
        return result;
    }

    /** 加入 Doris：打节点标签（DaemonSet 起 BE）+ 注册 BE 到 FE。 */
    public DorisNodeRow join(String nodeName) {
        Node node = requireNode(nodeName);
        String ip = internalIp(node);
        boolean labelChanged = !"true".equals(labelOf(node));
        if (labelChanged) {
            setNodeLabel(nodeName, BE_NODE_LABEL, "true");
            log.info("doris-be join: 节点 {} 打标签 {} = true", nodeName, BE_NODE_LABEL);
        }
        Backend be = backendOf(ip);
        if (be == null) {
            feSql.exec("ALTER SYSTEM ADD BACKEND \"" + ip + ":" + HEARTBEAT_PORT + "\"");
            log.info("doris-be join: ADD BACKEND {}", ip);
        } else if (be.decommissioned()) {
            feSql.exec("ALTER SYSTEM CANCEL DECOMMEND BACKEND \"" + ip + ":" + HEARTBEAT_PORT + "\"");
            log.info("doris-be join: 取消退役 {}", ip);
        }
        return rowOf(nodeName);
    }

    /**
     * 第一步缩容：让 Doris 把该 BE 上的 tablet 迁走。
     *
     * <p>单副本表也能迁：Doris 会在其它 BE 上克隆出一份新副本，再删掉源节点上的旧副本，
     * 所以不拦。风险在迁移窗口内——这时该 tablet 只有一份，源节点真宕了才会丢，
     * 页面用 {@link #replicationCheck()} 把这个风险提前说清楚。
     */
    public DorisNodeRow decommission(String nodeName) {
        Node node = requireNode(nodeName);
        String ip = internalIp(node);
        Backend be = backendOf(ip);
        if (be == null) {
            throw ApiException.conflict("节点 " + nodeName + " 上没有注册到 Doris 的 BE，无需退役");
        }
        List<Backend> all = backends();
        long otherAlive = all.stream().filter(b -> b.alive() && !b.host().equals(ip)).count();
        if (be.tabletNum() > 0 && otherAlive == 0) {
            throw ApiException.conflict("集群里没有其它可用的 BE，tablet 无处迁移；至少要留一台在线 BE");
        }
        if (be.tabletNum() > 0) {
            List<String> single = singleReplicaTables(be);
            if (!single.isEmpty()) {
                log.warn("doris-be decommission: {} 上有 {} 个单副本表的 tablet，迁移期间没有冗余副本",
                        ip, single.size());
            }
        }
        feSql.exec("ALTER SYSTEM DECOMMEND BACKEND \"" + ip + ":" + HEARTBEAT_PORT + "\"");
        log.info("doris-be decommission: DECOMMEND {}", ip);
        return rowOf(nodeName);
    }

    /** 副本迁移进度：每张表离「3 副本」还差多少（页面进度条用）。5 秒内复用缓存。 */
    public Map<String, Object> replicationProgress() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = progressCache;
        if (cached != null && now - progressCacheAt < 5000) {
            return cached;
        }
        List<String> tables = userTables();

        // 一次 exec 拿全部表的 tablet 明细；一次 exec 拿全部表的 DDL
        StringBuilder tabletsSql = new StringBuilder();
        StringBuilder ddlSql = new StringBuilder();
        for (String full : tables) {
            tabletsSql.append("SHOW TABLETS FROM ").append(full).append("; ");
            ddlSql.append("SHOW CREATE TABLE ").append(full).append("; ");
        }
        List<List<Map<String, String>>> tabletBlocks =
                tables.isEmpty() ? List.of() : feSql.queryBlocks(tabletsSql.toString(), "TabletId");
        List<String> ddlBlocks =
                tables.isEmpty() ? List.of() : feSql.queryTextBlocks(ddlSql.toString(), "Table");

        List<Map<String, Object>> rows = new ArrayList<>();
        long expectedTotal = 0;
        long doneTotal = 0;
        int singleReplica = 0;
        for (int i = 0; i < tables.size(); i++) {
            String full = tables.get(i);
            List<Map<String, String>> tabletRows = i < tabletBlocks.size()
                    ? tabletBlocks.get(i) : List.of();
            long replicas = tabletRows.size();
            long buckets = tabletRows.stream()
                    .map(r -> r.getOrDefault("TabletId", ""))
                    .distinct().count();
            long expected = buckets * TARGET_REPLICATION;
            int current = i < ddlBlocks.size() ? replicationOfDdl(ddlBlocks.get(i)) : 1;
            if (current < 2) {
                singleReplica++;
            }
            expectedTotal += expected;
            doneTotal += Math.min(replicas, expected);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", full);
            row.put("replication", current);
            row.put("buckets", buckets);
            row.put("replicas", replicas);
            row.put("expected", expected);
            row.put("percent", expected == 0 ? 100
                    : (int) Math.min(100, Math.round(replicas * 100.0 / expected)));
            rows.add(row);
        }
        int percent = expectedTotal == 0 ? 100
                : (int) Math.min(100, Math.round(doneTotal * 100.0 / expectedTotal));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", TARGET_REPLICATION);
        out.put("tables", rows);
        out.put("tableCount", tables.size());
        out.put("singleReplicaCount", singleReplica);
        out.put("replicas", doneTotal);
        out.put("expectedReplicas", expectedTotal);
        out.put("percent", percent);
        out.put("complete", percent >= 100 && singleReplica == 0);
        progressCache = out;
        progressCacheAt = now;
        return out;
    }

    private static int replicationOfDdl(String block) {
        Matcher m = REPLICATION.matcher(block == null ? "" : block);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    /** 副本数体检：退役前告诉用户哪些表会在迁移窗口内短暂只有一份。 */
    public Map<String, Object> replicationCheck() {
        List<String> single = new ArrayList<>();
        List<String> detail = new ArrayList<>();
        for (String full : userTables()) {
            int replication = replicationOf(full);
            if (replication < 2) {
                single.add(full);
                detail.add(full + "（" + replication + " 副本）");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("singleReplica", single);
        out.put("singleReplicaCount", single.size());
        out.put("detail", detail);
        return out;
    }

    /** 取消退役：DECOMMEND 之后反悔，Doris 停止迁移并把该 BE 恢复可用。 */
    public DorisNodeRow cancelDecommission(String nodeName) {
        Node node = requireNode(nodeName);
        String ip = internalIp(node);
        Backend be = backendOf(ip);
        if (be == null || !be.decommissioned()) {
            throw ApiException.conflict("节点 " + nodeName + " 当前不在退役中");
        }
        feSql.exec("ALTER SYSTEM CANCEL DECOMMEND BACKEND \"" + ip + ":" + HEARTBEAT_PORT + "\"");
        log.info("doris-be decommission: CANCEL {}", ip);
        return rowOf(nodeName);
    }

    /** 第二步缩容：tablet 已迁空后真正下掉 BE，并去掉节点标签（DaemonSet 删 Pod）。 */
    public DorisNodeRow drop(String nodeName) {
        Node node = requireNode(nodeName);
        String ip = internalIp(node);
        Backend be = backendOf(ip);
        if (be == null) {
            // Doris 侧已经没了，只清标签，保证重复点击也能收敛
            setNodeLabel(nodeName, BE_NODE_LABEL, null);
            return rowOf(nodeName);
        }
        if (be.tabletNum() > 0) {
            throw ApiException.conflict("节点 " + nodeName + " 上还有 " + be.tabletNum()
                    + " 个 tablet，迁移没完成，现在下掉会丢数据");
        }
        if (!be.decommissioned()) {
            throw ApiException.conflict("请先执行「开始退役」，等 tablet 迁空后再下掉");
        }
        feSql.exec("ALTER SYSTEM DROP BACKEND \"" + ip + ":" + HEARTBEAT_PORT + "\"");
        setNodeLabel(nodeName, BE_NODE_LABEL, null);
        log.info("doris-be drop: DROP BACKEND {}，已移除节点标签", ip);
        return rowOf(nodeName);
    }

    // ------------------------------------------------------------------ Doris 侧

    private List<Backend> backends() {
        List<Backend> result = new ArrayList<>();
        for (Map<String, String> row : feSql.query("SHOW BACKENDS")) {
            result.add(new Backend(
                    row.getOrDefault("BackendId", ""),
                    row.getOrDefault("Host", ""),
                    "true".equalsIgnoreCase(row.getOrDefault("Alive", "false")),
                    "true".equalsIgnoreCase(row.getOrDefault("SystemDecommissioned", "false")),
                    parseLong(row.getOrDefault("TabletNum", "0")),
                    row.getOrDefault("DataUsedCapacity", "-").trim(),
                    row.getOrDefault("UsedPct", "-").trim(),
                    row.getOrDefault("TotalCapacity", "-").trim()));
        }
        return result;
    }

    private Backend backendOf(String ip) {
        return backends().stream().filter(b -> b.host().equals(ip)).findFirst().orElse(null);
    }

    /** 该 BE 上属于「单副本表」的表名（这些 tablet 没有第二份副本，迁不走）。 */
    private List<String> singleReplicaTables(Backend be) {
        List<String> blockers = new ArrayList<>();
        for (String full : userTables()) {
            long onThisBe = 0;
            for (Map<String, String> tablet : feSql.query("SHOW TABLETS FROM " + full)) {
                if (be.backendId().equals(tablet.getOrDefault("BackendId", ""))) {
                    onThisBe++;
                }
            }
            if (onThisBe == 0) {
                continue;
            }
            if (replicationOf(full) < 2) {
                blockers.add(full + "(" + onThisBe + " tablet)");
            }
        }
        return blockers;
    }

    /**
     * 把副本数不足的表调到目标副本数。
     *
     * <p>这是缩容的前置条件：单副本表的 tablet 只有一份，退役节点时无处可迁。
     * 只加副本、不删数据，调完等 Doris 自动均衡即可。
     */
    public Map<String, Object> raiseReplication(int target) {
        if (target < 2 || target > 3) {
            throw ApiException.badRequest("副本数只能是 2 或 3（集群 3 个 BE，3 份副本才容忍掉一台机器）");
        }
        List<String> changed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String full : userTables()) {
            int current = replicationOf(full);
            if (current >= target) {
                continue;
            }
            try {
                feSql.exec("ALTER TABLE " + full + " SET (\"replication_allocation\" = "
                        + "\"tag.location.default: " + target + "\")");
                changed.add(full + "(" + current + "→" + target + ")");
            } catch (ApiException e) {
                skipped.add(full + ": " + e.getMessage());
            }
        }
        log.info("doris 副本数调整为 {}：{} 张表已改，{} 张跳过", target, changed.size(), skipped.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        result.put("changed", changed);
        result.put("skipped", skipped);
        return result;
    }

    /** 非系统库里的全部表（库名.表名）。 */
    private List<String> userTables() {
        List<String> tables = new ArrayList<>();
        for (Map<String, String> t : feSql.query(
                "SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.tables "
                        + "WHERE TABLE_SCHEMA NOT IN ('information_schema','mysql','__internal_schema','sys')")) {
            String schema = t.get("TABLE_SCHEMA");
            String table = t.get("TABLE_NAME");
            if (schema != null && table != null) {
                tables.add(schema + "." + table);
            }
        }
        return tables;
    }

    /**
     * 表当前副本数；读不到 DDL 时按 1 处理。
     *
     * <p>必须用多行块解析：DDL 单元格里带换行，按行切只能拿到第一行，
     * 永远读不到 replication_allocation（会一直误报 1 副本）。</p>
     */
    private int replicationOf(String fullTable) {
        List<String> blocks = feSql.queryTextBlocks("SHOW CREATE TABLE " + fullTable, "Table");
        return blocks.isEmpty() ? 1 : replicationOfDdl(blocks.get(0));
    }

    // ------------------------------------------------------------------ K8s 侧

    private List<Node> k8sNodes() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            return client.nodes().list().getItems();
        } catch (Exception e) {
            throw new ApiException(502, "读取 K8s 节点失败: " + e.getMessage());
        }
    }

    private Node requireNode(String name) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Node node = client.nodes().withName(name).get();
            if (node == null) {
                throw ApiException.notFound("节点不存在: " + name);
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "读取节点失败: " + e.getMessage());
        }
    }

    private void setNodeLabel(String nodeName, String key, String value) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            client.nodes().withName(nodeName).edit(n -> {
                NodeBuilder builder = new NodeBuilder(n);
                if (value == null) {
                    return builder.editOrNewMetadata().removeFromLabels(key).endMetadata().build();
                }
                return builder.editOrNewMetadata().addToLabels(key, value).endMetadata().build();
            });
        } catch (Exception e) {
            throw new ApiException(502, "修改节点标签失败: " + e.getMessage());
        }
    }

    private Map<String, Pod> bePodsByNode() {
        Map<String, Pod> map = new LinkedHashMap<>();
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            for (Pod p : client.pods().inNamespace(NAMESPACE).withLabel("app", BE_LABEL).list().getItems()) {
                if (p.getSpec() != null && p.getSpec().getNodeName() != null) {
                    map.put(p.getSpec().getNodeName(), p);
                }
            }
        } catch (Exception e) {
            log.debug("读取 BE Pod 失败: {}", e.getMessage());
        }
        return map;
    }

    private DorisNodeRow rowOf(String nodeName) {
        for (DorisNodeRow row : (List<DorisNodeRow>) list().get("nodes")) {
            if (row.name().equals(nodeName)) {
                return row;
            }
        }
        throw ApiException.notFound("节点不存在: " + nodeName);
    }

    private static String internalIp(Node node) {
        if (node.getStatus() == null || node.getStatus().getAddresses() == null) {
            return "";
        }
        return node.getStatus().getAddresses().stream()
                .filter(a -> "InternalIP".equals(a.getType()))
                .map(a -> a.getAddress())
                .findFirst().orElse("");
    }

    private static String role(Node node) {
        Map<String, String> labels = node.getMetadata().getLabels();
        if (labels == null) {
            return "worker";
        }
        if (labels.containsKey("node-role.kubernetes.io/control-plane")) {
            return "control-plane";
        }
        if (labels.containsKey("node-role.kubernetes.io/master")) {
            return "master";
        }
        return "worker";
    }

    private static String labelOf(Node node) {
        Map<String, String> labels = node.getMetadata().getLabels();
        return labels == null ? null : labels.get(BE_NODE_LABEL);
    }

    private static String note(Node node, Backend be, boolean beRunning) {
        if (be == null) {
            return beRunning ? "BE 在跑但未注册" : "未加入 Doris";
        }
        if (be.decommissioned()) {
            return be.tabletNum() == 0 ? "已迁空，可下掉" : "退役中，正在迁移 tablet";
        }
        return be.alive() ? "在线" : "已注册但心跳异常";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
