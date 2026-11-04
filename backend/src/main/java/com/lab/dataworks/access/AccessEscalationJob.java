package com.lab.dataworks.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TC7-G5：审批超时升级——策略单在某一级停留超过 {@code policy_steps.timeoutDays} 天，
 * 把该策略的 {@code escalateTo} 并入当级可签池（不等新审批人，原池仍可继续签），
 * 并落一条 {@code alert_event} 提醒（投递复用 NotifyService 轮询，与到期提醒同口径）。
 *
 * <p><b>为什么只作用于策略单</b>：旧硬编码链（policy_steps=null）没有超时语义，
 * 存量在途单行为不变；要对旧口径开超时升级，先给它建默认策略行。</p>
 *
 * <p><b>幂等</b>：每单每级只升一次——候选条件排除 {@code escalated_step=当前级}，
 * 置位与事件/提醒同事务，批次再取一把 {@code pg_advisory_xact_lock} 防定时轮与
 * 手动触发并发（同 {@link AccessExpiryJob#notifyExpiring} 的理由）。</p>
 */
@Component
public class AccessEscalationJob {

    private static final Logger log = LoggerFactory.getLogger(AccessEscalationJob.class);

    /** 单轮最多处理多少条，防止一次扫描把告警队列打满。 */
    private static final int BATCH_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AccessEscalationJob(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${access.escalate-ms:600000}", initialDelay = 180000)
    public void scheduled() {
        try {
            Map<String, Object> out = escalateDue("SYSTEM", false);
            int n = (int) out.getOrDefault("escalated", 0);
            if (n > 0) {
                log.info("access approval escalations: {}", n);
            }
        } catch (RuntimeException e) {
            log.warn("access escalation job failed: {}", e.getMessage());
        }
    }

    /**
     * 扫描并升级（或仅预览）。
     *
     * @param dryRun true=只返回候选名单，不落库、不发提醒（联调用）
     * @return escalated=升级条数；candidates=预览时的名单
     */
    public Map<String, Object> escalateDue(String actor, boolean dryRun) {
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select ar.id, ar.requester, ar.resource_type as "resourceType",
                       ar.schema_name as "schemaName", ar.table_name as "tableName",
                       ar.column_name as "columnName", ar.permission, ar.status,
                       ar.policy_steps::text as "policySteps",
                       ar.step_at as "stepAt",
                       case ar.status when 'PENDING' then 0 else 1 end as "step"
                  from platform.access_request ar
                 where ar.status in ('PENDING', 'PENDING_SECOND')
                   and ar.policy_steps is not null
                   and coalesce((ar.policy_steps->>'timeoutDays')::int, 0) > 0
                   and coalesce(ar.escalated_step, -1)
                       <> case ar.status when 'PENDING' then 0 else 1 end
                   and ar.step_at <= now()
                       - ((ar.policy_steps->>'timeoutDays') || ' days')::interval
                 order by ar.step_at
                 limit ?
                """, BATCH_LIMIT);
        Map<String, Object> out = new LinkedHashMap<>();
        if (dryRun) {
            out.put("escalated", 0);
            out.put("candidates", candidates.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.get("id"));
                m.put("requester", c.get("requester"));
                m.put("status", c.get("status"));
                m.put("step", c.get("step"));
                m.put("stepAt", String.valueOf(c.get("stepAt")));
                return m;
            }).toList());
            return out;
        }
        int escalated = 0;
        for (Map<String, Object> c : candidates) {
            Long id = c.get("id") == null ? null : ((Number) c.get("id")).longValue();
            if (id == null) {
                continue;
            }
            int step = ((Number) c.get("step")).intValue();
            String status = String.valueOf(c.get("status"));
            Boolean done = tx.execute(ignored -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class,
                        "access-escalate:" + id);
                // 行锁重读：候选是快照，扫描与升级之间可能已有人签批/已升级/已换级
                List<Map<String, Object>> fresh = jdbc.queryForList(
                        "select status, coalesce(escalated_step, -1) as esc from platform.access_request where id = ?",
                        id);
                if (fresh.isEmpty()) {
                    return false;
                }
                String freshStatus = String.valueOf(fresh.get(0).get("status"));
                int freshStep = "PENDING_SECOND".equals(freshStatus) ? 1 : 0;
                if (!"PENDING".equals(freshStatus) && !"PENDING_SECOND".equals(freshStatus)) {
                    return false;
                }
                if (freshStep != step || ((Number) fresh.get(0).get("esc")).intValue() == step) {
                    return false;
                }
                int n = jdbc.update("""
                        update platform.access_request
                           set escalated_step = ?
                         where id = ?
                           and status in ('PENDING', 'PENDING_SECOND')
                           and coalesce(escalated_step, -1) <> ?
                        """, step, id, step);
                if (n == 0) {
                    return false;
                }
                ApprovalPolicy.Snapshot snap = ApprovalPolicy.parse(String.valueOf(c.get("policySteps")));
                List<String> pool = snap.escalateTo();
                String resource = resourceText(c);
                jdbc.update("""
                        insert into platform.alert_event
                            (rule_name, rule_type, severity, title, detail, event_key, source, status)
                        select ?, ?, 'WARNING', ?, ?, ?, 'ACCESS', 'OPEN'
                        where not exists (select 1 from platform.alert_event e where e.event_key = ?)
                        """, "审批超时升级", "ACCESS_APPROVAL_ESCALATION",
                        "审批超时已升级：" + resource + "（申请人 " + c.get("requester") + "）",
                        "申请单 #" + id + "；资源 " + resource + "；权限 " + c.get("permission")
                                + "；停留级=" + step + "（状态 " + status + "）；起算时间 " + c.get("stepAt")
                                + "；升级池=" + pool + "（已并入当级可签池）"
                                + "；处理：审批中心「待我审批」或直接打开权限申请页",
                        "access-escalate:" + id + ":" + step,
                        "access-escalate:" + id + ":" + step);
                appendEvent(id, step, pool, actor, resource);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                escalated++;
            }
        }
        out.put("escalated", escalated);
        return out;
    }

    /** 事件留痕复用 AccessController 的只 insert 通道（同表同口径，回收/到期也都写这里）。 */
    private void appendEvent(long id, int step, List<String> pool, String actor, String resource) {
        jdbc.update("""
                insert into platform.access_request_event (request_id, action, actor, detail)
                values (?, 'ESCALATE', ?, ?)
                """, id, actor, "step=" + step + "; resource=" + resource
                + "; escalateTo=" + pool + " 已并入当级可签池");
    }

    private static String resourceText(Map<String, Object> row) {
        String column = row.get("columnName") == null ? null : String.valueOf(row.get("columnName"));
        String schema = String.valueOf(row.get("schemaName"));
        String table = row.get("tableName") == null ? "" : String.valueOf(row.get("tableName"));
        String base = "ASSET_FOLDER".equals(String.valueOf(row.get("resourceType")))
                ? "资产目录 " + schema
                : schema + "." + table;
        return column == null || column.isBlank() ? base : base + "." + column;
    }
}
