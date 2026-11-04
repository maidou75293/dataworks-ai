package com.lab.dataworks.quality;

import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 质量规则对下游调度的阻断（TC7-G2）。
 *
 * <p>DataWorks 的口径是「强规则校验失败时阻塞下游任务」。本平台原先只有
 * 同一条 Flow 内的 {@code QUALITY_GATE} 任务能挡住下游，跨工作流完全不受影响
 * （客户感知：规则配了等于没配）。</p>
 *
 * <p>这里用**窄口径**落地：</p>
 * <ol>
 *   <li>强规则 = {@code config_json->>'severity' = 'STRONG'}；缺省按弱规则处理，
 *       升级不会让存量规则突然停掉别人的链路；</li>
 *   <li>失败 = 该规则在业务日期当天最后一条执行结果 {@code passed=false}
 *       且 {@code exec_status='OK'}（规则本身没跑起来不算数据结论）；</li>
 *   <li>下游 = 同项目、同环境内，沿运行/发布期血缘（{@code platform.lineage_edge}）
 *       从失败表可达的所有表，被读了这些表的工作流本次不调度。</li>
 * </ol>
 *
 * <p>已知边界（窄口径的代价，文档里同步写明）：没有血缘记录的工作流
 * （非 SQL 任务、发布早于血缘功能的版本）反查不到下游，不会被阻断。</p>
 */
@Service
public class QualityBlockService {

    /**
     * 项目内启用中的规则 + 当天最后一次执行结果。
     *
     * <p>取 {@code max(id)} 而不是 {@code max(created_at)}：同一天可能被重跑多次，
     * 只看最后一条，避免旧的失败结果永久卡住下游。判「强/失败」放在 Java 里做，
     * 是为了让口径可以被单测覆盖（SQL 只负责取数）。</p>
     */
    private static final String RULES_SQL = """
            select q.id, q.code, q.name, q.table_name,
                   upper(coalesce(q.config_json->>'severity', '')) as severity,
                   r.passed, r.exec_status
              from platform.quality_rule q
              left join lateral (
                    select r2.passed, r2.exec_status
                      from platform.quality_result r2
                     where r2.rule_id = q.id and r2.biz_date = ?::date
                     order by r2.id desc limit 1
              ) r on true
             where q.project_id = ? and q.enabled
             order by q.id
            """;

    /** 项目内的表级血缘边（发布期 + 运行期都算，重复边对集合运算无影响）。 */
    private static final String EDGES_SQL = """
            select e.workflow_id, w.code as workflow_code, w.name as workflow_name,
                   e.source_table, e.target_table
              from platform.lineage_edge e
              join platform.workflow w on w.id = e.workflow_id
             where w.project_id = ? and e.env = ?
               and coalesce(e.source_table, '') <> ''
               and coalesce(e.target_table, '') <> ''
            """;

    private static final String WORKFLOW_PROJECT_SQL =
            "select project_id from platform.workflow where id = ?";

    private final JdbcTemplate jdbc;

    public QualityBlockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一条阻断记录：哪条强规则在哪张表上失败，挡住了哪个下游工作流。
     *
     * @param upstreamWorkflowCode 产出该表的（可见时）工作流，用于给用户说清「谁把数据弄脏的」，
     *                             找不到时为 null（例如表由同步任务写入、或血缘缺失）
     */
    public record Blocker(long workflowId, String workflowCode, String workflowName,
                          String ruleCode, String ruleName, String tableName, String bizDate,
                          Long upstreamWorkflowId, String upstreamWorkflowCode,
                          String upstreamWorkflowName) {

        /** 给运行实例/接口展示的一句话，必须带业务日期与表名，能直接定位。 */
        public String message() {
            StringBuilder sb = new StringBuilder();
            sb.append("上游强规则 ").append(ruleCode)
                    .append("（表 ").append(tableName).append("）在业务日期 ")
                    .append(bizDate).append(" 未通过");
            if (upstreamWorkflowCode != null) {
                sb.append("，产出工作流 ").append(upstreamWorkflowCode);
            }
            sb.append("，下游工作流 ").append(workflowCode).append(" 本次不调度");
            return sb.toString();
        }
    }

