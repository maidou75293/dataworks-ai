package com.lab.dataworks.workflow;

import com.lab.dataworks.common.JdbcUrlGuard;

import java.util.List;
import java.util.Locale;

/**
 * SPARK 脚本引擎的连接配置（application.yml 的 {@code spark.thrift.*}）。
 *
 * <p>节点没绑定独立数据源时用它执行只读 SQL。URL 在**使用前**过一遍
 * {@link JdbcUrlGuard}：默认地址是集群内 Service，已在平台内置白名单里；
 * 配错地址（本机/元数据/未放行的私网）时给出明确错误，而不是连上再说。
 * 超时默认 120 秒，夹在 1–3600 秒之间，防止配成 0（永不超时）或过大。</p>
 */
public record SparkThriftSettings(String url, String username, String password,
                                  int queryTimeoutSeconds) {

    public static final String DEFAULT_URL =
            "jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/";
    public static final String DEFAULT_USERNAME = "spark";
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 归一化配置：URL 校验 + 账号兜底 + 超时夹取。 */
    public static SparkThriftSettings of(String url, String username, String password,
                                         int timeoutSeconds) {
        String effectiveUrl = url == null || url.isBlank()
                ? DEFAULT_URL : url.trim();
        // 配置写死在后端，不经过用户提交路径；仍然校验一次，防止误配内网/元数据地址
        effectiveUrl = JdbcUrlGuard.validate(effectiveUrl);
        String effectiveUser = username == null || username.isBlank()
                ? DEFAULT_USERNAME : username.trim();
        int timeout = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS
                : Math.min(timeoutSeconds, 3600);
        return new SparkThriftSettings(effectiveUrl, effectiveUser,
                password == null ? "" : password, timeout);
    }

    /** JDBC 层「连不上」的常见特征（驱动 / 网络 / 认证握手）。 */
    private static final List<String> UNAVAILABLE_MARKERS = List.of(
            "could not open client transport", "connection refused", "connect timed out",
            "connection timed out", "communications link failure", "unable to connect",
            "no route to host", "unknownhost", "connection reset", "connectexception",
            "no suitable driver", "login timed out", "failed to connect",
            "socket timeout", "i/o error");

    /**
     * 异常链里是否包含「连不上 Spark Thrift」的特征。
     *
     * <p>只用于把基础设施未就绪翻译成可行动提示；SQL 语法错误、只读守卫拒绝等
     * 业务错误不在此列，必须原样返回。</p>
     */
    public static boolean unavailable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message == null || message.isBlank()) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            for (String marker : UNAVAILABLE_MARKERS) {
                if (lower.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 「Spark Thrift 未就绪」的统一文案：地址 + 原因 + 下一步。
     *
     * <p>试跑返回 503（基础设施未就绪，可重试），发布校验把它归为 warning，
     * 不让集群抖动卡住发布闸门。</p>
     */
    public static String unavailableHint(String url, Throwable error) {
        return "Spark Thrift 未就绪：无法连接 " + url + "。原因：" + rootMessage(error)
                + "。请确认 spark-thrift 服务已启动、地址与端口可达（默认 "
                + DEFAULT_URL + "），或检查平台配置 spark.thrift.url；"
                + "需要先出结果可改用 DORIS 引擎新建脚本。";
    }

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
        if (message == null) {
            return "连接被拒绝或超时（驱动未返回原因）";
        }
        String value = message.trim().replaceAll("\\s+", " ");
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
