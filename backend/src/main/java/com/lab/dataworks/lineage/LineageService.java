package com.lab.dataworks.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Savepoint;
import java.util.List;

/**
 * 血缘记录：发布时静态解析（origin=PUBLISH），运行期由 Flow 回报实际执行的 SQL
 * （origin=RUNTIME）。表级边是基础，INSERT/CTAS 带显式列时额外记列级边。
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    private final JdbcTemplate jdbc;
    private final DorisPlanLineage dorisPlan;
    private final FlinkPlanLineage flinkPlan;
    private final SparkPlanLineage sparkPlan;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SqlLineageParser parser = new SqlLineageParser();

    public LineageService(JdbcTemplate jdbc, DorisPlanLineage dorisPlan,
                          FlinkPlanLineage flinkPlan, SparkPlanLineage sparkPlan) {
        this.jdbc = jdbc;
        this.dorisPlan = dorisPlan;
        this.flinkPlan = flinkPlan;
        this.sparkPlan = sparkPlan;
    }

    /**
     * 发布版本：重算该版本在指定环境的静态血缘，不动运行期记录。
     *
     * <p>SQL 节点按 {@code engine}（DORIS / SPARK / FLINK）都进同一条静态解析链路：
     * 血缘只看 SQL 文本，引擎差异只影响后面的计划确认层。降级原则（REQ-LINEAGE-SPARK-1）：
     * <b>单个任务</b>解析或入库失败只跳过这一条并留 WARN 台账，同版本其它任务照产边，
     * 本次发布照成功——血缘不能把发布拖红。</p>
     *
     * <p>逐任务隔离在 PG 下必须走保存点（BUG-VER-V572-1）：任一语句报错后整个事务
     * aborted，光靠 try/catch 捕不掉，好任务的边会被末尾的静默回滚一并吞掉——
     * 见 {@link #insertTaskIsolated}。</p>
     */
    @Transactional
    public int record(long workflowId, int versionNo, String env, String modelJson) {
        jdbc.update("""
                delete from platform.lineage_edge
                where workflow_id = ? and version_no = ? and origin = 'PUBLISH' and env = ?
                """, workflowId, versionNo, env);
        JsonNode model;
        try {
            model = mapper.readTree(modelJson);
        } catch (Exception e) {
            // 整个模型读不出来：没有任务可解析，记 WARN 后按「无血缘」返回，发布继续
            log.warn("lineage model unreadable workflow={} v{} env={}: {}",
                    workflowId, versionNo, env, e.getMessage());
            return 0;
        }
        int inserted = 0;
        int tasks = 0;
        int sparkTasks = 0;
        int sparkConfirmed = 0;
        int skipped = 0;
        // 一次发布最多花一个 3 秒预算在 Thrift 上：第一次拿不到计划就当作引擎不可用/方言不认，
        // 后面的 SPARK 任务直接落 inferred，不让发布被引擎抖动逐个拖住。
        boolean sparkPlanUsable = true;
        for (JsonNode task : model.path("tasks")) {
            if (!"SQL".equalsIgnoreCase(task.path("type").asText("SQL"))) {
                continue;
            }
            tasks++;
            String code = task.path("code").asText("");
            String sql = task.path("script").asText("");
            String engine = engineOf(task);
            if (SPARK.equals(engine)) {
                sparkTasks++;
            }
            try {
                List<SqlLineageParser.Edge> edges = parser.parse(sql);
                if (SPARK.equals(engine) && sparkPlanUsable && needsSparkPlan(task, sql)) {
                    java.util.Set<String> planned = sparkPlan.tables(sql);
                    if (planned.isEmpty()) {
                        sparkPlanUsable = false;
                    } else {
                        List<SqlLineageParser.Edge> marked = markMeasured(edges, planned);
                        if (measuredCount(marked) > measuredCount(edges)) {
                            sparkConfirmed++;
                        }
                        edges = marked;
                    }
                }
                inserted += insertTaskIsolated(workflowId, versionNo, code, edges, "PUBLISH", env, null);
            } catch (RuntimeException | StackOverflowError e) {
                // 逐任务隔离：SparkSQL 方言（INSERT OVERWRITE / PARTITION / 反引号）解析或
                // 写库失败时只丢这一条，并把「哪个任务、哪个引擎、为什么」落到 WARN 台账
                skipped++;
                log.warn("lineage task skipped workflow={} v{} env={} task={} engine={}: {}",
                        workflowId, versionNo, env, code,
                        engine.isEmpty() ? "DORIS" : engine, rootMessage(e));
            }
        }
        log.info("lineage recorded workflow={} v{} env={} origin=PUBLISH tasks={} sparkTasks={} "
                        + "sparkConfirmed={} skipped={} edges={}",
                workflowId, versionNo, env, tasks, sparkTasks, sparkConfirmed, skipped, inserted);
        return inserted;
    }

    /**
     * 单个任务的写入用 JDBC 保存点包住（BUG-VER-V572-1）。
     *
     * <p>PG 的失败语义不是「语句级」而是「事务级」：任一语句报错（现网样本＝22001
     * value too long）后整个事务进入 aborted，同事务后续语句一律 25006 失败，
     * 末尾提交静默变回滚——外层 try/catch 捕不住它，日志 {@code edges=N} 打得漂亮、
     * 库里 0 行，且坏任务之后的好任务还会收到误导性的级联 WARN。
     * 清掉 aborted 的唯一办法是回滚到保存点：坏任务连同它已写入的半截边一起退回，
     * 事务恢复可用，其它任务的边照落，外层提交不再被连坐。计数只累加保存点
     * 存活的任务，{@code edges=} 口径＝实落库数。</p>
     */
    private int insertTaskIsolated(long workflowId, int versionNo, String taskCode,
                                   List<SqlLineageParser.Edge> edges, String origin,
                                   String env, String runId) {
        if (edges.isEmpty()) {
            return 0;
        }
        Integer written = jdbc.execute((ConnectionCallback<Integer>) connection -> {
            if (connection.getAutoCommit()) {
                // 没有外层事务（每条语句自提交）：不存在 aborted 连坐，直接写
                return insert(workflowId, versionNo, taskCode, edges, origin, env, runId);
            }
            Savepoint savepoint = connection.setSavepoint();
            try {
                return insert(workflowId, versionNo, taskCode, edges, origin, env, runId);
            } catch (RuntimeException failure) {
                try {
                    connection.rollback(savepoint);
                } catch (java.sql.SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    log.warn("lineage savepoint rollback failed workflow={} v{} env={} task={}: {}",
                            workflowId, versionNo, env, taskCode, rollbackFailure.getMessage());
                }
                throw failure;
            }
        });
        return written == null ? 0 : written;
    }

    /** 已确认（measured）的表级边条数，用来统计这次发布有几个 SPARK 任务被计划确认到。 */
    static long measuredCount(List<SqlLineageParser.Edge> edges) {
        return edges.stream().filter(e -> "TABLE".equals(e.edgeType())
                && "measured".equals(e.confidence())).count();
    }

    /**
     * 这条 SPARK SQL 值不值得跑 EXPLAIN 确认。
     *
     * <p>两个条件与 Doris 侧同口径：① 只有写入类语句才有 source→target 的表级边需要确认
     * （{@link #writesTable}）；② 节点绑了独立数据源时，平台常驻 Thrift 里并没有这些表，
     * 确认出来的表名是假的，所以保持 inferred，不做跨引擎误确认。</p>
     */
    private static boolean needsSparkPlan(JsonNode task, String sql) {
        return writesTable(sql) && !task.hasNonNull("datasourceId");
    }

    /** SPARK 引擎的字面值；节点没写 engine 字段＝老任务，按 DORIS 处理。 */
    static final String SPARK = "SPARK";

    /**
     * 节点的引擎名（大写，缺省空串＝DORIS）。
     *
     * <p>这里刻意不调 {@code ScriptEngine.normalize}：它对非法值抛 400，而血缘是发布链路上的
     * best-effort 环节，不能因为一个写错的 engine 值把异常抛回发布；非法值交给发布校验报错，
     * 血缘按「非 SPARK」处理即可。</p>
     */
    static String engineOf(JsonNode task) {
        if (task == null) {
            return "";
        }
        JsonNode node = task.get("engine");
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 异常链最里层的原因（一行，最长 200 字），供 WARN 台账定位。 */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        String value = message == null ? error.getClass().getSimpleName() : message.trim();
        value = value.replaceAll("\\s+", " ");
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    /** 运行期：Flow 在 SQL 任务成功后回报实际执行的语句。 */
    @Transactional
    public int recordRuntime(long workflowId, int versionNo, String env, String taskCode,
                             String sql, String runId) {
        List<SqlLineageParser.Edge> edges = parser.parse(sql);
        // 引擎计划能确认真实读到的表：确认到的表级边标 measured；
        // 计划拿不到（外部引擎/语法不兼容/超时）就保持 inferred，绝不阻断上报。
        if (!edges.isEmpty() && writesTable(sql)) {
            String engine = taskEngine(workflowId, versionNo, taskCode);
            if (SPARK.equals(engine)) {
                // SPARK 节点的 SQL 只有 Spark 引擎解释得了：用 Doris EXPLAIN 确认会得到
                // 「另一个引擎读到的表」，那种 measured 是假确认，必须按引擎分派。
                java.util.Set<String> planned = sparkPlan.tables(sql);
                if (!planned.isEmpty()) {
                    edges = markMeasured(edges, planned);
                }
            } else {
                Long projectId = projectOf(workflowId);
                if (projectId != null) {
                    java.util.Set<String> planned = dorisPlan.tables(projectId, sql);
                    if (!planned.isEmpty()) {
                        edges = markMeasured(edges, planned);
                    }
                }
            }
        }
        int inserted = insert(workflowId, versionNo, taskCode, edges, "RUNTIME", env, runId);
        log.info("lineage recorded workflow={} v{} env={} task={} origin=RUNTIME run={} edges={}",
                workflowId, versionNo, env, taskCode, runId, inserted);
        return inserted;
    }

    /**
     * 该版本模型里这个任务节点的引擎（大写；读不到＝空串，按老口径当 DORIS 处理）。
     *
     * <p>多一次版本模型查询，只为避免跨引擎误确认；查询失败不影响上报，记 WARN 后走默认口径。</p>
     */
    private String taskEngine(long workflowId, int versionNo, String taskCode) {
        try {
            Object raw = jdbc.queryForObject("""
                    select model_json::text
                    from platform.workflow_version
                    where workflow_id = ? and version_no = ?
                    """, Object.class, workflowId, versionNo);
            if (raw == null || taskCode == null || taskCode.isBlank()) {
                return "";
            }
            JsonNode model = mapper.readTree(String.valueOf(raw));
            for (JsonNode task : model.path("tasks")) {
                if (taskCode.equals(task.path("code").asText(""))) {
                    return engineOf(task);
                }
            }
        } catch (Exception e) {
            log.warn("lineage engine lookup failed workflow={} v{} task={}: {}",
                    workflowId, versionNo, taskCode, e.getMessage());
        }
        return "";
    }

    /** 写入类语句才值得跑 EXPLAIN；纯 SELECT 不产生 target 边。 */
    private static boolean writesTable(String sql) {
        String lower = sql == null ? "" : sql.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("insert ") || lower.contains("create table")
                || lower.contains("create or replace table");
    }

    private Long projectOf(long workflowId) {
        try {
            return jdbc.queryForObject(
                    "select project_id from platform.workflow where id = ?", Long.class, workflowId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 计划里出现过来源表 → 该表级边标 measured；其余保持 inferred。 */
    static List<SqlLineageParser.Edge> markMeasured(List<SqlLineageParser.Edge> edges,
                                                    java.util.Set<String> planned) {
        List<SqlLineageParser.Edge> out = new java.util.ArrayList<>(edges.size());
        for (SqlLineageParser.Edge edge : edges) {
            if (!"TABLE".equals(edge.edgeType())) {
                out.add(edge);
                continue;
            }
            String[] parts = SqlLineageParser.parts(edge.sourceTable());
            String leaf = parts.length == 0 ? "" : parts[parts.length - 1];
            boolean hit = !leaf.isEmpty() && planned.contains(leaf.toLowerCase(java.util.Locale.ROOT));
            out.add(hit ? edge.withConfidence("measured") : edge);
        }
        return out;
    }

    /**
     * Flink 作业：从 {@code /jobs/{id}/plan} 提表级 source → sink，全部标 measured。
     * 拿不到计划时返回 0，调用方按「无血缘」处理（不影响作业）。
     */
    @Transactional
    public int recordFlinkPlan(long workflowId, int versionNo, String env, String taskCode,
                               String jobId, String runId) {
        List<SqlLineageParser.Edge> edges = flinkPlan.edges(jobId);
        int inserted = edges.isEmpty() ? 0
                : insert(workflowId, versionNo, taskCode, edges, "RUNTIME", env, runId);
        log.info("lineage recorded workflow={} v{} env={} task={} origin=RUNTIME flink-job={} edges={}",
                workflowId, versionNo, env, taskCode, jobId, inserted);
        return inserted;
    }

    private int insert(long workflowId, int versionNo, String taskCode,
                       List<SqlLineageParser.Edge> edges, String origin, String env, String runId) {
        int inserted = 0;
        for (SqlLineageParser.Edge edge : edges) {
            inserted += jdbc.update("""
                    insert into platform.lineage_edge
                        (workflow_id, version_no, task_code,
                         source_catalog, source_db, source_table, source_column,
                         target_catalog, target_db, target_table, target_column,
                         edge_type, confidence, origin, env, run_id, occurred_at, tenant_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(),
                            (select tenant_id from platform.workflow where id = ?))
                    on conflict do nothing
                    """, workflowId, versionNo, taskCode,
                    edge.sourceCatalog(), edge.sourceDb(), edge.sourceNode(),
                    edge.sourceColumn() == null ? "" : edge.sourceColumn(),
                    edge.targetCatalog(), edge.targetDb(), edge.targetNode(),
                    edge.targetColumn() == null ? "" : edge.targetColumn(),
                    edge.edgeType(),
                    edge.confidence() == null ? "inferred" : edge.confidence(),
                    origin, env, runId, workflowId);
        }
        return inserted;
    }
}
