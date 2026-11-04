package com.lab.dataworks.project;

import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 项目存储后端：Doris 库 / HDFS 目录 / S3 前缀。
 *
 * <p>三种后端遵守同一套行为（需求红线 3）：
 * 已存在且为空 → 复用；已存在且非空 → 409；创建失败 → 回滚本次已建的部分。</p>
 *
 * <p>「空」的判定排除平台自己的占位文件 {@value #MARKER}：占位对象只用来让目录/前缀
 * 真实存在，不能被下一次创建当成「别人在用」。</p>
 */
@Service
public class ProjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ProjectStorageService.class);
    private static final String MARKER = "_dw_project_init";

    /** 项目选的外部 MinIO/S3；为空时用平台内置。 */
    public record S3Config(String endpoint, String accessKey, String secretKey, String bucket, String region) {
    }

    private final String s3Endpoint;
    private final String s3Bucket;
    private final String s3AccessKey;
    private final String s3SecretKey;
    private final String s3Region;
    private final String hdfsWebhdfs;
    private final String hdfsRoot;

    public ProjectStorageService(
            @Value("${platform.storage.s3.endpoint:${S3_ENDPOINT:http://minio.dw-dev.svc.cluster.local:9000}}")
            String s3Endpoint,
            @Value("${platform.storage.s3.bucket:${S3_BUCKET:warehouse}}") String s3Bucket,
            @Value("${platform.storage.s3.access-key:${S3_ACCESS_KEY:}}") String s3AccessKey,
            @Value("${platform.storage.s3.secret-key:${S3_SECRET_KEY:}}") String s3SecretKey,
            @Value("${platform.storage.s3.region:us-east-1}") String s3Region,
            @Value("${platform.storage.hdfs.webhdfs:${HDFS_WEBHDFS_URL:}}") String hdfsWebhdfs,
            @Value("${platform.storage.hdfs.root:/warehouse}") String hdfsRoot) {
        this.s3Endpoint = s3Endpoint;
        this.s3Bucket = s3Bucket;
        this.s3AccessKey = s3AccessKey;
        this.s3SecretKey = s3SecretKey;
        this.s3Region = s3Region;
        this.hdfsWebhdfs = hdfsWebhdfs == null ? "" : hdfsWebhdfs.trim();
        this.hdfsRoot = hdfsRoot;
    }

    /** 存储类型白名单（与数据库 CHECK 约束一致）。 */
    public static String normalizeType(String type) {
        String value = type == null || type.isBlank() ? "doris" : type.trim().toLowerCase(Locale.ROOT);
        if (!List.of("doris", "hdfs", "s3").contains(value)) {
            throw ApiException.badRequest("存储类型只能是 doris / hdfs / s3");
        }
        return value;
    }

    public static String devPath(String type, String projectCode) {
        return switch (type) {
            case "hdfs" -> "/warehouse/" + projectCode + "/dev";
            case "s3" -> "s3://" + "warehouse" + "/" + projectCode + "/dev/";
            default -> null;
        };
    }

    public String s3DevPath(String projectCode) {
        return s3DevPath(defaultS3(), projectCode);
    }

    public String s3ProdPath(String projectCode) {
        return s3ProdPath(defaultS3(), projectCode);
    }

    private String s3DevPath(S3Config s3, String projectCode) {
        return "s3://" + s3.bucket() + "/" + projectCode + "/dev/";
    }

    private String s3ProdPath(S3Config s3, String projectCode) {
        return "s3://" + s3.bucket() + "/" + projectCode + "/prd/";
    }

    private S3Config defaultS3() {
        return new S3Config(s3Endpoint, s3AccessKey, s3SecretKey, s3Bucket, s3Region);
    }

    public String hdfsDevPath(String projectCode) {
        return hdfsRoot + "/" + projectCode + "/dev";
    }

    public String hdfsProdPath(String projectCode) {
        return hdfsRoot + "/" + projectCode + "/prd";
    }

    /**
     * 建 hdfs/s3 两套目录/前缀；doris 返回 null（由 DorisAdminService 处理）。
     *
     * @return 开发/生产路径；doris 返回 null
     */
    public String[] provision(String type, String projectCode) {
        return provision(type, projectCode, hdfsWebhdfs, null);
    }

    /** hdfsBase：项目选的外部 HDFS；为空时用平台内置（native）HDFS。 */
    public String[] provision(String type, String projectCode, String hdfsBase) {
        return provision(type, projectCode, hdfsBase, null);
    }

    /** s3cfg：项目选的外部 MinIO/S3；为空时用平台内置。 */
    public String[] provision(String type, String projectCode, String hdfsBase, S3Config s3cfg) {
        S3Config s3 = s3cfg == null ? defaultS3() : s3cfg;
        String hdfs = hdfsBase == null || hdfsBase.isBlank() ? hdfsWebhdfs : hdfsBase.trim();
        if ("doris".equals(type)) {
            return null;
        }
        if ("s3".equals(type)) {
            requireS3(s3);
            String dev = s3DevPath(s3, projectCode);
            String prod = s3ProdPath(s3, projectCode);
            List<String> created = new ArrayList<>();
            try {
                created.addAll(ensureS3Prefix(s3, projectCode + "/dev/"));
                created.addAll(ensureS3Prefix(s3, projectCode + "/prd/"));
                return new String[]{dev, prod};
            } catch (RuntimeException e) {
                rollbackS3(s3, created);
                throw e;
            }
        }
        if (hdfs.isEmpty()) {
            throw ApiException.badRequest(
                    "HDFS 未配置（platform.storage.hdfs.webhdfs），请联系管理员先接入 HDFS");
        }
        String dev = hdfsDevPath(projectCode);
        String prod = hdfsProdPath(projectCode);
        List<String> created = new ArrayList<>();
        try {
            created.addAll(ensureHdfsDir(hdfs, dev));
            created.addAll(ensureHdfsDir(hdfs, prod));
            return new String[]{dev, prod};
        } catch (RuntimeException e) {
            rollbackHdfs(hdfs, created);
            throw e;
        }
    }

    /** 创建失败时撤掉本次新建的占位（已存在复用的不动）。 */
    public void rollback(String type, String projectCode) {
        if ("s3".equals(type)) {
            try {
                rollbackS3(defaultS3(), List.of(projectCode + "/dev/" + MARKER, projectCode + "/prd/" + MARKER));
            } catch (RuntimeException e) {
                log.warn("回滚 S3 前缀失败 project={}: {}", projectCode, e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- S3 通用对象读写（UDF 用）

    public boolean s3Configured() {
        S3Config s3 = defaultS3();
        return s3.accessKey() != null && !s3.accessKey().isBlank()
                && s3.secretKey() != null && !s3.secretKey().isBlank();
    }

    public String s3Bucket() {
        return s3Bucket;
    }

    /** {@code s3://bucket/key}，可直接作为 Flink 的 USING JAR / pipeline.jars 地址。 */
    public String s3Uri(String key) {
        return "s3://" + s3Bucket + "/" + key;
    }

    public void s3Put(String key, byte[] data) {
        S3Config s3 = defaultS3();
        requireS3(s3);
        try (S3Client client = s3(s3)) {
            client.headBucket(HeadBucketRequest.builder().bucket(s3.bucket()).build());
            client.putObject(b -> b.bucket(s3.bucket()).key(key).contentLength((long) data.length),
                    RequestBody.fromBytes(data));
        } catch (Exception e) {
            throw new ApiException(500, "写入对象存储失败: " + e.getMessage());
        }
    }

    public byte[] s3Get(String key) {
        S3Config s3 = defaultS3();
        requireS3(s3);
        try (S3Client client = s3(s3);
             var stream = client.getObject(b -> b.bucket(s3.bucket()).key(key))) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new ApiException(404, "对象不存在: " + key);
        }
    }

    public void s3Delete(String key) {
        S3Config s3 = defaultS3();
        if (!s3Configured()) {
            return;
        }
        try (S3Client client = s3(s3)) {
            client.deleteObject(b -> b.bucket(s3.bucket()).key(key));
        } catch (Exception e) {
            log.warn("删除对象失败 key={}: {}", key, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- S3

    private void requireS3(S3Config s3) {
        if (s3.accessKey() == null || s3.accessKey().isBlank()
                || s3.secretKey() == null || s3.secretKey().isBlank()) {
            throw ApiException.badRequest("S3 未配置访问凭据（platform.storage.s3.access-key/secret-key）");
        }
    }

    private S3Client s3(S3Config s3) {
        return S3Client.builder()
                .endpointOverride(URI.create(s3.endpoint()))
                .region(Region.of(s3.region() == null || s3.region().isBlank() ? "us-east-1" : s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                // MinIO 必须走 path-style：bucket.host 形式解析不到
                .forcePathStyle(true)
                .build();
    }

    /** @return 本次新建的占位对象 key（回滚用） */
    private List<String> ensureS3Prefix(S3Config s3, String prefix) {
        try (S3Client client = s3(s3)) {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(s3.bucket()).build());
            } catch (Exception e) {
                try {
                    client.createBucket(b -> b.bucket(s3.bucket()));
                } catch (Exception createError) {
                    throw new ApiException(500, "S3 存储桶不可用: " + s3.bucket()
                            + "（" + createError.getMessage() + "）");
                }
            }
            List<S3Object> objects = client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(s3.bucket()).prefix(prefix).maxKeys(20).build())
                    .contents();
            boolean occupied = objects.stream()
                    .anyMatch(o -> !o.key().equals(prefix + MARKER));
            if (occupied) {
                throw ApiException.conflict("S3 前缀已存在且非空: s3://" + s3.bucket() + "/" + prefix);
            }
            if (objects.stream().noneMatch(o -> o.key().equals(prefix + MARKER))) {
                client.putObject(b -> b.bucket(s3.bucket()).key(prefix + MARKER),
                        RequestBody.fromBytes(new byte[0]));
                return List.of(prefix + MARKER);
            }
            return List.of();
        }
    }

    private void rollbackS3(S3Config s3, List<String> keys) {
        try (S3Client client = s3(s3)) {
            for (String key : keys) {
                client.deleteObject(b -> b.bucket(s3.bucket()).key(key));
            }
        }
    }

    // ---------------------------------------------------------------- HDFS (WebHDFS)

    private String hdfsUrl(String base, String path) {
        String trimmed = base.endsWith("/")
                ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/webhdfs/v1" + path;
    }

    private HttpResponse<String> webhdfs(String url, String method) throws IOException,
            InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** @return 本次新建的占位文件路径（回滚用） */
    private List<String> ensureHdfsDir(String base, String dir) {
        String listUrl = hdfsUrl(base, dir) + "?op=LISTSTATUS";
        try {
            HttpResponse<String> list = webhdfs(listUrl, "GET");
            if (list.statusCode() == 404) {
                HttpResponse<String> mkdir = webhdfs(hdfsUrl(base, dir) + "?op=MKDIRS&permission=755", "PUT");
                if (mkdir.statusCode() / 100 != 2) {
                    throw new ApiException(500, "HDFS 建目录失败: " + dir
                            + "（HTTP " + mkdir.statusCode() + "）");
                }
                String marker = dir + "/" + MARKER;
                if (!createHdfsFile(base, marker)) {
                    throw new ApiException(500, "HDFS 建占位文件失败: " + marker);
                }
                return List.of(marker);
            }
            if (list.statusCode() / 100 != 2) {
                throw new ApiException(500, "HDFS 目录探测失败: " + dir
                        + "（HTTP " + list.statusCode() + "）");
            }
            String body = list.body() == null ? "" : list.body();
            int entries = countOccurrences(body, "\"pathSuffix\"");
            boolean onlyMarker = entries == 1
                    && body.contains("\"pathSuffix\":\"" + MARKER + "\"");
            if (entries == 0) {
                // 目录存在但为空：补一个占位
                String marker = dir + "/" + MARKER;
                if (!createHdfsFile(base, marker)) {
                    throw new ApiException(500, "HDFS 建占位文件失败: " + marker);
                }
                return List.of(marker);
            }
            if (onlyMarker) {
                // 只有平台占位文件：视为空，复用
                return List.of();
            }
            throw ApiException.conflict("HDFS 目录已存在且非空: " + dir);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "HDFS 不可达（" + hdfsWebhdfs + "）：" + e.getMessage());
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private boolean createHdfsFile(String base, String path) throws IOException, InterruptedException {
        // WebHDFS CREATE 两跳：第一跳 307 带 Location，第二跳 PUT 空内容
        HttpResponse<String> first = webhdfs(hdfsUrl(base, path) + "?op=CREATE&overwrite=false", "PUT");
        if (first.statusCode() == 307 || first.statusCode() == 308) {
            String location = first.headers().firstValue("Location").orElse("");
            if (location.isBlank()) {
                return false;
            }
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest put = HttpRequest.newBuilder(URI.create(location))
                    .timeout(Duration.ofSeconds(15))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            return http.send(put, HttpResponse.BodyHandlers.ofString()).statusCode() / 100 == 2;
        }
        return first.statusCode() / 100 == 2;
    }

    private void rollbackHdfs(String base, List<String> files) {
        for (String file : files) {
            try {
                webhdfs(hdfsUrl(base, file) + "?op=DELETE&recursive=false", "DELETE");
            } catch (Exception e) {
                log.warn("回滚 HDFS 占位失败 {}: {}", file, e.getMessage());
            }
        }
    }
}
