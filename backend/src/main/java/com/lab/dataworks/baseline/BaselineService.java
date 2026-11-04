package com.lab.dataworks.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务基线（SLA 预警）。
 *
 * <p>每分钟对每条启用的基线做一次预测：</p>
 * <ol>
 *   <li>取基线内各工作流当天的实例与任务状态；</li>
 *   <li>用最近 10 次历史耗时算每个任务的 P90（新任务没有历史时用当前均值兜底）；</li>
 *   <li>在依赖图上算关键路径剩余耗时，得到 {@code predictedFinish}；</li>
 *   <li>超过「承诺时间 - 提前分钟」写 {@code PREDICT_LATE}，过点未完成写 {@code BREACH}，
 *       恢复后写 {@code RECOVERED}。</li>
 * </ol>
 *
 * <p>时区固定 Asia/Shanghai：承诺时间和业务日期都按这个时区解释，容器内是 UTC 也不会偏。</p>
 */
@Service
public class BaselineService {

    private static final Logger log = LoggerFactory.getLogger(BaselineService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> LEVELS = Set.of("P1", "P2", "P3");
    private static final Set<String> FAILED_STATES = Set.of("failed", "upstream_failed", "cancelled");
    /** 完全没有历史、也没有已完成任务时，单个任务的保守默认耗时。 */
    private static final double DEFAULT_TASK_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public BaselineService(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    // ------------------------------------------------------------------ 请求 / 响应

    public record BaselineRequest(Long projectId, String name, String deadlineTime,
                                  Integer warnBeforeMin, String level, String owner,
                                  Boolean enabled, List<Long> workflowIds) {
    }

    private record MemberPrediction(long workflowId, String code, String name, String state,
                                    Instant predictedFinish, boolean fallback, boolean failed,
                                    String detail) {
    }

    private record Prediction(LocalDate statDate, Instant deadlineAt, Instant warnAt,
                              Instant predictedFinish, Integer lateMinutes, String status,
                              List<MemberPrediction> members, String detail) {
        boolean allDone() {
            return !members.isEmpty()
                    && members.stream().allMatch(m -> "success".equals(m.state()));
        }
    }

    // ------------------------------------------------------------------ CRUD

    public List<Map<String, Object>> list(Long projectId) {
        String tenant = TenantScope.current();
        StringBuilder sql = new StringBuilder("""
                select b.id, b.tenant_id, b.project_id, b.name,
                       to_char(b.deadline_time, 'HH24:MI') as deadline_time,
                       b.warn_before_min, b.level, b.owner, b.enabled,
                       b.created_at, b.updated_at,
                       p.code as project_code, p.name as project_name
                  from platform.baseline b
                  join platform.project p on p.id = b.project_id
                 where b.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (projectId != null) {
            sql.append(" and b.project_id = ?");
            args.add(projectId);
        }
        sql.append(" order by b.enabled desc, b.level, b.name");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(toMap(row, predict(row, now)));
        }
        return out;
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> row = baselineRow(id, TenantScope.current());
        return toMap(row, predict(row, Instant.now()));
    }

    public Map<String, Object> create(BaselineRequest req, String username) {
        String tenant = TenantScope.current();
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.projectId() == null) {
            throw ApiException.badRequest("必须指定项目");
        }
        String name = validateName(req.name());
        LocalTime deadline = parseDeadline(req.deadlineTime());
        int warn = validateWarn(req.warnBeforeMin());
        String level = validateLevel(req.level());
        Map<String, Object> project = project(req.projectId(), tenant);
        List<Long> workflowIds = validateMembers(req.workflowIds(), req.projectId(), tenant);
        boolean enabled = req.enabled() == null || req.enabled();
        try {
            Long id = transactions.execute(status -> {
                Long baselineId = jdbc.queryForObject("""
                        insert into platform.baseline
                            (tenant_id, project_id, name, deadline_time, warn_before_min,
                             level, owner, enabled)
                        values (?, ?, ?, ?::time, ?, ?, ?, ?)
                        returning id
                        """, Long.class, tenant, req.projectId(), name, deadline.format(HH_MM),
                        warn, level, blankToNull(req.owner()), enabled);
                if (baselineId == null) {
                    throw ApiException.badRequest("基线创建失败");
                }
                insertMembers(baselineId, workflowIds);
                return baselineId;
            });
            log.info("baseline {} created by {} project={} name={}", id, username,
                    project.get("code"), name);
            return get(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一项目下已存在同名基线: " + name);
        }
    }

    public Map<String, Object> update(long id, BaselineRequest req, String username) {
        String tenant = TenantScope.current();
        Map<String, Object> existing = baselineRow(id, tenant);
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String name = req.name() == null ? String.valueOf(existing.get("name")) : validateName(req.name());
        LocalTime deadline = req.deadlineTime() == null
                ? LocalTime.parse(String.valueOf(existing.get("deadline_time")), HH_MM)
                : parseDeadline(req.deadlineTime());
        int warn = req.warnBeforeMin() == null
                ? intOf(existing.get("warn_before_min")) : validateWarn(req.warnBeforeMin());
        String level = req.level() == null
                ? String.valueOf(existing.get("level")) : validateLevel(req.level());
        boolean enabled = req.enabled() == null
                ? Boolean.TRUE.equals(existing.get("enabled")) : req.enabled();
        long projectId = ((Number) existing.get("project_id")).longValue();
        List<Long> workflowIds = req.workflowIds() == null
                ? jdbc.queryForList("""
                        select workflow_id from platform.baseline_member
                         where baseline_id = ? order by workflow_id
                        """, Long.class, id)
                : validateMembers(req.workflowIds(), projectId, tenant);
        try {
            transactions.execute(status -> {
                jdbc.update("""
                        update platform.baseline
                           set name = ?, deadline_time = ?::time, warn_before_min = ?,
                               level = ?, owner = ?, enabled = ?, updated_at = now()
                         where id = ? and tenant_id = ?
                        """, name, deadline.format(HH_MM), warn, level,
                        blankToNull(req.owner()), enabled, id, tenant);
                jdbc.update("delete from platform.baseline_member where baseline_id = ?", id);
                insertMembers(id, workflowIds);
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一项目下已存在同名基线: " + name);
        }
        log.info("baseline {} updated by {}", id, username);
        return get(id);
    }

    public void delete(long id, String username) {
        int changed = jdbc.update(
                "delete from platform.baseline where id = ? and tenant_id = ?",
                id, TenantScope.current());
        if (changed == 0) {
            throw ApiException.notFound("基线不存在: " + id);
        }
        log.info("baseline {} deleted by {}", id, username);
    }

    public List<Map<String, Object>> events(long id, String dateRaw) {
        Map<String, Object> row = baselineRow(id, TenantScope.current());
        LocalDate date = parseDate(dateRaw, LocalDate.now(ZONE));
        return jdbc.queryForList("""
                select id, baseline_id as "baselineId", stat_date as "statDate",
                       predicted_finish as "predictedFinish", deadline_at as "deadlineAt",
                       late_minutes as "lateMinutes", kind, notified_at as "notifiedAt", detail
                  from platform.baseline_event
                 where baseline_id = ? and stat_date = ?::date
                 order by notified_at desc, id desc
                 limit 200
                """, id, date);
    }

    // ------------------------------------------------------------------ 评估

    /** 手工触发一次评估（与每分钟定时任务走同一条路径，方便验收不必等整分钟）。 */
    public Map<String, Object> evaluate(long id) {
        return evaluate(id, TenantScope.current(), true);
    }

    private Map<String, Object> evaluate(long id, String tenant, boolean persist) {
        Map<String, Object> row = baselineRow(id, tenant);
        Instant now = Instant.now();
        Prediction prediction = predict(row, now);
        if (persist && Boolean.TRUE.equals(row.get("enabled"))) {
            persistEvents(row, prediction, now);
        }
        Map<String, Object> out = toMap(row, prediction);
        out.put("events", eventsInternal(id, prediction.statDate()));
        return out;
    }

    /**
     * 每分钟评估所有启用的基线。单条失败只记日志，不影响其它基线。
     *
     * <p>后端单副本时进程内 {@link AtomicBoolean} 足够防止两轮重叠；多副本要换成
     * 数据库 advisory lock（与补数 tick 的约束一致）。</p>
     */
    @Scheduled(fixedDelayString = "${platform.baseline-tick-ms:60000}",
            initialDelayString = "${platform.baseline-initial-ms:15000}")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Long> ids = jdbc.queryForList(
                    "select id from platform.baseline where enabled order by id", Long.class);
            for (Long id : ids) {
                try {
                    evaluate(id, null, true);
                } catch (Exception e) {
                    log.warn("baseline {} evaluation failed: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("baseline tick failed: {}", e.getMessage());
        } finally {
            ticking.set(false);
        }
    }

    private void persistEvents(Map<String, Object> row, Prediction prediction, Instant now) {
        long id = ((Number) row.get("id")).longValue();
        LocalDate statDate = prediction.statDate();
        List<String> existing = jdbc.queryForList("""
                select kind from platform.baseline_event
                 where baseline_id = ? and stat_date = ?::date
                """, String.class, id, statDate);
        if (prediction.allDone()) {
            if (existing.contains("PREDICT_LATE") || existing.contains("BREACH")) {
                String detail = "全部 " + prediction.members().size() + " 个工作流已完成，完成时间 "
                        + hhmm(prediction.predictedFinish()) + "，承诺 "
                        + hhmm(prediction.deadlineAt()) + "（"
                        + describeLatency(prediction.lateMinutes()) + "）";
                upsertEvent(id, statDate, prediction, "RECOVERED", detail);
                log.info("baseline {} RECOVERED on {}", id, statDate);
            }
            return;
        }
        if (!now.isBefore(prediction.deadlineAt())) {
            List<String> pending = prediction.members().stream()
                    .filter(m -> !"success".equals(m.state()))
                    .map(MemberPrediction::code)
                    .toList();
            String detail = "已过承诺时间 " + hhmm(prediction.deadlineAt()) + "，"
                    + pending.size() + " 个工作流未完成："
                    + (pending.isEmpty() ? "-" : String.join("、", pending));
            upsertEvent(id, statDate, prediction, "BREACH", detail);
            log.info("baseline {} BREACH on {} pending={}", id, statDate, pending);
        }
        Instant predicted = prediction.predictedFinish();
        if (predicted != null && predicted.isAfter(prediction.warnAt())) {
            String detail = warningDetail(prediction);
            upsertEvent(id, statDate, prediction, "PREDICT_LATE", detail);
            log.info("baseline {} PREDICT_LATE on {} predicted={} late={}min", id, statDate,
                    hhmm(predicted), prediction.lateMinutes());
        }
    }

    private void upsertEvent(long baselineId, LocalDate statDate, Prediction prediction,
                             String kind, String detail) {
        Instant predicted = prediction.predictedFinish();
        jdbc.update("""
                insert into platform.baseline_event
                    (baseline_id, stat_date, predicted_finish, deadline_at, late_minutes,
                     kind, detail)
                values (?, ?::date, ?, ?, ?, ?, ?)
                on conflict (baseline_id, stat_date, kind) do update set
                    predicted_finish = excluded.predicted_finish,
                    deadline_at = excluded.deadline_at,
                    late_minutes = excluded.late_minutes,
                    detail = excluded.detail
                """, baselineId, statDate,
                predicted == null ? null : OffsetDateTime.ofInstant(predicted, ZONE),
                OffsetDateTime.ofInstant(prediction.deadlineAt(), ZONE),
                prediction.lateMinutes(), kind, truncate(detail, 4000));
    }

    // ------------------------------------------------------------------ 预测

    private Prediction predict(Map<String, Object> row, Instant now) {
        LocalDate statDate = now.atZone(ZONE).toLocalDate();
        LocalTime deadline = LocalTime.parse(String.valueOf(row.get("deadline_time")), HH_MM);
        int warnBefore = intOf(row.get("warn_before_min"));
        Instant deadlineAt = statDate.atTime(deadline).atZone(ZONE).toInstant();
        Instant warnAt = deadlineAt.minus(Duration.ofMinutes(warnBefore));
        boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
        long baselineId = ((Number) row.get("id")).longValue();

        List<Map<String, Object>> workflows = jdbc.queryForList("""
                select w.id, w.code, w.name, w.model_json::text as model
                  from platform.baseline_member m
                  join platform.workflow w on w.id = m.workflow_id
                 where m.baseline_id = ?
                 order by w.code
                """, baselineId);
        List<MemberPrediction> members = new ArrayList<>(workflows.size());
        for (Map<String, Object> workflow : workflows) {
            members.add(predictWorkflow(workflow, statDate, now));
        }

        boolean allDone = !members.isEmpty()
                && members.stream().allMatch(m -> "success".equals(m.state()));
        // 基线完成时间 = 最后一个成员完成时间（所有工作流都完成才算完成）
        Instant predicted = null;
        for (MemberPrediction member : members) {
            if (member.predictedFinish() != null
                    && (predicted == null || member.predictedFinish().isAfter(predicted))) {
                predicted = member.predictedFinish();
            }
        }
        boolean anyFailed = members.stream().anyMatch(MemberPrediction::failed);
        boolean anyStarted = members.stream().anyMatch(m -> !"not_started".equals(m.state()));
        String status;
        if (!enabled) {
            status = "DISABLED";
        } else if (allDone) {
            status = "DONE";
        } else if (!now.isBefore(deadlineAt)) {
            status = "BREACH";
        } else if (anyFailed) {
            status = "FAILED";
        } else if (predicted != null && predicted.isAfter(warnAt)) {
            status = "WARN";
        } else if (anyStarted) {
            status = "RUNNING";
        } else {
            status = "NOT_STARTED";
        }
        Integer lateMinutes = BaselinePredictor.lateMinutes(predicted, deadlineAt);
        return new Prediction(statDate, deadlineAt, warnAt, predicted, lateMinutes, status,
                members, statusDetail(status, members, deadlineAt, predicted, lateMinutes));
    }

    private MemberPrediction predictWorkflow(Map<String, Object> workflow, LocalDate statDate,
                                             Instant now) {
        long workflowId = ((Number) workflow.get("id")).longValue();
        String code = String.valueOf(workflow.get("code"));
        String name = workflow.get("name") == null ? code : String.valueOf(workflow.get("name"));
        String model = workflow.get("model") == null ? null : String.valueOf(workflow.get("model"));

        List<Map<String, Object>> instances = jdbc.queryForList("""
                select id, state, started_at, ended_at
                  from platform.wf_instance
                 where workflow_id = ? and biz_date = ?::date
                 order by created_at desc, id desc limit 1
                """, workflowId, statDate);
        Map<String, Object> instance = instances.isEmpty() ? null : instances.get(0);
        String state = instance == null ? "not_started"
                : String.valueOf(instance.get("state")).toLowerCase(Locale.ROOT);
        Instant endedAt = instantOf(instance == null ? null : instance.get("ended_at"));

        Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
        if (instance != null) {
            for (Map<String, Object> task : jdbc.queryForList("""
                    select task_code, state, started_at, ended_at
                      from platform.task_instance
                     where wf_instance_id = ?
                    """, ((Number) instance.get("id")).longValue())) {
                tasks.put(String.valueOf(task.get("task_code")), task);
            }
        }

        Map<String, List<String>> deps = new HashMap<>();
        Set<String> codes = new LinkedHashSet<>();
        parseModel(model, codes, deps);
        codes.addAll(tasks.keySet());
        if (codes.isEmpty()) {
            codes.add("__workflow__");
        }

        Map<String, Double> p90 = p90(workflowId);
        Double currentMean = currentMean(tasks);
        // 只有确实有任务缺 P90、且当前实例也没有已完成任务可参考时，才再查一次
        // 工作流级历史均值；正常路径（有历史）不会多一次查询。
        boolean missingHistory = codes.stream().anyMatch(taskCode -> {
            Map<String, Object> task = tasks.get(taskCode);
            String taskState = task == null ? null
                    : String.valueOf(task.get("state")).toLowerCase(Locale.ROOT);
            return !"success".equals(taskState) && !p90.containsKey(taskCode);
        });
        Double workflowMean = missingHistory && currentMean == null ? workflowMean(workflowId) : null;
        boolean[] fallback = {false};
        List<BaselinePredictor.Node> nodes = new ArrayList<>(codes.size());
        for (String taskCode : codes) {
            Map<String, Object> task = tasks.get(taskCode);
            String taskState = task == null ? null
                    : String.valueOf(task.get("state")).toLowerCase(Locale.ROOT);
            if ("success".equals(taskState)) {
                nodes.add(new BaselinePredictor.Node(taskCode, 0));
                continue;
            }
            double estimate = estimate(taskCode, p90, currentMean, workflowMean, fallback);
            if ("running".equals(taskState)) {
                Instant taskStarted = instantOf(task.get("started_at"));
                if (taskStarted != null) {
                    double elapsed = Duration.between(taskStarted, now).toSeconds();
                    estimate = Math.max(0, estimate - Math.max(0, elapsed));
                }
            }
            nodes.add(new BaselinePredictor.Node(taskCode, estimate));
        }
        double remaining = BaselinePredictor.remainingSeconds(nodes, deps);
        boolean allTasksDone = !tasks.isEmpty() && tasks.values().stream()
                .allMatch(t -> "success".equalsIgnoreCase(String.valueOf(t.get("state"))));
        Instant predicted;
        if ("success".equals(state)) {
            // 已完成的工作流用真实结束时间，RECOVERED 的迟到分钟数才有意义
            predicted = endedAt == null ? now : endedAt;
        } else {
            predicted = now.plusSeconds(Math.round(remaining));
            if (predicted.isBefore(now)) {
                predicted = now;
            }
        }
        boolean failed = FAILED_STATES.contains(state);
        String detail;
        if ("success".equals(state)) {
            detail = "已完成";
        } else if (instance == null) {
            detail = "今日尚未运行，按完整关键路径估算剩余 " + minutes(remaining) + " 分钟";
        } else if (failed) {
            detail = "运行" + state + "，预计无法自动完成";
        } else {
            detail = "剩余关键路径 " + minutes(remaining) + " 分钟"
                    + (allTasksDone ? "（任务已全部完成，等待实例收敛）" : "")
                    + (fallback[0] ? "；部分任务无历史 P90，用当前均值兜底" : "");
        }
        return new MemberPrediction(workflowId, code, name, state, predicted, fallback[0],
                failed, detail);
    }

    /**
     * 单任务耗时估计：历史 P90 → 当前实例已完成任务均值 → 该工作流历史均值 → 60 秒。
     * 后三级都算兜底，会在响应里标 {@code fallback=true}，验收据此确认新任务不报错。
     */
    private double estimate(String taskCode, Map<String, Double> p90, Double currentMean,
                            Double workflowMean, boolean[] fallback) {
        Double history = p90.get(taskCode);
        if (history != null && history > 0) {
            return history;
        }
        fallback[0] = true;
        if (currentMean != null && currentMean > 0) {
            return currentMean;
        }
        if (workflowMean != null && workflowMean > 0) {
            return workflowMean;
        }
        return DEFAULT_TASK_SECONDS;
    }

    private Map<String, Double> p90(long workflowId) {
        Map<String, Double> out = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select task_code, percentile_cont(0.9) within group (order by secs) as p90
                  from (
                    select ti.task_code,
                           extract(epoch from (ti.ended_at - ti.started_at)) as secs,
                           row_number() over (partition by ti.task_code
                                              order by ti.ended_at desc) as rn
                      from platform.task_instance ti
                      join platform.wf_instance i on i.id = ti.wf_instance_id
                     where i.workflow_id = ?
                       and ti.state = 'success'
                       and ti.started_at is not null
                       and ti.ended_at is not null
                  ) t
                 where rn <= 10
                 group by task_code
                """, workflowId);
        for (Map<String, Object> row : rows) {
            Object value = row.get("p90");
            if (value instanceof Number number && number.doubleValue() > 0) {
                out.put(String.valueOf(row.get("task_code")), number.doubleValue());
            }
        }
        return out;
    }

    private Double currentMean(Map<String, Map<String, Object>> tasks) {
        double sum = 0;
        int count = 0;
        for (Map<String, Object> task : tasks.values()) {
            Instant started = instantOf(task.get("started_at"));
            Instant ended = instantOf(task.get("ended_at"));
            if (started != null && ended != null && !ended.isBefore(started)) {
                sum += Duration.between(started, ended).toSeconds();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    private Double workflowMean(long workflowId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select avg(extract(epoch from (ti.ended_at - ti.started_at))) as mean
                  from (
                    select ti.started_at, ti.ended_at
                      from platform.task_instance ti
                      join platform.wf_instance i on i.id = ti.wf_instance_id
                     where i.workflow_id = ?
                       and ti.state = 'success'
                       and ti.started_at is not null
                       and ti.ended_at is not null
                     order by ti.ended_at desc
                     limit 20
                  ) ti
                """, workflowId);
        Object value = rows.isEmpty() ? null : rows.get(0).get("mean");
        return value instanceof Number number && number.doubleValue() > 0
                ? number.doubleValue() : null;
    }

    /** 解析工作流模型里的任务与依赖；模型损坏时退化为「每个任务互不依赖」，不影响预测任务。 */
    private void parseModel(String modelJson, Set<String> codes, Map<String, List<String>> deps) {
        if (modelJson == null || modelJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(modelJson);
            for (JsonNode task : root.path("tasks")) {
                String code = task.path("code").asText("");
                if (!code.isBlank()) {
                    codes.add(code);
                }
            }
            for (JsonNode edge : root.path("edges")) {
                String from = edge.path("from").asText("");
                String to = edge.path("to").asText("");
                if (!from.isBlank() && !to.isBlank() && !from.equals(to)) {
                    deps.computeIfAbsent(to, ignored -> new ArrayList<>()).add(from);
                }
            }
        } catch (Exception e) {
            log.warn("baseline model parse failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 响应组装

    private Map<String, Object> toMap(Map<String, Object> row, Prediction prediction) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("projectId", row.get("project_id"));
        out.put("projectCode", row.get("project_code"));
        out.put("projectName", row.get("project_name"));
        out.put("name", row.get("name"));
        out.put("deadlineTime", row.get("deadline_time"));
        out.put("warnBeforeMin", row.get("warn_before_min"));
        out.put("level", row.get("level"));
        out.put("owner", row.get("owner"));
        out.put("enabled", row.get("enabled"));
        out.put("createdAt", row.get("created_at"));
        out.put("updatedAt", row.get("updated_at"));
        out.put("statDate", prediction.statDate().toString());
        out.put("deadlineAt", prediction.deadlineAt().toString());
        out.put("warnAt", prediction.warnAt().toString());
        out.put("predictedFinish",
                prediction.predictedFinish() == null ? null : prediction.predictedFinish().toString());
        out.put("lateMinutes", prediction.lateMinutes());
        out.put("status", prediction.status());
        out.put("detail", prediction.detail());
        List<Map<String, Object>> members = new ArrayList<>(prediction.members().size());
        for (MemberPrediction member : prediction.members()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("workflowId", member.workflowId());
            item.put("code", member.code());
            item.put("name", member.name());
            item.put("state", member.state());
            item.put("predictedFinish",
                    member.predictedFinish() == null ? null : member.predictedFinish().toString());
            item.put("fallback", member.fallback());
            item.put("failed", member.failed());
            item.put("detail", member.detail());
            members.add(item);
        }
        out.put("memberCount", members.size());
        out.put("members", members);
        return out;
    }

    private List<Map<String, Object>> eventsInternal(long baselineId, LocalDate date) {
        return jdbc.queryForList("""
                select id, stat_date as "statDate", predicted_finish as "predictedFinish",
                       deadline_at as "deadlineAt", late_minutes as "lateMinutes",
                       kind, notified_at as "notifiedAt", detail
                  from platform.baseline_event
                 where baseline_id = ? and stat_date = ?::date
                 order by notified_at desc, id desc
                 limit 200
                """, baselineId, date);
    }

    private static String statusDetail(String status, List<MemberPrediction> members,
                                       Instant deadlineAt, Instant predicted, Integer lateMinutes) {
        return switch (status) {
            case "DONE" -> "全部 " + members.size() + " 个工作流已完成";
            case "DISABLED" -> "基线已停用";
            case "NOT_STARTED" -> "今日尚未运行";
            case "BREACH" -> "已过承诺时间 " + hhmm(deadlineAt) + "，仍有工作流未完成";
            case "FAILED" -> "有工作流运行失败："
                    + String.join("、", members.stream().filter(MemberPrediction::failed)
                    .map(MemberPrediction::code).toList());
            case "WARN" -> "预计 " + hhmm(predicted) + " 完成，" + describeLatency(lateMinutes)
                    + "（承诺 " + hhmm(deadlineAt) + "）";
            default -> predicted == null ? "运行中"
                    : "预计 " + hhmm(predicted) + " 完成（承诺 " + hhmm(deadlineAt) + "）";
        };
    }

    private static String warningDetail(Prediction prediction) {
        Integer late = prediction.lateMinutes();
        String base;
        if (late != null && late > 0) {
            base = "预计 " + hhmm(prediction.predictedFinish()) + " 完成，预计迟到 "
                    + late + " 分钟（承诺 " + hhmm(prediction.deadlineAt()) + "）";
        } else {
            base = "预计 " + hhmm(prediction.predictedFinish()) + " 完成，距承诺 "
                    + hhmm(prediction.deadlineAt()) + " 还有 " + (late == null ? 0 : -late)
                    + " 分钟，已进入提前预警窗口";
        }
        List<String> pending = prediction.members().stream()
                .filter(m -> !"success".equals(m.state()))
                .map(MemberPrediction::code)
                .toList();
        return base + (pending.isEmpty() ? "" : "；未完成：" + String.join("、", pending));
    }

    private static String describeLatency(Integer lateMinutes) {
        if (lateMinutes == null) {
            return "完成时间未知";
        }
        return lateMinutes > 0 ? "迟到 " + lateMinutes + " 分钟" : "提前 " + (-lateMinutes) + " 分钟";
    }

    // ------------------------------------------------------------------ 基础

    private Map<String, Object> baselineRow(long id, String tenant) {
        String sql = """
                select b.id, b.tenant_id, b.project_id, b.name,
                       to_char(b.deadline_time, 'HH24:MI') as deadline_time,
                       b.warn_before_min, b.level, b.owner, b.enabled,
                       b.created_at, b.updated_at,
                       p.code as project_code, p.name as project_name
                  from platform.baseline b
                  join platform.project p on p.id = b.project_id
                 where b.id = ?
                """ + (tenant == null ? "" : " and b.tenant_id = ?");
        List<Map<String, Object>> rows = tenant == null
                ? jdbc.queryForList(sql, id)
                : jdbc.queryForList(sql, id, tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("基线不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> project(long projectId, String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, code from platform.project where id = ? and tenant_id = ?",
                projectId, tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
        return rows.get(0);
    }

    private List<Long> validateMembers(List<Long> workflowIds, long projectId, String tenant) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            throw ApiException.badRequest("基线至少需要一个工作流成员");
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(workflowIds));
        distinct.removeIf(id -> id == null);
        if (distinct.isEmpty()) {
            throw ApiException.badRequest("工作流成员不能为空");
        }
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.add(tenant);
        args.addAll(distinct);
        String placeholders = String.join(",",
                java.util.Collections.nCopies(distinct.size(), "?"));
        List<Long> found = jdbc.queryForList(
                "select id from platform.workflow"
                        + " where project_id = ? and tenant_id = ? and id in (" + placeholders + ")",
                Long.class, args.toArray());
        if (found.size() != distinct.size()) {
            List<Long> missing = new ArrayList<>(distinct);
            missing.removeAll(found);
            throw ApiException.badRequest("工作流不属于该项目或不存在: " + missing);
        }
        return distinct;
    }

    private void insertMembers(long baselineId, List<Long> workflowIds) {
        for (Long workflowId : workflowIds) {
            jdbc.update("""
                    insert into platform.baseline_member (baseline_id, workflow_id)
                    values (?, ?) on conflict do nothing
                    """, baselineId, workflowId);
        }
    }

    private static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("基线名称不能为空");
        }
        if (name.length() > 128) {
            throw ApiException.badRequest("基线名称不能超过 128 个字符");
        }
        return name;
    }

    private static LocalTime parseDeadline(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("承诺完成时间不能为空（HH:mm）");
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("承诺完成时间格式不正确，应为 HH:mm: " + raw);
        }
    }

    private static int validateWarn(Integer raw) {
        int warn = raw == null ? 30 : raw;
        if (warn < 0 || warn > 1440) {
            throw ApiException.badRequest("提前预警分钟数只能是 0–1440");
        }
        return warn;
    }

    private static String validateLevel(String raw) {
        String level = raw == null || raw.isBlank() ? "P2" : raw.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw ApiException.badRequest("级别只能是 P1 / P2 / P3");
        }
        return level;
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("日期格式不正确，应为 yyyy-MM-dd: " + raw);
        }
    }

    private static Instant instantOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.LocalDateTime local) {
            return local.atZone(ZONE).toInstant();
        }
        return null;
    }

    private static String hhmm(Instant instant) {
        return instant == null ? "-" : HH_MM.format(instant.atZone(ZONE));
    }

    private static long minutes(double seconds) {
        return Math.max(0, Math.round(seconds / 60.0));
    }

    private static int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
