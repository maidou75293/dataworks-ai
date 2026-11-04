package com.lab.dataworks.workflow;

import com.lab.dataworks.common.PlatformInternalDb;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引擎与脚本语法的交叉校验。
 *
 * <p>引擎在脚本创建后只读，但脚本正文随时可改，很容易出现「Doris 节点里贴了一段
 * Flink 建表语法」。如果不在保存/试跑/发布前拦住，用户只会拿到引擎一句
 * {@code Error 1064}，不知道该改引擎还是改 SQL。这里按引擎分流，把冲突翻译成
 * 可行动的提示：说清是哪个引擎、命中了哪段专有语法、下一步该新建哪种脚本。</p>
 *
 * <p>只识别**该引擎一定不支持**的强特征，避免误伤普通查询：Flink 的连接器
 * {@code WITH ('connector'=...)}、水位线、窗口 TVF；Doris 的
 * {@code DISTRIBUTED BY} / {@code replication_num} / 数据模型关键字。</p>
 */
public final class ScriptEngineRules {

    /** Flink 连接器建表：WITH ('connector' = ...)，容忍大小写、换行与空格。 */
    private static final Pattern FLINK_CONNECTOR = Pattern.compile(
            "(?is)\\bwith\\s*\\([^)]*'\\s*connector\\s*'\\s*=");
    private static final Pattern FLINK_WATERMARK = Pattern.compile(
            "(?i)\\bwatermark\\s+for\\b");
    private static final Pattern FLINK_TIME_ATTRIBUTE = Pattern.compile(
            "(?i)\\b(proctime|eventtime)\\s*\\(\\s*\\)");
    private static final Pattern FLINK_WINDOW_TVF = Pattern.compile(
            "(?i)\\b(tumble|hop|cumulate)\\s*\\(");

    /** Doris 专有：分桶、副本参数、数据模型关键字（Flink / Spark 都不支持）。 */
    private static final Pattern DORIS_DISTRIBUTED = Pattern.compile(
            "(?i)\\bdistributed\\s+by\\b");
    private static final Pattern DORIS_REPLICATION = Pattern.compile(
            "(?i)\\breplication_num\\b");
    private static final Pattern DORIS_KEY_MODEL = Pattern.compile(
            "(?im)^\\s*(duplicate|unique|aggregate)\\s+key\\s*\\(");

    private ScriptEngineRules() {
    }

    /**
     * 返回可行动的错误说明；{@code null} 表示引擎与脚本没有明显冲突。
     * 只做静态文本判断，不连接任何引擎。
     */
    public static String crossEngineSyntax(ScriptEngine engine, String sql) {
        if (engine == null || sql == null || sql.isBlank()) {
            return null;
        }
        String flinkHint = flinkHint(sql);
        String dorisHint = dorisHint(sql);

        if (engine == ScriptEngine.FLINK && dorisHint != null) {
            return "任务是 FLINK 引擎，但脚本用了 Doris 专有语法（" + dorisHint
                    + "）。Flink 建表请用 CREATE TABLE ... WITH ('connector'=...)，"
                    + "需要写 Doris 表请新建一个 DORIS 引擎的脚本（引擎创建后不可修改）。";
        }
        if (engine != ScriptEngine.FLINK && flinkHint != null) {
            String fallback = engine == ScriptEngine.SPARK
                    ? "Spark 建表请用 USING / CTAS 语法。"
                    : "Doris 建表请用 CREATE TABLE ... DISTRIBUTED BY / PROPERTIES 语法。";
            return "任务是 " + engine.name() + " 引擎，但脚本用了 Flink 专有语法（"
                    + flinkHint + "）。流式/连接器作业请新建一个 FLINK 引擎的脚本"
                    + "（引擎创建后不可修改）；" + fallback;
        }
        if (engine != ScriptEngine.DORIS && dorisHint != null) {
            String action = engine == ScriptEngine.SPARK
                    ? "Spark 建表请用 USING / CTAS 语法，或在 Doris 里建表后再查。"
                    : "请改成 Flink SQL 语法（CREATE TABLE ... WITH ('connector'=...)）。";
            return "任务是 " + engine.name() + " 引擎，但脚本用了 Doris 专有语法（"
                    + dorisHint + "）。" + action
                    + "需要 Doris 表请新建一个 DORIS 引擎的脚本（引擎创建后不可修改）。";
        }
        return null;
    }

