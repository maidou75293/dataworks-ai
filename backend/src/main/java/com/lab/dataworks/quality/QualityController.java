package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.auth.UserService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class QualityController {

    /**
     * TC7-G1：对标 DataWorks 内置监控规则模板补齐四类——
     * VOLATILITY（行数波动率）/REGEX（格式校验）/ENUM（枚举白名单）/DISCRETE（离散值个数）。
     * 执行分支在 {@link QualityService}，创建期配置校验在 {@code QualityService.validateConfig}；
     * 验收契约 docs/测试-TC7-G1.md §五，口径说明 docs/质量规则新类型-波动率正则枚举离散值-20261027.md。
     */
    static final Set<String> RULE_TYPES = Set.of(
            "ROW_COUNT", "NULL_RATE", "UNIQUE", "RANGE", "FRESHNESS", "CUSTOM_SQL",
            "VOLATILITY", "REGEX", "ENUM", "DISCRETE");

    /**
     * 走 {@code POST /api/quality/gate} 需要的权限点。
     *
     * <p>D49：这个值**只能写在方法里，不能写成 {@code @RequireAtom}**。
     * {@code /api/quality/gate} 在 {@link AuthInterceptor#preHandle} 的会话豁免分支里
     * 就 {@code return true} 了（内部凭证调用方没有会话），而注解是在那次 return 之后
     * 才被读的——挂上去等于装饰，谁都不会被拦。判定统一收在
     * {@link #authorizeGate}，回归用例见 {@code QualityGateAuthTest}。</p>
     */
    static final String GATE_ATOM = "quality:run";

    /**
     * TC7-G2：规则强度。STRONG = 失败时阻断跨工作流下游调度；WEAK = 只告警。
     *
     * <p>存 {@code config_json.severity} 而不是新列：config_json 本来就是规则的选项包，
     * 这样不占迁移号（V116 已被 D51 预留）。缺省按 WEAK 处理，
     * 保证升级不会让存量规则突然把别人的链路停掉。</p>
     */
    private static final Set<String> SEVERITIES = Set.of("STRONG", "WEAK");

    private final JdbcTemplate jdbc;
    private final QualityService quality;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final UserService users;
    private final PermissionResolver permissions;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;
    /** BUG-TC4-4：环境落点判定（与 D57 / TC-2 / TC-3 同一处，不各写一份）。 */
    private final com.lab.dataworks.project.ProjectEnvironmentService environments;

    public QualityController(JdbcTemplate jdbc, QualityService quality, ObjectMapper mapper,
                             SessionService sessions, UserService users,
                             PermissionResolver permissions,
                             com.lab.dataworks.auth.InternalTokenService internalTokens,
                             com.lab.dataworks.project.ProjectEnvironmentService environments) {
        this.jdbc = jdbc;
        this.quality = quality;
        this.mapper = mapper;
        this.sessions = sessions;
        this.users = users;
        this.permissions = permissions;
        this.internalTokens = internalTokens;
        this.environments = environments;
    }

    /**
     * BUG-TC4-6：不再静默吞掉未知字段（acceptance-rules 第 4 条「不许静默」）。
     * 以前 body 里多带 {@code env} 之类的字段会 200 收单再丢掉，
     * 用户以为指定了生产环境，实际按默认路径执行；现在多一个字段就 400。
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    /**
     * {@code allowProd}（BUG-TC4-4）： tableName 写在生产库（{@code *_prd} 或项目 prod_database）时，
     * 必须显式带 {@code allowProd=true} 才允许创建——这是一条「发布线规则」，
     * 只能由 {@code /api/quality/gate}（内部 token / 发布链路）触发，页面上的「执行」会被拒。
     */
    public record RuleRequest(String code, String name, String ruleType, String tableName,
                              String columnName, Object config, Boolean enabled, String datasource,
                              Long datasourceId, Boolean allowProd) {
    }

    public record GateRequest(String ruleCode, String bizDate) {
    }

    @GetMapping("/projects/{projectId}/quality/rules")
    public List<Map<String, Object>> rules(@PathVariable long projectId) {
        // 列表曾经只按 project_id 查：tenant_b 能读到别人的规则（P0-4）
        requireProject(projectId);
        return jdbc.queryForList("""
                select q.id, q.project_id, q.code, q.name, q.rule_type, q.table_name, q.column_name,
                       q.config_json::text as config, q.enabled, q.datasource, q.datasource_id,
                       coalesce(upper(q.config_json->>'severity'), 'WEAK') as severity,
                       d.name as datasource_name, d.ds_type as datasource_type, q.created_at
                from platform.quality_rule q
                left join platform.datasource d on d.id = q.datasource_id
                where q.project_id = ? order by q.id
                """, projectId);
    }

    /**
     * TC7-G1：内置模板清单（对标 DataWorks「内置监控规则模板」）。
     *
     * <p>只读静态清单，不落库：前端据此在「新建规则」里一键带出类型/字段/配置，
     * 「按模板批量下发」仍走现有创建接口（模板与表的绑定由调用方决定，
     * 见 docs/测试-TC7-G1.md §五契约）。每项含 name/ruleType/columnRequired/description/config。</p>
     */
    @GetMapping("/quality/templates")
    public List<Map<String, Object>> ruleTemplates() {
        return QualityRuleTemplates.list();
    }

    @PostMapping("/projects/{projectId}/quality/rules")
    @RequireAtom("quality:write")
    public Map<String, Object> create(@PathVariable long projectId, @RequestBody RuleRequest req,
                                      HttpServletRequest http) {
        validate(req);
        requireProject(projectId);
        // D56：数据源不再默认 PG——按「显式绑定 → 显式老字段 → 表名解析」定归属，判不出来 400。
        QualityService.RuleTarget target = quality.bindTarget(
                projectId, req.tableName(), req.datasourceId(), req.datasource());
        // BUG-TC4-4 第 3 条：生产库落点在创建时就要求显式确认，别等运行期才炸
        requireProdAcknowledged(projectId, req.tableName(), req.allowProd());
        // S12 + S13：质量规则建/改/删原先零审计（安全扫描泳道 3「下线」小节点名项）。
        // 建规则先记 PENDING 再落库，重复编码 409 等失败也回填 FAILED。
        String detail = "projectId=" + projectId + " code=" + req.code() + " name=" + req.name().trim()
                + " type=" + req.ruleType().toUpperCase(Locale.ROOT)
                + " table=" + req.tableName() + " column=" + req.columnName()
                + " datasource=" + describe(target)
                + " enabled=" + (req.enabled() == null || req.enabled());
        return sessions.auditAround(user(http), "QUALITY_RULE_CREATE", req.code(),
                detail, AuthInterceptor.clientIp(http), () -> {
                    Long id;
                    try {
                        // 创建接口返回 id：调用方拿不到 id 就没法立即执行/引用，只能翻列表（审计 P2-1）
                        id = jdbc.queryForObject("""
                                insert into platform.quality_rule
                                    (project_id, code, name, rule_type, table_name, column_name,
                                     config_json, enabled, datasource, datasource_id, tenant_id)
                                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                                returning id
                                """, Long.class, projectId, req.code(), req.name().trim(),
                                req.ruleType().toUpperCase(Locale.ROOT), req.tableName(), req.columnName(),
                                toJson(req.config()), req.enabled() == null || req.enabled(),
                                target.engine(), target.datasourceId(), TenantScope.current());
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("规则编码已存在: " + req.code());
                    }
                    return Map.of("ok", true, "id", id);
                },
                out -> "id=" + out.get("id"));
    }

    @PutMapping("/quality/rules/{id}")
    @RequireAtom("quality:write")
    public Map<String, Object> update(@PathVariable long id, @RequestBody RuleRequest req,
                                      HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        validate(req);
        Map<String, Object> rule = owned(id);
        // BUG-TC1-1：UPDATE 的 set 列表本来就不含 code，请求里改了编码会被静默丢弃
        // （200 + 旧值不变，违反「不许静默」）。code 是 /api/quality/gate 与已发布 Flow
        // 引用规则的外部标识，允许改会让门禁错位 → 明确禁止：不一致直接 400。
        String currentCode = rule.get("code") == null ? "" : String.valueOf(rule.get("code"));
        if (!currentCode.equals(req.code().trim())) {
            throw ApiException.badRequest("规则编码不可修改: " + currentCode
                    + "（需要换编码请删除规则后重建）");
        }
        long projectId = ((Number) rule.get("project_id")).longValue();
        // D56：改表名/换数据源同样重新解析，避免「改了表、数据源还停在老引擎」这类跑不了的规则
        QualityService.RuleTarget target = quality.bindTarget(
                projectId, req.tableName(), req.datasourceId(), req.datasource());
        requireProdAcknowledged(projectId, req.tableName(), req.allowProd());   // BUG-TC4-4
        // S12 + S13：改规则带「旧值→新值」（门禁被谁改松了，审计里要能直接对账）
        String detail = "id=" + id + " code=" + rule.get("code")
                + " name：" + rule.get("name") + "→" + req.name().trim()
                + "；type：" + rule.get("rule_type") + "→" + req.ruleType().toUpperCase(Locale.ROOT)
                + "；table：" + rule.get("table_name") + "→" + req.tableName()
                + "；column：" + rule.get("column_name") + "→" + req.columnName()
                + "；datasource：" + rule.get("datasource") + "→" + describe(target)
                + "；enabled：" + rule.get("enabled") + "→" + (req.enabled() == null || req.enabled());
        return sessions.auditAround(user(http), "QUALITY_RULE_UPDATE", String.valueOf(rule.get("code")),
                detail, AuthInterceptor.clientIp(http), () -> {
                    int n = jdbc.update("""
                            update platform.quality_rule
                            set name = ?, rule_type = ?, table_name = ?, column_name = ?,
                                config_json = ?::jsonb, enabled = ?, datasource = ?, datasource_id = ?
                            where id = ?
                              and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                            """, req.name().trim(), req.ruleType().toUpperCase(Locale.ROOT), req.tableName(), req.columnName(),
                            toJson(req.config()), req.enabled() == null || req.enabled(),
                            target.engine(), target.datasourceId(), id,
                            com.lab.dataworks.auth.TenantScope.current());
                    if (n == 0) {
                        throw ApiException.notFound("规则不存在: " + id);
                    }
                    return Map.of("ok", true);
                });
    }

    @DeleteMapping("/quality/rules/{id}")
    @RequireAtom("quality:write")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        // 删别人的规则 = 静默拆掉对方的数据质量门禁，必须先做租户归属校验
        Map<String, Object> rule = owned(id);
        // S12 + S13：删规则是破坏性动作——先记 PENDING 再删，detail 带删前的规则内容，
        // 失败（含并发下的 404）也回填 FAILED
        String detail = "id=" + id + " name=" + rule.get("name")
                + " type=" + rule.get("rule_type") + " table=" + rule.get("table_name")
                + " column=" + rule.get("column_name")
                + " enabled=" + rule.get("enabled") + " projectId=" + rule.get("project_id");
        return sessions.auditAround(user(http), "QUALITY_RULE_DELETE", String.valueOf(rule.get("code")),
                detail, AuthInterceptor.clientIp(http), () -> {
                    int n = jdbc.update("""
                            delete from platform.quality_rule
                             where id = ?
                               and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                            """, id, com.lab.dataworks.auth.TenantScope.current());
                    if (n == 0) {
                        throw ApiException.notFound("质量规则不存在: " + id);
                    }
                    return Map.of("ok", true);
                });
    }

    @PostMapping("/quality/rules/{id}/run")
    @RequireAtom("quality:run")
    public Map<String, Object> run(@PathVariable long id,
                                   @RequestParam(required = false) String bizDate) {
        owned(id);
        return quality.evaluate(id, parseDate(bizDate));
    }

    @GetMapping("/projects/{projectId}/quality/results")
    public List<Map<String, Object>> results(@PathVariable long projectId,
                                             @RequestParam(defaultValue = "100") int limit) {
        requireProject(projectId);
        return jdbc.queryForList("""
                select r.id, r.rule_id, q.code as rule_code, q.name as rule_name,
                       r.biz_date, r.passed, r.exec_status, r.detail, r.created_at
                from platform.quality_result r join platform.quality_rule q on q.id = r.rule_id
                where q.project_id = ?
                order by r.id desc limit ?
                """, projectId, Math.max(1, Math.min(500, limit)));
    }

    @PostMapping("/quality/gate")
    public Map<String, Object> gate(@RequestBody GateRequest req,
                                    @RequestHeader(value = "X-Internal-Token", required = false)
                                    String token,
                                    HttpServletRequest http) {
        // AuthInterceptor 对 /api/quality/gate 放行是给 Prefect Flow 用的；
        // 这里必须自己校验，否则任何人都能触发 JDBC 查询并写入质量结果。
        String tenant = authorizeGate(token, http);
        if (req == null || req.ruleCode() == null || req.ruleCode().isBlank()) {
            throw ApiException.badRequest("ruleCode 不能为空");
        }
        Map<String, Object> result = quality.evaluateByCode(
                req.ruleCode(), parseDate(req.bizDate()), tenant);
        // 规则没执行成功 ≠ 数据不达标：422 让调用方知道要回去修规则/数据源，
        // 而不是当成「质量闸门拦下了坏数据」。
        // D56：以前这里是 502 + 数据库原文——502 会被当成网关错误重试，原文还带 SQL 和连接信息。
        if ("ERROR".equals(String.valueOf(result.get("status")))) {
            throw new ApiException(422, "质量规则无法执行：" + result.get("detail")
                    + "（规则 " + result.get("ruleCode") + "，数据源 " + result.get("datasource")
                    + (result.get("datasourceId") == null ? " 未绑定，按项目路由" : " #" + result.get("datasourceId")) + "）");
        }
        if (!Boolean.TRUE.equals(result.get("passed"))) {
            throw new ApiException(409, "质量闸门未通过: " + result.get("detail"));
        }
        return result;
    }

    /**
     * D46：这条规则被项目里哪些工作流当「质量闸门」用、闸门不过会拦住哪些下游任务。
     *
     * <p>只读工作流模型（{@code platform.workflow.model_json} 的 tasks/edges），
     * 不触发任何执行、不写质量结果。真正的「拦」发生在调度期：Flow 里的
     * {@code _run_quality_gate} 判定不达标直接抛错，下游任务 {@code future.result()}
     * 拿不到就跟着不跑。界面上原本看不到「谁在用这条规则当闸门」，这个接口补的就是这一块。</p>
     */
    @GetMapping("/projects/{projectId}/quality/gate-usage")
    public List<Map<String, Object>> gateUsage(@PathVariable long projectId) {
        requireProject(projectId);
        List<Map<String, Object>> workflows = jdbc.queryForList("""
                select w.id, w.code, w.name, w.model_json::text as model
                  from platform.workflow w
                 where w.project_id = ? and w.tenant_id = ?
                 order by w.id
                """, projectId, TenantScope.current());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> wf : workflows) {
            for (GateUsage usage : gateUsages(mapper, String.valueOf(wf.get("model")))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ruleCode", usage.ruleCode());
                row.put("workflowId", wf.get("id"));
                row.put("workflowCode", wf.get("code"));
                row.put("workflowName", wf.get("name"));
                row.put("gateTaskCode", usage.gateTaskCode());
                row.put("blockedTasks", usage.blockedTasks());
                out.add(row);
            }
        }
        return out;
    }

    /** 一条闸门用法：规则编码 + 闸门任务编码 + 因它而被拦住的下游任务。 */
    record GateUsage(String ruleCode, String gateTaskCode, List<String> blockedTasks) {
    }

    /**
     * 从工作流模型里挑出 {@code QUALITY_GATE} 任务。
     *
     * <p>模型不合法/没有 tasks 时返回空列表而不是报错：这是质量页的旁路展示信息，
     * 一份存坏的草稿不该把整个「规则」列表打不开。</p>
     */
    static List<GateUsage> gateUsages(ObjectMapper mapper, String modelJson) {
        if (modelJson == null || modelJson.isBlank() || "null".equals(modelJson)) {
            return List.of();
        }
        JsonNode model;
        try {
            model = mapper.readTree(modelJson);
        } catch (Exception e) {
            return List.of();
        }
        JsonNode tasks = model.path("tasks");
        if (!tasks.isArray()) {
            return List.of();
        }
        Map<String, List<String>> next = new LinkedHashMap<>();
        for (JsonNode task : tasks) {
            String code = task.path("code").asText("");
            if (!code.isBlank()) {
                next.putIfAbsent(code, new ArrayList<>());
            }
        }
        for (JsonNode edge : model.path("edges")) {
            String from = edge.path("from").asText("");
            String to = edge.path("to").asText("");
            // 悬空引用/自环按渲染器的校验口径应当不存在，这里只防御性地忽略
            if (from.equals(to) || !next.containsKey(from) || !next.containsKey(to)) {
                continue;
            }
            next.get(from).add(to);
        }
        List<GateUsage> out = new ArrayList<>();
        for (JsonNode task : tasks) {
            if (!"QUALITY_GATE".equalsIgnoreCase(task.path("type").asText(""))) {
                continue;
            }
            String ruleCode = task.path("ruleCode").asText("").trim();
            String code = task.path("code").asText("");
            if (ruleCode.isBlank() || code.isBlank()) {
                continue;
            }
            out.add(new GateUsage(ruleCode, code, downstream(code, next)));
        }
        return out;
    }

    /** 闸门失败后被拦住的下游任务：沿 edges 的传递闭包（用 visited 兜住环，不会死循环）。 */
    private static List<String> downstream(String from, Map<String, List<String>> next) {
        Set<String> seen = new LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String to : next.getOrDefault(current, List.of())) {
                if (seen.add(to)) {
                    queue.add(to);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * 允许集群内部凭证（Prefect Flow）或具备 quality:run 的登录会话。
     *
     * @return 登录会话调用时返回当前租户（用来限定规则范围）；内部 token 返回 null。
     */
    private String authorizeGate(String token, HttpServletRequest request) {
        if (internalTokens.configured() && internalTokens.matches(token)) {
            return null;
        }
        com.lab.dataworks.auth.SessionService.Session session =
                sessions.validate(AuthInterceptor.bearer(request));
        if (session == null) {
            throw new ApiException(401, "未登录或内部凭证无效");
        }
        String username = session.username();
        UserService.Account account = users.find(username);
        if (account == null || !account.active()) {
            throw new ApiException(403, "账号不可用");
        }
        // 豁免路径没有走 AuthInterceptor 的租户解析，TenantScope 是空的：
        // 这里显式解析，否则权限永远按默认租户算，规则也不限定租户。
        String tenant = permissions.resolveTenant(username, request.getHeader("X-Tenant-Id"));
        if (tenant == null) {
            throw new ApiException(403, "当前账号不属于该租户");
        }
        if (!permissions.atoms(username, tenant).contains(GATE_ATOM)) {
            throw new ApiException(403, "无权限执行质量闸门（需要权限点：" + GATE_ATOM + "）");
        }
        return tenant;
    }

    private void validate(RuleRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.code() == null || !req.code().matches("[a-zA-Z][a-zA-Z0-9_]{1,63}")) {
            throw ApiException.badRequest("规则编码不合法");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("规则名称不能为空");
        }
        if (req.name().trim().length() > 128) {
            throw ApiException.badRequest("规则名称不能超过 128 个字符");
        }
        if (req.ruleType() == null || req.ruleType().isBlank()) {
            throw ApiException.badRequest("规则类型不能为空");
        }
        String type = req.ruleType().trim().toUpperCase(Locale.ROOT);
        if (!RULE_TYPES.contains(type)) {
            throw ApiException.badRequest("不支持的规则类型: " + req.ruleType());
        }
        if (req.tableName() == null || !req.tableName().matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw ApiException.badRequest("表名不合法");
        }
        if (req.tableName().length() > 256) {
            throw ApiException.badRequest("表名不能超过 256 个字符");
        }
        if (req.columnName() != null && !req.columnName().isBlank()) {
            if (!req.columnName().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw ApiException.badRequest("字段名不合法");
            }
            if (req.columnName().length() > 128) {
                throw ApiException.badRequest("字段名不能超过 128 个字符");
            }
        }
        if (List.of("NULL_RATE", "UNIQUE", "RANGE", "REGEX", "ENUM", "DISCRETE").contains(type)
                && (req.columnName() == null || req.columnName().isBlank())) {
            throw ApiException.badRequest("该规则类型必须指定字段");
        }
        // TC7-G1：新类型的关键配置在创建时就验（acceptance-rules 第 4 条「不许静默」），
        // 存量六类的老行为不动；执行期另有同一份校验兜底（QualityService.evaluate）。
        // CONFLICT-WM3-2：与下方 REQ-DW-7 的 CUSTOM_SQL 守卫正交（validateConfig 只管新四类），两段都保留。
        QualityService.validateConfig(type, req.config() == null
                ? mapper.nullNode() : mapper.valueToTree(req.config()));
        // REQ-DW-7 ②：CUSTOM_SQL 创建时就必须有 SQL。页面已收敛为「模板参数/SQL 配置」，
        // 建一条没 SQL 的规则静默入库、等试跑才炸，违反「不许静默」，也毁掉
        // 「建完即可试跑成功」的判据（live 回归 dw7_1793098369 实测原行为是 200）。
        if ("CUSTOM_SQL".equals(type)) {
            boolean hasSql = req.config() instanceof Map<?, ?> map
                    && ((map.get("sql") != null && !String.valueOf(map.get("sql")).isBlank())
                        || (map.get("sql_b64") != null && !String.valueOf(map.get("sql_b64")).isBlank()));
            if (!hasSql) {
                throw ApiException.badRequest(
                        "CUSTOM_SQL 规则需要 config.sql：一条返回单个数字的只读查询（可用 ${biz_date} 代表执行日期）");
            }
        }
        validateSeverity(req.config());
    }

    /**
     * 规则强度校验：非法枚举必须 400，不能静默存成「弱规则」——
     * 用户以为自己配了阻断，实际什么都没挡，比报错更糟。
     */
    private void validateSeverity(Object config) {
        if (config == null) {
            return;
        }
        if (!(config instanceof Map<?, ?> map)) {
            throw ApiException.badRequest("规则配置必须是 JSON 对象");
        }
        Object raw = map.get("severity");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof String value)
                || !SEVERITIES.contains(value.trim().toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("规则强度 severity 只能是 STRONG（阻断下游）或 WEAK（仅告警）");
        }
    }

    private LocalDate parseDate(String value) {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (value == null || value.isBlank()) {
            return today;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("日期格式不正确，应为 yyyy-MM-dd: " + value);
        }
        // 未来日期会让结果污染近 7 天趋势与告警统计，直接拒绝
        if (date.isAfter(today)) {
            throw ApiException.badRequest("执行日期不能晚于今天: " + date);
        }
        return date;
    }

    /** 审计主体：拦截器写进 request 的用户名；没有上下文时记 unknown。 */
    private static String user(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private void requireProject(long projectId) {
        // v447 P0-4：项目必须属于当前租户，跨租户一律 404
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    /** 规则必须属于当前租户（按项目归属判定），取不到 404。 */
    private Map<String, Object> owned(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select q.* from platform.quality_rule q
                 where q.id = ?
                   and q.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("质量规则不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * 生产库落点必须显式确认（BUG-TC4-4 第 3 条）：不确认就 400 说清后果与替代做法，
     * 确认过的规则也只能由发布线触发（{@code QualityService.evaluate} 里 releaseLine=false 会被拒）。
     */
    private void requireProdAcknowledged(long projectId, String tableName, Boolean allowProd) {
        String database = QualityService.databaseOf(tableName);
        if (database == null || Boolean.TRUE.equals(allowProd) || environments == null) {
            return;
        }
        if ("PROD".equals(environments.landingEnv(projectId, database))) {
            throw ApiException.badRequest("表 " + tableName + " 落在生产库 " + database
                    + "：质量门禁默认只跑开发库。确认要建一条只在发布线（POST /api/quality/gate，"
                    + "内部凭证触发）生效的规则，请带 allowProd=true；否则把 tableName "
                    + "改成本项目的开发库（见项目管理的 dev_database）");
        }
    }

    /** 审计里写清「这次落到哪」：引擎 + 绑定 id（没有 id = 按项目数仓路由执行）。 */
    private static String describe(QualityService.RuleTarget target) {
        if (target.datasourceId() == null) {
            return target.engine() + "(按项目数仓路由)";
        }
        return target.engine() + "#" + target.datasourceId()
                + (target.datasourceName() == null ? "" : "(" + target.datasourceName() + ")");
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? new LinkedHashMap<>() : value);
        } catch (Exception e) {
            throw ApiException.badRequest("规则配置序列化失败: " + e.getMessage());
        }
    }
}
