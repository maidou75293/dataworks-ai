package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.secret.SecretCipher;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把平台里的同步任务渲染成 SeaTunnel conf，并以 K8s Job 提交到 Zeta 集群。
 *
 * v1 起支持通用连接器：source_type/sink_type + source_config/sink_config。
 * 旧的 JDBC -> Doris 字段仍然兼容（config 为空时回退）。
 */
@Service
public class SyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(SyncTaskService.class);

    private final String namespace;
    private final String configMap;
    /** 私有仓库里的引擎镜像；节点把 docker.io 转发到外部源，不能依赖它拉内网镜像。 */
    private final String image;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretCipher cipher;

    public SyncTaskService(@Value("${seatunnel.namespace:dw-dev}") String namespace,
                           @Value("${seatunnel.config-configmap:seatunnel-config}") String configMap,
                           @Value("${seatunnel.image:127.0.0.1:5000/library/seatunnel:dev}")
                           String image,
                           JdbcTemplate jdbc, ObjectMapper json, SecretCipher cipher) {
        this.namespace = namespace;
        this.configMap = configMap;
        this.image = image;
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
    }

    public String renderConf(Map<String, Object> task) {
        String parallelism = String.valueOf(task.getOrDefault("parallelism", 1));
        String jobMode = String.valueOf(task.getOrDefault("jobMode", "BATCH")).toUpperCase();
        String sourceType = text(task.get("sourceType"), "Jdbc");
        String sinkType = text(task.get("sinkType"), "Doris");
        Map<String, Object> source = map(task.get("sourceConfig"));
        Map<String, Object> sink = map(task.get("sinkConfig"));
        if (source.isEmpty()) {
            source = legacySource(task);
        }
        if (sink.isEmpty()) {
            sink = legacySink(task);
        }

        List<Map<String, Object>> tables = sourceTables(source);
        if (!tables.isEmpty() && "Jdbc".equals(canonical(sourceType))) {
            return renderJdbcMulti(task, source, sink, sinkType, tables, parallelism, jobMode);
        }
        if ("Jdbc".equals(canonical(sourceType)) && source.get("query") != null) {
            String q = applyMapping(source, source, text(source.get("query"), ""));
            if (!q.equals(source.get("query"))) {
                source = new LinkedHashMap<>(source);
                source.put("query", q);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("env {\n");
        out.append("  parallelism = ").append(parallelism).append("\n");
        out.append("  job.mode = \"").append(jobMode).append("\"\n");
        out.append("}\n\n");
        out.append("source {\n");
        out.append(render(sourceType, source, true));
        out.append("}\n\n");
        out.append("sink {\n");
        out.append(render(sinkType, sink, false));
        out.append("}\n");
        return out.toString();
    }

    /**
     * JDBC 多表任务：每个表一对 source/sink，用 plugin_output/plugin_input 配对。
     * 每张表可选 incremental（cursor 列 + initial），平台按任务+表保存水位。
     */
    private String renderJdbcMulti(Map<String, Object> task, Map<String, Object> source,
                                   Map<String, Object> sink, String sinkType,
                                   List<Map<String, Object>> tables,
                                   String parallelism, String jobMode) {
        long taskId = task.get("id") == null ? -1 : Long.parseLong(String.valueOf(task.get("id")));
        List<Map<String, Object>> sinkTables = mapList(sink.get("tables"));
        List<String> queries = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        // 汇端配了主键/upsert 时，增量下界可以用 >=（重复读到的同一游标值会被 upsert 覆盖）；
        // 没有主键时只能用 >，否则会写出重复行。
        boolean upsert = sink.get("primary_keys") != null || sink.get("primaryKeys") != null;
        boolean backfill = "BACKFILL".equals(String.valueOf(task.get("__runType")));
        String jobName = task.get("__jobName") == null ? null : String.valueOf(task.get("__jobName"));
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> entry = tables.get(i);
            String table = text(entry.get("table"), null);
            if (table != null) {
                requireIdentifier(table, "tables[" + i + "].table");
            }
            String explicitQuery = text(entry.get("query"), null);
            String query = explicitQuery != null ? explicitQuery
                    : table == null ? null : "select * from " + table;
            if (query == null) {
                throw ApiException.badRequest("tables[" + i + "] 需要 table 或 query");
            }
            query = applyIncremental(taskId, entry, table, query, source,
                    upsert, backfill, jobName);
            query = applyMapping(entry, source, query);
            // table 模式拼出来的 SQL 也要过只读校验；query 模式原本就没经过校验
            SqlTemplates.guardReadOnly(query);
            queries.add(query);
            String target = resolveTarget(i, entry, sink, sinkTables, table);
            requireIdentifier(target, "tables[" + i + "].target");
            targets.add(target);
        }

        StringBuilder out = new StringBuilder();
        out.append("env {\n");
        out.append("  parallelism = ").append(parallelism).append("\n");
        out.append("  job.mode = \"").append(jobMode).append("\"\n");
        out.append("}\n\n");
        out.append("source {\n");
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> cfg = new LinkedHashMap<>(source);
            cfg.remove("tables");
            cfg.put("query", queries.get(i));
            cfg.put("plugin_output", "st_" + i);
            out.append(render("Jdbc", cfg, true));
        }
        out.append("}\n\n");
        out.append("sink {\n");
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> cfg = new LinkedHashMap<>(sink);
            cfg.remove("tables");
            cfg.put("table", targets.get(i));
            cfg.put("plugin_input", "st_" + i);
            // 目标可能是 Doris / S3File / LocalFile，不能一律按 Jdbc 渲染
            out.append(render(canonical(sinkType), cfg, false));
        }
        out.append("}\n");
        return out.toString();
    }

    private List<Map<String, Object>> sourceTables(Map<String, Object> source) {
        return mapList(source.get("tables"));
    }

    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    m.forEach((k, v) -> entry.put(String.valueOf(k), v));
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private String resolveTarget(int index, Map<String, Object> entry,
                                 Map<String, Object> sink,
                                 List<Map<String, Object>> sinkTables,
                                 String sourceTable) {
        String target = text(entry.get("target"), null);
        if (target == null && index < sinkTables.size()) {
            target = text(sinkTables.get(index).get("table"), null);
        }
        if (target == null) {
            String prefix = text(sink.get("tablePrefix"), null);
            if (prefix != null && sourceTable != null) {
                String base = sourceTable.contains(".")
                        ? sourceTable.substring(sourceTable.lastIndexOf('.') + 1) : sourceTable;
                target = prefix + base;
            }
        }
        if (target == null) {
            target = text(sink.get("table"), null);
        }
        if (target == null) {
            throw ApiException.badRequest("tables[" + index + "] 缺少目标表：给 table/tables/tablePrefix 之一");
        }
        return target;
    }

    /**
     * 增量窗口 = (上次水位, 本次运行前快照的上界]，两端都可以不加。
     *
     * <p>下界默认 {@code >}：追加写的汇端重复读会写出重复行。汇端配置 primary_keys
     * （upsert）时用 {@code >=}，这样同一游标值晚到的行即使上一轮没读到，这一轮也会被
     * 重新读入并由 upsert 覆盖，不会丢失。
     *
     * <p>上界来自运行前快照（{@link #snapshotWatermarkUpper}），不是作业结束后的 max()：
     * 否则作业期间新写入的行会被算进水位，下一次 {@code > 水位} 永远读不到。
     */
    private String applyIncremental(long taskId, Map<String, Object> entry,
                                    String table, String query,
                                    Map<String, Object> source,
                                    boolean upsert, boolean backfill, String jobName) {
        Map<String, Object> inc = map(entry.get("incremental"));
        String column = text(inc.get("column"), null);
        if (column == null || table == null) {
            return query;
        }
        requireIdentifier(column, "incremental.column");
        requireIdentifier(table, "table");
        String watermark = watermark(taskId, table);
        String value = watermark != null ? watermark : text(inc.get("initial"), null);
        // 补数不回写水位，也不套用「本次运行前快照的上界」：它按业务日期取历史区间
        String upper = backfill ? null : pendingUpper(taskId, table, jobName);
        if (value == null && upper == null) {
            return query;
        }
        String type = text(inc.get("type"), null);
        String url = text(source.get("url"), "");
        boolean timestamp = "timestamp".equalsIgnoreCase(type)
                || (type == null && url.startsWith("jdbc:oracle:"));
        StringBuilder where = new StringBuilder("select * from (").append(query).append(") st where ");
        if (value != null) {
            where.append(column).append(upsert ? " >= " : " > ").append(literal(value, timestamp));
        } else {
            where.append("1 = 1");
        }
        if (upper != null) {
            where.append(" and ").append(column).append(" <= ").append(literal(upper, timestamp));
        }
        return where.toString();
    }

    private String literal(String value, boolean timestamp) {
        String escaped = value.replace("'", "''");
        // Oracle 的 NLS 日期格式依环境而变，字符串直接比较会 ORA-01843；时间戳显式转换
        return timestamp
                ? "to_timestamp('" + escaped + "', 'YYYY-MM-DD HH24:MI:SS.FF')"
                : "'" + escaped + "'";
    }

    /** 本次作业在提交时快照的上界；没有快照（补数/老版本提交的作业）返回 null。 */
    private String pendingUpper(long taskId, String table, String jobName) {
        if (jobName == null) {
            return null;
        }
        try {
            List<String> values = jdbc.queryForList("""
                    select pending_value from platform.sync_task_watermark
                    where task_id = ? and table_name = ? and pending_job = ?
                    """, String.class, taskId, table, jobName);
            return values.isEmpty() ? null : values.get(0);
        } catch (Exception e) {
            log.warn("read pending watermark failed task={} table={}: {}", taskId, table, e.getMessage());
            return null;
        }
    }

    /**
     * 字段裁剪/重命名：mapping.include 给列清单，mapping.rename 给别名。
     * 没有 include 时无法安全生成列清单（源表结构未知），保持原查询。
     */
    private String applyMapping(Map<String, Object> entry, Map<String, Object> source, String query) {
        Map<String, Object> mapping = map(entry.get("mapping"));
        if (mapping.isEmpty()) {
            mapping = map(source.get("mapping"));
        }
        if (mapping.isEmpty() || !(mapping.get("include") instanceof List<?> include) || include.isEmpty()) {
            return query;
        }
        Map<String, Object> rename = map(mapping.get("rename"));
        List<String> parts = new ArrayList<>();
        for (Object col : include) {
            String name = String.valueOf(col);
            requireIdentifier(name, "mapping.include");
            String alias = rename.get(name) == null ? name : String.valueOf(rename.get(name));
            requireIdentifier(alias, "mapping.rename");
            // 别名不加引号：MySQL 双引号是字符串、Oracle 会转大写，交给方言处理
            parts.add(name + " as " + alias);
        }
        return "select " + String.join(", ", parts) + " from (" + query + ") mp";
    }

    private void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_.$\"\\[\\]]+")) {
            throw ApiException.badRequest(field + " 不是合法标识符: " + value);
        }
    }

    private String watermark(long taskId, String table) {
        try {
            List<String> values = jdbc.queryForList(
                    "select watermark_value from platform.sync_task_watermark "
                            + "where task_id = ? and table_name = ?",
                    String.class, taskId, table);
            return values.isEmpty() ? null : values.get(0);
        } catch (Exception e) {
            log.warn("read watermark failed task={} table={}: {}", taskId, table, e.getMessage());
            return null;
        }
    }

    /**
     * 运行前快照每张增量表的游标上界，写进 {@code pending_value}（{@code pending_job}
     * 标识本次作业）。作业成功后只把水位推进到这个上界——作业期间才写入的行属于下一轮。
     *
     * <p>取不到上界（表为空/连接失败）时留空，成功时退回旧行为（作业结束后取 max），
     * 不影响提交。
     */
    void snapshotWatermarkUpper(Map<String, Object> task, String jobName) {
        if (task.get("id") == null || jobName == null) {
            return;
        }
        long taskId = Long.parseLong(String.valueOf(task.get("id")));
        Map<String, Object> source = map(task.get("sourceConfig"));
        List<Map<String, Object>> tables = sourceTables(source);
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> entry : tables) {
            Map<String, Object> inc = map(entry.get("incremental"));
            String column = text(inc.get("column"), null);
            String maxColumn = text(inc.get("maxColumn"), column);
            String table = text(entry.get("table"), null);
            if (column == null || table == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("column", column);
            row.put("maxColumn", maxColumn);
            row.put("table", table);
            String query = text(entry.get("query"), null);
            if (query == null || query.isBlank()) {
                query = "select * from " + table;
            }
            query = query.trim();
            while (query.endsWith(";")) {
                query = query.substring(0, query.length() - 1).trim();
            }
            row.put("query", query);
            pending.add(row);
        }
        if (pending.isEmpty()) {
            return;
        }
        try (Connection c = openSource(taskId, source)) {
            if (c == null) {
                return;
            }
            for (Map<String, Object> row : pending) {
                String table = String.valueOf(row.get("table"));
                String column = String.valueOf(row.get("column"));
                String maxColumn = String.valueOf(row.get("maxColumn"));
                String query = String.valueOf(row.get("query"));
                try {
                    requireIdentifier(maxColumn, "incremental.maxColumn");
                    requireIdentifier(table, "table");
                    // 上界必须基于「用户自己的 query」，不能直接 max(table)：
                    // where 1=0 这类过滤掉的行会被算进水位，下一次 > 水位 就漏数。
                    String value;
                    if (wrappable(query)) {
                        try (Statement st = c.createStatement();
                             ResultSet rs = st.executeQuery(
                                     "select max(" + maxColumn + ") from (" + query + ") dw_wm")) {
                            value = rs.next() ? rs.getString(1) : null;
                        }
                        if (value != null) {
                            log.info("watermark upper bound task={} table={} job={} "
                                            + "value={} (filtered query)",
                                    taskId, table, jobName, value);
                        }
                    } else {
                        // 方案 A 降级：query 有 order by/limit/union，包不成子查询，
                        // 就按用户原 query 实际读一遍，取本次读到的最大游标值。
                        // 必须显式记日志；降级失败/0 行都不写 pending，绝不用整表 max。
                        log.warn("watermark upper fallback task={} table={} job={}: "
                                        + "query has order by/limit/union, reading rows to "
                                        + "compute actual max",
                                taskId, table, jobName);
                        value = maxByReadingQuery(c, query, maxColumn, column);
                        if (value != null) {
                            log.info("watermark upper fallback value task={} table={} "
                                            + "job={} value={}",
                                    taskId, table, jobName, value);
                        }
                    }
                    if (value == null) {
                        // 0 行（例如 where 1=0 / limit 0）：没有可推进的上界，绝不写 pending
                        log.info("watermark upper empty task={} table={} job={}: no rows",
                                taskId, table, jobName);
                        continue;
                    }
                    jdbc.update("""
                            insert into platform.sync_task_watermark
                                (task_id, table_name, cursor_column, pending_value,
                                 pending_job, updated_at)
                            values (?, ?, ?, ?, ?, now())
                            on conflict (task_id, table_name) do update set
                                cursor_column = excluded.cursor_column,
                                pending_value = excluded.pending_value,
                                pending_job = excluded.pending_job,
                                updated_at = now()
                            """, taskId, table, column, value, jobName);
                } catch (Exception e) {
                    log.warn("snapshot watermark upper failed task={} table={}: {}",
                            taskId, table, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("snapshot watermark upper skipped task={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 方案 A 降级：直接执行用户原 query，逐行取游标列最大值。
     * 只用于 order by/limit/union 这类包不成子查询的场景；超时/失败直接抛出，
     * 由调用方记日志并放弃 pending（宁可下一轮重读，也不静默推进水位）。
     */
    private String maxByReadingQuery(Connection c, String query, String maxColumn,
                                     String column) throws java.sql.SQLException {
        try (Statement st = c.createStatement()) {
            try {
                st.setQueryTimeout(120);
            } catch (Exception ignored) {
                // 驱动不支持 query timeout 时继续
            }
            try (ResultSet rs = st.executeQuery(query)) {
                Object best = null;
                while (rs.next()) {
                    Object value;
                    try {
                        value = rs.getObject(maxColumn);
                    } catch (java.sql.SQLException missingAlias) {
                        value = rs.getObject(column);
                    }
                    if (value == null) {
                        continue;
                    }
                    if (best == null || compareCursor(value, best) > 0) {
                        best = value;
                    }
                }
                return best == null ? null : String.valueOf(best);
            }
        }
    }

    private int compareCursor(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            try {
                return new java.math.BigDecimal(na.toString())
                        .compareTo(new java.math.BigDecimal(nb.toString()));
            } catch (NumberFormatException ignored) {
                // 非标准数字（NaN 等）退回字符串比较
            }
        }
        if (a instanceof java.util.Date da && b instanceof java.util.Date db) {
            return da.compareTo(db);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** order by/limit/union 不能安全包成子查询；降级为直接读用户 query 取实际 max。 */
    private boolean wrappable(String query) {
        return query != null && !java.util.regex.Pattern.compile(
                "(?i)\\border\\s+by\\b|\\blimit\\b|\\bunion\\b").matcher(query).find();
    }

    /**
     * 作业成功后推进水位：只推进到「本次运行前的快照上界」，并清掉 pending。
     * 没有快照（快照失败/不可包裹 query/0 行）时不推进，下一轮重读；
     * 绝不退回作业结束后的 max()，否则作业期间新写入会被算进水位而漏数。
     */
    public void advanceWatermark(long taskId, String jobName) {
        Map<String, Object> task;
        try {
            task = jdbc.queryForMap(
                    "select source_config from platform.sync_task where id = ?", taskId);
        } catch (Exception e) {
            log.warn("advance watermark: task {} not found", taskId);
            return;
        }
        Map<String, Object> source = jsonMap(task.get("source_config"));
        for (Map<String, Object> entry : sourceTables(source)) {
            Map<String, Object> inc = map(entry.get("incremental"));
            String column = text(inc.get("column"), null);
            String table = text(entry.get("table"), null);
            if (column == null || table == null) {
                continue;
            }
            String pending = pendingUpper(taskId, table, jobName);
            if (pending == null) {
                // 关键：不能退回「作业结束后的 max()」。快照失败/源库不可达时，
                // 作业期间新写入的行会被 max() 算进水位，下一次 > 水位 永远读不到。
                // 这里宁可不推进（下一轮重读），也不制造水位漏数。
                log.warn("watermark not advanced task={} table={} job={}: "
                                + "no pre-run snapshot; will re-read from previous watermark",
                        taskId, table, jobName);
                continue;
            }
            // 水位只前进不后退：fallback 的 limit/union 可能取到比当前水位更小的段，
            // 回写更小值会导致重复读；这里显式跳过并留日志。
            String current = watermark(taskId, table);
            if (current != null && compareCursor(pending, current) <= 0) {
                log.warn("watermark not advanced (older than current) task={} table={} "
                                + "job={} pending={} current={}",
                        taskId, table, jobName, pending, current);
                continue;
            }
            try {
                jdbc.update("""
                        update platform.sync_task_watermark
                        set watermark_value = ?, cursor_column = ?,
                            pending_value = null, pending_job = null, updated_at = now()
                        where task_id = ? and table_name = ? and pending_job = ?
                        """, pending, column, taskId, table, jobName);
                log.info("watermark advanced task={} table={} value={} (snapshot job={})",
                        taskId, table, pending, jobName);
            } catch (Exception e) {
                log.warn("advance watermark (snapshot) failed task={} table={}: {}",
                        taskId, table, e.getMessage());
            }
        }
    }

    /** 用源端连接配置打开 JDBC 连接；URL 不合法或连不上返回 null（只记日志）。 */
    private Connection openSource(long taskId, Map<String, Object> source) {
        String url = text(source.get("url"), null);
        String user = text(source.get("user"), null);
        String password = text(source.get("password"), null);
        if (url == null || user == null) {
            return null;
        }
        try {
            url = JdbcUrlGuard.validate(url);
            // 不再使用配置里的 driver：调用方传入类名等于任意类加载入口
            String driver = JdbcUrlGuard.driverFor(url);
            if (driver != null) {
                Class.forName(driver);
            }
            return DriverManager.getConnection(url, user, password);
        } catch (Exception e) {
            log.warn("open source connection failed task={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private String render(String type, Map<String, Object> config, boolean source) {
        String name = canonical(type);
        return switch (name) {
            case "Jdbc" -> renderJdbc(config, source);
            case "Doris" -> renderDoris(config, source);
            case "S3File" -> renderS3File(config, source);
            case "LocalFile" -> renderLocalFile(config, source);
            case "MongoDB" -> renderMongo(config);
            case "Redis" -> renderRedis(config, source);
            case "RabbitMQ" -> renderRabbitMq(config);
            case "Typesense" -> renderTypesense(config, source);
            case "Activemq" -> renderActivemq(config);
            case "ClickHouse" -> renderClickHouse(config, source);
            default -> renderGeneric(name, config);
        };
    }

    private String renderJdbc(Map<String, Object> c, boolean source) {
        StringBuilder sb = new StringBuilder("  Jdbc {\n");
        append(sb, "url", jdbcUrl(text(c.get("url"), "")));
        append(sb, "driver", c.get("driver"));
        append(sb, "user", c.get("user"));
        append(sb, "password", c.get("password"));
        if (source) {
            append(sb, "query", c.get("query"));
            append(sb, "plugin_output", c.get("plugin_output"));
        } else {
            append(sb, "table", c.get("table"));
            // generate_sink_sql 时连接器强制要求 database，且它用于建立连接，
            // 必须是真实库名（如 sync_test），不能填 schema。没配就从 URL 里取。
            String database = first(c, "database", "db");
            append(sb, "database", database != null ? database : databaseFromUrl(text(c.get("url"), "")));
            // 没有手写 insert SQL 时，让连接器按上游 schema 生成
            // CDC 汇必须按主键 upsert，否则 update 事件会变成重复插入
            append(sb, "primary_keys", c.get("primary_keys"));
            if (c.get("primary_keys") != null) {
                append(sb, "support_upsert_by_query_primary_key_exist", "true");
            }
            append(sb, "generate_sink_sql", "true");
            // Oracle 等目录不支持 CREATE DATABASE，允许任务按方言覆盖
            append(sb, "schema_save_mode", first(c, "schema_save_mode", "schemaSaveMode") == null
                    ? "CREATE_SCHEMA_WHEN_NOT_EXIST" : first(c, "schema_save_mode", "schemaSaveMode"));
            append(sb, "data_save_mode", first(c, "data_save_mode", "dataSaveMode") == null
                    ? "APPEND_DATA" : first(c, "data_save_mode", "dataSaveMode"));
            append(sb, "plugin_input", c.get("plugin_input"));
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderDoris(Map<String, Object> c, boolean source) {
        StringBuilder sb = new StringBuilder("  Doris {\n");
        append(sb, "fenodes", c.get("fenodes"));
        append(sb, "username", first(c, "username", "user"));
        append(sb, "password", c.get("password"));
        String tableId = text(c.get("table.identifier"), null);
        if (tableId == null) {
            String db = text(c.get("database"), null);
            String table = text(c.get("table"), null);
            tableId = db != null && table != null ? db + "." + table : table;
        }
        append(sb, "table.identifier", tableId);
        if (source) {
            // 2.3.9 的 Doris Source 额外要求 database/table（Sink 不需要）
            append(sb, "database", c.get("database"));
            append(sb, "table", c.get("table"));
        }
        if (!source) {
            append(sb, "sink.label-prefix", "st_" + text(c.get("labelPrefix"), "sync"));
            append(sb, "sink.enable-2pc", "false");
            sb.append("    doris.config = {\n");
            sb.append("      format = \"json\"\n");
            sb.append("      read_json_by_line = \"true\"\n");
            sb.append("    }\n");
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderS3File(Map<String, Object> c, boolean source) {
        StringBuilder sb = new StringBuilder("  S3File {\n");
        String bucket = text(c.get("bucket"), "");
        if (!bucket.isBlank() && !bucket.startsWith("s3a://") && !bucket.startsWith("s3://")) {
            bucket = "s3a://" + bucket;
        }
        append(sb, "bucket", bucket);
        append(sb, "path", c.get("path"));
        String rawEndpoint = text(c.get("endpoint"), text(c.get("fs.s3a.endpoint"), ""));
        boolean ssl = rawEndpoint.startsWith("https://");
        append(sb, "fs.s3a.endpoint", rawEndpoint);
        String accessKey = first(c, "accessKey", "access_key", "fs.s3a.access.key");
        String secretKey = first(c, "secretKey", "secret_key", "fs.s3a.secret.key");
        append(sb, "access_key", accessKey);
        append(sb, "secret_key", secretKey);
        // 2.3.9 文档字段名是 access_secret；同时保留 secret_key 兼容
        append(sb, "access_secret", secretKey);
        // 部分 SeaTunnel/Hadoop 版本只认 fs.s3a.* 凭据，这里同时给出
        append(sb, "fs.s3a.access.key", accessKey);
        append(sb, "fs.s3a.secret.key", secretKey);
        append(sb, "fs.s3a.endpoint.region", text(c.get("region"), "us-east-1"));
        // 连接/重试上限，避免 headBucket 卡住时无限等待
        append(sb, "fs.s3a.connection.establish.timeout", "5000");
        append(sb, "fs.s3a.connection.timeout", "5000");
        append(sb, "fs.s3a.socket.timeout", "5000");
        append(sb, "fs.s3a.connection.request.timeout", "5000");
        append(sb, "fs.s3a.attempts.maximum", "3");
        append(sb, "fs.s3a.retry.limit", "3");
        append(sb, "fs.s3a.retry.interval", "1000");
        String format = first(c, "fileFormat", "file_format_type");
        if (format == null) {
            format = "binary";
        }
        append(sb, "file_format_type", format);
        // 平台 prepare() 按这个正则登记/归档；作业端也必须用同一过滤条件，
        // 否则被排除的文件会被作业读走，却永远不会归档。
        append(sb, "file_filter_pattern", c.get("file_filter_pattern"));
        if (!"binary".equalsIgnoreCase(format)) {
            appendSchema(sb, c.get("schema"));
        }
        append(sb, "fs.s3a.path.style.access", "true");
        append(sb, "fs.s3a.aws.credentials.provider",
                "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
        append(sb, "fs.s3a.connection.ssl.enabled", ssl ? "true" : "false");
        // S3A 写文件需要本地磁盘缓冲；镜像里 hadoop.tmp.dir 不一定可写
        append(sb, "fs.s3a.buffer.dir", "/tmp");
        append(sb, "hadoop.tmp.dir", "/tmp");
        // 兜底：连接器支持 hadoop_s3_properties map，把同一组配置再放一份，
        // 避免某些版本只从 map 读取 endpoint/凭据。
        sb.append("    hadoop_s3_properties = {\n");
        appendNested(sb, "      ", "fs.s3a.endpoint", rawEndpoint);
        appendNested(sb, "      ", "fs.s3a.access.key", accessKey);
        appendNested(sb, "      ", "fs.s3a.secret.key", secretKey);
        appendNested(sb, "      ", "fs.s3a.endpoint.region", text(c.get("region"), "us-east-1"));
        appendNested(sb, "      ", "fs.s3a.path.style.access", "true");
        appendNested(sb, "      ", "fs.s3a.connection.ssl.enabled", ssl ? "true" : "false");
        appendNested(sb, "      ", "fs.s3a.aws.credentials.provider",
                "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
        appendNested(sb, "      ", "fs.s3a.connection.establish.timeout", "5000");
        appendNested(sb, "      ", "fs.s3a.connection.timeout", "5000");
        appendNested(sb, "      ", "fs.s3a.socket.timeout", "5000");
        appendNested(sb, "      ", "fs.s3a.attempts.maximum", "3");
        appendNested(sb, "      ", "fs.s3a.retry.limit", "3");
        appendNested(sb, "      ", "fs.s3a.buffer.dir", "/tmp");
        appendNested(sb, "      ", "hadoop.tmp.dir", "/tmp");
        sb.append("    }\n");
        if (!source) {
            append(sb, "tmp_path", text(c.get("tmpPath"), "/tmp/seatunnel"));
            append(sb, "data_save_mode", text(c.get("dataSaveMode"), "APPEND_DATA"));
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderLocalFile(Map<String, Object> c, boolean source) {
        StringBuilder sb = new StringBuilder("  LocalFile {\n");
        append(sb, "path", c.get("path"));
        String format = first(c, "fileFormat", "file_format_type");
        if (format == null) {
            format = "binary";
        }
        append(sb, "file_format_type", format);
        append(sb, "file_filter_pattern", c.get("file_filter_pattern"));
        if (!"binary".equalsIgnoreCase(format)) {
            appendSchema(sb, c.get("schema"));
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderMongo(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder("  MongoDB {\n");
        String uri = text(c.get("uri"), null);
        String host = text(c.get("host"), null);
        if (uri == null && host != null) {
            uri = "mongodb://" + host + ":" + text(c.get("port"), "27017") + "/";
        }
        String user = first(c, "user", "username");
        String password = text(c.get("password"), null);
        // 密码从数据源解密后注入，避免把 mongodb://user:pass@... 明文存进 config_json
        if (uri != null && user != null && password != null && !uri.contains("@")) {
            uri = uri.replaceFirst("://", "://" + urlEncode(user) + ":" + urlEncode(password) + "@");
        }
        append(sb, "uri", uri);
        append(sb, "database", c.get("database"));
        append(sb, "collection", c.get("collection"));
        if (c.get("schema") instanceof Map<?, ?> map && !map.isEmpty()) {
            appendSchema(sb, map);
        } else {
            append(sb, "schema", c.get("schema"));
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private void appendSchema(StringBuilder sb, Object schema) {
        if (!(schema instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        // 兼容两种写法：{id=int} 和 {fields:{id=int}}（前端/接口两种都有人用）
        if (map.get("fields") instanceof Map<?, ?> nested && !nested.isEmpty()) {
            map = nested;
        }
        sb.append("    schema {\n      fields {\n");
        for (Map.Entry<?, ?> e : map.entrySet()) {
            sb.append("        ").append(e.getKey()).append(" = ")
                    .append(quote(String.valueOf(e.getValue()))).append("\n");
        }
        sb.append("      }\n    }\n");
    }

    private String renderRedis(Map<String, Object> c, boolean source) {
        StringBuilder sb = new StringBuilder("  Redis {\n");
        append(sb, "host", c.get("host"));
        append(sb, "port", c.get("port"));
        if (source) {
            append(sb, "keys", c.get("keys"));
            // 配置 format=json 时 Redis Source 强制要求 schema
            append(sb, "format", c.get("format"));
            if (c.get("schema") instanceof Map<?, ?> map && !map.isEmpty()) {
                appendSchema(sb, map);
            }
        } else {
            // 2.3.9 汇端强制要求 key，它指定上游哪个字段作为 Redis 键
            append(sb, "key", text(c.get("key"), "key"));
        }
        append(sb, "batch_size", c.getOrDefault("batchSize", c.getOrDefault("batch_size", 10)));
        append(sb, "data_type", first(c, "dataType", "data_type") == null
                ? "string" : first(c, "dataType", "data_type"));
        append(sb, "auth", first(c, "auth", "password"));
        append(sb, "db_num", c.getOrDefault("dbNum", c.getOrDefault("db_num", 0)));
        append(sb, "mode", c.getOrDefault("mode", "single"));
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderActivemq(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder("  Activemq {\n");
        String uri = text(c.get("uri"), null);
        String host = text(c.get("host"), null);
        if (uri == null && host != null) {
            uri = "tcp://" + host + ":" + text(c.get("port"), "61616");
        }
        append(sb, "uri", uri);
        append(sb, "queue_name", c.get("queue_name"));
        append(sb, "username", first(c, "username", "user"));
        append(sb, "password", c.get("password"));
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderTypesense(Map<String, Object> c, boolean source) {
        StringBuilder sb = new StringBuilder("  Typesense {\n");
        Object hosts = c.get("hosts");
        if (!(hosts instanceof List<?>)) {
            String host = first(c, "host", "url");
            if (host != null) {
                hosts = List.of(host);
            }
        }
        append(sb, "hosts", hosts);
        // Typesense 认证用 api_key；数据源把密钥放在加密的 password 列
        append(sb, "api_key", first(c, "api_key", "apiKey", "password"));
        append(sb, "collection", c.get("collection"));
        if (source) {
            append(sb, "query", c.getOrDefault("query", "*"));
            append(sb, "batch_size", c.getOrDefault("batch_size", 100));
            if (c.get("schema") instanceof Map<?, ?> map && !map.isEmpty()) {
                appendSchema(sb, map);
            }
        } else {
            append(sb, "schema_save_mode",
                    c.getOrDefault("schema_save_mode", "CREATE_SCHEMA_WHEN_NOT_EXIST"));
            append(sb, "data_save_mode", c.getOrDefault("data_save_mode", "APPEND_DATA"));
            // 2.3.9 不配 primary_keys 时 serializeRow 不会写 id，Typesense 会拒收整批文档
            append(sb, "primary_keys", c.get("primary_keys"));
            append(sb, "key_delimiter", c.get("key_delimiter"));
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderRabbitMq(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder("  RabbitMQ {\n");
        append(sb, "host", c.get("host"));
        append(sb, "port", c.getOrDefault("port", 5672));
        append(sb, "virtual_host", c.getOrDefault("virtual_host", "/"));
        append(sb, "queue_name", c.get("queue_name"));
        // RabbitMQ 连接器要求键名是 username，不接受 user
        append(sb, "username", first(c, "username", "user"));
        append(sb, "password", c.get("password"));
        // 2.3.9 汇端未显式给 durable/exclusive 会 NPE
        append(sb, "durable", c.getOrDefault("durable", true));
        append(sb, "exclusive", c.getOrDefault("exclusive", false));
        append(sb, "auto_delete", c.getOrDefault("auto_delete", false));
        if (c.get("schema") instanceof Map<?, ?> map && !map.isEmpty()) {
            appendSchema(sb, map);
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private String renderClickHouse(Map<String, Object> c, boolean source) {
        // 2.3.9 的 plugin-mapping 里键是 Clickhouse（小写 h），大小写不匹配会 Plugin not found
        StringBuilder sb = new StringBuilder("  Clickhouse {\n");
        // 2.3.9 的 host 要求 host:port 形式，分开写会在解析端口时数组越界
        String chHost = text(c.get("host"), null);
        String chPort = text(c.get("port"), "8123");
        if (chHost != null && !chHost.contains(":")) {
            chHost = chHost + ":" + chPort;
        }
        append(sb, "host", chHost);
        append(sb, "database", c.get("database"));
        append(sb, "table", c.get("table"));
        append(sb, "username", first(c, "username", "user"));
        // 2.3.9 源端强制要求 password，空密码也要显式输出 ""
        sb.append("    password = ").append(quote(text(c.get("password"), ""))).append("\n");
        if (source) {
            String sql = text(c.get("sql"), null);
            if (sql == null) {
                String table = text(c.get("table"), null);
                String database = text(c.get("database"), null);
                if (table != null) {
                    requireIdentifier(table, "table");
                    if (database != null) {
                        requireIdentifier(database, "database");
                    }
                    sql = "select * from " + (database == null ? table : database + "." + table);
                }
            }
            if (sql != null) {
                SqlTemplates.guardReadOnly(sql);
            }
            append(sb, "sql", sql);
        }
        sb.append("  }\n");
        return sb.toString();
    }

    /** 平台自己的元数据，不能写进 SeaTunnel conf（否则连接器会当成未知参数报错）。 */
    private static final Set<String> RESERVED_KEYS = Set.of("params", "verify");

    private String renderGeneric(String type, Map<String, Object> c) {
        StringBuilder sb = new StringBuilder("  ").append(type).append(" {\n");
        for (Map.Entry<String, Object> e : c.entrySet()) {
            if (RESERVED_KEYS.contains(e.getKey())) {
                continue;
            }
            appendNested(sb, "    ", e.getKey(), e.getValue());
        }
        sb.append("  }\n");
        return sb.toString();
    }

    private void appendNested(StringBuilder sb, String indent, String key, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return;
        }
        if (value instanceof Map<?, ?> m) {
            sb.append(indent).append(key).append(" {\n");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                appendNested(sb, indent + "  ", String.valueOf(e.getKey()), e.getValue());
            }
            sb.append(indent).append("}\n");
        } else {
            sb.append(indent).append(key).append(" = ").append(literal(value)).append("\n");
        }
    }

    private void append(StringBuilder sb, String key, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return;
        }
        sb.append("    ").append(key).append(" = ").append(literal(value)).append("\n");
    }

    private String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(literal(item));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            // 泛型配置里的对象数组（如 Fake 的 rows）要渲染成 HOCON 内联对象，
            // 否则 String.valueOf(map) 会得到 {id=1} 这种非法值。
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                parts.add(quote(String.valueOf(e.getKey())) + " = " + literal(e.getValue()));
            }
            return "{ " + String.join(", ", parts) + " }";
        }
        return quote(String.valueOf(value));
    }

    /**
     * HOCON / JSON 双引号字符串。多行 SQL 是常态，只转义反斜杠和引号会让
     * Typesafe Config 在换行处解析失败（整个作业起不来）。
     */
    private String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * MySQL 驱动默认按服务端 latin1 解码；中文会变成 mojibake（实测 1 万行验收发现）。
     * URL 没显式给编码时补上 UTF-8，避免“行数对、内容错”。
     */
    private String jdbcUrl(String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql://")) {
            return url;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        List<String> extra = new ArrayList<>();
        if (!lower.contains("useunicode=")) {
            extra.add("useUnicode=true");
        }
        if (!lower.contains("characterencoding=")) {
            extra.add("characterEncoding=UTF-8");
        }
        // 已显式配置时区就不要重复追加：重复参数在不同驱动版本里取值顺序不一致
        if (!lower.contains("servertimezone=")) {
            extra.add("serverTimezone=UTC");
        }
        if (extra.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + String.join("&", extra);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String canonical(String type) {
        return switch (type.toLowerCase()) {
            case "jdbc" -> "Jdbc";
            case "doris" -> "Doris";
            case "s3", "s3file" -> "S3File";
            case "localfile", "local_file" -> "LocalFile";
            case "mongodb", "mongo" -> "MongoDB";
            case "redis" -> "Redis";
            case "clickhouse" -> "ClickHouse";
            // 目录里叫 Rabbitmq，插件标识是 RabbitMQ
            case "rabbitmq" -> "RabbitMQ";
            case "typesense" -> "Typesense";
            case "activemq" -> "Activemq";
            case "socket" -> "Socket";
            // MySQL / PostgreSQL 在 2.3.9 没有独立插件，统一走 Jdbc；
            // Oracle/SqlServer/DB2/Greenplum/Vertica/TiDB 同样由 connector-jdbc + 方言驱动支持
            case "mysql", "postgresql", "postgres", "oracle", "sqlserver", "db2",
                 "greenplum", "vertica", "tidb" -> "Jdbc";
            // 目录名与插件标识不一致的两处
            case "postgresql-cdc", "postgres-cdc" -> "Postgres-CDC";
            case "rocketmq" -> "Rocketmq";
            // 2.3.9 的插件标识叫 FakeSource，配置里写 Fake 会报 Plugin not found
            case "fake", "fakesource" -> "FakeSource";
            default -> type;
        };
    }

    /**
     * 从 JDBC URL 里取库名：jdbc:postgresql://host:5432/sync_test → sync_test。
     * 取不到返回 null，交给连接器报缺参。
     */
    private String databaseFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String base = url.split("\\?", 2)[0];
        int slash = base.lastIndexOf('/');
        if (slash < 0 || slash == base.length() - 1) {
            return null;
        }
        String db = base.substring(slash + 1);
        return db.isBlank() || db.contains(":") ? null : db;
    }

    private Map<String, Object> legacySource(Map<String, Object> task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", task.get("sourceJdbcUrl"));
        m.put("driver", task.get("sourceDriver"));
        m.put("user", task.get("sourceUser"));
        m.put("password", task.get("sourcePassword"));
        m.put("query", task.get("sourceQuery"));
        return m;
    }

    private Map<String, Object> legacySink(Map<String, Object> task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fenodes", task.get("sinkFenodes"));
        m.put("username", task.get("sinkUser"));
        m.put("password", task.get("sinkPassword"));
        m.put("database", task.get("sinkDatabase"));
        m.put("table", task.get("sinkTable"));
        return m;
    }

    private String first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !(value instanceof String s && s.isBlank())) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    /** PG json/jsonb 列经 JdbcTemplate 取出来可能是 String/PGobject，统一转 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(String.valueOf(value), Map.class);
        } catch (Exception e) {
            log.warn("parse config json failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 一次运行的句柄：run 表主键 + k8s Job 名。 */
    public record RunStart(long id, String jobName) {
    }

    public RunStart run(Map<String, Object> task) {
        long id = Long.parseLong(String.valueOf(task.get("id")));
        String code = String.valueOf(task.get("code")).replaceAll("[^A-Za-z0-9-]", "-");
        if (code.length() > 40) {
            code = code.substring(0, 40);
        }
        // 同一秒可能提交多个作业（补数），加短随机后缀保证 Job 名唯一
        String runSuffix = Instant.now().getEpochSecond() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 4);
        String jobName = "sync-" + code + "-" + runSuffix;
        // Secret 必须每个作业一个：同一任务的并发运行（补数/定时+手动）挂同一份 conf
        // 会互相覆盖，实测补数 3 天全部读到最后一次渲染的 bizdate。
        String secretName = "sync-sec-" + id + "-" + runSuffix;
        // 动态参数：按任务声明与本次运行传值渲染 SQL；缺参/未声明直接拒绝。
        Map<String, Object> overrides = task.get("runParams") instanceof Map<?, ?> m
                ? map(m) : Map.of();
        SqlTemplates.Result rendered = SqlTemplates.apply(task, overrides);
        String runType = task.get("runType") == null
                ? "MANUAL" : String.valueOf(task.get("runType")).toUpperCase();
        // 渲染前把水位上界快照下来，写进 pending_value。作业结束后只推进到这个上界，
        // 避免「作业期间新写入的行被 max() 算进水位」导致下次漏读。
        task.put("__jobName", jobName);
        task.put("__runType", runType);
        if (!"BACKFILL".equals(runType)) {
            snapshotWatermarkUpper(task, jobName);
        }
        String conf = renderConf(task);
        // F-A（E2E-TRIAGE N2）：Doris 汇缺表时提交前由平台自建带主键的表；失败即拒绝提交
        ensureSinkSchema(task);

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var secret = new SecretBuilder()
                    .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(namespace)
                    .addToLabels("app", "seatunnel-sync")
                    .addToLabels("job-name", jobName)
                    .endMetadata()
                    .addToData("job.conf", Base64.getEncoder()
                            .encodeToString(conf.getBytes(StandardCharsets.UTF_8)))
                    .build();
            client.secrets().inNamespace(namespace).resource(secret).createOrReplace();

            Job job = buildJob(jobName, id, secretName);
            client.batch().v1().jobs().inNamespace(namespace).resource(job).create();
            // 先建运行实例，再打提交日志：日志里就能带上 run 号
            long runId = 0;
            try {
                String verifyMode = verifyMode(task);
                Long inserted = jdbc.queryForObject("""
                        insert into platform.sync_task_run
                            (task_id, job_name, params, rendered_sql, created_by,
                             run_type, started_at, verify_mode, status)
                        values (?, ?, ?::json, ?::json, ?, ?, now(), ?, 'RUNNING')
                        returning id
                        """, Long.class, id, jobName,
                        json.writeValueAsString(rendered.params()),
                        json.writeValueAsString(rendered.sqls()),
                        task.get("createdBy") == null ? "unknown" : String.valueOf(task.get("createdBy")),
                        runType, verifyMode);
                runId = inserted == null ? 0 : inserted;
            } catch (Exception e) {
                // 快照写失败不能影响作业提交，但必须留日志
                log.warn("save sync_task_run failed task={} job={}: {}", id, jobName, e.getMessage());
            }
            // MDC：这一段后端日志带 run:xxx，便于和运行实例对齐
            if (runId > 0) {
                MDC.put("runId", "run:" + runId);
            }
            try {
                log.info("submitted sync job name={} task={} code={} run={}",
                        jobName, id, code, runId);
            } finally {
                MDC.remove("runId");
            }
            return new RunStart(runId, jobName);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("failed to submit sync job task={}", id, e);
            throw new ApiException(502, "提交同步任务失败: " + e.getMessage());
        }
    }

    /** 简单 SQL 里取 FROM 后的第一个限定表名（用于回查源表主键）；取不到返回 null。 */
    private static final java.util.regex.Pattern FROM_TABLE = java.util.regex.Pattern
            .compile("(?i)\\bfrom\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)");

    /**
     * F-A（E2E-TRIAGE N2）：{@code CREATE_SCHEMA_WHEN_NOT_EXIST}（缺省）下 Doris 汇缺表时，
     * 在提交 K8s 作业前由平台按源结果集 + 源表主键生成带 {@code unique key} 的建表 DDL 并执行。
     *
     * <p>为什么必须在平台侧做：Doris 汇在 2.3.9 里根本没有 schema_save_mode 的自建表能力，
     * 表不存在时作业直接报 {@code COMMON-24 ... has no primary keys}（红基线实测连表都不留）；
     * Jdbc 汇指向 Doris 时连接器虽能自建，但建出的是无主键表，首跑必挂。
     * DL-1 链1 靠 {@code POST /api/tables} 预建表绕开了这条路径，真自动建表分支首次执行才暴露。
     *
     * <p>边界：表已存在不动（不碰别人的表）；mode 显式为其它值不动（尊重用户语义）；
     * 非 JDBC 源/无源查询不探测、跳过按原行为；建表失败直接拒绝提交（502），
     * 不再提交一个注定失败的作业。
     */
    void ensureSinkSchema(Map<String, Object> task) {
        String sinkType = canonical(text(task.get("sinkType"), "Doris"));
        Map<String, Object> sink = map(task.get("sinkConfig"));
        if (sink.isEmpty()) {
            sink = legacySink(task);
        }
        if (!DorisSinkAutoSchema.shouldAutoCreate(sinkType, sink)) {
            return;
        }
        Map<String, Object> source = map(task.get("sourceConfig"));
        if (source.isEmpty()) {
            source = legacySource(task);
        }
        if (!"Jdbc".equals(canonical(text(task.get("sourceType"), "Jdbc")))) {
            log.info("auto-create sink table skipped task={}: sourceType={} 不是 JDBC，无法探测源结构",
                    task.get("id"), task.get("sourceType"));
            return;
        }
        List<Map<String, Object>> tables = sourceTables(source);
        List<Map<String, Object>> sinkTables = mapList(sink.get("tables"));
        // 每对 (探测 SQL, 目标表, 源表提示)；目标解析与 renderJdbcMulti 同一套 resolveTarget
        List<String[]> pairs = new ArrayList<>();
        if (!tables.isEmpty()) {
            for (int i = 0; i < tables.size(); i++) {
                Map<String, Object> entry = tables.get(i);
                String table = text(entry.get("table"), null);
                String explicit = text(entry.get("query"), null);
                if (explicit == null && table == null) {
                    continue;
                }
                String probe = applyMapping(entry, source,
                        explicit != null ? explicit : "select * from " + table);
                String target = resolveTarget(i, entry, sink, sinkTables, table);
                pairs.add(new String[] {probe, target, table});
            }
        } else {
            String raw = text(source.get("query"), null);
            if (raw == null) {
                return;
            }
            String query = applyMapping(source, source, raw);
            String target = text(sink.get("table"), null);
            if (target == null) {
                // 手写配置允许只给 table.identifier（与 renderDoris 同口径）
                target = text(sink.get("table.identifier"), null);
            }
            if (target == null) {
                return;
            }
            pairs.add(new String[] {query, target, null});
        }
        if (pairs.isEmpty()) {
            return;
        }
        String database = text(sink.get("database"), null);
        List<String> declared = DorisSinkAutoSchema.declaredPrimaryKeys(sink);
        try (Connection doris = openSinkSchemaConnection(sinkType, sink)) {
            List<String[]> missing = new ArrayList<>();
            for (String[] pair : pairs) {
                String[] dbTbl = DorisSinkAutoSchema.splitQualified(database, pair[1]);
                if (!sinkTableExists(doris, dbTbl[0], dbTbl[1])) {
                    missing.add(new String[] {dbTbl[0], dbTbl[1], pair[0], pair[2]});
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            try (Connection src = connect(source)) {
                for (String[] item : missing) {
                    List<DorisSinkAutoSchema.Column> columns = probeSourceColumns(src, item[2]);
                    List<String> pks = declared.isEmpty()
                            ? sourcePrimaryKeys(src, item[3], item[2]) : declared;
                    String ddl = DorisSinkAutoSchema.buildCreateTableDdl(item[0], item[1], columns, pks);
                    try (java.sql.Statement st = doris.createStatement()) {
                        st.execute(ddl);
                    }
                    log.info("auto-created sink table task={} target={}.{} columns={} keys={}",
                            task.get("id"), item[0], item[1], columns.size(),
                            pks.isEmpty() ? "(无主键→duplicate 模型)" : pks);
                }
            }
        } catch (ApiException e) {
            log.warn("auto-create sink table rejected task={}: {}", task.get("id"), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("auto-create sink table failed task={}: {}", task.get("id"), e.getMessage());
            throw new ApiException(502, "自动创建 sink 表失败: " + rootMessage(e));
        }
    }

    /** FE 侧执行 DDL 的连接：Doris 走 jdbcUrl/fenodes 还原的 9030 口，Jdbc 指向 Doris 走自带 url。 */
    private Connection openSinkSchemaConnection(String sinkType, Map<String, Object> sink)
            throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", DorisSinkAutoSchema.sinkJdbcUrl(sinkType, sink));
        String user = first(sink, "username", "user");
        cfg.put("user", user == null ? "" : user);
        cfg.put("password", sink.get("password") == null ? "" : String.valueOf(sink.get("password")));
        return connect(cfg);
    }

    private boolean sinkTableExists(Connection conn, String database, String table)
            throws SQLException {
        // 标识符已在 splitQualified 校验过字符集，这里只会是合法名字
        try (java.sql.Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("show tables from `" + database + "` like '" + table + "'")) {
            return rs.next();
        }
    }

    private List<DorisSinkAutoSchema.Column> probeSourceColumns(Connection conn, String query)
            throws SQLException {
        SqlTemplates.guardReadOnly(query);
        try (java.sql.PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setMaxRows(1);
            ps.setQueryTimeout(10);
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                List<DorisSinkAutoSchema.Column> columns = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(new DorisSinkAutoSchema.Column(md.getColumnLabel(i),
                            md.getColumnType(i), md.getPrecision(i), md.getScale(i),
                            md.getColumnClassName(i)));
                }
                return columns;
            }
        }
    }

    /** 源表主键（按 KEY_SEQ 排序）。提示表名优先，其次从 SQL 的 FROM 子句取；取不到返回空表。 */
    private List<String> sourcePrimaryKeys(Connection conn, String tableHint, String query) {
        String table = tableHint != null ? tableHint : fromTableName(query);
        if (table == null) {
            return List.of();
        }
        String schema = null;
        String bare = table.replace("\"", "");
        int dot = bare.lastIndexOf('.');
        if (dot >= 0) {
            schema = bare.substring(0, dot);
            bare = bare.substring(dot + 1);
        }
        try {
            java.sql.DatabaseMetaData meta = conn.getMetaData();
            List<String> pks = readPrimaryKeys(meta, schema, bare);
            if (pks.isEmpty() && bare.equals(bare.toLowerCase(Locale.ROOT))) {
                // Oracle/DB2 目录里未加引号的标识符存大写
                pks = readPrimaryKeys(meta, schema == null ? null : schema.toUpperCase(Locale.ROOT),
                        bare.toUpperCase(Locale.ROOT));
            }
            return pks;
        } catch (SQLException e) {
            // 与 SourceMetadataService 同口径：读主键失败不能让整次运行挂（退化 duplicate 模型）
            log.debug("read source primary keys failed for {}: {}", table, e.getMessage());
            return List.of();
        }
    }

    private List<String> readPrimaryKeys(java.sql.DatabaseMetaData meta, String schema, String table)
            throws SQLException {
        List<long[]> seqs = new ArrayList<>(); // {seq, index into names}
        List<String> names = new ArrayList<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
                seqs.add(new long[] {rs.getLong("KEY_SEQ"), names.size() - 1L});
            }
        }
        seqs.sort(java.util.Comparator.comparingLong(a -> a[0]));
        List<String> out = new ArrayList<>();
        for (long[] entry : seqs) {
            out.add(names.get((int) entry[1]));
        }
        return out;
    }

    private String fromTableName(String sql) {
        if (sql == null) {
            return null;
        }
        java.util.regex.Matcher m = FROM_TABLE.matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 运行前校验：SQL 渲染/只读检查 + 源库执行计划 + 目标表可访问。
     * 不写数据；返回逐项 checks，前端必须全部通过才允许提交。
     */
    public Map<String, Object> validate(Map<String, Object> task, Map<String, Object> overrides) {
        List<Map<String, Object>> checks = new ArrayList<>();
        SqlTemplates.Result rendered;
        try {
            rendered = SqlTemplates.apply(task, overrides == null ? Map.of() : overrides);
            checks.add(check("SQL 模板", true, "渲染通过，共 " + rendered.sqls().size() + " 条 SQL"));
        } catch (ApiException e) {
            checks.add(check("SQL 模板", false, e.getMessage()));
            return Map.of("ok", false, "checks", checks);
        }
        Map<String, Object> source = map(task.get("sourceConfig"));
        Map<String, Object> sink = map(task.get("sinkConfig"));
        boolean sourceJdbc = "Jdbc".equals(canonical(text(task.get("sourceType"), "")));
        boolean sinkJdbc = "Jdbc".equals(canonical(text(task.get("sinkType"), "")));
        if (sourceJdbc && !rendered.sqls().isEmpty()) {
            try (Connection conn = connect(source)) {
                for (String sql : rendered.sqls()) {
                    try (java.sql.PreparedStatement ps = conn.prepareStatement(
                            "select * from (" + sql + ") v")) {
                        ps.setMaxRows(1);
                        ps.setQueryTimeout(8);
                        try (ResultSet ignored = ps.executeQuery()) {
                            // 只验证能执行并取到元数据
                        }
                    }
                }
                checks.add(check("源端连接与 SQL", true, "连接成功，SQL 可执行"));
            } catch (Exception e) {
                checks.add(check("源端连接与 SQL", false, rootMessage(e)));
            }
        } else {
            checks.add(check("源端连接与 SQL", true, "非 JDBC 源，跳过数据库预检"));
        }
        if (sinkJdbc) {
            String table = text(sink.get("table"), null);
            if (table == null) {
                checks.add(check("目标表", false, "缺少 sinkConfig.table"));
            } else if (!table.matches("[A-Za-z0-9_.$]+")) {
                checks.add(check("目标表", false, "表名不合法: " + table));
            } else {
                try (Connection conn = connect(sink);
                     java.sql.PreparedStatement ps = conn.prepareStatement(
                             "select 1 from " + table + " where 1=0")) {
                    ps.setQueryTimeout(8);
                    ps.executeQuery().close();
                    checks.add(check("目标表", true, table + " 可访问"));
                } catch (Exception e) {
                    checks.add(check("目标表", false, rootMessage(e)));
                }
            }
        } else {
            checks.add(check("目标表", true, "非 JDBC 目标，跳过数据库预检"));
        }
        boolean ok = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        return Map.of("ok", ok, "checks", checks);
    }

    private Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("ok", ok);
        item.put("detail", detail);
        return item;
    }

    private Connection connect(Map<String, Object> config) throws Exception {
        String url = text(config.get("url"), null);
        if (url == null) {
            throw new IllegalStateException("缺少连接 URL");
        }
        url = JdbcUrlGuard.validate(url);
        String driver = JdbcUrlGuard.driverFor(url);
        if (driver != null) {
            Class.forName(driver);
        }
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", text(config.get("user"), ""));
        props.setProperty("password", text(config.get("password"), ""));
        props.setProperty("connectTimeout", "8000");
        props.setProperty("socketTimeout", "15000");
        DriverManager.setLoginTimeout(8);
        return DriverManager.getConnection(url, props);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    /** 预览：只渲染 SQL 与参数，不提交 Job。 */
    public Map<String, Object> preview(Map<String, Object> task, Map<String, Object> overrides) {
        SqlTemplates.Result rendered = SqlTemplates.apply(task, overrides == null ? Map.of() : overrides);
        return Map.of("params", rendered.params(), "sql", rendered.sqls());
    }

    /** 停止任务：删除 k8s Job（客户端退出后 Zeta 作业随之取消）。 */
    public boolean stop(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return false;
        }
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var deleted = client.batch().v1().jobs().inNamespace(namespace)
                    .withName(jobName).delete();
            boolean ok = deleted != null && !deleted.isEmpty();
            log.info("stop sync job name={} deleted={}", jobName, ok);
            return ok;
        } catch (Exception e) {
            throw new ApiException(502, "停止任务失败: " + e.getMessage());
        }
    }

    private Job buildJob(String jobName, long taskId, String secretName) {
        List<String> args = List.of(
                "-Dfile.encoding=UTF-8",
                "-Dhazelcast.client.config=/opt/seatunnel/config/hazelcast-client.yaml",
                "-Dseatunnel.config=/opt/seatunnel/config/seatunnel.yaml",
                "-Dhazelcast.config=/opt/seatunnel/config/hazelcast.yaml",
                "-Dlog4j2.configurationFile=/opt/seatunnel/config/log4j2_client.properties",
                "-Dseatunnel.logs.path=/opt/seatunnel/logs",
                "-cp", "/opt/seatunnel/lib/*:/opt/seatunnel/starter/seatunnel-starter.jar",
                "org.apache.seatunnel.core.starter.seatunnel.SeaTunnelClient",
                "-c", "/opt/seatunnel/jobs/job.conf",
                "-m", "cluster");

        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(namespace)
                .addToLabels("app", "seatunnel-sync")
                .addToLabels("sync-task-id", String.valueOf(taskId))
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withTtlSecondsAfterFinished(1800)
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", "seatunnel-sync")
                .addToLabels("sync-task-id", String.valueOf(taskId))
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("client")
                .withImage(image)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/opt/java/bin/java")
                .withArgs(args)
                .addNewEnv().withName("JAVA_HOME").withValue("/opt/java").endEnv()
                .addNewEnv().withName("SEATUNNEL_HOME").withValue("/opt/seatunnel").endEnv()
                .withNewResources()
                .addToRequests("cpu", new io.fabric8.kubernetes.api.model.Quantity("200m"))
                .addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity("512Mi"))
                .addToLimits("cpu", new io.fabric8.kubernetes.api.model.Quantity("1"))
                .addToLimits("memory", new io.fabric8.kubernetes.api.model.Quantity("1Gi"))
                .endResources()
                // 发行包整体来自节点 hostPath（2.3.13），覆盖镜像内 2.3.9 的 /opt/seatunnel
                .addNewVolumeMount().withName("seatunnel-dist").withMountPath("/opt/seatunnel").endVolumeMount()
                .addNewVolumeMount().withName("config").withMountPath("/opt/seatunnel/config").endVolumeMount()
                .addNewVolumeMount().withName("jobs").withMountPath("/opt/seatunnel/jobs").endVolumeMount()
                .addNewVolumeMount().withName("logs").withMountPath("/opt/seatunnel/logs").endVolumeMount()
                .addNewVolumeMount().withName("connectors").withMountPath("/opt/seatunnel/connectors").endVolumeMount()
                // SeaTunnel 只把 plugins/jdbc/lib 里的驱动加入公共 classpath；
                // 镜像内只有 mysql/postgres，其余方言驱动从节点目录挂进来
                .addNewVolumeMount().withName("jdbc-lib").withMountPath("/opt/seatunnel/plugins/jdbc/lib").endVolumeMount()
                // 本地文件采集：与 backend 共享同一份 NFS 卷，平台侧才能登记/归档文件
                .addNewVolumeMount().withName("local-shared").withMountPath("/data/local").endVolumeMount()
                .endContainer()
                .addNewVolume().withName("seatunnel-dist")
                .withNewHostPath().withPath("/tmp/seatunnel-2.3.13").withType("Directory")
                .endHostPath().endVolume()
                .addNewVolume().withName("config")
                .withNewConfigMap().withName(configMap).endConfigMap().endVolume()
                .addNewVolume().withName("jobs")
                .withNewSecret().withSecretName(secretName).endSecret().endVolume()
                .addNewVolume().withName("logs").withNewEmptyDir().endEmptyDir().endVolume()
                // 连接器目录已同步到三个节点，客户端 Job 可调度到任意节点
                .addNewVolume().withName("connectors")
                .withNewHostPath().withPath("/tmp/seatunnel-connectors").withType("Directory")
                .endHostPath().endVolume()
                .addNewVolume().withName("jdbc-lib")
                .withNewHostPath().withPath("/tmp/seatunnel-connectors/jdbc-lib").withType("Directory")
                .endHostPath().endVolume()
                .addNewVolume().withName("local-shared")
                .withNewPersistentVolumeClaim().withClaimName("local-shared")
                .endPersistentVolumeClaim().endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /** 校验结论：最终状态、校验结果（PASSED/FAILED/SKIPPED）、失败原因。 */
    public record VerifyOutcome(String status, String verifyResult, String reason) {
    }

    /**
     * Job succeeded 不等于数据正确：ROWS 模式下源读取行数必须等于汇写入行数。
     * 指标缺失（日志被截断/版本变化）时不误判，记 SKIPPED。
     */
    public static VerifyOutcome verify(String mode, String status, Long read, Long written) {
        if (!"SUCCESS".equals(status)) {
            return new VerifyOutcome(status, null, null);
        }
        if (!"ROWS".equals(mode)) {
            return new VerifyOutcome(status, "SKIPPED", null);   // NONE / CHECKSUM 未实现
        }
        if (read == null || written == null) {
            return new VerifyOutcome(status, "SKIPPED", null);
        }
        if (read.equals(written)) {
            return new VerifyOutcome(status, "PASSED", null);
        }
        return new VerifyOutcome("FAILED", "FAILED",
                "校验不通过：读取 " + read + " 行，写入 " + written + " 行");
    }

    /** 任务级校验模式：sourceConfig.verify = NONE / ROWS / CHECKSUM，默认 ROWS。 */
    static String verifyMode(Map<String, Object> task) {
        Object raw = task.get("sourceConfig");
        if (raw instanceof Map<?, ?> cfg) {
            Object v = cfg.get("verify");
            if (v != null) {
                String s = String.valueOf(v).trim().toUpperCase();
                if (Set.of("NONE", "ROWS", "CHECKSUM").contains(s)) {
                    return s;
                }
            }
        }
        return "ROWS";
    }

    public Map<String, Object> status(String jobName) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (jobName == null || jobName.isBlank()) {
            out.put("status", "NEVER_RUN");
            return out;
        }
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Job job = client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get();
            if (job == null) {
                out.put("status", "GONE");
                out.put("jobName", jobName);
                return out;
            }
            var st = job.getStatus();
            int succeeded = st == null || st.getSucceeded() == null ? 0 : st.getSucceeded();
            int failed = st == null || st.getFailed() == null ? 0 : st.getFailed();
            int active = st == null || st.getActive() == null ? 0 : st.getActive();
            String status = failed > 0 ? "FAILED" : succeeded > 0 ? "SUCCESS" : active > 0 ? "RUNNING" : "PENDING";
            String log = null;
            // SeaTunnel client 在服务端作业失败时仍可能以 0 退出，k8s Job 显示 Complete。
            // 不能只看 Job 状态：必须检查客户端日志里的致命错误。
            if ("SUCCESS".equals(status)) {
                log = safeLog(jobName);
                Map<String, Long> metrics = parseMetrics(log);
                if (metrics.get("rowsRead") != null) {
                    out.put("rowsRead", metrics.get("rowsRead"));
                }
                if (metrics.get("rowsWritten") != null) {
                    out.put("rowsWritten", metrics.get("rowsWritten"));
                }
                if (log.contains("Fatal Error")
                        || log.contains("Exception in thread \"main\"")
                        || log.contains("CommandExecuteException")
                        || log.contains("job executed failed")) {
                    status = "FAILED";
                    out.put("failureReason", sanitizeFailure(
                            "客户端日志包含致命错误：k8s Job 退出码为 0，但 SeaTunnel 服务端作业失败"));
                }
            }
            // k8s Job 自身失败（failed>0）以前只返回 status=FAILED，没有原因，前端
            // 只能显示一个空白的「失败」。优先取 Job condition，再退到日志最后一条错误行。
            if ("FAILED".equals(status) && !out.containsKey("failureReason")) {
                if (log == null) {
                    log = safeLog(jobName);
                }
                String reason = jobFailureReason(job);
                String tail = lastErrorLine(log);
                if (tail != null) {
                    reason = reason == null ? tail : reason + "：" + tail;
                }
                out.put("failureReason", sanitizeFailure(reason == null
                        ? "k8s Job 执行失败（failed=" + failed + "）" : reason));
            }
            out.put("status", status);
            out.put("jobName", jobName);
            out.put("succeeded", succeeded);
            out.put("failed", failed);
            out.put("active", active);
            return out;
        } catch (Exception e) {
            throw new ApiException(502, "查询作业状态失败: " + e.getMessage());
        }
    }

    /**
     * 兜底清理：每分钟删掉 1 小时前的作业 Secret。
     * 补数的作业不会被前端轮询，光靠状态回调清理会漏。
     */
    public int cleanupStaleSecrets() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var secrets = client.secrets().inNamespace(namespace)
                    .withLabel("app", "seatunnel-sync").list().getItems();
            java.time.Instant cutoff = java.time.Instant.now().minusSeconds(3600);
            int deleted = 0;
            for (var secret : secrets) {
                String jobName = secret.getMetadata().getLabels().get("job-name");
                if (jobName == null) {
                    continue;
                }
                String raw = secret.getMetadata().getCreationTimestamp();
                if (raw == null) {
                    continue;
                }
                if (java.time.Instant.parse(raw).isBefore(cutoff)) {
                    client.secrets().inNamespace(namespace)
                            .withName(secret.getMetadata().getName()).delete();
                    deleted++;
                }
            }
            if (deleted > 0) {
                log.info("cleaned {} stale sync secrets", deleted);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("cleanup stale secrets failed: {}", e.getMessage());
            return 0;
        }
    }

    /** 作业结束后清理它专用的 Secret（每个作业一份 conf，不清理会越积越多）。 */
    public void cleanupSecret(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return;
        }
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var secrets = client.secrets().inNamespace(namespace)
                    .withLabel("job-name", jobName).list().getItems();
            int deleted = 0;
            for (var secret : secrets) {
                client.secrets().inNamespace(namespace)
                        .withName(secret.getMetadata().getName()).delete();
                deleted++;
            }
            if (deleted > 0) {
                log.info("cleaned secrets for job {}", jobName);
            }
        } catch (Exception e) {
            log.warn("cleanup secret failed job={}: {}", jobName, e.getMessage());
        }
    }

    /** 对外返回的日志：脱敏后再给前端。 */
    public String logs(String jobName) {
        return redact(rawLogs(jobName));
    }

    /** 从作业日志里取最新一次汇总指标（实时采样用）。 */
    public Map<String, Long> logMetrics(String jobName) {
        try {
            return parseMetrics(rawLogs(jobName));
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 掩掉日志里的密码/密钥，避免作业 conf 被原样打印后泄露。 */
    static String redact(String log) {
        if (log == null) {
            return null;
        }
        String out = log
                .replaceAll("(?i)(\"password\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)(\"secret_key\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)(\"access_key\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)(\"secretKey\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)(\"accessKey\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)((?:password|pwd)=)[^&\\s\"]+", "$1***");
        return out;
    }

    /** 解析 SeaTunnel 客户端结束时的汇总指标。 */
    static Map<String, Long> parseMetrics(String log) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (log == null) {
            return out;
        }
        out.put("rowsRead", firstNumber(log, "Total Read Count"));
        out.put("rowsWritten", firstNumber(log, "Total Write Count"));
        out.put("durationSeconds", firstNumber(log, "Total Time\\(s\\)"));
        return out;
    }

    /**
     * SeaTunnel 每 60 秒打印一次统计。必须取最后一次：取第一次会让长时间/流式
     * 任务的行数和最终校验永远停在启动时的值。
     */
    private static Long firstNumber(String log, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(label + "\\s*:\\s*(\\d+)").matcher(log);
        Long last = null;
        while (m.find()) {
            try {
                last = Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
                // 超长数字按未解析处理
            }
        }
        return last;
    }

    private String safeLog(String jobName) {
        try {
            return rawLogs(jobName);
        } catch (Exception e) {
            log.warn("read job log failed {}: {}", jobName, e.getMessage());
            return "";
        }
    }

    private String jobFailureReason(Job job) {
        if (job == null || job.getStatus() == null || job.getStatus().getConditions() == null) {
            return null;
        }
        for (var condition : job.getStatus().getConditions()) {
            if ("Failed".equalsIgnoreCase(condition.getType())
                    && condition.getMessage() != null
                    && !condition.getMessage().isBlank()) {
                return condition.getMessage().trim();
            }
        }
        return null;
    }

    /** 失败原因只保留摘要：去堆栈帧、去绝对路径，最多 1000 字符。 */
    private String sanitizeFailure(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String out = value
                .replaceAll("\\s+at\\s+[\\w.$]+\\([^)]*\\)", " ")
                .replaceAll("(?i)(?:[A-Za-z]:\\\\|/)(?:[\\w.-]+[\\\\/])*[\\w.-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return out.length() > 1000 ? out.substring(0, 1000) : out;
    }

    /**
     * DL-F2（DL-1 发现 2）：从日志底部向上优先找「真因行」——异常链的
     * {@code Caused by:}、PSQLException、{@code does not exist} 这类带语义的行，
     * 排除以 {@code at } 开头的栈帧。旧实现单趟小写包含匹配，会被栈帧
     * {@code at ...QueryExecutorImpl.receiveErrorResponse(...)}（含子串 error）抢先命中，
     * 界面失败原因只剩栈帧看不到「表不存在」。
     * 找不到真因行时回退原行为（error/exception/caused by/failed 任一命中即返回），
     * 状态词本身不改。包内可见以便单测直接调用。
     */
    static String lastErrorLine(String log) {
        if (log == null || log.isBlank()) {
            return null;
        }
        String[] lines = log.split("\\R");
        // 第一趟：真因行优先；底部向上取最近一条，异常链里即最深的 Caused by（根因）。
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("at ")
                    && (lower.contains("caused by") || lower.contains("psqlexception")
                        || lower.contains("does not exist"))) {
                return line.length() > 300 ? line.substring(0, 300) : line;
            }
        }
        // 回退：保持原有收敛行为。
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("fatal error") || lower.contains("exception")
                    || lower.contains("caused by") || lower.contains("error")
                    || lower.contains("failed")) {
                return line.length() > 300 ? line.substring(0, 300) : line;
            }
        }
        return null;
    }

    private String rawLogs(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return "任务尚未运行";
        }
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            var pods = client.pods().inNamespace(namespace)
                    .withLabel("job-name", jobName).list().getItems();
            if (pods.isEmpty()) {
                return "作业 Pod 不存在（可能已被 TTL 清理）";
            }
            String log = client.pods().inNamespace(namespace)
                    .withName(pods.get(0).getMetadata().getName()).getLog();
            if (log != null && log.length() > 200_000) {
                log = log.substring(log.length() - 200_000);
            }
            return log == null ? "" : log;
        } catch (Exception e) {
            throw new ApiException(502, "读取作业日志失败: " + e.getMessage());
        }
    }
}
