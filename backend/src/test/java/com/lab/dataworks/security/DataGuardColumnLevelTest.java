package com.lab.dataworks.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列级分级（4a）：等级推导、L1/L2 明文、L3/L4 默认规则、TABLE/COLUMN 授权范围。
 */
class DataGuardColumnLevelTest {

    private static Map<String, Object> column(String level) {
        return Map.of("dataLevel", level);
    }

    @Test
    void derivesTableLevelFromMaxColumnLevel() {
        assertEquals("L3", DataGuardService.deriveLevel(
                List.of(column("L2"), column("L3")), "L1"));
        assertEquals("L4", DataGuardService.deriveLevel(
                List.of(column("L1"), column("L4"), column("L3")), "L2"));
        // 没有列时回落到 stored 值
        assertEquals("L2", DataGuardService.deriveLevel(List.of(), "L2"));
        // stored 也缺失时按 L3（宁严勿松）
        assertEquals("L3", DataGuardService.deriveLevel(List.of(), null));
        // 非法等级忽略，不参与推导
        assertEquals("L3", DataGuardService.deriveLevel(
                List.of(column("L9"), column("L3")), "L1"));
    }

    @Test
    void l1AndL2AreAlwaysPlaintext() {
        assertNull(DataGuardService.effectiveMask("L1", "MASK"));
        assertNull(DataGuardService.effectiveMask("L1", "TOKEN"));
        assertNull(DataGuardService.effectiveMask("L2", "MASK"));
        assertNull(DataGuardService.effectiveMask("L2", "NULL"));
    }

    @Test
    void l3AndL4UseRuleOrDefaults() {
        // 无规则：L3 默认 TOKEN、L4 默认 MASK
        assertEquals("TOKEN", DataGuardService.effectiveMask("L3", ""));
        assertEquals("TOKEN", DataGuardService.effectiveMask("L3", "NONE"));
        assertEquals("MASK", DataGuardService.effectiveMask("L4", ""));
        assertEquals("MASK", DataGuardService.effectiveMask("L4", "NONE"));
        // 显式规则优先
        assertEquals("MASK", DataGuardService.effectiveMask("L3", "MASK"));
        assertEquals("TOKEN", DataGuardService.effectiveMask("L4", "TOKEN"));
        assertEquals("PARTIAL", DataGuardService.effectiveMask("L3", "PARTIAL"));
        // 配置者显式选择 PLAIN 时不再套默认规则
        assertNull(DataGuardService.effectiveMask("L3", "PLAIN"));
        assertNull(DataGuardService.effectiveMask("L4", "PLAIN"));
    }

    @Test
    void tableGrantCoversEveryColumn() {
        Map<String, String> grant = Map.of(
                "resource_type", "TABLE",
                "schema_name", "demo",
                "table_name", "orders");
        assertTrue(DataGuardService.permits(grant, "demo", "orders", "amount"));
        assertTrue(DataGuardService.permits(grant, "demo", "orders", "customer"));
        assertFalse(DataGuardService.permits(grant, "demo", "orders2", "amount"));
        assertFalse(DataGuardService.permits(grant, "other", "orders", "amount"));
    }

    @Test
    void columnGrantOnlyCoversMatchingColumn() {
        Map<String, String> grant = Map.of(
                "resource_type", "COLUMN",
                "schema_name", "demo",
                "table_name", "orders",
                "column_name", "customer");
        assertTrue(DataGuardService.permits(grant, "demo", "orders", "customer"));
        assertTrue(DataGuardService.permits(grant, "demo", "orders", "CUSTOMER"));
        assertFalse(DataGuardService.permits(grant, "demo", "orders", "amount"));
        assertFalse(DataGuardService.permits(grant, "demo", "orders2", "customer"));
    }

    @Test
    void databaseGrantDoesNotUnmaskColumns() {
        // 列明文只认 TABLE / COLUMN 授权；库级授权不自动解锁库内所有列
        Map<String, String> grant = Map.of(
                "resource_type", "DATABASE",
                "schema_name", "demo",
                "table_name", "orders");
        assertFalse(DataGuardService.permits(grant, "demo", "orders", "amount"));
    }
}
