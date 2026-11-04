package com.lab.dataworks.alert;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.lab.dataworks.common.LikePattern;

/**
 * 告警中心：告警事件、规则管理、确认/关闭。
 */
@RestController
@RequestMapping("/api")
public class AlertController {

    private static final Set<String> TYPES = Set.of("WORKFLOW_FAILED", "QUALITY_FAILED",
            "SLA_TIMEOUT", "SYNC_TASK_FAILED");
    private static final Set<String> SEVERITIES = Set.of("WARNING", "CRITICAL");

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AlertService alerts;

    public AlertController(JdbcTemplate jdbc, SessionService sessions, AlertService alerts) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.alerts = alerts;
    }

    // ---------- 事件 ----------

    @GetMapping("/alerts/summary")
    public Map<String, Object> summary() {
        String tenant = com.lab.dataworks.auth.TenantScope.current();
        boolean platform = com.lab.dataworks.common.TenantGuard.platform();
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where status = 'OPEN') as open,
                       count(*) filter (where status = 'OPEN' and severity = 'CRITICAL') as critical_open,
                       count(*) filter (where created_at >= (date_trunc('day', now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai')) as today_new,
                       count(*) filter (where status = 'CLOSED' and closed_at >= (date_trunc('day', now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai')) as today_closed,
                       count(*) as total
                from platform.alert_event e
                where (e.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (e.project_id is null and ?))
                """, tenant, platform);
        Map<String, Object> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select rule_type, count(*) as c from platform.alert_event e
                where e.status = 'OPEN'
                  and (e.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (e.project_id is null and ?))
                group by rule_type
                """, tenant, platform)) {
            byType.put(String.valueOf(r.get("rule_type")), ((Number) r.get("c")).longValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", num(row.get("open")));
        out.put("criticalOpen", num(row.get("critical_open")));
        out.put("todayNew", num(row.get("today_new")));
        out.put("todayClosed", num(row.get("today_closed")));
        out.put("total", num(row.get("total")));
        out.put("openByType", byType);
        // BUG-TC6-8：投递失败要在汇总里看得见，不能只躺在 alert_delivery 表里。
        // 窗口取调度器实际覆盖的 2 天（NotifyService.deliverPending 同口径），
        // 按渠道归属租户过滤，与 GET /api/alert-deliveries 一致。
        Map<String, Object> delivery = jdbc.queryForMap("""
                select count(*) filter (where d.status = 'FAILED') as failed,
                       count(*) filter (where d.status = 'FAILED' and d.attempts >= 5) as exhausted
                from platform.alert_delivery d
                join platform.notify_channel c on c.id = d.channel_id
                where d.created_at > now() - interval '2 days'
                  and (c.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (c.project_id is null and ?))
                """, tenant, platform);
        out.put("deliveryFailed", num(delivery.get("failed")));
        out.put("deliveryExhausted", num(delivery.get("exhausted")));
        return out;
    }

    /** 传了 page/size 但没传 size 时的每页条数（与 UDF 资源列表同口径）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 告警事件列表。
     *
     * <p>D19：原来 page/size 被完全忽略——{@code ?page=1&size=1} 仍然按 {@code limit}
     * （默认 200）全量返回，也没有 {@code X-Total-Count}，客户端以为分页生效其实整表在拉。
     * 现在对齐平台契约（{@link com.lab.dataworks.common.Paging}，与 UDF/资产列表同一口径）：</p>
     * <ul>
     *   <li>{@code page}/{@code size} 都不传：保持原结构（数组 + {@code limit} 上限），
     *       告警中心前端 {@code /api/alerts?limit=200} 行为不变；</li>
     *   <li>传了任一：参数从严（page 从 1、size 1–500，非法值 400，不静默夹紧），
     *       返回 {@code {items,total,page,size}} 并带 {@code X-Total-Count} 响应头。</li>
     * </ul>
     */
    @GetMapping("/alerts")
    public Object events(@RequestParam(required = false) String status,
                         @RequestParam(required = false) String type,
                         @RequestParam(required = false) String severity,
                         @RequestParam(required = false) Long projectId,
                         @RequestParam(required = false) String keyword,
                         @RequestParam(required = false) Boolean createdToday,
                         @RequestParam(required = false) Boolean closedToday,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size,
                         @RequestParam(defaultValue = "200") int limit,
                         jakarta.servlet.http.HttpServletResponse response) {
        // 非法分页参数必须在任何查询之前挡住（不静默夹紧，与项目/UDF 列表口径一致）
        boolean paged = page != null || size != null;
        if (paged) {
            com.lab.dataworks.common.Paging.requireValid(page, size);
        }
        String select = """
                select e.id, e.rule_id as "ruleId", e.rule_name as "ruleName",
                       e.project_id as "projectId", p.name as "projectName",
                       e.rule_type as "ruleType", e.severity, e.title, e.detail, e.status,
                       e.workflow_id as "workflowId", w.name as "workflowName",
                       e.instance_id as "instanceId", e.quality_result_id as "qualityResultId",
                       e.created_at as "createdAt", e.acked_by as "ackedBy", e.acked_at as "ackedAt",
                       e.closed_by as "closedBy", e.closed_at as "closedAt",
                       e.close_reason as "closeReason",
                       e.escalated_at as "escalatedAt", e.escalated_to as "escalatedTo",
                       (select count(*) from platform.alert_delivery d
                         where d.event_id = e.id and d.status = 'SENT') as "deliverySent",
                       (select count(*) from platform.alert_delivery d
                         where d.event_id = e.id and d.status = 'FAILED') as "deliveryFailed",
                       (select count(*) from platform.alert_delivery d
                         where d.event_id = e.id and d.status = 'FAILED'
                           and d.attempts >= 5) as "deliveryExhausted"
                """;
        // from + 租户守卫，列表与 count 共用，避免两处口径漂移
        String from = """
                from platform.alert_event e
                left join platform.project p on p.id = e.project_id
                left join platform.workflow w on w.id = e.workflow_id
                where (e.project_id in (select p2.id from platform.project p2 where p2.tenant_id = ?)
                       or (e.project_id is null and ?))
                """;
        StringBuilder filters = new StringBuilder();
        List<Object> args = new ArrayList<>();
        // 项目告警按项目归属；系统告警（project_id 为空）只有默认租户能看到
        args.add(com.lab.dataworks.auth.TenantScope.current());
        args.add(com.lab.dataworks.common.TenantGuard.platform());
        if (status != null && !status.isBlank()) {
            filters.append(" and e.status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (type != null && !type.isBlank()) {
            filters.append(" and e.rule_type = ?");
            args.add(type.trim().toUpperCase(Locale.ROOT));
        }
        if (severity != null && !severity.isBlank()) {
            filters.append(" and e.severity = ?");
            args.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (projectId != null) {
            filters.append(" and e.project_id = ?");
            args.add(projectId);
        }
        if (keyword != null && !keyword.isBlank()) {
            filters.append(" and (e.title ilike ? or coalesce(e.detail, '') ilike ? or e.rule_name ilike ?)");
            String like = LikePattern.contains(keyword);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        // 统计卡「今日新增 / 今日已关闭」需要与列表口径一致（北京时间切日）
        if (Boolean.TRUE.equals(createdToday)) {
            filters.append(" and e.created_at >= (date_trunc('day', now() at time zone 'Asia/Shanghai')"
                    + " at time zone 'Asia/Shanghai')");
        }
        if (Boolean.TRUE.equals(closedToday)) {
            filters.append(" and e.closed_at >= (date_trunc('day', now() at time zone 'Asia/Shanghai')"
                    + " at time zone 'Asia/Shanghai')");
        }
        if (!paged) {
            // 兼容旧调用方（告警中心前端就是这条路径）：数组 + limit 上限
            List<Object> legacyArgs = new ArrayList<>(args);
            legacyArgs.add(Math.min(Math.max(limit, 1), 500));
            return jdbc.queryForList(
                    select + from + filters + " order by e.id desc limit ?", legacyArgs.toArray());
        }
        int p = com.lab.dataworks.common.Paging.pageOf(page);
        int s = com.lab.dataworks.common.Paging.sizeOf(size, DEFAULT_PAGE_SIZE);
        Long total = jdbc.queryForObject("select count(*) " + from + filters,
                Long.class, args.toArray());
        long totalRows = total == null ? 0L : total;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(com.lab.dataworks.common.Paging.offset(p, s));
        List<Map<String, Object>> items = jdbc.queryForList(
                select + from + filters + " order by e.id desc limit ? offset ?",
                pageArgs.toArray());
        response.setHeader("X-Total-Count", String.valueOf(totalRows));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", totalRows);
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    @PostMapping("/alerts/{id}/ack")
    @RequireAtom("alert:ack")
    public Map<String, Object> ack(@PathVariable long id, HttpServletRequest request) {
        requireEvent(id);
        String username = username(request);
        int updated = jdbc.update("""
                update platform.alert_event
                set status = 'ACKED', acked_by = ?, acked_at = now()
                where id = ? and status = 'OPEN'
                  and (project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (project_id is null and ?))
                """, username, id, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        if (updated == 0) {
            requireEvent(id);
            throw ApiException.badRequest("只有待处理的告警可以确认");
        }
        audit(request, "ALERT_ACK", "alert " + id, "确认告警");
        return Map.of("ok", true, "status", "ACKED");
    }

    /** BUG-TC6-10：关闭告警的请求体；{@code reason} 是必填的处置说明。 */
    public record CloseRequest(String reason) {
    }

    /** BUG-TC6-10：处置说明下限。与变更单/重命名的理由口径一致（≥5 字才算说明）。 */
    static final int MIN_CLOSE_REASON = 5;
    /** BUG-TC6-10：上限，防止把整段日志塞进事件行。 */
    static final int MAX_CLOSE_REASON = 500;

    /**
     * 关闭告警（BUG-TC6-10）。
     *
     * <p>原状是两个口子：① 不传任何 body 也能关，事后查不到「为什么关」；
     * ② 对仍为 OPEN 的事件直接 close 也能 200，「确认」在流程上不承重。
     * 现在按状态机收口：<b>只有 ACKED 能关闭</b>，且<b>必须带 ≥5 字处置说明</b>。
     * 理由落 {@code close_reason} 列（V117），同时进审计——两处都能查，删事件也不丢审计。</p>
     *
     * <p>关错了有退路：{@link #reopen(long, HttpServletRequest)} 把 CLOSED 拉回 ACKED，
     * 因此「关闭」不再是不可逆操作。</p>
     */
    @PostMapping("/alerts/{id}/close")
    @RequireAtom("alert:ack")
    public Map<String, Object> close(@PathVariable long id,
                                     @RequestBody(required = false) CloseRequest req,
                                     HttpServletRequest request) {
        requireEvent(id);
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.length() < MIN_CLOSE_REASON) {
            throw ApiException.badRequest("必须填写处置说明（至少 " + MIN_CLOSE_REASON + " 个字）");
        }
        if (reason.length() > MAX_CLOSE_REASON) {
            throw ApiException.badRequest("处置说明不能超过 " + MAX_CLOSE_REASON + " 个字");
        }
        String username = username(request);
        int updated = jdbc.update("""
                update platform.alert_event
                set status = 'CLOSED', closed_by = ?, closed_at = now(), close_reason = ?
                where id = ? and status = 'ACKED'
                  and (project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (project_id is null and ?))
                """, username, reason, id, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        if (updated == 0) {
            // 状态不符要给出可操作的原因，不能让调用方猜：OPEN 需要先确认，CLOSED 已终态
            throw closeRejected(requireEventStatus(id));
        }
        audit(request, "ALERT_CLOSE", "alert " + id, "关闭告警；处置说明：" + reason);
        return Map.of("ok", true, "status", "CLOSED");
    }

    /** 关闭被状态机拒绝时的 400（调用方据文案知道下一步该点「确认」还是「重开」）。 */
    private static ApiException closeRejected(String status) {
        if ("OPEN".equals(status)) {
            return ApiException.badRequest("告警还没有确认：请先确认，再填写处置说明关闭");
        }
        if ("CLOSED".equals(status)) {
            return ApiException.badRequest("该告警已经关闭；如需继续处置请先重开");
        }
        return ApiException.badRequest("只有已确认的告警可以关闭（当前状态：" + status + "）");
    }

    /**
     * 重开已关闭的告警（BUG-TC6-10 ②，二选一里选「可撤销」）。
     *
     * <p>CLOSED → ACKED：回到「已确认」而不是 OPEN，避免重开后又被升级扫描挑中。
     * 当前行的 closed_by/closed_at/close_reason 清空（状态已经不是关闭，留着会误导），
     * 上一次的关闭理由写进 ALERT_REOPEN 审计，复盘链路不断。</p>
     */
    @PostMapping("/alerts/{id}/reopen")
    @RequireAtom("alert:ack")
    public Map<String, Object> reopen(@PathVariable long id, HttpServletRequest request) {
        requireEvent(id);
        String status = requireEventStatus(id);
        if (!"CLOSED".equals(status)) {
            throw ApiException.badRequest("只有已关闭的告警可以重开（当前状态：" + status + "）");
        }
        String username = username(request);
        List<Map<String, Object>> before = jdbc.queryForList(
                "select close_reason from platform.alert_event where id = ?", id);
        Object previousReason = before.isEmpty() ? null : before.get(0).get("close_reason");
        int updated = jdbc.update("""
                update platform.alert_event
                set status = 'ACKED',
                    closed_by = null, closed_at = null, close_reason = null,
                    acked_by = coalesce(acked_by, ?), acked_at = coalesce(acked_at, now())
                where id = ? and status = 'CLOSED'
                  and (project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (project_id is null and ?))
                """, username, id, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        if (updated == 0) {
            // 并发：查询后到更新前被别人改过状态
            throw ApiException.badRequest("告警状态已经变化，请刷新后重试");
        }
        audit(request, "ALERT_REOPEN", "alert " + id,
                "重开已关闭告警" + (previousReason == null ? "" : "（原处置说明：" + previousReason + "）"));
        return Map.of("ok", true, "status", "ACKED");
    }

    /** 事件当前状态（已过 {@link #requireEvent} 归属校验，所以这里只按 id 取）。 */
    private String requireEventStatus(long id) {
        String status = jdbc.queryForObject(
                "select status from platform.alert_event where id = ?", String.class, id);
        return status == null ? "UNKNOWN" : status;
    }

    @DeleteMapping("/alerts/{id}")
    @RequireAtom("alert:ack")
    public Map<String, Object> deleteEvent(@PathVariable long id, HttpServletRequest request) {
        requireEvent(id);
        int deleted = jdbc.update("""
                delete from platform.alert_event
                 where id = ? and status = 'CLOSED'
                   and (project_id in (select p.id from platform.project p where p.tenant_id = ?)
                        or (project_id is null and ?))
                """, id, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        if (deleted == 0) {
            requireEvent(id);
            throw ApiException.badRequest("只能删除已关闭的告警");
        }
        audit(request, "ALERT_DELETE", "alert " + id, "删除已关闭告警");
        return Map.of("ok", true);
    }

    /**
     * BUG-TC6-13 ④：立即评估支持可选范围参数。
     *
     * <p>原状是零入参的全局扫描：UI「立即检查」想只测当前这条规则做不到，还会顺带
     * 扫全平台规则。现在 {@code ruleId}/{@code projectId} 可任选其一（都不传＝全局，保持现状）；
     * 传了不存在的 {@code ruleId} 必须 404，不能静默降级成全局执行——那是「点了检查、
     * 结果扫了别人」的假动作。回执里带 {@code scope}，调用方能确认范围真的生效。</p>
     */
    @PostMapping("/alerts/evaluate")
    @RequireAtom("ops:run")
    public Map<String, Object> evaluate(@RequestBody(required = false) EvaluateRequest req) {
        Long ruleId = req == null ? null : req.ruleId();
        Long projectId = req == null ? null : req.projectId();
        if (ruleId != null && projectId != null) {
            throw ApiException.badRequest("ruleId 与 projectId 只能二选一（都不传＝全局评估）");
        }
        if (ruleId != null) {
            ownedRule(ruleId);
        }
        if (projectId != null) {
            requireProject(projectId);
        }
        return alerts.evaluate(ruleId, projectId);
    }

    /** BUG-TC6-13 ④：evaluate 的可选范围入参。 */
    public record EvaluateRequest(Long ruleId, Long projectId) {
    }

    // ---------- 规则 ----------

    @GetMapping("/alert-rules")
    public List<Map<String, Object>> rules(@RequestParam(required = false) Long projectId) {
        String sql = """
                select r.id, r.project_id as "projectId", p.name as "projectName", r.name,
                       r.rule_type as "ruleType", r.workflow_id as "workflowId",
                       w.name as "workflowName", r.quality_rule_id as "qualityRuleId",
                       q.name as "qualityRuleName", r.threshold_minutes as "thresholdMinutes",
                       r.severity, r.enabled, r.created_by as "createdBy",
                       r.created_at as "createdAt", r.updated_at as "updatedAt",
                       r.silence_until as "silenceUntil",
                       r.escalate_after_minutes as "escalateAfterMinutes",
                       r.escalate_to as "escalateTo",
                       (r.silence_until is not null and r.silence_until > now()) as "silenced"
                from platform.alert_rule r
                join platform.project p on p.id = r.project_id
                left join platform.workflow w on w.id = r.workflow_id
                left join platform.quality_rule q on q.id = r.quality_rule_id
                where r.project_id in (select pp.id from platform.project pp where pp.tenant_id = ?)"""
                + (projectId == null ? "" : " and r.project_id = ?")
                + " order by r.id desc";
        return projectId == null
                ? jdbc.queryForList(sql, com.lab.dataworks.auth.TenantScope.current())
                : jdbc.queryForList(sql, com.lab.dataworks.auth.TenantScope.current(), projectId);
    }

    @PostMapping("/alert-rules")
    @RequireAtom("alert:manage")
    public Map<String, Object> createRule(@RequestBody RuleRequest req, HttpServletRequest request) {
        RuleRequest r = validate(req);
        requireProject(r.projectId());
        Long id = jdbc.queryForObject("""
                insert into platform.alert_rule
                    (project_id, name, rule_type, workflow_id, quality_rule_id,
                     threshold_minutes, severity, enabled, created_by, tenant_id,
                     silence_until, escalate_after_minutes, escalate_to)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, r.projectId(), r.name(), r.ruleType(), r.workflowId(),
                r.qualityRuleId(), r.thresholdMinutes(), r.severity(),
                r.enabled() == null || r.enabled(), username(request),
                com.lab.dataworks.auth.TenantScope.current(),
                parseSilenceUntil(r.silenceUntil()), r.escalateAfterMinutes(), r.escalateTo());
        audit(request, "ALERT_RULE_CREATE", "rule " + id,
                r.name() + " / " + r.ruleType() + escalateNote(r));
        return Map.of("id", id == null ? 0 : id);
    }

    @PutMapping("/alert-rules/{id}")
    @RequireAtom("alert:manage")
    public Map<String, Object> updateRule(@PathVariable long id, @RequestBody RuleRequest req,
                                          HttpServletRequest request) {
        // 先校验规则归属再 validate：否则跨租户请求会先撞上「项目不属于你」的 400，
        // 状态码与「不存在统一 404」的口径不一致（第二轮验收实测）。
        ownedRule(id);
        RuleRequest r = validate(req);
        requireProject(r.projectId());
        int updated = jdbc.update("""
                update platform.alert_rule
                set project_id = ?, name = ?, rule_type = ?, workflow_id = ?, quality_rule_id = ?,
                    threshold_minutes = ?, severity = ?, enabled = ?, updated_at = now(),
                    silence_until = ?, escalate_after_minutes = ?, escalate_to = ?
                where id = ?
                  and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, r.projectId(), r.name(), r.ruleType(), r.workflowId(), r.qualityRuleId(),
                r.thresholdMinutes(), r.severity(), r.enabled() == null || r.enabled(),
                parseSilenceUntil(r.silenceUntil()), r.escalateAfterMinutes(), r.escalateTo(), id,
                com.lab.dataworks.auth.TenantScope.current());
        if (updated == 0) {
            throw ApiException.notFound("告警规则不存在: " + id);
        }
        audit(request, "ALERT_RULE_UPDATE", "rule " + id,
                r.name() + " / " + r.ruleType() + escalateNote(r));
        return Map.of("ok", true);
    }

    @DeleteMapping("/alert-rules/{id}")
    @RequireAtom("alert:manage")
    public Map<String, Object> deleteRule(@PathVariable long id, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.name from platform.alert_rule r
                 where r.id = ?
                   and r.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("告警规则不存在: " + id);
        }
        int deleted = jdbc.update("""
                delete from platform.alert_rule
                 where id = ?
                   and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (deleted == 0) {
            throw ApiException.notFound("告警规则不存在: " + id);
        }
        audit(request, "ALERT_RULE_DELETE", "rule " + id, String.valueOf(rows.get(0).get("name")));
        return Map.of("ok", true);
    }

    // ---------- 内部 ----------

    /**
     * D50：新增静默窗口与升级策略三项。
     *
     * @param silenceUntil         ISO 时间（如 2026-10-27T10:00:00+08:00）；空＝不静默。
     *                             窗口内命中不生成事件，但会写 ALERT_SILENCED 审计。
     * @param escalateAfterMinutes 多久没人确认就升级；0＝不升级；不传＝默认 30 分钟。
     * @param escalateTo           升级接收人（用户名或角色码），仅留痕与通知文案用，
     *                             投递通道仍是现有 notify_channel。
     */
    public record RuleRequest(Long projectId, String name, String ruleType, Long workflowId,
                              Long qualityRuleId, Integer thresholdMinutes, String severity,
                              Boolean enabled, String silenceUntil, Integer escalateAfterMinutes,
                              String escalateTo) {
    }

    private RuleRequest validate(RuleRequest req) {
        if (req == null || req.projectId() == null) {
            throw ApiException.badRequest("请选择项目");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("告警规则名称不能为空");
        }
        if (req.name().trim().length() > 128) {
            throw ApiException.badRequest("告警规则名称不能超过 128 个字符");
        }
        String type = req.ruleType() == null ? "" : req.ruleType().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("不支持的告警类型: " + req.ruleType());
        }
        // BUG-TC6-13 ①：severity 前端是必填（Alerts.tsx 表单 rules required），后端原来留空
        // 兜底成 CRITICAL——「前端标必填、后端静默给默认」两套口径，API 直调方必踩，
        // 也违反「不许 setDefault 掩盖必填」（acceptance-rules.md 第 4 条）。现在缺省直接 400。
        if (req.severity() == null || req.severity().isBlank()) {
            throw ApiException.badRequest("请选择告警级别（WARNING / CRITICAL）");
        }
        String severity = req.severity().trim().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw ApiException.badRequest("告警级别只能是 WARNING / CRITICAL");
        }
        Long projectId = req.projectId();
        Long workflowId = "QUALITY_FAILED".equals(type) ? null : req.workflowId();
        Long qualityRuleId = "QUALITY_FAILED".equals(type) ? req.qualityRuleId() : null;
        Long project = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (project == null || project == 0) {
            // 别的租户的项目按「不存在」处理，不暴露存在性
            throw ApiException.notFound("项目不存在: " + projectId);
        }
        if (workflowId != null) {
            Long n = jdbc.queryForObject("""
                    select count(*) from platform.workflow where id = ? and project_id = ?
                    """, Long.class, workflowId, projectId);
            if (n == null || n == 0) {
                throw ApiException.badRequest("工作流不属于该项目: " + workflowId);
            }
        }
        if (qualityRuleId != null) {
            Long n = jdbc.queryForObject("""
                    select count(*) from platform.quality_rule where id = ? and project_id = ?
                    """, Long.class, qualityRuleId, projectId);
            if (n == null || n == 0) {
                throw ApiException.badRequest("质量规则不属于该项目: " + qualityRuleId);
            }
        }
        Integer threshold = "SLA_TIMEOUT".equals(type) ? req.thresholdMinutes() : null;
        if ("SLA_TIMEOUT".equals(type)) {
            if (threshold == null || threshold < 0) {
                throw ApiException.badRequest("SLA 超时告警必须设置阈值分钟数（≥0）");
            }
        }
        // D50：静默时间与升级策略在入口就校验，非法值 400（不静默吞掉，也不等落库撞约束）
        java.sql.Timestamp silenceUntil = parseSilenceUntil(req.silenceUntil());
        int escalateAfterMinutes = req.escalateAfterMinutes() == null
                ? DEFAULT_ESCALATE_AFTER_MINUTES : req.escalateAfterMinutes();
        if (escalateAfterMinutes < 0 || escalateAfterMinutes > MAX_ESCALATE_AFTER_MINUTES) {
            throw ApiException.badRequest("升级等待分钟数只能是 0（不升级）到 "
                    + MAX_ESCALATE_AFTER_MINUTES + " 之间，当前: " + req.escalateAfterMinutes());
        }
        String escalateTo = req.escalateTo() == null || req.escalateTo().isBlank()
                ? null : req.escalateTo().trim();
        if (escalateTo != null && escalateTo.length() > 64) {
            throw ApiException.badRequest("升级接收人不能超过 64 个字符");
        }
        if (silenceUntil != null && silenceUntil.getTime() < System.currentTimeMillis()) {
            throw ApiException.badRequest("静默时间必须晚于当前时间（过去的时间点等于没静默）");
        }
        return new RuleRequest(projectId, req.name().trim(), type, workflowId, qualityRuleId,
                threshold, severity, req.enabled(),
                req.silenceUntil(), escalateAfterMinutes, escalateTo);
    }

    /** D50：不传＝30 分钟；0＝该规则不升级。 */
    static final int DEFAULT_ESCALATE_AFTER_MINUTES = 30;
    /** D50：上限 7 天，避免填个离谱值等于永不升级。 */
    static final int MAX_ESCALATE_AFTER_MINUTES = 7 * 24 * 60;

    /**
     * D50：解析静默截止时间。接受带时区的 ISO 时间、{@code yyyy-MM-dd HH:mm:ss}（按东八区）
     * 与纯日期（当天 00:00 东八区）；空＝不静默；其它写法 400。
     */
    static java.sql.Timestamp parseSilenceUntil(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return java.sql.Timestamp.from(java.time.OffsetDateTime.parse(value).toInstant());
        } catch (java.time.format.DateTimeParseException ignored) {
            // 试下不带时区的写法
        }
        try {
            return java.sql.Timestamp.valueOf(
                    java.time.LocalDateTime.parse(value.replace(' ', 'T')));
        } catch (java.time.format.DateTimeParseException ignored) {
            // 试下纯日期
        }
        try {
            java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
            return java.sql.Timestamp.valueOf(
                    java.time.LocalDate.parse(value).atStartOfDay(zone).toLocalDateTime());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("静默时间格式不合法: " + raw
                    + "（示例 2026-10-27T10:00:00+08:00 / 2026-10-27 10:00:00 / 2026-10-27）");
        }
    }

    /** D50：把静默/升级策略带进规则审计，事后能看出「是谁把这条规则静音到什么时候」。 */
    private static String escalateNote(RuleRequest r) {
        StringBuilder note = new StringBuilder();
        if (r.silenceUntil() != null && !r.silenceUntil().isBlank()) {
            note.append(" / 静默到 ").append(r.silenceUntil().trim());
        }
        note.append(" / 升级 ")
                .append(r.escalateAfterMinutes() != null && r.escalateAfterMinutes() == 0
                        ? "关闭" : r.escalateAfterMinutes() + " 分钟")
                .append(r.escalateTo() == null ? "" : "→" + r.escalateTo());
        return note.toString();
    }

    /** 规则归属：按项目判定，取不到 404。 */
    private void ownedRule(long id) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.alert_rule r
                 where r.id = ?
                   and r.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, Long.class, id, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("告警规则不存在: " + id);
        }
    }

    /** 项目必须属于当前租户，否则 404。 */
    private void requireProject(long projectId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    /** 告警归属：项目告警随项目租户；project_id 为空的是系统告警，仅默认租户可见。 */
    private void requireEvent(long id) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.alert_event e
                 where e.id = ?
                   and (e.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                        or (e.project_id is null and ?))
                """, Long.class, id, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        if (n == null || n == 0) {
            throw ApiException.notFound("告警不存在: " + id);
        }
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "system" : String.valueOf(value);
    }

    private void audit(HttpServletRequest request, String action, String target, String detail) {
        sessions.audit(username(request), action, target, detail, "SUCCESS",
                AuthInterceptor.clientIp(request));
    }

    private long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
