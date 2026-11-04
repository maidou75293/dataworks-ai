package com.lab.dataworks.access;

import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.RequireAnyAtom;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 库 / 表 / 资产目录权限申请：任何登录用户都能申请库、表、列或多模态资产目录
 * （{@code ASSET_FOLDER}，V112/S11-2）的读/写，由资源负责人或平台管理员审批；
 * 通过的申请即视为授权，用于界面展示与审计。目录授权同时物化到
 * {@code platform.asset_folder_grant}（source='ACCESS'）供 asset 侧判定生效。
 */
@RestController
@RequestMapping("/api/access")
public class AccessController {

    /** V112：ASSET_FOLDER＝多模态资产目录内容权（S11 的 403「请提交权限申请」由此闭环）。 */
    private static final Set<String> RESOURCE_TYPES =
            Set.of("DATABASE", "TABLE", "COLUMN", "ASSET_FOLDER");
    /** ASSET_FOLDER 申请没有库表定位概念；catalog 固定占位，真键是 folder_id，schema_name 存 path 快照。 */
    static final String FOLDER_CATALOG = "ASSET";
    /** 授权有效期（天）：到期自动失效，避免转岗/离职后权限永久保留。 */
    private static final int GRANT_DAYS = 90;
    /** L4 机密资源：双人审批 + 更短有效期。 */
    private static final int L4_GRANT_DAYS = 30;
    private static final Set<String> PERMISSIONS = Set.of("READ", "WRITE");

    /**
     * D61：申请理由上限（**500 字**）。
     *
     * <p>口径来源：前端 {@code Access.tsx} 的申请理由与续期说明<b>早就</b>是
     * {@code maxLength={500}}，缺的是后端——直连 API 可以把 5000 字原样写进
     * {@code platform.access_request.reason}（列类型 text，无界），审批列表/详情页被撑爆。
     * 后端补齐同一个数字，前后端不再两套口径，也不需要迁历史数据。</p>
     *
     * <p>按**字（code point）**计数，不按 UTF-16 长度：一个 emoji／生僻字（代理对）
     * 算一个字，否则同样内容在 JS 侧 500、Java 侧 250，又是一套错位口径。</p>
     */
    static final int MAX_REASON_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final PermissionResolver permissions;
    private final TransactionTemplate tx;
    /** 到期提醒（D52）：判定与落库都在 {@link AccessExpiryJob} 里，控制器只负责权限与审计。 */
    private final AccessExpiryJob expiryReminders;
    /** TC7-G5 审批超时升级：同 D52 口径，控制器只做权限/审计/手动触发，逻辑在 Job 里。 */
    private final AccessEscalationJob escalations;

