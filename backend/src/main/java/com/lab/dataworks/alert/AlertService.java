package com.lab.dataworks.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警评估：工作流失败、质量未通过、运行超时（SLA）。
 *
 * <p>采用“轮询 + 唯一键去重”而不是在业务代码里逐点埋钩子：
 * Prefect / 质量执行的结果最终都会落到 platform 表，扫描一遍即可，
 * 即使漏了某次回调，下一轮也会补上，且 (rule_id, event_key) 保证不重复。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 静默/升级都是平台自己触发的，审计里没有真人：操作人记 system。 */
    static final String SYSTEM_ACTOR = "system";

    private final JdbcTemplate jdbc;
    private final com.lab.dataworks.auth.SessionService sessions;

    public AlertService(JdbcTemplate jdbc, com.lab.dataworks.auth.SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${platform.alert-eval-ms:30000}")
    public void scheduledEvaluate() {
        try {
            Map<String, Object> result = evaluate();
            Number total = (Number) result.get("total");
            if (total != null && total.intValue() > 0) {
                log.info("alert evaluate: {}", result);
            }
        } catch (Exception e) {
            log.warn("alert evaluate failed: {}", e.getMessage());
        }
    }

    /** 立即评估一轮，返回本次新产生的告警数（全局，定时任务口径）。 */
    public Map<String, Object> evaluate() {
        return evaluate(null, null);
    }

    /**
     * BUG-TC6-13 ④：带范围的评估。{@code ruleId}/{@code projectId} 至多传一个（控制器已拦
     * 两者同传），都不传＝全局。范围条件统一追加在六个扫描语句的 {@code alert_rule r}
     * 过滤段之后——包括静默留痕与升级扫描，否则「只检查这条规则」会顺带把别的规则
     * 留痕/升级一遍，等于没圈住范围。
     *
     * <p>全局路径必须继续以「零 varargs」调用 {@code jdbc.update(String)} /
     * {@code jdbc.queryForList(String)}：{@code AlertSilenceEscalationTest} 按这个形态
     * 钉住了每轮 5 次 update + 1 次留痕查询的计数。</p>
     */
    public Map<String, Object> evaluate(Long ruleId, Long projectId) {
        String scope = scopeCondition(ruleId, projectId);
        java.util.List<Object> args = scopeArgs(ruleId, projectId);
        int workflowFailed = evaluateWorkflowFailures(scope, args);
        int qualityFailed = evaluateQualityFailures(scope, args);
        int slaTimeout = evaluateSlaTimeouts(scope, args);
        int syncFailed = evaluateSyncTaskFailures(scope, args);
        // D50：静默期内命中的规则要留痕（否则事后查不到「为什么没告警」），
        //      超时没人确认的事件要升级。两者都跟着评估节奏走，不另起定时器。
        int silencedRules = auditSilenced(scope, args);
        int escalated = escalateOverdue(scope, args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowFailed", workflowFailed);
        out.put("qualityFailed", qualityFailed);
        out.put("slaTimeout", slaTimeout);
        out.put("syncTaskFailed", syncFailed);
        out.put("silencedRules", silencedRules);
        out.put("escalated", escalated);
        out.put("total", workflowFailed + qualityFailed + slaTimeout + syncFailed);
        // 回执调用方本次真的扫了哪个范围（不存在/别人的规则在控制器就 404 了）
        out.put("scope", ruleId != null ? "RULE#" + ruleId
                : projectId != null ? "PROJECT#" + projectId : "ALL");
        return out;
    }

    /** 范围条件统一按 {@code alert_rule} 的别名 r 追加；SQL 里六个语句都有该别名。 */
    private static String scopeCondition(Long ruleId, Long projectId) {
        StringBuilder cond = new StringBuilder();
        if (ruleId != null) {
            cond.append(" and r.id = ?\n");
        }
        if (projectId != null) {
            cond.append(" and r.project_id = ?\n");
        }
        return cond.toString();
    }

    private static java.util.List<Object> scopeArgs(Long ruleId, Long projectId) {
        java.util.List<Object> args = new ArrayList<>();
        if (ruleId != null) {
            args.add(ruleId);
        }
        if (projectId != null) {
            args.add(projectId);
        }
        return args;
    }

    /**
     * D50：静默窗口内「本来会告警」的规则各写一条 {@code ALERT_SILENCED} 审计。
     *
     * <p>四类判定与上面的 INSERT 完全同口径，只把静默条件反过来（{@code silence_until > now()}）。
     * 静默是运维主动决定的，但「压掉了什么」必须可追溯，所以按规则聚合后逐条落审计。</p>
     */
    private static final String SILENCED_CANDIDATES = """
            select r.id as "ruleId", r.name as "ruleName", r.rule_type as "ruleType",
                   count(*) as hits, max(r.silence_until) as "silenceUntil"
              from (
                    select r2.id, w.name || ' 实例 #' || i.id as object
                      from platform.alert_rule r2
                      join platform.workflow w on w.project_id = r2.project_id
                      join platform.wf_instance i on i.workflow_id = w.id
                     where r2.enabled = true and r2.rule_type = 'WORKFLOW_FAILED'
                       and (r2.workflow_id is null or r2.workflow_id = w.id)
                       and i.state = 'failed' and i.updated_at > now() - interval '2 days'
                    union all
                    select r2.id, q.name || ' 结果 #' || qr.id
                      from platform.alert_rule r2
                      join platform.quality_rule q on q.project_id = r2.project_id
                      join platform.quality_result qr on qr.rule_id = q.id
                     where r2.enabled = true and r2.rule_type = 'QUALITY_FAILED'
                       and (r2.quality_rule_id is null or r2.quality_rule_id = q.id)
                       and qr.passed = false and qr.exec_status = 'OK'
                       and qr.created_at > now() - interval '2 days'
                    union all
                    select r2.id, w.name || ' 实例 #' || i.id
                      from platform.alert_rule r2
                      join platform.workflow w on w.project_id = r2.project_id
                      join platform.wf_instance i on i.workflow_id = w.id
                     where r2.enabled = true and r2.rule_type = 'SLA_TIMEOUT'
                       and r2.threshold_minutes is not null
                       and (r2.workflow_id is null or r2.workflow_id = w.id)
                       and i.created_at > now() - interval '2 days'
                       and (coalesce(i.ended_at, now()) - coalesce(i.started_at, i.created_at))
                           > make_interval(mins => r2.threshold_minutes)
                    union all
                    select r2.id, t.name
                      from platform.alert_rule r2
                      join platform.sync_task t on t.project_id = r2.project_id
                     where r2.enabled = true and r2.rule_type = 'SYNC_TASK_FAILED'
                       and t.last_status in ('FAILED', 'TIMEOUT')
                       and coalesce(t.last_run_at, t.last_schedule_at, t.created_at)
                           > now() - interval '2 days'
                   ) hit
              join platform.alert_rule r on r.id = hit.id
             where r.silence_until is not null and r.silence_until > now()
            """;

    /** BUG-TC6-13 ④：留痕语句的范围条件插在 where 尾、聚合之前。 */
    private static final String SILENCED_GROUP = "             group by r.id, r.name, r.rule_type\n";

    private int auditSilenced(String scope, List<Object> args) {
        List<Map<String, Object>> rows;
        try {
            rows = args.isEmpty()
                    ? jdbc.queryForList(SILENCED_CANDIDATES + SILENCED_GROUP)
                    : jdbc.queryForList(SILENCED_CANDIDATES + scope + SILENCED_GROUP, args.toArray());
        } catch (RuntimeException e) {
            // 审计留痕失败不能把整轮评估带崩（事件已经正常生成/跳过）
            log.warn("alert silence audit query failed: {}", e.getMessage());
            return 0;
        }
        for (Map<String, Object> row : rows) {
            sessions.audit(SYSTEM_ACTOR, "ALERT_SILENCED", "rule " + row.get("ruleId"),
                    "规则「" + row.get("ruleName") + "」在静默期内命中 " + row.get("hits")
                            + " 次（类型 " + row.get("ruleType") + "），未生成事件；静默到 "
                            + row.get("silenceUntil"),
                    "SUCCESS", null);
        }
        return rows.size();
    }

    /**
     * D50：超过 {@code escalate_after_minutes} 仍未确认的事件，生成一条 ESCALATED 事件。
     *
     * <p>一条 SQL 做完三件事：挑出到期源事件 → 插入升级事件（{@code event_key} 加
     * {@code :escalated} 后缀，天然被 {@code uk_alert_event_rule_key} 去重）→
     * 给源事件打 {@code escalated_at}。升级事件自身也置位，不会被下一轮再升级；
     * 投递沿用现有 {@code notify_channel} 通道（新事件行会被 NotifyService 正常投出去）。</p>
     *
     * @return 本次升级的事件数
     */
    /** 全局升级扫描（老口径，测试与手工触发仍走这个入口）。 */
    int escalateOverdue() {
        return escalateOverdue("", List.of());
    }

    int escalateOverdue(String scope, List<Object> args) {
        // 外层语句是 UPDATE，JdbcTemplate 返回的就是被标记的源事件数。
        // BUG-TC6-13 ④：范围条件加在 due 候选段里（该 CTE 已 join alert_rule r），
        // 只升级圈中规则的超时事件；全局路径保持单参 update(String) 的老形态
        //（AlertSilenceEscalationTest 按这个形态钉住每轮调用计数）。
        return run(ESCALATE_HEAD + scope + ESCALATE_TAIL, args);
    }

    private static final String ESCALATE_HEAD = """
                with due as (
                    select e.id, e.rule_id, e.project_id, e.rule_type, e.severity,
                           e.title, e.detail, e.event_key, e.workflow_id, e.instance_id,
                           e.quality_result_id, r.name as rule_name,
                           r.escalate_to, r.escalate_after_minutes
                      from platform.alert_event e
                      join platform.alert_rule r on r.id = e.rule_id
                     where e.escalated_at is null
                       and e.status = 'OPEN'
                       and e.acked_at is null
                       and coalesce(r.escalate_after_minutes, 0) > 0
                       and e.created_at < now() - make_interval(mins => r.escalate_after_minutes)
            """;

    /** BUG-TC6-13 ④：范围条件插在 due 候选段的 where 尾、取样之前。 */
    private static final String ESCALATE_TAIL = """
                     order by e.created_at
                     limit 50
                ), fired as (
                    insert into platform.alert_event
                        (rule_id, rule_name, project_id, rule_type, severity, title, detail,
                         event_key, workflow_id, instance_id, quality_result_id,
                         escalated_at, escalated_to)
                    select d.rule_id, d.rule_name, d.project_id, d.rule_type, d.severity,
                           '【升级】' || d.title,
                           coalesce(d.detail, '') || '（超 ' || d.escalate_after_minutes
                               || ' 分钟无人确认，升级给 '
                               || coalesce(d.escalate_to, '未指定') || '）',
                           d.event_key || ':escalated', d.workflow_id, d.instance_id,
                           d.quality_result_id, now(), d.escalate_to
                      from due d
                    on conflict (rule_id, event_key) do nothing
                )
                update platform.alert_event e
                   set escalated_at = now(),
                       escalated_to = (select d.escalate_to from due d where d.id = e.id)
                  from due d
                 where e.id = d.id
                   and e.escalated_at is null
                """;

    /** 四类评估 INSERT 共同的收尾；BUG-TC6-13 ④ 的范围条件插在其之前的 where 尾部。 */
    private static final String ON_CONFLICT = "on conflict (rule_id, event_key) do nothing\n";

    /** 全局（无范围）保持单参 update(String) 老形态——测试按它钉住每轮 5 次调用的计数。 */
    private int run(String sql, List<Object> args) {
        return args.isEmpty() ? jdbc.update(sql) : jdbc.update(sql, args.toArray());
    }

    private int evaluateWorkflowFailures(String scope, List<Object> args) {
        return run(WORKFLOW_FAILURES_HEAD + scope + ON_CONFLICT, args);
    }

    private static final String WORKFLOW_FAILURES_HEAD = """
                insert into platform.alert_event
                    (rule_id, rule_name, project_id, rule_type, severity, title, detail,
                     event_key, workflow_id, instance_id)
                select r.id, r.name, r.project_id, 'WORKFLOW_FAILED', r.severity,
                       '工作流运行失败：' || w.name,
                       '实例 #' || i.id || '，DAG ' || i.dag_id
                           || '，业务日期 ' || coalesce(i.biz_date::text, '-'),
                       'instance:' || i.id, w.id, i.id
                from platform.alert_rule r
                join platform.workflow w on w.project_id = r.project_id
                join platform.wf_instance i on i.workflow_id = w.id
                where r.enabled = true
                  and (r.silence_until is null or r.silence_until <= now())
                  and r.rule_type = 'WORKFLOW_FAILED'
                  and (r.workflow_id is null or r.workflow_id = w.id)
                  and i.state = 'failed'
                  and i.updated_at > now() - interval '2 days'
                """;

    /**
     * 采集任务失败/超时：每次失败的运行产生一条事件。
     * event_key 带作业名，下一次失败（新作业）会再告警一次，同一次失败不会重复刷屏。
     */
    private int evaluateSyncTaskFailures(String scope, List<Object> args) {
        return run(SYNC_TASK_FAILURES_HEAD + scope + ON_CONFLICT, args);
    }

    private static final String SYNC_TASK_FAILURES_HEAD = """
                insert into platform.alert_event
                    (rule_id, rule_name, project_id, rule_type, severity, title, detail,
                     event_key)
                select r.id, r.name, t.project_id, 'SYNC_TASK_FAILED', r.severity,
                       '采集任务失败：' || t.name,
                       '任务 ' || t.code || '，状态 ' || t.last_status
                           || '，作业 ' || coalesce(t.last_job_name, '-'),
                       'sync_task:' || t.id || ':' || coalesce(t.last_job_name, t.last_status)
                from platform.alert_rule r
                join platform.sync_task t on t.project_id = r.project_id
                where r.enabled = true
                  and (r.silence_until is null or r.silence_until <= now())
                  and r.rule_type = 'SYNC_TASK_FAILED'
                  and t.last_status in ('FAILED', 'TIMEOUT')
                  and coalesce(t.last_run_at, t.last_schedule_at, t.created_at)
                      > now() - interval '2 days'
                """;

    private int evaluateQualityFailures(String scope, List<Object> args) {
        return run(QUALITY_FAILURES_HEAD + scope + ON_CONFLICT, args);
    }

    private static final String QUALITY_FAILURES_HEAD = """
                insert into platform.alert_event
                    (rule_id, rule_name, project_id, rule_type, severity, title, detail,
                     event_key, quality_result_id)
                select r.id, r.name, r.project_id, 'QUALITY_FAILED', r.severity,
                       '质量规则未通过：' || q.name,
                       '规则 ' || q.code || '，业务日期 ' || qr.biz_date
                           || '，' || coalesce(qr.detail, ''),
                       'quality:' || qr.id, qr.id
                from platform.alert_rule r
                join platform.quality_rule q on q.project_id = r.project_id
                join platform.quality_result qr on qr.rule_id = q.id
                where r.enabled = true
                  and (r.silence_until is null or r.silence_until <= now())
                  and r.rule_type = 'QUALITY_FAILED'
                  and (r.quality_rule_id is null or r.quality_rule_id = q.id)
                  and qr.passed = false
                  -- 只对「规则执行成功但数据不达标」告警；数据源连不上/规则报错是
                  -- 平台问题（exec_status=ERROR），把它报成质量不通过会误导排查。
                  and qr.exec_status = 'OK'
                  and qr.created_at > now() - interval '2 days'
                """;

    private int evaluateSlaTimeouts(String scope, List<Object> args) {
        return run(SLA_TIMEOUTS_HEAD + scope + ON_CONFLICT, args);
    }

    private static final String SLA_TIMEOUTS_HEAD = """
                insert into platform.alert_event
                    (rule_id, rule_name, project_id, rule_type, severity, title, detail,
                     event_key, workflow_id, instance_id)
                select r.id, r.name, r.project_id, 'SLA_TIMEOUT', r.severity,
                       '实例运行超时：' || w.name,
                       '实例 #' || i.id || ' 已运行 '
                           || round(extract(epoch from
                                (coalesce(i.ended_at, now()) - coalesce(i.started_at, i.created_at))) / 60.0, 1)
                           || ' 分钟，超过阈值 ' || r.threshold_minutes || ' 分钟',
                       'instance:' || i.id, w.id, i.id
                from platform.alert_rule r
                join platform.workflow w on w.project_id = r.project_id
                join platform.wf_instance i on i.workflow_id = w.id
                where r.enabled = true
                  and (r.silence_until is null or r.silence_until <= now())
                  and r.rule_type = 'SLA_TIMEOUT'
                  and r.threshold_minutes is not null
                  and (r.workflow_id is null or r.workflow_id = w.id)
                  and i.created_at > now() - interval '2 days'
                  and (coalesce(i.ended_at, now()) - coalesce(i.started_at, i.created_at))
                      > make_interval(mins => r.threshold_minutes)
                """;
}
