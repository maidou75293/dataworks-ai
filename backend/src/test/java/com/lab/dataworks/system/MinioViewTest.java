package com.lab.dataworks.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinIO 两口径判定的红绿锁（BUG-MON-POD-FALSEPOS-1）：
 * 服务健康只看 Pod 实况，指标抓取失败只单显「指标采集故障」，不许翻转状态。
 * 本测试的失败场景就是项目负责人 11-03 报①的现场：三节点全 Ready、minio 端点 403。
 */
class MinioViewTest {

    @Test
    void metricsDownButPodsReadyIsNotDegraded() {
        // up 序列还在（count=1）但值为 0（403 抓取失败），3/3 Pod Ready
        ClusterOverviewService.MinioView v = ClusterOverviewService.minioView(
                true, 3, 3, 0, 0, 0, 1);
        assertTrue(v.healthy(), "Pod 全就绪时服务判定必须是健康——指标瞎了不是服务坏了");
        assertEquals("健康", v.status());
        assertFalse(v.metricsOk(), "抓取失败必须单显指标口径为坏");
        assertTrue(v.capacity().contains("指标未采集"), "容量文案要说明未采集，而不是假装 0 容量");
    }

    @Test
    void podsNotReadyIsDegradedEvenWithMetrics() {
        ClusterOverviewService.MinioView v = ClusterOverviewService.minioView(
                true, 3, 2, 8e9, 12e9, 1, 1);
        assertFalse(v.healthy());
        assertEquals("降级", v.status());
        assertTrue(v.metricsOk());
    }

    @Test
    void allMetricsUpWithCapacityShown() {
        ClusterOverviewService.MinioView v = ClusterOverviewService.minioView(
                true, 3, 3, 4e9, 16e9, 3, 3);
        assertTrue(v.healthy());
        assertTrue(v.metricsOk());
        assertEquals("健康", v.status());
        assertTrue(v.capacity().startsWith("集群容量"), v.capacity());
    }

    @Test
    void missingDeploymentNotReportedAsDown() {
        ClusterOverviewService.MinioView v = ClusterOverviewService.minioView(
                false, 0, 0, 0, 0, 0, 0);
        assertFalse(v.healthy());
        assertEquals("未部署", v.status());
        assertEquals("未部署 / Pod 已消失", v.capacity());
    }
}
