package com.lab.dataworks.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V101：Catalog 命名规则必须是确定性的，否则重复保存会生成新 Catalog 而不是 ALTER。
 */
class GravitinoCatalogNamingTest {

    @Test
    void tenantAndNameAreLowercasedAndSeparated() {
        assertEquals("tenant_b_doris_kb",
                GravitinoCatalogNaming.catalogName("tenant-b", "Doris KB"));
        assertEquals("default_doris_prod",
                GravitinoCatalogNaming.catalogName(null, "Doris-Prod"));
    }

    @Test
    void digitLeadingNameGetsSafePrefix() {
        assertEquals("default_ds_9030",
                GravitinoCatalogNaming.catalogName("default", "9030"));
    }

    @Test
    void nonAsciiNameFallsBackToStableHash() {
        String first = GravitinoCatalogNaming.catalogName("tenant_b", "订单库");
        String second = GravitinoCatalogNaming.catalogName("tenant_b", "订单库");
        assertEquals(first, second, "同一个名字必须生成同一个 Catalog 名（幂等的前提）");
        assertTrue(first.matches("tenant_b_ds_[0-9a-f]{8}"), first);
        // 不同中文名不能落到同一个 Catalog：否则第二个数据源会覆盖第一个
        assertTrue(!first.equals(GravitinoCatalogNaming.catalogName("tenant_b", "用户库")));
    }

    @Test
    void longNameIsTruncatedWithHashAndStaysWithinIdentifierLimit() {
        String longName = "very_" + "long_".repeat(30) + "datasource";
        String catalog = GravitinoCatalogNaming.catalogName("default", longName);
        assertTrue(catalog.length() <= GravitinoCatalogNaming.MAX_LENGTH, catalog);
        assertTrue(catalog.matches("default_very_long.*_[0-9a-f]{8}"), catalog);
        assertEquals(catalog, GravitinoCatalogNaming.catalogName("default", longName));
    }

    @Test
    void longTenantKeepsNameSpaceAndLimit() {
        String tenant = "t".repeat(80);
        String catalog = GravitinoCatalogNaming.catalogName(tenant, "doris_kb");
        assertTrue(catalog.length() <= GravitinoCatalogNaming.MAX_LENGTH, catalog);
        assertTrue(catalog.endsWith("_doris_kb"), catalog);
    }

    @Test
    void blankNameStillProducesUsableIdentifier() {
        assertEquals("default_ds", GravitinoCatalogNaming.catalogName("default", "  "));
    }

    @Test
    void tenantPrefixMatchesGeneratedCatalogNames() {
        // 清理能力靠前缀划定命名空间：前缀必须与生成规则严格一致，否则会漏删/误删
        assertEquals("default_", GravitinoCatalogNaming.tenantPrefix("default"));
        assertTrue(GravitinoCatalogNaming.catalogName("Default", "Doris KB")
                .startsWith(GravitinoCatalogNaming.tenantPrefix("default")));
        String longTenant = "t".repeat(80);
        assertTrue(GravitinoCatalogNaming.catalogName(longTenant, "doris_kb")
                .startsWith(GravitinoCatalogNaming.tenantPrefix(longTenant)));
        // 中文租户名与前缀同样保持确定性
        assertEquals(GravitinoCatalogNaming.tenantPrefix("订单"),
                GravitinoCatalogNaming.tenantPrefix("订单"));
    }
}
