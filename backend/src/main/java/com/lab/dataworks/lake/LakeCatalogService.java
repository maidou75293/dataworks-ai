package com.lab.dataworks.lake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.secret.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Iceberg 湖 Catalog 的落库、下发、授权与元数据浏览（V100）。
 *
 * <p>为什么必须用管理员连接：平台业务账号（{@code doris.user}，默认 dataworks）
 * 只有建表/查询权限，没有 {@code CREATE CATALOG} / {@code GRANT} 的 Admin_priv。
 * 因此「保存数据源」时用 {@code doris.admin-user}（DORIS_ADMIN_USER，默认 root）
 * 下发 Catalog 与授权，之后浏览/查询仍走业务账号——这样权限边界和普通 SQL 一致。</p>
 *
 * <p>下发步骤是幂等的：CREATE CATALOG IF NOT EXISTS → 已存在则 ALTER SET PROPERTIES
 * → REFRESH CATALOG → 授权（catalog 级 + 每个 db）→ 用业务账号 SHOW DATABASES 验证。</p>
 */
@Service
public class LakeCatalogService {

    private static final Logger log = LoggerFactory.getLogger(LakeCatalogService.class);
    private static final Set<String> SYSTEM_DBS = Set.of(
            "information_schema", "mysql", "__internal_schema", "sys");
    private static final java.util.regex.Pattern PRINCIPAL =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretCipher cipher;
    private final DorisAdminService admin;
    private final DorisQueryService query;
    private final WarehouseResolver resolver;
    private final String grantUser;

    public LakeCatalogService(JdbcTemplate jdbc, ObjectMapper json, SecretCipher cipher,
                              DorisAdminService admin, DorisQueryService query,
                              WarehouseResolver resolver,
                              @Value("${lake.grant-user:${doris.user:dataworks}}") String grantUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
        this.admin = admin;
        this.query = query;
        this.resolver = resolver;
        this.grantUser = grantUser == null || grantUser.isBlank() ? "dataworks" : grantUser.trim();
        if (!PRINCIPAL.matcher(this.grantUser).matches()) {
            throw new IllegalStateException("lake.grant-user 不合法: " + grantUser);
        }
    }

    // ---------------------------------------------------------------- 记录

    /** 数据源行（含解密后的密码，仅服务内部使用）。 */
    public record LakeDatasource(long id, long projectId, String tenantId, String name,
                                 String username, String secret, Map<String, Object> config,
                                 String catalog, String status, String statusMessage) {
    }

    /** 已纳入当前租户的 Catalog。 */
    public record OwnedCatalog(long datasourceId, long projectId, String catalog,
                               String catalogType, String warehouse, String status,
                               String statusMessage) {
    }

