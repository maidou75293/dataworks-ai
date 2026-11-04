package com.lab.dataworks.flink;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * FlinkSQL 流开发：任务 CRUD + 校验 / 提交 / 停止 / 状态。
 *
 * <p>模型和 SQL 工作流一致：任务属于项目，保存的是 SQL 脚本；
 * 运行态（Job ID / 会话句柄）落在任务行上，状态以 Flink 为准、按需刷新。</p>
 */
@RestController
@RequestMapping("/api/flink/jobs")
public class FlinkSqlController {

    private static final Logger log = LoggerFactory.getLogger(FlinkSqlController.class);
    private static final Pattern CODE = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,63}");
    private static final int NAME_MAX = 128;
    private static final int SQL_MAX = 256 * 1024;
    /** 已经结束的状态：停止时不需要（也不能）再调 cancel。 */
    private static final Set<String> TERMINAL_STATES =
            Set.of("FINISHED", "CANCELED", "CANCELLED", "FAILED", "SUSPENDED", "GONE");
    /**
     * 平台认为「这个任务还占着一个作业」的状态。
     *
     * <p>前端早按这组状态禁用了按钮（StreamJob.tsx 的 ACTIVE_STATES），后端以前只拒
     * RUNNING/SUBMITTING：重启中的任务直接调接口还能再提交一个，旧作业失去句柄成孤儿。</p>
     */
    private static final Set<String> ACTIVE_STATES =
            Set.of("SUBMITTING", "STARTING", "RUNNING", "RESTARTING", "STOPPING");
    /** PROD 侧「有作业/正在启停」的状态：这些状态下不允许再上线第二个作业。 */
    private static final Set<String> ACTIVE_PROD_STATES =
            Set.of("RUNNING", "RESTARTING", "STARTING", "STOPPING");

    private final JdbcTemplate jdbc;
    private final FlinkSqlService flink;
    private final SessionService sessions;
    private final com.lab.dataworks.udf.UdfService udf;

    public FlinkSqlController(JdbcTemplate jdbc, FlinkSqlService flink, SessionService sessions,
                              com.lab.dataworks.udf.UdfService udf) {
        this.jdbc = jdbc;
        this.flink = flink;
        this.sessions = sessions;
        this.udf = udf;
    }

    public record JobRequest(Long projectId, String code, String name, String description,
                             String sqlText, String sessionMode, String folder, String env,
                             Integer parallelism, String consumerGroup) {
    }

    public record FolderRequest(String path) {
    }

    @GetMapping
    @RequireAtom("query:run")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long projectId) {
        String sql = """
                select j.id, j.project_id, p.name as project_name, p.code as project_code,
                       j.code, j.name, j.description, j.folder, j.env, j.status, j.flink_job_id,
                       j.session_mode, j.parallelism, j.consumer_group,
                       j.smoke_at, j.published_version, j.published_at,
                       j.prod_status, j.prod_job_id, j.last_error, j.submitted_at, j.stopped_at,
                       j.updated_at,
                       length(j.sql_text) as sql_length
                  from platform.flink_sql_job j
                  join platform.project p on p.id = j.project_id
                 where p.tenant_id = ?
                """;
        return projectId == null
                ? jdbc.queryForList(sql + " order by j.id desc", TenantScope.current())
                : jdbc.queryForList(sql + " and j.project_id = ? order by j.id desc",
                TenantScope.current(), projectId);
    }

    @GetMapping("/{id}")
    @RequireAtom("query:run")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> row = find(id);
        addStateFlags(row);
        return row;
    }

    /**
     * 「对当前这版 SQL 有效」的校验/DEV 跑通标记：按 SQL 指纹比较，不看时间。
     * 改名字、改目录、改并行度不会让标记失效；SQL 一变就自动变 false。
     */
    private void addStateFlags(Map<String, Object> row) {
        String current = hash(sqlOf(row));
        row.put("validate_ok", current.equals(String.valueOf(row.get("validate_hash"))));
        row.put("smoke_ok", current.equals(String.valueOf(row.get("smoke_hash"))));
    }

    @PostMapping
    @RequireAtom("flink:write")
    public Map<String, Object> create(@RequestBody JobRequest req, HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.projectId() == null) {
            throw ApiException.badRequest("请选择项目");
        }
        requireProject(req.projectId());
        String code = requireCode(req.code());
        String name = requireName(req.name());
        // 创建允许空 SQL：草稿可以先建、后填（前端「新建文件」流程）。
        // v449 P1-1 要堵的是**更新**把已就绪任务的 SQL 清空（见 update）。
        String sqlText = requireSql(req.sqlText(), false);
        try {
            String sessionMode = sessionMode(req.sessionMode());
            String folder = requireFolder(req.folder());
            String env = envOf(req.env());
            int parallelism = parallelism(req.parallelism());
            String consumerGroup = consumerGroup(req.consumerGroup());
            Long id = jdbc.query("""
                    insert into platform.flink_sql_job
                        (project_id, code, name, description, sql_text, session_mode, folder, env,
                         parallelism, consumer_group, tenant_id, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, rs -> rs.next() ? rs.getLong(1) : null,
                    req.projectId(), code, name, req.description(), sqlText, sessionMode, folder, env,
                    parallelism, consumerGroup, TenantScope.current(), user(http));
            touchFolder(req.projectId(), folder);
            sessions.audit(user(http), "FLINK_JOB_CREATE", code, name);
            return detail(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("任务编码已存在: " + code);
        }
    }

    @PutMapping("/{id}")
    @RequireAtom("flink:write")
    public Map<String, Object> update(@PathVariable long id, @RequestBody JobRequest req,
                                      HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Map<String, Object> row = find(id);
        if (ACTIVE_STATES.contains(String.valueOf(row.get("status")))) {
            throw ApiException.conflict("任务运行中（含启动/重启/停止中），先停止再修改");
        }
        String name = requireName(req.name());
        // v449 P1-1：更新也要求 SQL 非空；没传 SQL 时保留原值（不能把任务改废）
        String sqlText = req.sqlText() == null ? sqlOf(row) : requireSql(req.sqlText(), true);
        String folder = requireFolder(req.folder());
        // 只改并行度/名称/目录时不清 DEV 跑通记录：SQL 没变，指纹就还对得上
        boolean sqlChanged = !sqlText.equals(sqlOf(row));
        jdbc.update("""
                update platform.flink_sql_job
                   set name = ?, description = ?, sql_text = ?, session_mode = ?,
                       folder = ?, env = ?, parallelism = ?, consumer_group = ?,
                       smoke_hash = case when ? then null else smoke_hash end,
                       smoke_at = case when ? then null else smoke_at end,
                       smoke_by = case when ? then null else smoke_by end,
                       validate_hash = case when ? then null else validate_hash end,
                       updated_at = now()
                 where id = ? and tenant_id = ?
                """, name, req.description(), sqlText, sessionMode(req.sessionMode()),
                folder, envOf(req.env()), parallelism(req.parallelism()),
                consumerGroup(req.consumerGroup()),
                sqlChanged, sqlChanged, sqlChanged, sqlChanged, id, TenantScope.current());
        touchFolder(((Number) row.get("project_id")).longValue(), folder);
        sessions.audit(user(http), "FLINK_JOB_UPDATE", String.valueOf(row.get("code")), name);
        return detail(id);
    }

    @DeleteMapping("/{id}")
    @RequireAtom("flink:write")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        if (ACTIVE_STATES.contains(String.valueOf(row.get("status")))) {
            throw ApiException.conflict("任务运行中（含启动/重启/停止中），先停止再删除");
        }
        // 线上还在跑时删库行会留下谁也管不了的 Flink 孤儿（审计 P1-1）：
        // 远端还在跑就拒绝，确认已结束再顺手清理一次。
        Object prodJobId = row.get("prod_job_id");
        if (prodJobId != null) {
            String state = "";
            try {
                state = String.valueOf(flink.asProd(
                        () -> flink.jobStatus(String.valueOf(prodJobId))).get("state"));
            } catch (RuntimeException ignored) {
                // 查不到就按「可能还在跑」处理，宁可拒绝
            }
            if (TERMINAL_STATES.contains(state) || "GONE".equals(state) || state.isBlank()) {
                flink.asProd(() -> flink.cancel(String.valueOf(prodJobId)));
                if (row.get("prod_session") != null) {
                    flink.asProd(() -> flink.closeSession(String.valueOf(row.get("prod_session"))));
                }
            } else {
                throw ApiException.conflict(
                        "线上作业还在运行（" + state + "），请先「停止线上」再删除任务");
            }
        }
        // DEV 侧可能残留句柄：先取消。取消真的失败（不是 404/已终态）时不能删行，
        // 否则远端作业既没人管、平台也没有记录了（对账扫不到）。
        if (row.get("flink_job_id") != null) {
            try {
                flink.cancel(String.valueOf(row.get("flink_job_id")));
            } catch (RuntimeException e) {
                if (!(e instanceof ApiException api) || api.getStatus() != 404) {
                    throw new ApiException(502, "取消远端作业失败，暂不能删除：" + e.getMessage());
                }
            }
        }
        jdbc.update("delete from platform.flink_sql_job where id = ? and tenant_id = ?",
                id, TenantScope.current());
        sessions.audit(user(http), "FLINK_JOB_DELETE", String.valueOf(row.get("code")),
                prodJobId == null ? null : "prod job=" + prodJobId);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/validate")
    @RequireAtom("flink:run")
    public Map<String, Object> validate(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        String sql = requireSql(sqlOf(row), true);
        Map<String, Object> result;
        try {
            result = flink.validate(sql);
        } catch (RuntimeException e) {
            // 校验失败要留下痕迹：清掉旧的「通过」，刷新后不会误显示绿色
            jdbc.update("""
                    update platform.flink_sql_job
                       set validate_hash = null, validate_at = now(), validate_by = ?,
                           updated_at = now()
                     where id = ? and tenant_id = ?
                    """, user(http), id, TenantScope.current());
            sessions.audit(user(http), "FLINK_JOB_VALIDATE", String.valueOf(row.get("code")),
                    "failed: " + e.getMessage());
            throw e;
        }
        jdbc.update("""
                update platform.flink_sql_job
                   set validate_hash = ?, validate_at = now(), validate_by = ?, updated_at = now()
                 where id = ? and tenant_id = ?
                """, hash(sql), user(http), id, TenantScope.current());
        sessions.audit(user(http), "FLINK_JOB_VALIDATE", String.valueOf(row.get("code")), "ok");
        return result;
    }

    @PostMapping("/{id}/submit")
    @RequireAtom("flink:run")
    public Map<String, Object> submit(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        String previous = String.valueOf(row.get("status"));
        if (ACTIVE_STATES.contains(previous)) {
            throw ApiException.conflict("任务已经在运行（状态：" + previous + "），先停止再运行");
        }
        String sql = requireSql(sqlOf(row), true);
        String code = String.valueOf(row.get("code"));
        boolean shared = "SHARED".equals(String.valueOf(row.get("session_mode")));
        // 先原子占位再提交：并发点击时只有一个请求能进入 Flink，避免起出孤儿作业。
        // （之前是「先起作业再条件更新」，落败方取消不彻底会留下 RUNNING 孤儿。）
        int claimed = jdbc.update("""
                update platform.flink_sql_job
                   set status = 'SUBMITTING', last_error = null, restarting_since = null,
                       updated_at = now()
                 where id = ? and tenant_id = ?
                   and status not in ('SUBMITTING', 'STARTING', 'RUNNING', 'RESTARTING', 'STOPPING')
                """, id, TenantScope.current());
        if (claimed == 0) {
            throw ApiException.conflict("任务正在提交或已经在运行（可能是重复点击）");
        }
        Map<String, Object> started;
        try {
            started = flink.submit(withRuntimeConfig(sql, row), shared);
        } catch (RuntimeException e) {
            // 提交失败要把状态退回去，否则任务会永远停在「提交中」
            jdbc.update("""
                    update platform.flink_sql_job
                       set status = ?, last_error = ?, updated_at = now()
                     where id = ? and tenant_id = ?
                    """, previous, trim(e.getMessage()), id, TenantScope.current());
            flink.logSubmitFailure(id, e.getMessage());
            sessions.audit(user(http), "FLINK_JOB_SUBMIT", code, "failed: " + e.getMessage());
            throw e;
        }
        String jobId = String.valueOf(started.get("jobId"));
        String session = String.valueOf(started.get("session"));
        jdbc.update("""
                update platform.flink_sql_job
                   set status = 'RUNNING', flink_job_id = ?, last_flink_job_id = ?,
                       last_job_at = now(), session_handle = ?, last_error = null,
                       restarting_since = null,
                       submitted_at = now(), stopped_at = null, updated_at = now()
                 where id = ? and tenant_id = ?
                """, jobId, jobId, session, id, TenantScope.current());
        sessions.audit(user(http), "FLINK_JOB_SUBMIT", code, "job=" + jobId);
        return find(id);
    }

    @PostMapping("/{id}/stop")
    @RequireAtom("flink:run")
    public Map<String, Object> stop(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        Object jobId = row.get("flink_job_id");
        String session = row.get("session_handle") == null ? null
                : String.valueOf(row.get("session_handle"));
        if (jobId == null) {
            // 平台还挂着 RUNNING 但没有 job id（历史脏数据/集群重建）：不能报「没有运行中的作业」
            // 把用户卡死在 RUNNING，直接归位成已停止，任务回到可发布的正常状态。
            if ("RUNNING".equals(String.valueOf(row.get("status")))) {
                markStopped(id);
                Map<String, Object> out = new LinkedHashMap<>(find(id));
                out.put("note", "平台记录里没有作业 ID（可能集群重建过），已标记为已停止");
                sessions.audit(user(http), "FLINK_JOB_STOP", String.valueOf(row.get("code")),
                        "stale-without-job-id");
                return out;
            }
            throw ApiException.conflict("任务没有运行中的作业");
        }
        // 作业在 Flink 上已经不存在时，停止本来就没有对象可停：不能把 404 当失败，
        // 否则任务永远卡在 RUNNING、也无法再发布。
        boolean gone = false;
        boolean terminal = false;
        try {
            String state = String.valueOf(flink.jobStatus(String.valueOf(jobId)).get("state"));
            gone = "GONE".equals(state);
            terminal = TERMINAL_STATES.contains(state);
        } catch (RuntimeException ignored) {
            // 查询失败不阻断取消：交给 cancel 的异常决定是否真失败
        }
        // 并发停止只有一个请求能拿到这次「句柄清零」：其余直接 409，
        // 不会出现两个 200（审计 P2-6）。
        int claimed = jdbc.update("""
                update platform.flink_sql_job
                   set status = 'STOPPED', stopped_at = now(),
                       last_flink_job_id = coalesce(last_flink_job_id, flink_job_id),
                       last_job_at = coalesce(last_job_at, submitted_at),
                       flink_job_id = null, session_handle = null, restarting_since = null,
                       updated_at = now()
                 where id = ? and tenant_id = ? and flink_job_id = ?
                """, id, TenantScope.current(), String.valueOf(jobId));
        if (claimed == 0) {
            throw ApiException.conflict("任务没有运行中的作业");
        }
        if (!gone && !terminal) {
            try {
                flink.cancel(String.valueOf(jobId));
            } catch (RuntimeException e) {
                if (e instanceof ApiException api && api.getStatus() == 404) {
                    gone = true;
                } else {
                    // 远端没停下来就不能报「已停止」：否则用户以为停了、任务还能再次运行，
                    // 实际 Flink 上还在读写。回滚状态与句柄，下一轮对账继续兜底。
                    rollbackStop(id, row, String.valueOf(jobId), session);
                    sessions.audit(user(http), "FLINK_JOB_STOP", String.valueOf(row.get("code")),
                            "cancel failed: " + e.getMessage());
                    throw new ApiException(502, "取消远端作业失败，作业仍在运行：" + e.getMessage());
                }
            }
        }
        if (!"SHARED".equals(String.valueOf(row.get("session_mode")))) {
            flink.closeSession(session);
        }
        Map<String, Object> out = new LinkedHashMap<>(find(id));
        if (gone) {
            out.put("note", "作业在 Flink 上已不存在（可能集群重启或被回收），已标记为已停止");
        }
        sessions.audit(user(http), "FLINK_JOB_STOP", String.valueOf(row.get("code")),
                "job=" + jobId + (gone ? " (gone)" : ""));
        return out;
    }

    /** 撤销 stop() 的「句柄清零」：只有这次请求占位成功（句柄仍为空）时才回滚。 */
    private void rollbackStop(long id, Map<String, Object> row, String jobId, String session) {
        String previous = row.get("status") == null ? "RUNNING" : String.valueOf(row.get("status"));
        jdbc.update("""
                update platform.flink_sql_job
                   set status = ?, flink_job_id = ?, session_handle = ?, stopped_at = null,
                       updated_at = now()
                 where id = ? and tenant_id = ? and flink_job_id is null and status = 'STOPPED'
                """, previous, jobId, session, id, TenantScope.current());
    }

    /** 把 DEV 侧状态归位成已停止：清掉 job/session 句柄，任务回到可重新运行/发布的状态。 */
    private void markStopped(long id) {
        jdbc.update("""
                update platform.flink_sql_job
                   set last_flink_job_id = coalesce(last_flink_job_id, flink_job_id),
                       last_job_at = coalesce(last_job_at, submitted_at),
                       status = 'STOPPED', stopped_at = now(), flink_job_id = null,
                       session_handle = null, restarting_since = null, updated_at = now()
                 where id = ? and tenant_id = ?
                """, id, TenantScope.current());
    }

    /**
     * 停止后仍要能看指标/日志/DAG：优先当前作业，其次当前线上作业，最后最近一次
     * 提交过的作业。返回 {jobId, history, env}；env=PROD 时调用方要在线上集群上下文里读。
     */
    private Map<String, Object> readableJob(Map<String, Object> row) {
        Object current = row.get("flink_job_id");
        if (current != null) {
            return Map.of("jobId", String.valueOf(current), "history", false, "env", "DEV");
        }
        Object prod = row.get("prod_job_id");
        if (prod != null) {
            return Map.of("jobId", String.valueOf(prod), "history", false, "env", "PROD");
        }
        Object last = row.get("last_flink_job_id");
        if (last != null) {
            return Map.of("jobId", String.valueOf(last), "history", true, "env", "DEV");
        }
        return Map.of();
    }

    private String trim(String value) {
        return value == null ? null : value.length() > 4000 ? value.substring(0, 4000) : value;
    }

    /** 刷新运行状态：只读接口，但会顺手把库里状态对齐 Flink。 */
    @GetMapping("/{id}/status")
    @RequireAtom("query:run")
    public Map<String, Object> status(@PathVariable long id) {
        Map<String, Object> row = find(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("code", row.get("code"));
        out.put("jobId", row.get("flink_job_id"));
        out.put("status", row.get("status"));
        Object jobId = row.get("flink_job_id");
        if (jobId == null) {
            // v450：停止/失败后句柄被清空，但「远端最终是什么状态」仍然可查——
            // 用 last_flink_job_id 回看，前端才能证明是被 CANCELED 而不是凭空消失。
            Object last = row.get("last_flink_job_id");
            if (last != null) {
                try {
                    Map<String, Object> remote = flink.jobStatus(String.valueOf(last));
                    out.put("remoteState", remote.get("state"));
                    out.put("history", true);
                } catch (RuntimeException e) {
                    out.put("remoteState", null);
                }
            } else {
                out.put("remoteState", null);
            }
            out.put("error", row.get("last_error"));
            return out;
        }
        Map<String, Object> remote;
        try {
            remote = flink.jobStatus(String.valueOf(jobId));
        } catch (RuntimeException e) {
            out.put("remoteState", null);
            out.put("error", "暂时连不上 Flink：" + e.getMessage());
            return out;
        }
        String remoteState = String.valueOf(remote.get("state"));
        String mapped = mapState(remoteState);
        String error = "";
        if ("FAILED".equals(mapped)) {
            error = flink.jobException(String.valueOf(jobId));
        }
        if ("GONE".equals(remoteState)) {
            // 作业已经不在集群上了：platform 不能一直显示 RUNNING，清掉句柄并标记已停止
            markStopped(id);
            // v450 P2-3：外部取消/回收与「用户点停止」要能区分，审计里留一条系统记录
            sessions.audit("system", "FLINK_JOB_EXTERNAL_STOP", "s" + id,
                    "作业在 Flink 上消失（可能被外部取消或集群回收）");
            out.put("status", "STOPPED");
            out.put("remoteState", remoteState);
            out.put("error", null);
            out.put("note", "作业在 Flink 上已不存在（可能集群重启或被回收），已标记为已停止");
            return out;
        }
        // v450 P2-1：只有拿到了非空错误才覆盖 last_error。以前一次「瞬时 RUNNING」
        // 的刷新会把「正在重启/失败原因」刷成 null，用户刷新一次线索就没了。
        if (!mapped.equals(String.valueOf(row.get("status")))) {
            jdbc.update("""
                    update platform.flink_sql_job
                       set status = ?, last_error = coalesce(?, last_error),
                           restarting_since = case when ? then restarting_since else null end,
                           updated_at = now()
                     where id = ? and tenant_id = ?
                    """, mapped, error == null || error.isBlank() ? null : error,
                    "RESTARTING".equals(mapped), id, TenantScope.current());
        } else if (error != null && !error.isBlank()
                && !error.equals(String.valueOf(row.get("last_error")))) {
            jdbc.update("""
                    update platform.flink_sql_job
                       set last_error = ?, updated_at = now()
                     where id = ? and tenant_id = ?
                    """, error, id, TenantScope.current());
        }
        out.put("status", mapped);
        out.put("remoteState", remoteState);
        out.put("name", remote.get("name"));
        out.put("duration", remote.get("duration"));
        out.put("startTime", remote.get("startTime"));
        out.put("error", error);
        return out;
    }

    /** 作业日志：Flink 异常 + TaskManager 输出（print sink 的 +I[...] 行）。 */
    @GetMapping("/{id}/logs")
    @RequireAtom("query:run")
    public Map<String, Object> logs(@PathVariable long id,
                                    @RequestParam(defaultValue = "300") int lines) {
        Map<String, Object> row = find(id);
        Map<String, Object> readable = readableJob(row);
        if (readable.isEmpty()) {
            return Map.of("jobId", "", "logs", "任务还没有提交过，暂无日志", "history", false);
        }
        int limit = Math.max(20, Math.min(2000, lines));
        String jobId = String.valueOf(readable.get("jobId"));
        boolean prod = "PROD".equals(String.valueOf(readable.get("env")));
        String logs = onEnv(readable, () -> flink.jobLogs(jobId, limit, prod));
        return Map.of(
                "jobId", jobId,
                "logs", logs,
                "history", readable.get("history"),
                "env", readable.get("env"));
    }

    @GetMapping("/{id}/graph")
    @RequireAtom("query:run")
    public Map<String, Object> graph(@PathVariable long id) {
        Map<String, Object> row = find(id);
        Map<String, Object> readable = readableJob(row);
        if (readable.isEmpty()) {
            throw ApiException.conflict("任务还没有提交过，暂无 DAG");
        }
        String jobId = String.valueOf(readable.get("jobId"));
        Map<String, Object> out = new LinkedHashMap<>(onEnv(readable, () -> flink.jobGraph(jobId)));
        out.put("history", readable.get("history"));
        out.put("env", readable.get("env"));
        return out;
    }

    @GetMapping("/{id}/metrics")
    @RequireAtom("query:run")
    public Map<String, Object> metrics(@PathVariable long id) {
        Map<String, Object> row = find(id);
        Map<String, Object> readable = readableJob(row);
        if (readable.isEmpty()) {
            throw ApiException.conflict("任务还没有提交过，暂无指标");
        }
        String jobId = String.valueOf(readable.get("jobId"));
        Map<String, Object> out = new LinkedHashMap<>(onEnv(readable, () -> flink.jobMetrics(jobId)));
        out.put("history", readable.get("history"));
        out.put("env", readable.get("env"));
        return out;
    }

    /** 读作业数据时按 readableJob 给出的环境切换 DEV / PROD 集群。 */
    private <T> T onEnv(Map<String, Object> readable, java.util.function.Supplier<T> action) {
        return "PROD".equals(String.valueOf(readable.get("env"))) ? flink.asProd(action) : action.get();
    }

    // ---------------------------------------------------------------- 目录树

    @GetMapping("/projects/{projectId}/flink-folders")
    @RequireAtom("query:run")
    public List<Map<String, Object>> folders(@PathVariable long projectId) {
        requireProject(projectId);
        return jdbc.queryForList("""
                select id, path, created_at from platform.flink_folder
                 where project_id = ? and tenant_id = ? order by path
                """, projectId, TenantScope.current());
    }

    @PostMapping("/projects/{projectId}/flink-folders")
    @RequireAtom("flink:write")
    public Map<String, Object> createFolder(@PathVariable long projectId,
                                            @RequestBody FolderRequest req) {
        requireProject(projectId);
        String path = requireFolder(req == null ? null : req.path());
        if (path.isBlank()) {
            throw ApiException.badRequest("目录不能为空");
        }
        jdbc.update("""
                insert into platform.flink_folder (project_id, path, tenant_id)
                values (?, ?, ?) on conflict (project_id, path) do nothing
                """, projectId, path, TenantScope.current());
        return Map.of("ok", true, "path", path);
    }

    private void touchFolder(long projectId, String folder) {
        if (folder == null || folder.isBlank()) {
            return;
        }
        jdbc.update("""
                insert into platform.flink_folder (project_id, path, tenant_id)
                values (?, ?, ?) on conflict (project_id, path) do nothing
                """, projectId, folder, TenantScope.current());
    }

    /** 目录是相对路径：a/b；每段只允许字母数字中文下划线短横线，不允许 .. 和空段。 */
    private String requireFolder(String value) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty()) {
            return "";
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw ApiException.badRequest("目录格式不合法: " + path);
        }
        if (path.length() > 512) {
            throw ApiException.badRequest("目录过长");
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || "..".equals(segment) || ".".equals(segment)
                    || !segment.matches("[A-Za-z0-9_\u4e00-\u9fa5-]{1,64}")) {
                throw ApiException.badRequest("目录段不合法: " + segment);
            }
        }
        return path;
    }

    // ---------------------------------------------------------------- DEV 跑通 / 上线

    @PostMapping("/{id}/smoke")
    @RequireAtom("flink:run")
    public Map<String, Object> smoke(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        String sql = requireSql(sqlOf(row), true);
        String code = String.valueOf(row.get("code"));
        try {
            Map<String, Object> result = flink.submitAndObserve(withRuntimeConfig(sql, row));
            jdbc.update("""
                    update platform.flink_sql_job
                       set smoke_hash = ?, smoke_at = now(), smoke_by = ?, updated_at = now()
                     where id = ? and tenant_id = ?
                    """, hash(sql), user(http), id, TenantScope.current());
            sessions.audit(user(http), "FLINK_JOB_DEV_RUN", code, "job=" + result.get("jobId"));
            return result;
        } catch (RuntimeException e) {
            jdbc.update("""
                    update platform.flink_sql_job
                       set smoke_hash = null, smoke_at = null, smoke_by = null
                     where id = ? and tenant_id = ?
                    """, id, TenantScope.current());
            sessions.audit(user(http), "FLINK_JOB_DEV_RUN", code, "failed: " + e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{id}/publish")
    @RequireAtom("flink:publish")
    public Map<String, Object> publish(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        String sql = requireSql(sqlOf(row), true);
        Object smokeHash = row.get("smoke_hash");
        if (smokeHash == null) {
            throw ApiException.conflict("还没有在 DEV 跑通，不能发布");
        }
        if (!hash(sql).equals(String.valueOf(smokeHash))) {
            throw ApiException.conflict("SQL 在 DEV 跑通后又改过，请重新跑通再发布");
        }
        Integer max = jdbc.queryForObject(
                "select coalesce(max(version), 0) from platform.flink_sql_release where job_id = ?",
                Integer.class, id);
        int version = (max == null ? 0 : max) + 1;
        jdbc.update("""
                insert into platform.flink_sql_release
                    (job_id, version, sql_text, session_mode, created_by)
                values (?, ?, ?, ?, ?)
                """, id, version, sql,
                row.get("session_mode") == null ? "ISOLATED" : row.get("session_mode"),
                user(http));
        jdbc.update("""
                update platform.flink_sql_job
                   set published_version = ?, published_at = now(), published_by = ?,
                       updated_at = now()
                 where id = ? and tenant_id = ?
                """, version, user(http), id, TenantScope.current());
        sessions.audit(user(http), "FLINK_JOB_PUBLISH", String.valueOf(row.get("code")),
                "v" + version);
        // v450 P2-5：发布只生成版本快照，真正提交到 PROD 是 prod/up；响应里说清楚
        return Map.of("ok", true, "version", version,
                "note", "已生成生产版本快照；提交/启动作业请用「上线」(prod/up)");
    }

    /**
     * 历史发布版本：按版本倒序，{@code current} 标出当前线上快照版本。
     *
     * <p>BUG-TC6-3：以前只有 insert 新版本 + 取最新一条，发布后看不到历史版本，
     * 也就无从回滚。列表只带 SQL 预览（200 字符），不把整份脚本塞进响应。</p>
     */
    @GetMapping("/{id}/releases")
    @RequireAtom("query:run")
    public List<Map<String, Object>> releases(@PathVariable long id) {
        find(id); // 租户/项目归属校验：by-id 读也不能跨租户
        return jdbc.queryForList("""
                select r.version, r.session_mode, r.created_by, r.created_at,
                       length(r.sql_text) as sql_length,
                       left(r.sql_text, 200) as sql_preview,
                       (r.version = j.published_version) as current
                  from platform.flink_sql_release r
                  join platform.flink_sql_job j on j.id = r.job_id
                 where r.job_id = ? and j.tenant_id = ?
                 order by r.version desc
                """, id, TenantScope.current());
    }

    /**
     * 按历史版本回滚：以旧快照内容生成一个新版本并切换发布标记。
     *
     * <p>回滚的是历史上已经发布过的版本，所以<b>跳过</b>「必须先在 DEV 跑通」的闸门——
     * 与调度工作流 rollback 同一口径（回滚内容早已验证过）。新版本号 = max+1，
     * 不覆盖历史，旧版本永远可查。</p>
     *
     * <p>只改快照与草稿，<b>不自动重启线上作业</b>：启停归 /prod/up 与 /prod/stop。
     * 线上有作业时返回 {@code prodRestartRequired=true}，提示先停再上线。</p>
     */
    @PostMapping("/{id}/rollback/{version}")
    @RequireAtom("flink:publish")
    public Map<String, Object> rollback(@PathVariable long id, @PathVariable int version,
                                        HttpServletRequest http) {
        Map<String, Object> row = find(id);
        List<Map<String, Object>> found = jdbc.queryForList("""
                select version, sql_text, session_mode
                  from platform.flink_sql_release
                 where job_id = ? and version = ?
                """, id, version);
        if (found.isEmpty()) {
            throw ApiException.notFound("版本不存在: v" + version);
        }
        Map<String, Object> old = found.get(0);
        String oldSql = String.valueOf(old.get("sql_text"));
        String mode = old.get("session_mode") == null
                ? "ISOLATED" : String.valueOf(old.get("session_mode"));
        Integer max = jdbc.queryForObject(
                "select coalesce(max(version), 0) from platform.flink_sql_release where job_id = ?",
                Integer.class, id);
        int next = (max == null ? 0 : max) + 1;
        try {
            jdbc.update("""
                    insert into platform.flink_sql_release
                        (job_id, version, sql_text, session_mode, created_by)
                    values (?, ?, ?, ?, ?)
                    """, id, next, oldSql, mode, user(http));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("已有并发发布（版本冲突），请刷新后重试");
        }
        // 草稿同步成回滚内容：否则页面上「已发布 vN」和编辑器里的 SQL 对不上。
        // 不改 smoke_hash——回滚内容与线上快照一致，prod/up 会走「内容未变」reuse 分支。
        jdbc.update("""
                update platform.flink_sql_job
                   set sql_text = ?, session_mode = ?, published_version = ?,
                       published_at = now(), published_by = ?, updated_at = now()
                 where id = ? and tenant_id = ?
                """, oldSql, mode, next, user(http), id, TenantScope.current());
        boolean prodActive = row.get("prod_job_id") != null
                || ACTIVE_PROD_STATES.contains(String.valueOf(row.get("prod_status")));
        sessions.audit(user(http), "FLINK_JOB_ROLLBACK", String.valueOf(row.get("code")),
                "to v" + version + " as v" + next);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("version", next);
        out.put("rolledBackTo", version);
        out.put("prodRestartRequired", prodActive);
        out.put("note", prodActive
                ? "快照已回滚到 v" + version + "（新版本 v" + next
                        + "）；线上作业仍在跑旧内容，请先「停止线上」再「发布线上」"
                : "快照已回滚到 v" + version + "（新版本 v" + next + "）；点「发布线上」即可用旧内容启动");
        return out;
    }

    /**
     * 「上线」一个按钮：必要时生成快照（要求 DEV 跑通指纹一致），然后把线上作业拉起来。
     *
     * <p>页面上不再把「发布」和「启动线上」拆成两个按钮：对使用者来说它们是一个动作——
     * 把当前 SQL 放到线上跑。停止仍走 {@code /prod/stop}，两个方向构成一个开关。</p>
     */
    @PostMapping("/{id}/prod/up")
    @RequireAtom("flink:publish")
    public Map<String, Object> prodUp(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        // 只要线上还有作业句柄（哪怕是 FAILED/RESTARTING 的残留），就不允许再起一个：
        // 两个线上作业写同一张目标表比“停止一次再上线”危险得多。
        if (ACTIVE_PROD_STATES.contains(String.valueOf(row.get("prod_status")))
                || row.get("prod_job_id") != null) {
            throw ApiException.conflict("线上还有作业（状态：" + String.valueOf(row.get("prod_status"))
                    + "），请先「停止线上」再上线");
        }
        String sql = requireSql(sqlOf(row), true);
        List<Map<String, Object>> releases = jdbc.queryForList("""
                select version, sql_text from platform.flink_sql_release
                 where job_id = ? order by version desc limit 1
                """, id);
        boolean reuse = !releases.isEmpty()
                && sql.equals(String.valueOf(releases.get(0).get("sql_text")));
        int version;
        if (reuse) {
            version = ((Number) releases.get(0).get("version")).intValue();
        } else {
            Object smokeHash = row.get("smoke_hash");
            if (smokeHash == null) {
                throw ApiException.conflict("还没有在 DEV 跑通，不能上线");
            }
            if (!hash(sql).equals(String.valueOf(smokeHash))) {
                throw ApiException.conflict("SQL 在 DEV 跑通后又改过，请重新跑通再上线");
            }
            Integer max = jdbc.queryForObject(
                    "select coalesce(max(version), 0) from platform.flink_sql_release where job_id = ?",
                    Integer.class, id);
            version = (max == null ? 0 : max) + 1;
            try {
                jdbc.update("""
                        insert into platform.flink_sql_release
                            (job_id, version, sql_text, session_mode, created_by)
                        values (?, ?, ?, ?, ?)
                        """, id, version, sql,
                        row.get("session_mode") == null ? "ISOLATED" : row.get("session_mode"),
                        user(http));
            } catch (DataIntegrityViolationException e) {
                throw ApiException.conflict("已有并发发布（版本冲突），请刷新后重试");
            }
            jdbc.update("""
                    update platform.flink_sql_job
                       set published_version = ?, published_at = now(), published_by = ?,
                           updated_at = now()
                     where id = ? and tenant_id = ?
                    """, version, user(http), id, TenantScope.current());
        }
        // 线上作业跑独立 PROD 集群；会话独立，避免和 DEV 抢状态
        Map<String, Object> started = flink.asProd(
                () -> flink.submit(withRuntimeConfig(sql, row), false));
        String jobId = String.valueOf(started.get("jobId"));
        String session = started.get("session") == null ? null
                : String.valueOf(started.get("session"));
        int claimed = jdbc.update("""
                update platform.flink_sql_job
                   set prod_job_id = ?, prod_session = ?, prod_status = 'RUNNING',
                       prod_restarting_since = null, updated_at = now()
                 where id = ? and tenant_id = ?
                   and prod_job_id is null
                   and coalesce(prod_status, 'OFFLINE') not in ('RUNNING', 'RESTARTING', 'STARTING', 'STOPPING')
                """, jobId, session, id, TenantScope.current());
        if (claimed == 0) {
            // 必须在 PROD 上下文里取消：出了 asProd 线程切换回 DEV，会取消一个不存在的 DEV 作业，
            // 而刚起在 PROD 的作业成了没人管的孤儿。
            flink.asProd(() -> {
                flink.cancel(jobId);
                flink.closeSession(session);
            });
            throw ApiException.conflict("线上作业已经在运行（可能是重复点击），已取消这次的作业");
        }
        sessions.audit(user(http), "FLINK_JOB_PROD_UP", String.valueOf(row.get("code")),
                "v" + version + " job=" + jobId + (reuse ? " reuse" : " new"));
        return Map.of("ok", true, "version", version, "jobId", jobId, "reused", reuse);
    }

    @PostMapping("/{id}/prod/start")
    @RequireAtom("flink:publish")
    public Map<String, Object> startProd(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        if (ACTIVE_PROD_STATES.contains(String.valueOf(row.get("prod_status")))
                || row.get("prod_job_id") != null) {
            throw ApiException.conflict("线上还有作业（状态：" + String.valueOf(row.get("prod_status"))
                    + "），请先「停止线上」再启动");
        }
        List<Map<String, Object>> releases = jdbc.queryForList("""
                select version, sql_text, session_mode from platform.flink_sql_release
                 where job_id = ? order by version desc limit 1
                """, id);
        if (releases.isEmpty()) {
            throw ApiException.conflict("还没有发布过，不能启动线上作业");
        }
        Map<String, Object> release = releases.get(0);
        Map<String, Object> started = flink.asProd(() -> flink.submit(
                withRuntimeConfig(String.valueOf(release.get("sql_text")), row), false));
        String jobId = String.valueOf(started.get("jobId"));
        String session = started.get("session") == null ? null
                : String.valueOf(started.get("session"));
        int claimed = jdbc.update("""
                update platform.flink_sql_job
                   set prod_job_id = ?, prod_session = ?, prod_status = 'RUNNING',
                       prod_restarting_since = null, updated_at = now()
                 where id = ? and tenant_id = ?
                   and prod_job_id is null
                   and coalesce(prod_status, 'OFFLINE') not in ('RUNNING', 'RESTARTING', 'STARTING', 'STOPPING')
                """, jobId, session, id, TenantScope.current());
        if (claimed == 0) {
            flink.asProd(() -> {
                flink.cancel(jobId);
                flink.closeSession(session);
            });
            throw ApiException.conflict("线上作业已经在运行（可能是重复点击），已取消这次的作业");
        }
        sessions.audit(user(http), "FLINK_JOB_PROD_START", String.valueOf(row.get("code")),
                "job=" + jobId);
        return Map.of("ok", true, "jobId", jobId, "version", release.get("version"));
    }

    @PostMapping("/{id}/prod/stop")
    @RequireAtom("flink:run")
    public Map<String, Object> stopProd(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        Object jobId = row.get("prod_job_id");
        if (jobId == null) {
            // 平台记着 RUNNING 但没有作业 ID / 作业已经被回收：归位成已停止，别让用户卡死
            if ("RUNNING".equals(String.valueOf(row.get("prod_status")))) {
                markProdStopped(id);
                sessions.audit(user(http), "FLINK_JOB_PROD_STOP",
                        String.valueOf(row.get("code")), "stale-without-job-id");
                return Map.of("ok", true, "note", "平台记录里没有线上作业 ID，已标记为已停止");
            }
            throw ApiException.conflict("线上作业没有运行");
        }
        boolean gone = false;
        boolean terminal = false;
        try {
            String state = String.valueOf(flink.asProd(
                    () -> flink.jobStatus(String.valueOf(jobId))).get("state"));
            gone = "GONE".equals(state);
            terminal = TERMINAL_STATES.contains(state);
        } catch (RuntimeException ignored) {
            // 查询失败不阻断取消，交给 cancel 判断
        }
        if (!gone && !terminal) {
            try {
                flink.asProd(() -> flink.cancel(String.valueOf(jobId)));
            } catch (ApiException e) {
                if (e.getStatus() != 404) {
                    throw e;
                }
                gone = true;
            }
        }
        if (row.get("prod_session") != null) {
            flink.asProd(() -> flink.closeSession(String.valueOf(row.get("prod_session"))));
        }
        markProdStopped(id);
        sessions.audit(user(http), "FLINK_JOB_PROD_STOP", String.valueOf(row.get("code")),
                "job=" + jobId + (gone ? " (gone)" : ""));
        return gone
                ? Map.of("ok", true, "note", "线上作业在 Flink 上已不存在，已标记为已停止")
                : Map.of("ok", true);
    }

    private void markProdStopped(long id) {
        jdbc.update("""
                update platform.flink_sql_job
                   set prod_status = 'STOPPED', prod_job_id = null, prod_session = null,
                       prod_restarting_since = null, updated_at = now()
                 where id = ? and tenant_id = ?
                """, id, TenantScope.current());
    }

    @GetMapping("/{id}/prod/status")
    @RequireAtom("query:run")
    public Map<String, Object> prodStatus(@PathVariable long id) {
        Map<String, Object> row = find(id);
        Object jobId = row.get("prod_job_id");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", row.get("prod_status"));
        out.put("jobId", jobId);
        if (jobId != null) {
            Map<String, Object> remote;
            try {
                remote = flink.asProd(() -> flink.jobStatus(String.valueOf(jobId)));
            } catch (RuntimeException e) {
                out.put("error", "暂时连不上 Flink（PROD 集群）：" + e.getMessage());
                return out;
            }
            String state = String.valueOf(remote.get("state"));
            if ("GONE".equals(state)) {
                // 线上作业已经被回收：清掉句柄，状态归位成已停止，允许重新上线
                markProdStopped(id);
                out.put("status", "STOPPED");
                out.put("jobId", null);
                out.put("remoteState", state);
                out.put("note", "线上作业在 Flink 上已不存在，已标记为已停止");
                return out;
            }
            // 与 DEV 同口径：重启中不能折叠成 RUNNING，否则线上无限重启时页面一直「运行中」
            String mapped = switch (state) {
                case "RESTARTING", "RECONCILING" -> "RESTARTING";
                case "CREATED", "INITIALIZING", "RUNNING" -> "RUNNING";
                case "FAILED", "SUSPENDED" -> "FAILED";
                default -> "STOPPED";
            };
            if (!mapped.equals(String.valueOf(row.get("prod_status")))) {
                jdbc.update("""
                        update platform.flink_sql_job
                           set prod_status = ?,
                               prod_restarting_since = case when ? then prod_restarting_since
                                                            else null end,
                               updated_at = now()
                         where id = ? and tenant_id = ?
                        """, mapped, "RESTARTING".equals(mapped), id, TenantScope.current());
            }
            out.put("status", mapped);
            out.put("remoteState", state);
        }
        return out;
    }

    private String hash(String sql) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new ApiException(500, "计算 SQL 指纹失败");
        }
    }

    private String mapState(String remoteState) {
        // v450 P1-2：RESTARTING/RECONCILING 以前被折叠成 RUNNING，作业无限重启时面板
        // 一直显示「运行中」。初始化（CREATED/INITIALIZING）也不算 RUNNING。
        if ("RESTARTING".equals(remoteState) || "RECONCILING".equals(remoteState)) {
            return "RESTARTING";
        }
        if ("CREATED".equals(remoteState) || "INITIALIZING".equals(remoteState)) {
            return "STARTING";
        }
        if ("CANCELLING".equals(remoteState) || "CANCELING".equals(remoteState)) {
            return "STOPPING";
        }
        if ("RUNNING".equals(remoteState)) {
            return "RUNNING";
        }
        return switch (remoteState) {
            case "FINISHED", "CANCELED", "GONE" -> "STOPPED";
            case "FAILED", "SUSPENDED" -> "FAILED";
            default -> "RUNNING";
        };
    }

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select j.id, j.project_id, p.name as project_name, p.code as project_code,
                       j.code, j.name, j.description, j.sql_text, j.folder, j.env, j.status,
                       j.flink_job_id, j.last_flink_job_id, j.last_job_at,
                       j.session_mode, j.parallelism, j.consumer_group,
                       j.session_handle, j.smoke_hash, j.smoke_at, j.smoke_by,
                       j.validate_hash, j.validate_at, j.validate_by,
                       j.published_version, j.published_at,
                       j.published_by, j.prod_status, j.prod_job_id, j.last_error,
                       j.submitted_at,
                       j.stopped_at,
                       j.created_by, j.created_at, j.updated_at
                  from platform.flink_sql_job j
                  join platform.project p on p.id = j.project_id
                 where j.id = ? and p.tenant_id = ?
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("流任务不存在: " + id);
        }
        return rows.get(0);
    }

    private void requireProject(long projectId) {
        Long count = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, TenantScope.current());
        if (count == null || count == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private String requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw ApiException.badRequest("任务编码必须以字母开头，只含字母、数字、下划线，长度 2-64");
        }
        return code;
    }

    private String requireName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("任务名称不能为空");
        }
        requireClean(value, "任务名称");
        if (value.length() > NAME_MAX) {
            throw ApiException.badRequest("任务名称最长 " + NAME_MAX + " 个字符");
        }
        return value;
    }

    private String requireSql(String sql, boolean required) {
        String value = sql == null ? "" : sql;
        requireClean(value, "SQL");
        if (required && value.isBlank()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        if (value.length() > SQL_MAX) {
            throw ApiException.badRequest("SQL 超过 " + SQL_MAX + " 字符上限");
        }
        return value;
    }

    /** 控制字符（尤其 NUL）会让 PostgreSQL 直接拒绝，按「输入非法」返回 400。 */
    private void requireClean(String value, String label) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || (c < 0x20 && c != 10 && c != 13 && c != 9)) {
                throw ApiException.badRequest(label + " 含非法控制字符");
            }
        }
    }

    private String envOf(String value) {
        String env = value == null || value.isBlank() ? "DEV" : value.trim().toUpperCase();
        if (!Set.of("DEV", "PROD").contains(env)) {
            throw ApiException.badRequest("环境只能是 DEV / PROD");
        }
        return env;
    }

    private String sessionMode(String value) {
        String mode = value == null ? "ISOLATED" : value.trim().toUpperCase();
        if (!Set.of("ISOLATED", "SHARED").contains(mode)) {
            throw ApiException.badRequest("会话模式只支持 ISOLATED / SHARED");
        }
        return mode;
    }

    private String sqlOf(Map<String, Object> row) {
        Object value = row.get("sql_text");
        return value == null ? "" : String.valueOf(value);
    }

    private int parallelism(Integer value) {
        int n = value == null ? 1 : value;
        if (n < 1 || n > 128) {
            throw ApiException.badRequest("并行度必须在 1-128 之间");
        }
        return n;
    }

    private String consumerGroup(String value) {
        String group = value == null ? "" : value.trim();
        if (group.isEmpty()) {
            return null;
        }
        if (group.length() > 249) {
            throw ApiException.badRequest("消费组名称最长 249 个字符");
        }
        return group;
    }

    /**
     * 运行时把任务级配置拼进 SQL：消费组占位符 + 并行度。
     *
     * <p>${KAFKA_GROUP_ID} 让脚本按环境运行（DEV/PROD 用不同消费组），库里不落具体组名；
     * 脚本里没写占位符就不动 SQL，避免改写用户的 WITH 子句。</p>
     */
    private String withRuntimeConfig(String sql, Map<String, Object> row) {
        String out = sql;
        if (out.contains("${KAFKA_GROUP_ID}")) {
            Object raw = row.get("consumer_group");
            String group = raw == null ? "" : String.valueOf(raw).trim();
            if (group.isEmpty()) {
                throw ApiException.badRequest(
                        "SQL 里用了 ${KAFKA_GROUP_ID}，但任务没有配置「消费组」，请先在右侧配置里填写");
            }
            out = out.replace("${KAFKA_GROUP_ID}", group);
        }
        String ready = withParallelism(out, row);
        // UDF：脚本里引用到的已发布函数 → 注入 pipeline.jars + CREATE TEMPORARY FUNCTION。
        // 未发布 / 其他项目的函数在这里直接给中文提示，不让 Flink 抛 ClassNotFound。
        Object rawProject = row.get("project_id");
        if (rawProject instanceof Number projectId) {
            udf.checkUsage(projectId.longValue(), ready);
            String preamble = udf.flinkPreamble(projectId.longValue(), ready);
            if (!preamble.isEmpty()) {
                return preamble + ready;
            }
        }
        return ready;
    }

    /**
     * 运行时把任务的并行度拼到 SQL 前面。
     * 脚本里自己写了 {@code parallelism.default} 就以脚本为准，不覆盖用户的手写设置。
     */
    private String withParallelism(String sql, Map<String, Object> row) {
        Object raw = row.get("parallelism");
        int n = raw instanceof Number number ? number.intValue() : 1;
        if (n <= 1 || sql.toLowerCase().contains("parallelism.default")) {
            return sql;
        }
        return "SET 'parallelism.default' = '" + n + "';\n" + sql;
    }

    private String user(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }
}
