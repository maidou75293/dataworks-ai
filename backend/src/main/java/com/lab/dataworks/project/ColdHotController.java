package com.lab.dataworks.project;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.system.DorisFeSqlService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Doris 冷热分离：远端存储 Resource + 三档 Storage Policy + 表挂策略。
 *
 * <p>语义（不要理解错）：
 * <ul>
 *   <li>{@code cooldown_ttl} 从**最后一次访问**开始计时，不是建表时间；</li>
 *   <li>下沉单位是**分区**，未分区的表不会下沉；</li>
 *   <li>下沉后查询对用户透明（Doris 自动读远端）。</li>
 * </ul>
 * 策略是可选增强：建表不挂策略 = 数据始终留在本地（红线 4）。</p>
 */
@RestController
@RequestMapping("/api/storage/cold-hot")
public class ColdHotController {

    private static final Logger log = LoggerFactory.getLogger(ColdHotController.class);
    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    private static final String RESOURCE = "dw_cold_s3";
    private static final Map<String, String> POLICIES = new LinkedHashMap<>();

    static {
        POLICIES.put("p_realtime", "9999d");
        POLICIES.put("p_warm", "30d");
        POLICIES.put("p_cold", "1d");
    }

    private final DorisAdminService doris;
    /** 需要 Admin_priv 的语句（RESOURCE / STORAGE POLICY）走 FE 容器内的 root；业务查询仍走 JDBC。 */
    private final DorisFeSqlService feSql;
    private final SessionService sessions;
    private final WarehouseConnectionService connections;
    private final com.lab.dataworks.common.TenantDataScope tenantScope;
    private final JdbcTemplate jdbc;
    private final String s3Endpoint;
    private final String s3Bucket;
    private final String s3AccessKey;
    private final String s3SecretKey;
    private final String s3Region;
    private final String s3Root;

    public ColdHotController(DorisAdminService doris, DorisFeSqlService feSql,
                             SessionService sessions, WarehouseConnectionService connections,
                             com.lab.dataworks.common.TenantDataScope tenantScope,
                             JdbcTemplate jdbc,
                             @Value("${platform.storage.s3.endpoint:${S3_ENDPOINT:http://minio.dw-dev.svc.cluster.local:9000}}")
                             String s3Endpoint,
                             @Value("${platform.storage.s3.bucket:${S3_BUCKET:warehouse}}") String s3Bucket,
                             @Value("${platform.storage.s3.access-key:${S3_ACCESS_KEY:}}") String s3AccessKey,
                             @Value("${platform.storage.s3.secret-key:${S3_SECRET_KEY:}}") String s3SecretKey,
                             @Value("${platform.storage.s3.region:us-east-1}") String s3Region,
                             @Value("${platform.storage.cold.root-path:cold}") String s3Root) {
        this.doris = doris;
        this.feSql = feSql;
        this.sessions = sessions;
        this.connections = connections;
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.s3Endpoint = s3Endpoint;
        this.s3Bucket = s3Bucket;
        this.s3AccessKey = s3AccessKey;
        this.s3SecretKey = s3SecretKey;
        this.s3Region = s3Region;
        this.s3Root = s3Root;
    }

