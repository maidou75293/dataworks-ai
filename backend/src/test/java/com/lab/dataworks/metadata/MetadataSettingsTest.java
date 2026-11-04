package com.lab.dataworks.metadata;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V101：metadata.source 开关的默认值、取值校验与热更新。 */
class MetadataSettingsTest {

    private JdbcTemplate jdbc;
    private MetadataSettings settings;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // TTL=0：每次读取都走数据库，避免测试之间共享缓存
        settings = new MetadataSettings(jdbc, 0);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    @Test
    void defaultsToGravitinoWhenSettingMissing() {
        assertEquals(MetadataSettings.GRAVITINO, settings.source());
        assertTrue(settings.gravitinoEnabled());
    }

    @Test
    void readsDirectFromPlatformSetting() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("direct"));

        assertEquals(MetadataSettings.DIRECT, settings.source());
        assertFalse(settings.gravitinoEnabled());
    }

    @Test
    void invalidStoredValueFallsBackToGravitino() {
        // 手工改坏库里的值不能让同步静默关闭
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("mysql"));

        assertEquals(MetadataSettings.GRAVITINO, settings.source());
    }

    @Test
    void invalidSourceIsRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> settings.setSource("mysql", "tester"));
        assertEquals(400, e.getStatus(), e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void setSourceWritesSettingAndRefreshesCacheImmediately() {
        // 这个用例要验证「切完立刻生效」，必须让缓存真正参与：TTL 不能为 0
        MetadataSettings cached = new MetadataSettings(jdbc, 60_000);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("direct"));
        assertEquals(MetadataSettings.DIRECT, cached.source());

        cached.setSource("GRAVITINO", "tester");

        // 不等 TTL，切换后本进程下一次读取立刻生效
        assertEquals(MetadataSettings.GRAVITINO, cached.source());
        verify(jdbc).update(contains("insert into platform.setting"),
                eq(MetadataSettings.KEY), eq(MetadataSettings.GRAVITINO), eq("tester"));
    }
}
