package com.lab.dataworks.lineage;

import com.lab.dataworks.common.JdbcUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spark 计划血缘：对平台常驻 Spark Thrift Server 执行 {@code EXPLAIN <sql>}，
 * 从物理计划文本里提真实扫描的表，确认到的表级边由 {@code inferred} 升 {@code measured}。
 *
 * <p>与 {@link DorisPlanLineage} 同型（REQ-LINEAGE-SPARK-1 步骤 2）：Spark 的 EXPLAIN 只给计划树，
 * 不提供稳定的列级映射，所以这里<b>只做表级确认</b>，列级边继续由 jsqlparser 推断保持 inferred。</p>
 *
 * <p><b>降级口径</b>：连不上 Thrift / SQL 方言不认 / 计划里没有扫描节点 / 超时，一律返回空集合，
 * 调用方按 inferred 落库，绝不把发布拖红。时间预算 3 秒（与 {@link FlinkPlanLineage} 的连接超时同口径），
 * 发布是同步链路，不能按试跑的 120 秒等引擎。</p>
 *
 * <p>计划文本形态按本机 {@code spark-thrift}（Spark 3.x + Hive 表）实测取：
 * {@code Scan hive spark_catalog.tmp_lineage_m2.src [id#611]} /
 * {@code HiveTableRelation [`spark_catalog`.`db`.`tbl`, ...]} /
 * {@code SubqueryAlias spark_catalog.db.tbl}。解析失败的计划文本（例如
 * {@code AnalysisException: TABLE_OR_VIEW_NOT_FOUND}）不含这些标记，因此不会误确认表名。</p>
 */
@Component
public class SparkPlanLineage {

    private static final Logger log = LoggerFactory.getLogger(SparkPlanLineage.class);

    /** EXPLAIN 的时间预算（秒）：登录与查询各按它夹紧，拿不到就走 inferred。 */
    static final int EXPLAIN_BUDGET_SECONDS = 3;

    /** 计划文本最多读这么多行，畸形/超长计划不无界吃内存。 */
    private static final int MAX_PLAN_ROWS = 500;

    /** {@code Scan hive spark_catalog.db.tbl [...]} —— provider 与标识符之间必有一段。 */
    private static final Pattern SCAN_REF = Pattern.compile(
            "(?i)\\bscan\\s+[A-Za-z0-9_]+\\s+([`\"]?[A-Za-z0-9_.$\\-]+)");

    /** {@code HiveTableRelation [`cat`.`db`.`tbl`, ...]} / {@code SubqueryAlias cat.db.tbl}。 */
    private static final Pattern RELATION_REF = Pattern.compile(
            "(?i)(?:HiveTableRelation\\s*\\[\\s*|SubqueryAlias\\s+)([`\"]?[A-Za-z0-9_.$\\-]+)");

    private final String url;
    private final String username;
    private final String password;

    public SparkPlanLineage(
            @Value("${spark.thrift.url:jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/}")
            String url,
            @Value("${spark.thrift.username:spark}") String username,
            @Value("${spark.thrift.password:}") String password) {
        this.url = url == null ? "" : url.trim();
        this.username = username == null || username.isBlank() ? "spark" : username.trim();
        this.password = password == null ? "" : password;
    }

    /**
     * 计划里扫描到的表名集合（小写；同时给三段全名与裸表名，匹配 {@code markMeasured} 的叶子名口径）。
     *
     * <p>失败/超时返回空集合，不抛异常。</p>
     */
    public Set<String> tables(String sql) {
        String statement = singleStatement(sql);
        if (statement.isEmpty()) {
            return Set.of();
        }
        long start = System.currentTimeMillis();
        try {
            List<List<String>> rows = explain("EXPLAIN " + statement);
            Set<String> out = tablesFromPlan(rows);
            log.info("spark explain lineage url={} planRows={} tables={} cost={}ms",
                    url, rows.size(), out.size(), System.currentTimeMillis() - start);
            return out;
        } catch (Exception | LinkageError e) {
            // 连不上（驱动/网络/认证）、语法不支持 EXPLAIN、超时、多语句…全部按「拿不到计划」降级
            log.warn("spark explain lineage failed url={}: {}", url, reason(e));
            return Set.of();
        }
    }

    /** 从 EXPLAIN 结果文本提表名（不连引擎，可单测）。 */
    public static Set<String> tablesFromPlan(List<List<String>> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> row : rows) {
            for (String cell : row) {
                if (cell == null || cell.isBlank()) {
                    continue;
                }
                collect(out, SCAN_REF, cell);
                collect(out, RELATION_REF, cell);
            }
        }
        return out;
    }

    private static void collect(Set<String> out, Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            add(out, m.group(1));
        }
    }

    /** 与 {@code DorisPlanLineage.add} 同口径：去引号、小写，全名与裸表名都收。 */
    private static void add(Set<String> out, String raw) {
        String name = raw.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.indexOf('.') == 0 || name.endsWith(".")) {
            return;
        }
        out.add(name);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            out.add(name.substring(dot + 1));
        }
    }

    /**
     * 取可 EXPLAIN 的单条语句：去尾部分号；中间还有分号（多语句脚本）就不确认，
     * 因为 {@code EXPLAIN a; b;} 会连 b 一起交给引擎解析，语义不是「只读计划」。
     */
    private static String singleStatement(String sql) {
        if (sql == null) {
            return "";
        }
        String value = sql.trim();
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.contains(";") ? "" : value;
    }

    /** 执行 EXPLAIN 并把结果读成文本表。包成接缝：子类可覆写，单测不打网络。 */
    protected List<List<String>> explain(String explainSql) throws Exception {
        String validated = JdbcUrlGuard.validate(url);
        String driver = JdbcUrlGuard.driverFor(validated);
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        if (driver != null) {
            Class.forName(driver);
        }
        int previousLoginTimeout = DriverManager.getLoginTimeout();
        try {
            DriverManager.setLoginTimeout(EXPLAIN_BUDGET_SECONDS);
            try (Connection conn = DriverManager.getConnection(validated, props);
                 Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(EXPLAIN_BUDGET_SECONDS);
                try (ResultSet rs = stmt.executeQuery(explainSql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columns = meta.getColumnCount();
                    List<List<String>> rows = new java.util.ArrayList<>();
                    while (rs.next() && rows.size() < MAX_PLAN_ROWS) {
                        List<String> cells = new java.util.ArrayList<>(columns);
                        for (int i = 1; i <= columns; i++) {
                            cells.add(rs.getString(i));
                        }
                        rows.add(cells);
                    }
                    return rows;
                }
            }
        } finally {
            DriverManager.setLoginTimeout(previousLoginTimeout);
        }
    }

    private static String reason(Throwable error) {
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
}
