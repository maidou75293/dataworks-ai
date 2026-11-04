package com.lab.dataworks.project;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数仓解析：项目只引用一个 {@code type=DORIS} 数据源，真正的执行入口仍是
 * {@code warehouse_connection}（查询/表管理/DDL 全部按连接 id 走）。
 *
 * <p>统一规则（V98 起）：
 * <ul>
 *   <li>项目上的 {@code warehouse_datasource_id} 指向平台数据源；</li>
 *   <li>内置 Doris 数据源（{@code config_json.builtin=true}）→ 返回 null，查询仍走 MCP；</li>
 *   <li>外部 Doris 数据源 → 按 JDBC 地址里的 host:port 找/建 type=doris 的
 *       {@code warehouse_connection}，返回其 id；</li>
 *   <li>{@code warehouse_datasource_id} 为空 → 回退旧的 {@code doris_connection_id}。</li>
 * </ul>
 *
 * <p>默认存储（HDFS/S3）归属数仓连接：{@code warehouse_connection.default_storage_*}。
 * 项目不感知存储；同一个数据源被多个项目引用时读到的是同一份默认存储。</p>
 */
@Service
public class WarehouseResolver {

    private static final Logger log = LoggerFactory.getLogger(WarehouseResolver.class);
    // REQ-DW-5：sso＝单点登录方式访问存储，与 hdfs/s3 同为「数据源方式」的通用选项
    private static final List<String> STORAGE_TYPES = List.of("none", "hdfs", "s3", "sso");

    /** 数据源视图；encryptedPassword 是 platform.datasource.password 的密文。 */
    public record DorisDatasource(long id, Long projectId, String projectTenant, String name,
                                  String dsType, String jdbcUrl, String username,
                                  String encryptedPassword, boolean builtin) {
    }

    /** 数仓连接的默认存储；type=null/none 表示未配置。 */
    public record Storage(String type, String endpoint, String path) {
        public boolean configured() {
            return type != null && !type.isBlank() && !"none".equalsIgnoreCase(type);
        }
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final String builtinHost;
    private final int builtinPort;

    public WarehouseResolver(JdbcTemplate jdbc, SecretCipher cipher,
                             @Value("${doris.host:127.0.0.1}") String builtinHost,
                             @Value("${doris.port:9030}") int builtinPort) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.builtinHost = builtinHost;
        this.builtinPort = builtinPort;
    }

    /**
     * 项目最终该用哪个 warehouse_connection：
     * null = 平台内置（MCP），非 null = 外部 Doris 连接。
     *
     * <p>projectId 为空 = 平台内置；项目不存在或不属于当前租户按 404 处理。</p>
     */
    public Long connectionIdForProject(Long projectId) {
        if (projectId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant_id, warehouse_datasource_id, doris_connection_id
                  from platform.project
                 where id = ? and tenant_id = ?
                """, projectId, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
        Object raw = rows.get(0).get("warehouse_datasource_id");
        if (raw == null) {
            // 旧项目：还没回填数据源时保持原有路由
            return longOrNull(rows.get(0).get("doris_connection_id"));
        }
        DorisDatasource datasource = requireDorisDatasource(((Number) raw).longValue());
        // 内置 Doris 仍然走 MCP：不返回连接 id，避免被误当成外部 JDBC 直连
        return datasource.builtin() ? null : ensureConnection(datasource, TenantScope.current());
    }

    /**
     * 项目创建/编辑时校验用户选择的数据源：
     * 不属于当前租户统一 404（不暴露其他租户是否存在），类型不是 DORIS 返回 400。
     */
    public DorisDatasource requireDorisDatasource(Long datasourceId) {
        if (datasourceId == null) {
            throw ApiException.badRequest("请选择 Doris 数据源");
        }
        DorisDatasource datasource = findDatasource(datasourceId);
        if (datasource == null) {
            throw ApiException.notFound("数据源不存在: " + datasourceId);
        }
        String tenant = TenantScope.current();
        if (!datasource.builtin() && !tenant.equals(datasource.projectTenant())) {
            // 跨租户与不存在同口径，避免枚举 id
            throw ApiException.notFound("数据源不存在: " + datasourceId);
        }
        if (!"DORIS".equalsIgnoreCase(datasource.dsType())) {
            throw ApiException.badRequest(
                    "项目数仓必须选择 DORIS 类型的数据源（当前 " + datasource.dsType() + "）");
        }
        return datasource;
    }

    /** 平台内置 Doris 数据源 id；没有时返回 null。 */
    public Long builtinDatasourceId() {
        List<Long> ids = jdbc.queryForList("""
                select d.id
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where coalesce(d.config_json ->> 'builtin', 'false') = 'true'
                 order by case when d.id = 19 then 0 else 1 end, d.id
                 limit 1
                """, Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 数据源对应的 warehouse_connection id；内置也返回（供存储读取/写入用）。 */
    public Long warehouseConnectionIdForDatasource(Long datasourceId) {
        DorisDatasource datasource = requireDorisDatasource(datasourceId);
        return ensureConnection(datasource, TenantScope.current());
    }

    /** 读数据源的默认存储；读不到连接时返回 null（不报错）。 */
    public Storage storageForDatasource(Long datasourceId) {
        DorisDatasource datasource = requireDorisDatasource(datasourceId);
        String endpoint = endpointOf(datasource);
        if (endpoint == null) {
            return null;
        }
        Long connectionId = findConnectionId(TenantScope.current(), endpoint);
        if (connectionId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select default_storage_type, default_storage_endpoint, default_storage_path
                  from platform.warehouse_connection
                 where id = ? and tenant_id = ?
                """, connectionId, TenantScope.current());
        if (rows.isEmpty()) {
            return null;
        }
        return toStorage(rows.get(0));
    }