    /**
     * 数据源能否绑定到该引擎：{@code null} 表示可绑定，否则返回可行动的拒绝原因。
     *
     * <p>与前端下拉过滤同一口径（DORIS→可执行 SQL 的源；SPARK→只允许 ds_type=SPARK；
     * FLINK→不允许绑定）。放在后端再做一次，是因为模型可以被接口直接写入，
     * 不能只靠界面把不合适的数据源藏起来。</p>
     *
     * @param jdbcUrl 绑定数据源的 JDBC 地址；指向平台元数据库时同样拒绝（S1）
     */
    public static String datasourceBindingError(ScriptEngine engine, String dsType,
                                                boolean queryable, String jdbcUrl) {
        String type = dsType == null ? "" : dsType.trim().toUpperCase(Locale.ROOT);
        if (engine == ScriptEngine.FLINK) {
            return "FLINK 引擎不支持绑定数据源：Flink 走 SQL Gateway，连接器在 "
                    + "CREATE TABLE ... WITH ('connector'=...) 里声明；请解除节点绑定";
        }
        if (engine == ScriptEngine.SPARK) {
            if (!"SPARK".equals(type)) {
                return "SPARK 引擎只能绑定 SPARK 类型的数据源，当前是 "
                        + (type.isBlank() ? "未知类型" : type)
                        + "；请解除绑定后改用平台 Spark Thrift（"
                        + SparkThriftSettings.DEFAULT_URL + "）";
            }
            return internalTargetError(jdbcUrl);
        }
        // DORIS（默认引擎）：Kafka / 文件类只能入湖或导入，不能直接执行 SQL
        if (!queryable) {
            return "DORIS 引擎只能绑定可执行 SQL 的数据源，当前是 "
                    + (type.isBlank() ? "未知类型" : type)
                    + "；Kafka / 文件类请先入湖或导入，或解除绑定改用项目数仓";
        }
        return internalTargetError(jdbcUrl);
    }

    /**
     * S1：绑定关系不能指向平台内部元数据库。
     *
     * <p>数据源可以被直接写进库里（历史数据就有一个 {@code postgres-platform} 指向
     * {@code jdbc:postgresql://postgres.dw-dev.svc.cluster.local:5432/dataworks_dev}），
     * 所以除了建数据源时的校验，绑定与执行链路也要按同一口径再拦一次。</p>
     */
    public static String internalTargetError(String jdbcUrl) {
        String reason = PlatformInternalDb.urlViolation(jdbcUrl);
        return reason == null ? null : reason + "；该数据源不能作为节点执行引擎";
    }

    /** Flink 专有特征的可读描述；没命中返回 null。 */
    public static String flinkHint(String sql) {
        if (first(FLINK_CONNECTOR, sql)) {
            return "CREATE TABLE ... WITH ('connector'=...)";
        }
        if (first(FLINK_WATERMARK, sql)) {
            return "WATERMARK FOR";
        }
        if (first(FLINK_TIME_ATTRIBUTE, sql)) {
            return "PROCTIME() / EVENTTIME()";
        }
        if (first(FLINK_WINDOW_TVF, sql)) {
            return "TUMBLE / HOP / CUMULATE 窗口表函数";
        }
        return null;
    }

    /** Doris 专有特征的可读描述；没命中返回 null。 */
    public static String dorisHint(String sql) {
        if (first(DORIS_DISTRIBUTED, sql)) {
            return "DISTRIBUTED BY";
        }
        if (first(DORIS_REPLICATION, sql)) {
            return "replication_num";
        }
        if (first(DORIS_KEY_MODEL, sql)) {
            return "DUPLICATE / UNIQUE / AGGREGATE KEY";
        }
        return null;
    }

    private static boolean first(Pattern pattern, String sql) {
        Matcher matcher = pattern.matcher(sql);
        return matcher.find();
    }
}
