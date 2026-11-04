package com.lab.dataworks.common;

import java.util.Locale;
import java.util.Properties;

/**
 * JDBC 连接/读取超时参数。
 *
 * <p>坑：{@code connectTimeout} / {@code socketTimeout} 的单位**按驱动不同**——
 * MySQL Connector/J 是毫秒，PostgreSQL JDBC 是秒。用同一个数值（例如 5000）给
 * PG 等于 5000 秒，实际等于没有超时：实测把端口指到一个不回 RST 的地址时，
 * 连接测试挂了 **134 秒**（OS 的 TCP 超时）才返回。</p>
 *
 * <p>只对 classpath 里有驱动的两种方言设置；其它方言不塞不认识的参数，
 * 避免驱动因未知属性报错。</p>
 */
public final class JdbcTimeouts {

    private JdbcTimeouts() {
    }

    /**
     * 按方言写入连接/读取超时（秒）。MySQL 系驱动要毫秒，这里换算。
     *
     * @param connectSeconds 建立连接的上限
     * @param socketSeconds  单次读写的上限（0 或负数表示不设置）
     */
    public static void apply(Properties props, String url, int connectSeconds, int socketSeconds) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        boolean mysql = lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")
                || lower.startsWith("jdbc:tidb:");
        boolean postgres = lower.startsWith("jdbc:postgresql:");
        if (!mysql && !postgres) {
            return;
        }
        int factor = mysql ? 1000 : 1;
        props.setProperty("connectTimeout", String.valueOf(connectSeconds * factor));
        if (socketSeconds > 0) {
            props.setProperty("socketTimeout", String.valueOf(socketSeconds * factor));
        }
    }
}
