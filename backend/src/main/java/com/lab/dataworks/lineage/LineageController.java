package com.lab.dataworks.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.lab.dataworks.common.LikePattern;

@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final JdbcTemplate jdbc;

    public LineageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PreviewRequest(JsonNode model) {
    }

    /**
     * 按当前编辑器里的 SQL 实时解析表血缘（不入库）。
     *
     * <p>之前只有「发布 / 运行」后才有血缘边，没发布过的文件点血缘图是空的；
     * 这里直接解析模型里的 script，让用户编 SQL 时就能看到源表 → 目标表。</p>
     */
    @PostMapping("/preview")
    @com.lab.dataworks.auth.RequireAtom("lineage:read")
    public Map<String, Object> preview(@RequestBody(required = false) PreviewRequest req) {
        List<Map<String, Object>> edges = new ArrayList<>();
        if (req != null && req.model() != null) {
            SqlLineageParser parser = new SqlLineageParser();
            for (JsonNode task : req.model().path("tasks")) {
                if (!"SQL".equalsIgnoreCase(task.path("type").asText("SQL"))) {
                    continue;
                }
                String code = task.path("code").asText("");
                String script = task.path("script").asText("");
                for (SqlLineageParser.Edge edge : parser.parse(script)) {
                    if (!"TABLE".equals(edge.edgeType())) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("taskCode", code);
                    // 统一节点 ID：catalog.db.table（V103）
                    item.put("source", edge.sourceNode());
                    item.put("target", edge.targetNode());
                    item.put("confidence", edge.confidence());
                    edges.add(item);
                }
            }
        }
        return Map.of("edges", edges);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long workflowId,
                                    @RequestParam(required = false) Integer versionNo,
                                    @RequestParam(required = false) String table,
                                    @RequestParam(required = false) String origin,
                                    @RequestParam(required = false) String edgeType,
                                    @RequestParam(required = false) String focus,
                                    @RequestParam(required = false, defaultValue = "3") int depth,
                                    @RequestParam(required = false, defaultValue = "DEV") String env,
                                    @RequestParam(required = false, defaultValue = "false") boolean history,
                                    @RequestParam(required = false, defaultValue = "500") int limit) {
        StringBuilder sql = new StringBuilder("""
                select e.id, e.workflow_id, w.code as workflow_code, w.name as workflow_name,
                       e.version_no, e.task_code, e.source_table, e.target_table,
                       e.source_catalog, e.source_db, e.target_catalog, e.target_db,
                       e.confidence,
                       e.source_column, e.target_column, e.edge_type, e.origin,
                       e.run_id, e.occurred_at, e.created_at
                from platform.lineage_edge e
                join platform.workflow w on w.id = e.workflow_id
                where e.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(com.lab.dataworks.auth.TenantScope.current());
        if (workflowId != null) {
            sql.append(" and e.workflow_id = ?");
            args.add(workflowId);
        }
        if (versionNo != null) {
            sql.append(" and e.version_no = ?");
            args.add(versionNo);
        }
        String envFilter = normalizeEnv(env);
        sql.append(" and e.env = ?");
        args.add(envFilter);
        boolean hasFocus = focus != null && !focus.isBlank();
        // focus 需要先拿到全量边再做连通子图，不能先用 LIKE 把邻边截掉
        if (!hasFocus && table != null && !table.isBlank()) {
            String like = LikePattern.contains(table.trim().toLowerCase(Locale.ROOT));
            sql.append("""
                     and (lower(e.source_table) like ? or lower(e.target_table) like ?
                          or lower(e.source_column) like ? or lower(e.target_column) like ?)
                    """);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (origin != null && !origin.isBlank() && !"ALL".equalsIgnoreCase(origin)) {
            sql.append(" and e.origin = ?");
            args.add(origin.trim().toUpperCase(Locale.ROOT));
        }
        if (edgeType != null && !edgeType.isBlank() && !"ALL".equalsIgnoreCase(edgeType)) {
            sql.append(" and e.edge_type = ?");
            args.add(edgeType.trim().toUpperCase(Locale.ROOT));
        }
        if (!history) {
            // 默认只看「当前」血缘：发布期取该工作流最高版本，运行期取最近一次 run。
            // 历史版本/历史运行只在显式 history=true 或指定 versionNo 时返回。
            if (versionNo == null) {
                sql.append("""
                         and (e.origin <> 'PUBLISH' or e.version_no = (
                               select p.version_no from platform.lineage_edge p
                               where p.workflow_id = e.workflow_id and p.origin = 'PUBLISH'
                                 and p.env = e.env
                               order by p.version_no desc limit 1))
                         and (e.origin <> 'RUNTIME' or e.run_id is not distinct from (
                               select r.run_id from platform.lineage_edge r
                               where r.workflow_id = e.workflow_id and r.origin = 'RUNTIME'
                                 and r.env = e.env
                               order by r.occurred_at desc limit 1))
                        """);
            } else {
                sql.append("""
                         and (e.origin <> 'RUNTIME' or e.run_id is not distinct from (
                               select r.run_id from platform.lineage_edge r
                               where r.workflow_id = e.workflow_id and r.origin = 'RUNTIME'
                                 and r.env = e.env
                                 and r.version_no = ?
                               order by r.occurred_at desc limit 1))
                        """);
                args.add(versionNo);
            }
        }
        // 聚焦模式要在内存里做 BFS，必须尽量拿全；普通列表仍尊重调用方 limit
        int cap = Math.max(1, Math.min(hasFocus ? 2000 : limit, 2000));
        sql.append(" order by w.code, e.version_no desc, e.task_code, e.source_table limit ")
                .append(cap);

        List<Map<String, Object>> edges = jdbc.queryForList(sql.toString(), args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        boolean focusFound = true;
        if (hasFocus) {
            FocusResult focused = focus(edges, focus.trim(), depth);
            edges = focused.edges();
            focusFound = focused.found();
            out.put("focus", focus.trim());
            out.put("depth", focused.depth());
            out.put("componentTables", focused.tables());
        }
        out.put("focusFound", focusFound);
        out.put("count", edges.size());
        out.put("edges", edges);
        // 工作流下拉与汇总必须和当前 env/筛选后的边一致；以前单独查全表，
        // 会出现「DEV 的边 + PROD 的工作流/统计」。
        Map<Long, Map<String, Object>> workflows = new LinkedHashMap<>();
        Map<String, Map<String, Object>> summary = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            Object wid = edge.get("workflow_id");
            if (wid instanceof Number number) {
                workflows.computeIfAbsent(number.longValue(), key -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", key);
                    row.put("code", edge.get("workflow_code"));
                    row.put("name", edge.get("workflow_name"));
                    return row;
                });
            }
            String originValue = String.valueOf(edge.get("origin"));
            String edgeTypeValue = String.valueOf(edge.get("edge_type"));
            String key = originValue + "\u0000" + edgeTypeValue;
            Map<String, Object> row = summary.computeIfAbsent(key, ignored -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("origin", originValue);
                item.put("edge_type", edgeTypeValue);
                item.put("edges", 0L);
                return item;
            });
            row.put("edges", ((Number) row.get("edges")).longValue() + 1);
        }
        out.put("workflows", workflows.values().stream()
                .sorted(java.util.Comparator.comparing(row -> String.valueOf(row.get("code"))))
                .toList());
        out.put("summary", new ArrayList<>(summary.values()));
        if (workflowId != null) {
            // D08：versions 与主查询同口径按租户收口，否则可枚举他租户工作流的版本号
            out.put("versions", jdbc.queryForList("""
                    select distinct version_no from platform.lineage_edge
                    where workflow_id = ? and env = ? and tenant_id = ?
                    order by version_no desc
                    """, Integer.class, workflowId, envFilter,
                    com.lab.dataworks.auth.TenantScope.current()));
        } else if (hasFocus) {
            // 按表查看时，版本下拉来自这张表相关的全部历史版本。
            // V103 起节点 ID 是 catalog.db.table：用户输入 db.table / table 时两种写法都试。
            String rawFocus = focus.trim().toLowerCase(Locale.ROOT);
            List<String> variants = new ArrayList<>();
            variants.add(rawFocus);
            String[] parts = rawFocus.replace("`", "").split("\\.");
            variants.add(parts.length >= 3 ? rawFocus
                    : parts.length == 2 ? "doris." + rawFocus : "doris.." + rawFocus);
            out.put("versions", jdbc.queryForList("""
                    select distinct version_no from platform.lineage_edge
                    where (lower(source_table) in (?, ?) or lower(target_table) in (?, ?))
                      and env = ? and tenant_id = ?
                    order by version_no desc
                    """, Integer.class, variants.get(0), variants.get(1),
                    variants.get(0), variants.get(1), envFilter,
                    com.lab.dataworks.auth.TenantScope.current()));
        } else {
            out.put("versions", List.of());
        }
        return out;
    }

    /**
     * 血缘表清单 + 有血缘的工作流，供页面「选一张表看上下游」做可搜索下拉。
     * 只扫 lineage_edge，不碰元数据服务，响应大小可控（最多 500 张表）。
     */
    @GetMapping("/tables")
    public Map<String, Object> tables(@RequestParam(required = false) Long workflowId,
                                      @RequestParam(required = false) Integer versionNo,
                                      @RequestParam(required = false) String origin,
                                      @RequestParam(required = false, defaultValue = "DEV") String env,
                                      @RequestParam(required = false, defaultValue = "false") boolean history) {
        StringBuilder where = new StringBuilder(" where e.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(com.lab.dataworks.auth.TenantScope.current());
        if (workflowId != null) {
            where.append(" and e.workflow_id = ?");
            args.add(workflowId);
        }
        if (versionNo != null) {
            where.append(" and e.version_no = ?");
            args.add(versionNo);
        }
        where.append(" and e.env = ?");
        args.add(normalizeEnv(env));
        if (origin != null && !origin.isBlank() && !"ALL".equalsIgnoreCase(origin)) {
            where.append(" and e.origin = ?");
            args.add(origin.trim().toUpperCase(Locale.ROOT));
        }
        if (!history) {
            if (versionNo == null) {
                where.append("""
                         and (e.origin <> 'PUBLISH' or e.version_no = (
                               select p.version_no from platform.lineage_edge p
                               where p.workflow_id = e.workflow_id and p.origin = 'PUBLISH'
                                 and p.env = e.env
                               order by p.version_no desc limit 1))
                         and (e.origin <> 'RUNTIME' or e.run_id is not distinct from (
                               select r.run_id from platform.lineage_edge r
                               where r.workflow_id = e.workflow_id and r.origin = 'RUNTIME'
                                 and r.env = e.env
                               order by r.occurred_at desc limit 1))
                        """);
            } else {
                where.append("""
                         and (e.origin <> 'RUNTIME' or e.run_id is not distinct from (
                               select r.run_id from platform.lineage_edge r
                               where r.workflow_id = e.workflow_id and r.origin = 'RUNTIME'
                                 and r.env = e.env
                                 and r.version_no = ?
                               order by r.occurred_at desc limit 1))
                        """);
                args.add(versionNo);
            }
        }
        // 两次查询共用同一组参数，分别绑定
        List<Object> twice = new ArrayList<>();
        twice.addAll(args);
        twice.addAll(args);
        String sql = """
                select table_name, count(*) as edges from (
                    select e.source_table as table_name
                    from platform.lineage_edge e join platform.workflow w on w.id = e.workflow_id
                """ + where + """
                      and e.source_table <> ''
                    union all
                    select e.target_table as table_name
                    from platform.lineage_edge e join platform.workflow w on w.id = e.workflow_id
                """ + where + """
                      and e.target_table <> ''
                ) x group by table_name order by edges desc, table_name limit 500
                """;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", jdbc.queryForList(sql, twice.toArray()));
        // 工作流清单必须与上面同一组过滤条件：否则切到 PROD 时表清单为空、
        // 工作流列表却还列着只在 DEV 有血缘的工作流，点进去是一张空图。
        // D08：同一口径还必须带 e.tenant_id —— 之前只按 env 过滤，
        // 任何租户都能在这里枚举到其他租户的工作流 id/code/名称。
        StringBuilder wfWhere = new StringBuilder(" where e.tenant_id = ? and e.env = ?");
        List<Object> wfArgs = new ArrayList<>();
        wfArgs.add(com.lab.dataworks.auth.TenantScope.current());
        wfArgs.add(normalizeEnv(env));
        if (workflowId != null) {
            wfWhere.append(" and e.workflow_id = ?");
            wfArgs.add(workflowId);
        }
        if (origin != null && !origin.isBlank() && !"ALL".equalsIgnoreCase(origin)) {
            wfWhere.append(" and e.origin = ?");
            wfArgs.add(origin.trim().toUpperCase(Locale.ROOT));
        }
        out.put("workflows", jdbc.queryForList("""
                select distinct w.id, w.code, w.name
                from platform.lineage_edge e join platform.workflow w on w.id = e.workflow_id
                """ + wfWhere + " order by w.code", wfArgs.toArray()));
        return out;
    }

    private record FocusResult(List<Map<String, Object>> edges, boolean found, int depth, int tables) {
    }

    /**
     * 以某张表为中心取无向连通子图（最多 depth 跳）。默认页只画选中表的上下游，
     * 不再把整库血缘塞进一张图。
     */
    private FocusResult focus(List<Map<String, Object>> edges, String rawFocus, int depth) {
        int maxDepth = Math.max(1, Math.min(depth, 5));
        String focusKey = rawFocus.toLowerCase(Locale.ROOT);

        Set<String> seeds = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            String source = lower(edge.get("source_table"));
            String target = lower(edge.get("target_table"));
            if (source.equals(focusKey)) seeds.add(source);
            if (target.equals(focusKey)) seeds.add(target);
        }
        if (seeds.isEmpty()) {
            // 允许只输入部分表名；命中太多时取边数最多的 20 张，避免把整图拖进来
            Map<String, Integer> hits = new LinkedHashMap<>();
            for (Map<String, Object> edge : edges) {
                for (String name : List.of(lower(edge.get("source_table")), lower(edge.get("target_table")))) {
                    if (!name.isEmpty() && name.contains(focusKey)) {
                        hits.merge(name, 1, Integer::sum);
                    }
                }
            }
            hits.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(20)
                    .forEach(e -> seeds.add(e.getKey()));
        }
        if (seeds.isEmpty()) {
            return new FocusResult(List.of(), false, maxDepth, 0);
        }

        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String source = lower(edge.get("source_table"));
            String target = lower(edge.get("target_table"));
            if (source.isEmpty() || target.isEmpty() || source.equals(target)) {
                continue;
            }
            adjacency.computeIfAbsent(source, k -> new LinkedHashSet<>()).add(target);
            adjacency.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(source);
        }

        Set<String> seen = new LinkedHashSet<>(seeds);
        Map<String, Integer> level = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        seeds.forEach(name -> {
            level.put(name, 0);
            queue.add(name);
        });
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentLevel = level.getOrDefault(current, 0);
            if (currentLevel >= maxDepth) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if (seen.add(next)) {
                    level.put(next, currentLevel + 1);
                    queue.add(next);
                }
            }
        }

        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (seen.contains(lower(edge.get("source_table")))
                    || seen.contains(lower(edge.get("target_table")))) {
                kept.add(edge);
            }
        }
        return new FocusResult(kept, true, maxDepth, seen.size());
    }

    private static String lower(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeEnv(String env) {
        String value = env == null || env.isBlank() ? "DEV" : env.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("DEV", "PROD").contains(value)) {
            throw ApiException.badRequest("env 只能是 DEV 或 PROD");
        }
        return value;
    }

}
