package com.lab.dataworks.project;

import com.lab.dataworks.auth.AtomCheck;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAnyAtom;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);
    private static final int NAME_MAX = 128;
    /** D42：项目成员角色枚举（V113 的 check 约束与此一致）。 */
    private static final Set<String> MEMBER_ROLES = Set.of("OWNER", "ADMIN");
    /** 业务英文名：同时用作 Doris 库名前缀，只允许小写字母和数字。 */
    private static final Pattern BUSINESS = Pattern.compile("^[a-z][a-z0-9]{1,31}$");

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final DorisAdminService doris;
    private final WarehouseConnectionService connections;
    private final TransactionTemplate tx;
    private final com.lab.dataworks.workflow.WorkflowMutationService mutations;
    /** v483：项目级安全策略（解析与缓存都在 DataGuardService）。 */
    private final DataGuardService security;
    /** V98：项目只引用一个 DORIS 数据源，执行目标统一在这里解析。 */
    private final WarehouseResolver warehouseResolver;

    /**
     * D57：项目 DEV/PROD 环境服务。字段注入是为了不改构造签名——现有 4 处测试
     * （含 {@code security/ProjectSecurityControllerTest}，不在本卡文件域）直接 new 这个控制器；
     * 生产环境由 Spring 注入，取不到时清理/巡检/回填三条能力显式拒绝，不静默跳过。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProjectEnvironmentService environment;

    public ProjectController(JdbcTemplate jdbc, SessionService sessions, DorisAdminService doris,
                             WarehouseConnectionService connections,
                             TransactionTemplate tx,
                             com.lab.dataworks.workflow.WorkflowMutationService mutations,
                             DataGuardService security, WarehouseResolver warehouseResolver) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.doris = doris;
        this.connections = connections;
        this.tx = tx;
        this.mutations = mutations;
        this.security = security;
        this.warehouseResolver = warehouseResolver;
    }

    /**
     * 新建项目。
     *
     * <p>带 departmentId 时按部门生成编码与 Doris 库：
     * 编码 = 部门编码_部门ID_业务英文名（gx_01_gongxiang），
     * 库 = 业务英文名_dev / 业务英文名_prd（gongxiang_dev / gongxiang_prd），
     * 表名前缀继承部门。
     * REQ-DW-1：表单不再让用户手填 business——不传时库名前缀按默认规则自动生成
     * （见 {@link #autoBusiness}），传了则沿用旧校验，兼容历史脚本。
     * 不带 departmentId 时保持旧行为（只建项目，不建库），兼容历史脚本/接口。
     */
    /**
     * 项目创建/编辑请求。
     *
     * <p>V98 起数仓只选一个 {@code type=DORIS} 数据源：{@code warehouseDatasourceId}
     * 缺省 = 内置 Doris。{@code storageType / dorisConnectionId / hdfsConnectionId /
     * s3ConnectionId} 仅为老脚本兼容保留；存储已归属数据源，不再从项目上读取。</p>
     */
    public record ProjectRequest(String code, String name, String description,
                                 Long departmentId, String business, String ownerUser,
                                 String storageType, Long dorisConnectionId,
                                 Long hdfsConnectionId, Long s3ConnectionId,
                                 Long warehouseDatasourceId,
                                 String devDatabase, String prodDatabase) {
    }

    /**
     * 项目安全策略写入（v483）。
     *
     * @param confirm 设为 OFF 必须二次确认：true 或字符串 "CONFIRM"
     * @param reason  可选，写入审计便于追溯「谁因为什么关掉了该项目的数据安全」
     */
    public record SecurityRequest(String securityMode, String defaultColumnLevel,
                                  Boolean columnControlRequired, Object confirm,
                                  String reason) {
    }

    /** SQL 守卫开关（V105 / 007 第四节）：项目级，关闭后破坏性语句不再要求审批。 */
    public record SqlGuardRequest(Boolean enabled, String reason) {
    }

    /**
     * 项目列表。
     *
     * <p>v449 P2：支持 {@code keyword / page / size}（page 从 1 开始，size 1–500）。
     * 不传 size 时保持旧行为（全量返回）；传 size 时回 {@code X-Total-Count}。
     * 以前 page/size 被静默忽略，客户端以为分页了其实全量拉。</p>
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          jakarta.servlet.http.HttpServletResponse response) {
        StringBuilder sql = new StringBuilder(SELECT + " where p.tenant_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(com.lab.dataworks.auth.TenantScope.current());
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (p.code ilike ? or p.name ilike ?)");
            String like = com.lab.dataworks.common.LikePattern.contains(keyword);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by p.id");
        if (size != null) {
            if (size < 1 || size > 500) {
                throw ApiException.badRequest("size 取值 1–500");
            }
            int pageNo = page == null ? 1 : page;
            if (pageNo < 1) {
                throw ApiException.badRequest("page 从 1 开始");
            }
            sql.append(" limit ? offset ?");
            args.add(size);
            // D12：offset 用 long，page=2147483647 不再 int 溢出成 409「数据冲突」
            args.add(com.lab.dataworks.common.Paging.offset(pageNo, size));
            Long total = jdbc.queryForObject(
                    "select count(*) from platform.project p where p.tenant_id = ?"
                            + (keyword != null && !keyword.isBlank()
                               ? " and (p.code ilike ? or p.name ilike ?)" : ""),
                    Long.class, keyword != null && !keyword.isBlank()
                            ? new Object[]{com.lab.dataworks.auth.TenantScope.current(),
                                           com.lab.dataworks.common.LikePattern.contains(keyword),
                                           com.lab.dataworks.common.LikePattern.contains(keyword)}
                            : new Object[]{com.lab.dataworks.auth.TenantScope.current()});
            response.setHeader("X-Total-Count", String.valueOf(total == null ? 0 : total));
        }
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping
    @RequireAtom("project:manage")
    public Map<String, Object> create(@RequestBody ProjectRequest req, HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String name = requireName(req.name());
        String code = req.code();
        String devDatabase = null;
        String prodDatabase = null;
        String tablePrefix = null;
        Long departmentId = null;
        String business = req.business();

        if (req.departmentId() != null) {
            Map<String, Object> dept = findDepartment(req.departmentId());
            // REQ-DW-1：表单不再让用户手填业务线/库名。business 缺省时后端按默认规则
            // 自动生成库名前缀（部门编码+部门ID，平台级撞库自动顺延序号避让）；
            // 老脚本显式传 business 时保持原校验（撞库仍由 ensureProjectUnused 报 409）。
            business = business == null || business.isBlank()
                    ? autoBusiness(dept).business()
                    : requireBusiness(business);
            String expected = dept.get("code") + "_" + dept.get("dept_id") + "_" + business;
            if (code != null && !code.isBlank() && !expected.equals(code)) {
                throw ApiException.badRequest("项目编码按部门规则生成，应为 " + expected);
            }
            code = expected;
            devDatabase = business + "_dev";
            prodDatabase = business + "_prd";
            tablePrefix = String.valueOf(dept.get("table_prefix"));
            departmentId = req.departmentId();
        }

        // 025：项目直接指定 dev/prod 库名（不再是存储/业务名推导的附属品）
        if (req.devDatabase() != null || req.prodDatabase() != null) {
            if (req.devDatabase() == null || req.prodDatabase() == null) {
                throw ApiException.badRequest("devDatabase 与 prodDatabase 必须同时提供");
            }
            String dev = dbName(req.devDatabase(), "dev 库名");
            String prod = dbName(req.prodDatabase(), "prod 库名");
            if (departmentId != null
                    && (!dev.equals(devDatabase) || !prod.equals(prodDatabase))) {
                throw ApiException.badRequest("按部门创建时库名由规则生成，不能再指定 dev/prod 库名");
            }
            devDatabase = dev;
            prodDatabase = prod;
        }

        if (code == null || !code.matches("[a-zA-Z][a-zA-Z0-9_]{1,63}")) {
            throw ApiException.badRequest("项目编码必须以字母开头，只含字母、数字、下划线，长度 2-64");
        }
        // D57：DEV/PROD 两列是环境隔离的唯一依据（表管理按它判 env 与审批、质量按它定库、
        // 集成按它填目标库）。以前既不传部门也不传库名就直接落 NULL——项目能用，
        // 但「试跑走 dev、发布走 prd」在数据层根本不成立（工作台 select database() 返回空串）。
        // 现在缺一对就按编码补一对，不再允许空。
        if (devDatabase == null || prodDatabase == null) {
            String[] derived = ProjectEnvironmentService.deriveDatabases(code);
            if (derived == null) {
                throw ApiException.badRequest("项目编码 " + code + " 推不出合法的 Doris 库名，"
                        + "请显式指定 devDatabase 与 prodDatabase（如 xxx_dev / xxx_prd）");
            }
            devDatabase = derived[0];
            prodDatabase = derived[1];
        }
        String creator = username(http);
        String owner = resolveOwner(req.ownerUser(), creator);
        String ownerTenant = ownerTenant(owner);
        // V98：数仓只选一个 DORIS 数据源；未传 = 内置，老脚本的 dorisConnectionId 继续兼容。
        // 存储（HDFS/S3）已归属数据源，项目创建不再建目录/前缀。
        Long datasourceId = req.warehouseDatasourceId();
        Long connectionId = null;
        if (datasourceId != null) {
            WarehouseResolver.DorisDatasource datasource =
                    warehouseResolver.requireDorisDatasource(datasourceId);
            // 内置 Doris 走 MCP：连接 id 必须保持 null
            connectionId = datasource.builtin() ? null
                    : warehouseResolver.warehouseConnectionIdForDatasource(datasource.id());
        } else if (req.dorisConnectionId() != null) {
            // 兼容老脚本：项目还没有数据源引用，交给 WarehouseResolver 回退旧列
            requireConnection(req.dorisConnectionId(), "doris");
            connectionId = req.dorisConnectionId();
        } else {
            datasourceId = warehouseResolver.builtinDatasourceId();
            if (datasourceId == null) {
                throw new ApiException(500,
                        "平台未配置内置 Doris 数据源，请确认 V98 迁移已执行");
            }
        }
        ensureProjectUnused(code, devDatabase, prodDatabase);

        // 先建库再落项目：建库失败时前面已建的库可能是空库，重试会走 ensureDatabase 的复用分支。
        // 不带 departmentId 时没有业务英文名，也就没有库名可建（旧接口兼容行为）；
        // 之前无条件调用会把 null 当库名，直接 400「Doris 库名不合法: null」。
        if (devDatabase != null) {
            doris.ensureDatabase(connectionId, devDatabase);
            doris.ensureDatabase(connectionId, prodDatabase);
        }

        // lambda 里只能用 final 变量：先把生成结果固定下来
        final String finalCode = code;
        final String finalBusiness = business;
        final Long finalDepartmentId = departmentId;
        final String finalDevDatabase = devDatabase;
        final String finalProdDatabase = prodDatabase;
        final String finalTablePrefix = tablePrefix;
        final String finalOwner = owner;
        final String finalOwnerTenant = ownerTenant;
        final Long finalDatasourceId = datasourceId;
        final Long finalConnectionId = connectionId;
        try {
            Long id = tx.execute(status -> {
                KeyHolder keys = new GeneratedKeyHolder();
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("""
                            insert into platform.project
                                (code, name, description, department_id, business,
                                 dev_database, prod_database, table_prefix, owner_user, tenant_id,
                                 warehouse_datasource_id, doris_connection_id)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, finalCode);
                    ps.setString(2, name);
                    ps.setString(3, req.description());
                    if (finalDepartmentId == null) {
                        ps.setNull(4, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(4, finalDepartmentId);
                    }
                    ps.setString(5, finalBusiness);
                    ps.setString(6, finalDevDatabase);
                    ps.setString(7, finalProdDatabase);
                    ps.setString(8, finalTablePrefix);
                    ps.setString(9, finalOwner);
                    ps.setString(10, com.lab.dataworks.auth.TenantScope.current());
                    setNullableLong(ps, 11, finalDatasourceId);
                    setNullableLong(ps, 12, finalConnectionId);
                    return ps;
                }, keys);
                // 项目库登记成可申请资源：负责人就是项目负责人，审批与「全部权限」都按它算。
                if (finalDevDatabase != null) {
                    registerDatabase(finalOwnerTenant, finalDevDatabase, finalOwner,
                            "项目 " + finalCode + " 的 DEV 库（Doris）");
                    registerDatabase(finalOwnerTenant, finalProdDatabase, finalOwner,
                            "项目 " + finalCode + " 的 PROD 库（Doris）");
                }
                // PostgreSQL 把整行都放进 generated keys，getKey() 会因多列直接抛异常。
                Object idValue = keys.getKeys().get("id");
                if (idValue == null) {
                    throw new ApiException(500, "创建项目失败：数据库未返回 id");
                }
                return ((Number) idValue).longValue();
            });
            if (id == null) {
                throw new ApiException(500, "创建项目失败：数据库未返回 id");
            }
            Map<String, Object> created = find(id);
            sessions.audit(username(http), "PROJECT_CREATE", code, name);
            if (devDatabase != null) {
                sessions.audit(username(http), "PROJECT_DATABASE_CREATE", code,
                        devDatabase + ", " + prodDatabase);
            }
            return created;
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求刚抢到同一个编码/库名
            log.warn("创建项目冲突 code={} dev={} prod={}", code, devDatabase, prodDatabase);
            throw ApiException.conflict("项目编码或 Doris 库名已被占用: " + code);
        }
    }

    /**
     * REQ-DW-1 ①：创建表单选完部门后调这个接口，提交前就能看到最终编码/库名/表前缀。
     * skipped&gt;0 表示默认前缀已被（平台内任意租户的）项目占用、已自动顺延避让——
     * 前缀冲突在提交前以提示呈现，而不是提交后吃 409。只回生成本部门所用的参数，
     * 不回任何占用方的项目名/编码，避免跨租户泄露。
     */
    @GetMapping("/business-preview")
    @RequireAtom("project:manage")
    public Map<String, Object> businessPreview(@RequestParam long departmentId) {
        Map<String, Object> dept = findDepartment(departmentId);
        AutoBusiness auto = autoBusiness(dept);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("departmentId", departmentId);
        out.put("business", auto.business());
        out.put("code", dept.get("code") + "_" + dept.get("dept_id") + "_" + auto.business());
        out.put("devDatabase", auto.business() + "_dev");
        out.put("prodDatabase", auto.business() + "_prd");
        out.put("tablePrefix", dept.get("table_prefix"));
        out.put("skipped", auto.skipped());
        return out;
    }

    /** 实时探测项目对应的 Doris 库是否存在（列表不查，避免每条项目连一次 Doris）。 */
    @GetMapping("/{id}/databases")
    public Map<String, Object> databases(@PathVariable long id) {
        Map<String, Object> project = find(id);
        // V98：按项目选择的 DORIS 数据源解析；内置返回 null
        Long connectionId = warehouseResolver.connectionIdForProject(id);
        return Map.of(
                "dev", checkDatabase(connectionId, project.get("dev_database")),
                "prod", checkDatabase(connectionId, project.get("prod_database")));
    }

    private Map<String, Object> checkDatabase(Long connectionId, Object nameValue) {
        if (nameValue == null) {
            return Map.of("name", "", "exists", false);
        }
        String name = String.valueOf(nameValue);
        try {
            return Map.of("name", name, "exists", doris.databaseExists(connectionId, name));
        } catch (ApiException e) {
            return Map.of("name", name, "exists", false, "error", e.getMessage());
        }
    }

    /**
     * 空壳库巡检（D57 第 4 条）。只读：列出该数仓上的库、每个库的表数、被哪些项目引用，
     * 并标出「0 张表且没有任何项目引用」的空壳库——要不要删由人看清单后决定。
     *
     * <p>只给平台管理员（默认租户 + {@code system:manage}）：内置 Doris 是所有租户共用的实例，
     * {@code show databases} 会带出别的租户的库名，普通租户不能看。</p>
     */
    @GetMapping("/database-inventory")
    @RequireAtom("system:manage")
    public Map<String, Object> databaseInventory(@RequestParam(required = false) Long projectId,
                                                 HttpServletRequest http) {
        requireEnvironment();
        if (!platformAdmin(http)) {
            throw new ApiException(403, "空壳库巡检会列出全平台库名，只给平台管理员");
        }
        if (projectId != null) {
            find(projectId);      // 带项目时先验归属：跨租户 404
        }
        return environment.inventory(projectId);
    }

    /**
     * 一次性回填缺失的 DEV/PROD 库名（D57：历史项目两列为空 ⇒ 环境约束落空）。
     *
     * <p>{@code create=false}（默认）只补元数据；{@code create=true} 才在 Doris 上真建库
     * （{@code ensureDatabase} 幂等，目标库存在且非空会 409 原样报出，不会覆盖别人的数据）。
     * 推不出合法库名或库名已被占用的一律跳过并在 conflicts 里说明，不静默改数据。</p>
     */
    @PostMapping("/backfill-databases")
    @RequireAtom("system:manage")
    public Map<String, Object> backfillDatabases(@RequestParam(defaultValue = "false") boolean create,
                                                 HttpServletRequest http) {
        requireEnvironment();
        if (!platformAdmin(http)) {
            throw new ApiException(403, "批量回填库名只给平台管理员");
        }
        return sessions.auditAround(username(http), "PROJECT_BACKFILL_DATABASES", "platform",
                "create=" + create + " tenant=" + com.lab.dataworks.auth.TenantScope.current(),
                AuthInterceptor.clientIp(http), () -> environment.backfill(create),
                out -> "filled=" + out.get("filled") + " conflicts=" + out.get("conflicts"));
    }

    private void requireEnvironment() {
        if (environment == null) {
            throw new ApiException(503, "项目环境服务未注入，巡检 / 回填不可用");
        }
    }

    /** 项目更新允许出现的字段（D41：出现才更新，未知字段 400，不许静默忽略）。 */
    private static final Set<String> PROJECT_UPDATABLE = Set.of(
            "name", "description", "departmentId", "business",
            "warehouseDatasourceId", "dorisConnectionId", "devDatabase", "prodDatabase");

    /**
     * 更新项目（D41 起为 PATCH 语义；D40 起全站只有「管理中心 · 项目设置」一个全字段入口，
     * 数据开发页的「重命名」只发 {@code {"name": ...}}）。
     *
     * <p>契约（见 {@code docs/保存语义-20261026.md}）：
     * <ul>
     *   <li>**请求里出现的字段才更新**；未出现 = 不动（SQL 里根本不进 set 子句）；</li>
     *   <li>**显式 {@code null} = 清空**（description / business / departmentId /
     *       warehouseDatasourceId 回到内置 Doris）；</li>
     *   <li>{@code devDatabase} / {@code prodDatabase} 不可清空：要么两个都出现且非空，
     *       要么都不出现，否则 400；</li>
     *   <li>{@code departmentId} 非空但不存在 → 400，不静默置空；</li>
     *   <li>未知字段 → 400（以前 {@code ProjectRequest} 收 13 个字段只写 6 个，
     *       其余静默忽略，正是 D40/D41 立案的根因）；</li>
     *   <li>{@code storageType / hdfsConnectionId / s3ConnectionId} 是创建期字段，
     *       出现在更新请求里直接 400（界面按「创建后不可改」展示）。</li>
     * </ul>
     * 事务与租户条件沿用 v448 的口径：校验与写入同一事务，UPDATE 带 tenant_id。</p>
     */
    @PutMapping("/{id}")
    @RequireAtom("project:manage")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body,
                                      HttpServletRequest http) {
        if (body == null || body.isEmpty()) {
            throw ApiException.badRequest("请求体不能为空");
        }
        List<String> unknown = body.keySet().stream()
                .filter(key -> !PROJECT_UPDATABLE.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("项目更新不接受这些字段（创建期字段或不存在）: " + unknown);
        }
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (body.containsKey("name")) {
            sets.add("name = ?");
            args.add(requireName(asString(body.get("name"), "name")));
        }
        if (body.containsKey("description")) {
            sets.add("description = ?");
            args.add(asString(body.get("description"), "description"));
        }
        if (body.containsKey("departmentId")) {
            Long departmentId = asLong(body.get("departmentId"), "departmentId");
            if (departmentId != null) {
                findDepartment(departmentId);
            }
            sets.add("department_id = ?");
            args.add(departmentId);
        }
        if (body.containsKey("business")) {
            String business = asString(body.get("business"), "business");
            sets.add("business = ?");
            args.add(business == null ? null : requireBusiness(business));
        }
        if (body.containsKey("warehouseDatasourceId") || body.containsKey("dorisConnectionId")) {
            Long datasourceId = null;
            Long connectionId = null;
            if (body.containsKey("warehouseDatasourceId")) {
                Long wanted = asLong(body.get("warehouseDatasourceId"), "warehouseDatasourceId");
                if (wanted != null) {
                    WarehouseResolver.DorisDatasource datasource =
                            warehouseResolver.requireDorisDatasource(wanted);
                    datasourceId = datasource.id();
                    connectionId = datasource.builtin() ? null
                            : warehouseResolver.warehouseConnectionIdForDatasource(datasource.id());
                }
            } else {
                Long connection = asLong(body.get("dorisConnectionId"), "dorisConnectionId");
                if (connection != null) {
                    requireConnection(connection, "doris");
                    connectionId = connection;
                }
            }
            sets.add("warehouse_datasource_id = ?");
            args.add(datasourceId);
            sets.add("doris_connection_id = ?");
            args.add(connectionId);
        }
        boolean devPresent = body.containsKey("devDatabase");
        boolean prodPresent = body.containsKey("prodDatabase");
        if (devPresent || prodPresent) {
            if (devPresent != prodPresent || body.get("devDatabase") == null
                    || body.get("prodDatabase") == null) {
                throw ApiException.badRequest(
                        "devDatabase 与 prodDatabase 必须成对提供且非空（不支持清空，不改就都不传）");
            }
            sets.add("dev_database = ?");
            args.add(dbName(asString(body.get("devDatabase"), "devDatabase"), "dev 库名"));
            sets.add("prod_database = ?");
            args.add(dbName(asString(body.get("prodDatabase"), "prodDatabase"), "prod 库名"));
        }
        if (sets.isEmpty()) {
            throw ApiException.badRequest("没有要修改的字段（请求里出现的字段才会更新）");
        }
        String sql = """
                update platform.project
                   set %s
                 where id = ? and tenant_id = ?
                """.formatted(String.join(",\n       ", sets));
        args.add(id);
        args.add(com.lab.dataworks.auth.TenantScope.current());
        // v448 口径：校验与写入同一事务；UPDATE 带 tenant_id，n=0 即跨租户/不存在。
        return tx.execute(status -> {
            find(id);
            int n = jdbc.update(sql, args.toArray());
            if (n == 0) {
                throw ApiException.notFound("项目不存在: " + id);
            }
            sessions.audit(username(http), "PROJECT_UPDATE", "p" + id,
                    String.join(",", body.keySet().stream().sorted().toList()));
            return find(id);
        });
    }

    private static String asString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw ApiException.badRequest(field + " 必须是字符串或 null，收到: " + value);
    }

    private static Long asLong(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw ApiException.badRequest(field + " 必须是数字或 null，收到: " + value);
    }

    // ---------------------------------------------------------------- 成员（D42）

    /** 一条项目成员；role 只允许 OWNER / ADMIN。 */
    public record MemberEntry(String username, String role) {
    }

    /**
     * 项目成员全量替换（D42）。
     *
     * @param members   全量集合，必须至少一个 {@code OWNER}，否则 400（否则项目将无人可管理）
     * @param ownerUser 可选：换负责人。必须是 {@code members} 里 role=OWNER 的那个人；
     *                  {@code platform.project.owner_user} 仍是负责人主字段，
     *                  成员表回答的是「谁能管理这个项目」
     */
    public record MembersRequest(List<MemberEntry> members, String ownerUser) {
    }

    @GetMapping("/{id}/members")
    @RequireAnyAtom({"project:read", "system:manage"})
    public Map<String, Object> members(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        List<Map<String, Object>> list = jdbc.queryForList("""
                select username, role, created_at
                  from platform.project_member
                 where project_id = ?
                 order by role, username
                """, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", id);
        out.put("ownerUser", row.get("owner_user"));
        out.put("members", list);
        out.put("manageable", canManageMembers(row, username(http), platformAdmin(http)));
        return out;
    }

    /**
     * 配置项目负责人 / 管理员。
     *
     * <p>权限：只有**现有负责人**或平台管理员可以改（D42 卡的口径：
     * 「谁能管成员」不再下放给项目管理员，避免 ADMIN 把自己以外的人都踢掉）。</p>
     */
    @PutMapping("/{id}/members")
    @RequireAnyAtom({"project:manage", "system:manage"})
    public Map<String, Object> updateMembers(@PathVariable long id,
                                             @RequestBody MembersRequest req,
                                             HttpServletRequest http) {
        if (req == null || req.members() == null) {
            throw ApiException.badRequest("members 不能为空");
        }
        Map<String, Object> row = find(id);
        String actor = username(http);
        boolean admin = platformAdmin(http);
        String currentOwner = row.get("owner_user") == null ? null : String.valueOf(row.get("owner_user"));
        if (!canManageMembers(row, actor, admin)) {
            throw new ApiException(403, "只有项目负责人或平台管理员可以配置项目成员");
        }
        // 归一化 + 校验：用户名非空、角色枚举、同一人不得出现两次、用户必须存在且启用
        List<MemberEntry> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean hasOwner = false;
        for (MemberEntry entry : req.members()) {
            if (entry == null || entry.username() == null || entry.username().isBlank()) {
                throw ApiException.badRequest("成员用户名不能为空");
            }
            String usernameValue = entry.username().trim();
            String role = entry.role() == null ? "" : entry.role().trim().toUpperCase(Locale.ROOT);
            if (!MEMBER_ROLES.contains(role)) {
                throw ApiException.badRequest("成员角色只能是 OWNER / ADMIN，收到: " + entry.role());
            }
            if (!seen.add(usernameValue)) {
                throw ApiException.badRequest("成员重复: " + usernameValue);
            }
            requireActiveUser(usernameValue);
            if ("OWNER".equals(role)) {
                hasOwner = true;
            }
            normalized.add(new MemberEntry(usernameValue, role));
        }
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("项目至少要有一个成员（OWNER）");
        }
        if (!hasOwner) {
            throw ApiException.badRequest("必须保留至少一个 OWNER（负责人），否则项目将无人可管理");
        }
        String newOwner = currentOwner;
        if (req.ownerUser() != null && !req.ownerUser().isBlank()) {
            String wanted = req.ownerUser().trim();
            boolean ownerInList = normalized.stream()
                    .anyMatch(m -> m.username().equals(wanted) && "OWNER".equals(m.role()));
            if (!ownerInList) {
                throw ApiException.badRequest("新负责人必须在成员列表里且角色为 OWNER: " + wanted);
            }
            newOwner = wanted;
        }
        final String fromOwner = currentOwner;
        final String toOwner = newOwner;
        List<Map<String, Object>> before = jdbc.queryForList(
                "select username, role from platform.project_member where project_id = ?", id);
        return tx.execute(status -> {
            find(id);
            jdbc.update("delete from platform.project_member where project_id = ?", id);
            for (MemberEntry entry : normalized) {
                jdbc.update("""
                        insert into platform.project_member (project_id, username, role)
                        values (?, ?, ?)
                        on conflict (project_id, username) do update set role = excluded.role
                        """, id, entry.username(), entry.role());
            }
            if (toOwner != null && !toOwner.equals(fromOwner)) {
                int n = jdbc.update("update platform.project set owner_user = ?"
                                + " where id = ? and tenant_id = ?", toOwner, id,
                        com.lab.dataworks.auth.TenantScope.current());
                if (n == 0) {
                    throw ApiException.notFound("项目不存在: " + id);
                }
                sessions.audit(actor, "PROJECT_OWNER_CHANGE", "p" + id,
                        "ownerUser: " + dash(fromOwner) + " → " + dash(toOwner));
            }
            auditMemberDiff(actor, before, normalized);
            return members(id, http);
        });
    }

    /** 「谁能管成员」：现有负责人或平台管理员（不含项目管理员，见方法上的注释）。 */
    private boolean canManageMembers(Map<String, Object> projectRow, String actor, boolean admin) {
        if (admin) {
            return true;
        }
        Object owner = projectRow.get("owner_user");
        return actor != null && owner != null && actor.equals(String.valueOf(owner));
    }

    /**
     * D42 统一口径：项目内「能否管理」= {@code owner_user} ∪
     * {@code project_member(role in OWNER, ADMIN)}。
     * 所有判 owner 的地方都走这里，不允许各写各的。
     */
    private boolean canManageProject(long projectId, String ownerUser, String actor, boolean admin) {
        if (admin) {
            return true;
        }
        if (actor == null) {
            return false;
        }
        if (actor.equals(ownerUser)) {
            return true;
        }
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.project_member
                 where project_id = ? and username = ? and role in ('OWNER', 'ADMIN')
                """, Integer.class, projectId, actor);
        return n != null && n > 0;
    }

    /** 成员变更审计：按「谁被加 / 谁被删 / 谁换了角色」逐条记，含前后值。 */
    private void auditMemberDiff(String actor, List<Map<String, Object>> before,
                                 List<MemberEntry> after) {
        Map<String, String> oldRoles = new LinkedHashMap<>();
        for (Map<String, Object> row : before) {
            oldRoles.put(String.valueOf(row.get("username")), String.valueOf(row.get("role")));
        }
        Map<String, String> newRoles = new LinkedHashMap<>();
        for (MemberEntry entry : after) {
            newRoles.put(entry.username(), entry.role());
        }
        for (Map.Entry<String, String> added : newRoles.entrySet()) {
            String old = oldRoles.get(added.getKey());
            if (old == null) {
                sessions.audit(actor, "PROJECT_MEMBER_ADD", added.getKey(),
                        "role: （无） → " + added.getValue());
            } else if (!old.equals(added.getValue())) {
                sessions.audit(actor, "PROJECT_MEMBER_ADD", added.getKey(),
                        "role: " + old + " → " + added.getValue());
            }
        }
        for (Map.Entry<String, String> old : oldRoles.entrySet()) {
            if (!newRoles.containsKey(old.getKey())) {
                sessions.audit(actor, "PROJECT_MEMBER_REMOVE", old.getKey(),
                        "role: " + old.getValue() + " → （移除）");
            }
        }
    }

    private void requireActiveUser(String usernameValue) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.app_user where username = ? and status = 'ACTIVE'",
                Integer.class, usernameValue);
        if (exists == null || exists == 0) {
            throw ApiException.badRequest("成员不存在或已禁用: " + usernameValue);
        }
    }

    // ---------------------------------------------------------------- 数据安全（v483）

    /**
     * 项目安全策略：返回项目当前值 + 平台默认值 + 最终有效值。
     * 项目不存在或不属于当前租户统一 404；非负责人且无 system:manage 只读（editable=false）。
     */
    @GetMapping("/{id}/security")
    @RequireAnyAtom({"project:read", "system:manage"})
    public Map<String, Object> securityPolicy(@PathVariable long id, HttpServletRequest http) {
        DataGuardService.ProjectSecurity project = security.projectSecurity(id);
        if (project == null) {
            throw ApiException.notFound("项目不存在: " + id);
        }
        return securityView(project, username(http), platformAdmin(http));
    }

    /**
     * 修改项目安全策略（项目 owner 或 system:manage）。
     *
     * <p>securityMode 为 null 表示保持原值；defaultColumnLevel 为 null 表示继承平台；
     * columnControlRequired 为 null 表示继承。设为 OFF 必须二次确认并写 [高风险] 审计。</p>
     */
    @PutMapping("/{id}/security")
    @RequireAnyAtom({"project:read", "system:manage"})
    public Map<String, Object> updateSecurity(@PathVariable long id,
                                              @RequestBody SecurityRequest req,
                                              HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        DataGuardService.ProjectSecurity before = security.projectSecurity(id);
        if (before == null) {
            throw ApiException.notFound("项目不存在: " + id);
        }
        String actor = username(http);
        boolean admin = platformAdmin(http);
        // D42：判定统一走 canManageProject（owner_user ∪ project_member(OWNER/ADMIN)），
        // 不再各处自己比 owner_user。
        if (!canManageProject(before.projectId(), before.ownerUser(), actor, admin)) {
            throw new ApiException(403,
                    "只有项目负责人 / 项目管理员或平台管理员（system:manage）可以修改项目数据安全策略");
        }
        String oldMode = before.securityMode() == null
                ? DataGuardService.PROJECT_INHERIT : before.securityMode();
        String newMode = req.securityMode() == null ? oldMode
                : DataGuardService.requireProjectMode(req.securityMode());
        String oldLevel = before.defaultColumnLevel();
        String newLevel = normalizeColumnLevel(req.defaultColumnLevel());
        Boolean oldRequired = before.columnControlRequired();
        Boolean newRequired = req.columnControlRequired();
        // 设为 OFF 必须二次确认；已经是 OFF 时重复提交不要求（脚本恢复步骤不受影响）。
        if (DataGuardService.PERMISSION_OFF.equals(newMode)
                && !DataGuardService.PERMISSION_OFF.equals(oldMode)
                && !confirmed(req.confirm())) {
            throw ApiException.badRequest(
                    "关闭项目数据安全会让该项目查询不再过滤/脱敏，"
                            + "请传 confirm=true 或 confirm=\"CONFIRM\" 二次确认");
        }
        int n = jdbc.update("""
                update platform.project
                   set security_mode = ?, default_column_level = ?, column_control_required = ?
                 where id = ? and tenant_id = ?
                """, newMode, newLevel, newRequired, id,
                com.lab.dataworks.auth.TenantScope.current());
        if (n == 0) {
            throw ApiException.notFound("项目不存在: " + id);
        }
        security.invalidateProjectSecurity(id);
        // 审计必须记录三个字段的「旧值 → 新值」；OFF 单独标 [高风险]。
        String detail = "securityMode: " + oldMode + " → " + newMode
                + "; defaultColumnLevel: " + dash(oldLevel) + " → " + dash(newLevel)
                + "; columnControlRequired: " + dash(oldRequired) + " → " + dash(newRequired);
        if (req.reason() != null && !req.reason().isBlank()) {
            detail = detail + "; reason: " + req.reason().trim();
        }
        if (DataGuardService.PERMISSION_OFF.equals(newMode)) {
            detail = "[高风险] " + detail;
        }
        sessions.audit(actor, "PROJECT_SECURITY_UPDATE", "p" + id, detail,
                "SUCCESS", AuthInterceptor.clientIp(http));
        DataGuardService.ProjectSecurity after = security.projectSecurity(id);
        return securityView(after == null ? before : after, actor, admin);
    }

    // ---------------------------------------------------------------- SQL 守卫开关（V105）

    /** 查看项目 SQL 守卫开关（只读）。 */
    /**
     * 项目 SQL 守卫当前值（D43：后端早就有这个开关，界面一直没有入口）。
     *
     * <p>同时回 {@code canManage}：开关是**平台级**动作（{@code system:manage} 且默认租户），
     * 租户管理员不能自己关掉本租户的破坏性语句审批。前端据此决定只读还是可编辑，
     * 并把「为什么不能改」显示出来，而不是让用户点了才收到 403。</p>
     */
    @GetMapping("/{id}/sql-guard")
    @RequireAtom("project:read")
    public Map<String, Object> sqlGuard(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> project = find(id);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("projectId", id);
        // fail-safe：列缺失/为 null 时按开启展示，与 ChangeService.guardEnabled 一致
        Object raw = project.get("sql_guard_enabled");
        out.put("sqlGuardEnabled", raw == null || Boolean.TRUE.equals(raw)
                || "true".equalsIgnoreCase(String.valueOf(raw)));
        out.put("canManage", platformAdmin(http));
        return out;
    }

    /**
     * 开关项目 SQL 守卫（V105 / 007 第十节第 5 条：管理员操作，落审计）。
     *
     * <p>这是平台级动作（{@code system:manage}，仅默认租户）：一个租户的管理员
     * 关掉守卫等于让自己的破坏性语句不经审批，不能由租户自己决定。</p>
     */
    @PutMapping("/{id}/sql-guard")
    @RequireAtom("system:manage")
    public Map<String, Object> updateSqlGuard(@PathVariable long id,
                                              @RequestBody(required = false) SqlGuardRequest req,
                                              HttpServletRequest http) {
        if (req == null || req.enabled() == null) {
            throw ApiException.badRequest("enabled 不能为空");
        }
        boolean enabled = req.enabled();
        Map<String, Object> project = find(id);
        Object before = project.get("sql_guard_enabled");
        int n = jdbc.update("""
                update platform.project
                   set sql_guard_enabled = ?
                 where id = ? and tenant_id = ?
                """, enabled, id, com.lab.dataworks.auth.TenantScope.current());
        if (n == 0) {
            throw ApiException.notFound("项目不存在: " + id);
        }
        String detail = "sqlGuardEnabled: " + (before == null ? "true" : before)
                + " → " + enabled;
        if (req.reason() != null && !req.reason().isBlank()) {
            detail = detail + "; reason: " + req.reason().trim();
        }
        if (!enabled) {
            detail = "[高风险] " + detail;
        }
        sessions.audit(username(http), enabled ? "SQL_GUARD_ENABLE" : "SQL_GUARD_DISABLE",
                "p" + id, detail, "SUCCESS", AuthInterceptor.clientIp(http));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("projectId", id);
        out.put("sqlGuardEnabled", enabled);
        return out;
    }

    private Map<String, Object> securityView(DataGuardService.ProjectSecurity project,
                                             String user, boolean admin) {
        DataGuardService.Policy policy = security.policy(project.projectId());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("projectId", project.projectId());
        out.put("ownerUser", project.ownerUser());
        out.put("securityMode", project.securityMode() == null
                ? DataGuardService.PROJECT_INHERIT : project.securityMode());
        out.put("defaultColumnLevel", project.defaultColumnLevel());
        out.put("columnControlRequired", project.columnControlRequired());
        // 最终生效值 + 来源（前端据此显示「继承自平台」或「项目已覆盖」）
        out.put("effectiveMode", policy.effectiveMode());
        out.put("effectiveSource", policy.source());
        out.put("effectiveDefaultColumnLevel",
                DataGuardService.resolveDefaultColumnLevel(project.defaultColumnLevel()));
        out.put("editable", canManageProject(project.projectId(), project.ownerUser(), user, admin));
        Map<String, Object> platform = new java.util.LinkedHashMap<>();
        platform.put("mode", policy.platformMode());
        platform.put("enforce", policy.enforce());
        platform.put("defaultColumnLevel", DataGuardService.platformDefaultColumnLevel());
        out.put("platform", platform);
        Map<String, Object> tenant = new java.util.LinkedHashMap<>();
        tenant.put("mode", policy.tenantMode());
        out.put("tenant", tenant);
        out.put("modes", List.of(
                Map.of("code", DataGuardService.PROJECT_INHERIT, "name", "继承平台"),
                Map.of("code", DataGuardService.PERMISSION_OFF, "name", "关闭", "danger", true),
                Map.of("code", DataGuardService.PERMISSION_TABLE, "name", "表级"),
                Map.of("code", DataGuardService.PERMISSION_COLUMN, "name", "字段级")));
        out.put("levels", List.of("L1", "L2", "L3", "L4"));
        return out;
    }

    private static String normalizeColumnLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("L1", "L2", "L3", "L4").contains(value)) {
            throw ApiException.badRequest("defaultColumnLevel 只能是 L1 / L2 / L3 / L4");
        }
        return value;
    }

    /** 二次确认：true 或字符串 "CONFIRM"（大小写不敏感）。 */
    private static boolean confirmed(Object confirm) {
        if (confirm instanceof Boolean b) {
            return b;
        }
        return confirm != null && "CONFIRM".equalsIgnoreCase(String.valueOf(confirm).trim());
    }

    private static String dash(Object value) {
        return value == null ? "（继承）" : String.valueOf(value);
    }

    /** system:manage 只对平台（默认租户）生效，与拦截器的平台级权限收口保持一致。 */
    private static boolean platformAdmin(HttpServletRequest request) {
        return AtomCheck.has(request, "system:manage")
                && com.lab.dataworks.auth.TenantScope.DEFAULT.equalsIgnoreCase(
                        com.lab.dataworks.auth.TenantScope.current());
    }

    @DeleteMapping("/{id}")
    @RequireAtom("project:manage")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> project = find(id);
        // S13：删项目原来也是「先做后记」，中间任何一步（内置数仓转挂、图表引用检查 409、
        // 工作流清理、撤纳管）抛异常就一条审计都不留。改成先记后做 + 失败回填 FAILED。
        String auditDetail = String.valueOf(project.get("name"))
                + (project.get("dev_database") == null ? ""
                : "；Doris 库: " + project.get("dev_database")
                + ", " + project.get("prod_database") + "（空库随删除清理，有数据一律保留）");
        return sessions.auditAround(username(http), "PROJECT_DELETE",
                String.valueOf(project.get("code")), auditDetail,
                AuthInterceptor.clientIp(http), () -> doDelete(id, project));
    }

    private Map<String, Object> doDelete(long id, Map<String, Object> project) {
        // D57：删项目时顺带清理「空壳库」，但必须在项目行还在的时候解析数仓连接
        // （connectionIdForProject 读的就是这一行），也必须在删行之后再判引用。
        Long projectConnection = warehouseResolver.connectionIdForProject(id);
        // 内置 Doris 数据源由迁移挂在最早的项目上：删这个项目前先转挂到别的项目，
        // 否则 project -> datasource 的级联删除会把平台内置数仓一起删掉。
        jdbc.update("""
                update platform.datasource d
                   set project_id = (select min(p.id) from platform.project p where p.id <> ?)
                 where d.project_id = ?
                   and coalesce(d.config_json ->> 'builtin', 'false') = 'true'
                   and exists (select 1 from platform.project p where p.id <> ?)
                """, id, id, id);
        // D06：项目下的数据源被看板图表引用时，删除会撞外键只回通用 409，用户看不出原因。
        // 这里提前查出来，给数量和下一步动作。
        Long charts = jdbc.queryForObject("""
                select count(*) from platform.bi_chart c
                  join platform.datasource d on d.id = c.datasource_id
                 where d.project_id = ?
                """, Long.class, id);
        if (charts != null && charts > 0) {
            throw ApiException.conflict("项目下有 " + charts
                    + " 个看板图表引用本项目的数源，请先在数据可视化里删除或改绑图表");
        }

        // 子工作流的锁与 Prefect 资产清理统一由 WorkflowMutationService 负责
        mutations.deleteProject(id);
        // 纳管关系先撤掉，否则权限申请里会留下无主资源
        if (project.get("dev_database") != null) {
            jdbc.update("""
                    delete from platform.managed_table
                     where catalog = 'DORIS' and table_name = '*' and schema_name in (?, ?)
                    """, project.get("dev_database"), project.get("prod_database"));
        }
        // D57 第 4 条：删项目要清理对应空库。保守口径——只删「0 张表且没有别的项目引用」的库，
        // 库里还有数据一律保留并把原因带回响应（以前是全部保留，空壳库因此只增不减）。
        List<Map<String, Object>> cleanup;
        if (environment == null) {
            cleanup = List.of(Map.of("action", "KEPT",
                    "reason", "环境服务未注入，库一律保留（不静默删）"));
        } else {
            cleanup = environment.dropEmptyDatabases(projectConnection,
                    List.of(String.valueOf(project.get("dev_database") == null ? ""
                                    : project.get("dev_database")),
                            String.valueOf(project.get("prod_database") == null ? ""
                                    : project.get("prod_database"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("databaseCleanup", cleanup);
        // 审计由 delete() 的 auditAround 统一回填（先记 PENDING → 这里成功即 SUCCESS）
        return out;
    }

    // V98：项目不再读取 storage_* / hdfs_connection_id / s3_connection_id（列保留但 DEPRECATED），
    // 项目只关心仓库数据源与 DEV/PROD 库；存储由数据源（数仓连接）自己维护。
    private static final String SELECT = """
            select p.id, p.code, p.name, p.description, p.created_at, p.owner_user,
                   p.department_id, p.business, p.dev_database, p.prod_database, p.table_prefix,
                   p.sql_guard_enabled, p.storage_type,
                   p.warehouse_datasource_id, p.doris_connection_id,
                   wd.name as warehouse_datasource_name, wd.ds_type as warehouse_datasource_type,
                   wd.jdbc_url as warehouse_datasource_jdbc_url,
                   d.code as department_code, d.dept_id as department_dept_id,
                   d.name as department_name,
                   (select count(*) from platform.workflow w where w.project_id = p.id) as workflow_count
            from platform.project p
            left join platform.department d on d.id = p.department_id
            left join platform.datasource wd on wd.id = p.warehouse_datasource_id
            """;

    /** 连接必须属于当前租户，且类型匹配。 */
    private void requireConnection(Long id, String type) {
        if (id == null) {
            return;
        }
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.warehouse_connection
                 where id = ? and tenant_id = ? and type = ?
                """, Integer.class, id, com.lab.dataworks.auth.TenantScope.current(), type);
        if (n == null || n == 0) {
            throw ApiException.badRequest("连接不存在或类型不匹配：" + id + " / " + type);
        }
    }

    private static void setNullableLong(java.sql.PreparedStatement ps, int idx, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, java.sql.Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT + " where p.id = ? and p.tenant_id = ?", id,
                com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("项目不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> findDepartment(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select code, dept_id, name, table_prefix from platform.department where id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("部门不存在: " + id);
        }
        return rows.get(0);
    }

    /** 编码 / 库名先在平台侧查重，避免建完库才发现编码冲突。 */
    private void ensureProjectUnused(String code, String devDatabase, String prodDatabase) {
        // 编码只在租户内唯一：不同租户可以各有一个同名项目，也不能泄露别的租户用了什么编码
        Integer sameCode = jdbc.queryForObject(
                "select count(*) from platform.project where code = ? and tenant_id = ?",
                Integer.class, code, com.lab.dataworks.auth.TenantScope.current());
        if (sameCode != null && sameCode > 0) {
            throw ApiException.conflict("项目编码已存在: " + code);
        }
        if (devDatabase != null) {
            Integer sameDb = jdbc.queryForObject("""
                    select count(*) from platform.project
                     where dev_database in (?, ?) or prod_database in (?, ?)
                    """, Integer.class, devDatabase, prodDatabase, devDatabase, prodDatabase);
            if (sameDb != null && sameDb > 0) {
                throw ApiException.conflict("Doris 库已被其他项目占用: " + devDatabase + " / " + prodDatabase);
            }
        }
    }

    /** 负责人默认创建者；必须是启用中的平台账号。 */
    private String resolveOwner(String raw, String creator) {
        String owner = raw == null || raw.isBlank() ? creator : raw.trim();
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.app_user where username = ? and status = 'ACTIVE'",
                Integer.class, owner);
        if (exists == null || exists == 0) {
            throw ApiException.badRequest("负责人不存在或已禁用: " + owner);
        }
        return owner;
    }

    private String ownerTenant(String owner) {
        List<String> rows = jdbc.queryForList(
                "select tenant from platform.app_user where username = ?", String.class, owner);
        return rows.isEmpty() || rows.get(0) == null ? "default" : rows.get(0);
    }

    /**
     * 库级资源用 table_name='*' 登记：负责人拥有全部权限，
     * 其他人可以在「权限申请」里申请整库的读取/写入。
     */
    private void registerDatabase(String tenant, String database, String owner, String description) {
        jdbc.update("""
                insert into platform.managed_table
                    (tenant, catalog, schema_name, table_name, owner_user, description)
                values (?, 'DORIS', ?, '*', ?, ?)
                on conflict (tenant, catalog, schema_name, table_name)
                do update set owner_user = excluded.owner_user, description = excluded.description
                """, tenant, database, owner, description);
    }

    /**
     * REQ-DW-1 ①：自动生成库名前缀。默认规则 = 部门编码+部门ID 拼接（小写字母数字），
     * 候选依次试 base、base+1、base+2…，编码（租户内）或 Doris 库名（平台级，D57）
     * 任一被占就顺延下一个，skipped 记避让次数供表单提交前提示。
     * 部门编码/ID 由 DepartmentController 校验为字母开头小写字母数字，拼出来必过 BUSINESS 正则。
     */
    private AutoBusiness autoBusiness(Map<String, Object> dept) {
        String base = (String.valueOf(dept.get("code")) + String.valueOf(dept.get("dept_id")))
                .toLowerCase().replaceAll("[^a-z0-9]", "");
        if (base.isEmpty() || !base.matches("[a-z][a-z0-9]*")) {
            throw ApiException.badRequest("部门编码+部门ID 推不出合法库名前缀（需字母开头）: " + base);
        }
        for (int n = 0; n < 1000; n++) {
            String candidate = n == 0 ? base : base + n;
            if (candidate.length() > 32) {
                break;
            }
            if (BUSINESS.matcher(candidate).matches() && businessAvailable(candidate, dept)) {
                return new AutoBusiness(candidate, n);
            }
        }
        throw ApiException.conflict("部门 " + base + " 的库名前缀已用尽（1000 个候选全部冲突），请换部门挂载");
    }

    /** 候选前缀是否可用：编码在租户内未占用，且 `<前缀>_dev/_prd` 未被平台内任何项目占用。 */
    private boolean businessAvailable(String business, Map<String, Object> dept) {
        String code = dept.get("code") + "_" + dept.get("dept_id") + "_" + business;
        Integer sameCode = jdbc.queryForObject(
                "select count(*) from platform.project where code = ? and tenant_id = ?",
                Integer.class, code, com.lab.dataworks.auth.TenantScope.current());
        if (sameCode != null && sameCode > 0) {
            return false;
        }
        Integer sameDb = jdbc.queryForObject("""
                select count(*) from platform.project
                 where dev_database in (?, ?) or prod_database in (?, ?)
                """, Integer.class,
                business + "_dev", business + "_prd", business + "_dev", business + "_prd");
        return sameDb == null || sameDb == 0;
    }

    /** autoBusiness 结果：选定的库名前缀 + 避让次数。 */
    record AutoBusiness(String business, int skipped) {
    }

    private String requireBusiness(String raw) {
        String business = raw == null ? "" : raw.trim().toLowerCase();
        if (!BUSINESS.matcher(business).matches()) {
            throw ApiException.badRequest("业务英文名 2-32 位，小写字母开头，只含小写字母和数字，例如 gongxiang");
        }
        return business;
    }

    private static String dbName(String value, String label) {
        if (value == null || !value.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")) {
            throw ApiException.badRequest(label + "不合法（字母或下划线开头，只含字母/数字/下划线）: " + value);
        }
        return value;
    }

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("项目名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw ApiException.badRequest("项目名称不能超过 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }
}
