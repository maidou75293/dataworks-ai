package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.lineage.LineageService;
import com.lab.dataworks.prefect.PrefectBridgeClient;
import com.lab.dataworks.prefect.PrefectFlowRenderer;
import com.lab.dataworks.prefect.PrefectPublisher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import java.sql.Timestamp;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final PrefectFlowRenderer renderer;
    private final PrefectPublisher publisher;
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final LineageService lineage;
    private final WorkflowMutationService mutations;
    private final WorkflowValidationService validation;
    private final WorkflowTriggerService triggers;
    private final BackfillService backfills;

    public WorkflowController(JdbcTemplate jdbc, PrefectFlowRenderer renderer, PrefectPublisher publisher,
                              SessionService sessions, ObjectMapper mapper, LineageService lineage,
                              WorkflowMutationService mutations, WorkflowValidationService validation,
                              WorkflowTriggerService triggers, BackfillService backfills) {
        this.jdbc = jdbc;
        this.renderer = renderer;
        this.publisher = publisher;
        this.sessions = sessions;
        this.mapper = mapper;
        this.lineage = lineage;
        this.mutations = mutations;
        this.validation = validation;
        this.triggers = triggers;
        this.backfills = backfills;
    }

    /**
     * revision 是乐观锁：客户端提交加载时拿到的版本号，服务端不匹配就返回 409，
     * 避免两个人同时编辑时后保存的人静默覆盖前一个人的改动。
     * force=true 表示用户已在冲突提示里选择覆盖。
     */
    public record WorkflowRequest(String code, String name, String env, String description, Object model,
                                  String folder, Long revision, Boolean force) {
    }

    public record FolderRequest(String path) {
    }

    /** 文件夹移动：from 是原路径，to 是目标父文件夹（空字符串表示项目根）。 */
    public record FolderMoveRequest(String from, String to) {
    }

    public record VersionRequest(Object model, String changeNote) {
    }

    public record EnvRequest(String env) {
    }

    public record ScheduleRequest(String env, String cron, String status, String startDate, String endDate,
                                  String scheduleType, String runTime, String weekdays,
                                  String monthDays, String startAt, String timezone) {
    }

    /** cron 用于展示与兼容；rrule 用于一次性 / 带起止时间的高级调度。 */
    private record ScheduleSpec(String cron, String rrule) {
    }

    public record TriggerRequest(String env, String bizDate, Map<String, Object> params) {
    }

    /**
     * 补数请求。任务单用 {@code dateStart/dateEnd/parallelism}，旧版运维页用
     * {@code startDate/endDate}；两套字段都接受，避免升级时前端必须同版本发布。
     */
    public record BackfillRequest(String env, String dateStart, String dateEnd,
                                  String startDate, String endDate, Integer parallelism,
                                  Map<String, Object> params) {

        public String resolvedStart() {
            return dateStart != null && !dateStart.isBlank() ? dateStart : startDate;
        }

        public String resolvedEnd() {
            return dateEnd != null && !dateEnd.isBlank() ? dateEnd : endDate;
        }
    }

    /**
     * 项目下的工作流列表。
     *
     * <p>v448（第二轮审计 P2-2）：之前不接受任何过滤/分页参数，大数据量下只能全量拉。
     * 现在支持 {@code keyword / env / folder / page / size}，响应仍是数组（不破坏前端），
     * 总条数放 {@code X-Total-Count} 响应头。</p>
     */
    @GetMapping("/projects/{projectId}/workflows")
    public List<Map<String, Object>> list(@PathVariable long projectId,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String env,
                                          @RequestParam(required = false) String folder,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          jakarta.servlet.http.HttpServletResponse response) {
        requireProject(projectId);
        StringBuilder sql = new StringBuilder("""
                select w.id, w.project_id, w.code, w.name, w.env, w.description, w.folder, w.revision,
                       w.model_json::text as model, w.created_at, w.updated_at,
                       (select count(*) from platform.workflow_version v where v.workflow_id = w.id) as version_count,
                       (select count(*) from platform.wf_instance i where i.workflow_id = w.id) as instance_count,
                       (select max(d.version_no) from platform.workflow_deployment d
                         where d.workflow_id = w.id and d.env = 'PROD'
                           and d.active and d.prefect_deployment_id is not null) as prod_version,
                       (select max(d.deployed_at) from platform.workflow_deployment d
                         where d.workflow_id = w.id and d.env = 'PROD'
                           and d.active and d.prefect_deployment_id is not null) as prod_published_at,
                       (select max(v.version_no) from platform.workflow_version v
                         where v.workflow_id = w.id
                           and v.prod_status = 'PENDING_APPROVAL') as prod_pending_version,
                       -- 「DEV 跑通」闸门（requireDevSuccess）的可见化：最近一次发布到 DEV 的
                       -- 版本上有没有成功运行。上线前先在列表里给出结论，别等点完才 400。
                       (select max(d.version_no) from platform.workflow_deployment d
                         where d.workflow_id = w.id and d.env = 'DEV'
                           and d.prefect_deployment_id is not null) as dev_version,
                       (select count(*) from platform.wf_instance i
                         join platform.workflow_deployment d on d.prefect_deployment_id = i.dag_id
                        where i.workflow_id = w.id and d.env = 'DEV' and i.state = 'success'
                          and d.version_no = (select max(d2.version_no)
                                                from platform.workflow_deployment d2
                                               where d2.workflow_id = w.id and d2.env = 'DEV'
                                                 and d2.prefect_deployment_id is not null))
                         as dev_success_count
                from platform.workflow w
                join platform.project p on p.id = w.project_id
                where w.project_id = ? and w.tenant_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        args.add(com.lab.dataworks.auth.TenantScope.current());
        // 过滤条件单独拼，列表与 count 共用，避免两处口径漂移
        StringBuilder filters = new StringBuilder();
        if (keyword != null && !keyword.isBlank()) {
            filters.append(" and (w.code ilike ? or w.name ilike ?)");
            String like = com.lab.dataworks.common.LikePattern.contains(keyword);
            args.add(like);
            args.add(like);
        }
        if (env != null && !env.isBlank()) {
            filters.append(" and w.env = ?");
            args.add(env.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (folder != null && !folder.isBlank()) {
            filters.append(" and w.folder = ?");
            args.add(folder.trim());
        }
        sql.append(filters).append(" order by w.id");
        // v449 P2：page 从 1 开始；size 必须在 1–500。以前 page 从 0 起、size=0 被
        // 悄悄改成 1 行，客户端拿到的「第 N 页」其实是第 N+1 页。
        int limit = 0;
        if (size != null) {
            if (size < 1 || size > 500) {
                throw ApiException.badRequest("size 取值 1–500");
            }
            int pageNo = page == null ? 1 : page;
            if (pageNo < 1) {
                throw ApiException.badRequest("page 从 1 开始");
            }
            limit = size;
            sql.append(" limit ? offset ?");
            args.add(limit);
            // D12：offset 用 long，page=2147483647 不再 int 溢出成 409「数据冲突」
            args.add(com.lab.dataworks.common.Paging.offset(pageNo, limit));
            // 带分页时回总条数，前端不用再拉一次全量
            Long total = jdbc.queryForObject("""
                    select count(*) from platform.workflow w
                     where w.project_id = ? and w.tenant_id = ?""" + filters, Long.class,
                    args.subList(0, args.size() - 2).toArray());
            response.setHeader("X-Total-Count", String.valueOf(total == null ? 0 : total));
        }
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping("/projects/{projectId}/workflows")
    @RequireAtom("workflow:create")
    public Map<String, Object> create(@PathVariable long projectId, @RequestBody WorkflowRequest req,
                                      HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.code() == null || !req.code().matches("[a-zA-Z][a-zA-Z0-9_]{1,63}")) {
            throw ApiException.badRequest("工作流编码必须以字母开头，只含字母、数字、下划线");
        }
        String name = requireName(req.name());
        String env = req.env() == null ? "DEV" : req.env().toUpperCase(Locale.ROOT);
        if (!Set.of("DEV", "PROD").contains(env)) {
            throw ApiException.badRequest("环境只能是 DEV 或 PROD");
        }
        // v449 P1-2：model 必须是 JSON 对象且 tasks/edges 是数组。
        // 之前传字符串也能落库，之后 /dag、/versions、/publish 全部 400，工作流报废。
        Object modelInput = req.model() == null
                ? Map.of("tasks", List.of(), "edges", List.of()) : req.model();
        validateModelShape(modelInput);
        String model = toJson(modelInput);
        String folder = normalizeFolder(req.folder());
        // 与项目删除互斥：避免项目刚被删除后插入一个立即级联消失的工作流
        return mutations.withProjectLock(projectId, () -> {
            requireProject(projectId);
            try {
                jdbc.update("""
                        insert into platform.workflow
                            (project_id, code, name, env, description, model_json, folder, tenant_id)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """, projectId, req.code(), name, env, req.description(), model,
                        folder.isEmpty() ? null : folder,
                        com.lab.dataworks.auth.TenantScope.current());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("同一项目下工作流编码已存在: " + req.code());
            }
            registerFolder(projectId, folder);
            Long id = jdbc.queryForObject(
                    "select id from platform.workflow where project_id = ? and code = ?",
                    Long.class, projectId, req.code());
            sessions.audit(user(http), "WORKFLOW_CREATE", "w" + id, "code=" + req.code() + " name=" + name);
            return getWorkflow(id);
        });
    }

    @GetMapping("/workflows/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> wf = getWorkflow(id);
        wf.put("versions", jdbc.queryForList("""
                select id, version_no, status, created_by, created_at, published_at, published_by,
                       prefect_env
                from platform.workflow_version where workflow_id = ? order by version_no desc
                """, id));
        // 每个环境最近一次发布：编辑器据此展示「DEV v3 / PROD v2」
        wf.put("deployments", jdbc.queryForList("""
                select distinct on (env) env, version_no, deployed_at, deployed_by
                from platform.workflow_deployment where workflow_id = ?
                order by env, deployed_at desc
                """, id));
        wf.put("schedule", jdbc.queryForList("""
                select id, env, cron, timezone, status, start_date, end_date,
                       schedule_type, run_time, weekdays, month_days, start_at, updated_at
                from platform.schedule where workflow_id = ? order by env
                """, id));
        return wf;
    }

    @PutMapping("/workflows/{id}")
    @RequireAtom("workflow:update")
    public Map<String, Object> update(@PathVariable long id, @RequestBody WorkflowRequest req,
                                      HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Map<String, Object> wf = getWorkflow(id);
        // 只改模型时允许不传 name；但传了空字符串必须拒绝，不能把名字改成空白
        String name = req.name() == null ? String.valueOf(wf.get("name")) : requireName(req.name());
        String description = req.description() == null ? (String) wf.get("description") : req.description();
        if (req.model() != null) {
            validateModelShape(req.model());
            // 引擎创建后只读：同一任务的 engine 不允许在编辑页被改掉
            WorkflowModelRules.ensureEnginesUnchanged(mapper, String.valueOf(wf.get("model")),
                    req.model());
        }
        String model = req.model() == null ? String.valueOf(wf.get("model")) : toJson(req.model());
        Object currentFolder = wf.get("folder");
        String folder = req.folder() == null
                ? (currentFolder == null ? "" : String.valueOf(currentFolder))
                : normalizeFolder(req.folder());
        int updated;
        if (req.revision() != null && !Boolean.TRUE.equals(req.force())) {
            // 乐观锁：只有客户端加载后没人改过才写入；改过就返回 409 让用户决定是否覆盖
            updated = jdbc.update("""
                    update platform.workflow set name = ?, description = ?, model_json = ?::jsonb,
                           folder = ?, revision = revision + 1, updated_at = now()
                    where id = ? and revision = ? and tenant_id = ?
                    """, name, description, model, folder.isEmpty() ? null : folder,
                    id, req.revision(), com.lab.dataworks.auth.TenantScope.current());
            if (updated == 0) {
                // 不把数据库原始时间戳（UTC + 微秒）透给调用方：界面自己展示本地时间，
                // 接口调用方也用不到这个值。
                throw ApiException.conflict(
                        "工作流已被其他人修改，请刷新后重试，或选择覆盖他人改动");
            }
        } else {
            jdbc.update("""
                    update platform.workflow set name = ?, description = ?, model_json = ?::jsonb,
                           folder = ?, revision = revision + 1, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, name, description, model, folder.isEmpty() ? null : folder, id, com.lab.dataworks.auth.TenantScope.current());
        }
        registerFolder(((Number) wf.get("project_id")).longValue(), folder);
        sessions.audit(user(http), "WORKFLOW_UPDATE", "w" + id, "code=" + wf.get("code"));
        return getWorkflow(id);
    }

    /** 项目内工作流目录：支持多级路径，空目录单独保留。 */
    @GetMapping("/projects/{projectId}/workflow-folders")
    public List<Map<String, Object>> listFolders(@PathVariable long projectId) {
        requireProject(projectId);
        return jdbc.queryForList("""
                select path, created_at from platform.workflow_folder
                where project_id = ? order by path
                """, projectId);
    }

    @PostMapping("/projects/{projectId}/workflow-folders")
    @RequireAtom("workflow:create")
    public Map<String, Object> createFolder(@PathVariable long projectId, @RequestBody FolderRequest req) {
        requireProject(projectId);
        String path = normalizeFolder(req == null ? null : req.path());
        if (path.isEmpty()) {
            throw ApiException.badRequest("目录不能为空");
        }
        registerFolder(projectId, path);
        return Map.of("ok", true, "path", path);
    }

    @DeleteMapping("/projects/{projectId}/workflow-folders")
    @RequireAtom("workflow:delete")
    public Map<String, Object> deleteFolder(@PathVariable long projectId, @RequestParam String path,
                                            HttpServletRequest http) {
        requireProject(projectId);
        String folder = normalizeFolder(path);
        if (folder.isEmpty()) {
            throw ApiException.badRequest("目录不能为空");
        }
        // S12 + S13：删文件夹原先零审计。先记 PENDING 再做，「目录下还有工作流 /
        // 子目录」400 与不存在 404 都回填 FAILED——「谁试过删哪个目录」必须留痕。
        return sessions.auditAround(user(http), "WORKFLOW_FOLDER_DELETE", folder,
                "projectId=" + projectId,
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http), () -> {
                    Integer used = jdbc.queryForObject("""
                            select count(*) from platform.workflow
                            where project_id = ? and (folder = ? or starts_with(folder, ?))
                            """, Integer.class, projectId, folder, folder + "/");
                    if (used != null && used > 0) {
                        throw ApiException.badRequest("目录下还有工作流，请先移动或删除");
                    }
                    Integer children = jdbc.queryForObject("""
                            select count(*) from platform.workflow_folder
                            where project_id = ? and starts_with(path, ?)
                            """, Integer.class, projectId, folder + "/");
                    if (children != null && children > 0) {
                        throw ApiException.badRequest("目录下还有子目录，请先删除子目录");
                    }
                    int deleted = jdbc.update("""
                            delete from platform.workflow_folder where project_id = ? and path = ?
                            """, projectId, folder);
                    if (deleted == 0) {
                        throw ApiException.notFound("目录不存在: " + folder);
                    }
                    return Map.of("ok", true, "path", folder);
                });
    }

    /**
     * 把文件夹移动到另一个文件夹下（空字符串表示项目根）：
     * 文件夹本身、子文件夹和里面的工作流一起搬过去，树不需要重新拉取。
     */
    @PutMapping("/projects/{projectId}/workflow-folders")
    @RequireAtom("workflow:update")
    public Map<String, Object> moveFolder(@PathVariable long projectId, @RequestBody FolderMoveRequest req,
                                          HttpServletRequest http) {
        requireProject(projectId);
        String from = normalizeFolder(req == null ? null : req.from());
        String to = normalizeFolder(req == null ? null : req.to());
        if (from.isEmpty()) {
            throw ApiException.badRequest("要移动的文件夹不能为空");
        }
        // 移到自身或自己的子目录里会把整棵子树弄丢，直接拒绝
        if (from.equals(to) || to.startsWith(from + "/")) {
            throw ApiException.badRequest("不能把文件夹移动到它自己或子文件夹里");
        }
        Integer exists = jdbc.queryForObject("""
                select count(*) from platform.workflow_folder where project_id = ? and path = ?
                """, Integer.class, projectId, from);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("文件夹不存在: " + from);
        }
        String name = from.substring(from.lastIndexOf('/') + 1);
        String dest = to.isEmpty() ? name : to + "/" + name;
        if (dest.equals(from)) {
            // 选中的就是当前所在文件夹：按已经到位处理，不报「同名冲突」
            return Map.of("ok", true, "from", from, "to", dest);
        }
        Integer folderClash = jdbc.queryForObject("""
                select count(*) from platform.workflow_folder
                where project_id = ? and (path = ? or starts_with(path, ?))
                """, Integer.class, projectId, dest, dest + "/");
        Integer workflowClash = jdbc.queryForObject("""
                select count(*) from platform.workflow
                where project_id = ? and (folder = ? or starts_with(folder, ?))
                """, Integer.class, projectId, dest, dest + "/");
        if ((folderClash != null && folderClash > 0)
                || (workflowClash != null && workflowClash > 0)) {
            throw ApiException.badRequest("目标文件夹下已经有同名文件夹: " + dest);
        }
        // 目标父目录先登记成空目录，避免移动后中间层在树里消失
        registerFolder(projectId, to);
        jdbc.update("""
                update platform.workflow_folder
                   set path = ? || substring(path from ?)
                 where project_id = ? and (path = ? or starts_with(path, ?))
                """, dest, from.length() + 1, projectId, from, from + "/");
        jdbc.update("""
                update platform.workflow
                   set folder = ? || substring(folder from ?), updated_at = now()
                 where project_id = ? and (folder = ? or starts_with(folder, ?))
                """, dest, from.length() + 1, projectId, from, from + "/");
        sessions.audit(user(http), "FOLDER_MOVE", from, "to=" + dest);
        return Map.of("ok", true, "from", from, "to", dest);
    }

    /** 把工作流移动到指定目录（空字符串表示根目录）。 */
    @PutMapping("/workflows/{id}/folder")
    @RequireAtom("workflow:update")
    public Map<String, Object> move(@PathVariable long id, @RequestBody FolderRequest req,
                                    HttpServletRequest http) {
        Map<String, Object> wf = getWorkflow(id);
        String folder = normalizeFolder(req == null ? null : req.path());
        long projectId = ((Number) wf.get("project_id")).longValue();
        jdbc.update("""
                update platform.workflow set folder = ?, updated_at = now()
                 where id = ? and tenant_id = ?
                """, folder.isEmpty() ? null : folder, id, com.lab.dataworks.auth.TenantScope.current());
        registerFolder(projectId, folder);
        sessions.audit(user(http), "WORKFLOW_MOVE", "w" + id, "code=" + wf.get("code") + " folder=" + folder);
        return getWorkflow(id);
    }

    @DeleteMapping("/workflows/{id}")
    @RequireAtom("workflow:delete")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        // 先做带租户过滤的存在性校验：GET/PUT 一直是这样，唯独删除漏了，
        // 外部审计实测 tenant_b 能删默认租户的工作流（v447 P0-1）。
        Map<String, Object> wf = getWorkflow(id);
        mutations.deleteWorkflow(id);
        // S3（2026-10-26 扫描 P1）：删除会连带删掉 Prefect 部署与全部版本，
        // 原先没有任何审计——事后无法回答「这个调度是谁删的、删的时候是什么状态」。
        sessions.audit(user(http), "WORKFLOW_DELETE", "w" + id,
                "code=" + wf.get("code") + " name=" + wf.get("name")
                        + " env=" + wf.get("env") + " folder=" + (wf.get("folder") == null ? "/" : wf.get("folder"))
                        + " project_id=" + wf.get("project_id"));
        return Map.of("ok", true);
    }

    @GetMapping("/workflows/{id}/dag")
    public Map<String, Object> preview(@PathVariable long id) {
        Map<String, Object> wf = getWorkflow(id);
        String env = String.valueOf(wf.get("env"));
        int nextVersion = jdbc.queryForObject(
                "select coalesce(max(version_no), 0) + 1 from platform.workflow_version where workflow_id = ?",
                Integer.class, id);
        String deploymentName = deploymentName(String.valueOf(wf.get("code")), env, nextVersion);
        ScheduleSpec previewSchedule = scheduleFor(id, env);
        PrefectFlowRenderer.RenderedFlow flow = renderer.render(
                id,
                String.valueOf(wf.get("project_code")),
                String.valueOf(wf.get("code")),
                String.valueOf(wf.get("name")),
                env,
                String.valueOf(wf.get("model")),
                previewSchedule == null ? null : previewSchedule.cron(),
                nextVersion,
                deploymentName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dagId", flow.flowName());
        out.put("flowName", flow.flowName());
        out.put("deploymentName", deploymentName);
        out.put("filename", flow.filename());
        out.put("python", flow.python());
        return out;
    }

    public record ValidateRequest(Object model, String env) {
    }

    /**
     * 发布前校验（v450 P1-3）：结构 + 每条 SQL 的语法探测，返回逐任务结果。
     * 不执行数据（外层 limit 0），失败带任务编码与行号。
     */
    @PostMapping("/workflows/{id}/validate")
    @RequireAtom("workflow:update")
    public Map<String, Object> validateWorkflow(@PathVariable long id,
                                                @RequestBody(required = false) ValidateRequest req,
                                                HttpServletRequest http) {
        Map<String, Object> wf = getWorkflow(id);
        String model = req != null && req.model() != null
                ? toJson(req.model()) : String.valueOf(wf.get("model"));
        if (req != null && req.model() != null) {
            validateModelShape(req.model());
        }
        String env = req != null && req.env() != null && !req.env().isBlank()
                ? req.env().trim().toUpperCase(Locale.ROOT) : "DEV";
        WorkflowValidationService.Outcome outcome = validation.validate(id,
                ((Number) wf.get("project_id")).longValue(), String.valueOf(wf.get("project_code")),
                String.valueOf(wf.get("code")), String.valueOf(wf.get("name")), env, model);
        sessions.audit(user(http), "WORKFLOW_VALIDATE", "w" + id,
                outcome.ok() ? "ok" : "failed: " + firstError(outcome));
        return outcome.toMap();
    }

    /** DEV 跑通：在 validate 基础上真实执行只读查询（最多 5 行），返回列/行数/耗时。 */
    @PostMapping("/workflows/{id}/smoke")
    @RequireAtom("workflow:update")
    public Map<String, Object> smokeWorkflow(@PathVariable long id,
                                             @RequestBody(required = false) ValidateRequest req,
                                             HttpServletRequest http) {
        Map<String, Object> wf = getWorkflow(id);
        String model = req != null && req.model() != null
                ? toJson(req.model()) : String.valueOf(wf.get("model"));
        if (req != null && req.model() != null) {
            validateModelShape(req.model());
        }
        String env = req != null && req.env() != null && !req.env().isBlank()
                ? req.env().trim().toUpperCase(Locale.ROOT) : "DEV";
        WorkflowValidationService.Outcome outcome = validation.smoke(id,
                ((Number) wf.get("project_id")).longValue(), String.valueOf(wf.get("project_code")),
                String.valueOf(wf.get("code")), String.valueOf(wf.get("name")), env, model);
        sessions.audit(user(http), "WORKFLOW_DEV_RUN", "w" + id,
                outcome.ok() ? "ok" : "failed: " + firstError(outcome));
        return outcome.toMap();
    }

    private static String firstError(WorkflowValidationService.Outcome outcome) {
        for (WorkflowValidationService.TaskCheck task : outcome.tasks()) {
            if ("failed".equals(task.status())) {
                return task.code() + ": " + task.message();
            }
        }
        return "unknown";
    }

    @PostMapping("/workflows/{id}/versions")
    @RequireAtom("workflow:update")
    public Map<String, Object> createVersion(@PathVariable long id, @RequestBody(required = false) VersionRequest req,
                                             HttpServletRequest http) {
        Map<String, Object> wf = getWorkflow(id);
        // 传了 model 就用传入的；否则把工作流当前模型快照成新版本。
        if (req != null && req.model() != null) {
            validateModelShape(req.model());
        }
        String modelJson = req != null && req.model() != null
                ? toJson(req.model()) : String.valueOf(wf.get("model"));
        String username = user(http);
        return mutations.withLock(id, () -> {
            // 与最近一个版本内容完全一致时不再新建：反复点发布/存版本不应堆出空版本
            List<Map<String, Object>> latest = jdbc.queryForList("""
                    select version_no, model_json::text as model
                    from platform.workflow_version where workflow_id = ?
                    order by version_no desc limit 1
                    """, id);
            // v449 P1-3：按 JSON 语义比较，不再比原始字符串——rollback/DB 回填的文本
            // 可能只差空格，字符串比较会把同一内容判成新版本，堆出重复版本。
            if (!latest.isEmpty()
                    && sameModelJson(modelJson, String.valueOf(latest.get(0).get("model")))) {
                int existing = ((Number) latest.get(0).get("version_no")).intValue();
                Map<String, Object> out = version(id, existing);
                out.put("created", false);
                sessions.audit(username, "VERSION_REUSE", "w" + id,
                        "code=" + wf.get("code") + " v" + existing);
                return out;
            }
            int next = mutations.nextVersion(id, modelJson, username);
            sessions.audit(username, "VERSION_CREATE", "w" + id, "code=" + wf.get("code") + " v" + next);
            Map<String, Object> out = version(id, next);
            out.put("created", true);
            return out;
        });
    }

    @GetMapping("/workflows/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable long id) {
        // 先确认工作流存在：删除后读版本列表不能返回 200 + 空数组，
        // 前端会以为「这个工作流只是还没存过版本」。
        getWorkflow(id);
        // dev_status / prod_status 按环境分别记录；status 只是「最近一次部署」的兼容字段，
        // 同一版本先发 DEV 再发 PROD 时它会互相覆盖，前端不能再拿它判断环境状态。
        return jdbc.queryForList("""
                select id, version_no, status, dev_status, prod_status, prod_pending_at,
                       created_by, created_at, published_at, published_by,
                       prefect_env, model_json::text as model
                from platform.workflow_version where workflow_id = ? order by version_no desc
                """, id);
    }

    @PostMapping("/workflows/{id}/versions/{versionNo}/publish")
    @RequireAtom("workflow:publish")
    public Map<String, Object> publish(@PathVariable long id, @PathVariable int versionNo,
                                       @RequestBody(required = false) EnvRequest req,
                                       HttpServletRequest http) {
        String env = resolveEnv(id, req == null ? null : req.env());
        Map<String, Object> ver = version(id, versionNo);
        if ("PROD".equals(env)) {
            // 这个版本已经在线上跑：重复提交审批只会把版本状态改回「待审批」，
            // 而线上部署仍在，列表就会出现「待审批」的已上线版本。直接幂等返回。
            Integer live = jdbc.queryForObject("""
                    select count(*) from platform.workflow_deployment
                    where workflow_id = ? and env = 'PROD' and version_no = ? and active
                      and prefect_deployment_id is not null
                    """, Integer.class, id, versionNo);
            if (live != null && live > 0) {
                // 兼容历史数据：线上部署在，prod_status 也必须显示已上线
                jdbc.update("""
                        update platform.workflow_version
                        set prod_status = 'PUBLISHED', prod_pending_at = null
                        where id = ? and coalesce(prod_status, '') <> 'PUBLISHED'
                        """, ver.get("id"));
                return Map.of("status", "PUBLISHED", "env", env, "versionNo", versionNo,
                        "message", "该版本已在线上运行，无需重复提交审批");
            }
            // 生产发布前必须先在开发环境跑通过：审批只能证明「有人看过」，
            // 证明不了 SQL / 依赖 / 参数在生产真能跑起来。
            requireDevSuccess(id, versionNo);
            // 待审批记在 prod_status 上，而不是共用的 status：同一版本之后发 DEV
            // 不会再把「PROD 待审批」冲掉，版本列表也能同时显示两个环境的状态。
            jdbc.update("""
                    update platform.workflow_version
                    set prod_status = 'PENDING_APPROVAL', prod_pending_at = now()
                    where id = ?
                    """, ver.get("id"));
            sessions.audit(user(http), "PUBLISH_REQUEST", "w" + id, "v" + versionNo + " PROD");
            return Map.of("status", "PENDING_APPROVAL", "env", env, "versionNo", versionNo,
                    "message", "生产发布已提交审批");
        }
        return doPublish(id, versionNo, env, user(http));
    }

    @PostMapping("/workflows/{id}/versions/{versionNo}/approve")
    @RequireAtom("workflow:approve")
    public Map<String, Object> approve(@PathVariable long id, @PathVariable int versionNo,
                                       @RequestBody(required = false) EnvRequest req,
                                       HttpServletRequest http) {
        String env = resolveEnv(id, req == null ? null : req.env());
        // D25：这个端点的注解只要求 workflow:approve，但非 PROD 分支实际会直接发布。
        // 审批权不等于发布权，DEV 发布必须另外具备 workflow:publish。
        if (!"PROD".equals(env) && !com.lab.dataworks.auth.AtomCheck.has(http, "workflow:publish")) {
            sessions.audit(user(http), "PUBLISH_DENIED", "w" + id,
                    "审批端点发布 " + env + " 需要 workflow:publish", "FAILED",
                    com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
            throw new ApiException(403, "DEV 发布需要 workflow:publish 权限（审批权不等于发布权）");
        }
        // 锁外先看一眼状态：重复点击审批时直接 400，不必排在「触发/发布」的工作流锁后面
        // 等上两分钟。真正的并发闸门仍是锁内那次条件更新。
        if ("PROD".equals(env)
                && !"PENDING_APPROVAL".equals(prodStatus(version(id, versionNo)))) {
            throw ApiException.badRequest("只有待审批（PENDING_APPROVAL）的版本可以审批发布");
        }
        return mutations.withLock(id, () -> {
            Map<String, Object> ver = version(id, versionNo);
            if ("PROD".equals(env)) {
                // v460 F-P2-8：提交→审批是双人闸门。非平台管理员不能审批自己创建的版本；
                // 平台管理员（默认租户 + user:manage）保留豁免，避免运维场景被卡死。
                String actor = user(http);
                boolean platformAdmin = com.lab.dataworks.common.TenantGuard.platform()
                        && com.lab.dataworks.auth.AtomCheck.has(http, "user:manage");
                if (!platformAdmin && actor.equals(String.valueOf(ver.get("created_by")))) {
                    sessions.audit(actor, "PUBLISH_APPROVE_DENIED", "w" + id,
                            "不能审批自己创建的版本 v" + versionNo, "FAILED",
                            com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
                    throw ApiException.badRequest("不能审批自己创建的版本：生产发布需要第二个人复核");
                }
                // 提交审批之后才在 DEV 跑成功也要认；审批入口再校验一次，
                // 防止「提交时没跑过、之后补跑失败」的版本被直接发布到生产。
                requireDevSuccess(id, versionNo);
                // 状态检查必须和发布在同一把锁里：两个并发的 approve 都会读到
                // PENDING_APPROVAL，否则会各发布一次。
                int claimed = jdbc.update("""
                        update platform.workflow_version set prod_status = 'PUBLISHING'
                        where id = ? and prod_status = 'PENDING_APPROVAL'
                        """, ver.get("id"));
                if (claimed == 0) {
                    throw ApiException.badRequest("只有待审批（PENDING_APPROVAL）的版本可以审批发布");
                }
                try {
                    return doPublishLocked(id, versionNo, env, user(http));
                } catch (RuntimeException e) {
                    jdbc.update("""
                            update platform.workflow_version set prod_status = 'PENDING_APPROVAL'
                            where id = ? and prod_status = 'PUBLISHING'
                            """, ver.get("id"));
                    throw e;
                }
            }
            if (!"DRAFT".equals(String.valueOf(ver.get("status")))
                    && !"PUBLISHED".equals(String.valueOf(ver.get("status")))) {
                throw ApiException.badRequest("当前版本状态不允许发布: " + ver.get("status"));
            }
            return doPublishLocked(id, versionNo, env, user(http));
        });
    }

    /**
     * D31b：撤回 PROD 待审批申请（PENDING_APPROVAL → CANCELLED）。
     *
     * <p>卡死场景（与变更单 D31 同形）：v460 双人闸门规定非平台管理员不能审批
     * {@code created_by == 自己} 的版本，但待审批一直没有任何撤回出口 ——
     * 单管理员提交自己的版本后既无人能批、也撤不掉，版本永久停在 PENDING_APPROVAL。
     * 这里给提交人自己开出口：只允许撤「自己创建且仍待审批」的版本；撤回后
     * prod_status=CANCELLED、清掉 prod_pending_at，可以再次提交审批，版本与模型不动。</p>
     *
     * <p>提交人口径：workflow_version 没有单独的「审批提交人」列，而审批侧双人闸门
     * 就是按 {@code created_by} 判定「自己」的（见 {@link #approve}），撤回沿用同一口径，
     * 两处不会出现「A 算提交人、B 算提交人」的分叉。</p>
     *
     * <p>权限点复用 {@code workflow:publish}：与「提交审批」同一个点 —— 提交人必然持有
     * （publish 端点就要求它），不会出现 D31 那种撤回接口没声明权限、
     * 拦截器默认拒绝导致的「403 接口未授权」死路。</p>
     */
    @PostMapping("/workflows/{id}/versions/{versionNo}/withdraw")
    @RequireAtom("workflow:publish")
    public Map<String, Object> withdraw(@PathVariable long id, @PathVariable int versionNo,
                                        HttpServletRequest http) {
        // 工作流不存在/不属于当前租户 → 404；与 versions() 列表同一入口守卫
        getWorkflow(id);
        String actor = user(http);
        return mutations.withLock(id, () -> {
            Map<String, Object> ver = version(id, versionNo);
            // 状态必须在锁内判：approve 的认领更新同样在锁里，
            // 「刚被批准/正在发布」与「撤回」不能交错通过。
            Integer deny = withdrawDecision(effectiveProdStatus(ver), actor,
                    String.valueOf(ver.get("created_by")));
            if (deny != null) {
                sessions.audit(actor, "PUBLISH_WITHDRAW_DENIED", "w" + id,
                        "v" + versionNo + (deny == 403 ? " 撤他人单被拒" : " 非待审批不可撤"),
                        "FAILED", com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
                throw new ApiException(deny, deny == 403
                        ? "只能撤回自己提交的发布申请"
                        : "只有待审批（PENDING_APPROVAL）的版本可以撤回，当前状态："
                                + effectiveProdStatus(ver));
            }
            // 条件更新兜并发：认领条件与判定一致（含 V62 之前的老数据回退）。
            int updated = jdbc.update("""
                    update platform.workflow_version
                       set prod_status = 'CANCELLED', prod_pending_at = null
                     where id = ? and created_by = ?
                       and (prod_status = 'PENDING_APPROVAL'
                            or (coalesce(prod_status, '') = '' and status = 'PENDING_APPROVAL'))
                    """, ver.get("id"), actor);
            if (updated == 0) {
                throw ApiException.conflict(
                        "只有待审批（PENDING_APPROVAL）的版本可以撤回，当前状态："
                                + effectiveProdStatus(ver));
            }
            sessions.audit(actor, "PUBLISH_WITHDRAW", "w" + id,
                    "v" + versionNo + " PROD 待审批→CANCELLED");
            return Map.of("status", "CANCELLED", "env", "PROD", "versionNo", versionNo,
                    "message", "已撤回生产发布申请，可修改后重新提交审批");
        });
    }

    /**
     * D31b 撤回判定（纯函数，回归用例直接打）：
     * 撤他人单 → 403；撤非待审批（已批准 / 发布中 / 已上线 / 已撤回 / 未提交）→ 409；
     * 提交人撤自己的待审批单 → null（放行）。
     * 先判人再判状态：撤他人已批准的单回 403，不借 409 泄露他人单据状态。
     */
    static Integer withdrawDecision(String effectiveProdStatus, String actor, String createdBy) {
        if (actor == null || !actor.equals(createdBy)) {
            return 403;
        }
        if (!"PENDING_APPROVAL".equals(effectiveProdStatus)) {
            return 409;
        }
        return null;
    }

    @PostMapping("/workflows/{id}/rollback/{versionNo}")
    @RequireAtom("workflow:publish")
    public Map<String, Object> rollback(@PathVariable long id, @PathVariable int versionNo,
                                        @RequestBody(required = false) EnvRequest req,
                                        HttpServletRequest http) {
        Map<String, Object> old = version(id, versionNo);
        String env = resolveEnv(id, req == null ? null : req.env());
        // 正常 PROD 发布有「提交审批 → 管理员审批」两道闸门，回滚不能绕过。
        // 权限与环境的校验必须在分配版本号之前：被拒绝的请求不该留下 DRAFT 版本。
        if ("PROD".equals(env) && !com.lab.dataworks.auth.AtomCheck.has(http, "user:manage")) {
            throw new ApiException(403, "生产回滚需要平台管理员执行，请先提交审批");
        }
        return mutations.withLock(id, () -> {
            // v449 P1-3：先做发布前校验再分配版本号。原来先 nextVersion 再校验，
            // 校验失败返回 400 却留下一个空 DRAFT，「回滚失败凭空多一版」。
            Map<String, Object> wf = getWorkflow(id);
            renderer.render(id, String.valueOf(wf.get("project_code")),
                    String.valueOf(wf.get("code")), String.valueOf(wf.get("name")),
                    env, String.valueOf(old.get("model")), null, versionNo,
                    deploymentName(String.valueOf(wf.get("code")), env, versionNo));
            int next = mutations.nextVersion(id, String.valueOf(old.get("model")), user(http));
            Map<String, Object> result = doPublishLocked(id, next, env, user(http), false);
            sessions.audit(user(http), "ROLLBACK", "w" + id, "to v" + versionNo + " as v" + next);
            return result;
        });
    }

    @PutMapping("/workflows/{id}/schedule")
    @RequireAtom("schedule:update")
    public Map<String, Object> schedule(@PathVariable long id, @RequestBody ScheduleRequest req,
                                        HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        // 工作流不存在时直接 404；否则插 schedule 会撞外键，被全局处理器当成 409「数据冲突」
        getWorkflow(id);
        String env = resolveEnv(id, req.env());
        String status = req.status() == null ? "ONLINE" : req.status().toUpperCase(Locale.ROOT);
        if (!Set.of("ONLINE", "OFFLINE").contains(status)) {
            throw ApiException.badRequest("调度状态只能是 ONLINE 或 OFFLINE");
        }
        String type = normalizeScheduleType(req.scheduleType());
        String cron;
        String runTime = null;
        String weekdays = null;
        String monthDays = null;
        String startAt = null;
        String startDate = parseOptionalDate(req.startDate(), "开始日期");
        String endDate = parseOptionalDate(req.endDate(), "结束日期");
        if (startDate != null && endDate != null
                && LocalDate.parse(endDate).isBefore(LocalDate.parse(startDate))) {
            throw ApiException.badRequest("结束日期不能早于开始日期");
        }
        if ("CRON".equals(type)) {
            // 兼容老界面 / 老数据：直接使用 cron。OFFLINE 时允许为空，
            // 但列是 NOT NULL，必须落空串而不是 null（否则 500）。
            cron = req.cron() == null ? "" : req.cron().trim();
        } else {
            ScheduleSpec spec;
            try {
                spec = buildScheduleSpec(type, req.startAt(), req.runTime(),
                        req.weekdays(), req.monthDays(), endDate);
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest("日期格式不正确，应为 yyyy-MM-dd");
            }
            cron = spec.cron();
            LocalDateTime start = parseStart(req.startAt(), req.runTime());
            runTime = start.format(TIME_FMT);
            startDate = start.toLocalDate().toString();
            startAt = start.atZone(ZONE).toOffsetDateTime().toString();
            weekdays = normalizeWeekdays(req.weekdays(), type);
            monthDays = normalizeMonthDays(req.monthDays(), type);
        }
        if ("ONLINE".equals(status)) {
            validateCron(cron);
        }
        // v449 P2-3：非法时区之前被静默忽略并落库成 Asia/Shanghai。渲染器目前只支持
        // 上海时区，收到别的值就明确 400，避免「看起来设置成功、实际按别的时区跑」。
        String timezone = req.timezone() == null ? "" : req.timezone().trim();
        if (!timezone.isEmpty() && !"Asia/Shanghai".equals(timezone)) {
            throw ApiException.badRequest(
                    "调度时区目前只支持 Asia/Shanghai，收到: " + req.timezone());
        }
        jdbc.update("""
                insert into platform.schedule
                    (workflow_id, env, cron, timezone, status, start_date, end_date,
                     schedule_type, run_time, weekdays, month_days, start_at)
                values (?, ?, ?, 'Asia/Shanghai', ?, ?::date, ?::date, ?, ?, ?, ?, ?::timestamptz)
                on conflict (workflow_id, env) do update
                set cron = excluded.cron, status = excluded.status,
                    start_date = excluded.start_date, end_date = excluded.end_date,
                    schedule_type = excluded.schedule_type, run_time = excluded.run_time,
                    weekdays = excluded.weekdays, month_days = excluded.month_days,
                    start_at = excluded.start_at, updated_at = now()
                """, id, env, cron, status, nullableDate(startDate), nullableDate(endDate),
                type, runTime, weekdays, monthDays, startAt);
        sessions.audit(user(http), "SCHEDULE_UPDATE", "w" + id,
                env + " " + status + " " + type + " " + cron);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("env", env);
        result.put("status", status);
        result.put("scheduleType", type);
        result.put("cron", cron);
        result.put("startAt", startAt);
        result.put("weekdays", weekdays);
        result.put("monthDays", monthDays);
        result.put("note", "调度变更后需重新发布才会在 Prefect 生效");
        return result;
    }

    @PostMapping("/workflows/{id}/trigger")
    @RequireAtom("ops:run")
    public Map<String, Object> trigger(@PathVariable long id, @RequestBody TriggerRequest req,
                                       HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        // 先判工作流是否存在：已删除的工作流不能提示「请先把该工作流发布到 DEV」
        getWorkflow(id);
        String env = resolveEnv(id, req.env());
        // 先校验日期再调 Prefect，避免日期非法时 Prefect 已经起了一个跑不了的实例
        String bizDate = dateParam(req.bizDate(), "bizDate", LocalDate.now(ZONE).toString());
        // 发布后 Flow 文件同步到 worker 是纯等待（25–60 秒），不读写共享状态：
        // 放到锁外预热，否则一次触发会持锁 100 秒以上，把同一工作流的审批/发布
        // 一起堵住。锁内仍会再 await 一次，此时文件已就绪，立即返回。
        WorkflowTriggerService.DeploymentRef warm = triggers.latestDeployment(id, env);
        if (warm != null && warm.file() != null) {
            publisher.awaitFlowFile(warm.file(), warm.publishedEpochMs());
        }
        // 与发布/离线/删除互斥：部署解析完到 createRun 之间不能被删掉
        Map<String, Object> result = triggers.trigger(
                id, env, bizDate, req.params(), "manual", user(http));
        sessions.audit(user(http), "TRIGGER", "w" + id, env + " " + bizDate);
        return result;
    }

    /**
     * 提交补数作业：异步按天切片执行，立即返回 jobId；进度走
     * {@code GET /api/backfills/{jobId}}。旧版同步返回的 {@code count}
     * （本次补数的天数）保留，运维页不用同版本升级也能继续展示。
     */
    @PostMapping("/workflows/{id}/backfill")
    @RequireAtom("ops:backfill")
    public Map<String, Object> backfill(@PathVariable long id, @RequestBody BackfillRequest req,
                                        HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        getWorkflow(id);
        Map<String, Object> job = backfills.submit(id, req.env(), req.resolvedStart(),
                req.resolvedEnd(), req.parallelism(), req.params(), user(http));
        sessions.audit(user(http), "BACKFILL", "w" + id,
                job.get("env") + " " + job.get("dateStart") + ".." + job.get("dateEnd")
                        + " job=" + job.get("jobId"));
        return job;
    }

    @PostMapping("/workflows/{id}/offline")
    @RequireAtom("workflow:publish")
    public Map<String, Object> offline(@PathVariable long id,
                                       @RequestBody(required = false) EnvRequest req,
                                       HttpServletRequest http) {
        // 不存在的工作流不能静默返回 ok=true，否则前端会把「已经没了」当成「下线成功」
        Map<String, Object> wf = getWorkflow(id);
        String env = resolveEnv(id, req == null ? null : req.env());
        // 与发布/触发互斥：避免刚查出部署就被并发发布替换，删错或漏删
        Map<String, Object> result = mutations.withLock(id, () -> doOffline(id, env));
        // S3：下线会删 Prefect deployment、把 workflow_deployment.active 与
        // 版本/调度状态改成 OFFLINE，以前这条链路零审计（对照 SERVICE_OFFLINE 是有的）
        sessions.audit(user(http), "WORKFLOW_OFFLINE", "w" + id,
                "code=" + wf.get("code") + " env=" + env
                        + " deploymentId=" + result.getOrDefault("deploymentId", "")
                        + " 版本状态与调度已置 OFFLINE");
        return result;
    }

    private Map<String, Object> doOffline(long id, String env) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select prefect_deployment_id, prefect_flow_file
                from platform.workflow_deployment
                where workflow_id = ? and env = ? and active
                  and prefect_deployment_id is not null
                """, id, env);
        if (rows.isEmpty()) {
            // 兼容 V43 之前的历史数据
            rows = jdbc.queryForList("""
                    select prefect_deployment_id, prefect_flow_file
                    from platform.workflow_version
                    where workflow_id = ? and prefect_deployment_id is not null and prefect_env = ?
                    order by version_no desc limit 1
                    """, id, env);
        }
        String deploymentId = rows.isEmpty() ? null
                : String.valueOf(rows.get(0).get("prefect_deployment_id"));
        for (Map<String, Object> row : rows) {
            publisher.delete(row.get("prefect_flow_file") == null ? null
                            : String.valueOf(row.get("prefect_flow_file")),
                    String.valueOf(row.get("prefect_deployment_id")));
        }
        jdbc.update("""
                update platform.workflow_deployment set active = false
                where workflow_id = ? and env = ?
                """, id, env);
        // 清掉版本行上的指针：latestDeployment 的兼容回退不能把离线环境当成已发布
        jdbc.update("""
                update platform.workflow_version
                set prefect_deployment_id = null, prefect_flow_file = null, prefect_flow_name = null
                where workflow_id = ? and prefect_env = ?
                """, id, env);
        // 该环境下线：版本状态同步置 OFFLINE，否则列表里仍显示「线上」
        String envColumn = "DEV".equals(env) ? "dev_status" : "prod_status";
        jdbc.update("update platform.workflow_version set " + envColumn
                + " = 'OFFLINE' where workflow_id = ? and " + envColumn + " = 'PUBLISHED'", id);
        jdbc.update("""
                update platform.schedule set status = 'OFFLINE', updated_at = now()
                where workflow_id = ? and env = ?
                """, id, env);
        return Map.of("ok", true, "deploymentId", deploymentId == null ? "" : deploymentId,
                "status", "OFFLINE");
    }

    private Map<String, Object> doPublish(long id, int versionNo, String env, String username) {
        // 与删除互斥：避免「发布创建了 Prefect 部署、主记录已被删」的孤儿
        return mutations.withLock(id, () -> doPublishLocked(id, versionNo, env, username, true));
    }

    private Map<String, Object> doPublishLocked(long id, int versionNo, String env, String username) {
        return doPublishLocked(id, versionNo, env, username, true);
    }

    /**
     * @param validateSql 发布前是否强制校验。DEV 发布与 PROD 审批发布都要校验；
     *                    rollback 传 false——回滚的是历史上已经跑通过的版本，
     *                    表被删了也不该因此禁止回滚。
     */
    private Map<String, Object> doPublishLocked(long id, int versionNo, String env, String username,
                                                boolean validateSql) {
        Map<String, Object> wf = getWorkflow(id);
        Map<String, Object> ver = version(id, versionNo);
        if (validateSql) {
            validation.requireValid(id, ((Number) wf.get("project_id")).longValue(),
                    String.valueOf(wf.get("project_code")), String.valueOf(wf.get("code")),
                    String.valueOf(wf.get("name")), env, String.valueOf(ver.get("model")));
        }
        ScheduleSpec schedule = scheduleFor(id, env);
        String projectCode = String.valueOf(wf.get("project_code"));
        String deploymentName = deploymentName(String.valueOf(wf.get("code")), env, versionNo);
        PrefectFlowRenderer.RenderedFlow flow = renderer.render(
                id,
                projectCode,
                String.valueOf(wf.get("code")),
                String.valueOf(wf.get("name")),
                env,
                String.valueOf(ver.get("model")),
                schedule == null ? null : schedule.cron(),
                versionNo,
                deploymentName);
        List<String> tags = List.of("dataworks", projectCode, env.toLowerCase(Locale.ROOT));
        // 同一 workflow+env 同时只允许一个生效部署：旧版本的调度如果不删，
        // 每次 tick 会按版本数各跑一遍，且都写同一张目标表。
        // 顺序：先建新部署并确认成功，再切 DB 指针，最后删旧资产。以前先删后建，
        // bridge 502 / 新 cron 被拒绝时线上调度已经被毁，而 DB 还指向已删部署。
        List<Map<String, Object>> previous = jdbc.queryForList("""
                select prefect_deployment_id, prefect_flow_file, prefect_flow_name,
                       version_no, deployed_at
                from platform.workflow_deployment
                where workflow_id = ? and env = ? and active
                  and prefect_deployment_id is not null
                """, id, env);
        // D28：同一版本重复发布以前走「删旧建新」，两次调用各拿到一个 deploymentId，
        // 先返回的那个当场失效（连点 / 重试 / 脚本重跑都会拿到死 id），而且线上有短空窗。
        // 现在先判幂等：该环境生效部署就是这一版、渲染结果没变、调度自那次发布以来没改过，
        // 就原样返回旧部署，不碰 Prefect、不写库（与 PROD「已在线上运行」分支同一口径）。
        Map<String, Object> reusable = reusableDeployment(id, env, previous, versionNo,
                flow.filename(), flow.flowName());
        String replacedDeploymentId = null;
        if (reusable != null) {
            String reuseDeployment = String.valueOf(reusable.get("prefect_deployment_id"));
            log.info("publish idempotent workflow={} v{} env={} deployment={}",
                    wf.get("code"), versionNo, env, reuseDeployment);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "PUBLISHED");
            out.put("env", env);
            out.put("versionNo", versionNo);
            out.put("dagId", reusable.get("prefect_flow_name"));
            out.put("flowName", reusable.get("prefect_flow_name"));
            out.put("deploymentId", reuseDeployment);
            out.put("filename", reusable.get("prefect_flow_file"));
            out.put("schedule", schedule == null ? "OFFLINE" : schedule.cron());
            out.put("idempotent", true);
            out.put("message", "该版本已是 " + env + " 当前生效部署，未做变更；"
                    + "如需让新的调度生效，请修改调度后重新发布");
            return out;
        }
        List<Map<String, Object>> deleteAfter = new java.util.ArrayList<>();
        for (Map<String, Object> row : previous) {
            long prevVersion = row.get("version_no") instanceof Number n ? n.longValue() : -1;
            if (prevVersion == versionNo) {
                // 走到这里说明同版本确实要重建（调度改了 / flow 资产变了 / 部署行缺指针）：
                // 部署名由「工作流+版本+环境」决定，和旧部署同名，Prefect 侧同名创建可能被拒，
                // 只能先删；这是唯一无法做到先建后删的情况。把旧 id 回带给调用方，别让人
                // 以为手里那个 id 还能用。
                replacedDeploymentId = String.valueOf(row.get("prefect_deployment_id"));
                publisher.delete(row.get("prefect_flow_file") == null ? null
                                : String.valueOf(row.get("prefect_flow_file")),
                        replacedDeploymentId);
            } else {
                deleteAfter.add(row);
            }
        }
        PrefectBridgeClient.Deployment deployment = publisher.publish(
                flow.filename(), flow.python(), flow.flowName(), deploymentName,
                flow.entrypoint(),
                schedule == null ? null : schedule.cron(),
                schedule == null ? null : schedule.rrule(), tags,
                String.valueOf(wf.get("name")) + " " + env + " v" + versionNo);
        try {
            jdbc.update("""
                    update platform.workflow_version
                    set status = 'PUBLISHED', published_at = now(), published_by = ?,
                        prefect_deployment_id = ?, prefect_flow_name = ?, prefect_flow_file = ?,
                        prefect_env = ?
                    where id = ?
                    """, username, deployment.deploymentId(), deployment.flowName(),
                    flow.filename(), env, ver.get("id"));
            // 环境状态：该环境只允许一个 PUBLISHED，新上线的版本把旧的记为「已替换」。
            // 同一版本在该环境重复发布时保持 PUBLISHED（id <> 排除自己）。
            String envColumn = "DEV".equals(env) ? "dev_status" : "prod_status";
            jdbc.update("update platform.workflow_version set " + envColumn
                    + " = 'SUPERSEDED' where workflow_id = ? and " + envColumn
                    + " in ('PUBLISHED', 'OFFLINE') and id <> ?", id, ver.get("id"));
            if ("PROD".equals(env)) {
                jdbc.update("""
                        update platform.workflow_version
                        set prod_status = 'PUBLISHED', prod_pending_at = null where id = ?
                        """, ver.get("id"));
            } else {
                jdbc.update("update platform.workflow_version set dev_status = 'PUBLISHED' where id = ?",
                        ver.get("id"));
            }
            // 指针以 workflow_deployment 为准：同一版本先发 DEV 再发 PROD 时，
            // workflow_version 只能记住最后一次环境，不能再作为触发依据。
            jdbc.update("""
                    update platform.workflow_deployment
                    set active = false
                    where workflow_id = ? and env = ? and active
                    """, id, env);
            // 旧版本的 Prefect 部署已被删除，版本行上的指针必须一起清掉，
            // 否则 latestDeployment 的兼容回退会把触发器带到 404。
            jdbc.update("""
                    update platform.workflow_version
                    set prefect_deployment_id = null, prefect_flow_file = null,
                        prefect_flow_name = null
                    where workflow_id = ? and prefect_env = ? and status = 'PUBLISHED'
                      and id <> ?
                    """, id, env, ver.get("id"));
            jdbc.update("""
                    insert into platform.workflow_deployment
                        (workflow_id, env, version_no, deployed_by,
                         prefect_deployment_id, prefect_flow_file, prefect_flow_name, active)
                    values (?, ?, ?, ?, ?, ?, ?, true)
                    """, id, env, versionNo, username, deployment.deploymentId(),
                    flow.filename(), deployment.flowName());
            jdbc.update("update platform.workflow set env = ?, updated_at = now() where id = ?", env, id);
        } catch (RuntimeException e) {
            // 外部部署已经创建；DB 没记住它就会变成「有调度、平台看不见」的孤儿。
            // 尽力回滚外部资产后再把异常抛给调用方。
            try {
                publisher.delete(flow.filename(), deployment.deploymentId());
            } catch (RuntimeException cleanup) {
                log.error("publish compensation failed workflow={} deployment={}: {}",
                        id, deployment.deploymentId(), cleanup.getMessage());
            }
            throw e;
        }
        // DB 已切到新部署，旧版本的外部资产再删。删除失败不影响本次发布：
        // 最坏情况是旧部署多留一会儿，而不是线上没有可用的部署。
        for (Map<String, Object> row : deleteAfter) {
            try {
                publisher.delete(row.get("prefect_flow_file") == null ? null
                                : String.valueOf(row.get("prefect_flow_file")),
                        String.valueOf(row.get("prefect_deployment_id")));
            } catch (RuntimeException e) {
                log.warn("delete previous deployment failed workflow={} deployment={}: {}",
                        id, row.get("prefect_deployment_id"), e.getMessage());
            }
        }
        log.info("published workflow={} v{} env={} flow={} deployment={}",
                wf.get("code"), versionNo, env, deployment.flowName(), deployment.deploymentId());
        // 表级血缘（best-effort）：解析该版本 SQL 任务，失败只记日志不阻断发布
        lineage.record(id, versionNo, env, String.valueOf(ver.get("model")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "PUBLISHED");
        out.put("env", env);
        out.put("versionNo", versionNo);
        out.put("dagId", deployment.flowName());
        out.put("flowName", deployment.flowName());
        out.put("deploymentId", deployment.deploymentId());
        out.put("filename", flow.filename());
        out.put("schedule", schedule == null ? "OFFLINE" : schedule.cron());
        if (replacedDeploymentId != null) {
            // D28：这一版以前那个部署已经被删掉，明确告诉调用方新 id 替换了谁
            out.put("replacedDeploymentId", replacedDeploymentId);
        }
        return out;
    }

    /**
     * D28：这一版本在该环境是否已有一份「不需要重建」的生效部署。
     *
     * <p>调度是否算「变了」必须由数据库判定：{@code PUT /workflows/{id}/schedule} 只写
     * {@code platform.schedule}，返回里就写着「调度变更后需重新发布才会在 Prefect 生效」，
     * 所以 {@code schedule.updated_at} 晚于那次发布的 {@code deployed_at} 时不能幂等，
     * 必须真的重建部署才能把新 cron 带上去。</p>
     */
    private Map<String, Object> reusableDeployment(long workflowId, String env,
                                                   List<Map<String, Object>> previous,
                                                   int versionNo, String filename, String flowName) {
        Map<String, Object> candidate =
                sameVersionDeployedRow(previous, versionNo, filename, flowName);
        if (candidate == null) {
            return null;
        }
        java.sql.Timestamp deployedAt = deployedAtOf(candidate);
        if (deployedAt == null) {
            // 取不到发布时间就按「需要重建」处理：宁可多发一次，也不能把新 cron 吞掉
            return null;
        }
        Integer changed = jdbc.queryForObject("""
                select count(*) from platform.schedule
                where workflow_id = ? and env = ? and updated_at > ?
                """, Integer.class, workflowId, env, deployedAt);
        // 判定仍走同一份纯函数，避免「查库」与「决策」两处口径漂移
        return pickReusableDeployment(previous, versionNo, filename, flowName,
                changed != null && changed > 0);
    }

    /** 驱动可能回 Timestamp，也可能回 OffsetDateTime；两种都收，拿不到返回 null。 */
    private static java.sql.Timestamp deployedAtOf(Map<String, Object> row) {
        Object value = row.get("deployed_at");
        if (value instanceof java.sql.Timestamp ts) {
            return ts;
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return java.sql.Timestamp.from(odt.toInstant());
        }
        return null;
    }

    /**
     * D28 纯决策：该环境唯一生效部署正好是这一版，且本次渲染出的 flow 资产与
     * 已部署的那份一致，才算「可以复用」。
     *
     * @return 可复用的部署行；需要重建返回 {@code null}
     */
    static Map<String, Object> sameVersionDeployedRow(List<Map<String, Object>> previous,
                                                      int versionNo, String filename,
                                                      String flowName) {
        if (previous == null || previous.size() != 1) {
            // 0 条 = 没发布过；多条 = 数据异常（同环境应只有一条生效），都走正常发布流程
            return null;
        }
        Map<String, Object> row = previous.get(0);
        long deployedVersion = row.get("version_no") instanceof Number n ? n.longValue() : -1;
        if (deployedVersion != versionNo) {
            return null;
        }
        Object deployedId = row.get("prefect_deployment_id");
        if (deployedId == null || String.valueOf(deployedId).isBlank()) {
            return null;
        }
        // 改工作流编码 / 换项目 / 换环境都会改掉 filename 与 flowName，
        // 这些必须真重建，否则线上还挂着旧名字的 flow。
        return filename.equals(String.valueOf(row.get("prefect_flow_file")))
                && flowName.equals(String.valueOf(row.get("prefect_flow_name"))) ? row : null;
    }

    /**
     * D28 纯决策（不碰数据库，便于用例覆盖）：同版本已部署 + 调度没改过 → 幂等复用。
     *
     * @param scheduleChangedAfterDeploy 该环境的 {@code schedule.updated_at} 晚于那次发布时间。
     *                                   改了 cron 之后重新发布是文档里写明的生效方式，
     *                                   这种情况必须真重建，不能幂等。
     */
    static Map<String, Object> pickReusableDeployment(List<Map<String, Object>> previous,
                                                      int versionNo, String filename, String flowName,
                                                      boolean scheduleChangedAfterDeploy) {
        return scheduleChangedAfterDeploy ? null
                : sameVersionDeployedRow(previous, versionNo, filename, flowName);
    }

    /** D26：口径收拢到 {@link WorkflowTriggerService#deploymentName}，按快照重跑重建部署时同名同形。 */
    private String deploymentName(String workflowCode, String env, int versionNo) {
        return WorkflowTriggerService.deploymentName(workflowCode, env, versionNo);
    }

    /**
     * 生产发布闸门：该版本必须发布到 DEV，且在 DEV 有成功运行过的实例。
     *
     * <p>实例与部署的关联走 {@code wf_instance.dag_id = workflow_deployment.prefect_deployment_id}：
     * 同一版本可能先发 DEV 再发 PROD，两者的 version_no 指向同一份内容。</p>
     */
    private void requireDevSuccess(long workflowId, int versionNo) {
        Integer deployed = jdbc.queryForObject("""
                select count(*) from platform.workflow_deployment
                where workflow_id = ? and env = 'DEV' and version_no = ?
                  and prefect_deployment_id is not null
                """, Integer.class, workflowId, versionNo);
        if (deployed == null || deployed == 0) {
            throw ApiException.badRequest(
                    "该版本还没有发布到开发环境：请先发布 DEV 并运行成功，再提交生产发布");
        }
        Integer succeeded = jdbc.queryForObject("""
                select count(*) from platform.wf_instance i
                join platform.workflow_deployment d on d.prefect_deployment_id = i.dag_id
                where i.workflow_id = ? and d.env = 'DEV' and d.version_no = ?
                  and i.state = 'success'
                """, Integer.class, workflowId, versionNo);
        if (succeeded == null || succeeded == 0) {
            throw ApiException.badRequest(
                    "该版本在开发环境还没有成功运行过：请先在 DEV 触发一次并等待成功，再提交生产发布");
        }
    }

    /**
     * PROD 环境状态。老数据只有单列 status：它等于 PENDING_APPROVAL 时视为 PROD 待审批。
     */
    private String prodStatus(Map<String, Object> ver) {
        return effectiveProdStatus(ver);
    }

    /** 同上，纯函数版：撤回/审批两条链路必须用同一份「PROD 状态」口径（D31b）。 */
    static String effectiveProdStatus(Map<String, Object> ver) {
        Object prod = ver.get("prod_status");
        if (prod != null && !String.valueOf(prod).isBlank()) {
            return String.valueOf(prod);
        }
        return "PENDING_APPROVAL".equals(String.valueOf(ver.get("status")))
                ? "PENDING_APPROVAL" : "DRAFT";
    }

    private String resolveEnv(long workflowId, String requested) {
        if (requested == null || requested.isBlank()) {
            return String.valueOf(getWorkflow(workflowId).get("env"));
        }
        String env = requested.toUpperCase(Locale.ROOT);
        if (!Set.of("DEV", "PROD").contains(env)) {
            throw ApiException.badRequest("环境只能是 DEV 或 PROD");
        }
        return env;
    }

    private ScheduleSpec scheduleFor(long workflowId, String env) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select cron, status, schedule_type, run_time, weekdays, month_days,
                       start_at, start_date, end_date
                from platform.schedule where workflow_id = ? and env = ?
                """, workflowId, env);
        if (rows.isEmpty() || !"ONLINE".equals(String.valueOf(rows.get(0).get("status")))) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String type = normalizeScheduleType(row.get("schedule_type") == null
                ? null : String.valueOf(row.get("schedule_type")));
        String legacyCron = row.get("cron") == null ? null : String.valueOf(row.get("cron"));
        if ("CRON".equals(type)) {
            return new ScheduleSpec(legacyCron, null);
        }
        LocalDateTime start = null;
        Object startAt = row.get("start_at");
        if (startAt instanceof Timestamp ts) {
            start = ts.toInstant().atZone(ZONE).toLocalDateTime();
        }
        if (start == null && row.get("start_date") instanceof java.sql.Date date) {
            String time = row.get("run_time") == null ? "00:00" : String.valueOf(row.get("run_time"));
            try {
                start = LocalDateTime.of(date.toLocalDate(), LocalTime.parse(time, TIME_FMT));
            } catch (Exception ignored) {
                start = date.toLocalDate().atStartOfDay();
            }
        }
        if (start == null) {
            return new ScheduleSpec(legacyCron, null);
        }
        String endDate = row.get("end_date") instanceof java.sql.Date end ? end.toString() : null;
        try {
            return buildScheduleSpec(type, start.format(DATE_TIME_FMT), null,
                    row.get("weekdays") == null ? null : String.valueOf(row.get("weekdays")),
                    row.get("month_days") == null ? null : String.valueOf(row.get("month_days")),
                    endDate);
        } catch (Exception e) {
            log.warn("invalid schedule workflow={} env={} type={}: {}", workflowId, env, type, e.getMessage());
            return new ScheduleSpec(legacyCron, null);
        }
    }

    private String normalizeScheduleType(String value) {
        if (value == null || value.isBlank()) {
            return "CRON";
        }
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CRON", "ONCE", "DAILY", "WEEKLY", "MONTHLY").contains(type)) {
            throw ApiException.badRequest("调度类型只能是 ONCE / DAILY / WEEKLY / MONTHLY / CRON");
        }
        return type;
    }

    /**
     * 生成两套表示：cron 用于列表展示，rrule 用于 Prefect（支持一次性与起止时间）。
     */
    private ScheduleSpec buildScheduleSpec(String type, String startAt, String runTime,
                                           String weekdays, String monthDays, String endDate) {
        LocalDateTime start = parseStart(startAt, runTime);
        String mm = String.format("%02d", start.getMinute());
        String hh = String.format("%02d", start.getHour());
        String dtStart = start.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        StringBuilder rule = new StringBuilder("DTSTART:").append(dtStart).append("\nRRULE:");
        String cron;
        switch (type) {
            case "ONCE" -> {
                rule.append("FREQ=DAILY;COUNT=1");
                cron = mm + " " + hh + " " + start.getDayOfMonth() + " " + start.getMonthValue() + " *";
            }
            case "DAILY" -> {
                rule.append("FREQ=DAILY");
                cron = mm + " " + hh + " * * *";
            }
            case "WEEKLY" -> {
                List<Integer> days = parseWeekdays(weekdays);
                List<String> byDay = new ArrayList<>();
                List<String> cronDays = new ArrayList<>();
                for (int day : days) {
                    byDay.add(rruleWeekday(day));
                    cronDays.add(day == 7 ? "0" : String.valueOf(day));
                }
                rule.append("FREQ=WEEKLY;BYDAY=").append(String.join(",", byDay));
                cron = mm + " " + hh + " * * " + String.join(",", cronDays);
            }
            case "MONTHLY" -> {
                List<Integer> days = parseMonthDays(monthDays);
                List<String> values = new ArrayList<>();
                for (int day : days) {
                    values.add(String.valueOf(day));
                }
                String joined = String.join(",", values);
                rule.append("FREQ=MONTHLY;BYMONTHDAY=").append(joined);
                cron = mm + " " + hh + " " + joined + " * *";
            }
            default -> throw ApiException.badRequest("不支持的调度类型: " + type);
        }
        if (endDate != null && !endDate.isBlank() && !"ONCE".equals(type)) {
            LocalDate end = LocalDate.parse(endDate);
            if (end.isBefore(start.toLocalDate())) {
                // 不校验的话 UNTIL 会被静默丢掉，用户以为只跑到 12-30，实际永久调度。
                throw ApiException.badRequest("结束日期不能早于开始日期: " + endDate);
            }
            // Prefect/dateutil 要求 UNTIL 与 DTSTART 同一种表示：这里都用本地时间，
            // 时区由 RRuleSchedule(timezone=Asia/Shanghai) 统一解释。
            rule.append(";UNTIL=")
                    .append(end.atTime(23, 59, 59)
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")));
        }
        return new ScheduleSpec(cron, rule.toString());
    }

    private LocalDateTime parseStart(String startAt, String runTime) {
        if (startAt != null && !startAt.isBlank()) {
            try {
                return LocalDateTime.parse(startAt.trim().replace(' ', 'T'),
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception e) {
                throw ApiException.badRequest("启动时间格式不正确: " + startAt);
            }
        }
        if (runTime != null && !runTime.isBlank()) {
            try {
                return LocalDateTime.of(LocalDate.now(ZONE), LocalTime.parse(runTime, TIME_FMT));
            } catch (Exception e) {
                throw ApiException.badRequest("启动时间格式不正确: " + runTime);
            }
        }
        throw ApiException.badRequest("请选择启动时间");
    }

    private List<Integer> parseWeekdays(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("每周调度必须选择星期几");
        }
        TreeSet<Integer> days = new TreeSet<>();
        for (String part : value.split(",")) {
            try {
                int day = Integer.parseInt(part.trim());
                if (day < 1 || day > 7) {
                    throw new NumberFormatException();
                }
                days.add(day);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("星期取值必须是 1-7（周一=1，周日=7）: " + value);
            }
        }
        return new ArrayList<>(days);
    }

    private List<Integer> parseMonthDays(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("每月调度必须选择几号");
        }
        TreeSet<Integer> days = new TreeSet<>();
        for (String part : value.split(",")) {
            try {
                int day = Integer.parseInt(part.trim());
                if (day < 1 || day > 31) {
                    throw new NumberFormatException();
                }
                days.add(day);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("日期取值必须是 1-31: " + value);
            }
        }
        return new ArrayList<>(days);
    }

    private String normalizeWeekdays(String value, String type) {
        if (!"WEEKLY".equals(type)) {
            return null;
        }
        List<Integer> days = parseWeekdays(value);
        List<String> values = new ArrayList<>();
        for (int day : days) {
            values.add(String.valueOf(day));
        }
        return String.join(",", values);
    }

    private String normalizeMonthDays(String value, String type) {
        if (!"MONTHLY".equals(type)) {
            return null;
        }
        List<Integer> days = parseMonthDays(value);
        List<String> values = new ArrayList<>();
        for (int day : days) {
            values.add(String.valueOf(day));
        }
        return String.join(",", values);
    }

    private String rruleWeekday(int day) {
        return switch (day) {
            case 1 -> "MO";
            case 2 -> "TU";
            case 3 -> "WE";
            case 4 -> "TH";
            case 5 -> "FR";
            case 6 -> "SA";
            case 7 -> "SU";
            default -> throw ApiException.badRequest("星期取值必须是 1-7: " + day);
        };
    }

    private Map<String, Object> getWorkflow(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select w.id, w.project_id, w.code, w.name, w.env, w.description, w.folder, w.revision,
                       w.model_json::text as model, w.created_at, w.updated_at, p.code as project_code
                from platform.workflow w join platform.project p on p.id = w.project_id
                where w.id = ? and w.tenant_id = ?
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("工作流不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> version(long workflowId, int versionNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, workflow_id, version_no, status, dev_status, prod_status,
                       created_by, created_at, published_at,
                       published_by, model_json::text as model
                from platform.workflow_version where workflow_id = ? and version_no = ?
                """, workflowId, versionNo);
        if (rows.isEmpty()) {
            throw ApiException.notFound("版本不存在: v" + versionNo);
        }
        return rows.get(0);
    }

    /** model 形状校验：对象 + tasks 数组 + edges 数组（可选）。 */
    private void validateModelShape(Object model) {
        if (!(model instanceof Map<?, ?> map)) {
            throw ApiException.badRequest("model 必须是 JSON 对象（包含 tasks / edges 数组）");
        }
        Object tasks = map.get("tasks");
        if (!(tasks instanceof List<?> taskList)) {
            throw ApiException.badRequest("model.tasks 必须是数组");
        }
        for (Object task : taskList) {
            if (!(task instanceof Map<?, ?> taskMap)) {
                throw ApiException.badRequest("model.tasks 的每一项必须是对象");
            }
            Object code = taskMap.get("code");
            if (code != null && !(code instanceof String)) {
                throw ApiException.badRequest("任务 code 必须是字符串");
            }
        }
        Object edges = map.get("edges");
        if (edges != null && !(edges instanceof List<?> edgeList)) {
            throw ApiException.badRequest("model.edges 必须是数组");
        }
        if (edges instanceof List<?> edgeList) {
            for (Object edge : edgeList) {
                if (!(edge instanceof Map)) {
                    throw ApiException.badRequest("model.edges 的每一项必须是对象");
                }
            }
        }
        // SQL 节点的 engine 不能是空值/非法值；FLINK 不允许绑定数据源
        WorkflowModelRules.validateEngines(taskList);
    }

    /** JSON 语义比较：忽略空格/键顺序差异，同内容视为同一版本。 */
    private boolean sameModelJson(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        try {
            return mapper.readTree(a).equals(mapper.readTree(b));
        } catch (Exception e) {
            return false;
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw ApiException.badRequest("模型序列化失败: " + e.getMessage());
        }
    }

    private void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw ApiException.badRequest("上线调度必须填写 cron 表达式");
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) {
            throw ApiException.badRequest("cron 必须是 5 段（分 时 日 月 周）: " + cron);
        }
        for (String p : parts) {
            if (!p.matches("[0-9A-Za-z*/?,LW#-]+")) {
                throw ApiException.badRequest("cron 含非法字符: " + cron);
            }
        }
    }

    private String nullableDate(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 规范工作流目录：支持 a/b/c 多级路径；空字符串表示根目录。 */
    private String normalizeFolder(String raw) {
        if (raw == null) {
            return "";
        }
        String path = raw.trim().replace('\\', '/').replaceAll("/+", "/");
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        if (path.length() > 240) {
            throw ApiException.badRequest("目录路径不能超过 240 个字符");
        }
        for (String part : path.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw ApiException.badRequest("目录路径不合法: " + raw);
            }
            if (!part.matches("[\\w\\u4e00-\\u9fa5-]+")) {
                throw ApiException.badRequest("目录名只能包含字母、数字、下划线、短横线或中文: " + part);
            }
        }
        return path;
    }

    /** 目录可能还没有工作流，先登记成空目录，避免树里丢失。 */
    private void registerFolder(long projectId, String folder) {
        if (folder == null || folder.isEmpty()) {
            return;
        }
        String[] parts = folder.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(part);
            jdbc.update("""
                    insert into platform.workflow_folder (project_id, path)
                    values (?, ?) on conflict (project_id, path) do nothing
                    """, projectId, path.toString());
        }
    }

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("工作流名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > 128) {
            throw ApiException.badRequest("工作流名称不能超过 128 个字符");
        }
        return name;
    }

    private void requireProject(long projectId) {
        // 第三期：项目必须属于当前租户，跨租户一律 404（不暴露对象存在性）
        Long n = jdbc.queryForObject(
                "select count(*) from platform.project where id = ? and tenant_id = ?",
                Long.class, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private LocalDate requiredDate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest(field + " 日期格式不正确，应为 yyyy-MM-dd: " + value);
        }
    }

    private String dateParam(String value, String field, String fallback) {
        return value == null || value.isBlank() ? fallback : requiredDate(value, field).toString();
    }

    private String parseOptionalDate(String value, String field) {
        return value == null || value.isBlank() ? null : requiredDate(value, field).toString();
    }

    private String user(HttpServletRequest request) {
        Object u = request.getAttribute("username");
        return u == null ? "unknown" : String.valueOf(u);
    }
}
