package com.lab.dataworks.project;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数仓连接：Doris / HDFS / S3 的内置或外部连接。
 *
 * <p>项目只引用连接 id；内置用 NULL 表示。连接密码用 SecretCipher 加密，
 * 任何查询接口都不回显密码。</p>
 */
@RestController
@RequestMapping("/api/warehouse-connections")
@RequireAtom("project:manage")
public class WarehouseConnectionController {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final SessionService sessions;

    public WarehouseConnectionController(JdbcTemplate jdbc, SecretCipher cipher,
                                         SessionService sessions) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.sessions = sessions;
    }

    public record CreateRequest(String name, String type, String endpoint,
                                String username, String password, String bucket,
                                String provider, String region) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.query("""
                select id, name, type, provider, endpoint, username, bucket_or_path, region, status, created_by, created_at
                  from platform.warehouse_connection
                 where tenant_id = ?
                 order by id
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("name", rs.getString("name"));
            row.put("type", rs.getString("type"));
            row.put("provider", rs.getString("provider"));
            row.put("endpoint", rs.getString("endpoint"));
            row.put("username", rs.getString("username"));
            row.put("bucket", rs.getString("bucket_or_path"));
            row.put("region", rs.getString("region"));
            row.put("status", rs.getString("status"));
            row.put("createdBy", rs.getString("created_by"));
            row.put("createdAt", rs.getTimestamp("created_at"));
            return row;
        }, TenantScope.current());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req, HttpServletRequest http) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("连接名称不能为空");
        }
        String type = req.type() == null ? "" : req.type().trim().toLowerCase(Locale.ROOT);
        if (!List.of("doris", "hdfs", "s3").contains(type)) {
            throw ApiException.badRequest("连接类型只能是 doris / hdfs / s3");
        }
        String endpoint = req.endpoint() == null ? "" : req.endpoint().trim();
        if (endpoint.isBlank()) {
            throw ApiException.badRequest("连接地址不能为空");
        }
        String provider = req.provider() == null ? "" : req.provider().trim().toLowerCase(Locale.ROOT);
        if (provider.isBlank()) {
            provider = switch (type) {
                case "hdfs" -> "hdfs";
                case "s3" -> "minio";
                default -> null;
            };
        }
        if (provider != null && !List.of("hdfs", "minio", "oss").contains(provider)) {
            throw ApiException.badRequest("provider 只能是 hdfs / minio / oss");
        }
        if (("minio".equals(provider) || "oss".equals(provider)) && !"s3".equals(type)) {
            throw ApiException.badRequest("minio/oss 连接的类型必须是 s3");
        }
        if ("hdfs".equals(provider) && !"hdfs".equals(type)) {
            throw ApiException.badRequest("hdfs 连接的类型必须是 hdfs");
        }
        String tenant = TenantScope.current();
        boolean hasPassword = req.password() != null && !req.password().isEmpty();
        // S12 + S13：数仓连接建/删原先零审计（安全扫描泳道 3「下线」小节点名项）。
        // 建连接先记 PENDING 再落库，失败回填 FAILED；detail 带归属与地址，
        // 密码只记「已设置」，绝不进审计。
        String fixedProvider = provider; // provider 上面有条件赋值，lambda 需要 effectively final
        String detail = "type=" + type + " provider=" + fixedProvider + " endpoint=" + endpoint
                + " username=" + req.username() + " bucket=" + req.bucket()
                + " region=" + req.region() + (hasPassword ? " password=set" : "");
        return sessions.auditAround(user(http), "WAREHOUSE_CONN_CREATE", req.name().trim(),
                detail, AuthInterceptor.clientIp(http), () -> {
                    Long id = jdbc.query("""
                            insert into platform.warehouse_connection
                                (tenant_id, name, type, provider, endpoint, username, encrypted_password,
                                 bucket_or_path, region, created_by)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            returning id
                            """, rs -> rs.next() ? rs.getLong(1) : null,
                            tenant, req.name().trim(), type, fixedProvider, endpoint, req.username(),
                            hasPassword ? cipher.encrypt(req.password()) : null,
                            req.bucket(), req.region(),
                            user(http));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", id);
                    out.put("name", req.name().trim());
                    out.put("type", type);
                    out.put("endpoint", endpoint);
                    return out;
                },
                out -> "id=" + out.get("id"));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        // D48：删前先查「还有谁在用」。以前直接删，项目侧 doris_connection_id 悬空，
        // 下一次查询/DDL 才炸出「连接不存在」，用户不知道是删连接引起的。
        // 三个列都查（V65 的 doris/hdfs/s3；V98 起 hdfs/s3 已 DEPRECATED 但老数据仍可能挂着）。
        List<Map<String, Object>> usedBy = jdbc.queryForList("""
                select id, name from platform.project
                 where tenant_id = ?
                   and ? in (doris_connection_id, hdfs_connection_id, s3_connection_id)
                 order by id
                """, TenantScope.current(), id);
        if (!usedBy.isEmpty()) {
            List<String> names = usedBy.stream().limit(5)
                    .map(p -> String.valueOf(p.get("name"))).toList();
            String more = usedBy.size() > names.size()
                    ? " 等 " + usedBy.size() + " 个" : "";
            throw ApiException.badRequest("该数仓连接正被项目使用，不能删除："
                    + String.join("、", names) + more
                    + "。请先在「管理中心 · 项目设置」把这些项目的数仓数据源改成别的连接。");
        }
        // 删前先查归属信息：审计 detail 要能回答「删掉的是哪个连接」（查不到也照删，
        // 由 n==0 走原 404 口径，审计回填 FAILED）
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select name, type, provider, endpoint from platform.warehouse_connection
                 where id = ? and tenant_id = ?
                """, id, TenantScope.current());
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        String target = row.get("name") == null ? "conn#" + id : String.valueOf(row.get("name"));
        String detail = "id=" + id + " type=" + row.get("type") + " provider=" + row.get("provider")
                + " endpoint=" + row.get("endpoint");
        return sessions.auditAround(user(http), "WAREHOUSE_CONN_DELETE", target,
                detail, AuthInterceptor.clientIp(http), () -> {
                    int n = jdbc.update("""
                            delete from platform.warehouse_connection
                             where id = ? and tenant_id = ?
                            """, id, TenantScope.current());
                    if (n == 0) {
                        throw new ApiException(404, "连接不存在");
                    }
                    return Map.of("ok", true);
                });
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable long id) {
        Map<String, Object> row = jdbc.query("""
                select type, provider, endpoint, username, encrypted_password, bucket_or_path, region
                  from platform.warehouse_connection
                 where id = ? and tenant_id = ?
                """, rs -> rs.next() ? Map.of(
                "type", rs.getString("type"),
                "endpoint", rs.getString("endpoint"),
                "username", rs.getString("username") == null ? "" : rs.getString("username"),
                "password", rs.getString("encrypted_password") == null ? ""
                        : cipher.decrypt(rs.getString("encrypted_password")),
                "bucket", rs.getString("bucket_or_path") == null ? "" : rs.getString("bucket_or_path"),
                "region", rs.getString("region") == null ? "" : rs.getString("region")
        ) : null, id, TenantScope.current());
        if (row == null) {
            throw new ApiException(404, "连接不存在");
        }
        String type = String.valueOf(row.get("type"));
        String endpoint = String.valueOf(row.get("endpoint"));
        String username = String.valueOf(row.get("username"));
        String password = String.valueOf(row.get("password"));
        String bucket = String.valueOf(row.get("bucket"));
        String region = String.valueOf(row.get("region"));
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String detail = switch (type) {
                case "hdfs" -> testHdfs(endpoint);
                case "doris" -> testDoris(endpoint, username, password);
                case "s3" -> testS3(endpoint, username, password, bucket, region);
                default -> "ok";
            };
            out.put("ok", true);
            out.put("detail", detail);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    private String testHdfs(String endpoint) throws Exception {
        String base = endpoint.replaceAll("/+$", "");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/webhdfs/v1/?op=LISTSTATUS"))
                .timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("WebHDFS HTTP " + resp.statusCode());
        }
        return "WebHDFS 可用";
    }

    private String testS3(String endpoint, String accessKey, String secretKey, String bucket, String region)
            throws Exception {
        software.amazon.awssdk.services.s3.S3Client s3 =
                software.amazon.awssdk.services.s3.S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(software.amazon.awssdk.regions.Region.of(
                                region == null || region.isBlank() ? "us-east-1" : region))
                        .credentialsProvider(
                                software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                                accessKey, secretKey)))
                        // MinIO 必须 path-style，否则会去解析 bucket.host
                        .forcePathStyle(true)
                        .build();
        try (s3) {
            if (bucket != null && !bucket.isBlank()) {
                s3.headBucket(b -> b.bucket(bucket));
                return "S3 bucket 可用";
            }
            s3.listBuckets();
            return "S3 可用";
        }
    }

    /** 审计主体：拦截器写进 request 的用户名；没有上下文时记 unknown。 */
    private static String user(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private String testDoris(String endpoint, String user, String password) throws Exception {
        String url = endpoint.startsWith("jdbc:") ? endpoint : "jdbc:mysql://" + endpoint + "/";
        try (Connection con = DriverManager.getConnection(url, user, password);
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("select 1")) {
            rs.next();
            return "Doris FE 可用";
        }
    }
}
