package com.lab.dataworks.tablemanage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.AtomCheck;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.DorisErrors;
import com.lab.dataworks.common.Paging;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.common.TenantGuard;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表编辑（DDL）的预检、SQL 生成、工单与执行。
 *
 * <p>安全边界：前端只提交结构化变更，SQL 全部由服务端生成；请求体里没有 {@code sql}
 * 字段。每次变更先预检（key/partition/bucket 列、类型矩阵、副本数、字段数），
 * 再写 {@code platform.table_ddl_job}，DEV 同步执行，PROD 一律进入
 * {@code PENDING_APPROVAL}，审批通过后才执行。</p>
 *
 * <p>租户隔离：目标库必须属于当前租户的项目（或当前租户名下的库）；按 id 取工单
 * 一律带 {@code tenant_id}，取不到按 404 处理，不暴露 id 是否存在。</p>
 */
@Service
public class TableDdlService {

    private static final Logger log = LoggerFactory.getLogger(TableDdlService.class);

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    private static final Set<String> SYSTEM_DBS =
            Set.of("information_schema", "mysql", "__internal_schema");
    private static final Set<String> RESERVED = Set.of(
            "SELECT", "FROM", "WHERE", "TABLE", "DATABASE", "ORDER", "GROUP", "KEY",
            "PARTITION", "DISTRIBUTED", "PROPERTIES", "ENGINE", "INDEX", "VIEW", "JOIN",
            "UNION", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "RENAME",
            "ADD", "COLUMN", "MODIFY", "TRUNCATE", "NULL", "NOT", "AND", "OR", "BY",
            "AS", "ON", "IN", "IS", "LIKE", "BETWEEN", "EXISTS", "CASE", "WHEN", "THEN",
            "ELSE", "END", "LIMIT", "HAVING", "DISTINCT", "ASC", "DESC", "PRIMARY",
            "UNIQUE", "DUPLICATE", "AGGREGATE", "COMMENT", "DEFAULT", "VALUES", "INTO",
            "SET", "USE", "SHOW", "DESCRIBE", "EXPLAIN", "INT", "BIGINT", "VARCHAR",
            "DATE", "DATETIME", "STRING", "TEXT", "BOOLEAN", "FLOAT", "DOUBLE", "DECIMAL");

    /**
     * 工单状态全集，与 {@code table_ddl_job_status_check}（V88）逐字对齐。
     * D29：列表接口的 {@code status} 过滤必须按这份清单校验，拼错要 400。
     */
    static final Set<String> JOB_STATUSES = Set.of(
            "PENDING_APPROVAL", "PENDING", "RUNNING", "FINISHED", "FAILED", "CANCELLED", "REJECTED");

    private static final int MAX_COLUMNS = 300;
    private static final int MAX_DDL_LENGTH = 64 * 1024;
    private static final int MAX_LIVE_REPLICA = 3;

    private final JdbcTemplate jdbc;
    private final DorisAdminService doris;
    private final SessionService sessions;
    private final TenantDataScope scope;
    private final ObjectMapper mapper;
    private final WarehouseResolver warehouseResolver;
    private final com.lab.dataworks.metadata.MetadataReader metadata;

    /** 进程内表级锁；DB 的 partial unique index 兜住跨进程并发。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public TableDdlService(JdbcTemplate jdbc, DorisAdminService doris, SessionService sessions,
                           TenantDataScope scope, ObjectMapper mapper,
                           WarehouseResolver warehouseResolver,
                           com.lab.dataworks.metadata.MetadataReader metadata) {
        this.jdbc = jdbc;
        this.doris = doris;
        this.sessions = sessions;
        this.scope = scope;
        this.mapper = mapper;
        this.warehouseResolver = warehouseResolver;
        this.metadata = metadata;
    }

    // ---------------------------------------------------------------- 请求/模型

    public record ColumnSpec(String name, String type, Boolean nullable, String defaultValue,
                             String comment, String position, String afterColumn) {
    }

    public record AlterRequest(Long projectId, String operation, ColumnSpec column,
                               Map<String, String> properties, String baseDdlHash, String ddlHash,
                               Boolean ackWarnings, String reason, String confirm) {
    }

    public record RenameRequest(Long projectId, String newName, String confirm, String reason,
                                String baseDdlHash, String ddlHash, Boolean ackWarnings) {
    }

    public record TruncateRequest(Long projectId, String confirm, String reason, Boolean backup) {
    }

    public record JobRequest(Long projectId, String comment) {
    }

    /** 解析后的写操作目标：租户、项目、连接、环境、负责人。 */
    public record Target(Long projectId, String tenantId, Long connectionId, String connectionKey,
                         String database, String table, String env, String owner) {
    }

    record Column(String name, String type, boolean nullable, boolean key, String comment,
                  String defaultValue) {
    }

    record Snapshot(String database, String table, String ddl, String model,
                    List<Column> columns, Set<String> keyColumns, Set<String> bucketColumns,
                    Set<String> partitionColumns, long rows, boolean baseTable,
                    boolean hasRollup, long aliveBackends) {
    }

    public record Issue(String code, String message) {
    }

    record Plan(String operation, String ddl, boolean requiresApproval, List<Issue> warnings) {
    }

    /** 预检不通过；code 用于前端分类，message 是给用户看的中文。 */
    static final class Rejected extends RuntimeException {
        final String code;

        Rejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---------------------------------------------------------------- 预检

    public Map<String, Object> precheck(Long projectId, String database, String table,
                                        AlterRequest req) {
        if (req == null || req.operation() == null || req.operation().isBlank()) {
            throw ApiException.badRequest("operation 不能为空");
        }
        Target target = resolve(projectId, database, table);
        Snapshot snapshot = introspect(target);
        try {
            Plan plan = planAlter(target, snapshot, req);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("allowed", true);
            out.put("operation", plan.operation());
            out.put("blocking", List.of());
            out.put("warnings", plan.warnings());
            out.put("ddl", plan.ddl());
            out.put("baseDdlHash", sha256(snapshot.ddl()));
            out.put("ddlHash", sha256(plan.ddl()));
            out.put("requiresApproval", plan.requiresApproval());
            out.put("target", targetView(target));
            out.put("columns", snapshot.columns().stream().map(this::columnView).toList());
            return out;
        } catch (Rejected rejected) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("allowed", false);
            out.put("operation", safeOperation(req.operation()));
            out.put("blocking", List.of(new Issue(rejected.code, rejected.getMessage())));
            out.put("warnings", List.of());
            out.put("ddl", null);
            out.put("baseDdlHash", sha256(snapshot.ddl()));
            out.put("ddlHash", null);
            out.put("requiresApproval", "PROD".equals(target.env()));
            out.put("target", targetView(target));
            out.put("columns", snapshot.columns().stream().map(this::columnView).toList());
            return out;
        }
    }

    // ---------------------------------------------------------------- 提交变更

    public Map<String, Object> submitAlter(Long projectId, String database, String table,
                                           AlterRequest req, HttpServletRequest http) {
        Target target = resolve(projectId, database, table);
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Snapshot snapshot = introspect(target);
        Plan plan;
        try {
            plan = planAlter(target, snapshot, req);
        } catch (Rejected rejected) {
            throw ApiException.badRequest(rejected.getMessage() + "（" + rejected.code + "）");
        }
        verifyHashes(req.baseDdlHash(), req.ddlHash(), snapshot.ddl(), plan.ddl());
        verifyReason(target, plan.operation(), req.reason());
        requireAck(plan, req.ackWarnings());
        Map<String, Object> params = paramsAlter(req);
        return createJob(target, snapshot, plan, params, req.reason(), http);
    }

    public Map<String, Object> submitRename(Long projectId, String database, String table,
                                            RenameRequest req, HttpServletRequest http) {
        Target target = resolve(projectId, database, table);
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Snapshot snapshot = introspect(target);
        String newName = requireName(req.newName(), "新表名");
        checkIdentifier(newName);
        if (newName.startsWith("__") || newName.toLowerCase(Locale.ROOT).startsWith("__bak_")) {
            throw ApiException.badRequest("__bak_ / __ 开头的表名是平台保留名");
        }
        if (newName.equalsIgnoreCase(table)) {
            throw ApiException.badRequest("新表名和原表名相同（不允许仅大小写变化）");
        }
        if (tableExists(target, newName)) {
            throw ApiException.badRequest("目标表已存在: " + database + "." + newName);
        }
        String confirm = req.confirm() == null ? "" : req.confirm().trim();
        if (!confirm.equalsIgnoreCase(newName)
                && !confirm.equalsIgnoreCase(database + "." + newName)) {
            throw ApiException.badRequest("重命名确认需要输入新表名: " + newName);
        }
        String ddl = "alter table " + qi(database) + "." + qi(table)
                + " rename " + qi(newName);
        Plan plan = new Plan("ALTER_RENAME_TABLE", ddl, "PROD".equals(target.env()),
                impactWarnings(target, snapshot, "ALTER_RENAME_TABLE", null, newName));
        verifyHashes(req.baseDdlHash(), req.ddlHash(), snapshot.ddl(), ddl);
        verifyReason(target, plan.operation(), req.reason());
        requireAck(plan, req.ackWarnings());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("newName", newName);
        return createJob(target, snapshot, plan, params, req.reason(), http);
    }