    /** 项目级：当天被强规则挡住的所有下游工作流（含原因）。 */
    public List<Blocker> forProject(long projectId, String env, LocalDate bizDate) {
        // 先校验环境：没有失败规则时也要对畸形参数报 400，不能靠「恰好没数据」掩盖
        String normalizedEnv = normalizeEnv(env);
        List<Map<String, Object>> rawRules = jdbc.queryForList(RULES_SQL, bizDate, projectId);
        List<Map<String, Object>> rules = new ArrayList<>(rawRules.size());
        for (Map<String, Object> rule : rawRules) {
            if (isBlockingFailure(rule.get("severity"), rule.get("passed"), rule.get("exec_status"))) {
                rules.add(rule);
            }
        }
        if (rules.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(EDGES_SQL, projectId, normalizedEnv);
        List<Edge> edges = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            edges.add(new Edge(
                    ((Number) row.get("workflow_id")).longValue(),
                    string(row.get("workflow_code")),
                    string(row.get("workflow_name")),
                    normalize(string(row.get("source_table"))),
                    normalize(string(row.get("target_table")))));
        }
        if (edges.isEmpty()) {
            return List.of();
        }

        List<Blocker> out = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String seed = normalize(string(rule.get("table_name")));
            if (seed.isEmpty()) {
                continue;
            }
            Set<String> reachable = reachableFrom(seed, edges);
            // 谁产出这张表：给用户看的上游工作流（可能不存在）
            Long producerId = null;
            String producerCode = null;
            String producerName = null;
            for (Edge edge : edges) {
                if (sameTable(edge.target(), seed)) {
                    producerId = edge.workflowId();
                    producerCode = edge.workflowCode();
                    producerName = edge.workflowName();
                    break;
                }
            }
            // 同一个工作流可能读了多张可达表，按工作流去重，但保留第一条原因
            Set<Long> seen = new HashSet<>();
            for (Edge edge : edges) {
                // 读了「脏数据会扩散到的任何一张表」就是下游（可达集已含种子表本身）
                if (containsTable(reachable, edge.source())) {
                    if (!seen.add(edge.workflowId())) {
                        continue;
                    }
                    out.add(new Blocker(edge.workflowId(), edge.workflowCode(), edge.workflowName(),
                            string(rule.get("code")), string(rule.get("name")),
                            string(rule.get("table_name")), bizDate.toString(),
                            producerId, producerCode, producerName));
                }
            }
        }
        out.sort(Comparator.comparing(Blocker::workflowCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(Blocker::ruleCode, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    /** 单个工作流：会挡住它本次运行的强规则失败（空 = 可以调度）。 */
    public List<Blocker> forWorkflow(long workflowId, String env, LocalDate bizDate) {
        List<Long> projects = jdbc.queryForList(WORKFLOW_PROJECT_SQL, Long.class, workflowId);
        if (projects.isEmpty() || projects.get(0) == null) {
            throw ApiException.notFound("工作流不存在: " + workflowId);
        }
        List<Blocker> out = new ArrayList<>();
        for (Blocker blocker : forProject(projects.get(0), env, bizDate)) {
            if (blocker.workflowId() == workflowId) {
                out.add(blocker);
            }
        }
        return out;
    }

    /**
     * 这条规则今天是否构成「阻断性失败」。
     *
     * <ul>
     *   <li>只有 STRONG 才阻断；缺省/未知一律按弱规则（升级不动存量行为）；</li>
     *   <li>必须真的执行过（{@code passed != null}）：没跑过只是未知，不能当成失败；</li>
     *   <li>{@code exec_status=ERROR} 是「规则没跑起来」，不是数据不达标
     *       （与 V90、/api/quality/gate 的 502/409 区分口径一致），不阻断。</li>
     * </ul>
     */
    static boolean isBlockingFailure(Object severity, Object passed, Object execStatus) {
        if (!"STRONG".equals(severity == null ? "" : String.valueOf(severity).trim().toUpperCase(Locale.ROOT))) {
            return false;
        }
        if (!Boolean.FALSE.equals(passed)) {
            return false;
        }
        String status = execStatus == null ? "OK" : String.valueOf(execStatus).trim().toUpperCase(Locale.ROOT);
        return "OK".equals(status);
    }

    // ---------------------------------------------------------------- 图计算

    /** 表级血缘边：workflowId 执行了 source → target。 */
    record Edge(long workflowId, String workflowCode, String workflowName,
                String source, String target) {
    }

    /**
     * 从种子表出发，沿 source → target 求可达表集合（脏数据会扩散到的表）。
     * 传递闭包是必须的：规则通常配在 ODS 表上，被挡的是读 DWD 的再下一层工作流。
     */
    static Set<String> reachableFrom(String seed, List<Edge> edges) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        reached.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (Edge edge : edges) {
                if (!sameTable(edge.source(), node)) {
                    continue;
                }
                if (reached.add(edge.target())) {
                    queue.add(edge.target());
                }
            }
        }
        return reached;
    }

    private static boolean containsTable(Set<String> tables, String name) {
        for (String table : tables) {
            if (sameTable(table, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 表名同表判定：规则里常写 {@code db.table}，血缘里可能是
     * {@code catalog.db.table}，按点分段后缀比较（与 /api/lineage 的口径一致）。
     * 只做后缀匹配是有代价的——不同库的同名表会被当成一张表，
     * 窄口径下宁可多挡也不漏挡，已在文档写明。
     */
    static boolean sameTable(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.equals(right)
                || left.endsWith("." + right)
                || right.endsWith("." + left);
    }

    static String normalize(String table) {
        return table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeEnv(String env) {
        if (env == null || env.isBlank()) {
            return "DEV";
        }
        String value = env.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DEV", "PROD").contains(value)) {
            throw ApiException.badRequest("env 只能是 DEV 或 PROD: " + env);
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 供内部接口/前端展示：只保留稳定字段并展开 message。 */
    public static Map<String, Object> toView(Blocker blocker) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("workflowId", blocker.workflowId());
        item.put("workflowCode", blocker.workflowCode());
        item.put("workflowName", blocker.workflowName());
        item.put("ruleCode", blocker.ruleCode());
        item.put("ruleName", blocker.ruleName());
        item.put("tableName", blocker.tableName());
        item.put("bizDate", blocker.bizDate());
        item.put("upstreamWorkflowId", blocker.upstreamWorkflowId());
        item.put("upstreamWorkflowCode", blocker.upstreamWorkflowCode());
        item.put("message", blocker.message());
        return item;
    }
}
