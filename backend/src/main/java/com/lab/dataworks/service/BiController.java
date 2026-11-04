package com.lab.dataworks.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.AutoCode;
import com.lab.dataworks.security.DataGuardService;
import com.lab.dataworks.secret.SecretCipher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 数据可视化（BI）看板。
 *
 * <p>模型与数据服务/采集一致：开发态可改，DEV 跑通通过后才能发布；
 * 生产态是快照（{@code bi_dashboard_release.snapshot}），改开发态不影响线上。
 * 图表只允许单条只读 SQL，数据出口统一过分级网关。</p>
 */
@RestController
@RequestMapping("/api/bi/dashboards")
public class BiController {

    private static final Logger log = LoggerFactory.getLogger(BiController.class);

    private static final Set<String> CHART_TYPES = Set.of("TABLE", "LINE", "BAR", "PIE", "KPI");
    private static final int MAX_CHARTS = 30;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretCipher cipher;
    private final DataGuardService dataGuard;
    private final SessionService sessions;

    public BiController(JdbcTemplate jdbc, ObjectMapper mapper, SecretCipher cipher,
                        DataGuardService dataGuard, SessionService sessions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
        this.dataGuard = dataGuard;
        this.sessions = sessions;
    }

    public record ChartRequest(String chartType, String name, Long datasourceId,
                               String sqlText, String xColumn, String yColumn,
                               String seriesColumn) {
    }

    public record DashboardRequest(Long projectId, String code, String name, String description,
                                   List<DataServiceController.ParamSpec> params,
                                   List<ChartRequest> charts) {
    }

    // ---------------------------------------------------------------- 管理面

