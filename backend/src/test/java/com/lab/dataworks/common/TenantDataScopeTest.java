package com.lab.dataworks.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D01 跨租户判定口径：只有「有归属证据且不属于当前租户」才算跨租户。
 *
 * <p>没有归属证据（外部数仓 / 未纳管共享库）必须保持 fail-open，否则会误伤
 * 项目级外部 Doris 路由。</p>
 */
class TenantDataScopeTest {

    @Test
    void ownerIsAnotherTenantIsCrossTenant() {
        assertTrue(TenantDataScope.crossTenant(Set.of("default"), "tenant_b"));
        assertTrue(TenantDataScope.crossTenant(Set.of("tenant_a", "tenant_c"), "tenant_b"));
    }

    @Test
    void ownerIncludesCurrentTenantIsNotCrossTenant() {
        assertFalse(TenantDataScope.crossTenant(Set.of("tenant_b"), "tenant_b"));
        // 大小写不敏感
        assertFalse(TenantDataScope.crossTenant(Set.of("Tenant_B"), "tenant_b"));
        // 多归属里包含当前租户也不算跨租户
        assertFalse(TenantDataScope.crossTenant(Set.of("tenant_a", "tenant_b"), "tenant_b"));
    }

    @Test
    void unknownOwnerFailsOpen() {
        assertFalse(TenantDataScope.crossTenant(Set.of(), "tenant_b"));
        assertFalse(TenantDataScope.crossTenant(null, "tenant_b"));
    }

    @Test
    void blankTenantFallsBackToDefault() {
        assertTrue(TenantDataScope.crossTenant(Set.of("tenant_b"), null));
        assertFalse(TenantDataScope.crossTenant(Set.of("default"), ""));
    }
}