    public LakeDatasource load(long datasourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.project_id, d.name, d.ds_type, d.username, d.password,
                       d.config_json, d.lake_catalog, d.lake_status, d.lake_status_message,
                       p.tenant_id
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where d.id = ? and p.tenant_id = ?
                """, datasourceId, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + datasourceId);
        }
        Map<String, Object> row = rows.get(0);
        String type = str(row.get("ds_type"));
        if (!LakeCatalogTemplate.DS_TYPE.equalsIgnoreCase(type)) {
            throw ApiException.badRequest("该操作只支持 ICEBERG 数据源（当前 " + type + "）");
        }
        String encrypted = str(row.get("password"));
        String secret;
        try {
            secret = cipher.decrypt(encrypted);
        } catch (RuntimeException e) {
            secret = "";
        }
        return new LakeDatasource(number(row.get("id")), number(row.get("project_id")),
                str(row.get("tenant_id")), str(row.get("name")), str(row.get("username")),
                secret, configOf(row.get("config_json")), str(row.get("lake_catalog")),
                str(row.get("lake_status")), str(row.get("lake_status_message")));
    }

    /** 名称 → 当前租户的湖 Catalog；不存在/跨租户统一 404（不泄露别的租户）。 */
    public OwnedCatalog requireOwned(String catalog) {
        if (!LakeCatalogTemplate.validIdentifier(catalog)) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CATALOG_NOT_FOUND, "Catalog 名称不合法", catalog));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.project_id, d.lake_catalog, d.lake_status, d.lake_status_message,
                       d.config_json
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where p.tenant_id = ? and upper(d.ds_type) = 'ICEBERG'
                   and lower(d.lake_catalog) = lower(?)
                 order by d.id limit 1
                """, TenantScope.current(), catalog.trim());
        if (rows.isEmpty()) {
            throw ApiException.notFound(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CATALOG_NOT_FOUND,
                    "Catalog 不存在或未纳入当前租户", catalog));
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> config = configOf(row.get("config_json"));
        return new OwnedCatalog(number(row.get("id")), number(row.get("project_id")),
                str(row.get("lake_catalog")), str(config.get("catalogType")),
                str(config.get("warehouse")), str(row.get("lake_status")),
                str(row.get("lake_status_message")));
    }

    /** 轻量判断：该名称是否是当前租户登记的湖 Catalog（不触发 Doris 查询）。 */
    public boolean isOwned(String catalog) {
        if (!LakeCatalogTemplate.validIdentifier(catalog)) {
            return false;
        }
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where p.tenant_id = ? and upper(d.ds_type) = 'ICEBERG'
                   and lower(d.lake_catalog) = lower(?)
                """, Integer.class, TenantScope.current(), catalog.trim());
        return n != null && n > 0;
    }

    /** 当前租户可见的湖 Catalog（页面「湖表」下拉）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.project_id, d.name, d.lake_catalog, d.lake_status,
                       d.lake_status_message, d.lake_synced_at, d.config_json,
                       p.code as project_code
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where p.tenant_id = ? and upper(d.ds_type) = 'ICEBERG'
                 order by d.id
                """, TenantScope.current());
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> config = configOf(row.get("config_json"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("datasourceId", row.get("id"));
            item.put("projectId", row.get("project_id"));
            item.put("projectCode", row.get("project_code"));
            item.put("name", row.get("name"));
            item.put("catalog", row.get("lake_catalog"));
            item.put("catalogType", config.get("catalogType"));
            item.put("warehouse", config.get("warehouse"));
            item.put("endpoint", config.get("endpoint"));
            item.put("status", row.get("lake_status"));
            item.put("statusMessage", row.get("lake_status_message"));
            item.put("lastSyncedAt", row.get("lake_synced_at"));
            item.put("grants", grants(number(row.get("id"))));
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> grants(long datasourceId) {
        return jdbc.queryForList("""
                select database_name, principal, granted_at
                  from platform.lake_catalog_grant
                 where datasource_id = ? order by database_name
                """, datasourceId);
    }

    // ---------------------------------------------------------------- 保存校验

    /** 供 DataSourceController 在落库前校验；失败抛 400（带错误码）。 */
    public void validateForSave(Map<String, Object> config, String accessKey, String secretKey,
                                boolean requireSecret) {
        try {
            validateConfig(config, accessKey, secretKey, requireSecret);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CONFIG_INVALID, e.getMessage(), null));
        }
    }

    private Validated validateConfig(Map<String, Object> config, String accessKey,
                                     String secretKey, boolean requireSecret) {
        Map<String, Object> cfg = config == null ? Map.of() : config;
        String catalog = text(cfg.get("catalog"));
        if (!LakeCatalogTemplate.validIdentifier(catalog)) {
            throw new IllegalArgumentException(
                    "catalog 不合法（字母或下划线开头，只含字母/数字/下划线）: " + catalog);
        }
        String catalogType = LakeCatalogTemplate.normalizeCatalogType(text(cfg.get("catalogType")));
        String warehouse = text(cfg.get("warehouse"));
        if (warehouse.isBlank()) {
            throw new IllegalArgumentException("warehouse 不能为空（示例 s3://lake/warehouse）");
        }
        String lower = warehouse.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("s3://") || lower.startsWith("s3a://"))) {
            throw new IllegalArgumentException("warehouse 必须是 s3:// 或 s3a:// 路径: " + warehouse);
        }
        String endpoint = text(cfg.get("endpoint"));
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException("S3 endpoint 不能为空（示例 http://minio:9000）");
        }
        Endpoint parsed = parseEndpoint(endpoint);
        JdbcUrlGuard.validateDestination(parsed.host(), parsed.port());
        String uri = text(cfg.get("uri"));
        if (("hive".equals(catalogType) || "rest".equals(catalogType)) && uri.isBlank()) {
            throw new IllegalArgumentException(catalogType + " 类型必须填写 uri（Metastore / REST 地址）");
        }
        if (!uri.isBlank()) {
            // 用户可提交的地址：同样走出网守卫，避免借 Catalog 属性探测内网/元数据地址
            checkUri(uri);
        }
        if (uri.isBlank() && "glue".equals(catalogType) && text(cfg.get("region")).isBlank()) {
            throw new IllegalArgumentException("glue 类型必须填写 region");
        }
        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("Access Key 不能为空");
        }
        if (requireSecret && (secretKey == null || secretKey.isBlank())) {
            throw new IllegalArgumentException("Secret Key 不能为空");
        }
        return new Validated(catalog, catalogType, warehouse.trim(), endpoint.trim(),
                text(cfg.get("region")), uri.trim(), isPathStyle(cfg));
    }

    private record Validated(String catalog, String catalogType, String warehouse,
                             String endpoint, String region, String uri, boolean pathStyle) {
    }

    // ---------------------------------------------------------------- 下发

    /**
     * 创建/更新 Catalog 并授权，然后做一次连通性检测。
     *
     * <p>不做事务：Doris 的 Catalog 是外部资源，回滚不现实。失败时把可诊断原因
     * 写进 {@code lake_status / lake_status_message}，数据源本身保留，修好配置
     * 后点「刷新 Catalog」即可重试。</p>
     */
    public Map<String, Object> provision(long datasourceId) {
        LakeDatasource ds = load(datasourceId);
        Validated cfg;
        try {
            cfg = validateConfig(ds.config(), ds.username(), ds.secret(), false);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CONFIG_INVALID, e.getMessage(), null));
        }
        Long connectionId = resolver.connectionIdForProject(ds.projectId());
        List<String> warnings = new ArrayList<>();
        List<String> databases = new ArrayList<>();
        boolean catalogGrant = false;
        try {
            ensureCatalog(connectionId, cfg, ds.username(), ds.secret());
            refreshCatalog(connectionId, cfg.catalog());
            // 授权：catalog 级（覆盖后续新建的库）+ 每个已存在的库
            catalogGrant = grantCatalogLevel(connectionId, cfg.catalog());
            List<Map<String, Object>> rows = admin.queryAdmin(connectionId,
                    "show databases from " + quote(cfg.catalog()));
            for (Map<String, Object> row : rows) {
                String db = firstValue(row);
                if (db == null || db.isBlank() || SYSTEM_DBS.contains(db.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                databases.add(db);
                grantDatabase(connectionId, cfg.catalog(), db, true);
            }
            if (!catalogGrant && databases.isEmpty()) {
                throw ApiException.badRequest(LakeCatalogTemplate.message(
                        LakeCatalogTemplate.GRANT_FAILED,
                        "Catalog 级授权失败，且没有可授权的库", null));
            }
            saveGrants(ds, cfg.catalog(), databases, catalogGrant);
            // 用业务账号验证：SHOW DATABASES 会触发 Iceberg/S3 元数据加载
            query.rawQuery(ds.projectId(), "show databases from " + quote(cfg.catalog()), 500);
        } catch (ApiException e) {
            String code = classify(e.getMessage(), LakeCatalogTemplate.CATALOG_CREATE_FAILED);
            remember(ds, cfg.catalog(), "error", e.getMessage());
            log.warn("lake catalog provision failed ds={} catalog={}: {}",
                    datasourceId, cfg.catalog(), e.getMessage());
            return result(false, "error", cfg, databases, warnings, e.getMessage(), code);
        } catch (Exception e) {
            String message = LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CATALOG_CREATE_FAILED, "Doris 下发 Catalog 失败", e.getMessage());
            remember(ds, cfg.catalog(), "error", message);
            log.warn("lake catalog provision error ds={} catalog={}: {}",
                    datasourceId, cfg.catalog(), e.getMessage());
            return result(false, "error", cfg, databases, warnings, message,
                    LakeCatalogTemplate.CATALOG_CREATE_FAILED);
        }
        remember(ds, cfg.catalog(), "available", null);
        return result(true, "available", cfg, databases, warnings, null, null);
    }

    private void ensureCatalog(Long connectionId, Validated cfg, String accessKey, String secretKey) {
        boolean exists = catalogExists(connectionId, cfg.catalog());
        if (!exists) {
            String properties = propertiesOf(cfg, accessKey, secretKey, true);
            try {
                admin.execAdmin(connectionId,
                        "CREATE CATALOG IF NOT EXISTS " + quote(cfg.catalog())
                                + " PROPERTIES (" + properties + ")");
                return;
            } catch (ApiException e) {
                throw new ApiException(400, LakeCatalogTemplate.message(
                        LakeCatalogTemplate.CATALOG_CREATE_FAILED,
                        "CREATE CATALOG 失败，请检查 DORIS_ADMIN_USER/PASSWORD 与 S3 配置",
                        e.getMessage()));
            }
        }
        try {
            // type 不可改：先确认同名 Catalog 确实是 iceberg，避免把别的 Catalog 改坏
            String existingType = catalogTypeOf(connectionId, cfg.catalog());
            if (existingType != null && !existingType.isBlank()
                    && !"iceberg".equalsIgnoreCase(existingType)) {
                throw new ApiException(400, LakeCatalogTemplate.message(
                        LakeCatalogTemplate.CATALOG_ALTER_FAILED,
                        "同名 Catalog 已存在且类型不是 iceberg（" + existingType + "），请先删除",
                        null));
            }
            // type 不可通过 ALTER 修改：同名 Catalog 若换了 catalogType，只能删除重建。
            admin.execAdmin(connectionId,
                    "ALTER CATALOG " + quote(cfg.catalog())
                            + " SET PROPERTIES ("
                            + propertiesOf(cfg, accessKey, secretKey, false) + ")");
        } catch (ApiException e) {
            if (e.getStatus() == 400) {
                throw e;
            }
            throw new ApiException(400, LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CATALOG_ALTER_FAILED,
                    "Catalog 已存在，更新属性失败；可先删除再保存",
                    e.getMessage()));
        }
    }

    private void refreshCatalog(Long connectionId, String catalog) {
        admin.execAdmin(connectionId, "REFRESH CATALOG " + quote(catalog));
    }

    private boolean catalogExists(Long connectionId, String catalog) {
        for (Map<String, Object> row : admin.queryAdmin(connectionId, "show catalogs")) {
            String name = text(row.get("CatalogName"));
            if (name.isBlank()) {
                name = firstValue(row);
            }
            if (catalog.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String catalogTypeOf(Long connectionId, String catalog) {
        for (Map<String, Object> row : admin.queryAdmin(connectionId, "show catalogs")) {
            String name = text(row.get("CatalogName"));
            if (name.isBlank()) {
                name = firstValue(row);
            }
            if (catalog.equalsIgnoreCase(name)) {
                return text(row.get("Type"));
            }
        }
        return null;
    }

    private String propertiesOf(Validated cfg, String accessKey, String secretKey,
                                boolean includeIdentity) {
        List<String> props = new ArrayList<>();
        if (includeIdentity) {
            props.add(property("type", "iceberg"));
            props.add(property("iceberg.catalog.type", switch (cfg.catalogType()) {
                case "hive" -> "hms";
                default -> cfg.catalogType();
            }));
        }
        props.add(property("warehouse", cfg.warehouse()));
        props.add(property("s3.endpoint", cfg.endpoint()));
        props.add(property("s3.access_key", accessKey == null ? "" : accessKey));
        props.add(property("s3.secret_key", secretKey == null ? "" : secretKey));
        if (!cfg.region().isBlank()) {
            props.add(property("s3.region", cfg.region()));
        }
        if (cfg.pathStyle()) {
            props.add(property("use_path_style", "true"));
        }
        if (!cfg.uri().isBlank()) {
            props.add(property("hive".equals(cfg.catalogType()) ? "hive.metastore.uris" : "uri",
                    cfg.uri()));
        }
        if ("glue".equals(cfg.catalogType())) {
            // Glue：元数据用 glue.* 凭据，数据文件仍用 s3.*
            props.add(property("glue.region", cfg.region()));
            props.add(property("glue.endpoint",
                    "https://glue." + cfg.region() + ".amazonaws.com"));
            props.add(property("glue.access_key", accessKey == null ? "" : accessKey));
            props.add(property("glue.secret_key", secretKey == null ? "" : secretKey));
        }
        return String.join(",\n  ", props);
    }

    private boolean grantCatalogLevel(Long connectionId, String catalog) {
        try {
            admin.execAdmin(connectionId, "GRANT SELECT_PRIV ON " + quote(catalog) + ".*.* TO "
                    + principal());
            return true;
        } catch (ApiException e) {
            log.warn("grant catalog level failed catalog={}: {}", catalog, e.getMessage());
            return false;
        }
    }

    private void grantDatabase(Long connectionId, String catalog, String database,
                               boolean ignoreFailure) {
        try {
            admin.execAdmin(connectionId, "GRANT SELECT_PRIV ON " + quote(catalog) + "."
                    + quote(database) + ".* TO " + principal());
        } catch (ApiException e) {
            if (!ignoreFailure) {
                throw e;
            }
            log.warn("grant lake db failed catalog={} db={}: {}",
                    catalog, database, e.getMessage());
        }
    }

    private void saveGrants(LakeDatasource ds, String catalog, List<String> databases,
                            boolean catalogGrant) {
        jdbc.update("delete from platform.lake_catalog_grant where datasource_id = ?", ds.id());
        if (catalogGrant) {
            insertGrant(ds, catalog, "*");
        }
        for (String database : databases) {
            insertGrant(ds, catalog, database);
        }
    }

    private void insertGrant(LakeDatasource ds, String catalog, String database) {
        jdbc.update("""
                insert into platform.lake_catalog_grant
                    (datasource_id, tenant_id, catalog_name, database_name, principal)
                values (?, ?, ?, ?, ?)
                on conflict (datasource_id, database_name, principal) do nothing
                """, ds.id(), ds.tenantId(), catalog, database, grantUser);
    }

    private String principal() {
        return "'" + grantUser.replace("'", "''") + "'@'%'";
    }

    private void remember(LakeDatasource ds, String catalog, String status, String message) {
        String text = message == null ? null
                : (message.length() > 1000 ? message.substring(0, 1000) : message);
        try {
            jdbc.update("""
                    update platform.datasource
                       set lake_catalog = ?, lake_status = ?, lake_status_message = ?,
                           lake_synced_at = case when ? = 'available' then now() else lake_synced_at end,
                           last_test_at = now(), last_test_ok = ?, last_test_message = ?,
                           engine = 'lake', queryable = true
                     where id = ?
                       and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                    """, catalog, status, text, status,
                    "available".equals(status), shorten(text), ds.id(), ds.tenantId());
        } catch (RuntimeException e) {
            log.warn("update lake status failed ds={}: {}", ds.id(), e.getMessage());
        }
    }

    private Map<String, Object> result(boolean ok, String status, Validated cfg,
                                       List<String> databases, List<String> warnings,
                                       String error, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("status", status);
        out.put("catalog", cfg.catalog());
        out.put("catalogType", cfg.catalogType());
        out.put("warehouse", cfg.warehouse());
        out.put("endpoint", cfg.endpoint());
        out.put("databases", databases);
        out.put("grants", List.of(grantUser + "@%"));
        out.put("warnings", warnings);
        out.put("errorCode", code);
        out.put("error", error);
        return out;
    }

    // ---------------------------------------------------------------- 运维

    /**
     * 未保存表单的连接测试：配置合法 + S3 endpoint 可达 + 管理员连接可用。
     * 还没下发 Catalog，所以不验证 Catalog 本身（保存时会自动下发）。
     */
    public Map<String, Object> testConfig(Map<String, Object> config, String accessKey,
                                          String secretKey) {
        try {
            Validated cfg = validateConfig(config, accessKey, secretKey, false);
            probeEndpoint(cfg.endpoint());
            admin.queryAdmin("show catalogs");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("product", "Doris Iceberg Catalog");
            out.put("catalog", cfg.catalog());
            out.put("note", "保存后将创建/刷新 Catalog 并授权给平台业务账号");
            return out;
        } catch (IllegalArgumentException e) {
            return failed(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CONFIG_INVALID, e.getMessage(), null));
        } catch (ApiException e) {
            return failed(e.getMessage());
        } catch (Exception e) {
            return failed(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.STORAGE_UNREACHABLE, "连接测试失败", e.getMessage()));
        }
    }

    private static Map<String, Object> failed(String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", error == null ? "连接测试失败" : error);
        return out;
    }

    /** 连通性检测：Catalog 存在 + 业务账号可读 + S3 端点 TCP 可达。 */
    public Map<String, Object> test(long datasourceId) {
        LakeDatasource ds = load(datasourceId);
        Validated cfg;
        try {
            cfg = validateConfig(ds.config(), ds.username(), ds.secret(), false);
        } catch (IllegalArgumentException e) {
            String message = LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CONFIG_INVALID, e.getMessage(), null);
            remember(ds, ds.catalog(), "error", message);
            return testResult(false, message, null, List.of());
        }
        Long connectionId = resolver.connectionIdForProject(ds.projectId());
        List<String> databases = new ArrayList<>();
        try {
            if (!catalogExists(connectionId, cfg.catalog())) {
                throw new ApiException(400, LakeCatalogTemplate.message(
                        LakeCatalogTemplate.CATALOG_NOT_FOUND,
                        "Doris 中不存在该 Catalog，请先保存数据源以下发", null));
            }
            probeEndpoint(cfg.endpoint());
            DorisQueryService.Result result = query.rawQuery(ds.projectId(),
                    "show databases from " + quote(cfg.catalog()), 500);
            for (List<String> row : result.rows()) {
                if (row.isEmpty() || row.get(0) == null) {
                    continue;
                }
                String db = row.get(0);
                if (!SYSTEM_DBS.contains(db.toLowerCase(Locale.ROOT))) {
                    databases.add(db);
                }
            }
            remember(ds, cfg.catalog(), "available", null);
            Map<String, Object> out = testResult(true, null, cfg.catalog(), databases);
            out.put("product", "Doris Iceberg Catalog");
            return out;
        } catch (ApiException e) {
            remember(ds, cfg.catalog(), "error", e.getMessage());
            return testResult(false, e.getMessage(), cfg.catalog(), databases);
        } catch (Exception e) {
            String message = LakeCatalogTemplate.message(
                    LakeCatalogTemplate.STORAGE_UNREACHABLE,
                    "无法连接 Warehouse / S3 端点", e.getMessage());
            remember(ds, cfg.catalog(), "error", message);
            return testResult(false, message, cfg.catalog(), databases);
        }
    }

    private Map<String, Object> testResult(boolean ok, String error, String catalog,
                                           List<String> databases) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("catalog", catalog);
        out.put("databases", databases);
        if (!ok) {
            out.put("error", error);
        }
        return out;
    }

    /** 删除 Doris 中的 Catalog（湖数据保留）。confirm 必须等于 Catalog 名。 */
    public Map<String, Object> dropCatalog(long datasourceId, String confirm) {
        LakeDatasource ds = load(datasourceId);
        String catalog = ds.catalog();
        if (catalog == null || catalog.isBlank()) {
            throw ApiException.badRequest("该数据源还没有创建 Catalog");
        }
        if (!catalog.equals(confirm == null ? "" : confirm.trim())) {
            throw ApiException.badRequest("删除确认需要与 Catalog 名一致: " + catalog);
        }
        Integer managed = jdbc.queryForObject("""
                select count(*) from platform.managed_table
                 where tenant = ? and catalog = ?
                """, Integer.class, ds.tenantId(), catalog);
        if (managed != null && managed > 0) {
            throw ApiException.conflict(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CATALOG_IN_USE,
                    "还有 " + managed + " 张湖表仍纳管在当前 Catalog，请先在数据分级里删除纳管记录",
                    null));
        }
        Long connectionId = resolver.connectionIdForProject(ds.projectId());
        admin.execAdmin(connectionId, "DROP CATALOG IF EXISTS " + quote(catalog));
        jdbc.update("delete from platform.lake_catalog_grant where datasource_id = ?", ds.id());
        jdbc.update("""
                update platform.datasource
                   set lake_catalog = null, lake_status = 'unknown',
                       lake_status_message = 'Catalog 已删除（湖数据保留）',
                       lake_synced_at = null
                 where id = ?
                """, ds.id());
        return Map.of("ok", true, "catalog", catalog,
                "message", "Catalog 已删除，Iceberg 数据与 Warehouse 文件保留");
    }

    /**
     * 删除数据源前清理外部资源：尽力删除 Catalog，并清掉该 Catalog 的纳管记录，
     * 避免留下指向不存在 Catalog 的权限申请。
     */
    public void dropForDatasourceDelete(long datasourceId) {
        LakeDatasource ds = load(datasourceId);
        if (ds.catalog() == null || ds.catalog().isBlank()) {
            return;
        }
        try {
            Long connectionId = resolver.connectionIdForProject(ds.projectId());
            admin.execAdmin(connectionId, "DROP CATALOG IF EXISTS " + quote(ds.catalog()));
        } catch (RuntimeException e) {
            log.warn("drop lake catalog failed ds={} catalog={}: {}",
                    datasourceId, ds.catalog(), e.getMessage());
        }
        jdbc.update("""
                delete from platform.managed_table where tenant = ? and catalog = ?
                """, ds.tenantId(), ds.catalog());
        jdbc.update("delete from platform.lake_catalog_grant where datasource_id = ?", ds.id());
    }

    // ---------------------------------------------------------------- 元数据浏览

    public Map<String, Object> databases(String catalog) {
        OwnedCatalog owned = requireOwned(catalog);
        ensureUsable(owned);
        DorisQueryService.Result result = query.rawQuery(owned.projectId(),
                "show databases from " + quote(owned.catalog()), 500);
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<String> row : result.rows()) {
            if (row.isEmpty() || row.get(0) == null) {
                continue;
            }
            String db = row.get(0);
            if (SYSTEM_DBS.contains(db.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("database", db);
            out.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog", owned.catalog());
        body.put("catalogType", owned.catalogType());
        body.put("status", owned.status());
        body.put("databases", out);
        return body;
    }

    public Map<String, Object> tables(String catalog, String database, boolean withCount) {
        OwnedCatalog owned = requireOwned(catalog);
        String db = LakeCatalogTemplate.requireIdentifier(database, "库名");
        ensureUsable(owned);
        DorisQueryService.Result result = query.rawQuery(owned.projectId(),
                "show tables from " + quote(owned.catalog()) + "." + quote(db), 2000);
        List<Map<String, Object>> out = new ArrayList<>();
        int counted = 0;
        for (List<String> row : result.rows()) {
            String table = pick(result.columns(), row, "Table", "TABLE_NAME", 0);
            if (table == null || table.isBlank()) {
                continue;
            }
            String type = pick(result.columns(), row, "Type", "TABLE_TYPE", 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", table);
            item.put("type", type);
            item.put("comment", null);
            item.put("rows", null);
            if (withCount && counted < 50) {
                counted++;
                try {
                    DorisQueryService.Result count = query.rawQuery(owned.projectId(),
                            "select count(*) from " + quote(owned.catalog()) + "."
                                    + quote(db) + "." + quote(table), 1);
                    if (!count.rows().isEmpty() && !count.rows().get(0).isEmpty()) {
                        item.put("rows", count.rows().get(0).get(0));
                    }
                } catch (RuntimeException e) {
                    item.put("rowsError", e.getMessage());
                }
            }
            out.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog", owned.catalog());
        body.put("database", db);
        body.put("tables", out);
        return body;
    }

    public Map<String, Object> tableDetail(String catalog, String database, String table) {
        OwnedCatalog owned = requireOwned(catalog);
        String db = LakeCatalogTemplate.requireIdentifier(database, "库名");
        String tbl = LakeCatalogTemplate.requireIdentifier(table, "表名");
        ensureUsable(owned);
        String full = quote(owned.catalog()) + "." + quote(db) + "." + quote(tbl);
        List<Map<String, Object>> columns = new ArrayList<>();
        try {
            DorisQueryService.Result desc = query.rawQuery(owned.projectId(), "desc " + full, 1000);
            for (List<String> row : desc.rows()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", pick(desc.columns(), row, "Field", "COLUMN_NAME", 0));
                col.put("type", pick(desc.columns(), row, "Type", "DATA_TYPE", 1));
                String nullable = pick(desc.columns(), row, "Null", "IS_NULLABLE", 2);
                col.put("nullable", nullable == null || !"NO".equalsIgnoreCase(nullable.trim()));
                col.put("key", pick(desc.columns(), row, "Key", "COLUMN_KEY", 3));
                col.put("comment", pick(desc.columns(), row, "Comment", "COLUMN_COMMENT", 4));
                if (col.get("name") != null) {
                    columns.add(col);
                }
            }
        } catch (ApiException e) {
            if (isMissing(e.getMessage())) {
                throw ApiException.notFound(LakeCatalogTemplate.message(
                        LakeCatalogTemplate.TABLE_NOT_FOUND, "表不存在", db + "." + tbl));
            }
            throw e;
        }
        if (columns.isEmpty()) {
            throw ApiException.notFound(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.TABLE_NOT_FOUND, "表不存在或没有字段", db + "." + tbl));
        }
        List<String> warnings = new ArrayList<>();
        String ddl = safeShowCreate(owned, full);
        Map<String, Object> partition = partitionInfo(owned, full);
        Object location = locate(owned, full, ddl);
        Object rows = safeCount(owned, full, warnings);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog", owned.catalog());
        out.put("database", db);
        out.put("table", tbl);
        out.put("columns", columns);
        out.put("partition", partition);
        out.put("location", location);
        out.put("rows", rows);
        out.put("ddl", ddl);
        out.put("warnings", warnings);
        return out;
    }

    private String safeShowCreate(OwnedCatalog owned, String full) {
        try {
            DorisQueryService.Result result = query.rawQuery(owned.projectId(),
                    "show create table " + full, 1);
            if (result.rows().isEmpty()) {
                return null;
            }
            List<String> row = result.rows().get(0);
            for (int i = 1; i < row.size(); i++) {
                if (row.get(i) != null && !row.get(i).isBlank()) {
                    return row.get(i);
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> partitionInfo(OwnedCatalog owned, String full) {
        try {
            DorisQueryService.Result result = query.rawQuery(owned.projectId(),
                    "show partitions from " + full, 200);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (List<String> row : result.rows()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("partition", pick(result.columns(), row, "Partition", "PARTITION", 0));
                item.put("rows", pick(result.columns(), row, "RowCount", "ROW_COUNT", 1));
                rows.add(item);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", true);
            out.put("source", "show partitions");
            out.put("partitions", rows);
            return out;
        } catch (RuntimeException e) {
            // Iceberg hadoop 某些 Doris 版本不返回 SHOW PARTITIONS，退回元数据表
            try {
                DorisQueryService.Result result = query.rawQuery(owned.projectId(),
                        "select * from " + full + "$partitions limit 50", 50);
                List<String> partitions = new ArrayList<>();
                for (List<String> row : result.rows()) {
                    String value = pick(result.columns(), row, "partition", "PARTITION", 0);
                    if (value != null) {
                        partitions.add(value);
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("available", true);
                out.put("source", "$partitions");
                out.put("partitions", partitions);
                return out;
            } catch (RuntimeException ignored) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("available", false);
                out.put("reason", "当前 Doris 版本未暴露 Iceberg 分区信息（可查看 DDL 或 S3 目录）");
                out.put("partitions", List.of());
                return out;
            }
        }
    }

    private Object locate(OwnedCatalog owned, String full, String ddl) {
        if (ddl != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?i)(?:location|LOCATION)\\s*=?\\s*'([^']+)'").matcher(ddl);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        try {
            DorisQueryService.Result result = query.rawQuery(owned.projectId(),
                    "select file_path from " + full + "$files limit 1", 1);
            if (!result.rows().isEmpty() && !result.rows().get(0).isEmpty()) {
                return result.rows().get(0).get(0);
            }
        } catch (RuntimeException ignored) {
            // $files 元数据表不是所有版本都支持
        }
        return null;
    }

    private Object safeCount(OwnedCatalog owned, String full, List<String> warnings) {
        try {
            DorisQueryService.Result result = query.rawQuery(owned.projectId(),
                    "select count(*) from " + full, 1);
            if (!result.rows().isEmpty() && !result.rows().get(0).isEmpty()) {
                return result.rows().get(0).get(0);
            }
        } catch (RuntimeException e) {
            warnings.add("行数统计失败（大表 COUNT 可能超时，不影响查询）: " + e.getMessage());
        }
        return null;
    }

    // ---------------------------------------------------------------- 湖表 DDL

    public Map<String, Object> createDatabase(String catalog, String database, String comment,
                                              String actor) {
        OwnedCatalog owned = requireOwned(catalog);
        String db = LakeCatalogTemplate.requireIdentifier(database, "库名");
        ensureUsable(owned);
        Long connectionId = resolver.connectionIdForProject(owned.projectId());
        String ddl;
        try {
            ddl = LakeDdl.createDatabase(owned.catalog(), db, comment);
            admin.execAdmin(connectionId, ddl);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.DDL_INVALID, e.getMessage(), null));
        } catch (ApiException e) {
            throw new ApiException(400, LakeCatalogTemplate.message(
                    LakeCatalogTemplate.DDL_FAILED, "创建湖库失败", e.getMessage()));
        }
        refreshCatalog(connectionId, owned.catalog());
        grantDatabase(connectionId, owned.catalog(), db, true);
        rememberGrant(owned.datasourceId(), owned.catalog(), db);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("catalog", owned.catalog());
        out.put("database", db);
        out.put("ddl", ddl);
        return out;
    }

    public Map<String, Object> createTable(String catalog, String database, String table,
                                           String comment, List<LakeDdl.Column> columns,
                                           LakeDdl.Partition partition, Integer formatVersion,
                                           String actor) {
        OwnedCatalog owned = requireOwned(catalog);
        String db = LakeCatalogTemplate.requireIdentifier(database, "库名");
        ensureUsable(owned);
        LakeDdl.Result plan;
        try {
            plan = LakeDdl.generate(owned.catalog(), db, table, comment, columns, partition,
                    formatVersion);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.DDL_INVALID, e.getMessage(), null));
        }
        Long connectionId = resolver.connectionIdForProject(owned.projectId());
        try {
            admin.execAdmin(connectionId, plan.ddl());
        } catch (ApiException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            String code = detail.toLowerCase(Locale.ROOT).contains("s3")
                    || detail.toLowerCase(Locale.ROOT).contains("endpoint")
                    ? LakeCatalogTemplate.STORAGE_UNREACHABLE
                    : LakeCatalogTemplate.DDL_FAILED;
            log.warn("lake create table failed catalog={} db={} table={}: {}",
                    owned.catalog(), db, table, detail);
            throw new ApiException(400, LakeCatalogTemplate.message(
                    code, "建表失败，请检查字段类型/分区列/存储配置", detail));
        }
        refreshCatalog(connectionId, owned.catalog());
        // Doris 对部分外部 Catalog 的 DDL 只回执不落表（或解析器静默跳过），
        // 必须自查一次：没建成时把 Doris 的原始错误回传，不能吞成 200。
        verifyTableExists(connectionId, owned.catalog(), db, table);
        grantDatabase(connectionId, owned.catalog(), db, true);
        rememberGrant(owned.datasourceId(), owned.catalog(), db);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("catalog", owned.catalog());
        out.put("database", db);
        out.put("table", table);
        out.put("ddl", plan.ddl());
        out.put("warnings", plan.warnings());
        out.put("partitionKind", plan.partitionKind());
        return out;
    }

    /**
     * 建表后核对表是否真的落到了 Catalog 里。
     *
     * <p>实测 Doris 4.1.4 在外部 Catalog 上可能「执行成功但表不存在」，
     * 因此 {@code JDBC execute()} 不抛异常不足以判定成功。这里用
     * {@code SHOW TABLES} 复核；找不到时再跑 {@code SHOW CREATE TABLE}
     * 拿 Doris 的原始报错并回传（400 / LAKE_DDL_FAILED），绝不返回 200。</p>
     */
    private void verifyTableExists(Long connectionId, String catalog, String db, String table) {
        String full = quote(catalog) + "." + quote(db) + "." + quote(table);
        List<Map<String, Object>> rows;
        try {
            rows = admin.queryAdmin(connectionId,
                    "show tables from " + quote(catalog) + "." + quote(db));
        } catch (ApiException e) {
            throw new ApiException(400, LakeCatalogTemplate.message(
                    LakeCatalogTemplate.DDL_FAILED,
                    "建表后核对失败：Doris 未确认表 " + db + "." + table + " 存在",
                    e.getMessage()));
        }
        for (Map<String, Object> row : rows) {
            for (Object value : row.values()) {
                if (value != null && table.equalsIgnoreCase(String.valueOf(value).trim())) {
                    return;
                }
            }
        }
        // SHOW TABLES 可能只是元数据列表滞后；用 SHOW CREATE TABLE 再确认一次。
        // 它成功 = 表确实存在；它失败时返回的就是 Doris 的原始原因（如 Table does not exist）。
        String detail = "Doris 执行 DDL 返回成功，但 SHOW TABLES 中找不到该表";
        try {
            admin.queryAdmin(connectionId, "show create table " + full);
            return;
        } catch (ApiException e) {
            detail = e.getMessage();
        }
        log.warn("lake create table not materialized catalog={} db={} table={}: {}",
                catalog, db, table, detail);
        throw new ApiException(400, LakeCatalogTemplate.message(
                LakeCatalogTemplate.DDL_FAILED,
                "建表未生效，" + db + "." + table + " 不存在", detail));
    }

    /**
     * 查询湖表纳管记录（managed_table）。建表自动纳管后即使接口兜底分支
     * 也需要回传 id，避免「已纳管但响应没有 managedTableId」。
     */
    public Map<String, Object> managedTable(String catalog, String database, String table) {
        if (catalog == null || database == null || table == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, data_level from platform.managed_table
                 where tenant = ? and catalog = ? and schema_name = ? and table_name = ?
                 order by id desc limit 1
                """, TenantScope.current(), catalog, database.trim(), table.trim());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("dataLevel", row.get("data_level"));
        return out;
    }

    /** 建表 DDL 预览：只生成不执行。 */
    public Map<String, Object> previewTable(String catalog, String database, String table,
                                            String comment, List<LakeDdl.Column> columns,
                                            LakeDdl.Partition partition, Integer formatVersion) {
        OwnedCatalog owned = requireOwned(catalog);
        LakeDdl.Result plan;
        try {
            plan = LakeDdl.generate(owned.catalog(), database, table, comment, columns, partition,
                    formatVersion);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.DDL_INVALID, e.getMessage(), null));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ddl", plan.ddl());
        out.put("warnings", plan.warnings());
        out.put("partitionKind", plan.partitionKind());
        return out;
    }

    public void dropTable(String catalog, String database, String table, String confirm) {
        OwnedCatalog owned = requireOwned(catalog);
        String db = LakeCatalogTemplate.requireIdentifier(database, "库名");
        String tbl = LakeCatalogTemplate.requireIdentifier(table, "表名");
        if (!tbl.equals(confirm == null ? "" : confirm.trim())) {
            throw ApiException.badRequest("删除确认需要与表名一致: " + tbl);
        }
        ensureUsable(owned);
        Long connectionId = resolver.connectionIdForProject(owned.projectId());
        try {
            admin.execAdmin(connectionId,
                    LakeDdl.dropTable(owned.catalog(), db, tbl));
        } catch (ApiException e) {
            throw new ApiException(400, LakeCatalogTemplate.message(
                    LakeCatalogTemplate.DDL_FAILED, "删除湖表失败", e.getMessage()));
        }
        jdbc.update("""
                delete from platform.managed_table
                 where tenant = ? and catalog = ? and schema_name = ? and table_name = ?
                """, TenantScope.current(), owned.catalog(), db, tbl);
        refreshCatalog(connectionId, owned.catalog());
    }

    /** 新建库/表后补记一条授权（不重建整张对账表，避免丢掉其它库）。 */
    private void rememberGrant(long datasourceId, String catalog, String database) {
        try {
            LakeDatasource ds = load(datasourceId);
            insertGrant(ds, catalog, database);
        } catch (RuntimeException e) {
            log.warn("remember lake grant failed ds={} catalog={} db={}: {}",
                    datasourceId, catalog, database, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 辅助

    private void ensureUsable(OwnedCatalog owned) {
        if ("error".equalsIgnoreCase(owned.status())) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CATALOG_UNAVAILABLE,
                    "Catalog 状态异常，请到数据源页「测试连接 / 刷新 Catalog」修复",
                    owned.statusMessage()));
        }
    }

    /** 查询前置校验：catalog 是否属于当前租户且状态可用。 */
    public OwnedCatalog requireQueryable(String catalog) {
        OwnedCatalog owned = requireOwned(catalog);
        ensureUsable(owned);
        return owned;
    }

    private final Map<String, Long> readableAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 查询前置校验（V100）：登记归属 + 状态 + 业务账号真的能读（SHOW DATABASES）。
     *
     * <p>结果按 project+catalog 缓存 30 秒：首次查询验证一次，之后不额外增加往返；
     * Catalog 被外部删除/权限被回收时最多 30 秒后重新校验到。</p>
     */
    public void assertQueryable(String catalog) {
        OwnedCatalog owned = requireQueryable(catalog);
        String key = owned.projectId() + "|" + owned.catalog().toLowerCase(Locale.ROOT);
        Long at = readableAt.get(key);
        long now = System.currentTimeMillis();
        if (at != null && now - at < 30_000) {
            return;
        }
        try {
            query.rawQuery(owned.projectId(), "show databases from " + quote(owned.catalog()), 1);
            readableAt.put(key, now);
        } catch (ApiException e) {
            String code = classify(e.getMessage(), LakeCatalogTemplate.CATALOG_UNAVAILABLE);
            throw ApiException.badRequest(LakeCatalogTemplate.message(code,
                    "Catalog 不存在、状态异常或平台账号无 SELECT 权限", e.getMessage()));
        } catch (RuntimeException e) {
            throw ApiException.badRequest(LakeCatalogTemplate.message(
                    LakeCatalogTemplate.CATALOG_UNAVAILABLE,
                    "Catalog 不存在或平台账号无 SELECT 权限", e.getMessage()));
        }
    }

    private void probeEndpoint(String endpoint) {
        Endpoint parsed = parseEndpoint(endpoint);
        // 再走一次出网守卫：保存后白名单可能被改小
        JdbcUrlGuard.validateDestination(parsed.host(), parsed.port());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parsed.host(), parsed.port()), 3000);
        } catch (Exception e) {
            throw new ApiException(400, LakeCatalogTemplate.message(
                    LakeCatalogTemplate.STORAGE_UNREACHABLE,
                    "无法连接 S3 endpoint " + parsed.host() + ":" + parsed.port(), e.getMessage()));
        }
    }

    /** hive/rest 的 uri 只校验主机与端口是否触犯出网策略；协议本身由 Doris 处理。 */
    private static void checkUri(String uri) {
        int scheme = uri.indexOf("://");
        if (scheme < 0) {
            return;
        }
        String authority = uri.substring(scheme + 3);
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            authority = authority.substring(0, slash);
        }
        String host = authority;
        int port = -1;
        int colon = authority.lastIndexOf(':');
        if (colon > 0) {
            host = authority.substring(0, colon);
            port = parseInt(authority.substring(colon + 1), -1);
        }
        JdbcUrlGuard.validateDestination(host, port);
    }

    private record Endpoint(String host, int port) {
    }

    private static Endpoint parseEndpoint(String raw) {
        String value = raw == null ? "" : raw.trim();
        int scheme = value.indexOf("://");
        String authority = scheme > 0 ? value.substring(scheme + 3) : value;
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            authority = authority.substring(0, slash);
        }
        String host = authority;
        int port = value.startsWith("https://") ? 443 : 9000;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close > 0) {
                host = authority.substring(1, close);
                if (close + 1 < authority.length() && authority.charAt(close + 1) == ':') {
                    port = parseInt(authority.substring(close + 2), port);
                }
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon > 0) {
                host = authority.substring(0, colon);
                port = parseInt(authority.substring(colon + 1), port);
            }
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("S3 endpoint 不合法: " + raw);
        }
        return new Endpoint(host, port);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String property(String key, String value) {
        return "'" + escapeProperty(key) + "' = '" + escapeProperty(value) + "'";
    }

    private static String escapeProperty(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("'", "''")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static boolean isPathStyle(Map<String, Object> config) {
        Object raw = config.get("usePathStyle");
        if (raw == null) {
            return true; // MinIO 默认需要 path style
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String classify(String message, String fallback) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("access denied") || lower.contains("permission")
                || lower.contains("denied") || lower.contains("not authorized")) {
            return LakeCatalogTemplate.GRANT_FAILED;
        }
        if (lower.contains("unknown catalog") || lower.contains("not found")) {
            return LakeCatalogTemplate.CATALOG_NOT_FOUND;
        }
        if (lower.contains("s3") || lower.contains("endpoint") || lower.contains("connect")
                || lower.contains("timeout") || lower.contains("unknownhost")) {
            return LakeCatalogTemplate.STORAGE_UNREACHABLE;
        }
        return fallback;
    }

    private static boolean isMissing(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lower.contains("unknown table") || lower.contains("does not exist")
                || lower.contains("not found");
    }

    private static String shorten(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String pick(List<String> columns, List<String> row, String name,
                               String alternative, int index) {
        if (columns != null) {
            for (int i = 0; i < columns.size() && i < row.size(); i++) {
                String column = columns.get(i);
                if (column != null && (column.equalsIgnoreCase(name)
                        || column.equalsIgnoreCase(alternative))) {
                    return row.get(i);
                }
            }
        }
        return row.size() > index ? row.get(index) : null;
    }

    private static String firstValue(Map<String, Object> row) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private Map<String, Object> configOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
