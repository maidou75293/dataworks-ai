package com.lab.dataworks.asset;

import com.lab.dataworks.asset.AssetFolderRules.Principal;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 多模态资产：文件本体在 MinIO {@code media} bucket，平台只存元数据（V102）。
 *
 * <ul>
 *   <li>去重：同租户同 sha256 只保留一条 ACTIVE；<b>同目录</b>重复上传直接复用已有记录；
 *       内容已存在但在别的目录（或你无权访问的目录）时返回 409，不会悄悄挪动别人的资产。</li>
 *   <li>隔离：所有读写都带 {@code tenant_id = TenantScope.current()}，
 *       按 id 取详情/删除也先过租户条件（对齐 by-id 审计）；跨租户一律 404。</li>
 *   <li><b>S11 两层口径</b>（评审确认 2026-10-26，见 {@code state/}）：
 *       <ol>
 *         <li>目录层——列表/树/详情元数据（名称、类型、大小、归属）同租户全员可见，
 *             不再按目录读授权过滤条目；</li>
 *         <li>内容层——预签名签发（{@code /url}）、文件本体、详情敏感字段
 *             （uri / sha256 / url）需要授权：目录内容授权命中、或 owner（上传者）、
 *             或管理侧权限点；「我的上传」（folder_id=NULL）内的资产仅 owner/管理侧可取内容。
 *             无授权时 {@code /url} 返回 403（提示提交权限申请），列表/详情对应字段置空并带
 *             {@code contentAuthorized=false}。</li>
 *         <li>写路径（上传/移动/删除）走目录<b>写</b>判定；{@code folder_id = null}
 *             虚拟根「我的上传」不做目录级授权，改为<b>归属</b>判定：
 *             仅 owner / 管理侧可移动或删除其中的资产（见 {@code move} / {@code delete}）。</li>
 *       </ol></li>
 *   <li>删除是软删（status=DELETED），对象保留，避免误删后无法恢复。</li>
 * </ul>
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    /** 与 spring.servlet.multipart.max-file-size 保持一致。 */
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final int PRESIGN_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final AssetFolderService folders;
    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    /** 预签名用的对外地址（浏览器可达）；对象读写仍走 {@link #endpoint}。 */
    private final String publicEndpoint;
    /**
     * S3 客户端延迟创建：凭据缺失时只让「上传/预览」报可行动错误，
     * 不能因为对象存储没配好就把整个应用启动打挂（上下文测试也会红）。
     */
    private volatile S3Client s3;
    private volatile S3Presigner presigner;

    public AssetService(
            JdbcTemplate jdbc,
            AssetFolderService folders,
            @Value("${asset.s3.endpoint:${S3_ENDPOINT:http://minio.dw-dev.svc.cluster.local:9000}}")
            String endpoint,
            @Value("${asset.s3.bucket:${ASSET_BUCKET:media}}") String bucket,
            @Value("${asset.s3.public-endpoint:${ASSET_S3_PUBLIC_ENDPOINT:}}")
            String publicEndpoint,
            @Value("${asset.s3.access-key:${S3_ACCESS_KEY:}}") String accessKey,
            @Value("${asset.s3.secret-key:${S3_SECRET_KEY:}}") String secretKey,
            @Value("${asset.s3.region:us-east-1}") String region) {
        this.jdbc = jdbc;
        this.folders = folders;
        this.endpoint = trimSlash(endpoint == null || endpoint.isBlank()
                ? "http://minio.dw-dev.svc.cluster.local:9000" : endpoint);
        this.bucket = bucket == null || bucket.isBlank() ? "media" : bucket.trim();
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.region = region == null || region.isBlank() ? "us-east-1" : region;
        // 预签名地址是给浏览器用的：集群内 DNS（*.svc.cluster.local）在用户机器上解析不了，
        // 所以允许单独配一个对外可达的 endpoint；没配就退回内网地址（浏览器会打不开，仅限集群内调试）。
        this.publicEndpoint = publicEndpoint == null || publicEndpoint.isBlank()
                ? this.endpoint : trimSlash(publicEndpoint);
    }

    /**
     * S8：对象存储里不要出现可执行类型。客户端声明的 Content-Type 原样落库时，
     * {@code text/html} / {@code image/svg+xml} 能在平台自有域名下变成可执行页（存储型 XSS）。
     * 这里只保留明确安全的类型，其余一律按二进制流存。
     */
    private static String safeContentType(String declared) {
        if (declared == null || declared.isBlank()) {
            return "application/octet-stream";
        }
        String type = declared.trim().toLowerCase(java.util.Locale.ROOT);
        boolean safe = type.startsWith("image/png") || type.startsWith("image/jpeg")
                || type.startsWith("image/gif") || type.startsWith("image/webp")
                || type.startsWith("application/pdf") || type.startsWith("text/plain")
                || type.startsWith("audio/") || type.startsWith("video/");
        return safe ? declared.trim() : "application/octet-stream";
    }

    private boolean storageConfigured() {
        return !accessKey.isEmpty() && !secretKey.isEmpty();
    }

    private S3Client s3() {
        if (!storageConfigured()) {
            throw ApiException.badRequest(
                    "对象存储未配置（S3_ACCESS_KEY / S3_SECRET_KEY），暂不能上传或预览多模态资产");
        }
        S3Client client = s3;
        if (client == null) {
            synchronized (this) {
                client = s3;
                if (client == null) {
                    client = S3Client.builder()
                            .endpointOverride(URI.create(endpoint))
                            .region(Region.of(region))
                            .credentialsProvider(credentials())
                            .serviceConfiguration(S3Configuration.builder()
                                    .pathStyleAccessEnabled(true).build())
                            .build();
                    s3 = client;
                }
            }
        }
        return client;
    }

    private S3Presigner presigner() {
        if (!storageConfigured()) {
            throw ApiException.badRequest(
                    "对象存储未配置（S3_ACCESS_KEY / S3_SECRET_KEY），暂不能生成预览地址");
        }
        S3Presigner value = presigner;
        if (value == null) {
            synchronized (this) {
                value = presigner;
                if (value == null) {
                    value = S3Presigner.builder()
                            .endpointOverride(URI.create(publicEndpoint))
                            .region(Region.of(region))
                            .credentialsProvider(credentials())
                            .serviceConfiguration(S3Configuration.builder()
                                    .pathStyleAccessEnabled(true).build())
                            .build();
                    presigner = value;
                }
            }
        }
        return value;
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
    }

    /** 上传：算 sha256 → 去重 → 写 MinIO → 落元数据。folderId 空/0 = 虚拟根「我的上传」。 */
    public Map<String, Object> upload(MultipartFile file, String mediaType, String meta,
                                      String createdBy, Long folderId, Principal p) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("上传文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("单个文件不能超过 50 MB");
        }
        Long target = normalizeFolderId(folderId);
        AssetFolderService.Snapshot snap = folders.load(p);
        if (target != null) {
            folders.requireWritable(snap, target, p);
        }
        String tenant = TenantScope.current();
        String sha;
        try (InputStream in = file.getInputStream()) {
            sha = sha256(in);
        } catch (Exception e) {
            throw ApiException.badRequest("读取上传文件失败: " + e.getMessage());
        }
        List<Map<String, Object>> existing = findActiveBySha(tenant, sha);
        if (!existing.isEmpty()) {
            String blocked = reuseBlocked(snap, existing.get(0), target, p);
            if (blocked != null) {
                throw ApiException.conflict(blocked);
            }
            return withUrl(existing.get(0), true, p, snap);
        }

        String type = normalizeType(mediaType, file.getContentType(), file.getOriginalFilename());
        String key = objectKey(tenant, sha, extensionOf(file.getOriginalFilename(), file.getContentType()));
        ensureBucket();
        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(safeContentType(file.getContentType()))
                    .contentLength(file.getSize())
                    .build();
            s3().putObject(req, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (Exception e) {
            log.warn("asset upload failed tenant={} sha={}: {}", tenant, sha, e.getMessage());
            throw ApiException.badRequest("写入对象存储失败: " + e.getMessage());
        }
        String uri = "s3://" + bucket + "/" + key;
        Map<String, Object> safeMeta = parseMeta(meta, file.getOriginalFilename());
        try {
            jdbc.update("""
                    insert into platform.asset
                        (tenant_id, uri, media_type, mime, size_bytes, sha256, meta, status,
                         created_by, folder_id)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, 'ACTIVE', ?, ?)
                    """, tenant, uri, type, file.getContentType(), file.getSize(), sha,
                    toJson(safeMeta), createdBy, target);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发上传同一内容：唯一索引兜底，回读已存在的那条
            List<Map<String, Object>> rows = findActiveBySha(tenant, sha);
            if (!rows.isEmpty()) {
                String blocked = reuseBlocked(snap, rows.get(0), target, p);
                if (blocked != null) {
                    throw ApiException.conflict(blocked);
                }
                return withUrl(rows.get(0), true, p, snap);
            }
            throw ApiException.conflict("同一内容正在被并发上传，请稍后重试");
        }
        List<Map<String, Object>> rows = findActiveBySha(tenant, sha);
        if (rows.isEmpty()) {
            throw ApiException.conflict("同一内容正在被并发上传，请稍后重试");
        }
        return withUrl(rows.get(0), false, p, snap);
    }

    /**
     * 列表（S11 两层口径·目录层）：page 从 1 开始，size 1–500，media_type 可选；
     * <b>条目对同租户全员可见</b>，不再按目录读授权过滤/403。
     *
     * <p>folderId：不传 = 全部目录 + 虚拟根；0 = 仅「我的上传」；N = 指定目录
     * （目录不存在/跨租户 404，但无权读内容不再影响列表）。</p>
     *
     * <p>内容层字段：每行带 {@code contentAuthorized}；为 false 的行 {@code uri}/{@code sha256}
     * 置空——目录名/类型/大小/归属照常下发，下载与预览另行授权（403）。</p>
     */
    public Map<String, Object> list(int page, int size, String mediaType, Long folderId,
                                    Principal p) {
        // D19：分页参数统一走 common.Paging 收口（v449 契约：page 从 1、size 1–500、非法 400）。
        // 此前这里静默夹紧——page=0 被改成 1、size=0 被改成 1、size=501 被改成 500 后回 200，
        // 与项目/告警/UDF 列表对同一组非法值 400 的口径不一致。
        com.lab.dataworks.common.Paging.requireValid(page, size);
        int pg = com.lab.dataworks.common.Paging.pageOf(page);
        int sz = size;
        String tenant = TenantScope.current();
        String type = mediaType == null || mediaType.isBlank()
                ? null : mediaType.trim().toUpperCase(Locale.ROOT);
        if (folderId != null && folderId < 0) {
            throw ApiException.badRequest("folderId 不合法（0 = 虚拟根「我的上传」）");
        }
        StringBuilder cond = new StringBuilder("tenant_id = ? and status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (type != null) {
            cond.append(" and media_type = ?");
            args.add(type);
        }
        AssetFolderService.Snapshot snap = folders.load(p);
        if (folderId != null && folderId > 0) {
            // 目录层可见：只做存在性/租户校验（不存在或跨租户 404），不再 requireReadable
            if (!snap.contains(folderId)) {
                throw ApiException.notFound("目录不存在: " + folderId);
            }
            cond.append(" and folder_id = ?");
            args.add(folderId);
        } else if (folderId != null) {
            cond.append(" and folder_id is null"); // 0 = 虚拟根「我的上传」
        }
        Long total = jdbc.queryForObject(
                "select count(*) from platform.asset where " + cond, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        // D12：offset 用 long，page=2147483647 不再 int 溢出成 409「数据冲突」
        pageArgs.add(com.lab.dataworks.common.Paging.offset(pg, sz));
        List<Map<String, Object>> items = jdbc.queryForList(
                "select id, uri, media_type, mime, size_bytes, sha256, meta::text as meta, "
                        + "status, created_by, created_at, folder_id"
                        + " from platform.asset where " + cond
                        + " order by created_at desc, id desc limit ? offset ?",
                pageArgs.toArray());
        for (Map<String, Object> item : items) {
            boolean allowed = canAccessContent(item, p, snap);
            item.put("contentAuthorized", allowed);
            if (!allowed) {
                item.put("uri", null);
                item.put("sha256", null);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("page", pg);
        out.put("size", sz);
        out.put("total", total == null ? 0L : total);
        return out;
    }

    /**
     * 详情（目录层元数据全员可见；内容层字段按授权裁剪）：
     * 不存在/跨租户 404；无内容授权时不返回 uri/sha256/url，仅置
     * {@code contentAuthorized=false} 并提示提交权限申请。
     */
    public Map<String, Object> detail(long id, Principal p) {
        Map<String, Object> row = require(id);
        Map<String, Object> out = new LinkedHashMap<>(row);
        boolean allowed = canAccessContent(row, p, folders.load(p));
        out.put("contentAuthorized", allowed);
        if (allowed) {
            embedUrl(out, row);
        } else {
            out.put("uri", null);
            out.put("sha256", null);
            out.put("contentHint", contentDeniedHint(row, p));
        }
        return out;
    }

    /**
     * 软删：只改状态，MinIO 对象保留（由清理任务统一回收）。
     *
     * <p>目录内资产删除需要该目录<b>写</b>权限；「我的上传」（{@code folder_id = NULL}）
     * 里的资产<b>不做目录级授权</b>，因此必须有归属检查——仅 owner / 管理侧可删
     * （S11 收口：此前根资产走 {@code source != null} 分支被整体跳过，同租户任何持
     * {@code asset:write} 的成员都能软删他人内容）。</p>
     *
     * <p>返回的 {@code uri} 只供 {@code AssetController} 写审计用，响应体里会被剥掉。</p>
     */
    public Map<String, Object> delete(long id, Principal p) {
        Map<String, Object> row = require(id);
        Long source = folderIdOf(row);
        if (source != null) {
            folders.requireWritable(folders.load(p), source, p);
        } else {
            requireContentAccess(row, p, folders.load(p));
        }
        jdbc.update("""
                update platform.asset
                   set status = 'DELETED', deleted_at = now()
                 where id = ? and tenant_id = ? and status = 'ACTIVE'
                """, id, TenantScope.current());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "DELETED");
        out.put("uri", row.get("uri"));
        return out;
    }

    /**
     * 移动资产到别的目录（V110）：folderId 空/0 = 虚拟根「我的上传」。
     *
     * <p><b>来源归属</b>（S11 收口）：源目录要求<b>写</b>权限；源是「我的上传」
     * （{@code folder_id = NULL}）时没有目录可判定，改按<b>内容层归属</b>收口——
     * 仅 owner / 管理侧可挪。此前根资产整体跳过来源检查，同租户成员 B 能把 A 的
     * 「我的上传」资产 {@code PUT /{id}/folder} 移进自己可写的目录，移完
     * {@code GET /{id}/url} 直接 200，虚拟根的内容锁等于没有。</p>
     *
     * <p>权限判定一律走在幂等短路<b>之前</b>：原地不动的移动也是写操作，
     * 先短路会让非 owner 拿到一份含 {@code uri}/{@code sha256} 的 200 回执。</p>
     *
     * <p>目标目录同样要求写权限；内容去重键 sha256 不变，只是换个位置。</p>
     */
    public Map<String, Object> move(long id, Long folderId, Principal p) {
        Map<String, Object> row = require(id);
        Long source = folderIdOf(row);
        Long target = normalizeFolderId(folderId);
        AssetFolderService.Snapshot snap = folders.load(p);
        if (source != null) {
            folders.requireWritable(snap, source, p);
        } else {
            requireContentAccess(row, p, snap); // 「我的上传」：仅 owner/管理侧可挪走
        }
        if (target != null) {
            folders.requireWritable(snap, target, p);
        }
        if (java.util.Objects.equals(source, target)) {
            return moveResult(row, p, snap); // 幂等：原地不动
        }
        jdbc.update("""
                update platform.asset set folder_id = ?
                 where id = ? and tenant_id = ? and status = 'ACTIVE'
                """, target, id, TenantScope.current());
        row.put("folder_id", target);
        // 移动后按新目录重新判定内容权（移入自己可内容的目录后应能取预览）
        return moveResult(row, p, snap);
    }

    /**
     * 移动回执：与详情同一套内容层裁剪——有内容授权才给 {@code url}，
     * 否则 {@code uri}/{@code sha256} 置空并给 {@code contentHint}。
     *
     * <p>不复用 {@link #withUrl}（那是上传回执，审计要记 sha256，且调用方就是上传者）。</p>
     */
    private Map<String, Object> moveResult(Map<String, Object> row, Principal p,
                                           AssetFolderService.Snapshot snap) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("reused", false);
        boolean allowed = canAccessContent(row, p, snap);
        out.put("contentAuthorized", allowed);
        if (allowed) {
            embedUrl(out, row);
        } else {
            out.put("uri", null);
            out.put("sha256", null);
            out.put("contentHint", contentDeniedHint(row, p));
        }
        return out;
    }

    /**
     * 预签名下载地址（10 分钟）——内容层入口：必须有授权（目录内容授权命中 / owner /
     * 管理侧），无授权 <b>403</b>（不是 404/500/空响应）并提示提交权限申请；
     * 「我的上传」（folder_id=NULL）中他人上传的资产仅 owner/管理侧可取。
     * 不存在/跨租户仍 404。
     */
    public Map<String, Object> presignedUrl(long id, Principal p) {
        Map<String, Object> row = require(id);
        AssetFolderService.Snapshot snap = folders.load(p);
        requireContentAccess(row, p, snap);
        String key = keyOf(String.valueOf(row.get("uri")));
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(PRESIGN_MINUTES))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build();
        String url = presigner().presignGetObject(req).url().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("url", url);
        out.put("expiresInSeconds", PRESIGN_MINUTES * 60);
        return out;
    }

    // ---------------------------------------------------------------- 目录判定辅助

    private static Long normalizeFolderId(Long folderId) {
        if (folderId == null || folderId == 0L) {
            return null;
        }
        if (folderId < 0) {
            throw ApiException.badRequest("folderId 不合法（0 = 虚拟根「我的上传」）");
        }
        return folderId;
    }

    private static Long folderIdOf(Map<String, Object> row) {
        Object value = row.get("folder_id");
        return value == null ? null : ((Number) value).longValue();
    }

    /**
     * 去重复用判定：返回 null 表示可在目标目录复用已有记录；否则返回 409 的说明文案。
     *
     * <p>同内容不同目录不再「悄悄复用」：复用会把上传结果落在旧目录，用户会以为文件丢了。
     * 需要挪位置就走显式的「移动」（要求两侧写权限）。</p>
     */
    private String reuseBlocked(AssetFolderService.Snapshot snap, Map<String, Object> existing,
                               Long targetFolder, Principal p) {
        Long existingFolder = folderIdOf(existing);
        if (java.util.Objects.equals(existingFolder, targetFolder)) {
            return null;
        }
        if (existingFolder != null && !snap.readable(existingFolder, p)) {
            return "同一内容（sha256 相同）已存在于资产库，但你没有它所在目录的内容读授权，"
                    + "请提交权限申请或联系目录管理员";
        }
        String where = existingFolder == null ? "「我的上传」"
                : "目录「" + (snap.folder(existingFolder) == null
                        ? String.valueOf(existingFolder) : snap.folder(existingFolder).path()) + "」";
        return "同一内容（sha256 相同）已存在于" + where
                + "；平台按内容去重只保留一份，如需挪到本目录，请对该资产执行「移动」（需要两侧目录的写权限）";
    }

    /** 内容层判定（S11）：owner / 管理侧 / 目录内容授权命中；「我的上传」仅 owner/管理侧。 */
    private boolean canAccessContent(Map<String, Object> row, Principal p,
                                     AssetFolderService.Snapshot snap) {
        Long folderId = folderIdOf(row);
        String owner = row.get("created_by") == null ? null : String.valueOf(row.get("created_by"));
        boolean folderOpen = folderId != null && snap.readable(folderId, p);
        return AssetFolderRules.assetContentReadable(p, owner, folderId, folderOpen);
    }

    /** 无内容授权时的 403 文案：区分「我的上传」与授权目录，均提示提交权限申请。 */
    private String contentDeniedHint(Map<String, Object> row, Principal p) {
        Long folderId = folderIdOf(row);
        if (folderId == null) {
            return "无内容访问权限：该资产位于「我的上传」（上传人 "
                    + (row.get("created_by") == null ? "?" : row.get("created_by"))
                    + "），仅上传人与管理员可下载；如需使用请提交权限申请或联系上传人";
        }
        return "无内容访问权限：目录未授予你内容读权限，请提交权限申请（由目录管理员授权后重试）";
    }

    /** 内容层 403 断言：无授权时拒绝签发/下载（S11 评审确认：403 + 提示申请，不返回 404/500）。 */
    private void requireContentAccess(Map<String, Object> row, Principal p,
                                      AssetFolderService.Snapshot snap) {
        if (!canAccessContent(row, p, snap)) {
            throw new ApiException(403, contentDeniedHint(row, p));
        }
    }

    private List<Map<String, Object>> findActiveBySha(String tenant, String sha) {
        return jdbc.queryForList("""
                select id, uri, media_type, mime, size_bytes, sha256, meta::text as meta,
                       status, created_by, created_at, folder_id
                  from platform.asset
                 where tenant_id = ? and sha256 = ? and status = 'ACTIVE'
                """, tenant, sha);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> require(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, uri, media_type, mime, size_bytes, sha256, meta::text as meta,
                       status, created_by, created_at, folder_id
                  from platform.asset
                 where id = ? and tenant_id = ? and status = 'ACTIVE'
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("资产不存在: " + id);
        }
        return rows.get(0);
    }

    /**
     * 上传/移动响应组装：{@code reused} 标记去重复用；预签名 url 仅在调用者有
     * <b>内容层</b>授权时下发（S11——复用他人「我的上传」里的同内容资产时不给 url）。
     * uri/sha256 保留：上传响应是写路径回执，审计要记 sha256，且调用方本就持有内容。
     */
    private Map<String, Object> withUrl(Map<String, Object> row, boolean reused, Principal p,
                                        AssetFolderService.Snapshot snap) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("reused", reused);
        boolean allowed = canAccessContent(row, p, snap);
        out.put("contentAuthorized", allowed);
        if (allowed) {
            embedUrl(out, row);
        } else {
            out.put("contentHint", contentDeniedHint(row, p));
        }
        return out;
    }

    /** 存储没配好时列表/详情仍然可读，只是没有预览地址；上传与 /url 会明确报错。 */
    private void embedUrl(Map<String, Object> out, Map<String, Object> row) {
        Object uri = row.get("uri");
        if (uri != null && storageConfigured()) {
            String key = keyOf(String.valueOf(uri));
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(PRESIGN_MINUTES))
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build();
            out.put("url", presigner().presignGetObject(req).url().toString());
            out.put("urlExpiresInSeconds", PRESIGN_MINUTES * 60);
        }
    }

    private void ensureBucket() {
        try {
            s3().headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            try {
                s3().createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("asset bucket created: {}", bucket);
            } catch (RuntimeException ex) {
                log.warn("create asset bucket failed {}: {}", bucket, ex.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("head asset bucket failed {}: {}", bucket, e.getMessage());
        }
    }

    static String normalizeType(String mediaType, String mime, String filename) {
        String value = mediaType == null ? "" : mediaType.trim().toUpperCase(Locale.ROOT);
        if (List.of("IMAGE", "PDF", "TEXT", "AUDIO", "VIDEO", "OTHER").contains(value)) {
            return value;
        }
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) {
            return "IMAGE";
        }
        if (m.startsWith("audio/")) {
            return "AUDIO";
        }
        if (m.startsWith("video/")) {
            return "VIDEO";
        }
        if (m.equals("application/pdf")) {
            return "PDF";
        }
        if (m.startsWith("text/")) {
            return "TEXT";
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "PDF";
        }
        if (name.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp|svg)$")) {
            return "IMAGE";
        }
        if (name.matches(".*\\.(txt|md|csv|log|json|xml|yaml|yml)$")) {
            return "TEXT";
        }
        return "OTHER";
    }

    static String objectKey(String tenant, String sha, String ext) {
        String month = DateTimeFormatter.ofPattern("yyyyMM").format(OffsetDateTime.now());
        String safeTenant = tenant == null || tenant.isBlank() ? "default" : tenant.replaceAll(
                "[^A-Za-z0-9_.-]", "_");
        return safeTenant + "/" + month + "/" + sha + ext;
    }

    private static String extensionOf(String filename, String mime) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,8}")) {
                    return "." + ext;
                }
            }
        }
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.equals("application/pdf")) {
            return ".pdf";
        }
        if (m.startsWith("image/png")) {
            return ".png";
        }
        if (m.startsWith("image/jpeg")) {
            return ".jpg";
        }
        if (m.startsWith("text/plain")) {
            return ".txt";
        }
        return "";
    }

    private static String keyOf(String uri) {
        int idx = uri.indexOf("://");
        if (idx < 0) {
            return uri;
        }
        int slash = uri.indexOf('/', idx + 3);
        if (slash < 0 || slash == uri.length() - 1) {
            throw ApiException.badRequest("资产 URI 不合法: " + uri);
        }
        return uri.substring(slash + 1);
    }

    private static String sha256(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMeta(String meta, String filename) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (meta != null && !meta.isBlank()) {
            try {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(meta, Map.class);
                if (parsed instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> out.put(String.valueOf(k), v));
                }
            } catch (Exception e) {
                throw ApiException.badRequest("meta 必须是 JSON 对象: " + e.getMessage());
            }
        }
        if (filename != null && !filename.isBlank()) {
            out.putIfAbsent("originalFilename", filename);
        }
        return out;
    }

    private static String toJson(Map<String, Object> meta) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            throw ApiException.badRequest("meta 序列化失败: " + e.getMessage());
        }
    }

    private static String trimSlash(String value) {
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