    public AccessController(JdbcTemplate jdbc, SessionService sessions,
                            PermissionResolver permissions, TransactionTemplate tx,
                            AccessExpiryJob expiryReminders, AccessEscalationJob escalations) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.permissions = permissions;
        this.tx = tx;
        this.expiryReminders = expiryReminders;
        this.escalations = escalations;
    }

    public record CreateRequest(String resourceType, String catalog, String schemaName,
                                String tableName, String columnName, Long folderId,
                                String permission, String reason) {
    }

    public record Decision(String comment) {
    }

    /** TC7-G5：PUT /approval-policies 请求体。steps 1–2 级；mode ANY=或签、ALL=会签。 */
    public record PolicyBody(String dataLevel, List<StepBody> steps, Integer timeoutDays,
                             List<String> escalateTo, Boolean enabled) {
    }

    /** 一级审批节点定义（name 可空，默认按模式取「或签/会签」）。 */
    public record StepBody(String name, String mode, List<String> approvers) {
    }

    /** 审批状态机的一步：下一步状态、事件名、是否写 expires_at、有效期天数。 */
    record ApprovalStep(String status, String event, boolean setsExpiry, int grantDays) {
    }

    /** 提交申请的结果：id 是落库主键，created=false 表示命中去重、复用了已有 PENDING 行。 */
    private record CreateResult(Long id, boolean created) {
    }

    /** 可申请资源：库（schema）、表，以及 V112 起的多模态资产目录（folders），带「我的权限 / 审批中」状态。 */
    @GetMapping("/resources")
    @RequireAnyAtom({"access:request", "access:approve", "access:revoke", "user:manage"})
    public Map<String, Object> resources(HttpServletRequest http) {
        String user = user(http);
        String tenant = tenantOf(user);
        boolean staff = platformStaff(http);
        // v460 F-P2-5：展示口径与执行口径一致——授权按 requester_tenant 过滤；
        // F-P2-3：历史 expires_at 为空按「决定时间 + 90 天」算，不再永不过期。
        List<Map<String, Object>> grants = jdbc.queryForList("""
                select resource_type, catalog, schema_name, table_name, folder_id, permission, status
                from platform.access_request
                where requester = ? and status in ('APPROVED', 'PENDING')
                  and (requester_tenant = ? or requester_tenant is null)
                  and (status <> 'APPROVED'
                       or coalesce(expires_at, decided_at + interval '90 days') > now())
                """, user, TenantScope.current());

        // table_name='*' 是库级资源（项目新建时登记），不是真表，列表里排除
        // v447：可申请资源同样按租户过滤（原来把全平台纳管表都列出来）
        List<Map<String, Object>> tables = jdbc.queryForList("""
                select id, tenant, catalog, schema_name as "schemaName",
                       table_name as "tableName", owner_user as "ownerUser", description
                from platform.managed_table
                where table_name <> '*' and (tenant = ? or ?)
                order by tenant, schema_name, table_name
                """, TenantScope.current(), staff);
        for (Map<String, Object> row : tables) {
            String catalog = str(row.get("catalogName"));
            if (catalog == null) catalog = str(row.get("catalog"));
            String schema = str(row.get("schemaName"));
            String table = str(row.get("tableName"));
            boolean owner = user.equals(str(row.get("ownerUser")));
            row.put("level", levelOf(grants, catalog, schema, table, true, owner));
            row.put("pending", pendingOf(grants, catalog, schema, table, true));
            row.put("owner", owner);
            row.put("mine", tenant.equals(str(row.get("tenant"))));
        }

        List<Map<String, Object>> databases = jdbc.queryForList("""
                select tenant, catalog, schema_name as "schemaName",
                       min(owner_user) as "ownerUser",
                       count(*) filter (where table_name <> '*')::int as "tableCount",
                       bool_or(owner_user = ?) as "ownerAny",
                       bool_or(table_name = '*' and owner_user = ?) as "ownerExplicit",
                       count(distinct owner_user) as "ownerKinds"
                from platform.managed_table
                where (tenant = ? or ?)
                group by tenant, catalog, schema_name
                order by tenant, schema_name
                """, user, user, TenantScope.current(), staff);
        for (Map<String, Object> row : databases) {
            String catalog = str(row.get("catalog"));
            String schema = str(row.get("schemaName"));
            // v460 F-P2-6：单表负责人不再被展示成库负责人
            boolean ownerAny = Boolean.TRUE.equals(row.get("ownerAny"));
            boolean ownerExplicit = Boolean.TRUE.equals(row.get("ownerExplicit"));
            int ownerKinds = row.get("ownerKinds") instanceof Number n ? n.intValue() : 0;
            boolean owner = ownerExplicit || (ownerAny && ownerKinds == 1);
            row.put("level", levelOf(grants, catalog, schema, null, false, owner));
            row.put("pending", pendingOf(grants, catalog, schema, null, false));
            row.put("mine", tenant.equals(str(row.get("tenant"))));
        }

        // V112：本租户资产目录清单，供「申请目录内容权」。level 只统计**通过申请**
        // （access_request）拿到的权限；目录管理页手工配置（MANUAL）或默认开放的目录
        // 可能显示 NONE，但实际可读写——那是 asset 侧授权，不欠申请。
        List<Map<String, Object>> folders = jdbc.queryForList("""
                select f.id, f.name, f.path, f.created_by as "createdBy",
                       (select count(*) from platform.asset_folder_grant g
                         where g.folder_id = f.id)::int as "grantCount"
                  from platform.asset_folder f
                 where f.tenant_id = ?
                 order by f.path
                """, TenantScope.current());
        for (Map<String, Object> row : folders) {
            Long fid = longOf(row.get("id"));
            boolean owner = user.equalsIgnoreCase(str(row.get("createdBy")));
            row.put("level", owner ? "WRITE" : folderLevelOf(grants, fid));
            row.put("pending", folderPendingOf(grants, fid));
            row.put("owner", owner);
            row.put("mine", true);
            row.put("openDefault", ((Number) row.get("grantCount")).intValue() == 0);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant);
        out.put("admin", admin(http));
        out.put("databases", databases);
        out.put("tables", tables);
        out.put("folders", folders);
        return out;
    }

    /** 目录「我的权限」：仅看本租户 + 通过申请（APPROVED 未过期）拿到的读写；负责人直接 WRITE。 */
    private String folderLevelOf(List<Map<String, Object>> grants, Long folderId) {
        if (folderId == null) return "NONE";
        boolean read = false;
        for (Map<String, Object> grant : grants) {
            if (!"APPROVED".equals(str(grant.get("status")))) continue;
            if (!folderId.equals(longOf(grant.get("folder_id")))) continue;
            if (!"ASSET_FOLDER".equals(str(grant.get("resource_type")))) continue;
            if ("WRITE".equals(str(grant.get("permission")))) return "WRITE";
            read = true;
        }
        return read ? "READ" : "NONE";
    }

    private boolean folderPendingOf(List<Map<String, Object>> grants, Long folderId) {
        if (folderId == null) return false;
        for (Map<String, Object> grant : grants) {
            if (!"PENDING".equals(str(grant.get("status")))) continue;
            if (!folderId.equals(longOf(grant.get("folder_id")))) continue;
            if ("ASSET_FOLDER".equals(str(grant.get("resource_type")))) return true;
        }
        return false;
    }

    /**
     * 某张纳管表的字段清单，供「按列申请」下拉选择。
     *
     * <p>可见范围与 /resources 保持一致：本租户资源，或平台 staff。真正提交时
     * 后端还会再校验列确实属于该表的 managed_column。</p>
     */
    @GetMapping("/columns")
    @RequireAnyAtom({"access:request", "access:approve", "access:revoke", "user:manage"})
    public List<Map<String, Object>> columns(@RequestParam String catalog,
                                             @RequestParam String schemaName,
                                             @RequestParam String tableName,
                                             HttpServletRequest http) {
        String c = requireText(catalog, "catalog 不能为空");
        String s = requireText(schemaName, "库名不能为空");
        String t = requireText(tableName, "表名不能为空");
        return jdbc.queryForList("""
                select c.column_name as "columnName", c.data_level as "dataLevel"
                from platform.managed_column c
                join platform.managed_table t on t.id = c.table_id
                where t.catalog = ? and t.schema_name = ? and t.table_name = ?
                  and (t.tenant = ? or ?)
                order by c.column_name
                """, c, s, t, TenantScope.current(), platformStaff(http));
    }

    /**
     * scope=mine：我的申请；scope=approvals：待我审批（负责人或管理员）；
     * scope=grants：已授权、且我点得动「回收」的单子（BUG-TC6-2）。
     */
    @GetMapping("/requests")
    @RequireAnyAtom({"access:request", "access:approve", "access:revoke", "user:manage"})
    public List<Map<String, Object>> requests(
            @RequestParam(defaultValue = "mine") String scope, HttpServletRequest http) {
        String user = user(http);
        boolean admin = admin(http);
        boolean platformFlag = com.lab.dataworks.common.TenantGuard.platform();
        String current = TenantScope.current();
        List<Map<String, Object>> rows;
        if ("approvals".equalsIgnoreCase(scope)) {
            // v448 P0-2：待审批列表按资源所属租户过滤。之前是全表 PENDING 再逐条判权，
            // tenant_b 能看到 default 租户的申请单（申请人、库表名、理由全暴露）。
            // 可见范围 = 本租户提交的申请 ∪ 资源属于本租户的申请（跨租户申请由资源方负责人处理）；
            // 平台管理员看全量。真正能不能点，仍由 canApprove 按审批人身份判定。
            rows = jdbc.queryForList(baseRequestSql()
                    + " where ar.status in ('PENDING', 'PENDING_SECOND')\n"
                    + tenantVisibleSql() + " order by ar.id desc\n",
                    platformFlag, current, current, current);
            List<Map<String, Object>> mine = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (canApprove(user, admin, row)) mine.add(row);
            }
            return mine;
        }
        if ("grants".equalsIgnoreCase(scope)) {
            // BUG-TC6-2：「已授权」页签是回收按钮的落点。此前 APPROVED 单只可能出现在
            // approvals 查询里，而那条 SQL 写死 PENDING/PENDING_SECOND —— 回收按钮成了死分支。
            // 这里单独查 APPROVED，并用 revoke 动作的同一个判定（canRevoke）收口：
            // 列表里看得见 ⇔ 点下去有权限，不会出现「有按钮但 403」或「能收却看不到」。
            rows = jdbc.queryForList(baseRequestSql()
                    + " where ar.status = 'APPROVED'\n"
                    + tenantVisibleSql() + " order by ar.id desc\n",
                    platformFlag, current, current, current);
            boolean platformAdmin = platformFlag && admin;
            boolean hasRevoke = permissions.atoms(user, current).contains("access:revoke");
            List<Map<String, Object>> grants = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (canRevoke(platformAdmin, hasRevoke, current,
                        str(row.get("ownerTenant")), str(row.get("requesterTenant")))) {
                    grants.add(row);
                }
            }
            return grants;
        }
        return jdbc.queryForList(baseRequestSql() + """
                 where ar.requester = ? order by ar.id desc
                """, user);
    }

    /**
     * 「一条申请对当前租户是否可见」的 SQL 片段（v448 P0-2 口径）：平台视角看全量，
     * 否则 = 本租户提交的申请 ∪ 资源/目录属于本租户的申请。
     * 位置参数顺序固定：platform、tenant、tenant、tenant（approvals 与 grants 共用）。
     */
    private static String tenantVisibleSql() {
        return """
                   and (? or ar.requester_tenant = ?
                        or exists (
                            select 1 from platform.managed_table mt
                             where mt.catalog = ar.catalog
                               and mt.schema_name = ar.schema_name
                               and (ar.resource_type = 'DATABASE'
                                    or mt.table_name = ar.table_name)
                               and mt.tenant = ?)
                        or (ar.resource_type = 'ASSET_FOLDER' and exists (
                                select 1 from platform.asset_folder af
                                 where af.id = ar.folder_id and af.tenant_id = ?)))
                """;
    }

    @PostMapping("/requests")
    @RequireAtom("access:request")
    public Map<String, Object> create(@RequestBody(required = false) CreateRequest req,
                                      HttpServletRequest http) {
        if (req == null) throw ApiException.badRequest("请求体不能为空");
        String user = user(http);
        String type = normalizeResourceType(req.resourceType());
        String permission = upper(req.permission());
        if (!PERMISSIONS.contains(permission)) {
            throw ApiException.badRequest("权限只能是 READ 或 WRITE");
        }
        // D61：入参形状先判（含限长，400 里带当前字数，用户不用自己数），
        // 再去做资源存在性查询——超长理由不该先跑三轮库查询再被拒。
        String reason = requireReasonLength(trim(req.reason()));
        boolean folder = "ASSET_FOLDER".equals(type);
        String catalog;
        String schema;
        String table;
        String column;
        Long folderId;
        if (folder) {
            // 目录内容权只认 folderId；混传库表字段一律 400，不做静默忽略（acceptance-rules 第 4 条）
            rejectForeignFields(req, "folderId");
            if (req.folderId() == null || req.folderId() <= 0) {
                throw ApiException.badRequest("资产目录申请必须提供 folderId（> 0 的目录 id）");
            }
            folderId = req.folderId();
            catalog = FOLDER_CATALOG;
            schema = requireAssetFolder(user, folderId, permission);
            table = null;
            column = null;
        } else {
            folderId = null;
            if (req.folderId() != null) {
                throw ApiException.badRequest("只有资产目录申请可以指定 folderId");
            }
            catalog = requireText(req.catalog(), "catalog 不能为空");
            schema = requireText(req.schemaName(), "库名不能为空");
            boolean needsTable = "TABLE".equals(type) || "COLUMN".equals(type);
            table = needsTable ? requireText(req.tableName(), "表名不能为空") : null;
            column = "COLUMN".equals(type)
                    ? requireText(req.columnName(), "列名不能为空") : null;
            if (!"COLUMN".equals(type) && req.columnName() != null
                    && !req.columnName().isBlank()) {
                throw ApiException.badRequest("只有列级申请可以指定 columnName");
            }

            // 申请是**跨租户**功能：tenant_b 的用户可以申请 default 的库表，由资源负责人审批。
            // 这里只校验「资源存在且已纳管」，不按申请人的租户过滤；审批阶段再按资源租户收口
            // （canApprove）。副作用是可以用 200/404 探测某个库表是否纳管，这与该功能的
            // 历史行为一致，且 /resources 列表仍只列本租户资源。ASSET_FOLDER 的存在性探测
            // 口径与此相同（见 requireAssetFolder）。
            Integer exists = jdbc.queryForObject("""
                    select count(*) from platform.managed_table
                    where catalog = ? and schema_name = ?
                      and (? = 'DATABASE' or table_name = ?)
                    """, Integer.class, catalog, schema, type, table);
            if (exists == null || exists == 0) {
                throw ApiException.notFound("资源不存在或未纳管");
            }
            if ("COLUMN".equals(type)) {
                // 列必须真实存在于该表的 managed_column，不能靠猜列名申请
                Integer columnExists = jdbc.queryForObject("""
                        select count(*) from platform.managed_column c
                        join platform.managed_table t on t.id = c.table_id
                        where t.catalog = ? and t.schema_name = ? and t.table_name = ?
                          and lower(c.column_name) = lower(?)
                        """, Integer.class, catalog, schema, table, column);
                if (columnExists == null || columnExists == 0) {
                    throw ApiException.notFound("字段不存在或未登记: " + column);
                }
            }
            if (ownedBy(user, type, catalog, schema, table)) {
                throw ApiException.badRequest("你是该资源的负责人，已具备权限");
            }
        }
        // v460 F-P1-1：并发同参申请会「先 count 后 insert」各插一条，且 max(id) 会拿到
        // 别人的 id。这里在事务内用 advisory lock 按（租户+申请人+资源+列+权限）串行化，
        // insert ... returning id 取真实主键；审计也用这个 id。
        // v471 F-P1-1：锁键与查重条件补齐 requester_tenant——授权本身按租户隔离，同一账号
        // 属于两个租户时的同参申请是两条独立业务。命中已有 PENDING 时不再 409，而是返回
        // 那一行的 id：并发的 10 个请求（以及重试）拿到的都是同一个申请 id。
        String tenant = TenantScope.current();
        CreateResult result = submitRequest(user, tenant, type, catalog, schema, table, column,
                folderId, permission, reason, false);
        if (result.id() == null) {
            throw new ApiException(500, "提交申请失败：数据库未返回 id");
        }
        Long id = result.id();
        String resourceDesc = folder
                ? type + " 目录 " + schema
                : type + " " + catalog + "." + schema
                        + (table == null ? "" : "." + table)
                        + (column == null ? "" : "." + column);
        sessions.audit(user, "ACCESS_REQUEST_CREATE",
                String.valueOf(id), (result.created() ? "" : "命中去重、复用审批中的申请；")
                        + resourceDesc + " " + permission);
        return requestRow(id, user, platformStaff(http));
    }

    /**
     * 落一条申请的公共实现（新建与续期共用，D52）：advisory lock 串行化同参并发，
     * 命中已有 PENDING 复用那一行，避免「并发 10 个请求插出 10 条申请」。
     *
     * @param renew 续期时<em>不</em>挡「已有生效授权」——那条授权正是本次要接上的对象；
     *              新建时仍挡（审计 F-2：叠多条会让回收一条另一条仍生效）
     */
    private CreateResult submitRequest(String user, String tenant, String type, String catalog,
                                       String schema, String table, String column, Long folderId,
                                       String permission, String reason, boolean renew) {
        // D61：这里是申请落库的**唯一**写入口（新建与续期共用），限长放在入口处兜底：
        // 以后再有新调用方（批量导入、助手代提）也带不上超限理由，不会悄悄写进 text 列。
        requireReasonLength(reason);
        boolean folder = "ASSET_FOLDER".equals(type);
        String lockKey = String.join("|", "access", tenant, user, type, catalog, schema,
                table == null ? "" : table, column == null ? "" : column,
                folderId == null ? "" : String.valueOf(folderId), permission);
        // 去重谓词按资源类型分支：目录申请的真键是 folder_id——path 只作展示快照，
        // 目录改名后老申请行的 schema_name 是旧 path，若仍按 catalog+schema 等值匹配
        // 会查不到既有授权、让同目录重复申请穿透（V112）。
        String resourceMatch;
        Object[] resourceArgs;
        if (folder) {
            resourceMatch = " and resource_type = 'ASSET_FOLDER' and folder_id = ? ";
            resourceArgs = new Object[]{folderId};
        } else {
            resourceMatch = """
                     and resource_type = ? and catalog = ? and schema_name = ?
                     and coalesce(table_name, '') = coalesce(?, '')
                     and lower(coalesce(column_name, '')) = lower(coalesce(?, ''))
                     and folder_id is null
                    """;
            resourceArgs = new Object[]{type, catalog, schema, table, column};
        }
        CreateResult result = tx.execute(status -> {
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, lockKey);
            if (!renew) {
                Integer approved = jdbc.queryForObject("""
                        select count(*) from platform.access_request
                        where requester = ? and requester_tenant = ? and status = 'APPROVED'
                          and coalesce(expires_at, decided_at + interval '90 days') > now()
                        """ + resourceMatch + """
                          and (permission = ? or permission = 'WRITE')
                        """, Integer.class, concat(user, tenant, resourceArgs, permission));
                if (approved != null && approved > 0) {
                    // 已经有可用授权时再申请会叠加多条：回收一条另一条仍生效（审计 F-2）
                    throw ApiException.conflict("你已经有该资源的权限，无需重复申请");
                }
            }
            List<Long> pending = jdbc.queryForList("""
                    select id from platform.access_request
                    where requester = ? and requester_tenant = ?
                      and status in ('PENDING', 'PENDING_SECOND')
                    """ + resourceMatch + """
                      and permission = ?
                    order by id
                    limit 1
                    """, Long.class, concat(user, tenant, resourceArgs, permission));
            if (!pending.isEmpty()) {
                return new CreateResult(pending.get(0), false);
            }
            // TC7-G5：提单时冻结审批链快照（之后改策略不回溯在途单）。
            // 密级判定复用 levelOfRequest（与审批时的旧硬编码同一口径），查不到按 L3 旧行为。
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("resourceType", type);
            probe.put("catalog", catalog);
            probe.put("schemaName", schema);
            probe.put("tableName", table);
            probe.put("columnName", column);
            probe.put("folderId", folderId);
            String policySnapshot = ApprovalPolicy.snapshotFor(jdbc, tenant, levelOfRequest(probe));
            return new CreateResult(jdbc.queryForObject("""
                    insert into platform.access_request
                        (requester, requester_tenant, resource_type, catalog, schema_name,
                         table_name, column_name, folder_id, permission, reason,
                         policy_steps, step_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                    returning id
                    """, Long.class, user, tenant, type, catalog, schema, table, column,
                    folderId, permission, reason, policySnapshot), true);
        });
        return result == null ? new CreateResult(null, false) : result;
    }

    /** resourceType 归一：大小写/空白宽容；枚举外（含空）一律 400 并列出**全部**合法取值，禁止静默降级。 */
    static String normalizeResourceType(String raw) {
        String type = upperStatic(raw);
        if (!RESOURCE_TYPES.contains(type)) {
            throw ApiException.badRequest("资源类型只能是 DATABASE、TABLE、COLUMN 或 ASSET_FOLDER，收到: "
                    + (raw == null || raw.isBlank() ? "（空）" : raw.trim()));
        }
        return type;
    }

    /** ASSET_FOLDER 与其他定位字段互斥：混传一律 400（说清只认哪个），不做「取一半丢一半」。 */
    private static void rejectForeignFields(CreateRequest req, String allowed) {
        List<String> foreign = new ArrayList<>();
        if (req.catalog() != null && !req.catalog().isBlank()) foreign.add("catalog");
        if (req.schemaName() != null && !req.schemaName().isBlank()) foreign.add("schemaName");
        if (req.tableName() != null && !req.tableName().isBlank()) foreign.add("tableName");
        if (req.columnName() != null && !req.columnName().isBlank()) foreign.add("columnName");
        if (!foreign.isEmpty()) {
            throw ApiException.badRequest("资产目录申请只需要 " + allowed
                    + "，请不要传: " + String.join("、", foreign));
        }
    }

    /**
     * 校验目录存在 + 申请人不是目录负责人 + 申请人没有生效授权。
     *
     * <p>存在性不按申请人租户过滤（与库表申请的跨租户探测口径一致）；审批阶段
     * 由 canApprove 按「目录所属租户 + 创建者/管理员」收口。</p>
     *
     * @return schema_name 展示快照（path，超长截断；真键是 folder_id）
     */
    private String requireAssetFolder(String user, long folderId, String permission) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select path, coalesce(created_by, '') as created_by
                  from platform.asset_folder where id = ?
                """, folderId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("资产目录不存在或已删除: " + folderId);
        }
        Map<String, Object> f = rows.get(0);
        if (user.equalsIgnoreCase(str(f.get("created_by")))) {
            throw ApiException.badRequest("你是该资源的负责人，已具备权限");
        }
        // 授权行可能来自目录管理页手工配置（MANUAL）或历史申请（ACCESS）：
        // 已覆盖所申请权限时不再叠加新申请，与「已有 APPROVED 申请查重」同口径。
        List<Map<String, Object>> eff = jdbc.queryForList("""
                select coalesce(bool_or(can_read or can_write), false) as has_read,
                       coalesce(bool_or(can_write), false) as has_write
                  from platform.asset_folder_grant
                 where folder_id = ? and subject_type = 'USER'
                   and lower(subject_id) = lower(?)
                """, folderId, user);
        boolean hasRead = !eff.isEmpty() && Boolean.TRUE.equals(eff.get(0).get("has_read"));
        boolean hasWrite = !eff.isEmpty() && Boolean.TRUE.equals(eff.get(0).get("has_write"));
        // WRITE 蕴含 READ：只有 READ 的人仍可申请 WRITE；已有 WRITE 的人什么都不用再申请
        boolean covered = "WRITE".equals(permission) ? hasWrite : hasRead;
        if (covered) {
            throw ApiException.conflict("你已经有该资源的权限，无需重复申请");
        }
        return AssetFolderGrants.displayPath(str(f.get("path")));
    }

    @PostMapping("/requests/{id}/approve")
    // 资源负责人通常没有 access:approve：入口用「任一」放进来，能不能批由 canApprove
    // 按「资源所属租户 + 负责人/审批员」判定；无权限者仍会 403。
    @RequireAnyAtom({"access:approve", "access:request", "user:manage"})
    public Map<String, Object> approve(@PathVariable long id,
                                       @RequestBody(required = false) Decision decision,
                                       HttpServletRequest http) {
        return decide(id, "APPROVED", decision, http);
    }

    @PostMapping("/requests/{id}/reject")
    @RequireAnyAtom({"access:approve", "access:request", "user:manage"})
    public Map<String, Object> reject(@PathVariable long id,
                                      @RequestBody(required = false) Decision decision,
                                      HttpServletRequest http) {
        return decide(id, "REJECTED", decision, http);
    }

    /** 批量签批请求体（BUG-TC6-14）：ids 逐项处理，comment 口径与单项一致（可空）。 */
    public record BulkDecision(List<Long> ids, String comment) {
    }

    @PostMapping("/requests/bulk-approve")
    @RequireAnyAtom({"access:approve", "access:request", "user:manage"})
    public Map<String, Object> bulkApprove(@RequestBody(required = false) BulkDecision req,
                                           HttpServletRequest http) {
        return bulkDecide(req, "APPROVED", http);
    }

    @PostMapping("/requests/bulk-reject")
    @RequireAnyAtom({"access:approve", "access:request", "user:manage"})
    public Map<String, Object> bulkReject(@RequestBody(required = false) BulkDecision req,
                                          HttpServletRequest http) {
        return bulkDecide(req, "REJECTED", http);
    }

    /**
     * 批量审批（BUG-TC6-14）：审批人一批同类申请逐单点太慢（每单 3 击）。
     *
     * <p>闸门口径（卡内硬要求「批量与单项必须同闸门、同文案、同状态机校验」）：
     * 每一项都原样走单项 {@link #decide} —— 自批 403、跨租户一致、canApprove、
     * L4 二审换人、策略链/会签/或签、并发乐观闸门、事件与双写审计，一条都不绕。
     * 批量端点只多了两件事：入参预检与结果汇总。</p>
     *
     * <ul>
     *   <li>预检（空 / 超 50 / id 非法或重复）在任何一单被处理前整体 400；</li>
     *   <li>预检后逐项执行，单项被闸门拒（403/400/404）不阻断其余项，
     *       逐条回传 {id, ok, status | code, message}——不许静默；</li>
     *   <li>L4/策略二审单在批量「通过」后进入 PENDING_SECOND，仍要换人再批，
     *       批量不会把双人闸门批没（这正是逐项复用 decide 的意义）。</li>
     * </ul>
     */
    private Map<String, Object> bulkDecide(BulkDecision req, String status,
                                           HttpServletRequest http) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw ApiException.badRequest("批量审批需要至少选择一条申请");
        }
        if (req.ids().size() > 50) {
            throw ApiException.badRequest("单次批量审批最多 50 条申请，请分批处理");
        }
        List<Long> ids = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (Long id : req.ids()) {
            if (id == null || id <= 0) {
                throw ApiException.badRequest("申请 id 不合法: " + id);
            }
            if (!seen.add(id)) {
                throw ApiException.badRequest("批量项重复: " + id);
            }
            ids.add(id);
        }
        Decision decision = new Decision(req.comment());
        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        for (Long id : ids) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", id);
            try {
                Map<String, Object> row = decide(id, status, decision, http);
                one.put("ok", true);
                one.put("status", str(row.get("status")));
                succeeded++;
            } catch (ApiException e) {
                one.put("ok", false);
                one.put("code", e.getStatus());
                one.put("message", e.getMessage());
            } catch (RuntimeException e) {
                // 与单项入口对未捕获异常的行为一致（进全局异常面），但批量里不能丢其余项：
                // 记日志 + 显式失败行，绝不静默跳过。
                org.slf4j.LoggerFactory.getLogger(AccessController.class)
                        .warn("批量审批出现未归类异常 id={}", id, e);
                one.put("ok", false);
                one.put("code", 500);
                one.put("message", "处理失败：" + e.getClass().getSimpleName());
            }
            results.add(one);
        }
        boolean approve = "APPROVED".equals(status);
        String actor = user(http);
        sessions.audit(actor,
                approve ? "ACCESS_REQUEST_BULK_APPROVE" : "ACCESS_REQUEST_BULK_REJECT",
                String.valueOf(ids.size()),
                "ids=" + ids + "; ok=" + succeeded + "; failed=" + (ids.size() - succeeded)
                        + (req.comment() == null || req.comment().isBlank()
                           ? "" : "; comment=" + trim(req.comment())),
                succeeded == ids.size() ? "SUCCESS"
                        : (succeeded == 0 ? "FAILED" : "PARTIAL"),
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", ids.size());
        out.put("succeeded", succeeded);
        out.put("failed", ids.size() - succeeded);
        out.put("results", results);
        return out;
    }

    @PostMapping("/requests/{id}/cancel")
    @RequireAtom("access:request")
    public Map<String, Object> cancel(@PathVariable long id, HttpServletRequest http) {
        String user = user(http);
        Map<String, Object> row = requestRow(id, user, platformStaff(http));
        if (!user.equals(str(row.get("requester")))) {
            denied(http, "ACCESS_REQUEST_CANCEL_DENIED", id, "只能撤销自己的申请");
        }
        if (!"PENDING".equals(str(row.get("status")))
                && !"PENDING_SECOND".equals(str(row.get("status")))) {
            throw ApiException.badRequest("只有审批中的申请可以撤销");
        }
        int cancelled = jdbc.update("""
                update platform.access_request
                   set status = 'CANCELLED', decided_at = now()
                 where id = ? and status in ('PENDING', 'PENDING_SECOND')
                """, id);
        if (cancelled == 0) {
            // 并发撤销：另一个请求已经处理过，不能把「已审批」覆盖成「已撤销」
            throw ApiException.badRequest("该申请已经处理过了");
        }
        sessions.audit(user, "ACCESS_REQUEST_CANCEL", String.valueOf(id), null);
        return requestRow(id, user, platformStaff(http));
    }

    /**
     * 回收已通过的表 / 库 / 目录权限（目录回收会同步撤掉物化的 ACCESS 授权行）。
     *
     * <p>v460 F-P1-3：`access:revoke` 必须是硬权限点（以前从不校验，成为死点）。
     * v467 收紧边界：审批/申请权限点不再能回收（回收是独立职责），且必须落在
     * 与本租户相关的申请上——资源属于本租户，或申请由本租户提交，否则 403。</p>
     */
    @PostMapping("/requests/{id}/revoke")
    @RequireAnyAtom({"access:revoke", "user:manage"})
    public Map<String, Object> revoke(@PathVariable long id, HttpServletRequest http) {
        String user = user(http);
        Map<String, Object> row = requestRow(id, user, platformStaff(http));
        String current = TenantScope.current();
        boolean platformAdmin = com.lab.dataworks.common.TenantGuard.platform() && admin(http);
        boolean hasRevoke = permissions.atoms(user, current).contains("access:revoke");
        // 只有 access:revoke（或平台管理员）能回收；且只能回收与本租户相关的授权。
        // 之前 access:approve / access:request / 资源负责人 都能回收，等于审批权绕过回收权。
        // 判定与 scope=grants 列表共用 canRevoke，保证「列表能看见 ⇔ 点得动」。
        if (!canRevoke(platformAdmin, hasRevoke, current,
                str(row.get("ownerTenant")), str(row.get("requesterTenant")))) {
            denied(http, "ACCESS_REQUEST_REVOKE_DENIED", id,
                    "缺少 access:revoke，或该授权不属于当前租户");
        }
        if (!"APPROVED".equals(str(row.get("status")))) {
            throw ApiException.badRequest("只有已通过的授权可以回收");
        }
        // v471 F-P1-2：回收只改「当前状态」字段（status/revoked_by/revoked_at 仍是判权依据），
        // 原审批人/意见/决定时间一个都不动；回收动作本身追加一条 REVOKE 事件，与状态变更
        // 同事务，detail 里固化回收人与回收时间（now() 即同事务的 revoked_at）。
        // V112：目录授权回收 = 状态机置 REVOKED（判权依据仍在 access_request）+
        // 同事务重算 asset_folder_grant 的 ACCESS 行；MANUAL 行不动。
        boolean folderRow = "ASSET_FOLDER".equals(str(row.get("resourceType")));
        Long grantFolderId = folderRow ? longOf(row.get("folderId")) : null;
        String grantSubject = str(row.get("requester"));
        Integer revoked = tx.execute(ignored -> {
            if (grantFolderId != null) {
                AssetFolderGrants.lockForSync(jdbc, grantFolderId, grantSubject);
            }
            List<String> revokedAt = jdbc.queryForList("""
                    update platform.access_request
                       set status = 'REVOKED', revoked_by = ?, revoked_at = now()
                     where id = ? and status = 'APPROVED'
                    returning revoked_at::text
                    """, String.class, user, id);
            if (revokedAt.isEmpty()) {
                return 0;
            }
            appendRequestEvent(id, "REVOKE", user, eventDetail(row,
                    "revoked_by=" + user + "; revoked_at=" + revokedAt.get(0)));
            if (grantFolderId != null) {
                AssetFolderGrants.sync(jdbc, grantFolderId, grantSubject, user);
            }
            // D52：授权都回收了，挂在告警中心的「即将到期」提醒必须一起收掉，
            // 否则审批人会继续收到一条不会再发生的提醒
            AccessExpiryJob.closeNotices(jdbc, id, user);
            return 1;
        });
        if (revoked == null || revoked == 0) {
            throw ApiException.badRequest("该授权已经被回收或状态已变化");
        }
        auditRequest(http, "ACCESS_REQUEST_REVOKE", id, row, null);
        return requestRow(id, user, platformStaff(http));
    }

    /**
     * 续期（D52）：对「已通过」或「已过期」的授权再走一轮审批，生成一张新申请。
     *
     * <p>为什么不是「直接延长 expires_at」：授权生效的唯一入口是审批
     * （v460 F-P0-1 还专门挡了「申请人自己批」）。让续期直接改到期时间等于开一条
     * 绕过审批人的授权通道，双人闸门（L4）也会跟着失效。所以续期＝<b>接一张新单走原审批链</b>，
     * 原授权保持不变、按原到期时间失效，新单批准后两段有效期自然衔接。</p>
     *
     * <p>为什么只有申请人能发起：新单的 requester 必须是本人，否则审计里「谁申请的」
     * 会被审批人代提写歪。审批人在「待我审批」里照常审批即可。</p>
     */
    @PostMapping("/requests/{id}/renew")
    @RequireAnyAtom({"access:request", "access:approve"})
    public Map<String, Object> renew(@PathVariable long id,
                                     @RequestBody(required = false) Decision decision,
                                     HttpServletRequest http) {
        String user = user(http);
        Map<String, Object> row = requestRow(id, user, platformStaff(http));
        String status = str(row.get("status"));
        if (!"APPROVED".equals(status) && !"EXPIRED".equals(status)) {
            // REVOKED 是审批人主动收回的判定，不能被「续期」静默绕过，必须重新申请
            throw ApiException.badRequest("只有「已通过」或「已过期」的授权可以续期；"
                    + "被回收的授权请重新提交申请并说明用途");
        }
        if (!user.equals(str(row.get("requester")))) {
            throw ApiException.badRequest("续期只能由申请人本人发起（代提会让审计里的申请人失真）");
        }
        String type = str(row.get("resourceType"));
        String catalog = str(row.get("catalog"));
        String schema = str(row.get("schemaName"));
        String table = str(row.get("tableName"));
        String column = str(row.get("columnName"));
        String permission = str(row.get("permission"));
        // 续期只能由申请人本人发起，requester_tenant 沿用原单（跨租户申请不能借续期换租户）
        Long folderId = "ASSET_FOLDER".equals(type) ? longOf(row.get("folderId")) : null;
        if (folderId != null) {
            // 目录续期的真键仍是 folder_id：目录改名后老单的 schema_name 是旧 path 快照，
            // 这里重取最新 path；目录已删除则没有续的意义
            List<Map<String, Object>> folders = jdbc.queryForList(
                    "select path from platform.asset_folder where id = ?", folderId);
            if (folders.isEmpty()) {
                throw ApiException.notFound("资产目录已删除，无法续期: #" + id);
            }
            schema = AssetFolderGrants.displayPath(str(folders.get(0).get("path")));
            table = null;
            column = null;
        } else {
            // 资源可能已被下线/移出纳管：续一张死资源的单只会让审批人白批
            Integer exists = jdbc.queryForObject("""
                    select count(*) from platform.managed_table
                    where catalog = ? and schema_name = ?
                      and (? = 'DATABASE' or table_name = ?)
                    """, Integer.class, catalog, schema, type, table);
            if (exists == null || exists == 0) {
                throw ApiException.notFound("资源已不存在或未纳管，无法续期: "
                        + AccessExpiryJob.resourceText(row));
            }
        }
        // D61：续期单的理由是**拼出来的**（续期自 #id + 原审批意见），历史数据里的审批意见
        // 可能早就超过 500 字（本次不迁历史）。这里对拼进来的说明做**显式截断**并留标记，
        // 而不是让整条续期被限长 400 挡死，也绝不静默写一条超限记录进库。
        String prefix = "续期自 #" + id + "（原到期 "
                + (row.get("expiresAt") == null ? "未记录，按决定时间 +90 天" : row.get("expiresAt"))
                + "）";
        String note = decision == null || decision.comment() == null
                || decision.comment().isBlank() ? "" : "；说明：" + trim(decision.comment());
        String reason = prefix + fitNote(note, prefix);
        CreateResult created = submitRequest(user, str(row.get("requesterTenant")), type, catalog,
                schema, table, column, folderId, permission, reason, true);
        if (created.id() == null) {
            throw new ApiException(500, "续期失败：数据库未返回 id");
        }
        appendRequestEvent(id, "RENEW_REQUEST", user,
                "续期生成新申请 #" + created.id() + (created.created() ? "" : "（命中去重，复用审批中的那一单）"));
        sessions.audit(user, "ACCESS_REQUEST_RENEW", String.valueOf(created.id()),
                "续期自 #" + id + "；资源 " + AccessExpiryJob.resourceText(row) + " " + permission
                        + (created.created() ? "" : "；命中去重、复用审批中的申请"));
        Map<String, Object> out = new LinkedHashMap<>(requestRow(created.id(), user, platformStaff(http)));
        out.put("renewOf", id);
        out.put("reused", !created.created());
        return out;
    }

    /**
     * 手动触发一轮「到期前提醒」（运维口子，D52）。
     *
     * <p>提醒本身由 {@code access.remind-ms} 定时轮跑（到期前 7 天与 1 天各一次）；
     * 这里给的是「刚配置好渠道想立刻验证」与「回归脚本要可重复跑」的入口。
     * 扫描是全平台的（授权到期不分租户），所以只允许平台（默认租户）的
     * {@code system:manage} 调用，且每次触发都写审计。</p>
     */
    @PostMapping("/expiry-reminders/run")
    @RequireAtom("system:manage")
    public Map<String, Object> runExpiryReminders(@RequestBody(required = false) ReminderRequest req,
                                                  HttpServletRequest http) {
        if (!com.lab.dataworks.common.TenantGuard.platform()) {
            throw new ApiException(403, "到期提醒扫描是平台级的，只有默认租户的管理员能触发");
        }
        int[] windows = req == null || req.windowDays() == null
                ? AccessExpiryJob.REMIND_WINDOWS_DAYS : new int[]{req.windowDays()};
        if (windows.length == 1 && (windows[0] < 1 || windows[0] > 365)) {
            throw ApiException.badRequest("windowDays 必须在 1-365 之间，收到 " + windows[0]);
        }
        String user = user(http);
        Map<String, Object> out = new LinkedHashMap<>(expiryReminders.notifyExpiring(windows, user));
        out.put("windowsRequested", java.util.Arrays.stream(windows).boxed().toList());
        sessions.audit(user, "ACCESS_EXPIRY_REMINDERS_RUN", "access",
                "手动触发到期提醒扫描 windows=" + out.get("windowsRequested")
                        + "，新发 " + out.getOrDefault("total", 0) + " 条",
                "SUCCESS", com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return out;
    }

    /** {@code POST /api/access/expiry-reminders/run} 的请求体（可空）。 */
    public record ReminderRequest(Integer windowDays) {
    }

    /** {@code POST /api/access/approval-escalations/run} 的请求体（可空；dryRun=true 只看名单不落库）。 */
    public record EscalationRequest(Boolean dryRun) {
    }

    /**
     * TC7-G5：本租户按密级配置的审批链 + default 租户兜底行。
     * 只读展示，不判审批权（能看到本页的本来就有 access:approve / user:manage）。
     */
    @GetMapping("/approval-policies")
    @RequireAnyAtom({"access:approve", "user:manage"})
    public List<Map<String, Object>> listApprovalPolicies() {
        String tenant = TenantScope.current();
        return jdbc.queryForList("""
                select id, tenant_id as "tenantId", data_level as "dataLevel",
                       steps::text as "steps", timeout_days as "timeoutDays",
                       escalate_to::text as "escalateTo", enabled,
                       updated_by as "updatedBy", updated_at as "updatedAt"
                from platform.access_approval_policy
                where tenant_id in (?, 'default')
                order by data_level, (tenant_id = ?) desc, id
                """, tenant, tenant);
    }

    /**
     * TC7-G5：新建/更新本租户某密级的审批链（unique(tenant_id, data_level) upsert）。
     *
     * <p>default 租户的管理员在这里配置的就是全平台兜底链——与到期提醒同一权限口径：
     * 管理配置＝user:manage；租户管理员只管自己租户的行（TenantScope.current()），
     * 改不到别的租户。</p>
     *
     * <p>校验全部显式 400（密级枚举、1–2 级、模式 ANY/ALL、池非空且 ≤20 人、
     * timeoutDays 0–365 且 &gt;0 必须配 escalateTo），不做静默兜底。</p>
     */
    @PutMapping("/approval-policies")
    @RequireAtom("user:manage")
    public Map<String, Object> upsertApprovalPolicy(@RequestBody PolicyBody body,
                                                    HttpServletRequest http) {
        ApprovalPolicy.Draft draft = ApprovalPolicy.canonicalize(body);
        String tenant = TenantScope.current();
        String user = user(http);
        boolean enabled = body.enabled() == null || body.enabled();
        Long id = jdbc.queryForObject("""
                insert into platform.access_approval_policy
                    (tenant_id, data_level, steps, timeout_days, escalate_to, enabled, updated_by)
                values (?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
                on conflict (tenant_id, data_level) do update set
                    steps = excluded.steps,
                    timeout_days = excluded.timeout_days,
                    escalate_to = excluded.escalate_to,
                    enabled = excluded.enabled,
                    updated_by = excluded.updated_by,
                    updated_at = now()
                returning id
                """, Long.class, tenant, draft.dataLevel(), draft.stepsJson(),
                draft.timeoutDays(), draft.escalateToJson(), enabled, user);
        Map<String, Object> row = jdbc.queryForMap("""
                select id, tenant_id as "tenantId", data_level as "dataLevel",
                       steps::text as "steps", timeout_days as "timeoutDays",
                       escalate_to::text as "escalateTo", enabled, updated_by as "updatedBy"
                from platform.access_approval_policy where id = ?
                """, id);
        sessions.audit(user, "ACCESS_APPROVAL_POLICY_UPSERT", "access",
                draft.dataLevel() + " 审批链：" + draft.stepsJson()
                        + " 超时 " + draft.timeoutDays() + " 天，升级池 " + draft.escalateToJson(),
                "SUCCESS", com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return row;
    }

    /**
     * TC7-G5：删除本租户某密级的自定义链，回退 default 兜底 / 旧硬编码。
     * 在途单不受影响（快照已冻结在申请行上）。
     */
    @DeleteMapping("/approval-policies/{dataLevel}")
    @RequireAtom("user:manage")
    public Map<String, Object> resetApprovalPolicy(@PathVariable String dataLevel,
                                                   HttpServletRequest http) {
        String level = upperStatic(dataLevel);
        if (!ApprovalPolicy.LEVELS.contains(level)) {
            throw ApiException.badRequest("dataLevel 只能是 L1-L4，收到: " + dataLevel);
        }
        String user = user(http);
        int deleted = jdbc.update("""
                delete from platform.access_approval_policy
                where tenant_id = ? and data_level = ?
                """, TenantScope.current(), level);
        if (deleted == 0) {
            throw ApiException.badRequest("当前租户没有 " + level + " 的自定义审批链（无需删除）");
        }
        sessions.audit(user, "ACCESS_APPROVAL_POLICY_RESET", "access",
                level + " 审批链已删除，回退平台兜底/默认两级规则",
                "SUCCESS", com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return Map.of("deleted", deleted, "dataLevel", level);
    }

    /**
     * TC7-G5：手动触发审批超时升级扫描（联调/回归入口）。
     *
     * <p>与到期提醒同口径：扫描跨租户（升级候选来自各租户在途单），
     * 所以只允许平台（默认租户）的 {@code system:manage} 调用，且每次触发都写审计。</p>
     */
    @PostMapping("/approval-escalations/run")
    @RequireAtom("system:manage")
    public Map<String, Object> runApprovalEscalations(@RequestBody(required = false) EscalationRequest req,
                                                      HttpServletRequest http) {
        if (!com.lab.dataworks.common.TenantGuard.platform()) {
            throw new ApiException(403, "审批超时升级扫描是平台级的，只有默认租户的管理员能触发");
        }
        boolean dryRun = req != null && Boolean.TRUE.equals(req.dryRun());
        String user = user(http);
        Map<String, Object> out = new LinkedHashMap<>(escalations.escalateDue(user, dryRun));
        out.put("dryRun", dryRun);
        sessions.audit(user, "ACCESS_APPROVAL_ESCALATIONS_RUN", "access",
                (dryRun ? "预览" : "手动触发") + "审批超时升级扫描，升级 "
                        + out.getOrDefault("escalated", 0) + " 条",
                "SUCCESS", com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return out;
    }

    private Map<String, Object> decide(long id, String status, Decision decision,
                                       HttpServletRequest http) {
        String user = user(http);
        // v469 F-P0-1：有审批权的调用者按 id 取原始行，**不做可见性 404**——否则跨租户
        // 审批人只会看到「申请不存在」，既拿不到 403 也留不下越权审计。
        // 普通用户（仅 access:request）仍走可见性过滤，避免按 id 探测别人的申请。
        boolean mayApprove = admin(http) || permissions.atoms(user, TenantScope.current())
                .contains("access:approve");
        Map<String, Object> row = mayApprove ? decisionRow(id)
                : requestRow(id, user, platformStaff(http));
        // v460 F-P0-1：审批人不能是申请人本人（含 ADMIN）；双人闸门对「有审批权的人」才有意义
        if (user.equals(str(row.get("requester")))) {
            denied(http, "ACCESS_REQUEST_APPROVE_DENIED", id, "不能审批自己的申请");
        }
        String currentStatus = str(row.get("status"));
        if (!"PENDING".equals(currentStatus) && !"PENDING_SECOND".equals(currentStatus)) {
            throw ApiException.badRequest("该申请已经处理过了");
        }
        // L4 二审必须换人：先给明确原因，避免被下面的 canApprove 泛化成「没有审批权」。
        // TC7-G5：仅对旧硬编码链生效——策略单的两级人员由策略池决定（可配置同人两连批时，
        // 说明配置人自己就要这么批；池校验与「不能自批」仍然成立）。
        if (str(row.get("policySteps")) == null
                && "PENDING_SECOND".equals(currentStatus)
                && user.equals(str(row.get("approver")))) {
            denied(http, "ACCESS_REQUEST_APPROVE_DENIED", id,
                    "同一审批人不能连续审批两次，请由另一位审批人完成二审");
        }
        // v469 F-P0-1：审批动作本身必须校验租户一致性。
        // 之前只在列表查询里按 requester_tenant 过滤，审批只判资源负责人，
        // 「第三方租户的审批人」仍可能批到别人的授权。这里显式拦：
        //   审批人租户必须 = 申请租户或资源租户；平台管理员（default + user:manage）除外。
        String current = TenantScope.current();
        boolean platformAdmin = com.lab.dataworks.common.TenantGuard.platform() && admin(http);
        boolean tenantConsistent = current.equals(str(row.get("requesterTenant")))
                || current.equals(str(row.get("ownerTenant")));
        if (!platformAdmin && !tenantConsistent) {
            denied(http, "ACCESS_REQUEST_APPROVE_DENIED", id,
                    "跨租户审批被拒绝：审批人租户=" + current
                            + "，申请租户=" + str(row.get("requesterTenant"))
                            + "，资源租户=" + str(row.get("ownerTenant")));
        }
        if (!canApprove(user, admin(http), row)) {
            denied(http, "ACCESS_REQUEST_APPROVE_DENIED", id,
                    str(row.get("policySteps")) != null
                            ? "不在当前审批节点的可签范围（含升级池）内，不能签批"
                            : "只有资源所属租户的负责人或平台管理员可以审批");
        }
        String comment = decision == null ? null : trim(decision.comment());
        // TC7-G5：带策略快照的申请走可配置链（或签/会签/升级池），旧硬编码行不受影响。
        String policyText = str(row.get("policySteps"));
        if (policyText != null) {
            return decidePolicy(id, status, row, policyText, user, comment, http);
        }
        // L4 双人审批：第一次通过只到 PENDING_SECOND，另一位审批人再批才 APPROVED。
        // 状态机抽成纯函数，单测覆盖「同一人不能批两次」等分支。
        String level = levelOfRequest(row);
        ApprovalStep step = approvalStep(status, currentStatus, level, user,
                str(row.get("approver")));
        // v471 F-P1-2：审批结果既写入请求行的当前状态，也**追加**一条事件（只 insert）。
        // 与状态更新同事务：不会出现「状态变了但没有历史记录」或反过来。
        // V112：目录申请终审通过时同事务物化 asset_folder_grant（source='ACCESS'）。
        // 加锁顺序统一「先 grant 锁、后改申请行」，与回收/到期一致，防并发重算互踩。
        boolean folderRow = "ASSET_FOLDER".equals(str(row.get("resourceType")));
        Long grantFolderId = folderRow ? longOf(row.get("folderId")) : null;
        String grantSubject = str(row.get("requester"));
        Integer updated = tx.execute(ignored -> {
            if (grantFolderId != null) {
                AssetFolderGrants.lockForSync(jdbc, grantFolderId, grantSubject);
            }
            int n;
            if ("APPROVE_1".equals(step.event())) {
                n = jdbc.update("""
                        update platform.access_request
                           set status = 'PENDING_SECOND', approver = ?, comment = ?
                         where id = ? and status = 'PENDING'
                        """, user, comment, id);
            } else if ("PENDING_SECOND".equals(currentStatus)) {
                n = jdbc.update("""
                        update platform.access_request
                           set status = ?, second_approver = ?, comment = coalesce(?, comment),
                               decided_at = now(),
                               expires_at = case when ? = 'APPROVED'
                                                 then now() + make_interval(days => ?)
                                                 else expires_at end
                         where id = ? and status = 'PENDING_SECOND'
                        """, step.status(), user, comment, step.status(), step.grantDays(), id);
            } else {
                n = jdbc.update("""
                        update platform.access_request
                           set status = ?, approver = ?, comment = ?, decided_at = now(),
                               expires_at = case when ? = 'APPROVED'
                                                 then now() + make_interval(days => ?)
                                                 else expires_at end
                         where id = ? and status = 'PENDING'
                        """, step.status(), user, comment, step.status(), step.grantDays(), id);
            }
            if (n > 0) {
                appendRequestEvent(id, step.event(), user, eventDetail(row, comment));
                if (grantFolderId != null && "APPROVED".equals(step.status())) {
                    AssetFolderGrants.materialize(jdbc, id, grantFolderId, grantSubject,
                            str(row.get("permission")), user);
                }
            }
            return n;
        });
        if (updated == null || updated == 0) {
            // 两个审批人同时点「通过」：只允许一个成功，另一个按已处理返回
            throw ApiException.badRequest("该申请已经处理过了");
        }
        String auditAction = "APPROVE_1".equals(step.event())
                ? "ACCESS_REQUEST_APPROVE_1" : "ACCESS_REQUEST_" + step.status();
        auditRequest(http, auditAction, id, row, comment);
        return requestRow(id, user, platformStaff(http));
    }

    /**
     * TC7-G5：策略单签批（可配置链：或签/会签/超时升级池）。
     *
     * <p>自批、跨租户一致性、可签池（canApprove 策略分支）已在 decide() 统一拦过；
     * 本方法只负责链推进与落库。中间态复用 PENDING/PENDING_SECOND（级号由状态推导，
     * 见 {@code ApprovalPolicy.stepOf}），所以列表/审计/旧断言零改动。</p>
     *
     * <p>会签并发：WAIT 分支用 {@code step_signed || to_jsonb(actor)} 数组追加而不是
     * 整列覆盖，两个审批人同时签不会互相吞；状态更新一律带 {@code and status = ?}
     * 乐观闸门，输家按「已处理」返回（与旧双审并发路径同口径）。</p>
     */
    private Map<String, Object> decidePolicy(long id, String requestedStatus,
                                             Map<String, Object> row, String policyText,
                                             String user, String comment, HttpServletRequest http) {
        ApprovalPolicy.Snapshot snap = ApprovalPolicy.parse(policyText);
        String currentStatus = str(row.get("status"));
        int step = ApprovalPolicy.stepOf(currentStatus);
        if (step >= snap.steps().size()) {
            throw ApiException.badRequest("申请单上的审批链与当前状态不匹配（级数被改过？），请撤销后重新提交");
        }
        boolean escalated = ApprovalPolicy.escalatedAt(escalatedStepOf(row), step);
        List<String> signed = ApprovalPolicy.signedList(str(row.get("stepSigned")));
        boolean approve = !"REJECTED".equals(upperStatic(requestedStatus));
        ApprovalPolicy.Outcome out = ApprovalPolicy.advance(snap, approve, step, signed, user,
                escalated, str(row.get("requester")));
        String level = levelOfRequest(row);
        int grantDays = "L4".equals(level) ? L4_GRANT_DAYS : GRANT_DAYS;
        boolean folderRow = "ASSET_FOLDER".equals(str(row.get("resourceType")));
        Long grantFolderId = folderRow ? longOf(row.get("folderId")) : null;
        String grantSubject = str(row.get("requester"));
        String stepLabel = "step=" + step
                + "; mode=" + snap.steps().get(step).mode()
                + "; pool=" + ApprovalPolicy.eligibleApprovers(snap, step, escalated,
                str(row.get("requester")));
        Integer updated = tx.execute(ignored -> {
            if (grantFolderId != null) {
                AssetFolderGrants.lockForSync(jdbc, grantFolderId, grantSubject);
            }
            int n = switch (out.kind()) {
                case "REJECTED" -> jdbc.update("""
                        update platform.access_request
                           set status = 'REJECTED', comment = coalesce(?, comment), decided_at = now()
                         where id = ? and status = ?
                        """, comment, id, currentStatus);
                case ApprovalPolicy.WAIT -> jdbc.update("""
                        update platform.access_request
                           set step_signed = coalesce(step_signed, '[]'::jsonb) || to_jsonb(?),
                               comment = coalesce(?, comment)
                         where id = ? and status = ?
                        """, user, comment, id, currentStatus);
                case ApprovalPolicy.ADVANCED -> jdbc.update("""
                        update platform.access_request
                           set status = 'PENDING_SECOND', approver = coalesce(approver, ?),
                               step_signed = '[]'::jsonb, step_at = now(),
                               comment = coalesce(?, comment)
                         where id = ? and status = ?
                        """, user, comment, id, currentStatus);
                default -> {
                    int done = jdbc.update("""
                            update platform.access_request
                               set status = 'APPROVED',
                                   approver = coalesce(approver, ?),
                                   second_approver = case when ? > 0 then ? else second_approver end,
                                   step_signed = ?::jsonb,
                                   decided_at = now(),
                                   expires_at = now() + make_interval(days => ?)
                             where id = ? and status = ?
                            """, user, step, user, ApprovalPolicy.signedJson(out.signed()),
                            grantDays, id, currentStatus);
                    if (done > 0 && grantFolderId != null) {
                        AssetFolderGrants.materialize(jdbc, id, grantFolderId, grantSubject,
                                str(row.get("permission")), user);
                    }
                    yield done;
                }
            };
            if (n > 0) {
                String event = switch (out.kind()) {
                    case "REJECTED" -> "REJECT";
                    case ApprovalPolicy.WAIT -> "POLICY_SIGN";
                    case ApprovalPolicy.ADVANCED -> "POLICY_ADVANCE";
                    default -> "POLICY_APPROVE";
                };
                appendRequestEvent(id, event, user, eventDetail(row,
                        stepLabel + "; signed=" + out.signed()
                                + (comment == null || comment.isBlank() ? "" : "; " + comment)));
            }
            return n;
        });
        if (updated == null || updated == 0) {
            // 并发签批/审批：状态闸门只放行一个推进动作，其余按已处理返回
            throw ApiException.badRequest("该申请已经处理过了");
        }
        String auditAction = switch (out.kind()) {
            case "REJECTED" -> "ACCESS_REQUEST_REJECTED";
            case ApprovalPolicy.WAIT -> "ACCESS_REQUEST_CO_APPROVE";
            case ApprovalPolicy.ADVANCED -> "ACCESS_REQUEST_APPROVE_1";
            default -> "ACCESS_REQUEST_APPROVED";
        };
        auditRequest(http, auditAction, id, row, stepLabel + "；链快照签批");
        return requestRow(id, user, platformStaff(http));
    }

    /** TC7-G5：escalated_step 兼容 Number/null（JDBC 返回 Integer，老行可能 NULL）。 */
    private static Integer escalatedStepOf(Map<String, Object> row) {
        Object v = row.get("escalatedStep");
        return v instanceof Number num ? num.intValue() : null;
    }

    /**
     * 审批状态机（纯函数，便于单测）。
     *
     * <ul>
     *   <li>PENDING + L4 + 通过 → PENDING_SECOND / APPROVE_1（不写有效期）；</li>
     *   <li>PENDING_SECOND + 另一审批人通过 → APPROVED / APPROVE_2 / 30 天；</li>
     *   <li>PENDING + 非 L4 通过 → APPROVED / APPROVE / 90 天；</li>
     *   <li>驳回 → REJECTED / REJECT；同一人不能在一审后再做二审决定。</li>
     * </ul>
     */
    static ApprovalStep approvalStep(String requestedStatus, String currentStatus, String level,
                                     String actor, String firstApprover) {
        String requested = upperStatic(requestedStatus);
        String current = upperStatic(currentStatus);
        String lvl = upperStatic(level);
        String who = actor == null ? "" : actor;
        String first = firstApprover == null ? "" : firstApprover;
        if ("PENDING_SECOND".equals(current) && !first.isBlank() && first.equals(who)) {
            throw ApiException.badRequest("同一审批人不能连续审批两次，请由另一位审批人完成二审");
        }
        if ("REJECTED".equals(requested)) {
            if (!"PENDING".equals(current) && !"PENDING_SECOND".equals(current)) {
                throw ApiException.badRequest("该申请已经处理过了");
            }
            return new ApprovalStep("REJECTED", "REJECT", false, 0);
        }
        if (!"APPROVED".equals(requested)) {
            throw ApiException.badRequest("审批结果只能是通过或驳回");
        }
        if ("PENDING".equals(current)) {
            if ("L4".equals(lvl)) {
                return new ApprovalStep("PENDING_SECOND", "APPROVE_1", false, 0);
            }
            return new ApprovalStep("APPROVED", "APPROVE", true, GRANT_DAYS);
        }
        if ("PENDING_SECOND".equals(current)) {
            return new ApprovalStep("APPROVED", "APPROVE_2", true, L4_GRANT_DAYS);
        }
        throw ApiException.badRequest("该申请已经处理过了");
    }

    private static String upperStatic(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 申请对应资源的当前等级：COLUMN 取列等级；TABLE 取列推导最大值；DATABASE 取库内最大值。
     * 查不到资源时按 L3（历史行为，单审），不阻塞存量数据。
     */
    private String levelOfRequest(Map<String, Object> row) {
        String type = str(row.get("resourceType"));
        if ("ASSET_FOLDER".equals(type)) {
            // 目录内容权与字段密级无关（数据分级挂在列/表上），恒 L3 单审；
            // 若未来要按目录内最高密级走 L4 二审，改这里即可。
            return "L3";
        }
        String catalog = str(row.get("catalog"));
        String schema = str(row.get("schemaName"));
        String table = str(row.get("tableName"));
        List<String> levels;
        if ("COLUMN".equals(type)) {
            levels = jdbc.queryForList("""
                    select c.data_level from platform.managed_column c
                    join platform.managed_table t on t.id = c.table_id
                    where t.catalog = ? and t.schema_name = ? and t.table_name = ?
                      and lower(c.column_name) = lower(?)
                    order by c.id limit 1
                    """, String.class, catalog, schema, table, str(row.get("columnName")));
        } else if ("TABLE".equals(type)) {
            levels = jdbc.queryForList("""
                    select coalesce((select max(c.data_level) from platform.managed_column c
                                      where c.table_id = t.id), t.data_level)
                    from platform.managed_table t
                    where t.catalog = ? and t.schema_name = ? and t.table_name = ?
                    order by t.id limit 1
                    """, String.class, catalog, schema, table);
        } else {
            levels = jdbc.queryForList("""
                    select coalesce(max(coalesce(
                               (select max(c.data_level) from platform.managed_column c
                                 where c.table_id = t.id), t.data_level)), 'L3')
                    from platform.managed_table t
                    where t.catalog = ? and t.schema_name = ?
                    """, String.class, catalog, schema);
        }
        String level = levels.isEmpty() ? "" : str(levels.get(0)).trim()
                .toUpperCase(Locale.ROOT);
        return level.isBlank() ? "L3" : level;
    }

    // ---------- 权限计算 ----------

    private String levelOf(List<Map<String, Object>> grants, String catalog, String schema,
                           String table, boolean tableLevel, boolean owner) {
        // 负责人拥有全部权限，不用等审批
        if (owner) return "WRITE";
        boolean read = false;
        for (Map<String, Object> grant : grants) {
            if (!"APPROVED".equals(str(grant.get("status")))) continue;
            if (!covers(grant, catalog, schema, table, tableLevel)) continue;
            if ("WRITE".equals(str(grant.get("permission")))) return "WRITE";
            read = true;
        }
        return read ? "READ" : "NONE";
    }

    private boolean pendingOf(List<Map<String, Object>> grants, String catalog, String schema,
                              String table, boolean tableLevel) {
        for (Map<String, Object> grant : grants) {
            if (!"PENDING".equals(str(grant.get("status")))) continue;
            if (covers(grant, catalog, schema, table, tableLevel)) return true;
        }
        return false;
    }

    /**
     * 库级授权覆盖库下所有表；表级授权只覆盖那一张表；列级只覆盖那一列
     * （因此不参与整表「我的权限」展示，避免把单列授权误显示成整表可读）。
     */
    private boolean covers(Map<String, Object> grant, String catalog, String schema,
                           String table, boolean tableLevel) {
        String type = str(grant.get("resource_type"));
        // 目录申请的 catalog 是占位 'ASSET'、path 只作展示快照，永远不参与库表覆盖判定
        if ("ASSET_FOLDER".equals(type)) return false;
        if (!catalog.equals(str(grant.get("catalog")))) return false;
        if (!schema.equals(str(grant.get("schema_name")))) return false;
        if ("COLUMN".equals(type)) return false;
        if ("DATABASE".equals(type)) return true;
        return tableLevel && table != null && table.equals(str(grant.get("table_name")));
    }

    /** 审计写失败不影响主流程：记录 actor / 申请 id / 拒绝原因。 */
    private void denied(HttpServletRequest http, String action, long id, String reason) {
        Object user = http.getAttribute("username");
        sessions.audit(user == null ? "unknown" : String.valueOf(user), action,
                String.valueOf(id), reason, "FAILED",
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        throw new ApiException(403, reason);
    }

    /**
     * 审批动作双写审计（v469 F-P0-2）：**申请租户 + 资源租户**都要有一条。
     *
     * <p>以前只写操作人所在租户，平台管理员代批跨租户申请时，申请租户和资源租户
     * 都查不到这条授权。现在按两个业务租户各写一条（与操作人租户去重），
     * detail 里标明视角，任何一方都能独立追溯。</p>
     */
    private void auditRequest(HttpServletRequest http, String action, long id,
                              Map<String, Object> row, String detail) {
        String user = user(http);
        String ip = com.lab.dataworks.auth.AuthInterceptor.clientIp(http);
        String requesterTenant = str(row.get("requesterTenant"));
        String ownerTenant = str(row.get("ownerTenant"));
        java.util.Set<String> written = new java.util.LinkedHashSet<>();
        // 操作人视角（当前租户）
        sessions.audit(user, action, String.valueOf(id), detail, "SUCCESS", ip);
        written.add(TenantScope.current());
        // 申请租户视角
        if (requesterTenant != null && !requesterTenant.isBlank()
                && written.add(requesterTenant)) {
            sessions.auditAs(requesterTenant, user, action, String.valueOf(id),
                    "申请租户视角：" + (detail == null ? "" : detail), "SUCCESS", ip);
        }
        // 资源租户视角
        if (ownerTenant != null && !ownerTenant.isBlank() && written.add(ownerTenant)) {
            sessions.auditAs(ownerTenant, user, action, String.valueOf(id),
                    "资源租户视角：" + (detail == null ? "" : detail), "SUCCESS", ip);
        }
    }

    /**
     * 追加一条权限申请事件（v471 F-P1-2）：**只 insert，不回头改原行**。
     *
     * <p>审批/回收都会调用它，历史因此可追溯：原审批人、意见、决定时间留在
     * access_request 行上不被覆盖，回收另有 REVOKE 事件记录回收人与时间。
     * 与状态变更同事务，失败则一起回滚。</p>
     *
     * <p>全类只有这一处写 platform.access_request_event，回滚时删掉本方法 +
     * decide/revoke 里的调用即可。</p>
     */
    private void appendRequestEvent(long requestId, String action, String actor, String detail) {
        jdbc.update("""
                insert into platform.access_request_event (request_id, action, actor, detail)
                values (?, ?, ?, ?)
                """, requestId, action, actor, detail);
    }

    /** 列级申请的审批/回收事件也要能看出是哪一列。 */
    private String eventDetail(Map<String, Object> row, String detail) {
        String column = str(row.get("columnName"));
        String prefix = column == null || column.isBlank() ? "" : "column=" + column + "; ";
        return prefix + (detail == null ? "" : detail);
    }

    /**
     * 谁能回收这条授权（「已授权」列表与 revoke 动作的<b>唯一</b>口径）：
     * 平台管理员兜底；其余必须持有 {@code access:revoke}，且申请与当前租户相关
     * （资源属于本租户，或申请由本租户提交）。
     *
     * <p>两处共用是为了不再出现 BUG-TC6-2 那种错位：列表与动作各判一套，
     * 结果要么按钮点了 403，要么授权能收却没人看得见。</p>
     */
    static boolean canRevoke(boolean platformAdmin, boolean hasRevoke, String currentTenant,
                             String ownerTenant, String requesterTenant) {
        if (platformAdmin) {
            return true;
        }
        if (!hasRevoke || currentTenant == null) {
            return false;
        }
        return currentTenant.equals(ownerTenant) || currentTenant.equals(requesterTenant);
    }

    private boolean canApprove(String user, boolean admin, Map<String, Object> row) {
        // TC7-G5：策略单的可签性由「当级池 ∪（已升级时的 escalateTo）」决定；
        // 平台管理员仍直通；自批/跨租户闸门在 decide() 入口统一拦，这里不重复。
        String policyText = str(row.get("policySteps"));
        if (policyText != null) {
            if (com.lab.dataworks.common.TenantGuard.platform() && admin) {
                return true;
            }
            ApprovalPolicy.Snapshot snap = ApprovalPolicy.parse(policyText);
            int step = ApprovalPolicy.stepOf(str(row.get("status")));
            if (step >= snap.steps().size()) {
                return false;
            }
            boolean escalated = ApprovalPolicy.escalatedAt(escalatedStepOf(row), step);
            List<String> pool = ApprovalPolicy.eligibleApprovers(snap, step, escalated,
                    str(row.get("requester")));
            if (!pool.contains(user)) {
                return false;
            }
            if ("ALL".equals(snap.steps().get(step).mode())) {
                // 已会签过的人不能重复签（advance() 会给出更具体的报错）
                return !ApprovalPolicy.signedList(str(row.get("stepSigned"))).contains(user);
            }
            return true;
        }
        // L4 二期：一审人不能同时是二审人，否则「双人审批」形同虚设。
        if ("PENDING_SECOND".equals(str(row.get("status")))
                && user.equals(str(row.get("approver")))) {
            return false;
        }
        // v448 P0-2：按「审批人身份与申请单租户一致」收口，分两类审批人：
        //   ① 角色型（租户管理员 / access:approve）：只能批**本租户提交的**申请；
        //     之前 admin 分支直接 return true，tenant_b 的 ADMIN 能批/驳/回收 default 的授权。
        //   ② 资源负责人：只能批**资源属于本租户**的申请（跨租户申请由资源方负责人批）。
        // 平台管理员（默认租户）不受限。
        boolean platformAdmin = com.lab.dataworks.common.TenantGuard.platform() && admin;
        if (platformAdmin) {
            return true;
        }
        String current = TenantScope.current();
        boolean sameRequester = current.equals(str(row.get("requesterTenant")));
        boolean sameResource = current.equals(str(row.get("ownerTenant")));
        // v460 F-P0-2：角色型审批人只认**资源所属租户**。以前允许 sameRequester，
        // 等于「申请人所在租户可以单方面放行别的租户的数据」——资源方完全不知情。
        // 跨租户申请必须由资源租户的负责人/管理员或平台管理员批准。
        boolean roleApprover = admin
                || permissions.atoms(user, current).contains("access:approve");
        if (roleApprover && sameResource) {
            return true;
        }
        // 资源已不在纳管表（行被删）时 ownerTenant 为空：非平台一律不放行，
        // 避免用「删表后残留的申请单」绕过归属判断。
        if (!sameResource) {
            return false;
        }
        if ("DATABASE".equals(str(row.get("resourceType")))) {
            // v460 F-P2-6：单表负责人不能代表整库。要么有显式库级 owner（table_name='*'），
            // 要么该库所有表的 owner 都是同一个人。
            Integer explicit = jdbc.queryForObject("""
                    select count(*) from platform.managed_table
                    where table_name = '*' and owner_user = ?
                      and catalog = ? and schema_name = ?
                    """, Integer.class, user, str(row.get("catalog")), str(row.get("schemaName")));
            if (explicit != null && explicit > 0) {
                return true;
            }
            List<Map<String, Object>> owners = jdbc.queryForList("""
                    select owner_user, count(*) as c from platform.managed_table
                    where catalog = ? and schema_name = ?
                    group by owner_user
                    """, str(row.get("catalog")), str(row.get("schemaName")));
            return owners.size() == 1 && user.equals(str(owners.get(0).get("owner_user")));
        }
        return user.equals(str(row.get("ownerUser")));
    }

    private boolean ownedBy(String user, String type, String catalog, String schema, String table) {
        Integer count = jdbc.queryForObject("""
                select count(*) from platform.managed_table
                where owner_user = ? and catalog = ? and schema_name = ?
                  and (? = 'DATABASE' or table_name = ?)
                """, Integer.class, user, catalog, schema, type, table);
        return count != null && count > 0;
    }

    /**
     * 列表/详情统一取数：owner 归属优先按 managed_table（库/表/列），
     * V112 起 ASSET_FOLDER 落到 asset_folder（created_by/tenant_id）——否则目录申请的
     * ownerTenant 恒空，除了平台管理员没人能批。目录已删（folder_id 置 NULL）时
     * ownerTenant 为空 → canApprove 拒绝、requestRow 仅申请租户可见，与
     * 「managed_table 行被删的残留申请」同一口径。
     */
    private String baseRequestSql() {
        return """
                select ar.id, ar.requester, ar.requester_tenant as "requesterTenant",
                       ar.resource_type as "resourceType", ar.catalog,
                       ar.schema_name as "schemaName", ar.table_name as "tableName",
                       ar.column_name as "columnName", ar.second_approver as "secondApprover",
                       ar.folder_id as "folderId",
                       ar.permission, ar.reason, ar.status, ar.approver, ar.comment,
                       ar.created_at as "createdAt", ar.decided_at as "decidedAt",
                       ar.expires_at as "expiresAt", ar.revoked_by as "revokedBy",
                       ar.revoked_at as "revokedAt",
                       ar.policy_steps::text as "policySteps",
                       ar.step_signed::text as "stepSigned",
                       ar.escalated_step as "escalatedStep",
                       ar.step_at as "stepAt",
                       coalesce((select min(mt.owner_user) from platform.managed_table mt
                                  where mt.catalog = ar.catalog
                                    and mt.schema_name = ar.schema_name
                                    and (ar.resource_type = 'DATABASE'
                                         or mt.table_name = ar.table_name)),
                                 (select af.created_by from platform.asset_folder af
                                   where af.id = ar.folder_id), 'admin')
                           as "ownerUser",
                       coalesce((select min(mt.tenant) from platform.managed_table mt
                                  where mt.catalog = ar.catalog
                                    and mt.schema_name = ar.schema_name
                                    and (ar.resource_type = 'DATABASE'
                                         or mt.table_name = ar.table_name)),
                                (select af.tenant_id from platform.asset_folder af
                                  where af.id = ar.folder_id))
                           as "ownerTenant"
                from platform.access_request ar
                """;
    }

    /**
     * @param staff 平台 staff（默认租户 + 管理/审批权限点）才跨租户可见。
     *              v460 F-P2-1：以前只看「租户是不是 default」，default 的普通 VIEWER
     *              也能用状态码区分别的租户的申请是否存在。
     */
    /** 审批用：按 id 取原始行，不做租户可见性过滤（否则跨租户审批只能得到 404）。 */
    private Map<String, Object> decisionRow(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                baseRequestSql() + " where ar.id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("申请不存在: " + id);
        return rows.get(0);
    }

    private Map<String, Object> requestRow(long id, String user, boolean staff) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                baseRequestSql() + " where ar.id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("申请不存在: " + id);
        Map<String, Object> row = rows.get(0);
        if (!staff) {
            String current = TenantScope.current();
            boolean visible = current.equals(str(row.get("requesterTenant")))
                    || current.equals(str(row.get("ownerTenant")));
            if (!visible) throw ApiException.notFound("申请不存在: " + id);
        }
        return row;
    }

    /**
     * 平台视角只看「平台 staff」：默认租户 + 管理/审批类权限点。
     * v460 F-P2-1/P2-4：以前只看租户是不是 default，default 的普通 VIEWER
     * 也能看到别的租户的库表与申请单，还能用状态码区分申请是否存在。
     */
    private boolean platformStaff(HttpServletRequest http) {
        if (!com.lab.dataworks.common.TenantGuard.platform()) {
            return false;
        }
        Object user = http.getAttribute("username");
        if (user == null) {
            return false;
        }
        Set<String> atoms = permissions.atoms(String.valueOf(user), TenantScope.current());
        // 只有平台管理级权限点才算「平台 staff」；普通审批员（access:approve）只是
        // 本租户的审批人，不能借此看到其他租户的申请单（F-P2-1 的根因就在这里）。
        return atoms.contains("user:manage") || atoms.contains("tenant:manage");
    }

    private String tenantOf(String username) {
        List<String> rows = jdbc.queryForList(
                "select tenant from platform.app_user where username = ?", String.class, username);
        return rows.isEmpty() || rows.get(0) == null ? "default" : rows.get(0);
    }

    private String user(HttpServletRequest http) {
        Object value = http.getAttribute("username");
        if (value == null) throw new ApiException(401, "未登录");
        return String.valueOf(value);
    }

    private boolean admin(HttpServletRequest http) {
        // 平台管理员 = 具备 user:manage 的角色（内置 ADMIN 天然具备，自定义角色也可授）
        String user = String.valueOf(http.getAttribute("username"));
        return permissions.atoms(user, TenantScope.current()).contains("user:manage");
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(message);
        return value.trim();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** 按字（code point）计数，代理对（emoji / 生僻字）算一个字，与前端 JS 的口径一致。 */
    static int reasonLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /**
     * D61：申请理由限长。超限直接 400 并写出当前字数，不做「截断后照收」的静默降级
     * （acceptance-rules 第 4 条：字段非法必须显式报错）。
     */
    static String requireReasonLength(String reason) {
        int length = reasonLength(reason);
        if (length > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("申请理由不能超过 " + MAX_REASON_LENGTH + " 字（当前 "
                    + length + " 字）");
        }
        return reason;
    }

    /**
     * D61：续期时把「说明」拼进理由，总长必须仍 ≤ {@link #MAX_REASON_LENGTH}。
     *
     * <p>历史审批意见可能本来就超限（本次不迁历史数据），所以这里对**拼进来的说明**做截断，
     * 并留下可见标记「…（说明超长已截断）」——既不静默写超限，也不因为旧数据把用户的续期挡死。</p>
     */
    static String fitNote(String note, String prefix) {
        if (note == null || note.isEmpty()) {
            return "";
        }
        int room = MAX_REASON_LENGTH - reasonLength(prefix);
        if (room <= 0) {
            return "";
        }
        if (reasonLength(note) <= room) {
            return note;
        }
        String marker = "…（说明超长已截断）";
        int keep = room - reasonLength(marker);
        if (keep <= 0) {
            return marker.substring(0, Math.min(marker.length(), Math.max(room, 0)));
        }
        return note.substring(0, note.offsetByCodePoints(0, keep)) + marker;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 拼接查重 SQL 的位置参数：前两个固定 + 分支谓词参数 + 末位权限（配合 resourceMatch）。 */
    private static Object[] concat(Object first, Object second, Object[] middle, Object last) {
        Object[] out = new Object[middle.length + 3];
        out[0] = first;
        out[1] = second;
        System.arraycopy(middle, 0, out, 2, middle.length);
        out[out.length - 1] = last;
        return out;
    }

    private static Long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
