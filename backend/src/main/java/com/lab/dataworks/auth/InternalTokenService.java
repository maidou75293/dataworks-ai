package com.lab.dataworks.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内部接口凭证的统一入口（v471 G-P1-2）。
 *
 * <p>之前拦截器和 8 个内部控制器各自 `@Value` 注入环境变量，启动后不再变化；
 * Secret 轮换后各处“期望值”与 Prometheus 的新值不一致，抓取永久 401。
 * 现在全部走这里：文件优先、5s TTL 热读、环境变量兜底。</p>
 */
@Component
public class InternalTokenService {

    private final InternalTokenProvider provider;

    public InternalTokenService(@Value("${platform.internal-token:}") String envToken,
                                @Value("${platform.internal-token-file:}") String tokenFile,
                                @Value("${platform.internal-token-ttl-ms:5000}") long ttlMs) {
        this.provider = new InternalTokenProvider(tokenFile, envToken, ttlMs);
    }

    /** 是否配置了内部凭证（文件或环境变量任一非空）。 */
    public boolean configured() {
        return !provider.current().isBlank();
    }

    /** 常量时间比较。 */
    public boolean matches(String candidate) {
        return provider.matches(candidate);
    }
}
