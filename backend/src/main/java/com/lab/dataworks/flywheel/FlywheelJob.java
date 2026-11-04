package com.lab.dataworks.flywheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 飞轮③定时圈：采集（{@link BadcaseCollectorService}）→ 封批回环（{@link RelabelTriggerService}）。
 *
 * <p>节奏 {@code flywheel.badcase-tick-ms}（默认 5 分钟）；badcase 旁路任务，
 * 单段失败只记日志、下一圈自愈，不能把异常抛进调度线程打断后续任务
 * （同 {@code UsageMeteringService} 的「只读旁路不放大故障」口径）。</p>
 */
@Component
public class FlywheelJob {

    private static final Logger log = LoggerFactory.getLogger(FlywheelJob.class);

    private final BadcaseCollectorService collector;
    private final RelabelTriggerService trigger;

    public FlywheelJob(BadcaseCollectorService collector, RelabelTriggerService trigger) {
        this.collector = collector;
        this.trigger = trigger;
    }

    @Scheduled(fixedDelayString = "${flywheel.badcase-tick-ms:300000}", initialDelay = 240_000)
    public void tick() {
        try {
            collector.collectOnce();
        } catch (Exception e) {
            log.error("badcase 采集圈失败（下一圈自动重试）", e);
        }
        try {
            trigger.triggerOnce();
        } catch (Exception e) {
            log.error("badcase 回环圈失败（下一圈自动重试）", e);
        }
    }
}