    /** 当前状态：远端 Resource 与三档策略是否就绪。 */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", s3AccessKey != null && !s3AccessKey.isBlank());
        List<String> resourceNames = new ArrayList<>();
        try {
            for (Map<String, String> row : feSql.query("SHOW RESOURCES")) {
                resourceNames.add(String.valueOf(row.get("Name")));
            }
        } catch (ApiException ignored) {
            // Doris 不可用时返回空列表，由前端提示
        }
        List<String> policyNames = new ArrayList<>();
        try {
            for (Map<String, String> row : feSql.query("SHOW STORAGE POLICY")) {
                policyNames.add(String.valueOf(row.get("PolicyName")));
            }
        } catch (ApiException ignored) {
            // 同上
        }
        out.put("s3Connections", jdbc.queryForList("""
                select id, name, provider, endpoint, bucket_or_path as bucket
                  from platform.warehouse_connection
                 where tenant_id = ? and type = 's3' order by id
                """, TenantScope.current()));
        out.put("resource", RESOURCE);
        out.put("resourceExists", resourceNames.contains(RESOURCE));
        out.put("policies", new LinkedHashMap<>(POLICIES));
        out.put("policyExists", policyNames);
        return out;
    }

    /** 冷热分离可以显式指定一个 S3 数仓连接；不传则用平台内置 MinIO 配置。 */
    public record InitRequest(Long s3ConnectionId) {
    }

    /** 幂等创建 Resource + 三档策略；已存在的跳过。 */
    @PostMapping("/init")
    @RequireAtom("project:manage")
    public Map<String, Object> init(@RequestBody(required = false) InitRequest req,
                                    HttpServletRequest http) {
        requirePlatform();
        String endpoint = s3Endpoint;
        String bucket = s3Bucket;
        String accessKey = s3AccessKey;
        String secretKey = s3SecretKey;
        String region = s3Region;
        String source = "内置配置 platform.storage.s3";
        if (req != null && req.s3ConnectionId() != null) {
            WarehouseConnectionService.ConnectionInfo c =
                    connections.get(req.s3ConnectionId(), "s3");
            endpoint = c.endpoint();
            bucket = c.bucketOrPath();
            accessKey = c.username();
            secretKey = c.password();
            region = c.region() == null || c.region().isBlank() ? region : c.region();
            source = "数仓连接 #" + c.id() + "（" + c.provider() + "）";
        }
        if (accessKey == null || accessKey.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            throw ApiException.badRequest(
                    "冷热分离未配置对象存储凭据（选择 S3 数仓连接，或配置 platform.storage.s3.access-key/secret-key）");
        }
        if (bucket == null || bucket.isBlank()) {
            throw ApiException.badRequest("冷热分离的对象存储缺少 bucket（S3 连接需填写 bucket）");
        }
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> existingResources = new ArrayList<>();
        for (Map<String, String> row : feSql.query("SHOW RESOURCES")) {
            existingResources.add(String.valueOf(row.get("Name")));
        }
        if (existingResources.contains(RESOURCE)) {
            skipped.add("resource:" + RESOURCE);
        } else {
            feSql.exec("CREATE RESOURCE \"" + RESOURCE + "\" PROPERTIES ("
                    + "\"type\"=\"s3\","
                    + "\"s3.endpoint\"=\"" + escape(endpoint) + "\","
                    + "\"s3.region\"=\"" + escape(region) + "\","
                    // Doris 的 S3 Resource 必须给 bucket，否则报 Missing [s3.bucket]
                    + "\"s3.bucket\"=\"" + escape(bucket) + "\","
                    + "\"s3.root.path\"=\"" + escape(s3Root) + "\","
                    + "\"s3.access_key\"=\"" + escape(accessKey) + "\","
                    + "\"s3.secret_key\"=\"" + escape(secretKey) + "\","
                    // MinIO 这类自建 S3 必须用 path style，否则 SDK 会去解析
                    // <bucket>.<endpoint> 这种虚拟主机名并抛 UnknownHostException。
                    // Doris 4.x 认的是不带 s3. 前缀的 use_path_style（实测两者都写才稳）。
                    + "\"use_path_style\"=\"true\","
                    + "\"s3.use_path_style\"=\"true\""
                    + ")");
            created.add("resource:" + RESOURCE);
        }
        List<String> existingPolicies = new ArrayList<>();
        for (Map<String, String> row : feSql.query("SHOW STORAGE POLICY")) {
            existingPolicies.add(String.valueOf(row.get("PolicyName")));
        }
        for (Map.Entry<String, String> entry : POLICIES.entrySet()) {
            if (existingPolicies.contains(entry.getKey())) {
                skipped.add("policy:" + entry.getKey());
                continue;
            }
            feSql.exec("CREATE STORAGE POLICY " + entry.getKey()
                    + " PROPERTIES(\"storage_resource\"=\"" + RESOURCE + "\","
                    + "\"cooldown_ttl\"=\"" + entry.getValue() + "\")");
            created.add("policy:" + entry.getKey());
        }
        // 审计不带 AK/SK
        sessions.audit(user(http), "STORAGE_COLD_HOT_INIT", RESOURCE,
                source + " created=" + created + " skipped=" + skipped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source", source);
        out.put("endpoint", endpoint);
        out.put("bucket", bucket);
        out.put("created", created);
        out.put("skipped", skipped);
        return out;
    }

    public record AttachRequest(String database, String table, String policy) {
    }

    /** 给已有表挂/换策略：ALTER TABLE ... SET ("storage_policy" = "p_warm")。 */
    @PostMapping("/attach")
    @RequireAtom("project:manage")
    public Map<String, Object> attach(@RequestBody AttachRequest req, HttpServletRequest http) {
        if (req == null || req.database() == null || req.table() == null) {
            throw ApiException.badRequest("请提供 database 与 table");
        }
        // 存储策略是集群级 DDL：只允许平台管理员（默认租户）操作
        requirePlatform();
        tenantScope.requireDatabase(req.database());
        String policy = req.policy() == null ? "p_warm" : req.policy().trim();
        if (!POLICIES.containsKey(policy)) {
            throw ApiException.badRequest("策略只能是 " + String.join(" / ", POLICIES.keySet()));
        }
        String db = requireIdent(req.database(), "库名");
        String table = requireIdent(req.table(), "表名");
        List<String> existing = new ArrayList<>();
        for (Map<String, String> row : feSql.query("SHOW STORAGE POLICY")) {
            existing.add(String.valueOf(row.get("PolicyName")));
        }
        if (!existing.contains(policy)) {
            throw ApiException.badRequest("策略 " + policy + " 还没创建，请先初始化冷热分离");
        }
        feSql.exec("ALTER TABLE `" + db + "`.`" + table + "` SET (\"storage_policy\" = \""
                + policy + "\")");
        sessions.audit(user(http), "STORAGE_COLD_HOT_ATTACH", db + "." + table, policy);
        return Map.of("ok", true, "database", db, "table", table, "policy", policy);
    }

    /**
     * 查看一张表的冷热属性与分区存储介质（验证下沉用）。
     *
     * <p>V98 定稿：这里只读 Doris 自身的表/分区属性（SHOW CREATE / SHOW PARTITIONS），
     * 不再从项目或数仓连接推断存储；没有显式 storage_medium 时统一显示「默认（热）」。</p>
     */
    @GetMapping("/tables/{database}/{table}")
    public Map<String, Object> table(@PathVariable String database, @PathVariable String table) {
        String db = requireIdent(database, "库名");
        String tbl = requireIdent(table, "表名");
        tenantScope.requireDatabase(db);
        List<Map<String, Object>> createRows =
                doris.query("SHOW CREATE TABLE `" + db + "`.`" + tbl + "`");
        List<Map<String, Object>> partitionRows =
                doris.query("SHOW PARTITIONS FROM `" + db + "`.`" + tbl + "`");
        String createSql = createTableSql(createRows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("createTable", createRows);
        out.put("partitions", partitionRows);
        out.put("storage", tableStorage(createSql));
        out.put("partitionStorage", partitionStorage(partitionRows));
        return out;
    }

    /** SHOW CREATE TABLE 只有一行，取除表名外的 DDL 文本。 */
    private static String createTableSql(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        Map<String, Object> row = rows.get(0);
        return row.values().stream().map(v -> v == null ? "" : String.valueOf(v))
                .filter(v -> !v.isBlank())
                .reduce((first, second) -> second).orElse("");
    }

    /** 表级 storage_medium / storage_cooldown_time；缺省按 Doris 默认（热）。 */
    private static Map<String, Object> tableStorage(String ddl) {
        String medium = firstMatch(ddl, "storage_medium");
        String cooldown = firstMatch(ddl, "storage_cooldown_time");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storageMedium", medium);
        out.put("cooldownTime", cooldown);
        out.put("label", medium == null
                ? "默认（热）" : ("HDD".equalsIgnoreCase(medium) ? "冷（HDD）" : "热（SSD）"));
        return out;
    }

    /** 分区级存储介质：字段名兼容 Doris 不同版本的 StorageMedium / CooldownTime。 */
    private static List<Map<String, Object>> partitionStorage(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("partitionName", valueIgnoreCase(row, "PartitionName"));
            String medium = valueIgnoreCase(row, "StorageMedium");
            item.put("storageMedium", medium);
            item.put("cooldownTime", valueIgnoreCase(row, "CooldownTime"));
            item.put("label", medium == null
                    ? "默认（热）" : ("HDD".equalsIgnoreCase(medium) ? "冷（HDD）" : "热（SSD）"));
            out.add(item);
        }
        return out;
    }

    private static String firstMatch(String text, String key) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*=\\s*\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String valueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? null : String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    /** 冷热分离的 Resource / Storage Policy 是集群级对象，只有默认租户可写。 */
    private static void requirePlatform() {
        if (!com.lab.dataworks.common.TenantGuard.platform()) {
            throw new ApiException(403, "冷热分离是集群级配置，只有平台管理员（默认租户）可以操作");
        }
    }

    private static String requireIdent(String value, String label) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw ApiException.badRequest(label + "不合法: " + value);
        }
        return value;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String user(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("username"));
    }

    @SuppressWarnings("unused")
    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