    @GetMapping
    @RequireAtom("query:run")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long projectId) {
        String sql = """
                select d.id, d.project_id, p.name as project_name, d.code, d.name, d.description,
                       d.status, d.version, d.created_by, d.created_at, d.updated_at, d.published_at,
                       d.smoke_hash, d.smoke_ok_at, d.params_json,
                       (select count(*) from platform.bi_chart c where c.dashboard_id = d.id) as chart_count,
                       (select max(r.version) from platform.bi_dashboard_release r
                         where r.dashboard_id = d.id) as release_version,
                       (select r.published_at from platform.bi_dashboard_release r
                         where r.dashboard_id = d.id order by r.version desc limit 1) as release_published_at
                from platform.bi_dashboard d
                join platform.project p on p.id = d.project_id
                where d.tenant_id = ?
                """;
        List<Map<String, Object>> rows = projectId == null
                ? jdbc.queryForList(sql + " order by d.id desc", TenantScope.current())
                : jdbc.queryForList(sql + " and d.project_id = ? order by d.id desc",
                        TenantScope.current(), projectId);
        for (Map<String, Object> row : rows) {
            decorate(row);
        }
        return rows;
    }

    @GetMapping("/{id}")
    @RequireAtom("query:run")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> row = find(id);
        decorate(row);
        row.put("params", paramsOf(row));
        row.put("charts", charts(id));
        return row;
    }

    @PostMapping
    @RequireAtom("bi:write")
    @Transactional
    public Map<String, Object> create(@RequestBody DashboardRequest req, HttpServletRequest http) {
        validate(req, false);
        requireProject(req.projectId());
        checkChartDatasources(req.projectId(), req.charts());
        // REQ-DW-9：编码留空即自动生成（前缀_日期_随机后缀），显式传入仍按原校验走
        String code = resolveCode(req.code());
        Long dup = jdbc.queryForObject("""
                select count(*) from platform.bi_dashboard where tenant_id = ? and code = ?
                """, Long.class, TenantScope.current(), code);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("看板编码已存在: " + code);
        }
        try {
            jdbc.update("""
                    insert into platform.bi_dashboard
                        (project_id, code, name, description, params_json, tenant_id, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, req.projectId(), code, req.name().trim(), req.description(),
                    paramsJson(req.params()), TenantScope.current(), user(http));
        } catch (DuplicateKeyException e) {
            // 查重与插入之间仍可能被并发穿过：唯一键兜底成 409，而不是 500。
            throw ApiException.conflict("看板编码已存在: " + code);
        }
        Long id = jdbc.queryForObject("""
                select id from platform.bi_dashboard where tenant_id = ? and code = ?
                """, Long.class, TenantScope.current(), code);
        saveCharts(id, req.charts());
        auditOutsideTx(http, "BI_DASHBOARD_CREATE", code, req.name().trim());
        return detail(id);
    }

    @PutMapping("/{id}")
    @RequireAtom("bi:write")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @RequestBody DashboardRequest req,
                                      HttpServletRequest http) {
        Map<String, Object> existing = find(id);
        validate(req);
        requireProject(req.projectId());
        checkChartDatasources(req.projectId(), req.charts());
        jdbc.update("""
                update platform.bi_dashboard
                set project_id = ?, name = ?, description = ?, params_json = ?, updated_at = now()
                where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, req.projectId(), req.name().trim(), req.description(),
                paramsJson(req.params()), id, com.lab.dataworks.auth.TenantScope.current());
        saveCharts(id, req.charts());
        Map<String, Object> fresh = find(id);
        String hash = contentHash(fresh, charts(id), paramsOf(fresh));
        if (!hash.equals(fresh.get("smoke_hash"))) {
            jdbc.update("""
                    update platform.bi_dashboard
                    set smoke_hash = null, smoke_ok_at = null, smoke_by = null
                    where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                    """, id, com.lab.dataworks.auth.TenantScope.current());
        }
        auditOutsideTx(http, "BI_DASHBOARD_UPDATE", String.valueOf(existing.get("code")),
                "id " + id);
        return detail(id);
    }

    @DeleteMapping("/{id}")
    @RequireAtom("bi:write")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        // S13：删看板原来先做后记——「已发布不能删」409 或 delete 本身失败时一条都不留。
        // 先记 PENDING 再执行，失败回填 FAILED。
        // S12：detail 带删前上下文（名称 + 状态），只留 id 事后回答不了「删的是哪个看板」
        return sessions.auditAround(user(http), "BI_DASHBOARD_DELETE",
                String.valueOf(row.get("code")),
                "id " + id + " name=" + row.get("name") + " 删除时状态=" + row.get("status"),
                AuthInterceptor.clientIp(http), () -> {
                    if ("PUBLISHED".equals(String.valueOf(row.get("status")))) {
                        throw ApiException.conflict("看板已发布到线上，请先下线再删除");
                    }
                    int deleted = jdbc.update("""
                            delete from platform.bi_dashboard
                             where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                            """, id, com.lab.dataworks.auth.TenantScope.current());
                    if (deleted == 0) {
                        throw ApiException.notFound("看板不存在: " + id);
                    }
                    return Map.of("ok", true);
                });
    }

    // ---------------------------------------------------------------- DEV 跑通 / 发布

    @PostMapping("/{id}/smoke")
    @RequireAtom("bi:write")
    public Map<String, Object> smoke(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> dashboard = find(id);
        List<Map<String, Object>> charts = charts(id);
        if (charts.isEmpty()) {
            throw ApiException.badRequest("看板还没有图表，无法在 DEV 跑通");
        }
        // DEV 跑通用参数默认值执行：必填参数没有默认值时，会在执行阶段提示「缺少必填参数」
        List<DataServiceController.ParamSpec> params = paramsOf(dashboard);
        Map<String, Object> values = paramValues(params, null);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> chart : charts) {
            String name = String.valueOf(chart.get("name"));
            try {
                QueryExecution execution = runChart(chart, user(http), params, values,
                        projectIdOf(dashboard));
                results.add(Map.of(
                        "name", name,
                        "columns", execution.result().columns(),
                        "rowCount", execution.result().rows().size(),
                        "truncated", execution.result().truncated(),
                        "elapsedMs", execution.result().elapsedMs()));
            } catch (ApiException e) {
                throw new ApiException(e.getStatus(),
                        "图表「" + name + "」DEV 跑通失败：" + e.getMessage());
            } catch (Exception e) {
                throw ApiException.badRequest("图表「" + name + "」DEV 跑通失败：" + e.getMessage());
            }
        }
        String hash = contentHash(dashboard, charts, paramsOf(dashboard));
        jdbc.update("""
                update platform.bi_dashboard
                set smoke_hash = ?, smoke_ok_at = now(), smoke_by = ?
                where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, hash, user(http), id, com.lab.dataworks.auth.TenantScope.current());
        auditOutsideTx(http, "BI_DASHBOARD_SMOKE", String.valueOf(dashboard.get("code")),
                "charts=" + charts.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("smokeOk", true);
        out.put("charts", results);
        return out;
    }

    @PostMapping("/{id}/publish")
    @RequireAtom("bi:publish")
    @Transactional
    public Map<String, Object> publish(@PathVariable long id, HttpServletRequest http) {
        // 锁住看板行：两个并发发布会各自读到同一个最新版本，随后都插 v2 → unique 冲突 500。
        // 发布本身也必须是原子的（快照 + 状态 + 版本号），所以放在同一事务里。
        Map<String, Object> dashboard = findForUpdate(id);
        List<Map<String, Object>> charts = charts(id);
        if (charts.isEmpty()) {
            throw ApiException.badRequest("看板还没有图表，无法发布");
        }
        String hash = contentHash(dashboard, charts, paramsOf(dashboard));
        Map<String, Object> latest = latestRelease(id);
        String releaseHash = latest == null ? null
                : contentHashOfSnapshot(latest.get("snapshot"), projectIdOf(dashboard));
        if (!hash.equals(releaseHash) && !hash.equals(dashboard.get("smoke_hash"))) {
            throw ApiException.badRequest(
                    "请先在 DEV 跑通：跑通后才能发布（修改后需重新跑通）");
        }
        boolean unchanged = hash.equals(releaseHash);
        int next;
        if (unchanged) {
            next = ((Number) latest.get("version")).intValue();
        } else {
            next = BiRules.nextVersion(latest == null
                    ? null : ((Number) latest.get("version")).intValue());
            try {
                jdbc.update("""
                        insert into platform.bi_dashboard_release
                            (dashboard_id, version, snapshot, published_at, published_by)
                        values (?, ?, ?::jsonb, now(), ?)
                        """, id, next, snapshot(dashboard, charts), user(http));
            } catch (DuplicateKeyException e) {
                // 行锁理论上已串行化；保留兜底，避免极端情况下把唯一键冲突暴露成 500。
                throw ApiException.conflict("看板正在被并发发布，请刷新后重试");
            }
        }
        jdbc.update("""
                update platform.bi_dashboard
                set status = 'PUBLISHED', version = ?, published_at = now()
                where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, next, id, com.lab.dataworks.auth.TenantScope.current());
        auditOutsideTx(http, "BI_DASHBOARD_PUBLISH", String.valueOf(dashboard.get("code")),
                "v" + next + (unchanged ? "（内容未变）" : ""));
        return Map.of("ok", true, "version", next, "unchanged", unchanged);
    }

    /**
     * 历史发布版本：按版本倒序，{@code current} 标出线上快照版本。
     *
     * <p>BUG-TC6-3：看板发布后只能在列表里看到一个 release_version，
     * 看不到历史版本，也无从回滚。</p>
     */
    @GetMapping("/{id}/releases")
    @RequireAtom("query:run")
    public List<Map<String, Object>> releases(@PathVariable long id) {
        find(id); // 租户归属校验
        return jdbc.queryForList("""
                select r.version, r.published_at, r.published_by,
                       jsonb_array_length(coalesce(r.snapshot->'charts', '[]'::jsonb)) as chart_count,
                       (r.version = d.version) as current
                  from platform.bi_dashboard_release r
                  join platform.bi_dashboard d on d.id = r.dashboard_id
                 where r.dashboard_id = ? and d.tenant_id = ?
                 order by r.version desc
                """, id, TenantScope.current());
    }

    /**
     * 按历史版本回滚：把旧快照的名称/描述/参数/图表恢复到开发态，并生成一个新版本。
     *
     * <p>回滚的是历史上已发布过的版本，跳过「先在 DEV 跑通」闸门（与工作流 rollback 同口径）。
     * 恢复后草稿 == 最新快照，重复发布走「内容未变」分支。整个动作在一个事务里，
     * 任何一步失败都不会留下「图表删了没插回去」的半截看板。</p>
     */
    @PostMapping("/{id}/rollback/{version}")
    @RequireAtom("bi:publish")
    @Transactional
    public Map<String, Object> rollback(@PathVariable long id, @PathVariable int version,
                                        HttpServletRequest http) {
        Map<String, Object> dashboard = findForUpdate(id);
        List<Map<String, Object>> found = jdbc.queryForList("""
                select version, snapshot::text as snapshot
                  from platform.bi_dashboard_release
                 where dashboard_id = ? and version = ?
                """, id, version);
        if (found.isEmpty()) {
            throw ApiException.notFound("版本不存在: v" + version);
        }
        Map<String, Object> root = snapshotRoot(found.get(0).get("snapshot"));
        List<Map<String, Object>> charts = chartsOfSnapshot(found.get(0).get("snapshot"));
        long projectId = ((Number) dashboard.get("project_id")).longValue();
        // 快照数据源必须仍属于看板当前项目：快照冻结的是发布时的项目，搬过项目的看板
        // 直接回滚会造出跨项目的图表（运行期才 404，比现在拒绝更贵）。
        for (Map<String, Object> chart : charts) {
            Object ds = chart.get("datasourceId");
            if (ds == null) {
                throw ApiException.conflict("版本 v" + version + " 的图表「"
                        + chart.get("name") + "」没有数据源，不能回滚");
            }
            Long ok = jdbc.queryForObject("""
                    select count(*) from platform.datasource where id = ? and project_id = ?
                    """, Long.class, ((Number) ds).longValue(), projectId);
            if (ok == null || ok == 0) {
                throw ApiException.conflict("版本 v" + version + " 的图表「"
                        + chart.get("name") + "」引用的数据源已不存在或已移出本项目，不能回滚");
            }
        }
        Integer max = jdbc.queryForObject("""
                select coalesce(max(version), 0) from platform.bi_dashboard_release
                 where dashboard_id = ?
                """, Integer.class, id);
        int next = (max == null ? 0 : max) + 1;
        jdbc.update("""
                update platform.bi_dashboard
                   set name = ?, description = ?, params_json = ?,
                       status = 'PUBLISHED', version = ?, published_at = now(), updated_at = now()
                 where id = ? and tenant_id = ?
                """, root.get("name"), root.get("description"),
                paramsJson(paramsOfSnapshot(root)), next, id, TenantScope.current());
        restoreCharts(id, charts);
        // 用恢复后的行重新生成快照（projectId 取当前项目），而不是复用旧快照原文：
        // 看板搬过项目时旧快照的 projectId 会过期，草稿会一直显示「有未发布改动」。
        Map<String, Object> fresh = find(id);
        try {
            jdbc.update("""
                    insert into platform.bi_dashboard_release
                        (dashboard_id, version, snapshot, published_at, published_by)
                    values (?, ?, ?::jsonb, now(), ?)
                    """, id, next, snapshot(fresh, charts(id)), user(http));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("看板正在被并发发布，请刷新后重试");
        }
        auditOutsideTx(http, "BI_DASHBOARD_ROLLBACK", String.valueOf(dashboard.get("code")),
                "to v" + version + " as v" + next);
        return Map.of("ok", true, "version", next, "rolledBackTo", version);
    }

    @PostMapping("/{id}/offline")
    @RequireAtom("bi:publish")
    public Map<String, Object> offline(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> dashboard = find(id);
        // S12：detail 带下线前状态，回答「从什么状态被下线」
        return sessions.auditAround(user(http), "BI_DASHBOARD_OFFLINE",
                String.valueOf(dashboard.get("code")),
                "id " + id + " name=" + dashboard.get("name")
                        + " 下线前状态=" + dashboard.get("status") + " → OFFLINE",
                AuthInterceptor.clientIp(http), () -> {
                    jdbc.update("""
                            update platform.bi_dashboard set status = 'OFFLINE'
                             where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                            """, id, com.lab.dataworks.auth.TenantScope.current());
                    return Map.of("ok", true);
                });
    }

    // ---------------------------------------------------------------- 数据

    /**
     * 看板数据：env=PRD 读生产快照（未发布 403），env=DEV 读开发态（需要编辑权限）。
     * 单个图表失败不影响其他图表，返回该图表的 error。
     */
    @GetMapping("/{id}/data")
    @RequireAtom("query:run")
    public Map<String, Object> data(@PathVariable long id,
                                    @RequestParam(defaultValue = "PRD") String env,
                                    @RequestParam(required = false) String params,
                                    HttpServletRequest http) {
        // D22：env 只认 DEV / PRD，非法值 400（规则与用例见 BiRules.envOf）
        String envName = BiRules.envOf(env);
        boolean dev = "DEV".equals(envName);
        Map<String, Object> dashboard = find(id);
        List<Map<String, Object>> charts;
        List<DataServiceController.ParamSpec> paramDefs;
        Integer version;
        // 生产快照冻结项目归属：看板后来被移到别的项目时，PRD 仍按发布时的项目判定数据分级；
        // 开发态跟随当前看板项目。否则同一条线上快照会因项目切换而换一套权限/数据源口径。
        Long projectId;
        if (dev) {
            if (!com.lab.dataworks.auth.AtomCheck.has(http, "bi:write")) {
                throw new ApiException(403, "预览开发态需要「建/改数据看板」权限");
            }
            charts = charts(id);
            paramDefs = paramsOf(dashboard);
            version = dashboard.get("version") instanceof Number n ? n.intValue() : null;
            projectId = projectIdOf(dashboard);
        } else {
            if (!"PUBLISHED".equals(String.valueOf(dashboard.get("status")))) {
                throw new ApiException(403, "看板尚未发布或已下线");
            }
            Map<String, Object> latest = latestRelease(id);
            if (latest == null) {
                throw new ApiException(403, "看板尚未发布");
            }
            version = ((Number) latest.get("version")).intValue();
            charts = chartsOfSnapshot(latest.get("snapshot"));
            paramDefs = paramsOfSnapshot(snapshotRoot(latest.get("snapshot")));
            projectId = snapshotProjectId(latest.get("snapshot"), dashboard);
        }
        Map<String, Object> overrides = parseValues(params);
        // 传了未声明的参数（典型是拼写错误）直接 400，避免静默忽略后拿到全量/空结果。
        List<String> unknown = BiRules.unknownKeys(overrides.keySet(), paramNames(paramDefs));
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("未定义的参数: " + String.join("、", unknown));
        }
        Map<String, Object> values = paramValues(paramDefs, overrides);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> chart : charts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", chart.get("name"));
            item.put("chartType", chart.get("chartType"));
            item.put("xColumn", chart.get("xColumn"));
            item.put("yColumn", chart.get("yColumn"));
            item.put("seriesColumn", chart.get("seriesColumn"));
            try {
                QueryExecution execution = runChart(chart, user(http), paramDefs, values,
                        projectId);
                item.put("columns", execution.result().columns());
                item.put("rows", execution.result().rows());
                item.put("rowCount", execution.result().rows().size());
                item.put("truncated", execution.result().truncated());
                item.put("elapsedMs", execution.result().elapsedMs());
            } catch (Exception e) {
                item.put("error", e.getMessage());
                item.put("columns", List.of());
                item.put("rows", List.of());
            }
            out.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", dashboard.get("name"));
        result.put("env", envName);
        result.put("version", version);
        result.put("params", paramDefs);
        result.put("paramValues", values);
        result.put("charts", out);
        return result;
    }

    private record QueryExecution(DataServiceController.QueryResult result) {
    }

    /** 执行单个图表 SQL，并按当前用户过分级网关（判定 + 脱敏）。v483：带上看板所属项目。 */
    private QueryExecution runChart(Map<String, Object> chart, String principal,
                                    List<DataServiceController.ParamSpec> params,
                                    Map<String, Object> values, Long projectId) {
        Object rawId = chart.get("datasourceId");
        if (!(rawId instanceof Number dsId)) {
            throw ApiException.badRequest("图表未绑定数据源");
        }
        if (projectId == null) {
            throw ApiException.badRequest("图表缺少所属项目，无法校验数据源归属");
        }
        // 运行期再按项目 + 租户复核：只靠保存时校验的话，数据源改归属/快照被搬项目后
        // 仍可能拿到别的项目的连接串。
        Map<String, Object> ds = datasource(dsId.longValue(), projectId);
        String sql = String.valueOf(chart.get("sqlText"));
        // 老快照可能是在校验上线前保存的：执行期再兜底一次，避免未声明参数被静默绑定 NULL。
        List<String> missing = BiRules.undeclared(
                BiRules.namedParams(sql), paramNames(params));
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("图表「" + chart.get("name")
                    + "」的 SQL 使用了未定义的参数: " + String.join("、", missing));
        }
        DataServiceController.QueryResult result = DataServiceController.execute(
                String.valueOf(ds.get("jdbc_url")), String.valueOf(ds.get("username")),
                cipher.decrypt(String.valueOf(ds.get("password"))), sql,
                params, values);
        DataGuardService.Guard guard = dataGuard.inspect(principal, sql, projectId);
        dataGuard.logDecision(principal, guard, null, "bi");
        if (guard.systemBlocked()) {
            throw new ApiException(403,
                    "系统库仅平台管理员可读；审计数据请使用平台审计接口 /api/security/audit。");
        }
        if (guard.blocked()) {
            throw ApiException.badRequest("SQL 无法安全解析，已按安全策略拒绝；请使用显式表名");
        }
        if (guard.denied()) {
            throw new ApiException(403, "涉及的数据表超出可访问范围（"
                    + String.join("、", guard.wouldDeny()) + "）");
        }
        // TC7-G6：带原始 SQL 回溯结果列来源，改名列不再漏遮
        List<Map<String, Object>> rows = dataGuard.maskMaps(principal, guard,
                result.columns(), result.rows(), projectId, sql);
        return new QueryExecution(new DataServiceController.QueryResult(
                result.columns(), rows, result.truncated(), result.elapsedMs()));
    }

    // ---------------------------------------------------------------- 内部

    /** 看板所属项目（分级网关按项目策略判定）；取不到时回退平台默认。 */
    private static Long projectIdOf(Map<String, Object> dashboard) {
        Object value = dashboard == null ? null : dashboard.get("project_id");
        return value instanceof Number n ? n.longValue() : null;
    }

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.*, d.tenant_id as tenant_code, p.name as project_name
                from platform.bi_dashboard d
                join platform.project p on p.id = d.project_id
                where d.id = ? and d.tenant_id = ?
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("看板不存在: " + id);
        }
        return rows.get(0);
    }

    /** 发布时锁住看板行，串行化并发发布（版本号 +1 的读-改-写）。 */
    private Map<String, Object> findForUpdate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.*, d.tenant_id as tenant_code, p.name as project_name
                from platform.bi_dashboard d
                join platform.project p on p.id = d.project_id
                where d.id = ? and d.tenant_id = ?
                  for update of d
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("看板不存在: " + id);
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> charts(long dashboardId) {
        return jdbc.queryForList("""
                select id, chart_type as "chartType", name, datasource_id as "datasourceId",
                       sql_text as "sqlText", x_column as "xColumn", y_column as "yColumn",
                       series_column as "seriesColumn", sort_order as "sortOrder"
                from platform.bi_chart
                where dashboard_id = ? order by sort_order, id
                """, dashboardId);
    }

    private Map<String, Object> latestRelease(long dashboardId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, version, snapshot::text as snapshot, published_at, published_by
                from platform.bi_dashboard_release
                where dashboard_id = ? order by version desc limit 1
                """, dashboardId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chartsOfSnapshot(Object snapshot) {
        try {
            Map<String, Object> root = mapper.readValue(String.valueOf(snapshot), Map.class);
            Object charts = root.get("charts");
            return charts instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();
        } catch (Exception e) {
            throw new ApiException(500, "生产快照损坏：无法读取图表定义");
        }
    }

    /**
     * 计算历史快照的内容指纹。
     *
     * <p>V61 时代的快照没有 {@code projectId}；用看板当前项目回填，避免升级后所有已发布
     * 看板都被判成「有未发布改动」。新快照会写入 projectId，移动项目后指纹自然变化。</p>
     */
    private String contentHashOfSnapshot(Object snapshot, Long fallbackProjectId) {
        try {
            Map<String, Object> root = snapshotRoot(snapshot);
            if (root.get("projectId") == null && fallbackProjectId != null) {
                root.put("projectId", fallbackProjectId);
            }
            return contentHash(root, chartsOfSnapshot(snapshot), paramsOfSnapshot(root));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotRoot(Object snapshot) {
        try {
            return mapper.readValue(String.valueOf(snapshot), Map.class);
        } catch (Exception e) {
            throw new ApiException(500, "生产快照损坏：无法读取看板定义");
        }
    }

    /** 快照里的参数定义（发布时冻结的那一份）。 */
    private List<DataServiceController.ParamSpec> paramsOfSnapshot(Map<String, Object> root) {
        Object raw = root == null ? null : root.get("params");
        if (!(raw instanceof List<?>)) {
            return List.of();
        }
        try {
            return mapper.convertValue(raw, new com.fasterxml.jackson.core.type
                    .TypeReference<List<DataServiceController.ParamSpec>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String snapshot(Map<String, Object> dashboard, List<Map<String, Object>> charts) {
        Map<String, Object> root = new LinkedHashMap<>();
        // 项目归属必须进快照：线上执行时按发布时的项目做分级判定/数据源校验，
        // 而不是看板当前（可能已被移动）的项目。
        root.put("projectId", dashboard.get("project_id"));
        root.put("name", dashboard.get("name"));
        root.put("description", dashboard.get("description"));
        root.put("params", paramsOf(dashboard));
        root.put("charts", charts);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ApiException(500, "看板快照序列化失败");
        }
    }

    /** 生产快照冻结的项目；历史快照没有该字段时回退到看板当前项目。 */
    private Long snapshotProjectId(Object snapshot, Map<String, Object> dashboard) {
        Object raw = snapshotRoot(snapshot).get("projectId");
        return raw instanceof Number n ? n.longValue() : projectIdOf(dashboard);
    }

    /**
     * 开发态内容指纹：名称 + 描述 + 参数定义 + 每个图表（类型/名称/数据源/SQL/字段映射）。
     * 参数默认值变了也要重新 DEV 跑通：它直接影响线上查出来的数据。
     */
    private static String contentHash(Map<String, Object> dashboard,
                                      List<Map<String, Object>> charts,
                                      List<DataServiceController.ParamSpec> params) {
        StringBuilder sb = new StringBuilder();
        // 项目变了要重新 DEV 跑通：分级判定、数据源归属都按项目生效。
        sb.append(projectRef(dashboard)).append('\n')
                .append(dashboard.get("name")).append('\n')
                .append(dashboard.get("description")).append('\n');
        for (DataServiceController.ParamSpec p : params == null
                ? List.<DataServiceController.ParamSpec>of() : params) {
            sb.append("param|").append(p.name()).append('|').append(p.type()).append('|')
                    .append(p.required()).append('|').append(p.defaultValue()).append('\n');
        }
        for (Map<String, Object> chart : charts) {
            sb.append(chart.get("chartType")).append('|')
                    .append(chart.get("name")).append('|')
                    .append(chart.get("datasourceId")).append('|')
                    .append(chart.get("sqlText")).append('|')
                    .append(chart.get("xColumn")).append('|')
                    .append(chart.get("yColumn")).append('|')
                    .append(chart.get("seriesColumn")).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /** 兼容两种 map 形态：数据库行用 {@code project_id}，快照根用 {@code projectId}。 */
    private static Object projectRef(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object value = row.get("projectId");
        return value != null ? value : row.get("project_id");
    }

    private void decorate(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        String hash = contentHash(row, charts(id), paramsOf(row));
        row.put("smoke_ok", hash.equals(row.get("smoke_hash")));
        Map<String, Object> latest = latestRelease(id);
        row.put("draft_dirty", latest != null
                && !hash.equals(contentHashOfSnapshot(latest.get("snapshot"), projectIdOf(row))));
    }

    /**
     * REQ-DW-9：编码留空时按规则自动生成（dash_yyyyMMdd_6位随机），查重不过夜、最多重试 5 次；
     * 租户内唯一由 create 里的查重 + 唯一键兜底，这里的跨租户查重只是生成期防碰撞。
     */
    private String resolveCode(String input) {
        if (input != null && !input.isBlank()) {
            return input.trim();
        }
        for (int i = 0; i < 5; i++) {
            String candidate = AutoCode.generate("dash");
            Long n = jdbc.queryForObject(
                    "select count(*) from platform.bi_dashboard where code = ?", Long.class, candidate);
            if (n == null || n == 0) {
                return candidate;
            }
        }
        throw ApiException.conflict("自动生成看板编码失败，请重试");
    }

    private void validate(DashboardRequest req) {
        validate(req, true);
    }

    /** REQ-DW-9：创建时编码可留空（自动生成），更新必须显式带原编码。 */
    private void validate(DashboardRequest req, boolean codeRequired) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.projectId() == null) {
            throw ApiException.badRequest("请选择项目");
        }
        String code = req.code() == null ? "" : req.code().trim();
        if (code.isEmpty()) {
            if (codeRequired) {
                throw ApiException.badRequest("看板编码不能为空");
            }
        } else if (!code.matches("[A-Za-z][A-Za-z0-9_]{1,63}")) {
            throw ApiException.badRequest("看板编码必须以字母开头，只含字母/数字/下划线，2-64 位");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("看板名称不能为空");
        }
        if (req.name().trim().length() > 128) {
            throw ApiException.badRequest("看板名称不能超过 128 个字符");
        }
        List<DataServiceController.ParamSpec> params = req.params() == null
                ? List.of() : req.params();
        if (params.size() > MAX_CHARTS) {
            throw ApiException.badRequest("看板参数最多 30 个");
        }
        Set<String> seen = new HashSet<>();
        for (DataServiceController.ParamSpec p : params) {
            if (p == null || p.name() == null
                    || !p.name().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                throw ApiException.badRequest(
                        "参数名必须以字母或下划线开头，只含字母/数字/下划线: "
                                + (p == null ? "" : p.name()));
            }
            if (!seen.add(p.name())) {
                throw ApiException.badRequest("参数名重复: " + p.name());
            }
            String type = p.type() == null ? "STRING" : p.type().toUpperCase(Locale.ROOT);
            if (!Set.of("STRING", "INT", "LONG", "DECIMAL", "BOOL", "DATE", "DATETIME")
                    .contains(type)) {
                throw ApiException.badRequest("参数「" + p.name() + "」类型不支持: " + p.type());
            }
        }
        List<ChartRequest> charts = req.charts() == null ? List.of() : req.charts();
        if (charts.size() > MAX_CHARTS) {
            throw ApiException.badRequest("一个看板最多 " + MAX_CHARTS + " 个图表");
        }
        Set<String> declared = new HashSet<>(seen);
        for (ChartRequest chart : charts) {
            if (chart == null || chart.name() == null || chart.name().isBlank()) {
                throw ApiException.badRequest("图表名称不能为空");
            }
            if (chart.sqlText() == null || chart.sqlText().isBlank()) {
                throw ApiException.badRequest("图表「" + chart.name() + "」缺少 SQL");
            }
            String type = chart.chartType() == null ? "TABLE"
                    : chart.chartType().toUpperCase(Locale.ROOT);
            if (!CHART_TYPES.contains(type)) {
                throw ApiException.badRequest(
                        "图表类型只能是 TABLE / LINE / BAR / PIE / KPI");
            }
            if (chart.datasourceId() == null) {
                throw ApiException.badRequest("图表「" + chart.name() + "」未选择数据源");
            }
            DataServiceController.checkReadOnly(chart.sqlText());
            // SQL 里的 :name 必须先在看板参数列表声明；否则执行期会被静默绑定 NULL。
            List<String> undeclared = BiRules.undeclared(
                    BiRules.namedParams(chart.sqlText()), declared);
            if (!undeclared.isEmpty()) {
                throw ApiException.badRequest("图表「" + chart.name()
                        + "」的 SQL 使用了未定义的参数: " + String.join("、", undeclared)
                        + "；请先在参数列表里定义");
            }
        }
    }

    /** 参数定义里声明的名字集合（忽略异常的空名）。 */
    private static Set<String> paramNames(List<DataServiceController.ParamSpec> params) {
        Set<String> out = new HashSet<>();
        for (DataServiceController.ParamSpec p : params == null
                ? List.<DataServiceController.ParamSpec>of() : params) {
            if (p != null && p.name() != null) {
                out.add(p.name());
            }
        }
        return out;
    }

    /** 数据源必须属于看板所在项目：在建/改之前校验，避免插入后失败留下空看板。 */
    private void checkChartDatasources(long projectId, List<ChartRequest> charts) {
        for (ChartRequest chart : charts == null ? List.<ChartRequest>of() : charts) {
            Long ok = jdbc.queryForObject("""
                    select count(*) from platform.datasource where id = ? and project_id = ?
                    """, Long.class, chart.datasourceId(), projectId);
            if (ok == null || ok == 0) {
                throw ApiException.badRequest(
                        "图表「" + chart.name() + "」的数据源不属于该项目");
            }
        }
    }

    private void saveCharts(long dashboardId, List<ChartRequest> charts) {
        jdbc.update("delete from platform.bi_chart where dashboard_id = ?", dashboardId);
        int order = 0;
        for (ChartRequest chart : charts == null ? List.<ChartRequest>of() : charts) {
            Long dsId = chart.datasourceId();
            jdbc.update("""
                    insert into platform.bi_chart
                        (dashboard_id, chart_type, name, datasource_id, sql_text,
                         x_column, y_column, series_column, sort_order)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, dashboardId,
                    chart.chartType() == null ? "TABLE"
                            : chart.chartType().toUpperCase(Locale.ROOT),
                    chart.name().trim(), dsId, chart.sqlText().trim(),
                    blankToNull(chart.xColumn()), blankToNull(chart.yColumn()),
                    blankToNull(chart.seriesColumn()), order++);
        }
        jdbc.update("""
                update platform.bi_dashboard set updated_at = now()
                 where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, dashboardId, com.lab.dataworks.auth.TenantScope.current());
    }

    /**
     * 用历史快照恢复图表（回滚专用）。
     *
     * <p>快照里的键用的是 {@code charts(...)} 的别名口径（chartType / datasourceId / sqlText …），
     * 不能直接喂 {@link #saveCharts}（它吃的是请求体 {@code ChartRequest}）。</p>
     */
    private void restoreCharts(long dashboardId, List<Map<String, Object>> charts) {
        jdbc.update("delete from platform.bi_chart where dashboard_id = ?", dashboardId);
        int order = 0;
        for (Map<String, Object> chart : charts) {
            Object ds = chart.get("datasourceId");
            jdbc.update("""
                    insert into platform.bi_chart
                        (dashboard_id, chart_type, name, datasource_id, sql_text,
                         x_column, y_column, series_column, sort_order)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, dashboardId,
                    chart.get("chartType") == null ? "TABLE" : chart.get("chartType"),
                    chart.get("name"),
                    ds instanceof Number n ? n.longValue() : null,
                    chart.get("sqlText"),
                    chart.get("xColumn"), chart.get("yColumn"), chart.get("seriesColumn"),
                    order++);
        }
        jdbc.update("""
                update platform.bi_dashboard set updated_at = now()
                 where id = ? and tenant_id = ?
                """, dashboardId, TenantScope.current());
    }

    /** 按项目 + 租户取数据源：跨项目引用在运行期也拿不到连接串。 */
    private Map<String, Object> datasource(long id, long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select jdbc_url, username, password from platform.datasource
                 where id = ? and project_id = ? and tenant_id = ?
                """, id, projectId, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在或不属于看板所在项目: " + id);
        }
        return rows.get(0);
    }

    private void requireProject(long projectId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<DataServiceController.ParamSpec> paramsOf(Map<String, Object> dashboard) {
        Object raw = dashboard == null ? null : dashboard.get("params_json");
        return parseParams(raw == null ? null : String.valueOf(raw));
    }

    private List<DataServiceController.ParamSpec> parseParams(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type
                    .TypeReference<List<DataServiceController.ParamSpec>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 查看时传入的参数值（JSON 对象）。格式不对直接 400，不要静默当没传。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseValues(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = mapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw ApiException.badRequest("参数值必须是 JSON 对象，例如 {\"day\":\"2026-10-24\"}");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("参数值不是合法 JSON: " + e.getMessage());
        }
    }

    private String paramsJson(List<DataServiceController.ParamSpec> params) {
        try {
            return mapper.writeValueAsString(params == null ? List.of() : params);
        } catch (Exception e) {
            throw ApiException.badRequest("参数定义无法序列化");
        }
    }

    /** 参数默认值 + 本次查看传入的值；没传的用默认值。 */
    private Map<String, Object> paramValues(List<DataServiceController.ParamSpec> params,
                                            Map<String, Object> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (DataServiceController.ParamSpec p : params == null
                ? List.<DataServiceController.ParamSpec>of() : params) {
            Object value = overrides == null ? null : overrides.get(p.name());
            if (value == null || String.valueOf(value).isBlank()) {
                value = p.defaultValue();
            }
            if (value != null) {
                values.put(p.name(), value);
            }
        }
        return values;
    }

    private static String user(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    /**
     * S13：把审计写到业务事务<b>之外</b>。
     *
     * <p>本控制器的写方法带 {@code @Transactional}，审计原来在事务<b>内</b>插入：
     * 业务一旦回滚（唯一键冲突、图表校验失败、Doris 侧报错……）审计行跟着一起消失，
     * 用户在安全中心里查不到任何痕迹，这正是「审计与业务不在同一事务」的反向症状。
     * 这里注册事务同步：提交成功后写 {@code SUCCESS}；回滚后补一条 {@code FAILED}
     * （此时业务事务已结束，这条插入不会再被回滚）。没有活动事务时按原样立即落库。</p>
     *
     * <p>审计自身写失败只 {@code log.error}，不影响已经提交的业务结果。</p>
     */
    // 包级可见：单测直接验证「提交后写 SUCCESS / 回滚后写 FAILED」，不必真起事务管理器
    void auditOutsideTx(HttpServletRequest http, String action, String target, String detail) {
        String actor = user(http);
        String ip = AuthInterceptor.clientIp(http);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeAudit(actor, action, target, detail, "SUCCESS", ip);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeAudit(actor, action, target, detail, "SUCCESS", ip);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;   // 成功路径已在 afterCommit 记过
                }
                safeAudit(actor, action, target,
                        (detail == null || detail.isBlank() ? "" : detail + "；")
                                + "事务回滚，操作未生效", "FAILED", ip);
            }
        });
    }

    private void safeAudit(String actor, String action, String target, String detail,
                           String result, String ip) {
        try {
            sessions.audit(actor, action, target, detail, result, ip);
        } catch (RuntimeException e) {
            log.error("BI 审计写入失败 action={} target={} result={}: {}", action, target, result, e);
        }
    }
}
