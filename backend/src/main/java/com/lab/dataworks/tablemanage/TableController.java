package com.lab.dataworks.tablemanage;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.DorisErrors;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表管理：库/表浏览 + 建库/建表/删表。
 *
 * <p>与数据开发里的「表管理」口径一致：DDL 是真的落到 Doris，列表/结构/预览从
 * information_schema 与 SHOW 读取；外部 Doris 项目按项目连接执行。</p>
 *
 * <p>Doris 4.1 的 information_schema 对 table_schema 等值/LIKE 过滤会返回 0 行
 * （见 docs/数仓连接路由-20261025.md），所以这里取全量、在后端按库名过滤。</p>
 */
@RestController
@RequestMapping("/api/tables")
public class TableController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TableController.class);
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    private static final Set<String> SYSTEM_DBS =
            Set.of("information_schema", "mysql", "__internal_schema");

    private final JdbcTemplate jdbc;
    private final DorisAdminService doris;
    private final DorisQueryService query;
    private final SessionService sessions;
    private final TenantDataScope scope;
    private final WarehouseResolver warehouseResolver;
    private final MetadataReader metadata;
    private final DataGuardService dataGuard;
    /** BUG-TC4-1：生产库闸门（环境判定 + 挂审批工单）复用表编辑那一条链，不再另立一套。 */
    private final TableDdlService ddl;

    public TableController(JdbcTemplate jdbc, DorisAdminService doris,
                           DorisQueryService query, SessionService sessions,
                           TenantDataScope scope, WarehouseResolver warehouseResolver,
                           MetadataReader metadata, DataGuardService dataGuard,
                           TableDdlService ddl) {
        this.jdbc = jdbc;
        this.doris = doris;
        this.query = query;
        this.sessions = sessions;
        this.scope = scope;
        this.warehouseResolver = warehouseResolver;
        this.metadata = metadata;
        this.dataGuard = dataGuard;
        this.ddl = ddl;
    }

    /**
     * aggType 仅 AGGREGATE 模型使用；defaultValue 为可选列默认值。
     * 老请求不带这两个字段时行为与第一批完全一致。
     */
    public record ColumnRequest(String name, String type, String comment, Boolean nullable,
                                String aggType, String defaultValue) {
    }

    /**
     * REQ-DW-6：建表表单里的二级索引（与字段同页配置）。
     * type 只认 BITMAP / INVERTED / NGRAM_BF（校验与缺省见 TableCreateDdl#normalizeIndexes）；
     * BloomFilter 不是 Doris 建表索引（是表属性 bloom_filter_columns），表单不提供。
     */
    public record IndexRequest(String name, String column, String type, String comment) {
    }

    /**
     * partition 缺省 = 不分区；distributionColumns 缺省 = 第一个 key 列，
     * 与老请求体完全兼容。
     */
    public record CreateTableRequest(Long projectId, String database, String table, String comment,
                                     List<ColumnRequest> columns, List<String> keys,
                                     Integer buckets, Integer replication, String model,
                                     TableCreateDdl.PartitionDef partition,
                                     List<String> distributionColumns,
                                     String storageMedium, String storageCooldownTime,
                                     // REQ-DW-6：表单内二级索引；老请求不带 = 无索引，行为不变
                                     List<IndexRequest> indexes,
                                     // BUG-TC4-1：生产库建表必须写原因并挂审批，与 alter 族同口径
                                     String reason) {
    }

    public record CreateDatabaseRequest(Long projectId, String database, String comment) {
    }

    /**
     * SQL 模式建表请求体（预览与执行共用）。
     * targetTable 只用于「SQL 表名与表单不一致」的提示，实际以 SQL 为准。
     */
    /** {@code reason}：目标库是生产库（{@code *_prd}）时必填，用于挂审批工单（BUG-TC4-1）。 */
    public record SqlCreateRequest(Long projectId, String database, String sql, String targetTable,
                                   String reason) {
    }

    // ---------------------------------------------------------------- 浏览

    /**
     * 库列表。默认仍返回数组（兼容既有脚本）；带 {@code withMeta=true} 时返回
     * {@code {databases, metadataSource, degraded, metadataCached}}，页面据此展示降级提示。
     */
    @GetMapping("/databases")
    @RequireAtom("query:run")
    public Object databases(@RequestParam(required = false) Long projectId,
                            @RequestParam(defaultValue = "false") boolean withMeta) {
        Long connection = connectionOf(projectId);
        MetadataReader.Read<List<Map<String, Object>>> read = metadata.databases(
                projectId, () -> databasesDirect(connection));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : read.data()) {
            String name = text(row, "database");
            if (name == null || SYSTEM_DBS.contains(name)) {
                continue;
            }
            // 跨租户隔离：非默认租户只返回自己项目名下的库（外部审计 P0-1）
            if (!scope.owns(name)) {
                continue;
            }
            rows.add(row);
        }
        if (!withMeta) {
            return rows;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("databases", rows);
        out.putAll(read.meta());
        return out;
    }

    /** 直连 Doris 的库列表（Gravitino 不可用/未启用时的原有逻辑）。 */
    private List<Map<String, Object>> databasesDirect(Long connection) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : doris.queryAs(connection,
                "select table_schema, table_name from information_schema.tables")) {
            String schema = text(row, "TABLE_SCHEMA");
            if (schema == null || SYSTEM_DBS.contains(schema)) {
                continue;
            }
            counts.merge(schema, 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : doris.queryAs(connection, "show databases")) {
            String name = firstValue(row);
            if (name == null || SYSTEM_DBS.contains(name)) {
                continue;
            }
            if (!scope.owns(name)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("database", name);
            item.put("tables", counts.getOrDefault(name, 0));
            out.add(item);
        }
        return out;
    }

    @GetMapping
    @RequireAtom("query:run")
    public Object tables(@RequestParam(required = false) Long projectId,
                         @RequestParam String database,
                         @RequestParam(defaultValue = "false") boolean withMeta,
                         HttpServletRequest request) {
        requireName(database, "库名");
        requireSystemSchemaAccess(database, request);
        scope.requireDatabase(database);
        Long connection = connectionOf(projectId);
        MetadataReader.Read<List<Map<String, Object>>> read = metadata.tables(
                projectId, database, () -> tablesDirect(connection, database));
        if (!withMeta) {
            return read.data();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", read.data());
        out.putAll(read.meta());
        return out;
    }

    /** 直连 Doris 的表列表（Gravitino 不可用/未启用时的原有逻辑）。 */
    private List<Map<String, Object>> tablesDirect(Long connection, String database) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : doris.queryAs(connection,
                "select table_schema, table_name, table_comment, table_rows "
                        + "from information_schema.tables")) {
            String schema = text(row, "TABLE_SCHEMA");
            if (!database.equals(schema)) {
                continue;
            }
            String name = text(row, "TABLE_NAME");
            if (name == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", name);
            item.put("comment", text(row, "TABLE_COMMENT"));
            item.put("rows", row.get("TABLE_ROWS"));
            byName.put(name, item);
        }
        List<Map<String, Object>> showRows;
        try {
            showRows = doris.queryAs(connection, "show tables from `" + database + "`");
        } catch (RuntimeException e) {
            // D58：原来这里只认「Unknown database / does not exist」两种写法，其余一律 502 带原文；
            // 统一走 DorisErrors：库不存在 404、权限 403、连不上才 502，且都不回显原文。
            // 文案沿用既有契约「库不存在: <db>」（docs/交接-表管理-20261025.md 第 9 行）
            throw DorisErrors.toApiException(e, "查看库 " + database + " 的表清单",
                    "库不存在: " + database);
        }
        for (Map<String, Object> row : showRows) {
            String name = firstValue(row);
            if (name == null) {
                continue;
            }
            Map<String, Object> item = byName.get(name);
            if (item == null) {
                item = new LinkedHashMap<>();
                item.put("table", name);
                item.put("comment", null);
                item.put("rows", null);
            }
            item.put("database", database);
            byName.put(name, item);
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 表结构。返回体在原有 {database, table, columns, ddl} 上追加
     * metadataSource / degraded / metadataCached（纯新增字段，不影响既有调用方）。
     *
     * <p>{@code direct=true} 强制直连 Doris。表编辑弹窗必须用它：Gravitino 的列类型是
     * 归一化类型（BIGINT→long、VARCHAR(64)→string），直接回填会把「只改注释」变成
     * 改类型（VARCHAR(64) 被放宽成 STRING）。展示仍走统一元数据，DDL 生成保持原样。</p>
     */
    @GetMapping("/{database}/{table}")
    @RequireAtom("query:run")
    public Map<String, Object> detail(@PathVariable String database, @PathVariable String table,
                                      @RequestParam(required = false) Long projectId,
                                      @RequestParam(defaultValue = "false") boolean direct,
                                      HttpServletRequest request) {
        requireName(database, "库名");
        requireName(table, "表名");
        requireSystemSchemaAccess(database, request);
        scope.requireDatabase(database);
        Long connection = connectionOf(projectId);
        if (direct) {
            return detailDirect(connection, database, table);
        }
        MetadataReader.Read<Map<String, Object>> read = metadata.table(
                projectId, database, table, () -> detailDirect(connection, database, table));
        Map<String, Object> out = new LinkedHashMap<>(read.data());
        out.putAll(read.meta());
        return out;
    }

    /** 直连 Doris 的表结构（Gravitino 不可用/未启用时的原有逻辑）。 */
    private Map<String, Object> detailDirect(Long connection, String database, String table) {
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> descRows;
        try {
            descRows = doris.queryAs(connection, "desc `" + database + "`." + "`" + table + "`");
        } catch (RuntimeException e) {
            // 表/库不存在是用户输入问题，不该按「Doris 不可用 502」报（D58：其余类别同样归一化，
            // 原文不再外泄；文案沿用既有契约「表不存在: db.t」）
            throw DorisErrors.toApiException(e, "查看表 " + database + "." + table + " 的结构",
                    "表不存在: " + database + "." + table);
        }
        for (Map<String, Object> row : descRows) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", text(row, "Field"));
            col.put("type", text(row, "Type"));
            col.put("nullable", "Yes".equalsIgnoreCase(text(row, "Null")));
            col.put("key", text(row, "Key"));
            col.put("comment", text(row, "Comment"));
            columns.add(col);
        }
        if (columns.isEmpty()) {
            throw ApiException.notFound("表不存在或没有字段: " + database + "." + table);
        }
        String ddl = "";
        for (Map<String, Object> row : doris.queryAs(connection,
                "show create table `" + database + "`." + "`" + table + "`")) {
            ddl = String.valueOf(row.values().stream().skip(1).findFirst().orElse(""));
            break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", database);
        out.put("table", table);
        out.put("columns", columns);
        out.put("ddl", ddl);
        return out;
    }

    /** 预览前 N 行；只读，走查询服务的统一只读校验。 */
    @PostMapping("/{database}/{table}/preview")
    @RequireAtom("query:run")
    public Map<String, Object> preview(@PathVariable String database, @PathVariable String table,
                                       @RequestParam(required = false) Long projectId,
                                       @RequestParam(defaultValue = "20") int limit,
                                       HttpServletRequest request) {
        requireName(database, "库名");
        requireName(table, "表名");
        requireSystemSchemaAccess(database, request);
        scope.requireDatabase(database);
        int rows = Math.max(1, Math.min(100, limit));
        DorisQueryService.Result result = query.rawQuery(projectId,
                "select * from `" + database + "`.`" + table + "` limit " + rows, rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", result.columns());
        out.put("rows", result.rows());
        out.put("rowCount", result.rowCount());
        return out;
    }

    // ---------------------------------------------------------------- DDL

    @PostMapping("/databases")
    @RequireAtom("table:ddl")
    public Map<String, Object> createDatabase(@RequestBody CreateDatabaseRequest req,
                                              HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String database = requireName(req.database(), "库名");
        // BUG-TC3-1：跨租户一律 404、不泄露存在性（docs/byid-tenant-audit.md）。
        // 原来这里直接 scope.requireDatabase()，等于把「新建库」锁死在本项目已登记的
        // dev/prd 两个环境库上——表管理的「新建库」按钮因此只能「初始化」。
        boolean registered = scope.owns(database);
        if (!registered && !scope.defaultTenant()) {
            // REQ-DW-6：附加库放行，但要按顺序过两道闸：
            //   ① 先认别人：有归属证据且不是本租户 → 404（与 requireDatabase 同文案，不泄露）；
            //   ② 再认命名：库名 = 项目业务英文名_后缀（docs/部门与项目建库-20261023.md 同族规则）。
            // 默认租户保持老宽松行为：owns() 恒真，历史脚本仍可按任意登记名初始化。
            if (scope.belongsToOtherTenant(database)) {
                throw ApiException.notFound("库不存在: " + database);
            }
            requireExtraDatabaseName(req.projectId(), database);
        }
        Long connection = connectionOf(req.projectId());
        boolean exists = doris.databaseExists(connection, database);
        if (!exists) {
            try {
                doris.exec(connection, "create database if not exists `" + database + "`");
            } catch (RuntimeException e) {
                // D63：建库侧同一根因（例如库名撞上 Doris 保留字、权限不足）也不能透传原文
                throw dorisFailure(e, database, null);
            }
        }
        if (!registered) {
            // 附加库登记进 platform.project_database（V120）：TenantDataScope 的所有权读取
            // 并入本表后，库列表/建表/预览等数据面无需逐点改造
            registerProjectDatabase(req.projectId(), database);
        }
        sessions.audit(user(http), "TABLE_CREATE_DATABASE", database,
                (exists ? "already exists" : "created") + (registered ? "" : " (附加库登记)"));
        // 库列表来自 Gravitino 缓存：建库成功后立即失效，下一次读取能立刻看到新库
        metadata.invalidateProject(req.projectId());
        return Map.of("ok", true, "database", database, "created", !exists);
    }

    /**
     * REQ-DW-6：附加库命名规则 = 业务英文名 + "_" + 后缀（环境库是 business_dev /
     * business_prd，附加库沿用同一前缀，库名一眼能认出归属项目；部门表前缀 gx_ 是部门级、
     * 会同部门多项目撞名，不能当库前缀）。项目不存在 → 404；历史项目没有 business → 400。
     */
    private void requireExtraDatabaseName(Long projectId, String database) {
        if (projectId == null) {
            throw ApiException.badRequest("新建附加库必须指定项目：库名 = 业务英文名_后缀");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select business from platform.project
                 where id = ? and tenant_id = ?
                """, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
        Object raw = rows.get(0).get("business");
        String business = raw == null ? "" : String.valueOf(raw).trim();
        if (business.isEmpty()) {
            throw ApiException.badRequest("该项目没有业务英文名（历史项目），无法派生附加库命名");
        }
        String prefix = business.toLowerCase(java.util.Locale.ROOT) + "_";
        String lower = database.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith(prefix) || lower.length() <= prefix.length()) {
            throw ApiException.badRequest("附加库名必须是 " + business + "_后缀（如 "
                    + business + "_report）");
        }
    }

    /**
     * REQ-DW-6：附加库登记（库名全局唯一，lower 判重）。并发撞名时后到的 insert 被唯一索引
     * 挡下（do nothing → 影响 0 行）——此时 Doris 里的库可能是别人刚建的，登记不能算我的，
     * 按 409「库名已被占用」报出（口径同项目业务名全局唯一）。
     */
    private void registerProjectDatabase(Long projectId, String database) {
        int inserted = jdbc.update("""
                insert into platform.project_database (tenant_id, project_id, database_name)
                values (?, ?, ?)
                on conflict (lower(database_name)) do nothing
                """,
                com.lab.dataworks.auth.TenantScope.current(), projectId, database);
        if (inserted == 0) {
            throw ApiException.conflict("库名已被占用: " + database);
        }
    }

    /**
     * 删库（BUG-TC1-3①）。此前只有建库入口没有删库入口：
     * {@code DELETE /api/tables/databases/<db>} 会被 Spring 匹配成删表
     * （database="databases"）→ Doris 报 {@code Unknown database 'databases'} → 502 透传。
     * 现在给出正式入口：confirm 必须等于库名；<b>只允许删空库</b>（非空 400 报表数，
     * cascade 属需求另有评审确认再加）；走 table:ddl + 租户库归属校验；审计
     * {@code TABLE_DROP_DATABASE} 成功/失败都落（口径同删表）；成功后失效元数据缓存，
     * 库列表立刻一致（建库那条已这么做）。
     */
    @DeleteMapping("/databases/{database}")
    @RequireAtom("table:ddl")
    public Map<String, Object> dropDatabase(@PathVariable String database,
                                            @RequestParam(required = false) Long projectId,
                                            @RequestParam String confirm,
                                            HttpServletRequest http) {
        requireName(database, "库名");
        // 系统库一律不可删（比 requireSystemSchemaAccess 更严：平台管理员也不行）
        if (SYSTEM_DBS.contains(database.toLowerCase(java.util.Locale.ROOT))
                || DataGuardService.isSystemSchema(database)) {
            throw ApiException.badRequest("系统库不可删除: " + database);
        }
        requireSystemSchemaAccess(database, http);
        scope.requireDatabase(database);
        if (!database.equals(confirm)) {
            throw ApiException.badRequest("删除确认需要与库名一致");
        }
        Long connection = connectionOf(projectId);
        // 快照口径：删库前记录当时可见的表清单；判空也用它——非空一律拒，
        // 不给「顺手删库带数据」留口子（审计里要能回答「删掉的是什么」）
        List<String> tablesBefore = listTables(connection, database);
        if (!tablesBefore.isEmpty()) {
            throw ApiException.badRequest("库非空（" + tablesBefore.size()
                    + " 张表），请先删表再删库: " + database);
        }
        String actor = user(http);
        String ip = com.lab.dataworks.auth.AuthInterceptor.clientIp(http);
        String detail = "projectId=" + (projectId == null ? "-" : projectId)
                + "; database=" + database + "; tables=0";
        try {
            doris.exec(connection, "drop database `" + database + "`");
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sessions.audit(actor, "TABLE_DROP_DATABASE", database,
                    cut(detail + "; dropError=" + cut(reason, 300), 2000), "FAILED", ip);
            throw e;
        }
        sessions.audit(actor, "TABLE_DROP_DATABASE", database, cut(detail, 2000), "SUCCESS", ip);
        metadata.invalidateProject(projectId);
        return Map.of("ok", true, "database", database);
    }

    /** 删库判空/快照：库内表清单（排序截断进审计用）；库不存在等错误按 Doris 原样上抛。 */
    private List<String> listTables(Long connection, String database) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : doris.queryAs(connection,
                "show tables from `" + database + "`")) {
            String name = firstValue(row);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        names.sort(java.util.Comparator.naturalOrder());
        return names;
    }

    @PostMapping
    @RequireAtom("table:ddl")
    public Map<String, Object> create(@RequestBody CreateTableRequest req,
                                      HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String database = requireName(req.database(), "库名");
        String table = requireName(req.table(), "表名");
        scope.requireDatabase(database);
        // 校验与 DDL 生成统一走 TableCreateDdl，和 /preview 完全一致；
        // 副本数预检需要当前存活 BE 数（监控查询失败时返回 null，自动跳过）
        TableCreateDdl.Result plan = TableCreateDdl.generate(database, table, req.comment(),
                req.columns(), req.keys(), req.buckets(), req.replication(), req.model(),
                req.partition(), req.distributionColumns(), aliveBackends(req.projectId()),
                req.storageMedium(), req.storageCooldownTime(),
                coldStorageAvailable(req.projectId()), req.indexes());
        // BUG-TC4-1：目标库是生产库（*_prd）时，建表与 alter 族走同一道闸门：
        // 没写原因 → 400；写了原因 → 挂 PENDING_APPROVAL 工单，审批通过后才下发
        TableDdlService.ProductionGate gate = ddl.gateProductionDdl("CREATE_TABLE",
                req.projectId(), database, table, plan.ddl(), req.reason(), http);
        if (gate.queued() != null) {
            return gate.queued();
        }
        Long connection = connectionOf(req.projectId());
        // D60：重复建表要 409「表已存在」，不能把 Doris 的 already exists 包成 502。
        // 放在 generate() 之后：入参不合法仍是 400，不被冲突抢掉。
        requireTableAbsent(connection, database, table);
        try {
            doris.exec(connection, plan.ddl());
        } catch (RuntimeException e) {
            // D60/D63：Doris 的执行异常一律过一遍映射表（already exists→409、
            // Unknown table/database→404、语法/类型→400、连不上与其它→502 但消毒文案）
            throw dorisFailure(e, database, table);
        }
        sessions.audit(user(http), "TABLE_CREATE", database + "." + table,
                "columns=" + plan.columnCount() + " keys=" + plan.keys()
                        + " model=" + plan.model());
        // 建表成功后让该 catalog 的库/表/表结构缓存全部失效
        metadata.invalidateProject(req.projectId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("database", database);
        out.put("table", table);
        out.put("ddl", plan.ddl());
        out.put("warnings", plan.warnings());
        out.put("env", gate.env());          // BUG-TC4-1：回显落点环境，验收与前端都能核对
        out.put("executed", true);
        return out;
    }

    /**
     * 建表 DDL 预览：与 {@link #create} 共用同一套校验和生成逻辑，只生成不执行。
     * 返回 {ddl, warnings}；参数不合法同样返回 400，文案直接指出问题。
     */
    @PostMapping("/preview")
    @RequireAtom("table:ddl")
    public Map<String, Object> previewCreate(@RequestBody CreateTableRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String database = requireName(req.database(), "库名");
        String table = requireName(req.table(), "表名");
        scope.requireDatabase(database);
        // 预览与建表走完全相同的预检（含存活 BE 数查询、二级索引校验）
        TableCreateDdl.Result plan = TableCreateDdl.generate(database, table, req.comment(),
                req.columns(), req.keys(), req.buckets(), req.replication(), req.model(),
                req.partition(), req.distributionColumns(), aliveBackends(req.projectId()),
                req.storageMedium(), req.storageCooldownTime(),
                coldStorageAvailable(req.projectId()), req.indexes());
        // 只校验项目归属，不执行任何 DDL
        connectionOf(req.projectId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ddl", plan.ddl());
        out.put("warnings", plan.warnings());
        return out;
    }

    /**
     * SQL 模式预览：只解析 + 校验，不执行任何 DDL。
     *
     * <p>返回 {ddl, table, model, unsupported, warnings}；model 为 null 表示
     * 该 SQL 无法转成表单（例如 CREATE TABLE ... LIKE）。语法/权限问题同样 400，
     * 文案直接指出具体片段。</p>
     */
    @PostMapping("/sql/preview")
    @RequireAtom("table:ddl")
    public Map<String, Object> sqlPreview(@RequestBody SqlCreateRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String database = requireName(req.database(), "库名");
        // 预览也要过租户库归属：不能让用户借预览探测别人库里的结构
        scope.requireDatabase(database);
        connectionOf(req.projectId());
        TableSqlParser.Result parsed = TableSqlParser.parse(database, req.sql(), req.targetTable());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ddl", parsed.ddl());
        out.put("table", parsed.table());
        out.put("model", parsed.model());
        out.put("unsupported", parsed.unsupported());
        out.put("warnings", parsed.warnings());
        return out;
    }

    /**
     * SQL 模式建表：与表单建表同一套权限（table:ddl）、租户库归属与连接解析。
     * DDL 原样下发（不做改写），审计 action = TABLE_CREATE_SQL，detail 记录来源 SQL 摘要。
     */
    @PostMapping("/sql")
    @RequireAtom("table:ddl")
    public Map<String, Object> sqlCreate(@RequestBody SqlCreateRequest req,
                                         HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String database = requireName(req.database(), "库名");
        scope.requireDatabase(database);
        Long connection = connectionOf(req.projectId());
        TableSqlParser.Result parsed = TableSqlParser.parse(database, req.sql(), req.targetTable());
        // BUG-TC4-1：SQL 模式建表同样过生产库闸门（原来只有 alter 族有）
        TableDdlService.ProductionGate gate = ddl.gateProductionDdl("CREATE_TABLE_SQL",
                req.projectId(), database, parsed.table(), parsed.ddl(), req.reason(), http);
        if (gate.queued() != null) {
            return gate.queued();
        }
        // D60/D63 同源问题在 SQL 模式：这里**不做预检**（SQL 里可能写了 IF NOT EXISTS，
        // 预检会把它误判成冲突），但执行异常同样要过映射表：
        // already exists→409、语法错（no viable alternative…）→400、连不上→502 消毒文案。
        try {
            doris.exec(connection, parsed.ddl());
        } catch (RuntimeException e) {
            throw dorisFailure(e, database, parsed.table());
        }
        sessions.audit(user(http), "TABLE_CREATE_SQL", database + "." + parsed.table(),
                "source=SQL; sql=" + summarize(req.sql()));
        metadata.invalidateProject(req.projectId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("database", database);
        out.put("table", parsed.table());
        out.put("ddl", parsed.ddl());
        out.put("warnings", parsed.warnings());
        // 即使执行成功也把「表单不支持」带回去，前端可提示后续用表单编辑会丢内容
        out.put("unsupported", parsed.unsupported());
        out.put("env", gate.env());
        out.put("executed", true);
        return out;
    }

    /** 批量删表单项（BUG-TC6-14）：与单项同一套 requireName/scope 闸门。 */
    public record BulkDropItem(String database, String table) {
    }

    /**
     * 批量删表请求体（BUG-TC6-14）。
     * confirm 必须逐字符等于按请求顺序用英文逗号连接的 {@code db.table} 全清单
     * ——「一次性总确认」仍然要手打全部表名，强度与单项删除的 confirm 同源。
     */
    public record BulkDropRequest(Long projectId, List<BulkDropItem> items, String confirm) {
    }

    /**
     * 删表（S12）。审计 detail 不再是 null：带库表全名、projectId、删前的
     * SHOW CREATE TABLE 快照；快照取不到时把原因写进 detail，不允许静默。
     * 删表失败也补一条 {@code result=FAILED} 的审计（try/catch 后原异常照常上抛，
     * 状态码口径不变），否则「谁在什么时候删了什么、删没删掉」在审计里对不上账。
     */
    @DeleteMapping("/{database}/{table}")
    @RequireAtom("table:ddl")
    public Map<String, Object> drop(@PathVariable String database, @PathVariable String table,
                                    @RequestParam(required = false) Long projectId,
                                    @RequestParam String confirm,
                                    @RequestParam(required = false) String reason,
                                    HttpServletRequest http) {
        requireName(database, "库名");
        requireName(table, "表名");
        // BUG-TC1-3②：保留段守卫。删库有正式入口后，正常小写路径会先被
        // /databases/{database} 接走；这里兜住大小写变体（路径匹配区分大小写，
        // DELETE /api/tables/DATABASES/x 仍会落到本方法），不再让 Doris 的
        // "Unknown database 'databases'" 以 502 透传给调用方。
        if ("databases".equalsIgnoreCase(database)) {
            throw new ApiException(405,
                    "方法不允许：删库请用 DELETE /api/tables/databases/{name}?confirm={name}");
        }
        scope.requireDatabase(database);
        if (!table.equals(confirm)) {
            throw ApiException.badRequest("删除确认需要与表名一致");
        }
        Long connection = connectionOf(projectId);
        // 生产闸门在 dropOne 内（BUG-TC6-5① × BUG-TC4-1 × BUG-TC6-14：批量与单项共用）；
        // 返回 env/executed 保持 main 上既有单项响应形状（前端按 env 展示落点）
        String landingEnv = dropOne(connection, projectId, database, table, user(http),
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        // 删表成功后失效：否则 30–60 秒内列表还会显示已删除的表
        metadata.invalidateProject(projectId);
        Map<String, Object> dropped = new LinkedHashMap<>();
        dropped.put("ok", true);
        dropped.put("database", database);
        dropped.put("table", table);
        dropped.put("env", landingEnv);      // 能走到这里必为非生产落点（PROD 已在 dropOne 内 400）
        dropped.put("executed", true);
        return dropped;
    }

    /**
     * 批量删表（BUG-TC6-14）。
     *
     * <p>闸门口径（卡内硬要求「批量与单项必须同闸门、同文案、同状态机校验」）：</p>
     * <ul>
     *   <li>权限点 {@code table:ddl}、库归属 {@code scope.requireDatabase}、名字校验与
     *       单项 {@link #drop} 完全一致；实际删除走同一个 {@link #dropOne}，
     *       每项各留一条 TABLE_DROP 的 SUCCESS/FAILED 审计（含删前快照）；</li>
     *   <li><b>预检</b>（空清单 / 超 100 / 重复项 / 非法名 / 确认串不符）发生在删除任何
     *       一张表之前，整体 400，绝不部分执行；</li>
     *   <li>预检通过后逐项执行，单项执行失败（表不存在等）不阻断其余项，
     *       结果逐条回传 {database, table, ok, message}，与前端表格一一对应；</li>
     *   <li>确认串要求手打全部 {@code db.table}（逗号连接、顺序同请求体）——这是
     *       「迁移 50 张表 = 50 次弹窗」与「防误删」之间的折中：确认动作只做一次，
     *       但确认内容仍是全部表名本身。</li>
     * </ul>
     */
    @PostMapping("/bulk-drop")
    @RequireAtom("table:ddl")
    public Map<String, Object> bulkDrop(@RequestBody(required = false) BulkDropRequest req,
                                        HttpServletRequest http) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw ApiException.badRequest("批量删除需要至少选择一张表");
        }
        if (req.items().size() > 100) {
            throw ApiException.badRequest("单次批量删除最多 100 张表，请分批操作");
        }
        // 预检阶段：逐字符校验 + 去重 + 生成确认清单。任何一项非法整批 400，不动任何表。
        List<String> fulls = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (BulkDropItem item : req.items()) {
            if (item == null) {
                throw ApiException.badRequest("批量项不能为空");
            }
            String database = requireName(item.database(), "库名");
            String table = requireName(item.table(), "表名");
            scope.requireDatabase(database);
            String full = database + "." + table;
            if (!seen.add(full.toLowerCase(java.util.Locale.ROOT))) {
                throw ApiException.badRequest("批量项重复: " + full);
            }
            fulls.add(full);
        }
        String expected = String.join(",", fulls);
        if (req.confirm() == null || !req.confirm().trim().equals(expected)) {
            throw ApiException.badRequest("批量删除确认需要逐字符输入全部表名（英文逗号分隔、顺序一致）");
        }
        Long connection = connectionOf(req.projectId());
        String actor = user(http);
        String ip = com.lab.dataworks.auth.AuthInterceptor.clientIp(http);
        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        for (int i = 0; i < req.items().size(); i++) {
            BulkDropItem item = req.items().get(i);
            String database = item.database();
            String table = item.table();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("database", database);
            one.put("table", table);
            try {
                dropOne(connection, req.projectId(), database, table, actor, ip);
                one.put("ok", true);
                succeeded++;
            } catch (RuntimeException e) {
                // 与单项同口径：Doris 失败先过映射表（404/409/400/502 消毒文案），
                // 非 ApiException 的意外异常也要显式记日志并给出可读 message，不静默。
                ApiException ae = e instanceof ApiException x ? x
                        : mapUnexpected(e, database, table);
                one.put("ok", false);
                one.put("code", ae.getStatus());
                one.put("message", ae.getMessage());
            }
            results.add(one);
        }
        if (succeeded > 0) {
            metadata.invalidateProject(req.projectId());
        }
        // 整批各记一条汇总审计；单项 SUCCESS/FAILED 明细仍由 dropOne 逐条落账
        sessions.audit(actor, "TABLE_DROP_BULK", String.valueOf(fulls.size()),
                "projectId=" + (req.projectId() == null ? "-" : req.projectId())
                        + "; ok=" + succeeded + "; failed=" + (fulls.size() - succeeded)
                        + "; tables=" + cut(expected, 1500),
                succeeded == fulls.size() ? "SUCCESS"
                        : (succeeded == 0 ? "FAILED" : "PARTIAL"), ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", fulls.size());
        out.put("succeeded", succeeded);
        out.put("failed", fulls.size() - succeeded);
        out.put("results", results);
        return out;
    }

    /** 意外异常兜底：进日志 + 对外可读文案（不允许把裸驱动异常透给批量结果行）。 */
    private static ApiException mapUnexpected(RuntimeException e, String database, String table) {
        log.warn("批量删表出现未归类异常 {}: {}.{}", e.getClass().getName(), database, table, e);
        return new ApiException(502, "数仓执行失败，未能完成该操作（原始错误已记入后端日志）");
    }

    /**
     * 单项删表的执行体（{@link #drop} 与 {@link #bulkDrop} 共用，BUG-TC6-14）。
     *
     * <p>BUG-TC6-5① × BUG-TC4-1（CONFLICT-WM1 裁决，采 main 侧较新口径并把生产判定拓宽）：
     * 生产闸门放在本方法内，批量与单项天然同闸门、同文案、同状态机校验（BUG-TC6-14 卡内
     * 硬要求）。生产库直接删表一律 400 指向「SQL 变更」单（D53：建单即备份、审批后执行、
     * 可回滚）；BUG-TC4-1 曾让删表走 gateProductionDdl 的 DROP_TABLE 工单通道——那是第二套
     * 不带备份/回滚的审批，作废，本方法不挂工单，{@code drop} 的 reason 参数保留仅为兼容
     * 既有调用方。生产判定 = 项目 prod_database 列（权威，覆盖自定义库名）+ *_prd 命名兜底
     * （与 TableDdlService.targetForGate 同一份口径，走 gateEnv 只读判定）。拒绝也要落审计
     * （REJECTED），否则「谁试过删生产表」在审计里对不上账。批量路径下这项拒绝按逐项
     * 400 回传（code=400 + 同一文案），不阻断其余项。</p>
     *
     * <p>快照→drop→审计（FAILED 留原文再上抛映射后的异常 / SUCCESS）；不校验 confirm，
     * 校验留在各入口（单项=表名、批量=全清单串），保证两个入口闸门语义各自明确。
     * 返回落点环境；能走到返回值必为非生产落点。</p>
     */
    private String dropOne(Long connection, Long projectId, String database, String table,
                           String actor, String ip) {
        String full = database + "." + table;
        String landingEnv = ddl.gateEnv(projectId, database, table);
        if (landingEnv == null) {
            // 单测里 TableDdlService 是 mock：按命名约定兜底，行为与真闸门一致
            landingEnv = isProdDatabase(database) ? "PROD" : "DEV";
        }
        if ("PROD".equals(landingEnv)) {
            sessions.audit(actor, "TABLE_DROP", full,
                    "projectId=" + (projectId == null ? "-" : projectId)
                            + "; gate=PROD_CHANGE_REQUEST_REQUIRED; 生产库删表被拒",
                    "REJECTED", ip);
            throw ApiException.badRequest("生产库 " + database + " 删表须走变更单：DELETE 已拒绝，"
                    + "请到「SQL 变更」对 " + full + " 建 DROP TABLE 变更（建单自动备份、审批后执行、支持回滚）");
        }
        // 先取删前快照再执行：drop 之后 SHOW CREATE TABLE 就查不到了
        String snapshot = dropSnapshot(connection, database, table);
        String detail = "projectId=" + (projectId == null ? "-" : projectId)
                + "; table=" + full + "; " + snapshot;
        try {
            doris.exec(connection, "drop table `" + database + "`.`" + table + "`");
        } catch (RuntimeException e) {
            String rawReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 审计里留**原文**（内部可查面，是恢复依据）；对外走映射表：
            // Unknown table/database → 404，不再 502 透传 errCode/detailMessage（D63）。
            sessions.audit(actor, "TABLE_DROP", full,
                    cut(detail + "; dropError=" + cut(rawReason, 300), 2000), "FAILED", ip);
            throw dorisFailure(e, database, table);
        }
        sessions.audit(actor, "TABLE_DROP", full, cut(detail, 2000), "SUCCESS", ip);
        return landingEnv;
    }

    /**
     * 下发一条 Doris DDL。D58：失败统一按语义映射状态码（不存在 404 / 权限 403 / 语法 400 /
     * 连不上才 502），原文只写日志，不进响应。
     */
    private void execDoris(Long connection, String statement, String action) {
        try {
            doris.exec(connection, statement);
        } catch (RuntimeException e) {
            log.warn("doris ddl failed action={} raw={}", action, DorisErrors.raw(e));
            throw DorisErrors.toApiException(e, action);
        }
    }

    /** S12：删表前的 DDL 快照（压掉换行、截断）；取不到就把原因写进串里，不静默留空。 */
    private String dropSnapshot(Long connection, String database, String table) {
        try {
            for (Map<String, Object> row : doris.queryAs(connection,
                    "show create table `" + database + "`.`" + table + "`")) {
                String ddl = String.valueOf(row.values().stream().skip(1).findFirst().orElse(""));
                if (!ddl.isBlank() && !"null".equalsIgnoreCase(ddl)) {
                    return "ddlSnapshot=" + cut(ddl.replaceAll("\\s+", " "), 1500);
                }
                break;
            }
            return "ddlSnapshot=unavailable(SHOW CREATE TABLE 未返回内容)";
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "ddlSnapshot=unavailable(" + cut(reason, 200) + ")";
        }
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 当前 Doris 存活 BE 数，用于副本数预检。
     *
     * <p>走统一的查询通道（内置 = MCP，外部 = 项目 JDBC）；SHOW 属于只读语句，
     * 两边都放行。任何失败（权限不足、通道不可用、版本差异）都返回 null，
     * 让预检自动跳过——监控查询不能阻断建表。</p>
     */
    /** 025：所选数仓数据源是否配置了冷存储（warehouse_connection.default_storage_type）。 */
    private Boolean coldStorageAvailable(Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select warehouse_datasource_id from platform.project
                     where id = ? and tenant_id = ?
                    """, projectId, com.lab.dataworks.auth.TenantScope.current());
            if (rows.isEmpty()) {
                return null;
            }
            Object raw = rows.get(0).get("warehouse_datasource_id");
            if (raw == null) {
                // 老项目回退内置 Doris（无冷存储）
                return false;
            }
            WarehouseResolver.Storage storage =
                    warehouseResolver.storageForDatasource(((Number) raw).longValue());
            return storage != null && storage.type() != null
                    && !"none".equalsIgnoreCase(storage.type());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer aliveBackends(Long projectId) {
        try {
            DorisQueryService.Result result = query.query(projectId, "show backends", 100);
            int aliveIndex = -1;
            List<String> columns = result.columns();
            for (int i = 0; i < columns.size(); i++) {
                if ("Alive".equalsIgnoreCase(columns.get(i))) {
                    aliveIndex = i;
                    break;
                }
            }
            // 拿不到 Alive 列或结果为空 = 监控数据不可用，跳过预检而不是当成 0 个 BE
            if (aliveIndex < 0 || result.rows().isEmpty()) {
                return null;
            }
            int alive = 0;
            for (List<String> row : result.rows()) {
                if (row == null || row.size() <= aliveIndex) {
                    continue;
                }
                String value = row.get(aliveIndex);
                if (value != null) {
                    String text = value.trim();
                    if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                        alive++;
                    }
                }
            }
            return alive;
        } catch (RuntimeException e) {
            log.warn("读取 BE 存活数失败，跳过副本数预检: {}", e.getMessage());
            return null;
        }
    }

    /**
     * D60：建表前预检同名表（{@code POST /api/tables} 与 {@code /api/tables/sql} 共用）。
     *
     * <p>缺陷现场（TC-2 用例 TB-07b，v539 实测）：同一个库重复创建同名表，第二次拿到
     * {@code 502 {"message":"Doris 执行失败: errCode = 2, detailMessage = Table 'x' already
     * exists"}}。{@link DorisAdminService#exec} 把任何驱动异常都包成 502，于是「表已存在」
     * 这种纯客户端冲突被上报成网关故障。这里参照 {@code DorisAdminService.ensureDatabase}
     * 的既有做法先预检，直接给 409。</p>
     *
     * <p>预检本身查不动（外部 Doris 权限受限、SHOW 语句不支持等）<b>不能</b>把建表一起
     * 拖死：那种情况记一条 warn 继续走 exec，重复表由
     * {@link #dorisFailure} 兜底转 409。修 BUG 不引入新的失败面。</p>
     */
    private void requireTableAbsent(Long connection, String database, String table) {
        String existing = null;
        try {
            for (Map<String, Object> row : doris.queryAs(connection,
                    "show tables from `" + database + "` like '" + table + "'")) {
                String found = firstValue(row);
                if (found != null && found.equalsIgnoreCase(table)) {
                    existing = found;
                    break;
                }
            }
        } catch (RuntimeException e) {
            // 预检查不动 ≠ 表不存在：记一条 warn 继续走 exec，由执行结果判定
            log.warn("建表前 SHOW TABLES 预检失败，改由执行结果判定冲突（不阻断建表）: {}",
                    e.getMessage());
            return;
        }
        if (existing != null) {
            throw ApiException.conflict("表已存在: " + database + "." + table
                    + "，请换一个表名，或先删除该表");
        }
    }

    /**
     * D60 + D63：Doris 执行失败 → 平台状态码，**一张表定死，不散落 if**。
     *
     * <p>两条卡是同一根因：{@code DorisAdminService.exec} 把任何驱动异常都包成
     * {@code 502 "Doris 执行失败: errCode = 2, detailMessage = …"}（外部连接分支
     * 还额外拼上 {@code c.endpoint()} 内部地址）。于是：</p>
     * <ul>
     *   <li>「表不存在 / 库不存在 / 表已存在 / SQL 写错」这些<b>客户端可纠正</b>的错误
     *       回 5xx，调用方以为服务故障，不会去改请求；</li>
     *   <li>响应 message 原样透传 Doris 内部错误格式与内部地址（TC-5 判据 1/2/4）。</li>
     * </ul>
     *
     * <p>口径（与同仓库既有做法一致：{@code GET /api/tables/{db}/{t}} 已映射 404「表不存在」，
     * {@code TenantDataScope.requireDatabase} 已统一 404）：</p>
     * <ol>
     *   <li>{@code already exists} → <b>409</b>「表已存在: db.table」；</li>
     *   <li>{@code Unknown table} → <b>404</b>「表不存在: db.table」；</li>
     *   <li>{@code Unknown database} → <b>404</b>「库不存在: 库名」（优先取上游给的库名）；</li>
     *   <li>语法/类型（{@code no viable alternative} / {@code mismatched input} /
     *       {@code syntax} / {@code Unknown type}）→ <b>400</b>；</li>
     *   <li>连不上（refused / timed out / unknown host…）→ <b>502</b>，但只说「无法连接到数仓」，
     *       <b>不带</b>端点与驱动原文；</li>
     *   <li>其余 → <b>502</b>，message 换成中文概要，<b>原文进日志</b>（不静默丢）。</li>
     * </ol>
     *
     * <p>状态码与原文的对应关系全在日志里；审计侧（如 {@code drop} 的 {@code dropError=}）
     * 保留原文，那是内部可查面，不是对外响应。</p>
     */
    static ApiException dorisFailure(Throwable e, String database, String table) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String low = raw.toLowerCase(java.util.Locale.ROOT);
        String full = table == null || table.isBlank()
                ? String.valueOf(database) : database + "." + table;
        if (low.contains("already exists")) {
            return ApiException.conflict("表已存在: " + full + "，请勿重复创建（并发建表时同样按此口径拒绝）");
        }
        if (low.contains("unknown table")) {
            return ApiException.notFound("表不存在: " + full);
        }
        if (low.contains("unknown database")) {
            return ApiException.notFound("库不存在: " + quotedAfter(raw, "database", database));
        }
        if (low.contains("no viable alternative") || low.contains("mismatched input")
                || low.contains("syntax") || low.contains("unknown type")
                || low.contains("invalid column")) {
            return ApiException.badRequest(
                    "SQL 语法或字段类型不正确，请检查 DDL（原始错误已记入后端日志）");
        }
        if (low.contains("access denied for user") || low.contains("denied for user")
                || low.contains("permission denied") || low.contains("not authorized")) {
            // D63：权限不足是客户端能改对的（换账号/申请授权），必须 403；账号与内网地址不进响应
            log.warn("数仓权限不足（原文只进日志）: {}", raw);
            return new ApiException(403, "没有该库的访问权限（Doris 账号权限不足），请联系管理员授权");
        }
        if (low.contains("connection refused") || low.contains("communications link failure")
                || low.contains("connect timed out") || low.contains("connection timed out")
                || low.contains("unknown host") || low.contains("no route to host")) {
            log.warn("数仓连接失败: {}", raw);
            return new ApiException(502, "无法连接到数仓（Doris）：连不上，请检查数据源配置或稍后重试");
        }
        log.warn("Doris 执行失败（未归类，原文只进日志）: {}", raw);
        return new ApiException(502, "数仓执行失败，未能完成该操作（原始错误已记入后端日志）");
    }

    /** 从上游原文里取 {@code Unknown database 'xxx'} 的这个名字；取不到就回落到请求里的库名。 */
    private static String quotedAfter(String raw, String word, String fallback) {
        int at = raw.toLowerCase(java.util.Locale.ROOT).indexOf(word);
        if (at < 0) {
            return fallback;
        }
        int open = raw.indexOf('\'', at);
        int close = open < 0 ? -1 : raw.indexOf('\'', open + 1);
        return open >= 0 && close > open ? raw.substring(open + 1, close) : fallback;
    }

    /** 项目为空 = 平台内置；否则按项目选择的 DORIS 数据源解析（V98）。 */
    private Long connectionOf(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return warehouseResolver.connectionIdForProject(projectId);
    }

    /**
     * 生产库判定：与 TableDdlService 的 env 口径同一规则（库名 `_prd` 结尾即生产，
     * 见 targetFromRequest `db.toLowerCase().endsWith("_prd") ? "PROD" : "DEV"`）。
     * BUG-TC6-5① 用它决定删表是否必须走变更单；两处如果将来要改，必须一起改。
     */
    static boolean isProdDatabase(String database) {
        return database != null && database.toLowerCase(java.util.Locale.ROOT).endsWith("_prd");
    }

    private String requireName(String value, String label) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw ApiException.badRequest(label + "不合法（字母或下划线开头，只含字母/数字/下划线）: "
                    + value);
        }
        return value;
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            // Doris 返回的列名可能是小写（取决于驱动），按大小写不敏感再找一次
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue() == null ? null : String.valueOf(entry.getValue());
                }
            }
            return null;
        }
        return String.valueOf(value);
    }

    private String firstValue(Map<String, Object> row) {
        return row.values().stream().filter(java.util.Objects::nonNull)
                .map(String::valueOf).findFirst().orElse(null);
    }

    /** 审计里只留 SQL 摘要，避免把整段脚本塞进 detail。 */
    private String summarize(String sql) {
        String text = sql == null ? "" : sql.trim().replaceAll("\\s+", " ");
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    private String user(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    /**
     * D04：系统库（platform / __internal_schema / information_schema / mysql / sys）
     * 只有平台管理员可读。表管理的列表/结构/预览不走 SQL 网关，必须在这里单独挡住，
     * 否则默认租户的普通角色可以用 `/api/tables/{db}/{table}/preview` 读到审计行。
     */
    private void requireSystemSchemaAccess(String database, HttpServletRequest request) {
        if (DataGuardService.isSystemSchema(database)
                && !dataGuard.systemSchemaReadable(user(request))) {
            throw new ApiException(403,
                    "系统库仅平台管理员可读；审计数据请使用平台审计接口 /api/security/audit。");
        }
    }
}