    /** 写数据源默认存储：没有连接时先按数据源端点建一个。 */
    public Storage saveStorage(Long datasourceId, Storage storage) {
        Storage normalized = normalizeStorage(storage);
        DorisDatasource datasource = requireDorisDatasource(datasourceId);
        Long connectionId = ensureConnection(datasource, TenantScope.current());
        int updated = jdbc.update("""
                update platform.warehouse_connection
                   set default_storage_type = ?, default_storage_endpoint = ?,
                       default_storage_path = ?
                 where id = ? and tenant_id = ?
                """, normalized.type(), normalized.endpoint(), normalized.path(),
                connectionId, TenantScope.current());
        if (updated == 0) {
            throw ApiException.notFound("数仓连接不存在: " + connectionId);
        }
        return normalized;
    }

    // ---------------------------------------------------------------- 内部

    private DorisDatasource findDatasource(long datasourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.project_id, d.name, d.ds_type, d.jdbc_url, d.username, d.password,
                       p.tenant_id as project_tenant,
                       coalesce(d.config_json ->> 'builtin', 'false') = 'true' as builtin
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where d.id = ?
                """, datasourceId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new DorisDatasource(((Number) row.get("id")).longValue(),
                row.get("project_id") == null ? null : ((Number) row.get("project_id")).longValue(),
                str(row.get("project_tenant")), str(row.get("name")), str(row.get("ds_type")),
                str(row.get("jdbc_url")), str(row.get("username")), str(row.get("password")),
                truthy(row.get("builtin")));
    }

    private Long ensureConnection(DorisDatasource datasource, String tenant) {
        String endpoint = endpointOf(datasource);
        if (endpoint == null) {
            throw ApiException.badRequest("Doris 数据源缺少 JDBC 地址: " + datasource.id());
        }
        Long existing = findConnectionId(tenant, endpoint);
        if (existing != null) {
            return existing;
        }
        String name = datasource.builtin() ? "内置 Doris" : "数据源 " + datasource.id();
        String password = cipher.decrypt(datasource.encryptedPassword());
        String encrypted = password == null || password.isEmpty() ? null : cipher.encrypt(password);
        try {
            Long id = jdbc.queryForObject("""
                    insert into platform.warehouse_connection
                        (tenant_id, name, type, provider, endpoint, username,
                         encrypted_password, default_storage_type)
                    values (?, ?, 'doris', null, ?, ?, ?, 'none')
                    returning id
                    """, Long.class, tenant, name, endpoint, datasource.username(), encrypted);
            log.info("按数据源建立 Doris 数仓连接 datasource={} tenant={} endpoint={} id={}",
                    datasource.id(), tenant, endpoint, id);
            return id;
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求刚插入同名/同端点连接
            Long id = findConnectionId(tenant, endpoint);
            if (id != null) {
                return id;
            }
            throw e;
        }
    }

    /** 按端点找连接；兼容历史里存成 jdbc: 前缀的 endpoint。 */
    private Long findConnectionId(String tenant, String endpoint) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, endpoint
                  from platform.warehouse_connection
                 where tenant_id = ? and type = 'doris'
                 order by id
                """, tenant);
        for (Map<String, Object> row : rows) {
            String stored = endpointOf(str(row.get("endpoint")));
            if (endpoint.equals(stored)) {
                return ((Number) row.get("id")).longValue();
            }
        }
        return null;
    }

    private String endpointOf(DorisDatasource datasource) {
        String endpoint = endpointOf(datasource.jdbcUrl());
        if (endpoint == null && datasource.builtin()) {
            // 内置数据源被人工改坏 jdbc_url 时仍回退到部署配置，避免整站查询不可用
            endpoint = builtinHost + ":" + builtinPort;
        }
        return endpoint;
    }

    /** 从 jdbc:mysql://host:port/db?x 里取 host:port；已是 host:port 时原样返回。 */
    static String endpointOf(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String value = jdbcUrl.trim();
        int scheme = value.indexOf("://");
        if (scheme > 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        return value.isBlank() ? null : value;
    }

    private Storage normalizeStorage(Storage storage) {
        String type = storage == null || storage.type() == null || storage.type().isBlank()
                ? "none" : storage.type().trim().toLowerCase(Locale.ROOT);
        if (!STORAGE_TYPES.contains(type)) {
            throw ApiException.badRequest("storageType 只能是 none / hdfs / s3 / sso");
        }
        if (!"none".equals(type) && (storage.endpoint() == null || storage.endpoint().isBlank())) {
            throw ApiException.badRequest("选择 " + type + " 存储时必须填写 storageEndpoint");
        }
        return new Storage(type,
                storage == null ? null : trimToNull(storage.endpoint()),
                storage == null ? null : trimToNull(storage.path()));
    }

    private static Storage toStorage(Map<String, Object> row) {
        String type = str(row.get("default_storage_type"));
        return new Storage(type == null || type.isBlank() ? "none" : type,
                str(row.get("default_storage_endpoint")), str(row.get("default_storage_path")));
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