    public Map<String, Object> submitTruncate(Long projectId, String database, String table,
                                              TruncateRequest req, HttpServletRequest http) {
        Target target = resolve(projectId, database, table);
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Snapshot snapshot = introspect(target);
        String confirm = req.confirm() == null ? "" : req.confirm().trim();
        if (!confirm.equalsIgnoreCase(table)) {
            throw ApiException.badRequest("清空数据需要输入表名确认: " + table);
        }
        String ddl = "truncate table " + qi(database) + "." + qi(table);
        Plan plan = new Plan("TRUNCATE_TABLE", ddl, "PROD".equals(target.env()), List.of());
        verifyReason(target, plan.operation(), req.reason());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("confirm", confirm);
        params.put("backup", Boolean.TRUE.equals(req.backup()));
        return createJob(target, snapshot, plan, params, req.reason(), http);
    }

    // ---------------------------------------------------------------- 工单

    /** D19：分页缺省页大小，与 UDF 资源列表同口径（只传 page 不传 size 时按 20）。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 工单列表（D19 分页契约）。
     *
     * <p>不带 {@code page/size}：维持原来的数组返回（兼容既有前端/脚本），条数由
     * {@code limit} 决定（夹紧 1–{@value com.lab.dataworks.common.Paging#MAX_SIZE}）。
     * 带 {@code page} 或 {@code size}：返回 {@code {items,total,page,size}}，控制层补
     * {@code X-Total-Count} 响应头；非法分页参数 400，不静默夹紧（与项目/工作流/UDF
     * 列表同口径）。原来 {@code ?page=1&size=1} 被整个忽略、仍回 limit 条。</p>
     *
     * <p>status 过滤继续走 {@link #normalizeJobStatusFilter}（D29：非法状态 400），
     * 分页改造不得回归这条。</p>
     */
    public Object jobs(String database, String table, String status, int limit,
                       Integer page, Integer size) {
        StringBuilder where = new StringBuilder(" where tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(TenantScope.current());
        appendJobFilters(where, args, database, table, status);
        String base = "select id from platform.table_ddl_job";
        boolean paged = page != null || size != null;
        if (!paged) {
            List<Object> listArgs = new ArrayList<>(args);
            listArgs.add(Math.min(Math.max(limit, Paging.MIN_SIZE), Paging.MAX_SIZE));
            List<Long> ids = jdbc.queryForList(base + where
                    + " order by id desc limit ?", Long.class, listArgs.toArray());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Long id : ids) {
                out.add(jobView(id));
            }
            return out;
        }
        Paging.requireValid(page, size);
        int p = Paging.pageOf(page);
        int s = Paging.sizeOf(size, DEFAULT_PAGE_SIZE);
        Integer total = jdbc.queryForObject("select count(*) from platform.table_ddl_job"
                + where, Integer.class, args.toArray());
        // offset 用 Paging.offset（long），大 page 不能 int 乘法溢出（D12 同源坑）
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(Paging.offset(p, s));
        List<Long> ids = jdbc.queryForList(base + where
                + " order by id desc limit ? offset ?", Long.class, pageArgs.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids) {
            items.add(jobView(id));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    /** 列表过滤条件；status 走 D29 校验（非法 400），database/table 大小写不敏感。 */
    private static void appendJobFilters(StringBuilder where, List<Object> args,
                                         String database, String table, String status) {
        if (database != null && !database.isBlank()) {
            where.append(" and lower(database_name) = lower(?)");
            args.add(database.trim());
        }
        if (table != null && !table.isBlank()) {
            where.append(" and lower(coalesce(table_name, '')) = lower(?)");
            args.add(table.trim());
        }
        String statusFilter = normalizeJobStatusFilter(status);
        if (statusFilter != null) {
            where.append(" and status = ?");
            args.add(statusFilter);
        }
    }

    /**
     * D29：列表接口的 {@code status} 过滤按 {@link #JOB_STATUSES} 校验。
     *
     * <p>缺陷现场是 {@code GET /api/tables/ddl-jobs?status=NOPE} 静默返回 200 空数组，
     * 而同类接口都报 400（{@code /api/changes?status=NOPE} → 「status 只能是 …」、
     * {@code /api/ops/instances?state=NOPE} → 「不支持的状态: nope」）。脚本拼错状态值
     * 会被当成「没有工单」，所以这里也走 400 并回显合法值。</p>
     *
     * @return 归一化（大写）后的状态；空/未传返回 {@code null} 表示不过滤
     */
    static String normalizeJobStatusFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!JOB_STATUSES.contains(value)) {
            throw ApiException.badRequest("status 只能是 "
                    + String.join(" / ", JOB_STATUSES.stream().sorted().toList())
                    + "，收到: " + raw.trim());
        }
        return value;
    }

    public Map<String, Object> job(long id) {
        return jobView(id);
    }

    public List<Map<String, Object>> events(long id) {
        requireJob(id);
        return jdbc.queryForList("""
                select id, action, actor, detail, created_at
                  from platform.table_ddl_job_event
                 where job_id = ? and tenant_id = ?
                 order by id
                """, id, TenantScope.current());
    }

    public Map<String, Object> approve(long id, String comment, HttpServletRequest http) {
        Map<String, Object> row = requireJob(id);
        if (!"PENDING_APPROVAL".equals(str(row, "status"))) {
            throw ApiException.conflict("工单当前状态不是待审批: " + str(row, "status"));
        }
        String actor = user(http);
        String requester = str(row, "requested_by");
        boolean platformAdmin = TenantGuard.platform() && AtomCheck.has(http, "system:manage");
        if (!platformAdmin) {
            if (!actor.equalsIgnoreCase(requester)) {
                boolean owner = actor.equalsIgnoreCase(projectOwner(row));
                if (!owner) {
                    throw new ApiException(403, "只有项目负责人或平台管理员可以审批该工单");
                }
            } else {
                throw new ApiException(403, "不能审批自己提交的工单");
            }
        }
        int updated = jdbc.update("""
                update platform.table_ddl_job
                   set status = 'PENDING', approver = ?, approved_at = now(),
                       approval_comment = ?, version = version + 1
                 where id = ? and tenant_id = ? and status = 'PENDING_APPROVAL'
                """, actor, comment, id, TenantScope.current());
        if (updated == 0) {
            throw ApiException.conflict("工单已被处理，请刷新后重试");
        }
        event(id, "APPROVE", actor, comment);
        audit(actor, "TABLE_DDL_APPROVE", id, str(row, "database_name") + "."
                + str(row, "table_name") + " " + comment, "SUCCESS", http);
        executeJob(id);
        return jobView(id);
    }

    public Map<String, Object> reject(long id, String comment, HttpServletRequest http) {
        Map<String, Object> row = requireJob(id);
        if (!"PENDING_APPROVAL".equals(str(row, "status"))) {
            throw ApiException.conflict("工单当前状态不是待审批: " + str(row, "status"));
        }
        if (comment == null || comment.isBlank()) {
            throw ApiException.badRequest("驳回必须填写意见");
        }
        String actor = user(http);
        boolean platformAdmin = TenantGuard.platform() && AtomCheck.has(http, "system:manage");
        if (!platformAdmin) {
            String requester = str(row, "requested_by");
            boolean owner = actor.equalsIgnoreCase(projectOwner(row));
            if (actor.equalsIgnoreCase(requester) || !owner) {
                throw new ApiException(403, "只有项目负责人或平台管理员可以驳回该工单");
            }
        }
        // D27：驳回不是审批。REJECTED 不写 approved_at / approver——审批时间与审批人
        // 只属于 approve 分支，驳回时刻只进 finished_at，口径与 CANCELLED 一致；
        // 驳回人身份保留在工单事件（REJECT，见下）与审计 TABLE_DDL_REJECT 里，不丢。
        int updated = jdbc.update("""
                update platform.table_ddl_job
                   set status = 'REJECTED', approval_comment = ?, finished_at = now(),
                       version = version + 1
                 where id = ? and tenant_id = ? and status = 'PENDING_APPROVAL'
                """, comment, id, TenantScope.current());
        if (updated == 0) {
            throw ApiException.conflict("工单已被处理，请刷新后重试");
        }
        event(id, "REJECT", actor, comment);
        audit(actor, "TABLE_DDL_REJECT", id, str(row, "database_name") + "."
                + str(row, "table_name") + " " + comment, "SUCCESS", http);
        return jobView(id);
    }

    public Map<String, Object> cancel(long id, String reason, HttpServletRequest http) {
        Map<String, Object> row = requireJob(id);
        String status = str(row, "status");
        if (!"PENDING_APPROVAL".equals(status) && !"PENDING".equals(status)) {
            throw ApiException.conflict("只有待审批/排队中的工单可以取消，当前状态: " + status);
        }
        String actor = user(http);
        boolean platformAdmin = TenantGuard.platform() && AtomCheck.has(http, "system:manage");
        if (!platformAdmin && !actor.equalsIgnoreCase(str(row, "requested_by"))) {
            throw new ApiException(403, "只有申请人或平台管理员可以取消该工单");
        }
        int updated = jdbc.update("""
                update platform.table_ddl_job
                   set status = 'CANCELLED', finished_at = now(),
                       approval_comment = coalesce(?, approval_comment), version = version + 1
                 where id = ? and tenant_id = ? and status in ('PENDING_APPROVAL', 'PENDING')
                """, reason, id, TenantScope.current());
        if (updated == 0) {
            throw ApiException.conflict("工单已被处理，请刷新后重试");
        }
        event(id, "CANCEL", actor, reason);
        audit(actor, "TABLE_DDL_CANCEL", id, str(row, "database_name") + "."
                + str(row, "table_name") + " before=" + status, "SUCCESS", http);
        return jobView(id);
    }

