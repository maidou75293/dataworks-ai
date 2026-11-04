package com.lab.dataworks.tablemanage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Doris schema change 异步作业轮询。
 *
 * <p>TableDdlService 提交 DDL 后若看到 PENDING/WAITING_TXN/RUNNING，会把工单留在
 * RUNNING；这里每 5 秒查一次 SHOW ALTER TABLE COLUMN，完成/失败后收尾。
 * 轻量变更没有作业记录时 executeJob 已经直接 FINISHED，不会进入这里。</p>
 */
@Component
public class TableDdlJobPoller {

    private static final Logger log = LoggerFactory.getLogger(TableDdlJobPoller.class);

    private final TableDdlService service;

    public TableDdlJobPoller(TableDdlService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${table.ddl.poll-ms:5000}",
            initialDelayString = "${table.ddl.poll-initial-ms:15000}")
    public void poll() {
        try {
            service.pollRunningJobs();
        } catch (Exception e) {
            log.warn("table ddl poll failed: {}", e.getMessage());
        }
    }
}
