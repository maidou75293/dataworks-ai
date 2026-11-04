package com.lab.dataworks.change;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import com.lab.dataworks.guard.SqlGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL 变更单 v1 的业务逻辑（inbox/007）。
 *
 * <p>状态机：PENDING --通过--> APPROVED --执行--> DONE / FAILED；PENDING --驳回--> REJECTED。
 * 「通过即执行」：不拆审批和执行两步，APPROVED 只是执行期间的中间态。</p>
 *
 * <p>租户隔离：所有读写都带 {@code tenant_id = TenantScope.current()}；
 * 数据源可见性沿用数据源接口的口径（项目属于当前租户，或内置数据源对所有租户可见）。</p>
 */
@Service
public class ChangeService {

    /** 007 第五节：列表默认只看自己的 + 待我审的。 */
    private static final Set<String> STATUSES =
            Set.of("PENDING", "APPROVED", "REJECTED", "DONE", "FAILED", "CANCELLED");

    private static final int LIST_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final ChangeExecutor executor;
    private final TenantGuard guard;

    public ChangeService(JdbcTemplate jdbc, SessionService sessions,
                         ChangeExecutor executor, TenantGuard guard) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.executor = executor;
        this.guard = guard;
    }

    /** 解析结果：数据源行 + 本次变更归属的项目 + 项目开关 + 是否为内置数据源。 */
    public record Target(long datasourceId, long projectId, boolean builtin,
                         boolean guardEnabled, Map<String, Object> datasource) {
    }

    // ---------------------------------------------------------------- 判定 / 建单

    /** {@code POST /api/sql/classify}：只判断，不落库。 */
    public Map<String, Object> classify(Long datasourceId, Long projectId, String sql) {
        String text = requireSql(sql);
        Target target = resolveTarget(datasourceId, projectId);
        SqlGuard.Result result = SqlGuard.check(text);
        boolean needReview = result.needReview() && target.guardEnabled();
        String reason = target.guardEnabled()
                ? result.reason()
                : "项目已关闭 SQL 守卫（sql_guard_enabled=false），全部直接执行";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needReview", needReview);
        out.put("tables", result.tables());
        out.put("reason", reason);
        out.put("datasourceId", target.datasourceId());
        out.put("projectId", target.projectId());
        out.put("guardEnabled", target.guardEnabled());
        return out;
    }

    /** {@code POST /api/changes}：命中审批规则才建单，否则明确告诉调用方可直接执行。 */
    public Map<String, Object> create(Long datasourceId, Long projectId, String sql,
                                      String requester) {
        String text = requireSql(sql);
        Target target = resolveTarget(datasourceId, projectId);
        if (!target.guardEnabled()) {
            throw ApiException.conflict("项目已关闭 SQL 守卫，可直接执行，无需建单");
        }
        if (!ChangeRules.executable(str(target.datasource().get("ds_type")))) {
            throw ApiException.badRequest(
                    "变更执行只支持 Doris / Spark 数据源，当前数据源类型是 "
                            + str(target.datasource().get("ds_type")));
        }
        SqlGuard.Result result = SqlGuard.check(text);
        if (!result.needReview()) {
            throw ApiException.conflict("该语句无需审批，可直接执行（" + result.reason() + "）");
        }
        // v1 只承诺「DROP/TRUNCATE 单表」自动备份：一次多表在审批时才拒绝会让单据
        // 变成必然 FAILED 的死单，建单前就拦下来。
        for (String statement : ChangeRules.statements(text)) {
            ChangeRules.BackupPlan plan = ChangeRules.backupPlan(statement);
            if (plan.kind() == ChangeRules.BackupKind.UNSUPPORTED) {
                throw ApiException.badRequest(plan.message());
            }
        }
        String tables = result.tables().isEmpty() ? null : String.join(",", result.tables());
        // D53：提交时生成回滚方案（DROP/TRUNCATE 的备份表名执行后回填，先用占位符）
        ChangeRules.RollbackPlan plan = ChangeRules.rollbackPlan(text, List.of());
        Long id = jdbc.queryForObject("""
                insert into platform.change_request
                    (tenant_id, project_id, datasource_id, target_table, sql_text,
                     status, requester, inverse_sql, irreversible)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                returning id
                """, Long.class, TenantScope.current(), target.projectId(),
                target.datasourceId(), tables, text, requester,
                plan.inverseSql(), plan.irreversible());
        sessions.audit(requester, "CHANGE_REQUEST_CREATE", "c" + id,
                "project=" + target.projectId() + " datasource=" + target.datasourceId()
                        + " tables=" + (tables == null ? "" : tables));
        return view(id);
    }

    // ---------------------------------------------------------------- 列表

    /**
     * {@code GET /api/changes?status=}。
     *
     * <p>可见范围：自己的全部单据；有 change:approve 的人额外看到本租户全部 PENDING。
     * 「待我审的」只对 PENDING 成立，所以已完结的单据别人看不到（审批历史不扩散）。</p>
     */
    public List<Map<String, Object>> list(String requester, boolean canApprove, String status) {
        String normalized = status == null || status.isBlank()
                ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw ApiException.badRequest("status 只能是 "
                    + String.join(" / ", STATUSES.stream().sorted().toList()));
        }
        StringBuilder sql = new StringBuilder(VIEW + """
                 where tenant_id = ?
                   and (requester = ? or (? and status = 'PENDING'))
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(TenantScope.current());
        args.add(requester);
        args.add(canApprove);
        if (normalized != null) {
            sql.append(" and status = ?");
            args.add(normalized);
        }
        sql.append(" order by id desc limit ").append(LIST_LIMIT);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------------------------------------------------------------- 审批

    /** {@code POST /api/changes/{id}/approve}：通过后立即执行（旧签名，未勾选不可逆确认）。 */
    public Map<String, Object> approve(long id, String reviewer, String comment) {
        return approve(id, reviewer, comment, false);
    }

    /**
     * {@code POST /api/changes/{id}/approve}：通过后立即执行。
     *
     * <p>D53：{@code irreversible} 的单子必须显式勾选「已知不可逆」（acknowledgeIrreversible），
     * 否则 400——不可逆判定在建单时已由 {@link ChangeRules#rollbackPlan} 算好落库。</p>
     */
    public Map<String, Object> approve(long id, String reviewer, String comment,
                                       boolean acknowledgeIrreversible) {
        Map<String, Object> row = require(id);
        String error = ChangeRules.reviewGuard(str(row.get("requester")), reviewer,
                str(row.get("status")));
        if (error != null) {
            if (error.startsWith("不能审批自己")) {
                sessions.audit(reviewer, "CHANGE_APPROVE_DENIED", "c" + id, error,
                        "FAILED", null);
                throw new ApiException(403, error);
            }
            throw ApiException.badRequest(error);
        }
        if (Boolean.TRUE.equals(row.get("irreversible")) && !acknowledgeIrreversible) {
            throw ApiException.badRequest(
                    "该变更单已标记为不可逆（数据无法自动还原），审批前必须勾选「已知不可逆」");
        }
        // 先原子认领：两个审批人同时点「通过」时只有一个能改到 PENDING
        int claimed = jdbc.update("""
                update platform.change_request
                   set status = 'APPROVED', reviewer = ?, review_comment = ?, reviewed_at = now()
                 where id = ? and tenant_id = ? and status = 'PENDING'
                """, reviewer, trim(comment), id, TenantScope.current());
        if (claimed == 0) {
            throw ApiException.badRequest("该变更单已经处理过了");
        }
        sessions.audit(reviewer, "CHANGE_APPROVE", "c" + id,
                "requester=" + row.get("requester") + " comment=" + trim(comment)
                        + (Boolean.TRUE.equals(row.get("irreversible"))
                           ? " acknowledgeIrreversible=true" : ""));

        ChangeExecutor.Outcome outcome;
        try {
            // 重新按 id 取数据源：建单到审批之间可能被删除/改类型/换租户
            Target target = resolveTarget(((Number) row.get("datasource_id")).longValue(), null);
            outcome = executor.execute(str(row.get("sql_text")), target.datasource());
        } catch (ApiException e) {
            outcome = new ChangeExecutor.Outcome(false, null, "执行前置校验失败：" + e.getMessage());
        }
        String status = outcome.ok() ? "DONE" : "FAILED";
        // D53：执行成功后用真实备份表名回填回滚 SQL（提交时是占位符）
        String inverseSql = str(row.get("inverse_sql"));
        if (outcome.ok() && outcome.backupTable() != null && !outcome.backupTable().isBlank()) {
            ChangeRules.RollbackPlan plan = ChangeRules.rollbackPlan(
                    str(row.get("sql_text")), List.of(outcome.backupTable().split(",")));
            if (plan.inverseSql() != null) {
                inverseSql = plan.inverseSql();
            }
        }
        jdbc.update("""
                update platform.change_request
                   set status = ?, backup_table = ?, exec_result = ?, inverse_sql = ?,
                       executed_at = now()
                 where id = ? and tenant_id = ?
                """, status, outcome.backupTable(), outcome.detail(), inverseSql,
                id, TenantScope.current());
        boolean isRollback = row.get("rollback_of") != null;
        String action = outcome.ok()
                ? (isRollback ? "CHANGE_ROLLBACK" : "CHANGE_EXECUTE")
                : (isRollback ? "CHANGE_ROLLBACK_FAILED" : "CHANGE_EXECUTE_FAILED");
        sessions.audit(reviewer, action, "c" + id, outcome.detail(),
                outcome.ok() ? "SUCCESS" : "FAILED", null);
        return view(id);
    }

    // ---------------------------------------------------------------- D53：回滚 / 备份表

    /** 备份表保留天数（超期在列表里标 expired，可清理）。 */
    static final int RETENTION_DAYS = 30;

    /** 最近 N 天的备份禁止手动清理（回滚窗口保护）。 */
    static final int CLEANUP_PROTECTED_DAYS = 7;

    /**
     * {@code POST /api/changes/{id}/rollback}：按已回填的 inverse_sql 生成一张**新变更单**
     * （rollback_of 指向原单），走同一套审批——不允许自动回滚，避免误操作放大事故。
     */
    public Map<String, Object> rollback(long id, String requester) {
        Map<String, Object> row = require(id);
        String status = str(row.get("status"));
        if (!"DONE".equals(status)) {
            throw ApiException.badRequest("只有已执行完成（DONE）的变更单才能回滚，当前状态：" + status);
        }
        if (Boolean.TRUE.equals(row.get("irreversible"))) {
            throw ApiException.badRequest("该变更已标记为不可逆，无法平台内回滚");
        }
        String inverse = str(row.get("inverse_sql"));
        if (inverse == null || inverse.isBlank()) {
            throw ApiException.badRequest("该变更单没有生成回滚 SQL，无法回滚");
        }
        if (inverse.contains(ChangeRules.BACKUP_PLACEHOLDER)) {
            throw ApiException.badRequest("回滚 SQL 中的备份表名尚未回填（执行未成功完成），无法回滚");
        }
        Integer inflight = jdbc.queryForObject("""
                select count(*) from platform.change_request
                 where tenant_id = ? and rollback_of = ? and status in ('PENDING', 'APPROVED')
                """, Integer.class, TenantScope.current(), id);
        if (inflight != null && inflight > 0) {
            throw ApiException.conflict("该变更单已有在途回滚单，请先处理完再发起");
        }
        // 回滚单自身也算一遍回滚方案：INSERT 恢复 / DROP COLUMN 撤销本身不可逆 →
        // 审批回滚单同样要勾选「已知不可逆」，与「不许自动回滚」口径一致。
        ChangeRules.RollbackPlan plan = ChangeRules.rollbackPlan(inverse, List.of());
        Long newId = jdbc.queryForObject("""
                insert into platform.change_request
                    (tenant_id, project_id, datasource_id, target_table, sql_text,
                     status, requester, inverse_sql, irreversible, rollback_of)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                returning id
                """, Long.class, TenantScope.current(), row.get("project_id"),
                row.get("datasource_id"), row.get("target_table"), inverse, requester,
                plan.inverseSql(), plan.irreversible(), id);
        sessions.audit(requester, "CHANGE_REQUEST_CREATE", "c" + newId,
                "rollback_of=" + id + "（回滚单，走同一套审批）");
        return view(newId);
    }

    /**
     * {@code GET /api/changes/backups}：备份表清单（源表/备份表/时间/大小）。
     *
     * <p>数据来自 change_request.backup_table（只有平台建的备份才登记在册）；
     * 大小只对内置 Doris 可查（information_schema.data_length），外部数据源为 null。
     * 可见范围与列表接口一致：自己的 + （有 change:approve 时）本租户全部。</p>
     */
    public Map<String, Object> backups(String requester, boolean canApprove) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.requester, c.target_table, c.backup_table,
                       c.executed_at, c.created_at,
                       coalesce(d.config_json ->> 'builtin', 'false') as builtin
                  from platform.change_request c
                  left join platform.datasource d on d.id = c.datasource_id
                 where c.tenant_id = ?
                   and c.backup_table is not null and c.backup_table <> ''
                   and (c.requester = ? or ?)
                 order by c.id desc
                 limit 500
                """, TenantScope.current(), requester, canApprove);
        Set<String> builtinNames = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (isBuiltin(row)) {
                builtinNames.addAll(backupNames(row));
            }
        }
        Map<String, Long> sizes = executor.builtinTableSizes(builtinNames);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean builtin = isBuiltin(row);
            for (String name : backupNames(row)) {
                LocalDateTime at = ChangeRules.backupTime(name);
                if (at == null) {
                    at = timestamp(row.get("executed_at") != null
                            ? row.get("executed_at") : row.get("created_at"));
                }
                long ageDays = at == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(at, now));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("changeId", row.get("id"));
                item.put("requester", row.get("requester"));
                item.put("sourceTable", ChangeRules.sourceOf(name));
                item.put("backupTable", name);
                item.put("backedUpAt", at == null ? null : at.toString());
                item.put("ageDays", ageDays);
                item.put("sizeBytes", builtin ? sizes.get(name) : null);
                item.put("builtin", builtin);
                item.put("cleanable", ageDays >= CLEANUP_PROTECTED_DAYS);
                item.put("expired", ageDays >= RETENTION_DAYS);
                items.add(item);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retentionDays", RETENTION_DAYS);
        out.put("protectedDays", CLEANUP_PROTECTED_DAYS);
        out.put("backups", items);
        return out;
    }

    /**
     * {@code DELETE /api/changes/backups?table=}：手动清理一张备份表。
     *
     * <p>三重保护：① 表名必须是平台 {@code *_bak_<时间戳>} 形态；② 必须登记在
     * **本租户**的变更单里（防止该接口变成任意 DROP 通道）；③ 最近
     * {@value #CLEANUP_PROTECTED_DAYS} 天内的备份禁止清理（回滚窗口）。</p>
     */
    public Map<String, Object> cleanupBackup(String table, String actor, String tenant) {
        String name = table == null ? "" : table.trim();
        if (!ChangeRules.isBackupName(name)) {
            throw ApiException.badRequest("不是平台备份表名（须为 *_bak_<14位时间戳> 形态）: " + table);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, backup_table, executed_at, created_at, datasource_id
                  from platform.change_request
                 where tenant_id = ?
                   and ? = any (string_to_array(backup_table, ','))
                 order by id desc
                """, tenant, name);
        if (rows.isEmpty()) {
            throw ApiException.notFound("备份表记录不存在（或不属于当前租户）: " + name);
        }
        Map<String, Object> row = rows.get(0);
        LocalDateTime at = ChangeRules.backupTime(name);
        if (at == null) {
            at = timestamp(row.get("executed_at") != null
                    ? row.get("executed_at") : row.get("created_at"));
        }
        long ageDays = at == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(at, LocalDateTime.now()));
        if (ageDays < CLEANUP_PROTECTED_DAYS) {
            throw ApiException.badRequest("最近 " + CLEANUP_PROTECTED_DAYS
                    + " 天内的备份禁止清理（该备份 " + ageDays + " 天，还需等待 "
                    + (CLEANUP_PROTECTED_DAYS - ageDays) + " 天）");
        }
        List<Map<String, Object>> ds = jdbc.queryForList(
                "select ds_type, coalesce(config_json ->> 'builtin', 'false') as builtin"
                        + " from platform.datasource where id = ?", row.get("datasource_id"));
        boolean builtin = !ds.isEmpty()
                && "true".equalsIgnoreCase(str(ds.get(0).get("builtin")))
                && "DORIS".equalsIgnoreCase(str(ds.get(0).get("ds_type")));
        if (!builtin) {
            throw ApiException.badRequest(
                    "外部数据源的备份表不支持在线清理，请登录对应实例手动处理");
        }
        try {
            executor.dropBuiltinTable(name);
        } catch (RuntimeException | SQLException e) {
            throw ApiException.badRequest("清理失败：" + e.getMessage());
        }
        String remaining = backupNames(row).stream()
                .filter(item -> !item.equals(name))
                .collect(Collectors.joining(","));
        jdbc.update("""
                update platform.change_request
                   set backup_table = ?
                 where id = ? and tenant_id = ?
                """, remaining.isEmpty() ? null : remaining, row.get("id"), tenant);
        sessions.audit(actor, "CHANGE_BACKUP_CLEANUP", name,
                "change=" + row.get("id") + " ageDays=" + ageDays);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("table", name);
        out.put("ageDays", ageDays);
        return out;
    }

    private static boolean isBuiltin(Map<String, Object> row) {
        return "true".equalsIgnoreCase(str(row.get("builtin")));
    }

    private static List<String> backupNames(Map<String, Object> row) {
        String raw = str(row.get("backup_table"));
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    /** PG 时间列 → LocalDateTime（驱动可能给 Timestamp 或 OffsetDateTime）。 */
    private static LocalDateTime timestamp(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        return null;
    }

    /**
     * D31 撤回：提交人撤回**自己的** PENDING 单（PENDING→CANCELLED）。
     *
     * <p>变更单原本只有 通过/驳回 两个出口，单管理员场景下自己提交的单必然撞
     * 「不能审批自己提交的变更单」，永久卡在 PENDING。撤回不执行任何 SQL，
     * 不构成审批动作，因此不受 self-guard 限制。</p>
     *
     * <p>错误口径（卡内验收）：不存在/跨租户 404（与 {@link #require} 一致）、
     * 撤他人单 403、非 PENDING 409；更新语句带租户条件并在 where 里复核
     * requester/status，检查和落库之间被并发审批抢改时同样 409，不会假成功。</p>
     */
    public Map<String, Object> cancel(long id, String requester) {
        Map<String, Object> row = require(id);
        String owner = str(row.get("requester"));
        if (!requester.equals(owner)) {
            sessions.audit(requester, "CHANGE_CANCEL_DENIED", "c" + id,
                    "撤回他人单 owner=" + owner, "FAILED", null);
            throw new ApiException(403, "不能撤回他人提交的变更单");
        }
        String status = str(row.get("status"));
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("该变更单已处理（当前状态 " + status + "），不能撤回");
        }
        int updated = jdbc.update("""
                update platform.change_request
                   set status = 'CANCELLED', reviewed_at = now(),
                       review_comment = coalesce(review_comment, '提交人撤回')
                 where id = ? and tenant_id = ? and requester = ? and status = 'PENDING'
                """, id, TenantScope.current(), requester);
        if (updated == 0) {
            throw ApiException.conflict("该变更单状态已变化，不能撤回");
        }
        sessions.audit(requester, "CHANGE_CANCEL", "c" + id, "提交人撤回", "SUCCESS", null);
        return view(id);
    }

    /**
     * D31 作废：{@code change:approve} 持有人作废本租户的 PENDING 单
     * （PENDING→CANCELLED，**状态迁移，不物理删**——单据是审计对象，与撤回同口径）。
     *
     * <p>与 {@link #cancel} 的分工：撤回是提交人自己的出口；作废是给审批账号
     * 清挂死在待审批队列、又不再需要处理的单（含本人提交的单——作废不执行 SQL，
     * 不审批，不受 self-guard 限制）。</p>
     */
    public Map<String, Object> voidChange(long id, String actor, String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                VIEW + " where id = ? and tenant_id = ?", id, tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("变更单不存在: " + id);
        }
        String status = str(rows.get(0).get("status"));
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("该变更单已处理（当前状态 " + status + "），不能作废");
        }
        int updated = jdbc.update("""
                update platform.change_request
                   set status = 'CANCELLED', reviewed_at = now(),
                       review_comment = coalesce(review_comment, '已作废')
                 where id = ? and tenant_id = ? and status = 'PENDING'
                """, id, tenant);
        if (updated == 0) {
            throw ApiException.conflict("该变更单状态已变化，不能作废");
        }
        sessions.audit(actor, "CHANGE_VOID", "c" + id,
                "作废 requester=" + str(rows.get(0).get("requester")), "SUCCESS", null);
        return view(id);
    }

    /** {@code POST /api/changes/{id}/reject}：只改状态，不执行。 */
    public Map<String, Object> reject(long id, String reviewer, String comment) {
        Map<String, Object> row = require(id);
        String error = ChangeRules.reviewGuard(str(row.get("requester")), reviewer,
                str(row.get("status")));
        if (error != null) {
            if (error.startsWith("不能审批自己")) {
                sessions.audit(reviewer, "CHANGE_REJECT_DENIED", "c" + id, error,
                        "FAILED", null);
                throw new ApiException(403, error);
            }
            throw ApiException.badRequest(error);
        }
        int updated = jdbc.update("""
                update platform.change_request
                   set status = 'REJECTED', reviewer = ?, review_comment = ?, reviewed_at = now()
                 where id = ? and tenant_id = ? and status = 'PENDING'
                """, reviewer, trim(comment), id, TenantScope.current());
        if (updated == 0) {
            throw ApiException.badRequest("该变更单已经处理过了");
        }
        sessions.audit(reviewer, "CHANGE_REJECT", "c" + id,
                "requester=" + row.get("requester") + " comment=" + trim(comment));
        return view(id);
    }

    // ---------------------------------------------------------------- 数据源 / 项目开关

    /**
     * 解析数据源与项目。
     *
     * <p>{@code projectId} 可选：不传时用数据源所属项目（007 的请求体只有 datasourceId）。
     * 传了必须属于当前租户；外部数据源还必须与数据源所属项目一致，
     * 避免「拿 A 项目的开关去执行 B 数据源」。</p>
     */
    public Target resolveTarget(Long datasourceId, Long projectId) {
        if (datasourceId == null) {
            throw ApiException.badRequest("datasourceId 不能为空");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.project_id, d.name, d.ds_type, d.jdbc_url, d.username,
                       d.password, coalesce(d.config_json ->> 'builtin', 'false') as builtin
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where d.id = ?
                   and (p.tenant_id = ?
                        or coalesce(d.config_json ->> 'builtin', 'false') = 'true')
                """, datasourceId, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + datasourceId);
        }
        Map<String, Object> datasource = rows.get(0);
        boolean builtin = Boolean.TRUE.equals(datasource.get("builtin"))
                || "true".equalsIgnoreCase(str(datasource.get("builtin")));
        long ownerProject = ((Number) datasource.get("project_id")).longValue();
        long project = projectId == null ? ownerProject : projectId;
        if (projectId != null) {
            guard.requireProject(projectId);
            if (!builtin && ownerProject != projectId) {
                throw ApiException.badRequest("数据源不属于该项目: " + projectId);
            }
        }
        return new Target(datasourceId, project, builtin, guardEnabled(project), datasource);
    }

    /** 项目开关；项目不存在按开启处理（fail-safe：宁可多审一次）。 */
    public boolean guardEnabled(long projectId) {
        List<Boolean> rows = jdbc.queryForList(
                "select sql_guard_enabled from platform.project where id = ?", Boolean.class,
                projectId);
        return rows.isEmpty() || !Boolean.FALSE.equals(rows.get(0));
    }

    // ---------------------------------------------------------------- 内部

    private static final String VIEW = """
            select id, tenant_id as "tenantId", project_id as "projectId",
                   datasource_id as "datasourceId", target_table as "targetTable",
                   sql_text as "sqlText", status, requester, reviewer,
                   review_comment as "reviewComment", backup_table as "backupTable",
                   exec_result as "execResult", created_at as "createdAt",
                   reviewed_at as "reviewedAt", executed_at as "executedAt",
                   inverse_sql as "inverseSql", irreversible,
                   rollback_of as "rollbackOf"
              from platform.change_request
            """;

    private Map<String, Object> view(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                VIEW + " where id = ? and tenant_id = ?", id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("变更单不存在: " + id);
        }
        return rows.get(0);
    }

    /** 取原始行（snake_case，内部判定用）；不存在/跨租户统一 404。 */
    private Map<String, Object> require(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from platform.change_request where id = ? and tenant_id = ?",
                id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("变更单不存在: " + id);
        }
        return rows.get(0);
    }

    private static String requireSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        return sql;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
