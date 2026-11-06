package com.lab.dataworks.datasource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcTimeouts;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.integration.ConnectorCatalog;
import com.lab.dataworks.metadata.GravitinoCatalogNaming;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.secret.SecretCipher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

@RestController
public class DataSourceController {

    private static final List<String> TYPES = List.of(
            "POSTGRESQL", "DORIS", "MYSQL", "SPARK", "S3FILE", "LOCALFILE", "MONGODB",
            "REDIS", "CLICKHOUSE", "ICEBERG");

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final ConnectorCatalog catalog;
    private final WarehouseResolver warehouseResolver;
    private final com.lab.dataworks.lake.LakeCatalogService lakeCatalogs;
    private final GravitinoCatalogSync gravitinoSync;
    private final GravitinoCatalogAdmin gravitinoAdmin;
    private final MetadataReader metadata;
    /** BUG-DEMO-DS-HYGIENE-1：内置 Doris 行的平台凭据（与 DorisQueryService 查询通路同源）。 */
    private final String builtinDorisUser;
    private final String builtinDorisPassword;

    public DataSourceController(JdbcTemplate jdbc, SessionService sessions,
                                SecretCipher cipher, ObjectMapper json,
                                ConnectorCatalog catalog, WarehouseResolver warehouseResolver,
                                com.lab.dataworks.lake.LakeCatalogService lakeCatalogs,
                                GravitinoCatalogSync gravitinoSync,
                                GravitinoCatalogAdmin gravitinoAdmin,
                                MetadataReader metadata,
                                @Value("${doris.user:dataworks}") String builtinDorisUser,
                                @Value("${doris.password:}") String builtinDorisPassword) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.cipher = cipher;
        this.json = json;
        this.catalog = catalog;
        this.warehouseResolver = warehouseResolver;
        this.lakeCatalogs = lakeCatalogs;
        this.gravitinoSync = gravitinoSync;
        this.gravitinoAdmin = gravitinoAdmin;
        this.metadata = metadata;
        this.builtinDorisUser = builtinDorisUser;
        this.builtinDorisPassword = builtinDorisPassword;
    }

    /**
     * 数据源请求。
     *
     * <p>V98：DORIS 数据源可以带默认存储（none / hdfs / s3 / sso + endpoint + path，sso 见 REQ-DW-5），
     * 后端写入它对应的 {@code warehouse_connection.default_storage_*}；项目不感知存储。</p>
     */
    /**
     * BUG-TC4-6：不再静默吞掉未知字段（acceptance-rules 第 4 条「不许静默」）。
     * 以前 body 里多带未声明的字段会 200 收单再丢掉，用户以为指定了某种语义，
     * 实际按默认路径执行；现在多一个字段就 400。
     *
     * <p>REQ-TC4-1：{@code env} 从「未知字段」转正为声明字段（V116 给
     * {@code platform.datasource} 加了同名列）。转正不放宽严格口径——
     * 取值只认 {@code DEV} / {@code PROD}，其他值由 {@link #normalizeEnv} 显式 400，
     * 不会退回「默默按 DEV 收单」。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record DataSourceRequest(String name, String type, String jdbcUrl,
                                    String username, String password,
                                    Map<String, Object> config, Long id,
                                    String storageType, String storageEndpoint,
                                    String storagePath, String env) {
    }

    /** 数据源环境取值（V116 / REQ-TC4-1）：只认这两个，其余 400。 */
    private static final List<String> ENVS = List.of("DEV", "PROD");

    /**
     * 环境归一化：没传 → {@code DEV}（兼容历史调用方与历史数据），
     * 大小写与首尾空白宽容（{@code " prod "} → {@code PROD}），
     * 非法值一律 400 并点名收到的是什么——不静默按 DEV 收单。
     */
    public static String normalizeEnv(String env) {
        if (env == null || env.isBlank()) {
            return "DEV";
        }
        String value = env.trim().toUpperCase(Locale.ROOT);
        if (!ENVS.contains(value)) {
            throw ApiException.badRequest("数据源环境只能是 DEV 或 PROD，收到: " + env.trim()
                    + "（DEV = 开发环境连接，PROD = 生产环境连接）");
        }
        return value;
    }

    /**
     * 行上已经存着的环境值。
     *
     * <p>只兜「没值」这一种：迁移前的历史行与内置数据源没有该列（或查询没选出该列），
     * 按 DEV 回显。已有取值原样大写返回，<b>不</b>把非法值洗成 DEV——
     * 数据脏不脏可以看出来，不能靠回显把它藏起来。</p>
     */
    static String envOf(Map<String, Object> row) {
        Object raw = row == null ? null : row.get("env");
        String value = raw == null ? "" : String.valueOf(raw).trim();
        return value.isEmpty() ? "DEV" : value.toUpperCase(Locale.ROOT);
    }

    @GetMapping("/api/projects/{projectId}/datasources")
    public List<Map<String, Object>> list(@PathVariable long projectId) {
        // 项目不属于当前租户时直接 404：v447 前只按 project_id 查，
        // 外部审计能据此读到别人的 jdbc_url / 账号（P0-2）。
        requireProject(projectId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.project_id, d.name, d.ds_type, d.jdbc_url, d.username,
                       d.config_json, d.created_at, d.last_test_at, d.last_test_ok,
                       d.last_test_message, d.queryable, d.engine, d.env,
                       d.lake_catalog, d.lake_status, d.lake_status_message, d.lake_synced_at,
                       d.gravitino_catalog, d.gravitino_status, d.gravitino_message,
                       d.gravitino_synced_at
                from platform.datasource d
                join platform.project p on p.id = d.project_id
                where (d.project_id = ? and p.tenant_id = ?)
                   or coalesce(d.config_json ->> 'builtin', 'false') = 'true'
                order by d.id
                """, projectId, com.lab.dataworks.auth.TenantScope.current());
        rows.forEach(this::normalize);
        return rows;
    }

    /**
     * 租户内全部数据源（可按类型 / 环境过滤）。
     * 项目弹窗用它列出可选的 DORIS 数据源；内置 Doris 对每个租户都可见。
     */
    @GetMapping("/api/datasources")
    public List<Map<String, Object>> all(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) String env) {
        // REQ-TC4-1：env 从「不支持的参数就 400」（BUG-TC4-6 的临时口径）转成真正的维度。
        // 仍然不静默：非法取值照旧 400，只是不再说「没有这个维度」。
        String envFilter = env == null || env.isBlank() ? null : normalizeEnv(env);
        StringBuilder sql = new StringBuilder("""
                select d.id, d.project_id, d.name, d.ds_type, d.jdbc_url, d.username,
                       d.config_json, d.created_at, d.last_test_at, d.last_test_ok,
                       d.last_test_message, d.queryable, d.engine, d.env,
                       p.code as project_code,
                       d.lake_catalog, d.lake_status, d.lake_status_message, d.lake_synced_at,
                       d.gravitino_catalog, d.gravitino_status, d.gravitino_message,
                       d.gravitino_synced_at
                from platform.datasource d
                join platform.project p on p.id = d.project_id
                where (p.tenant_id = ?
                       or coalesce(d.config_json ->> 'builtin', 'false') = 'true')
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(com.lab.dataworks.auth.TenantScope.current());
        if (type != null && !type.isBlank()) {
            sql.append(" and d.ds_type = ?");
            args.add(type.trim().toUpperCase(Locale.ROOT));
        }
        if (envFilter != null) {
            sql.append(" and d.env = ?");
            args.add(envFilter);
        }
        sql.append(" order by d.id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        rows.forEach(this::normalize);
        return rows;
    }

    /**
     * Gravitino Catalog 巡检（默认 dry-run）：列出当前可见的 Catalog 并分类
     * （in-use / protected / orphan / duplicate），只有平台管理员可调用。
     */
    @GetMapping("/api/datasources/gravitino/catalogs")
    @RequireAtom("system:manage")
    public Map<String, Object> gravitinoCatalogs() {
        return gravitinoAdmin.list();
    }

    /**
     * 清理孤儿/重复 Catalog。默认 dry-run；{@code confirm=true} 才真正删除，
     * 且逐条复检「仍在当前租户命名空间 + 无数据源引用」，没把握的一律跳过。
     */
    @PostMapping("/api/datasources/gravitino/cleanup")
    @RequireAtom("system:manage")
    public Map<String, Object> gravitinoCleanup(
            @RequestParam(defaultValue = "false") boolean confirm,
            HttpServletRequest http) {
        Map<String, Object> out = gravitinoAdmin.cleanup(confirm);
        Object deleted = out.get("deleted");
        int count = deleted instanceof List<?> list ? list.size() : 0;
        audit(http, "GRAVITINO_CATALOG_CLEANUP", confirm ? "confirm" : "dry-run",
                "tenant=" + out.get("tenant") + " prefix=" + out.get("namespacePrefix")
                        + " deleted=" + count);
        return out;
    }

    @PostMapping("/api/projects/{projectId}/datasources")
    @RequireAtom("datasource:write")
    public Map<String, Object> create(@PathVariable long projectId, @RequestBody DataSourceRequest req,
                                      HttpServletRequest http) {
        validate(req, true);
        requireProject(projectId);
        if (nameExists(projectId, req.name(), null)) {
            throw ApiException.conflict("数据源名称已存在: " + req.name());
        }
        String type = normalizeType(req.type());
        String env = normalizeEnv(req.env());
        Long id = jdbc.queryForObject("""
                insert into platform.datasource
                    (project_id, name, ds_type, jdbc_url, username, password, config_json,
                     tenant_id, engine, queryable, env)
                values (?, ?, ?, ?, ?, ?, ?::json, ?, ?, ?, ?)
                returning id
                """, Long.class, projectId, req.name().trim(), type,
                blankTo(req.jdbcUrl()), blankTo(req.username()),
                cipher.encrypt(req.password() == null ? "" : req.password()),
                writeJson(sanitize(req.config())),
                com.lab.dataworks.auth.TenantScope.current(),
                // V99：新建时就按类型写好查询能力，避免新 Kafka 数据源默认成「可查询」
                DatasourceEngine.engineOf(type), DatasourceEngine.queryable(type),
                // V116：环境落到库里，列表/过滤/审计都从这一列读
                env);
        if ("DORIS".equals(type)) {
            saveStorage(id, req);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("env", env);
        if ("ICEBERG".equals(type)) {
            // V100：保存即下发 CREATE CATALOG + GRANT。失败不回滚数据源：
            // 保留配置、lake_status=error，用户按返回的 error 修好后再刷新。
            out.put("lake", lakeCatalogs.provision(id));
        }
        if ("DORIS".equals(type)) {
            // V101：DORIS 数据源保存即注册 Gravitino Catalog。同样不回滚：
            // 失败写 gravitino_status=error + 可诊断原因，修好后重新保存即可重试。
            Map<String, Object> sync = gravitinoSync.sync(id);
            out.put("gravitino", sync);
            // 第二批：新建/改名会让项目对应的 catalog 变化，清掉元数据读取缓存
            metadata.invalidateCatalog(catalogOf(sync));
        }
        audit(http, "DATASOURCE_CREATE", req.name(), "project " + projectId);
        return out;
    }

    @PutMapping("/api/datasources/{id}")
    @RequireAtom("datasource:write")
    public Map<String, Object> update(@PathVariable long id, @RequestBody DataSourceRequest req,
                                      HttpServletRequest http) {
        validate(req, false);
        Map<String, Object> existing = find(id);
        // 第二批：编辑前记下旧 catalog，改名/改类型后旧缓存也要失效
        String oldCatalog = existing.get("gravitino_catalog") == null ? null
                : String.valueOf(existing.get("gravitino_catalog"));
        String type = normalizeType(req.type());
        // 内置 Doris：数据源本身由迁移维护，不允许改名/改地址；只允许改默认存储。
        // 存储落在当前租户的 warehouse_connection 上，不会影响其他租户。
        if (Boolean.TRUE.equals(existing.get("builtin"))) {
            if (!"DORIS".equals(type)) {
                throw ApiException.badRequest("内置 Doris 数据源的类型不能修改");
            }
            // V116：内置 Doris 是平台自身的数据通路，环境固定为建库时的那一行；
            // 想改成 PROD 不能静默忽略（用户会以为改成功了）。
            if (req.env() != null && !req.env().isBlank()
                    && !normalizeEnv(req.env()).equals(String.valueOf(existing.get("env")))) {
                throw ApiException.badRequest("内置数据源的环境固定为 "
                        + existing.get("env") + "，不能修改；需要指定环境请新建一个数据源");
            }
            saveStorage(id, req);
            audit(http, "DATASOURCE_UPDATE", String.valueOf(existing.get("name")),
                    "id " + id + " (builtin storage)");
            return Map.of("ok", true);
        }
        if (nameExists(((Number) existing.get("project_id")).longValue(), req.name(), id)) {
            throw ApiException.conflict("数据源名称已存在: " + req.name());
        }
        // 编辑时密码留空 = 不修改，避免把已保存密码覆盖成空串。
        // 但改了 JDBC 地址后不能复用旧密码：否则可以把库里的口令发给攻击者控制的
        // 服务端（与 testUnsaved 的同一条规则；以前 update 漏了这道检查）。
        String password = req.password();
        String newUrl = blankTo(req.jdbcUrl());
        String oldUrl = existing.get("jdbc_url") == null ? null
                : String.valueOf(existing.get("jdbc_url")).trim();
        boolean jdbcType = isJdbc(type);
        // SPARK 允许空口令（默认不认证）：不涉及复用旧密码，改地址也不用重填
        if (jdbcType && !"SPARK".equals(type) && (password == null || password.isEmpty())
                && !java.util.Objects.equals(newUrl, oldUrl)) {
            throw ApiException.badRequest(
                    "修改 JDBC 地址时必须重新输入密码，不能复用已保存的密码");
        }
        // V100：湖数据源同理——改了 S3 endpoint 还复用旧 Secret Key，等于把凭据
        // 发给新的服务端。必须重新输入 Secret Key。
        if ("ICEBERG".equals(type) && (password == null || password.isEmpty())) {
            Map<String, Object> oldConfig = existing.get("config") instanceof Map<?, ?> map
                    ? configMap(map) : Map.of();
            String oldEndpoint = String.valueOf(oldConfig.getOrDefault("endpoint", ""));
            Object rawNew = req.config() == null ? null : req.config().get("endpoint");
            String newEndpoint = rawNew == null ? "" : String.valueOf(rawNew);
            if (!oldEndpoint.trim().equalsIgnoreCase(newEndpoint.trim())) {
                throw ApiException.badRequest(
                        "修改 S3 Endpoint 时必须重新输入 Secret Key，不能复用已保存的凭据");
            }
        }
        if (password == null || password.isEmpty()) {
            password = String.valueOf(existing.get("password"));
        } else {
            password = cipher.encrypt(password);
        }
        // V100：表管理只按 lake_catalog 找 Catalog，改名后旧 Catalog 会变成无主资源。
        // 先把旧的删掉（有纳管表时返回 409，要求先清理），再创建新的。
        if ("ICEBERG".equals(type)) {
            Map<String, Object> oldConfig = existing.get("config") instanceof Map<?, ?> map
                    ? configMap(map) : Map.of();
            Object rawCatalog = req.config() == null ? null : req.config().get("catalog");
            Object rawType = req.config() == null ? null : req.config().get("catalogType");
            String newCatalog = rawCatalog == null ? "" : String.valueOf(rawCatalog).trim();
            String oldLakeCatalog = existing.get("lake_catalog") == null ? ""
                    : String.valueOf(existing.get("lake_catalog")).trim();
            String newType = rawType == null || String.valueOf(rawType).isBlank()
                    ? "hadoop" : String.valueOf(rawType).trim();
            String oldType = oldConfig.get("catalogType") == null
                    || String.valueOf(oldConfig.get("catalogType")).isBlank()
                    ? "hadoop" : String.valueOf(oldConfig.get("catalogType")).trim();
            // Catalog 名或 type 变了都必须删除重建（type 不可 ALTER）
            if (!oldLakeCatalog.isBlank() && (!oldLakeCatalog.equalsIgnoreCase(newCatalog)
                    || !oldType.equalsIgnoreCase(newType))) {
                lakeCatalogs.dropCatalog(id, oldLakeCatalog);
            }
        }
        // V100：类型从湖改成别的，必须在 UPDATE 之前清理（load() 按新类型会拒绝），
        // 否则会留下无主的 Doris 外部 Catalog。
        boolean wasLake = "ICEBERG".equalsIgnoreCase(String.valueOf(existing.get("ds_type")));
        if (wasLake && !"ICEBERG".equals(type)) {
            lakeCatalogs.dropForDatasourceDelete(id);
        }
        // v448：更新语句本身也带租户条件。find() 已经做过校验，这里再加一道，
        // 避免「校验后、写入前」被换租户（TOCTOU）或以后有人把 find 删掉。
        int updated = jdbc.update("""
                update platform.datasource
                set name = ?, ds_type = ?, jdbc_url = ?, username = ?, password = ?,
                    config_json = ?::json, engine = ?, queryable = ?, env = ?
                where id = ?
                  and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, req.name().trim(), type,
                blankTo(req.jdbcUrl()), blankTo(req.username()), password,
                writeJson(sanitize(req.config())),
                // V99：改类型时同步刷新查询能力（DORIS→KAFKA 后必须立刻变成仅导入）
                DatasourceEngine.engineOf(type), DatasourceEngine.queryable(type),
                // V116：编辑时不传 env 视为「不改」，沿用库里已有的值（不能默默打回 DEV）
                req.env() == null || req.env().isBlank()
                        ? envOf(existing) : normalizeEnv(req.env()), id,
                com.lab.dataworks.auth.TenantScope.current());
        if (updated == 0) {
            throw ApiException.notFound("数据源不存在: " + id);
        }
        if (wasLake && !"ICEBERG".equals(type)) {
            jdbc.update("""
                    update platform.datasource
                       set lake_catalog = null, lake_status = 'unknown',
                           lake_status_message = '类型已改为 ' || ?, lake_synced_at = null
                     where id = ?
                       and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                    """, type, id, com.lab.dataworks.auth.TenantScope.current());
        }
        if ("DORIS".equals(type)) {
            saveStorage(id, req);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if ("ICEBERG".equals(type)) {
            out.put("lake", lakeCatalogs.provision(id));
        }
        // V101：DORIS 数据源保存后同步 Catalog；改名会先删旧 Catalog 再建新的。
        // 从 DORIS 改成其他类型时清理映射，避免留下无主 Catalog。
        boolean wasDoris = "DORIS".equalsIgnoreCase(String.valueOf(existing.get("ds_type")));
        if ("DORIS".equals(type)) {
            Map<String, Object> sync = gravitinoSync.sync(id);
            out.put("gravitino", sync);
            // 第二批：改名（rename）或改地址（ALTER）后元数据缓存立即失效
            metadata.invalidateCatalog(oldCatalog);
            metadata.invalidateCatalog(catalogOf(sync));
        } else if (wasDoris) {
            gravitinoSync.detach(id);
            metadata.invalidateCatalog(oldCatalog);
        }
        audit(http, "DATASOURCE_UPDATE", req.name(), "id " + id);
        return out;
    }

    @DeleteMapping("/api/datasources/{id}")
    @RequireAtom("datasource:delete")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> existing = find(id);
        // 第二批：删除前记下已登记/按规则可能存在的 catalog 名，删除成功后清缓存
        String oldCatalog = existing.get("gravitino_catalog") == null ? null
                : String.valueOf(existing.get("gravitino_catalog"));
        // S13：删数据源是「先做后记」的老问题点——引用检查（409）或级联清理一旦抛异常，
        // 原来那条 audit(...) 根本走不到，安全中心里连「谁试过删它」都查不到。
        // 改成先记（PENDING）再做，成功回填 SUCCESS、失败回填 FAILED，异常原样抛出。
        return sessions.auditAround(user(http), "DATASOURCE_DELETE",
                String.valueOf(existing.get("name")), "id " + id,
                AuthInterceptor.clientIp(http), () -> doDelete(id, existing, oldCatalog));
    }

    private Map<String, Object> doDelete(long id, Map<String, Object> existing, String oldCatalog) {
        // V98：内置 Doris 是所有项目的兜底数仓，任何租户都不能删
        if (Boolean.TRUE.equals(existing.get("builtin"))) {
            throw ApiException.conflict("内置 Doris 数据源不能删除");
        }
        Long projectRefs = jdbc.queryForObject("""
                select count(*) from platform.project
                 where warehouse_datasource_id = ? and tenant_id = ?
                """, Long.class, id, com.lab.dataworks.auth.TenantScope.current());
        if (projectRefs != null && projectRefs > 0) {
            throw ApiException.conflict(
                    "还有 " + projectRefs + " 个项目使用该数仓数据源，请先改绑项目");
        }
        // 工作流任务把 datasourceId 存在 JSON 里，外键无法级联；直接删会留下
        // 悬空引用，运行时才报 "datasource not found"。这里提前拦下。
        Long used = jdbc.queryForObject("""
                select count(*) from platform.workflow w
                where jsonb_typeof(w.model_json -> 'tasks') = 'array'
                  and exists (
                      select 1 from jsonb_array_elements(w.model_json -> 'tasks') t
                      where (t ->> 'datasourceId') ~ '^[0-9]+$'
                        and (t ->> 'datasourceId')::bigint = ?
                  )
                """, Long.class, id);
        if (used != null && used > 0) {
            throw ApiException.conflict(
                    "还有 " + used + " 个工作流任务引用该数据源，请先在工作流编辑器中改掉数据源");
        }
        Long services = jdbc.queryForObject("""
                select count(*) from platform.data_service where datasource_id = ?
                """, Long.class, id);
        if (services != null && services > 0) {
            throw ApiException.conflict(
                    "还有 " + services + " 个数据服务使用该数据源，请先把服务改绑或删除");
        }
        // 数据集成任务的源/目标同样存的是数据源 id；删掉后任务卡片名称变空、运行时才报错
        Long syncTasks = jdbc.queryForObject("""
                select count(*) from platform.sync_task
                where source_datasource_id = ? or sink_datasource_id = ?
                """, Long.class, id, id);
        if (syncTasks != null && syncTasks > 0) {
            throw ApiException.conflict(
                    "还有 " + syncTasks + " 个数据集成任务引用该数据源，请先改绑或删除任务");
        }
        // D17：看板图表也挂 datasource_id，之前没检查，删除时只落成通用 409（外键异常），
        // 用户看不出是哪个看板在用。这里提前查出来并说清数量。
        Long charts = jdbc.queryForObject(
                "select count(*) from platform.bi_chart where datasource_id = ?", Long.class, id);
        if (charts != null && charts > 0) {
            throw ApiException.conflict(
                    "还有 " + charts + " 个看板图表使用该数据源，请先在数据可视化里改绑或删除图表");
        }

        // quality_rule.datasource_id 是 ON DELETE SET NULL；静默置空会让 PG 规则
        // 回退到平台库、DORIS 规则回退到 MCP，结果变成另一个库的。
        Long qualityRules = jdbc.queryForObject(
                "select count(*) from platform.quality_rule where datasource_id = ?",
                Long.class, id);
        if (qualityRules != null && qualityRules > 0) {
            throw ApiException.conflict(
                    "还有 " + qualityRules + " 条质量规则引用该数据源，请先改绑或删除规则");
        }
        // V100：湖数据源删除前先清理 Doris Catalog 与纳管记录（湖数据文件保留）
        if ("ICEBERG".equalsIgnoreCase(String.valueOf(existing.get("ds_type")))) {
            lakeCatalogs.dropForDatasourceDelete(id);
        }
        // V101：先软删 Gravitino Catalog，再删平台行。Gravitino 不可达时只告警，
        // 不阻塞数据源删除（平台 CRUD 不应该被外部元数据服务反向锁死）；
        // 留下的 Catalog 名会出现在日志里，可手工补删。
        if ("DORIS".equalsIgnoreCase(String.valueOf(existing.get("ds_type")))) {
            gravitinoSync.remove(id);
        }
        int deleted = jdbc.update("""
                delete from platform.datasource
                 where id = ?
                   and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (deleted == 0) {
            throw ApiException.notFound("数据源不存在: " + id);
        }
        // 第二批：删除/编辑数据源后清掉对应 catalog 的元数据缓存。
        // 除了已登记的 catalog，还按命名规则清一次候选名（上次同步失败时可能只建了一半）。
        metadata.invalidateCatalog(oldCatalog);
        metadata.invalidateCatalog(GravitinoCatalogNaming.catalogName(
                com.lab.dataworks.auth.TenantScope.current(),
                String.valueOf(existing.get("name"))));
        return Map.of("ok", true);
    }

    /**
     * 手动重试 Gravitino Catalog 同步（保存时已经自动同步过，这里用于修好配置/服务后重试）。
     * 内置 Doris 会更新平台级 Catalog（默认 doris_dev）的 properties，包括账号口令。
     */
    @PostMapping("/api/datasources/{id}/sync")
    @RequireAtom("datasource:write")
    public Map<String, Object> sync(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> out = gravitinoSync.sync(id);
        metadata.invalidateCatalog(catalogOf(out));
        audit(http, "DATASOURCE_GRAVITINO_SYNC", String.valueOf(id),
                "status=" + out.get("status") + " catalog=" + out.get("catalog")
                        + " operation=" + out.get("operation"));
        return out;
    }

    @PostMapping("/api/datasources/{id}/test")
    @RequireAtom("datasource:write")
    public Map<String, Object> test(@PathVariable long id) {
        Map<String, Object> ds = find(id);
        String type = String.valueOf(ds.get("ds_type"));
        if ("KAFKA".equalsIgnoreCase(type)) {
            Map<String, Object> config = ds.get("config") instanceof Map<?, ?> map
                    ? (Map<String, Object>) configMap(map) : Map.of();
            Map<String, Object> result = testKafka(bootstrapOf(config));
            recordTest(id, result);
            return result;
        }
        if ("ICEBERG".equalsIgnoreCase(type)) {
            // V100：连通性 = Catalog 存在 + 业务账号可读 + S3 endpoint 可达
            Map<String, Object> result = lakeCatalogs.test(id);
            recordTest(id, result);
            return result;
        }
        if (!isJdbc(type)) {
            return Map.of("ok", false, "error", "该类型暂不支持连接测试，请用同步任务验证");
        }
        Object storedPassword = ds.get("password");
        String[] creds = resolveTestCredentials(
                Boolean.TRUE.equals(ds.get("builtin")),
                String.valueOf(ds.get("username")),
                cipher.decrypt(storedPassword == null ? "" : String.valueOf(storedPassword)),
                builtinDorisUser, builtinDorisPassword);
        Map<String, Object> result = testConnection(String.valueOf(ds.get("jdbc_url")),
                creds[0], creds[1]);
        recordTest(id, result);
        return result;
    }

    /**
     * BUG-DEMO-DS-HYGIENE-1：内置 Doris 行连接测试的凭据选择。
     *
     * <p>内置行的口令从来不落库——V98 插行时 {@code password} 是空串，
     * PUT 的内置分支只允许改默认存储、拒写口令；于是 {@code /test} 拿行上空密
     * 去连，「连接状态」列对内置行永远显示「异常（账号或密码不正确）」，
     * 而真实查询通路（DorisQueryService）一直用平台 {@code doris.*} 配置。</p>
     *
     * <p>规约：内置行 + 行上没存到口令 + 平台口令已配置 → 跟随平台凭据，
     * 地址仍按行上 URL 检验（验的就是这一行指向的端点）；任一条件不满足
     * 则沿用行上口令——平台没配口令时如实按空口令测红，不藏。</p>
     *
     * @return {@code [user, password]}
     */
    static String[] resolveTestCredentials(boolean builtin, String rowUser, String rowPlainPassword,
                                           String platformUser, String platformPassword) {
        String rowPw = rowPlainPassword == null ? "" : rowPlainPassword;
        String platPw = platformPassword == null ? "" : platformPassword;
        if (builtin && rowPw.isEmpty() && !platPw.isEmpty()) {
            return new String[]{platformUser, platPw};
        }
        return new String[]{rowUser, rowPw};
    }

    /**
     * 测试未保存的表单。编辑场景下密码留空时，用 id 读取已保存密码，
     * 这样「改 URL / 用户但不动密码」也能测试。
     */
    @PostMapping("/api/datasources/test")
    @RequireAtom("datasource:write")
    public Map<String, Object> testUnsaved(@RequestBody DataSourceRequest req) {
        // v449 P2：未保存的连接测试不需要 name（「先测后存」流程）
        validate(req, req.id() == null, false);
        String type = normalizeType(req.type());
        if ("KAFKA".equalsIgnoreCase(type)) {
            return testKafka(bootstrapOf(req.config() == null ? Map.of() : req.config()));
        }
        if ("ICEBERG".equalsIgnoreCase(type)) {
            // 未保存时无法下发 Catalog：只校验配置 + S3 可达 + 管理员连接可用
            String password = req.password();
            if ((password == null || password.isEmpty()) && req.id() != null) {
                Map<String, Object> saved = find(req.id());
                password = cipher.decrypt(String.valueOf(saved.get("password")));
            }
            return lakeCatalogs.testConfig(req.config() == null ? Map.of() : req.config(),
                    req.username(), password);
        }
        if (!isJdbc(type)) {
            return Map.of("ok", false, "error", "该类型暂不支持连接测试，请用同步任务验证");
        }
        String url = JdbcUrlGuard.validate(req.jdbcUrl());
        String password = req.password();
        // SPARK 没有口令可复用，直接按表单里的空口令测（Kyuubi 允许空口令）
        if ((password == null || password.isEmpty()) && req.id() != null
                && !"SPARK".equals(type)) {
            Map<String, Object> saved = find(req.id());
            String savedUrl = saved.get("jdbc_url") == null
                    ? "" : String.valueOf(saved.get("jdbc_url")).trim();
            // 复用已保存密码时必须连原来的地址：否则可以把库里的密码发给
            // 攻击者控制的 JDBC 服务端（恶意 MySQL 可索要明文口令）。
            if (!savedUrl.equalsIgnoreCase(url)) {
                throw ApiException.badRequest("复用已保存的密码时不能修改 JDBC 地址，请重新输入密码");
            }
            password = cipher.decrypt(String.valueOf(saved.get("password")));
        }
        Map<String, Object> result = testConnection(url, req.username().trim(), password);
        recordTest(req.id(), result);
        return result;
    }

    /** 记录最近一次连接检测结果，列表页直接展示；失败不影响测试接口本身的返回。 */
    private void recordTest(Long id, Map<String, Object> result) {
        if (id == null) {
            return;
        }
        try {
            boolean ok = Boolean.TRUE.equals(result.get("ok"));
            String message = ok
                    ? ("连接成功：" + result.getOrDefault("product", "")
                       + " " + result.getOrDefault("version", "")).trim()
                    : String.valueOf(result.get("error"));
            if (message.length() > 500) {
                message = message.substring(0, 500);
            }
            jdbc.update("""
                    update platform.datasource
                    set last_test_at = now(), last_test_ok = ?, last_test_message = ?
                    where id = ?
                    """, ok, message, id);
        } catch (Exception e) {
            // 状态写入失败不能把「连接成功」变成接口错误
            org.slf4j.LoggerFactory.getLogger(DataSourceController.class)
                    .warn("record datasource test status failed id={}: {}", id, e.getMessage());
        }
    }

    private void audit(HttpServletRequest request, String action, String target, String detail) {
        sessions.audit(user(request), action, target, detail,
                "SUCCESS", request == null ? null : AuthInterceptor.clientIp(request));
    }

    /** 审计主体：拦截器写进 request 的用户名；没有上下文（内部调用/测试）时记 unknown。 */
    private static String user(HttpServletRequest request) {
        Object username = request == null ? null : request.getAttribute("username");
        return username == null ? "unknown" : String.valueOf(username);
    }

    private void requireProject(long projectId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private Map<String, Object> find(long id) {
        // 统一按「项目归属」判定租户：despite tenant_id 的历史默认值，
        // 项目才是所有者，join 后不会因旧数据漏判。
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.project_id, d.name, d.ds_type, d.jdbc_url, d.username,
                       d.password, d.config_json, d.queryable, d.engine, d.env,
                       d.lake_catalog, d.lake_status, d.lake_status_message,
                       d.gravitino_catalog, d.gravitino_status, d.gravitino_message,
                       d.gravitino_synced_at, p.tenant_id
                from platform.datasource d
                join platform.project p on p.id = d.project_id
                where d.id = ?
                  and (p.tenant_id = ?
                       or coalesce(d.config_json ->> 'builtin', 'false') = 'true')
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        normalize(row);
        return row;
    }

    private boolean nameExists(long projectId, String name, Long excludeId) {
        Long n = excludeId == null
                ? jdbc.queryForObject(
                        "select count(*) from platform.datasource where project_id = ? and name = ?",
                        Long.class, projectId, name.trim())
                : jdbc.queryForObject(
                        "select count(*) from platform.datasource where project_id = ? and name = ? and id <> ?",
                        Long.class, projectId, name.trim(), excludeId);
        return n != null && n > 0;
    }

    private Map<String, Object> testConnection(String url, String user, String password) {
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String checked = JdbcUrlGuard.validate(url);
            String driver = JdbcUrlGuard.driverFor(checked);
            if (driver != null) {
                Class.forName(driver);
            }
            DriverManager.setLoginTimeout(5);
            Properties props = new Properties();
            props.setProperty("user", user == null ? "" : user);
            props.setProperty("password", password == null ? "" : password);
            // 单位按驱动不同：PG 是秒、MySQL 是毫秒，统一见 JdbcTimeouts
            JdbcTimeouts.apply(props, checked, 5, 8);
            try (Connection conn = DriverManager.getConnection(checked, props)) {
                out.put("ok", true);
                out.put("product", conn.getMetaData().getDatabaseProductName());
                out.put("version", conn.getMetaData().getDatabaseProductVersion());
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", connectionFailure(e));
        }
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DataSourceController.class);

    /**
     * D64：连接失败原因**分类**，驱动原文只进日志、不进响应。
     *
     * <p>缺陷现场（TC-5 用例 T5-DS-03 / T5-DS-06，v539 live）：口令错误时接口 200 返回
     * {@code {"ok":false,"error":"Access denied for user 'bug_a_user@192.0.2.13'
     * (using password: YES)"}}。其中 {@code 192.0.2.13} 是<b>后端 Pod 在集群内的源地址</b>——
     * 调用方从没提供过、也不该在页面上看到；{@code (using password: YES)} 还额外泄露
     * 「服务端确实收到了密码」这一认证细节。{@code last_test_message} 会把这条存进库并在列表回显。</p>
     *
     * <p>分类原则：能给出**下一步动作**的才写进文案（改口令 / 查网络 / 查库名），
     * 归不了类的一律给中文概要 + 「原始错误已记入后端日志」，
     * <b>不因为「想保留可诊断性」就把拓扑信息透出去</b>。</p>
     */
    static String connectionFailure(Throwable e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        // 类名也是判据：UnknownHostException 的 message 就是主机名本身，
        // 只看文案会漏到兜底分支（实测「nope.svc.cluster.local」一条就是这样）。
        String kind = e.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        String code = e instanceof java.sql.SQLException sql && sql.getSQLState() != null
                ? sql.getSQLState().trim() : "";
        String low = raw.toLowerCase(java.util.Locale.ROOT);
        // 原文进日志（含 SQLState，便于排障），响应里没有
        log.warn("数据源连接失败 SQLState={} 原文={}", code.isBlank() ? "-" : code,
                raw.isBlank() ? kind : raw);
        // 08* 是 SQL 标准的「连接异常」类（PG 的 "The connection attempt failed." 就是 08001）
        if ("28000".equals(code) || code.startsWith("28")
                || low.contains("access denied") || low.contains("password authentication failed")
                || low.contains("authentication failed") || low.contains("invalid authorization")
                || low.contains("not permitted to log in")) {
            return "账号或密码不正确";
        }
        if (code.startsWith("08") || kind.contains("unknownhost") || kind.contains("connect")
                || low.contains("communications link failure") || low.contains("connect timed out")
                || low.contains("connection timed out") || low.contains("connection refused")
                || low.contains("connection attempt failed") || low.contains("unknown host")
                || low.contains("no route to host") || low.contains("network is unreachable")
                || low.contains("connection reset")) {
            return "无法连接到数据库地址，请检查主机、端口与网络连通性";
        }
        if (low.contains("unknown database") || low.contains("database \"") && low.contains("does not exist")
                || low.contains("invalid catalog name")) {
            return "JDBC 地址里的库（database/schema）不存在，请检查地址";
        }
        if (low.contains("ssl") || low.contains("certificate")) {
            return "SSL/TLS 握手失败，请检查服务端证书与 SSL 参数";
        }
        if (low.contains("classforname") || low.contains("no suitable driver")
                || low.contains("driver")) {
            return "平台缺少该类型的 JDBC 驱动，请联系管理员";
        }
        if (low.contains("timeout") || low.contains("timed out")) {
            return "连接超时，请检查地址与网络（详情见后端日志）";
        }
        return "连接失败，请检查地址、账号与密码（原始错误已记入后端日志）";
    }

    /** Kafka 连接测试：逐个 bootstrap 地址做 TCP 连通性探测（不做协议握手）。 */
    private Map<String, Object> testKafka(String bootstrap) {
        // v448 P1-2：Kafka 曾完全绕过 JdbcUrlGuard，169.254.169.254:9092 会真实发起连接。
        // 连接前逐个地址走出网守卫（抛 400，与 JDBC 测试一致）。
        for (String part : (bootstrap == null ? "" : bootstrap).split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            int colon = value.lastIndexOf(':');
            String host = colon > 0 ? value.substring(0, colon) : value;
            int port = 9092;
            if (colon > 0) {
                try {
                    port = Integer.parseInt(value.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            JdbcUrlGuard.validateDestination(host, port);
        }
        return probeKafka(bootstrap);
    }

    private Map<String, Object> probeKafka(String bootstrap) {
        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        if (bootstrap == null || bootstrap.isBlank()) {
            return Map.of("ok", false, "error", "缺少 bootstrap.servers", "elapsedMs", 0);
        }
        String[] parts = bootstrap.split(",");
        String error = "";
        for (String part : parts) {
            String value = part.trim();
            int colon = value.lastIndexOf(':');
            String host = colon > 0 ? value.substring(0, colon) : value;
            int port = 9092;
            try {
                if (colon > 0) {
                    port = Integer.parseInt(value.substring(colon + 1));
                }
            } catch (NumberFormatException e) {
                error = "端口不合法: " + value;
                continue;
            }
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 3000);
                out.put("ok", true);
                out.put("product", "Kafka");
                out.put("version", "");
                out.put("broker", value);
                out.put("elapsedMs", System.currentTimeMillis() - start);
                return out;
            } catch (Exception e) {
                // D64 同族：broker 地址是用户自己填的（可以回显），但驱动/Socket 原文不回显
                error = host + ":" + port + " " + connectionFailure(e);
            }
        }
        out.put("ok", false);
        out.put("error", error.isBlank() ? "无法连接任何 broker" : error);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    private Map<String, Object> configMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return json.readValue(String.valueOf(raw), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String bootstrapOf(Map<String, Object> config) {
        Object value = config.get("bootstrap.servers");
        if (value == null) {
            value = config.get("bootstrapServers");
        }
        return value == null ? "" : String.valueOf(value);
    }

    private void validate(DataSourceRequest req, boolean requirePassword) {
        validate(req, requirePassword, true);
    }

    /**
     * @param requireName 未保存的连接测试允许不填名称（先测后存），保存/更新时必须填
     */
    private void validate(DataSourceRequest req, boolean requirePassword, boolean requireName) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (requireName && (req.name() == null || req.name().isBlank())) {
            throw ApiException.badRequest("数据源名称不能为空");
        }
        if (req.name() != null && req.name().trim().length() > 128) {
            throw ApiException.badRequest("数据源名称不能超过 128 个字符");
        }
        // V116 / REQ-TC4-1：env 在这里就判，未保存的连接测试（先测后存）也不许带非法值静默通过
        normalizeEnv(req.env());
        String type = normalizeType(req.type());
        validateStorage(req.storageType(), req.storageEndpoint());
        Map<String, Object> config = req.config() == null ? Map.of() : req.config();
        if (isJdbc(type)) {
            JdbcUrlGuard.validate(req.jdbcUrl());
            if (req.username() == null || req.username().isBlank()) {
                throw ApiException.badRequest("用户名不能为空");
            }
            // Spark Thrift Server 默认不开认证（Kyuubi/HiveServer2 允许空口令），
            // 强制密码会让「补一个 SPARK 数据源」直接不可用；其余方言保持必须填。
            if (requirePassword && !"SPARK".equals(type)
                    && (req.password() == null || req.password().isEmpty())) {
                throw ApiException.badRequest("新建数据源必须填写密码");
            }
        } else {
            switch (type) {
                case "S3FILE" -> {
                    require(config, "endpoint");
                    require(config, "bucket");
                    checkEndpoint(String.valueOf(config.get("endpoint")));
                    if (req.username() == null || req.username().isBlank()) {
                        throw ApiException.badRequest("S3 Access Key 不能为空");
                    }
                    if (requirePassword && (req.password() == null || req.password().isEmpty())) {
                        throw ApiException.badRequest("S3 Secret Key 不能为空");
                    }
                }
                case "LOCALFILE" -> require(config, "path");
                case "MONGODB" -> {
                    // uri 可以不写：host/port + 用户名密码 由渲染器拼成 URI，
                    // 这样密码只存在加密列，不会进 config_json。
                    if (isBlankText(config.get("uri")) && isBlankText(config.get("host"))) {
                        throw ApiException.badRequest("缺少数据源配置: uri 或 host");
                    }
                    String uri = String.valueOf(config.get("uri") == null ? "" : config.get("uri"));
                    if (uri.matches("(?s).*://[^/@\\s]+@.*")) {
                        throw ApiException.badRequest(
                                "MongoDB URI 不能内嵌用户名/密码，请使用独立的用户名/密码字段");
                    }
                    if (!uri.isBlank()) {
                        checkEndpoint(uri);
                    } else {
                        checkHostPort(config);
                    }
                    require(config, "database");
                }
                case "REDIS" -> {
                    require(config, "host");
                    require(config, "port");
                    checkHostPort(config);
                }
                case "CLICKHOUSE" -> {
                    require(config, "host");
                    require(config, "port");
                    checkHostPort(config);
                    require(config, "database");
                }
                case "KAFKA" -> {
                    if (isBlankText(config.get("bootstrap.servers"))
                            && isBlankText(config.get("bootstrapServers"))) {
                        throw ApiException.badRequest("缺少数据源配置: bootstrap.servers");
                    }
                    // v448 P1-2：Kafka 之前完全绕过出网守卫
                    checkBootstrap(config.get("bootstrap.servers") == null
                            ? config.get("bootstrapServers") : config.get("bootstrap.servers"));
                }
                case "ICEBERG" -> {
                    // V100：catalog / warehouse / endpoint / AK/SK；catalog 名与属性值
                    // 都会拼进 Doris DDL，校验在 LakeCatalogTemplate 里统一做。
                    // 编辑时密码留空 = 沿用已保存的 Secret Key。
                    lakeCatalogs.validateForSave(config, req.username(), req.password(),
                            requirePassword);
                }
                default -> {
                    // 其余 SeaTunnel 连接器（SFTP/FTP/HTTP/Pulsar/…）同样可能有 host，
                    // 统一走出网守卫，避免「JDBC 堵住了、别的连接器还能探内网」。
                    checkHostPort(config);
                }
            }
        }
    }

    /** 非 JDBC 配置里的 host/port 同样走出网守卫。 */
    private void checkHostPort(Map<String, Object> config) {
        Object host = config.get("host");
        if (host == null || String.valueOf(host).isBlank()) {
            return;
        }
        int port = -1;
        Object rawPort = config.get("port");
        if (rawPort != null) {
            try {
                port = Integer.parseInt(String.valueOf(rawPort).trim());
            } catch (NumberFormatException ignored) {
                // 端口格式由连接器校验，这里只关心出网
            }
        }
        JdbcUrlGuard.validateDestination(String.valueOf(host).trim(), port);
    }

    /** S3 / MongoDB 这类 URI：取出 scheme 后的 host[:port] 再判定。 */
    private void checkEndpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        int scheme = value.indexOf("://");
        String authority = scheme > 0 ? value.substring(scheme + 3) : value;
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            authority = authority.substring(0, slash);
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String host = authority;
        int port = -1;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close > 0) {
                host = authority.substring(1, close);
                if (close + 1 < authority.length() && authority.charAt(close + 1) == ':') {
                    port = parseInt(authority.substring(close + 2));
                }
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon > 0) {
                host = authority.substring(0, colon);
                port = parseInt(authority.substring(colon + 1));
            }
        }
        JdbcUrlGuard.validateDestination(host, port);
    }

    private void checkBootstrap(Object raw) {
        JdbcUrlGuard.validateBootstrapServers(raw == null ? "" : String.valueOf(raw));
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 默认存储只允许 none / hdfs / s3 / sso（REQ-DW-5：数据源方式保持通用，新增 SSO）；
     * 非 none 必须给 endpoint，避免存一个不可用配置。
     */
    private void validateStorage(String type, String endpoint) {
        if (type == null || type.isBlank()) {
            return;
        }
        String value = type.trim().toLowerCase(Locale.ROOT);
        if (!List.of("none", "hdfs", "s3", "sso").contains(value)) {
            throw ApiException.badRequest("storageType 只能是 none / hdfs / s3 / sso");
        }
        if (!"none".equals(value) && (endpoint == null || endpoint.isBlank())) {
            throw ApiException.badRequest("选择 " + value + " 存储时必须填写 storageEndpoint");
        }
    }

    private void require(Map<String, Object> config, String key) {
        if (isBlankText(config.get(key))) {
            throw ApiException.badRequest("缺少数据源配置: " + key);
        }
    }

    private boolean isBlankText(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    /**
     * 类型归一：大小写与首尾空白宽容，但**空值不再默认成 POSTGRESQL**（D62）。
     *
     * <p>缺陷现场（TC-2 用例 DS-04，v539 实测）：请求体不带 {@code type}（或传空白串）时
     * 接口 200 创建成功，落库 {@code ds_type=POSTGRESQL}。用户拿到一个类型错的数据源，
     * 后续测试连接/同步全按 PG 方言解析，错乱点在更下游。这违反 acceptance-rules 第 4 条
     * 「必填字段缺失必须显式 400，禁止 setDefault 掩盖」。</p>
     *
     * <p>注意与「未保存的连接测试允许不填名称」的既有豁免区分（v449 P2）：豁免的是 name
     * （先测后存），类型是解析方言的前提，不在豁免范围。</p>
     */
    private String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("数据源类型不能为空，请先选择类型");
        }
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (TYPES.contains(type)) {
            return type;
        }
        // SFTP 在目录里叫 SftpFile
        String alias = "SFTP".equals(type) ? "SFTPFILE" : type;
        if (catalog.has(alias)) {
            // 统一存目录里的规范名，连接器映射再按需转换
            return String.valueOf(catalog.require(alias).get("name")).toUpperCase(Locale.ROOT);
        }
        throw ApiException.badRequest("数据源类型只能是 " + String.join(" / ", TYPES)
                + " 或 SeaTunnel 连接器目录中的类型");
    }

    /**
     * 走 JDBC 直连的方言。SPARK 是 Spark Thrift Server（HiveServer2 协议，
     * {@code jdbc:hive2://}），与 DORIS/MySQL/PG 一样用 DriverManager 测连通性。
     */
    private boolean isJdbc(String type) {
        return "POSTGRESQL".equals(type) || "DORIS".equals(type) || "MYSQL".equals(type)
                || "SPARK".equals(type);
    }

    private Map<String, Object> sanitize(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (config != null) {
            out.putAll(config);
        }
        out.remove("password");
        out.remove("secretKey");
        out.remove("secret_key");
        out.remove("auth");
        return out;
    }

    private void normalize(Map<String, Object> row) {
        Map<String, Object> config = jsonMap(row.get("config_json"));
        row.put("config", config);
        row.remove("config_json");
        // V116：环境永远以字符串回显。迁移前的历史行（以及内置数据源）是 DEV，
        // 列没读到也按 DEV 兜底——但只兜「没值」，不会把非法值洗成 DEV。
        row.put("env", envOf(row));
        boolean builtin = Boolean.TRUE.equals(config.get("builtin"))
                || "true".equalsIgnoreCase(String.valueOf(config.get("builtin")));
        row.put("builtin", builtin);
        // V99：查询能力（Kafka / 文件类为仅导入）。列缺失或旧数据按 ds_type 兜底，
        // 保证前端永远拿到布尔值和引擎名，不用自己再判一次。
        String dsType = String.valueOf(row.get("ds_type"));
        Object rawQueryable = row.get("queryable");
        boolean queryable = rawQueryable == null
                ? DatasourceEngine.queryable(dsType)
                : (rawQueryable instanceof Boolean bool
                        ? bool : Boolean.parseBoolean(String.valueOf(rawQueryable)));
        row.put("queryable", queryable);
        Object rawEngine = row.get("engine");
        row.put("engine", rawEngine == null || String.valueOf(rawEngine).isBlank()
                ? DatasourceEngine.engineOf(dsType) : String.valueOf(rawEngine));
        // DORIS 数据源的默认存储（存在对应 warehouse_connection 上）；读失败不影响列表
        Map<String, Object> storage = null;
        if ("DORIS".equalsIgnoreCase(String.valueOf(row.get("ds_type")))
                && row.get("id") instanceof Number id) {
            try {
                WarehouseResolver.Storage value = warehouseResolver.storageForDatasource(
                        id.longValue());
                if (value != null) {
                    storage = new LinkedHashMap<>();
                    storage.put("type", value.type());
                    storage.put("endpoint", value.endpoint());
                    storage.put("path", value.path());
                }
            } catch (ApiException ignored) {
                // 跨租户/连接不存在时按未配置展示
            }
        }
        row.put("storage", storage);
        // V100：湖数据源状态（available / error / unknown）+ 可诊断原因
        if ("ICEBERG".equalsIgnoreCase(dsType)) {
            row.put("lakeCatalog", row.get("lake_catalog"));
            row.put("lakeStatus", row.get("lake_status"));
            row.put("lakeStatusMessage", row.get("lake_status_message"));
            row.put("lakeSyncedAt", row.get("lake_synced_at"));
            Map<String, Object> lake = new LinkedHashMap<>();
            lake.put("catalog", row.get("lake_catalog"));
            lake.put("status", row.get("lake_status"));
            lake.put("message", row.get("lake_status_message"));
            lake.put("syncedAt", row.get("lake_synced_at"));
            row.put("lake", lake);
        }
        // V101：Gravitino 同步状态（synced / error / disabled / unknown）
        row.put("gravitinoCatalog", row.get("gravitino_catalog"));
        row.put("gravitinoStatus", row.get("gravitino_status"));
        row.put("gravitinoMessage", row.get("gravitino_message"));
        row.put("gravitinoSyncedAt", row.get("gravitino_synced_at"));
        if ("DORIS".equalsIgnoreCase(dsType)) {
            Map<String, Object> gravitino = new LinkedHashMap<>();
            gravitino.put("catalog", row.get("gravitino_catalog"));
            gravitino.put("status", row.get("gravitino_status"));
            gravitino.put("message", row.get("gravitino_message"));
            gravitino.put("syncedAt", row.get("gravitino_synced_at"));
            row.put("gravitino", gravitino);
        }
    }

    /** 保存 DORIS 数据源的默认存储；表单没带存储字段时保持原值。 */
    private void saveStorage(Long datasourceId, DataSourceRequest req) {
        if (req.storageType() == null && req.storageEndpoint() == null
                && req.storagePath() == null) {
            return;
        }
        warehouseResolver.saveStorage(datasourceId, new WarehouseResolver.Storage(
                req.storageType(), req.storageEndpoint(), req.storagePath()));
    }

    private Map<String, Object> jsonMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw ApiException.badRequest("配置序列化失败: " + e.getMessage());
        }
    }

    /** GravitinoCatalogSync.sync() 返回体里的 catalog 名；缺失时返回 null（invalidate 无操作）。 */
    private String catalogOf(Map<String, Object> sync) {
        Object value = sync == null ? null : sync.get("catalog");
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String blankTo(String value) {
        return value == null ? "" : value;
    }
}
