package com.lab.dataworks.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 监控命名空间的解析口径：默认覆盖 dw-dev + dw-spark + kube-system
 * （Spark 迁走后漏看、kube-system 入口组件卡死 34 分钟看不见，都是覆盖面缺口），
 * 配置可覆盖、去重、大小写归一，PromQL 正则与列表保持一致。
 */
class MonitorNamespacesTest {

    @Test
    void defaultsCoverDevSparkAndKubeSystem() {
        MonitorNamespaces ns = new MonitorNamespaces(null);
        assertEquals(List.of("dw-dev", "dw-spark", "kube-system"), ns.all());
        assertEquals("dw-dev|dw-spark|kube-system", ns.regex());
    }

    @Test
    void blankConfigFallsBackToDefaultSet() {
        MonitorNamespaces ns = new MonitorNamespaces("  ");
        assertEquals(List.of("dw-dev", "dw-spark", "kube-system"), ns.all());
        assertEquals("dw-dev|dw-spark|kube-system", ns.regex());
    }

    @Test
    void trimsDedupesAndLowercases() {
        MonitorNamespaces ns = new MonitorNamespaces(" dw-dev , dw-spark,DW-SPARK ,, ");
        assertEquals(List.of("dw-dev", "dw-spark"), ns.all());
        assertEquals("dw-dev|dw-spark", ns.regex());
    }
}
