package com.lab.dataworks.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台元数据库闸门的注入点（S1）。
 *
 * <p>{@link PlatformInternalDb} 与 {@link JdbcUrlGuard} 一样是静态工具（数据源保存、
 * 同步任务、Prefect 渲染都可能在没有 Spring 上下文的地方调用），所以用这个薄 Bean
 * 在启动时把「平台自己连的是哪个库」写进去。换环境只改配置，不改代码。</p>
 */
@Component
public class PlatformInternalPolicy {

    public PlatformInternalPolicy(@Value("${spring.datasource.url:}") String metadataUrl,
                                  @Value("${platform.internal-db.endpoints:}") String endpoints,
                                  @Value("${platform.internal-db.database-names:}") String databaseNames) {
        PlatformInternalDb.configure(metadataUrl, endpoints, databaseNames);
    }
}
