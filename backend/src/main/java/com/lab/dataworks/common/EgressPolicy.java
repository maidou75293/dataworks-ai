package com.lab.dataworks.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 出网白名单的注入点（v448）。
 *
 * <p>{@link JdbcUrlGuard} 是静态工具（连接池/Sync 任务在非 Spring 上下文里也会调），
 * 所以用这个很薄的 Bean 在启动时把 {@code platform.egress.allow} 写进去。
 * 换环境只改配置，不用改代码。</p>
 */
@Component
public class EgressPolicy {

    /**
     * REQ-UNIFY-1：桥接网关服务端调用 ModelOneStation（契约 §1 地址基准=NodePort
     * <mos 宿主地址>:30095）。mos 目标地址与出网白名单**同源**：直接取
     * {@code platform.mos.base-url} 的 host[:port] 追加，避免「代理指哪」和
     * 「放行哪」两处配置漂移（merge-plan §2.4-4 出网白名单先行）。
     */
    public EgressPolicy(@Value("${platform.egress.allow:}") String allow,
                        @Value("${platform.mos.base-url:http://127.0.0.1:30095}") String mosBaseUrl) {
        // 追加而非替换：内置白名单保证平台自己的基础设施不被配置遗漏挡掉
        JdbcUrlGuard.addAllowlist(allow);
        String mosHost = hostPortOf(mosBaseUrl);
        if (mosHost != null) {
            JdbcUrlGuard.addAllowlist(mosHost);
        }
    }

    /** 取 URL 的 host[:port]；配得不像 URL 就返回 null（不炸启动，出网仍按其余白名单）。 */
    static String hostPortOf(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
