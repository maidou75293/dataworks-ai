package com.lab.dataworks.mosbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 轮询调度：默认 30s 一轮（契约 §5 下限），到期跟踪行由 Service 逐条取状态。
 *
 * <p>开关/间隔独立配置（platform.mos.flywheel.*），不共用网关的号——网关是同步代理、
 * 这里是后台轮询，一个挂不该连坐另一个。mos 未配置（base-url 或 svc 口令缺失）时
 * 打一条 WARN 后空转，不静默吞掉原因。</p>
 */
@Component
public class MosFlywheelScheduler {

    private static final Logger log = LoggerFactory.getLogger(MosFlywheelScheduler.class);

    private final MosFlywheelService service;
    private final MosGateway gateway;
    private final boolean enabled;

    public MosFlywheelScheduler(MosFlywheelService service, MosGateway gateway,
                                @Value("${platform.mos.flywheel.enabled:true}") boolean enabled) {
        this.service = service;
        this.gateway = gateway;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${platform.mos.flywheel.interval-ms:30000}",
            initialDelayString = "${platform.mos.flywheel.initial-delay-ms:15000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        int handled = service.pollOnce();
        if (handled < 0) {
            log.warn("mos 飞轮轮询未启动：{}", gateway.disabledReason());
        }
    }
}
