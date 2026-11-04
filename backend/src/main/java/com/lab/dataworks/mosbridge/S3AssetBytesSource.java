package com.lab.dataworks.mosbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;

/**
 * 从 MinIO 读资产本体（REQ-FW-5）。
 *
 * <p>配置口径与 {@code asset/AssetService.java:92-97} 完全同源（{@code asset.s3.*} 键、
 * env 兜底、集群内 endpoint），只是只读、不建桶不写桶。S3Client 延迟创建：
 * 凭据缺失只让搬运报可行动错误，不能把应用启动打挂（同 AssetService:82-87 的理由）。</p>
 */
@Component
public class S3AssetBytesSource implements AssetBytesSource {

    private static final Logger log = LoggerFactory.getLogger(S3AssetBytesSource.class);

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private volatile S3Client s3;

    public S3AssetBytesSource(
            @Value("${asset.s3.endpoint:${S3_ENDPOINT:http://minio.dw-dev.svc.cluster.local:9000}}")
            String endpoint,
            @Value("${asset.s3.access-key:${S3_ACCESS_KEY:}}") String accessKey,
            @Value("${asset.s3.secret-key:${S3_SECRET_KEY:}}") String secretKey,
            @Value("${asset.s3.region:us-east-1}") String region) {
        this.endpoint = endpoint == null || endpoint.isBlank()
                ? "http://minio.dw-dev.svc.cluster.local:9000"
                : endpoint.replaceAll("/+$", "");
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
    }

    @Override
    public byte[] read(String uri) throws Exception {
        if (uri == null || !uri.startsWith("s3://")) {
            throw new IllegalArgumentException("资产 uri 不是 s3 对象：" + uri);
        }
        String rest = uri.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            throw new IllegalArgumentException("资产 uri 缺桶或键：" + uri);
        }
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("对象存储凭据未配置（S3_ACCESS_KEY/S3_SECRET_KEY），无法读资产本体");
        }
        return client().getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(key).build()).asByteArray();
    }

    private S3Client client() {
        S3Client c = s3;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (s3 == null) {
                s3 = S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.of(region))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                        .serviceConfiguration(S3Configuration.builder()
                                .pathStyleAccessEnabled(true).build())
                        .build();
                log.info("搬运器对象存储客户端就绪：{}", endpoint);
            }
            return s3;
        }
    }
}
