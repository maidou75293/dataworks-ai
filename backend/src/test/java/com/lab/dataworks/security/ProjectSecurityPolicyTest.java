package com.lab.dataworks.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v483 项目级安全策略：优先级（项目 > 租户 > 平台）、强制字段级覆盖、
 * 项目默认列等级解析。全部是纯函数测试，不依赖数据库。
 */
class ProjectSecurityPolicyTest {

    @Test
    void projectInheritFallsBackToPlatform() {
        // 项目 INHERIT + 无租户配置 → 平台
        assertEquals(DataGuardService.PERMISSION_TABLE,
                DataGuardService.resolveEffectiveMode("INHERIT", null, null, "TABLE"));
        assertEquals(DataGuardService.PERMISSION_COLUMN,
                DataGuardService.resolveEffectiveMode(null, null, null, "COLUMN"));
        // 非法项目值按 INHERIT 处理（宁可跟随平台，也不静默放行）
        assertEquals(DataGuardService.PERMISSION_OFF,
                DataGuardService.resolveEffectiveMode("BOGUS", null, null, "OFF"));
        assertEquals(DataGuardService.SOURCE_PLATFORM,
                DataGuardService.resolveSource("INHERIT", null, null));
    }

    @Test
    void projectOverridesTenantAndPlatform() {
        assertEquals(DataGuardService.PERMISSION_OFF,
                DataGuardService.resolveEffectiveMode("OFF", null, "TABLE", "TABLE"));
        assertEquals(DataGuardService.PERMISSION_TABLE,
                DataGuardService.resolveEffectiveMode("TABLE", null, "OFF", "COLUMN"));
        assertEquals(DataGuardService.PERMISSION_COLUMN,
                DataGuardService.resolveEffectiveMode("COLUMN", null, null, "TABLE"));
        assertEquals(DataGuardService.SOURCE_PROJECT,
                DataGuardService.resolveSource("TABLE", null, "COLUMN"));
    }

    @Test
    void tenantSitsBetweenProjectAndPlatform() {
        // 项目 INHERIT + 租户 TABLE → 取租户，不再看平台 COLUMN
        assertEquals(DataGuardService.PERMISSION_TABLE,
                DataGuardService.resolveEffectiveMode("INHERIT", null, "TABLE", "COLUMN"));
        assertEquals(DataGuardService.SOURCE_TENANT,
                DataGuardService.resolveSource("INHERIT", null, "TABLE"));
        // 租户值非法 → 继续回退平台
        assertEquals(DataGuardService.PERMISSION_COLUMN,
                DataGuardService.resolveEffectiveMode("INHERIT", null, "BAD", "COLUMN"));
    }

    @Test
    void columnControlRequiredOverridesProjectMode() {
        // 强制字段级优先级最高：项目 OFF/TABLE/INHERIT 都变成 COLUMN
        assertEquals(DataGuardService.PERMISSION_COLUMN,
                DataGuardService.resolveEffectiveMode("OFF", true, "OFF", "OFF"));
        assertEquals(DataGuardService.PERMISSION_COLUMN,
                DataGuardService.resolveEffectiveMode("TABLE", true, null, "TABLE"));
        assertEquals(DataGuardService.PERMISSION_COLUMN,
                DataGuardService.resolveEffectiveMode("INHERIT", true, null, "OFF"));
        assertEquals(DataGuardService.SOURCE_PROJECT,
                DataGuardService.resolveSource("INHERIT", true, null));
        // false 不覆盖项目粒度
        assertEquals(DataGuardService.PERMISSION_TABLE,
                DataGuardService.resolveEffectiveMode("TABLE", false, "COLUMN", "COLUMN"));
    }

    @Test
    void defaultColumnLevelComesFromProjectOrPlatform() {
        // 项目配置合法 → 用它
        assertEquals("L4", DataGuardService.resolveDefaultColumnLevel("L4"));
        // 未配置/非法 → 平台默认 L2
        assertEquals("L2", DataGuardService.resolveDefaultColumnLevel(null));
        assertEquals("L2", DataGuardService.resolveDefaultColumnLevel("  "));
        assertEquals("L2", DataGuardService.resolveDefaultColumnLevel("L9"));
        assertEquals("L3", DataGuardService.resolveDefaultColumnLevel("l3"));
        assertEquals("L2", DataGuardService.platformDefaultColumnLevel());
    }

    @Test
    void projectModeValidationIsStrictForWrites() {
        assertEquals("INHERIT", DataGuardService.requireProjectMode("inherit"));
        assertEquals("OFF", DataGuardService.requireProjectMode(" OFF "));
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.lab.dataworks.common.ApiException.class,
                () -> DataGuardService.requireProjectMode("LOG"));
        assertEquals(400, error.getStatus());
    }
}
