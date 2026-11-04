package com.lab.dataworks;

import com.lab.dataworks.metadata.MetadataCatalogResolver;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.metadata.MetadataSettings;
import com.lab.dataworks.secret.SecretMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量上下文装配测试：不连数据库 / Gravitino / K8s，只验证所有 Bean 能被构造出来。
 *
 * <p>2026-10-26 v498 就是因为 {@link MetadataSettings} 有多个构造器却没标
 * {@code @Autowired}，Spring 找不到默认构造器直接启动失败。单元测试覆盖不到这种装配错误，
 * 这里用完整上下文把它挡在构建阶段。
 *
 * <p>数据库替换成 Mockito mock；{@link SecretMigration} 是启动期扫库的 Runner，
 * 上下文测试里换成 mock，避免真的去连库。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 迁移需要真实 PG，上下文测试不跑
                "spring.flyway.enabled=false",
                // SecretCipher 没有密钥就拒绝启动；测试给一个固定假密钥
                "platform.secret-key=context-test-only",
                // 数据源只做 Bean 装配；即使被意外使用也不会卡住等连接
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.minimum-idle=0",
                // 定时任务不要立即扫库（默认 30s / 60s 的作业足够测试结束）
                "access.expiry-ms=3600000",
                "access.escalate-ms=3600000",
                "platform.alert-eval-ms=3600000",
                "platform.baseline-tick-ms=3600000",
                "platform.baseline-initial-ms=3600000",
                "platform.notify-eval-ms=3600000",
                "flink.reconcile-ms=3600000",
                "platform.run-metric-initial-ms=3600000"
        })
class ApplicationContextTest {

    @MockBean
    private JdbcTemplate jdbc;

    @MockBean
    private SecretMigration secretMigration;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsAndMetadataBeansWired() {
        assertThat(context.getBean(MetadataSettings.class)).isNotNull();
        assertThat(context.getBean(MetadataReader.class)).isNotNull();
        assertThat(context.getBean(MetadataCatalogResolver.class)).isNotNull();
    }
}
