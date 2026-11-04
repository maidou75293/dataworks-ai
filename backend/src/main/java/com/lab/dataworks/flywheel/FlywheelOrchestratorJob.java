package com.lab.dataworks.flywheel;

import com.lab.dataworks.mosbridge.MosFlywheelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 飞轮④编排定时圈（REQ-FW-4）：FW-2 轮询 → 编排推进。
 *
 * <p>badcase 采集/封批仍由 {@link FlywheelJob}（FW-3，5 分钟）负责——本类不双跑它，
 * 只保证「观察面」及时：训练/评测状态定期刷、闸门与步进即时衔接
 * （FW-2 的 {@code dueWatches} 按 next_poll_at 到期取行，双调度点不会放大出站频率，
 * 契约 §5「轮询 ≥30s」仍由 MosFlywheelService 构造器兜底）。
 * 异常不外抛（调度线程保护，FW-2/FW-3 同口径）。</p>
 */
@Component
public class FlywheelOrchestratorJob {

    private static final Logger log = LoggerFactory.getLogger(FlywheelOrchestratorJob.class);

    private final FlywheelOrchestrator orchestrator;
    private final MosFlywheelService flywheel;

    public FlywheelOrchestratorJob(FlywheelOrchestrator orchestrator, MosFlywheelService flywheel) {
        this.orchestrator = orchestrator;
        this.flywheel = flywheel;
    }

    @Scheduled(fixedDelayString = "${flywheel.orch.tick-ms:30000}", initialDelay = 90_000)
    public void tick() {
        try {
            flywheel.pollOnce();
        } catch (Exception e) {
            log.error("flywheel 编排圈轮询失败（下一圈自动重试）", e);
        }
        try {
            orchestrator.advanceOpenRuns();
        } catch (Exception e) {
            log.error("flywheel 编排推进失败（下一圈自动重试）", e);
        }
    }
}