    private Map<String, Object> createJob(Target target, Snapshot snapshot, Plan plan,
                                          Map<String, Object> params, String reason,
                                          HttpServletRequest http) {
        String actor = user(http);
        String status = plan.requiresApproval() ? "PENDING_APPROVAL" : "PENDING";
        String baseHash = sha256(snapshot.ddl());
        String ddlHash = sha256(plan.ddl());
        if (plan.ddl().length() > MAX_DDL_LENGTH) {
            throw ApiException.badRequest("生成的 DDL 超过 64KB");
        }
        ReentrantLock lock = locks.computeIfAbsent(lockKey(target), k -> new ReentrantLock());
        lock.lock();
        long jobId;
        try {
            ensureNoActiveJob(target);
            jobId = jdbc.queryForObject("""
                    insert into platform.table_ddl_job
                        (tenant_id, project_id, doris_connection_id, connection_key,
                         database_name, table_name, env, operation, params_json,
                         base_ddl, base_ddl_hash, ddl_sql, ddl_hash, impact_json,
                         status, reason, requested_by, request_id, client_ip)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb,
                            ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class,
                    target.tenantId(), target.projectId(), target.connectionId(),
                    target.connectionKey(), target.database(), target.table(), target.env(),
                    plan.operation(), json(params), snapshot.ddl(), baseHash, plan.ddl(),
                    ddlHash, json(Map.of("warnings", plan.warnings(), "operation", plan.operation())),
                    status, reason, actor, MDC.get("requestId"),
                    AuthInterceptor.clientIp(http));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("该表已有进行中的变更工单，请等待完成或取消后再提交（ACTIVE_JOB）");
        } finally {
            lock.unlock();
        }
        event(jobId, "REQUEST", actor, plan.operation() + " env=" + target.env()
                + (reason == null ? "" : " reason=" + reason));
        audit(actor, "TABLE_DDL_REQUEST", jobId, target.database() + "." + target.table()
                + " " + plan.operation() + " env=" + target.env()
                + (reason == null ? "" : " reason=" + reason), "SUCCESS", http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pendingApproval", plan.requiresApproval());
        out.put("executed", false);
        out.put("ddl", plan.ddl());
        out.put("target", targetView(target));
        if (plan.requiresApproval()) {
            out.put("job", jobView(jobId));
            return out;
        }
        executeJob(jobId);
        Map<String, Object> job = jobView(jobId);
        out.put("executed", "FINISHED".equals(job.get("status")));
        out.put("job", job);
        return out;
    }

    private void executeJob(long jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from platform.table_ddl_job where id = ? and tenant_id = ?",
                jobId, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("工单不存在: " + jobId);
        }
        Map<String, Object> row = rows.get(0);
        int claimed = jdbc.update("""
                update platform.table_ddl_job
                   set status = 'RUNNING', started_at = now(), version = version + 1
                 where id = ? and tenant_id = ? and status = 'PENDING'
                """, jobId, TenantScope.current());
        if (claimed == 0) {
            throw ApiException.conflict("工单已被执行或取消，状态: " + str(row, "status"));
        }
        String actor = str(row, "requested_by");
        Target target = targetFromJob(row);
        String ddl = str(row, "ddl_sql");
        event(jobId, "EXECUTE", actor, "ddlHash=" + str(row, "ddl_hash"));
        audit(actor, "TABLE_DDL_EXECUTE", jobId, target.database() + "." + target.table()
                + " " + str(row, "operation"), "SUCCESS", null);
        long started = System.currentTimeMillis();
        try {
            doris.exec(target.connectionId(), ddl);
            long elapsed = System.currentTimeMillis() - started;
            // Doris 的 schema change 可能是异步作业：提交成功后先看 SHOW ALTER TABLE COLUMN。
            // 轻量变更通常没有作业记录，直接按完成处理；有 PENDING/WAITING_TXN/RUNNING
            // 则把工单留在 RUNNING，由 TableDdlJobPoller 轮询。
            Map<String, Object> alter = latestAlter(target);
            String dorisState = alter == null ? null : text(alter, "State");
            if (dorisState != null
                    && Set.of("PENDING", "WAITING_TXN", "RUNNING").contains(
                    dorisState.toUpperCase(Locale.ROOT))) {
                String dorisJobId = text(alter, "JobId");
                String progress = text(alter, "Progress");
                jdbc.update("""
                        update platform.table_ddl_job
                           set status = 'RUNNING', doris_job_id = ?, doris_state = ?,
                               doris_progress = ?, last_polled_at = now(),
                               version = version + 1
                         where id = ? and tenant_id = ? and status = 'RUNNING'
                        """, dorisJobId, dorisState, progress, jobId, TenantScope.current());
                event(jobId, "WAIT", actor, "dorisJob=" + dorisJobId
                        + " state=" + dorisState + " progress=" + progress);
                log.info("table ddl async job={} dorisJob={} state={} progress={}",
                        jobId, dorisJobId, dorisState, progress);
                return;
            }
            String afterHash = null;
            try {
                afterHash = sha256(showCreate(target));
            } catch (RuntimeException ignored) {
                // 表改名后旧名字查不到；DDL 已成功，hash 只是补充信息
            }
            jdbc.update("""
                    update platform.table_ddl_job
                       set status = 'FINISHED', finished_at = now(), duration_ms = ?,
                           ddl_after_hash = ?, doris_state = 'FINISHED', last_polled_at = now(),
                           version = version + 1
                     where id = ? and tenant_id = ? and status = 'RUNNING'
                    """, elapsed, afterHash, jobId, TenantScope.current());
            event(jobId, "FINISH", actor, "elapsedMs=" + elapsed);
            audit(actor, "TABLE_DDL_FINISH", jobId, target.database() + "." + target.table()
                    + " " + str(row, "operation") + " elapsedMs=" + elapsed, "SUCCESS", null);
            // BUG-TC1-2：工单进终态必须同步失效元数据缓存，否则「FINISHED ⇒ 回读一致」
            // 不成立——表详情走 Gravitino 的 45 秒 TTL 缓存，用户改完列立刻看还是旧结构。
            invalidateMetadata(target);
            log.info("table ddl finish job={} op={} db={} table={} elapsedMs={}",
                    jobId, str(row, "operation"), target.database(), target.table(), elapsed);
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - started;
            // D58：分类统一走 DorisErrors（原来这里自己按文本关键字分 400/502，
            // 还把 Doris 原文当响应消息抛出去，用户分不清「列名写错」和「集群挂了」）。
            // 原文照旧写进工单 error_message、事件与审计——只是不再进响应。
            DorisErrors.Cause cause = DorisErrors.classify(e);
            boolean clientFixable = cause == DorisErrors.Cause.BAD_SQL
                    || cause == DorisErrors.Cause.MISSING_OBJECT
                    || cause == DorisErrors.Cause.DENIED
                    || cause == DorisErrors.Cause.ALREADY_EXISTS;
            String code = clientFixable ? "DORIS_SQL_ERROR" : "DORIS_UNAVAILABLE";
            int status = DorisErrors.httpStatus(cause);
            String trimmed = cut(DorisErrors.raw(e), 2000);
            jdbc.update("""
                    update platform.table_ddl_job
                       set status = 'FAILED', finished_at = now(), duration_ms = ?,
                           error_code = ?, error_message = ?, retryable = ?,
                           version = version + 1
                     where id = ? and tenant_id = ? and status = 'RUNNING'
                    """, elapsed, code, trimmed, !clientFixable, jobId, TenantScope.current());
            event(jobId, "FAIL", actor, code + " " + trimmed);
            audit(actor, "TABLE_DDL_FAIL", jobId, target.database() + "." + target.table()
                    + " " + code + " " + trimmed, "FAILED", null);
            // BUG-TC1-2：失败也是终态——DDL 可能已部分生效（如超时但 Doris 收下了），
            // 缓存留着只会让回读更不可信，一并失效。
            invalidateMetadata(target);
            log.warn("table ddl fail job={} op={} db={} table={} code={} elapsedMs={} message={}",
                    jobId, str(row, "operation"), target.database(), target.table(), code,
                    elapsed, trimmed);
            // 响应只给归一化说明；完整原因看工单 error_message / 审计 / 日志
            ApiException mapped = DorisErrors.toApiException(e,
                    "执行表变更 " + target.database() + "." + target.table());
            throw new ApiException(status,
                    mapped.getMessage() + "（工单 #" + jobId + " 已记录失败原因）");
        }
    }

    /**
     * BUG-TC1-2：工单进终态后同步失效该 catalog 的元数据缓存。
     * 表详情/列表走 {@code MetadataReader} 的 45 秒 TTL 进程内缓存；
     * {@code TableController} 的建库/建表/SQL 建表/删表四处都已失效，本 Service 的
     * alter / rename / truncate 此前一处都没做，导致「工单 FINISHED 但回读旧结构」
     * 最长滞后一个 TTL。失败/取消同样是终态（DDL 可能部分生效），一并覆盖。
     * {@code invalidateProject} 内部已捕获异常，不会反过来影响工单终态写入。
     */
    private void invalidateMetadata(Target target) {
        metadata.invalidateProject(target.projectId());
    }

    /** Doris schema change 可能异步；取该表最新一条 SHOW ALTER TABLE COLUMN 记录。 */
    Map<String, Object> latestAlter(Target target) {
        try {
            List<Map<String, Object>> rows = doris.queryAs(target.connectionId(),
                    "show alter table column from " + qi(target.database()));
            Map<String, Object> best = null;
            long bestId = -1;
            for (Map<String, Object> row : rows) {
                if (!target.table().equalsIgnoreCase(text(row, "TableName"))) {
                    continue;
                }
                String raw = text(row, "JobId");
                long id;
                try {
                    id = raw == null ? -1 : Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    id = -1;
                }
                if (best == null || id >= bestId) {
                    best = row;
                    bestId = id;
                }
            }
            return best;
        } catch (RuntimeException e) {
            log.warn("show alter table column failed db={} table={}: {}",
                    target.database(), target.table(), e.getMessage());
            return null;
        }
    }

    /** 调度器入口：跨租户轮询 RUNNING 的异步 schema change。 */
    public void pollRunningJobs() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    select * from platform.table_ddl_job
                     where status = 'RUNNING' and doris_job_id is not null
                     order by id limit 20
                    """);
        } catch (Exception e) {
            log.warn("poll table ddl jobs failed: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            TenantScope.set(str(row, "tenant_id"));
            try {
                pollOne(row);
            } catch (Exception e) {
                log.warn("poll table ddl job {} failed: {}", row.get("id"), e.getMessage());
            } finally {
                TenantScope.clear();
            }
        }
    }

    private void pollOne(Map<String, Object> row) {
        long jobId = ((Number) row.get("id")).longValue();
        Target target = targetFromJob(row);
        Map<String, Object> alter = latestAlter(target);
        if (alter == null) {
            return;
        }
        String state = text(alter, "State");
        String progress = text(alter, "Progress");
        String dorisJobId = text(alter, "JobId");
        if (state == null) {
            return;
        }
        jdbc.update("""
                update platform.table_ddl_job
                   set doris_state = ?, doris_progress = ?, doris_job_id = ?,
                       last_polled_at = now(), version = version + 1
                 where id = ? and tenant_id = ? and status = 'RUNNING'
                """, state, progress, dorisJobId, jobId, TenantScope.current());
        String upper = state.toUpperCase(Locale.ROOT);
        if ("FINISHED".equals(upper)) {
            String afterHash = null;
            try {
                afterHash = sha256(showCreate(target));
            } catch (RuntimeException ignored) {
                // 改名后旧名字查不到；不影响完成状态
            }
            Long elapsed = jdbc.queryForObject("""
                    select (extract(epoch from (now() - coalesce(started_at, requested_at)))
                            * 1000)::bigint
                      from platform.table_ddl_job where id = ?
                    """, Long.class, jobId);
            jdbc.update("""
                    update platform.table_ddl_job
                       set status = 'FINISHED', finished_at = now(), duration_ms = ?,
                           ddl_after_hash = ?, doris_state = 'FINISHED', version = version + 1
                     where id = ? and tenant_id = ? and status = 'RUNNING'
                    """, elapsed, afterHash, jobId, TenantScope.current());
            String actor = str(row, "requested_by");
            event(jobId, "FINISH", actor, "dorisJob=" + dorisJobId + " elapsedMs=" + elapsed);
            audit(actor, "TABLE_DDL_FINISH", jobId, target.database() + "." + target.table()
                    + " elapsedMs=" + elapsed, "SUCCESS", null);
            // BUG-TC1-2：异步 schema change 完成同样要失效缓存（缺陷现场就是这条路：
            // drop column 工单 FINISHED 后 45 秒内回读仍带旧列）
            invalidateMetadata(target);
            log.info("table ddl async finish job={} dorisJob={} elapsedMs={}",
                    jobId, dorisJobId, elapsed);
        } else if ("CANCELLED".equals(upper) || "FAILED".equals(upper)) {
            String message = text(alter, "Msg");
            if (message == null || message.isBlank()) {
                message = text(alter, "ErrorMsg");
            }
            message = cut(message, 2000);
            jdbc.update("""
                    update platform.table_ddl_job
                       set status = 'FAILED', finished_at = now(), error_code = ?,
                           error_message = ?, retryable = false, version = version + 1
                     where id = ? and tenant_id = ? and status = 'RUNNING'
                    """, "SCHEMA_CHANGE_" + upper, message, jobId, TenantScope.current());
            String actor = str(row, "requested_by");
            event(jobId, "FAIL", actor, "SCHEMA_CHANGE_" + upper + " " + message);
            audit(actor, "TABLE_DDL_FAIL", jobId, target.database() + "." + target.table()
                    + " SCHEMA_CHANGE_" + upper + " " + message, "FAILED", null);
            // BUG-TC1-2：取消/失败的 schema change 也可能已经改过结构，一并失效
            invalidateMetadata(target);
            log.warn("table ddl async fail job={} dorisJob={} state={} message={}",
                    jobId, dorisJobId, state, message);
        }
    }

    // ---------------------------------------------------------------- 规划

    private Plan planAlter(Target target, Snapshot snapshot, AlterRequest req) {
        String operation = normalizeOperation(req.operation());
        boolean requiresApproval = "PROD".equals(target.env());
        if (!snapshot.baseTable()) {
            throw new Rejected("NOT_BASE_TABLE", "只有 base table 可以做结构变更，视图请重建");
        }
        if (snapshot.hasRollup()) {
            throw new Rejected("MV_OR_ROLLUP",
                    "该表有 rollup / 物化视图，P1 不支持结构变更；请先删除 rollup 或联系管理员");
        }
        if ("AGGREGATE".equals(snapshot.model())
                && !"ALTER_SET_PROPERTIES".equals(operation)) {
            throw new Rejected("UNSUPPORTED_TABLE_MODEL",
                    "AGGREGATE 模型表 P1 不支持结构变更");
        }
        Plan plan = switch (operation) {
            case "ALTER_ADD_COLUMN" -> addColumn(snapshot, req, requiresApproval);
            case "ALTER_MODIFY_COLUMN" -> modifyColumn(snapshot, req, requiresApproval);
            case "ALTER_DROP_COLUMN" -> dropColumn(snapshot, req, requiresApproval);
            case "ALTER_SET_PROPERTIES" -> setProperties(target, snapshot, req, requiresApproval);
            default -> throw new Rejected("UNSUPPORTED_OPERATION",
                    "不支持的变更类型: " + req.operation());
        };
        List<Issue> warnings = impactWarnings(target, snapshot, plan.operation(),
                req.column() == null ? null : req.column().name(), null);
        return new Plan(plan.operation(), plan.ddl(), plan.requiresApproval(), warnings);
    }

    /**
     * 影响面预检（P1）：血缘、数据服务、BI、Flink、同步、质量、授权、分级。
     * 查询失败只记日志、不阻断预检（影响面是警告，不是安全边界）。
     */
    private List<Issue> impactWarnings(Target target, Snapshot s, String operation,
                                       String column, String newName) {
        List<Issue> out = new ArrayList<>();
        String db = s.database().toLowerCase(Locale.ROOT);
        String table = s.table().toLowerCase(Locale.ROOT);
        String full = db + "." + table;
        String like = "%" + table.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        String col = column == null ? null : column.toLowerCase(Locale.ROOT);
        boolean columnChange = col != null
                && ("ALTER_MODIFY_COLUMN".equals(operation)
                || "ALTER_DROP_COLUMN".equals(operation));

        List<Object> lineageArgs = new ArrayList<>(List.of(
                target.tenantId(), full, table, full, table));
        if (columnChange) {
            lineageArgs.add(col);
            lineageArgs.add(col);
        }
        long lineage = countQuery("""
                select count(*) from platform.lineage_edge
                 where tenant_id = ? and (
                       lower(source_table) in (?, ?) or lower(target_table) in (?, ?))
                """ + (columnChange
                ? " and (lower(source_column) = ? or lower(target_column) = ?)"
                : ""), lineageArgs.toArray());
        if (lineage > 0) {
            out.add(new Issue("DOWNSTREAM_LINEAGE",
                    "血缘中有 " + lineage + " 条上下游引用" + (columnChange ? "（含该列）" : "")));
        }

        long services = countQuery("""
                select count(*) from platform.data_service
                 where tenant_id = ? and lower(sql_text) like ?
                """, target.tenantId(), like);
        services += countQuery("""
                select count(*) from platform.data_service_release r
                  join platform.data_service s on s.id = r.service_id
                 where s.tenant_id = ? and lower(r.sql_text) like ?
                """, target.tenantId(), like);
        if (services > 0) {
            out.add(new Issue("DATA_SERVICE_REF",
                    "有 " + services + " 个数据服务版本引用该表；发布新版本前请先改 SQL"));
        }

        long bi = countQuery("""
                select count(*) from platform.bi_chart c
                  join platform.bi_dashboard d on d.id = c.dashboard_id
                 where d.tenant_id = ? and lower(c.sql_text) like ?
                """, target.tenantId(), like);
        bi += countQuery("""
                select count(*) from platform.bi_dashboard_release r
                  join platform.bi_dashboard d on d.id = r.dashboard_id
                 where d.tenant_id = ? and lower(r.snapshot::text) like ?
                """, target.tenantId(), like);
        if (bi > 0) {
            out.add(new Issue("BI_REF", "有 " + bi + " 个 BI 图表/发布快照引用该表"));
        }

        long flink = countQuery("""
                select count(*) from platform.flink_sql_job
                 where tenant_id = ? and lower(sql_text) like ?
                """, target.tenantId(), like);
        if (flink > 0) {
            out.add(new Issue("FLINK_REF", "有 " + flink + " 个 FlinkSQL 任务引用该表"));
        }

        long sync = countQuery("""
                select count(*) from platform.sync_task
                 where tenant_id = ? and (
                       (lower(sink_database) = ? and lower(sink_table) = ?)
                       or lower(coalesce(source_query, '')) like ?)
                """, target.tenantId(), db, table, like);
        if (sync > 0) {
            out.add(new Issue("SYNC_REF", "有 " + sync + " 个同步任务引用该表（源查询或目标表）"));
        }

        List<Object> qualityArgs = new ArrayList<>(List.of(target.tenantId(), full, table));
        if (columnChange) {
            qualityArgs.add(col);
        }
        long quality = countQuery("""
                select count(*) from platform.quality_rule
                 where tenant_id = ? and lower(table_name) in (?, ?)
                """ + (columnChange ? " and lower(coalesce(column_name, '')) = ?" : ""),
                qualityArgs.toArray());
        if (quality > 0) {
            out.add(new Issue("QUALITY_REF", "有 " + quality + " 条质量规则引用该表"
                    + (columnChange ? "（含该列）" : "")));
        }

        long grants = countQuery("""
                select count(*) from platform.access_request ar
                 where ar.status = 'APPROVED' and lower(ar.schema_name) = ?
                   and (ar.table_name is null or lower(ar.table_name) in (?, '*'))
                   and (ar.requester_tenant = ? or exists (
                         select 1 from platform.managed_table mt
                          where mt.tenant = ? and lower(mt.schema_name) = ?
                            and (ar.resource_type = 'DATABASE'
                                 or lower(mt.table_name) = ?)))
                """, db, table, target.tenantId(), target.tenantId(), db, table);
        if (grants > 0) {
            out.add(new Issue("GRANT_EXISTS", "有 " + grants + " 条已通过的表/库授权"));
        }

        List<Map<String, Object>> managed = jdbc.queryForList("""
                select data_level from platform.managed_table
                 where tenant = ? and lower(schema_name) = ? and lower(table_name) = ?
                 limit 1
                """, target.tenantId(), db, table);
        if (!managed.isEmpty()) {
            String level = str(managed.get(0), "data_level");
            if ("L3".equalsIgnoreCase(level) || "L4".equalsIgnoreCase(level)) {
                out.add(new Issue("SENSITIVE_TABLE",
                        "该表分级为 " + level + "，变更会影响脱敏/权限口径"));
            }
        }

        if (columnChange) {
            long masked = countQuery("""
                    select count(*) from platform.managed_column c
                      join platform.managed_table t on t.id = c.table_id
                     where t.tenant = ? and lower(t.schema_name) = ?
                       and lower(t.table_name) = ? and lower(c.column_name) = ?
                       and c.mask_rule <> 'NONE'
                    """, target.tenantId(), db, table, col);
            if (masked > 0) {
                out.add(new Issue("MASKED_COLUMN", "该列配置了脱敏规则，修改/删除会影响查询结果"));
            }
        }
        return out;
    }

    private long countQuery(String sql, Object... args) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("table impact query failed: {}", e.getMessage());
            return 0;
        }
    }

    /** 有影响面警告时必须显式确认，防止前端跳过勾选直接调接口。 */
    private void requireAck(Plan plan, Boolean ackWarnings) {
        if (plan.warnings().isEmpty() || Boolean.TRUE.equals(ackWarnings)) {
            return;
        }
        String codes = plan.warnings().stream().map(Issue::code)
                .distinct().collect(java.util.stream.Collectors.joining(","));
        throw ApiException.badRequest(
                "存在影响面警告，请确认影响后再提交（ackWarnings=true）：" + codes);
    }

    private Plan addColumn(Snapshot s, AlterRequest req, boolean approval) {
        ColumnSpec spec = requireColumn(req);
        String name = requireName(spec.name(), "字段名");
        checkIdentifier(name);
        if (findColumn(s, name) != null) {
            throw new Rejected("COLUMN_EXISTS", "字段已存在: " + name);
        }
        DorisType type = parseTargetType(spec.type());
        boolean nullable = spec.nullable() == null || spec.nullable();
        String defaultValue = blankToNull(spec.defaultValue());
        validateDefault(type, defaultValue, nullable, s.rows());
        if (s.columns().size() + 1 > MAX_COLUMNS) {
            throw new Rejected("COLUMN_COUNT_EXCEEDED",
                    "字段数超过平台上限 " + MAX_COLUMNS + "（当前 " + s.columns().size() + "）");
        }
        StringBuilder ddl = new StringBuilder();
        ddl.append("alter table ").append(qi(s.database())).append(".").append(qi(s.table()))
                .append(" add column ").append(qi(name)).append(" ").append(type.canonical());
        ddl.append(nullable ? " NULL" : " NOT NULL");
        if (defaultValue != null) {
            ddl.append(" default ").append(defaultLiteral(type, defaultValue));
        }
        if (spec.comment() != null && !spec.comment().isBlank()) {
            if (spec.comment().length() > 255) {
                throw new Rejected("COMMENT_TOO_LONG", "字段注释不能超过 255 字符");
            }
            ddl.append(" comment ").append(quote(spec.comment()));
        }
        String position = spec.position() == null ? "APPEND"
                : spec.position().trim().toUpperCase(Locale.ROOT);
        if ("FIRST".equals(position)) {
            ddl.append(" first");
        } else if ("AFTER".equals(position)) {
            String after = requireName(spec.afterColumn(), "AFTER 字段");
            if (after.equalsIgnoreCase(name)) {
                throw new Rejected("AFTER_COLUMN_NOT_FOUND", "AFTER 不能指向新字段自己");
            }
            if (findColumn(s, after) == null) {
                throw new Rejected("AFTER_COLUMN_NOT_FOUND", "AFTER 指定的字段不存在: " + after);
            }
            ddl.append(" after ").append(qi(after));
        } else if (!"APPEND".equals(position)) {
            throw new Rejected("POSITION_INVALID", "位置只支持 APPEND / FIRST / AFTER");
        }
        return new Plan("ALTER_ADD_COLUMN", ddl.toString(), approval, List.of());
    }

    private Plan modifyColumn(Snapshot s, AlterRequest req, boolean approval) {
        ColumnSpec spec = requireColumn(req);
        String name = requireName(spec.name(), "字段名");
        Column current = findColumn(s, name);
        if (current == null) {
            throw new Rejected("COLUMN_NOT_FOUND", "字段不存在: " + name);
        }
        checkNotKeyColumn(s, current);
        DorisType from = DorisType.parse(current.type());
        if (from == null) {
            throw new Rejected("TYPE_FORBIDDEN", "无法解析当前字段类型: " + current.type());
        }
        DorisType to = blankToNull(spec.type()) == null ? from : parseTargetType(spec.type());
        String widen = DorisType.checkWiden(from, to);
        if (widen != null) {
            throw new Rejected("TYPE_FORBIDDEN", widen);
        }
        boolean nullable = current.nullable();
        if (spec.nullable() != null) {
            if (!current.nullable() && spec.nullable()) {
                nullable = true;
            } else if (current.nullable() && !spec.nullable()) {
                throw new Rejected("NULLABILITY_FORBIDDEN",
                        "Doris 不支持 NULL → NOT NULL；请新建字段并迁移数据");
            }
        }
        if (blankToNull(spec.defaultValue()) != null) {
            throw new Rejected("DEFAULT_READONLY", "P1 不支持修改默认值；加列时可以指定默认值");
        }
        String comment = spec.comment() == null ? current.comment() : spec.comment();
        if (comment != null && comment.length() > 255) {
            throw new Rejected("COMMENT_TOO_LONG", "字段注释不能超过 255 字符");
        }
        StringBuilder ddl = new StringBuilder();
        ddl.append("alter table ").append(qi(s.database())).append(".").append(qi(s.table()))
                .append(" modify column ").append(qi(current.name())).append(" ")
                .append(to.canonical()).append(nullable ? " NULL" : " NOT NULL");
        if (current.defaultValue() != null && !"NULL".equalsIgnoreCase(current.defaultValue())) {
            ddl.append(" default ").append(defaultLiteral(to, current.defaultValue()));
        }
        if (comment != null && !comment.isBlank()) {
            ddl.append(" comment ").append(quote(comment));
        }
        return new Plan("ALTER_MODIFY_COLUMN", ddl.toString(), approval, List.of());
    }

    private Plan dropColumn(Snapshot s, AlterRequest req, boolean approval) {
        ColumnSpec spec = requireColumn(req);
        String name = requireName(spec.name(), "字段名");
        Column current = findColumn(s, name);
        if (current == null) {
            throw new Rejected("COLUMN_NOT_FOUND", "字段不存在: " + name);
        }
        checkNotKeyColumn(s, current);
        if (s.columns().size() - 1 < 1) {
            throw new Rejected("LAST_COLUMN", "删除后至少需要保留 1 个字段");
        }
        String confirm = req.confirm() == null ? "" : req.confirm().trim();
        if (!confirm.equalsIgnoreCase(s.database() + "." + s.table())) {
            throw new Rejected("CONFIRM_MISMATCH",
                    "删除字段需要输入 " + s.database() + "." + s.table() + " 确认");
        }
        String ddl = "alter table " + qi(s.database()) + "." + qi(s.table())
                + " drop column " + qi(current.name());
        return new Plan("ALTER_DROP_COLUMN", ddl, approval, List.of());
    }

    private Plan setProperties(Target target, Snapshot s, AlterRequest req, boolean approval) {
        Map<String, String> props = req.properties();
        if (props == null || props.isEmpty()) {
            throw new Rejected("PROPERTIES_EMPTY", "没有要修改的属性");
        }
        StringBuilder set = new StringBuilder();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if ("replication_allocation".equalsIgnoreCase(key)) {
                int replica = parseReplication(value);
                if (replica < 1 || replica > MAX_LIVE_REPLICA) {
                    throw new Rejected("REPLICA_INVALID", "副本数需在 1-" + MAX_LIVE_REPLICA);
                }
                if (s.aliveBackends() > 0 && replica > s.aliveBackends()) {
                    throw new Rejected("REPLICA_EXCEEDS_BACKENDS",
                            "当前只有 " + s.aliveBackends() + " 个存活 BE，副本数最多 "
                                    + Math.min(s.aliveBackends(), MAX_LIVE_REPLICA));
                }
                value = "tag.location.default: " + replica;
            } else if ("storage_policy".equalsIgnoreCase(key)) {
                if (value.isBlank()) {
                    throw new Rejected("STORAGE_POLICY_INVALID", "存储策略不能为空");
                }
                requireStoragePolicy(target, value);
            } else {
                throw new Rejected("PROPERTY_FORBIDDEN",
                        "P1 只允许修改 replication_allocation / storage_policy，不支持: " + key);
            }
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append("\"").append(key).append("\" = ").append(quote(value));
        }
        String ddl = "alter table " + qi(s.database()) + "." + qi(s.table())
                + " set (" + set + ")";
        return new Plan("ALTER_SET_PROPERTIES", ddl, approval, List.of());
    }

    // ---------------------------------------------------------------- 元数据

    private Snapshot introspect(Target target) {
        List<Map<String, Object>> all = doris.queryAs(target.connectionId(),
                "select table_schema, table_name, table_type, table_rows "
                        + "from information_schema.tables");
        boolean found = false;
        boolean base = false;
        long rows = 0;
        for (Map<String, Object> row : all) {
            if (!target.database().equalsIgnoreCase(text(row, "TABLE_SCHEMA"))
                    || !target.table().equalsIgnoreCase(text(row, "TABLE_NAME"))) {
                continue;
            }
            found = true;
            String type = text(row, "TABLE_TYPE");
            base = type == null || type.equalsIgnoreCase("BASE TABLE");
            Object n = row.get("TABLE_ROWS");
            if (n instanceof Number number) {
                rows = number.longValue();
            } else if (text(row, "TABLE_ROWS") != null) {
                try {
                    rows = Long.parseLong(text(row, "TABLE_ROWS"));
                } catch (NumberFormatException ignored) {
                    rows = 0;
                }
            }
            break;
        }
        if (!found) {
            throw ApiException.notFound("表不存在: " + target.database() + "." + target.table());
        }
        if (!base) {
            throw ApiException.badRequest("不是 base table（视图/外部表不支持结构变更）: "
                    + target.database() + "." + target.table());
        }
        List<Map<String, Object>> desc = doris.queryAs(target.connectionId(),
                "desc " + qi(target.database()) + "." + qi(target.table()));
        List<Column> columns = new ArrayList<>();
        for (Map<String, Object> row : desc) {
            String key = text(row, "Key");
            String def = text(row, "Default");
            columns.add(new Column(
                    text(row, "Field"),
                    text(row, "Type"),
                    "Yes".equalsIgnoreCase(text(row, "Null")),
                    key != null && (key.equalsIgnoreCase("true") || key.equalsIgnoreCase("key")),
                    text(row, "Comment"),
                    def == null || def.isBlank() || "NULL".equalsIgnoreCase(def) ? null : def));
        }
        if (columns.isEmpty()) {
            throw ApiException.notFound("表不存在或没有字段: "
                    + target.database() + "." + target.table());
        }
        String ddl = showCreate(target);
        String model = "UNKNOWN";
        Set<String> keys = new LinkedHashSet<>();
        Matcher km = Pattern.compile("(?i)\\b(DUPLICATE|UNIQUE|AGGREGATE)\\s+KEY\\s*\\(([^)]*)\\)")
                .matcher(ddl);
        if (km.find()) {
            model = km.group(1).toUpperCase(Locale.ROOT);
            keys.addAll(identifiers(km.group(2)));
        }
        Set<String> buckets = new LinkedHashSet<>();
        Matcher bm = Pattern.compile("(?i)DISTRIBUTED\\s+BY\\s+(?:HASH|RANDOM)\\s*\\(([^)]*)\\)")
                .matcher(ddl);
        if (bm.find()) {
            buckets.addAll(identifiers(bm.group(1)));
        }
        Set<String> partitions = new LinkedHashSet<>();
        Matcher pm = Pattern.compile("(?i)PARTITION\\s+BY\\s+(?:RANGE|LIST|HASH)?\\s*\\(([^)]*)\\)")
                .matcher(ddl);
        if (pm.find()) {
            partitions.addAll(identifiers(pm.group(1)));
        }
        boolean rollup = Pattern.compile("(?i)\\bROLLUP\\s*\\(").matcher(ddl).find();
        boolean mv = materializedViewExists(target);
        return new Snapshot(target.database(), target.table(), ddl, model, columns, keys,
                buckets, partitions, rows, true, rollup || mv, aliveBackends(target));
    }

    private String showCreate(Target target) {
        List<Map<String, Object>> rows = doris.queryAs(target.connectionId(),
                "show create table " + qi(target.database()) + "." + qi(target.table()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("表不存在: " + target.database() + "." + target.table());
        }
        return String.valueOf(rows.get(0).values().stream().skip(1).findFirst().orElse(""));
    }

    private boolean materializedViewExists(Target target) {
        try {
            List<Map<String, Object>> rows = doris.queryAs(target.connectionId(),
                    "show materialized views from " + qi(target.database()));
            for (Map<String, Object> row : rows) {
                for (Object value : row.values()) {
                    if (value != null && target.table().equalsIgnoreCase(String.valueOf(value))) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("SHOW MATERIALIZED VIEWS 不可用，跳过 MV 检查: {}", e.getMessage());
        }
        return false;
    }

    private long aliveBackends(Target target) {
        try {
            long alive = 0;
            for (Map<String, Object> row : doris.queryAs(target.connectionId(), "show backends")) {
                String aliveValue = text(row, "Alive");
                String decommissioned = text(row, "SystemDecommissioned");
                if ("true".equalsIgnoreCase(aliveValue)
                        && !"true".equalsIgnoreCase(decommissioned)) {
                    alive++;
                }
            }
            return alive;
        } catch (RuntimeException e) {
            log.debug("SHOW BACKENDS 不可用，跳过存活 BE 计数: {}", e.getMessage());
            return -1;
        }
    }

    private boolean tableExists(Target target, String table) {
        for (Map<String, Object> row : doris.queryAs(target.connectionId(),
                "select table_schema, table_name from information_schema.tables")) {
            if (target.database().equalsIgnoreCase(text(row, "TABLE_SCHEMA"))
                    && table.equalsIgnoreCase(text(row, "TABLE_NAME"))) {
                return true;
            }
        }
        return false;
    }

    private void requireStoragePolicy(Target target, String name) {
        try {
            for (Map<String, Object> row : doris.queryAs(target.connectionId(),
                    "show storage policy")) {
                if (name.equalsIgnoreCase(text(row, "Name"))) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            throw new Rejected("STORAGE_POLICY_INVALID",
                    "无法读取存储策略列表，请稍后重试: " + e.getMessage());
        }
        throw new Rejected("STORAGE_POLICY_INVALID", "存储策略不存在: " + name);
    }

    // ---------------------------------------------------------------- 校验

    private void checkNotKeyColumn(Snapshot s, Column column) {
        if (column.key() || containsIgnoreCase(s.keyColumns(), column.name())) {
            throw new Rejected("KEY_COLUMN", "主键列不能删除或修改: " + column.name());
        }
        if (containsIgnoreCase(s.bucketColumns(), column.name())) {
            throw new Rejected("BUCKET_COLUMN", "分桶列不能删除或修改: " + column.name());
        }
        if (containsIgnoreCase(s.partitionColumns(), column.name())) {
            throw new Rejected("PARTITION_COLUMN", "分区列不能删除或修改: " + column.name());
        }
    }

    private void checkIdentifier(String name) {
        if (RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
            throw new Rejected("RESERVED_WORD", "字段名是 Doris 保留字: " + name);
        }
    }

    private void validateDefault(DorisType type, String value, boolean nullable, long rows) {
        if (value == null) {
            if (!nullable && rows > 0) {
                throw new Rejected("DEFAULT_REQUIRED",
                        "表里已有 " + rows + " 行数据，新增 NOT NULL 字段必须填写默认值");
            }
            return;
        }
        if (DorisType.isNonDeterministicDefault(value)) {
            throw new Rejected("DEFAULT_INVALID",
                    "默认值必须是常量，不能使用 now()/uuid()/rand() 等函数");
        }
        if (!nullable && "null".equalsIgnoreCase(value.trim())) {
            throw new Rejected("DEFAULT_INVALID", "NOT NULL 字段的默认值不能是 NULL");
        }
        if (!type.acceptsDefault(value)) {
            throw new Rejected("DEFAULT_INVALID", "默认值 " + value + " 不符合类型 "
                    + type.canonical());
        }
    }

    private DorisType parseTargetType(String raw) {
        try {
            return DorisType.target(raw);
        } catch (ApiException e) {
            throw new Rejected("TYPE_FORBIDDEN", e.getMessage());
        }
    }

    private void verifyHashes(String baseHash, String ddlHash, String currentDdl,
                              String plannedDdl) {
        if (baseHash != null && !baseHash.isBlank()
                && !baseHash.equals(sha256(currentDdl))) {
            throw ApiException.conflict("表结构已变化，请重新预检（STALE_SCHEMA）");
        }
        if (ddlHash != null && !ddlHash.isBlank() && !ddlHash.equals(sha256(plannedDdl))) {
            throw ApiException.conflict("预览的 DDL 与当前表单不一致，请重新预检（DDL_CHANGED）");
        }
    }

    /**
     * 生产库闸门的结果（BUG-TC4-1）。
     *
     * @param env    这次 DDL 的落点环境（DEV / PROD），响应要回显，验收才核对得上
     * @param queued 非 null 表示已经挂成 {@code PENDING_APPROVAL} 工单、**没有直接执行**；
     *               null 表示 DEV 库，调用方照常下发
     */
    public record ProductionGate(String env, Map<String, Object> queued) {
    }

    /**
     * 建表 / SQL 建表 / 删表这三条「直接下发 Doris」的入口以前不看环境（BUG-TC4-1）：
     * alter 族有「PROD 必须写原因 + 挂审批」的闸门，这三条没有，
     * 一个 DEVELOPER 账号就能在生产库里建表、删表（删表只要 {@code confirm=<表名>}）。
     *
     * <p>这里补上同一道闸门：环境判定复用 {@link #resolve}（项目 dev/prod 两列是权威），
     * PROD 无原因 → 400（沿用 alter 族既有文案，不另立一套）；PROD 有原因 → 挂
     * {@code PENDING_APPROVAL} 工单，审批通过后才由 {@link #execute} 下发。</p>
     *
     * @param operation CREATE_TABLE / CREATE_TABLE_SQL / DROP_TABLE
     */
    /**
     * 只读落点判定（DEV / PROD），<b>不建工单、不抛原因错</b>——CONFLICT-WM1 为删表端点加的口子：
     * 生产库直接删表改为一律 400 指向变更单（BUG-TC6-5，较新口径），不能再走 {@link #gateProductionDdl}
     * 的「有原因即挂工单」通道（那会在变更单之外造出第二套不带备份/回滚的审批）。
     * 口径与 {@link #targetForGate} 同一份：项目 dev/prod 两列权威，解析不出按 {@code *_prd} 命名兜底。
     */
    public String gateEnv(Long projectId, String database, String table) {
        return targetForGate(projectId, database, table).env();
    }

    public ProductionGate gateProductionDdl(String operation, Long projectId, String database,
                                            String table, String ddl, String reason,
                                            HttpServletRequest http) {
        Target target = targetForGate(projectId, database, table);
        if (!"PROD".equals(target.env())) {
            return new ProductionGate(target.env(), null);
        }
        verifyReason(target, operation, reason);
        return new ProductionGate("PROD", submitApprovalJob(operation, target, ddl, reason, http));
    }

    /**
     * 环境判定优先按项目的 dev/prod 两列；解析不出来（库不属于该项目、系统库等）时
     * 按命名约定兜一道——**不能让闸门出现空档**，归属校验仍由调用方原有的
     * {@code TenantDataScope.requireDatabase} 负责，这里不越权改判。
     */
    private Target targetForGate(Long projectId, String database, String table) {
        try {
            return resolve(projectId, database, table);
        } catch (ApiException e) {
            if (e.getStatus() != 404) {
                throw e;
            }
            Long connectionId = projectId == null ? null
                    : warehouseResolver.connectionIdForProject(projectId);
            String env = database != null
                    && database.toLowerCase(Locale.ROOT).endsWith("_prd") ? "PROD" : "DEV";
            log.warn("production gate: env 不能按项目解析（{}），{} 按命名约定判成 {}",
                    e.getMessage(), database, env);
            return new Target(projectId, TenantScope.current(), connectionId,
                    connectionKey(connectionId), database, table, env, null);
        }
    }

    /** 挂一张待审批工单（不执行）；审计与事件口径与 alter 族一致。 */
    private Map<String, Object> submitApprovalJob(String operation, Target target, String ddl,
                                                  String reason, HttpServletRequest http) {
        if (ddl == null || ddl.isBlank()) {
            throw ApiException.badRequest("DDL 不能为空（" + operation + "）");
        }
        if (ddl.length() > MAX_DDL_LENGTH) {
            throw ApiException.badRequest("生成的 DDL 超过 64KB");
        }
        String actor = user(http);
        String baseDdl = "";
        try {
            baseDdl = showCreate(target);       // 建新表时还没有「变更前快照」，取不到就留空
        } catch (RuntimeException ignored) {
            // 不是错误：CREATE_TABLE 本来就没有基线；这里不把它当失败，也不静默改判
        }
        ReentrantLock lock = locks.computeIfAbsent(lockKey(target), k -> new ReentrantLock());
        long jobId;
        lock.lock();
        try {
            ensureNoActiveJob(target);
            jobId = jdbc.queryForObject("""
                    insert into platform.table_ddl_job
                        (tenant_id, project_id, doris_connection_id, connection_key,
                         database_name, table_name, env, operation, params_json,
                         base_ddl, base_ddl_hash, ddl_sql, ddl_hash, impact_json,
                         status, reason, requested_by, request_id, client_ip)
                    values (?, ?, ?, ?, ?, ?, 'PROD', ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb,
                            'PENDING_APPROVAL', ?, ?, ?, ?)
                    returning id
                    """, Long.class,
                    target.tenantId(), target.projectId(), target.connectionId(),
                    target.connectionKey(), target.database(), target.table(), operation,
                    json(Map.of("gate", "BUG-TC4-1", "source", "tablemanage-direct-ddl")),
                    baseDdl, sha256(baseDdl), ddl, sha256(ddl),
                    json(Map.of("operation", operation, "env", "PROD",
                            "warnings", List.of("生产库 DDL 需审批通过后才执行"))),
                    reason, actor, MDC.get("requestId"), AuthInterceptor.clientIp(http));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("该表已有进行中的变更工单，请等待完成或取消后再提交（ACTIVE_JOB）");
        } finally {
            lock.unlock();
        }
        event(jobId, "REQUEST", actor, operation + " env=PROD reason=" + reason);
        audit(actor, "TABLE_DDL_REQUEST", jobId, target.database() + "." + target.table()
                + " " + operation + " env=PROD" + (reason == null ? "" : " reason=" + reason),
                "SUCCESS", http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pendingApproval", true);
        out.put("executed", false);
        out.put("env", "PROD");
        out.put("ddl", ddl);
        out.put("target", targetView(target));
        out.put("job", jobView(jobId));
        out.put("message", "生产库 " + target.database() + " 的" + labelOf(operation)
                + "已提交审批（工单 #" + jobId + "），审批通过后才执行");
        return out;
    }

    private static String labelOf(String operation) {
        return switch (operation) {
            case "CREATE_TABLE" -> "建表 ";
            case "CREATE_TABLE_SQL" -> "SQL 建表 ";
            case "DROP_TABLE" -> "删表 ";
            default -> operation + " ";
        };
    }

    private void verifyReason(Target target, String operation, String reason) {
        if ("PROD".equals(target.env())) {
            String r = reason == null ? "" : reason.trim();
            if (r.length() < 5 || r.length() > 500) {
                throw ApiException.badRequest("生产库变更必须填写原因（5-500 字符）");
            }
        }
    }

    private void ensureNoActiveJob(Target target) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status from platform.table_ddl_job
                 where tenant_id = ? and connection_key = ?
                   and lower(database_name) = lower(?)
                   and lower(coalesce(table_name, '')) = lower(?)
                   and status in ('PENDING_APPROVAL', 'PENDING', 'RUNNING')
                 limit 1
                """, target.tenantId(), target.connectionKey(), target.database(),
                target.table());
        if (!rows.isEmpty()) {
            throw ApiException.conflict("该表已有进行中的变更工单 #" + rows.get(0).get("id")
                    + "（" + rows.get(0).get("status") + "），请等待完成或取消后再提交（ACTIVE_JOB）");
        }
    }

    // ---------------------------------------------------------------- 目标解析

    public Target resolve(Long projectId, String database, String table) {
        String db = requireName(database, "库名");
        String tb = requireName(table, "表名");
        if (SYSTEM_DBS.contains(db.toLowerCase(Locale.ROOT))) {
            throw ApiException.notFound("库不存在: " + db);
        }
        if (projectId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id, tenant_id, dev_database, prod_database, owner_user
                      from platform.project
                     where id = ? and tenant_id = ?
                    """, projectId, TenantScope.current());
            if (rows.isEmpty()) {
                throw ApiException.notFound("项目不存在: " + projectId);
            }
            Map<String, Object> project = rows.get(0);
            String dev = str(project, "dev_database");
            String prod = str(project, "prod_database");
            String env;
            if (dev != null && dev.equalsIgnoreCase(db)) {
                env = "DEV";
            } else if (prod != null && prod.equalsIgnoreCase(db)) {
                env = "PROD";
            } else {
                throw ApiException.notFound("库不存在或不属于该项目: " + db);
            }
            // 统一按项目的 DORIS 数据源解析；内置返回 null
            Long connectionId = warehouseResolver.connectionIdForProject(projectId);
            return new Target(projectId, TenantScope.current(), connectionId,
                    connectionKey(connectionId), db, tb, env, str(project, "owner_user"));
        }
        scope.requireDatabase(db);
        String connectionKey = "builtin";
        String env = db.toLowerCase(Locale.ROOT).endsWith("_prd") ? "PROD" : "DEV";
        return new Target(null, TenantScope.current(), null, connectionKey, db, tb, env, null);
    }

    private Target targetFromJob(Map<String, Object> row) {
        Object project = row.get("project_id");
        Object connection = row.get("doris_connection_id");
        return new Target(project == null ? null : ((Number) project).longValue(),
                str(row, "tenant_id"),
                connection == null ? null : ((Number) connection).longValue(),
                str(row, "connection_key"), str(row, "database_name"), str(row, "table_name"),
                str(row, "env"), null);
    }

    private String connectionKey(Long connectionId) {
        return connectionId == null ? "builtin" : "conn:" + connectionId;
    }

    private String lockKey(Target target) {
        return target.tenantId() + "|" + target.connectionKey() + "|"
                + target.database().toLowerCase(Locale.ROOT) + "|"
                + target.table().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- 工单视图

    private Map<String, Object> requireJob(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from platform.table_ddl_job where id = ? and tenant_id = ?",
                id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("工单不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> jobView(long id) {
        Map<String, Object> row = requireJob(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("tenantId", row.get("tenant_id"));
        out.put("projectId", row.get("project_id"));
        out.put("database", row.get("database_name"));
        out.put("table", row.get("table_name"));
        out.put("env", row.get("env"));
        out.put("operation", row.get("operation"));
        out.put("status", row.get("status"));
        out.put("version", row.get("version"));
        out.put("ddl", row.get("ddl_sql"));
        out.put("baseDdl", row.get("base_ddl"));
        out.put("baseDdlHash", row.get("base_ddl_hash"));
        out.put("ddlHash", row.get("ddl_hash"));
        out.put("ddlAfterHash", row.get("ddl_after_hash"));
        out.put("reason", row.get("reason"));
        out.put("requestedBy", row.get("requested_by"));
        out.put("requestedAt", row.get("requested_at"));
        out.put("approver", row.get("approver"));
        out.put("approvedAt", row.get("approved_at"));
        out.put("approvalComment", row.get("approval_comment"));
        out.put("startedAt", row.get("started_at"));
        out.put("finishedAt", row.get("finished_at"));
        out.put("durationMs", row.get("duration_ms"));
        out.put("dorisJobId", row.get("doris_job_id"));
        out.put("dorisState", row.get("doris_state"));
        out.put("dorisProgress", row.get("doris_progress"));
        out.put("lastPolledAt", row.get("last_polled_at"));
        out.put("errorCode", row.get("error_code"));
        out.put("errorMessage", row.get("error_message"));
        out.put("retryable", row.get("retryable"));
        out.put("params", readJson(str(row, "params_json")));
        return out;
    }

    private Map<String, Object> targetView(Target target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", target.projectId());
        out.put("database", target.database());
        out.put("table", target.table());
        out.put("env", target.env());
        out.put("owner", target.owner());
        out.put("connectionKey", target.connectionKey());
        return out;
    }

    private Map<String, Object> columnView(Column column) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", column.name());
        out.put("type", column.type());
        out.put("nullable", column.nullable());
        out.put("key", column.key());
        out.put("comment", column.comment());
        out.put("defaultValue", column.defaultValue());
        return out;
    }

    private Map<String, Object> paramsAlter(AlterRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", req.operation());
        if (req.column() != null) {
            params.put("column", req.column());
        }
        if (req.properties() != null) {
            params.put("properties", req.properties());
        }
        return params;
    }

    private void event(long jobId, String action, String actor, String detail) {
        jdbc.update("""
                insert into platform.table_ddl_job_event (job_id, tenant_id, action, actor, detail)
                values (?, ?, ?, ?, ?)
                """, jobId, TenantScope.current(), action, actor, cut(detail, 2000));
    }

    private void audit(String user, String action, long jobId, String detail, String result,
                       HttpServletRequest http) {
        sessions.audit(user, action, "ddl_job:" + jobId, cut(detail, 1000), result,
                http == null ? null : AuthInterceptor.clientIp(http));
    }

    private String projectOwner(Map<String, Object> job) {
        Object projectId = job.get("project_id");
        if (projectId == null) {
            return null;
        }
        List<String> rows = jdbc.queryForList("""
                select owner_user from platform.project
                 where id = ? and tenant_id = ?
                """, String.class, ((Number) projectId).longValue(), TenantScope.current());
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- 工具

    private String normalizeOperation(String operation) {
        String op = operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
        return switch (op) {
            case "ADD_COLUMN", "ALTER_ADD_COLUMN" -> "ALTER_ADD_COLUMN";
            case "MODIFY_COLUMN", "ALTER_MODIFY_COLUMN" -> "ALTER_MODIFY_COLUMN";
            case "DROP_COLUMN", "ALTER_DROP_COLUMN" -> "ALTER_DROP_COLUMN";
            case "SET_PROPERTIES", "ALTER_SET_PROPERTIES" -> "ALTER_SET_PROPERTIES";
            case "RENAME", "RENAME_TABLE", "ALTER_RENAME_TABLE" -> "ALTER_RENAME_TABLE";
            case "TRUNCATE", "TRUNCATE_TABLE" -> "TRUNCATE_TABLE";
            default -> throw new Rejected("UNSUPPORTED_OPERATION",
                    "不支持的变更类型: " + operation);
        };
    }

    private String safeOperation(String operation) {
        try {
            return normalizeOperation(operation);
        } catch (Rejected e) {
            return operation;
        }
    }

    private ColumnSpec requireColumn(AlterRequest req) {
        if (req.column() == null) {
            throw new Rejected("COLUMN_REQUIRED", "缺少 column 参数");
        }
        return req.column();
    }

    private Column findColumn(Snapshot snapshot, String name) {
        for (Column column : snapshot.columns()) {
            if (column.name().equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    private String requireName(String value, String label) {
        if (value == null || !NAME.matcher(value.trim()).matches()) {
            throw ApiException.badRequest(label + "不合法（字母或下划线开头，只含字母/数字/下划线，"
                    + "最长 64）: " + value);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultLiteral(DorisType type, String value) {
        return switch (type.family) {
            case BOOLEAN, INT, FLOAT, DECIMAL -> value;
            default -> quote(value);
        };
    }

    private String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private String qi(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private Set<String> identifiers(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim().replace("`", "");
            int space = token.indexOf(' ');
            if (space > 0) {
                token = token.substring(0, space);
            }
            if (NAME.matcher(token).matches()) {
                out.add(token);
            }
        }
        return out;
    }

    private boolean containsIgnoreCase(Set<String> values, String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private int parseReplication(String value) {
        String text = value == null ? "" : value.trim();
        if (text.contains(":")) {
            text = text.substring(text.lastIndexOf(':') + 1).trim();
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new Rejected("REPLICA_INVALID", "副本数格式不合法: " + value);
        }
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue() == null ? null : String.valueOf(entry.getValue());
                }
            }
            return null;
        }
        return String.valueOf(value);
    }

    private String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue() == null ? null : String.valueOf(entry.getValue());
                }
            }
            return null;
        }
        return String.valueOf(value);
    }

    private String user(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ApiException(500, "序列化变更参数失败");
        }
    }

    private Object readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

}
