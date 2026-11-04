package com.lab.dataworks.udf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.LikePattern;
import com.lab.dataworks.project.DorisAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UDF 资源与函数注册。
 *
 * <p>资源类型：{@code jar}（Java UDF，Doris 用）与 {@code py/zip/whl/txt}
 * （Python UDF，只能用于 Flink，提交作业时由 {@link #flinkPreamble} 注入）。</p>
 *
 * <p>边界：不做在线编辑器、不做在线编译。</p>
 *
 * <p>存储：资源落在后端可写目录 {@code ${platform.local-file-base}/udf/<tenant>/<uuid>.<ext>}
 * （部署里是 PVC {@code local-shared}，重启不丢）；文件名与内容只在上传时写一次，没有原地修改入口。
 * 引擎侧需要一个 HTTP 可达地址：由本服务的 {@code /api/udf/jars/{id}/{token}/{file}}
 * 以不可猜测的 token 对外提供，Doris BE 与 Flink TaskManager 都能直接 GET。</p>
 *
 * <p>删除是<b>软删</b>：行保留（文件也保留，便于审计/排查），在 metadata 里写
 * {@code deletedAt/deletedBy}，所有资源查询用 {@code jsonb_exists(...)} 反向过滤；
 * 删除动作另记一条 {@code udf_resource_event}。</p>
 *
 * <p>Doris 函数是<b>全局作用域</b>：函数名必须带 {@code p{projectId}_} 前缀，
 * 否则不同项目会互相覆盖。执行 DDL 用专用 admin 连接，不把该权限发给用户。</p>
 */
@Service
public class UdfService {

    private static final Logger log = LoggerFactory.getLogger(UdfService.class);

    static final long MAX_RESOURCE_BYTES = 50L * 1024 * 1024;

    /** 资源列表不传 size 时的每页条数（上限统一在 {@code common.Paging}：1–500）。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    private static final Set<String> TYPES = Set.of("jar", "py", "zip", "whl", "txt");
    /**
     * 软删标记落在 {@code metadata jsonb}（V89 已有列），不额外加列也就没有迁移依赖；
     * 注意 JDBC 里 {@code ?} 是占位符，判断 jsonb key 必须用 {@code jsonb_exists()}。
     */
    private static final String NOT_DELETED_R = "not jsonb_exists(r.metadata, 'deletedAt')";
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{1,47}$");
    private static final Pattern CLASS = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.]*$");
    /**
     * D59（2026-10-27 用户评审确认）：不做 Doris 远程 Python UDF，Python 只用于 Flink。
     * 注册 / 改配置 / 发布三处共用这一份文案，避免界面说一套、接口说另一套。
     */
    static final String PYTHON_DORIS_MESSAGE =
            "Python 函数仅支持 Flink 引擎（平台不做 Doris 远程 Python UDF）；"
                    + "要在 Doris 的 SQL 里调用，请上传 Java UDF（jar）并把引擎选成 Doris";

    /** Python + Doris 这个组合在三个入口都挡掉，不让它落成一个永远发布不了的草稿。 */
    static void requireEngineSupportsLanguage(String engine, String language) {
        if ("DORIS".equalsIgnoreCase(engine == null ? "" : engine.trim())
                && "python".equals(language)) {
            throw ApiException.badRequest(PYTHON_DORIS_MESSAGE);
        }
    }

    /** Python 入口：module.func 或 package.module.func。 */
    private static final Pattern PYTHON_ENTRY = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$");
    private static final Pattern TYPE = Pattern.compile(
            "(?i)^(BOOLEAN|TINYINT|SMALLINT|INT|BIGINT|LARGEINT|FLOAT|DOUBLE|STRING|DATE|DATETIME"
                    + "|VARCHAR\\(\\d{1,5}\\)|CHAR\\(\\d{1,5}\\)|DECIMAL\\(\\d{1,2}(,\\d{1,2})?\\))$");
    private static final Pattern FILENAME = Pattern.compile("^[A-Za-z0-9._-]{1,120}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final DorisAdminService doris;
    private final com.lab.dataworks.project.ProjectStorageService storage;
    private final Path baseDir;
    private final String jarBaseUrl;
    private final String pythonExecutable;
    private final UdfPackageInspector inspector = new UdfPackageInspector();
    private final ObjectMapper mapper = new ObjectMapper();

    public UdfService(JdbcTemplate jdbc, DorisAdminService doris,
                      com.lab.dataworks.project.ProjectStorageService storage,
                      @Value("${platform.local-file-base:/data/local}") String localBase,
                      @Value("${platform.udf.jar-base-url:http://dataworks-backend.dw-dev.svc.cluster.local:8080}")
                      String jarBaseUrl,
                      @Value("${platform.udf.python-executable:python3}") String pythonExecutable) {
        this.jdbc = jdbc;
        this.doris = doris;
        this.storage = storage;
        this.baseDir = Paths.get(localBase, "udf");
        this.jarBaseUrl = jarBaseUrl.endsWith("/")
                ? jarBaseUrl.substring(0, jarBaseUrl.length() - 1) : jarBaseUrl;
        this.pythonExecutable = pythonExecutable == null || pythonExecutable.isBlank()
                ? "python3" : pythonExecutable.trim();
    }

    // ---------- 资源 ----------

    public record ResourceRef(long id, long projectId, String name, int version,
                              String storageKey, String token, String filename) {
    }

    /** 兼容旧调用：不指定文件夹时放根目录。 */
    public Map<String, Object> upload(long projectId, String rawName, MultipartFile file,
                                      String uploader) {
        return upload(projectId, rawName, file, uploader, null);
    }

    /** 上传一个新版本。第一个版本自动启用；folderId=null 表示根目录。 */
    public Map<String, Object> upload(long projectId, String rawName, MultipartFile file,
                                      String uploader, Long folderId) {
        requireProject(projectId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要上传的资源文件");
        }
        if (file.getSize() > MAX_RESOURCE_BYTES) {
            throw new ApiException(413, "资源不能超过 50MB（当前 "
                    + (file.getSize() / 1024 / 1024) + "MB）");
        }
        String original = file.getOriginalFilename() == null
                ? "udf.jar" : file.getOriginalFilename();
        String type = extension(original);
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("只支持 .jar / .py / .zip / .whl / .txt 文件");
        }
        String language = "jar".equals(type) ? "java" : "python";
        String name = (rawName == null || rawName.isBlank() ? stripExt(original) : rawName)
                .trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw ApiException.badRequest(
                    "资源名必须以小写字母开头，只含小写字母、数字、下划线（2-48 位）");
        }
        // 同名资源不允许混用 Java/Python：函数注册按资源名找启用版本，混用会让语言判定不确定。
        // 软删版本视为「已删除」，不再参与语言冲突判定。
        List<String> languages = jdbc.queryForList("""
                select distinct language from platform.udf_resource
                 where project_id = ? and name = ?
                   and not jsonb_exists(metadata, 'deletedAt')
                """, String.class, projectId, name);
        if (!languages.isEmpty() && languages.stream().anyMatch(l -> !l.equals(language))) {
            throw ApiException.conflict("同名资源已有 " + languages
                    + " 版本，不能混用 Java/Python；请换一个资源名");
        }
        if (folderId != null) {
            requireFolder(projectId, folderId);
        }
        // 版本号包含软删行：唯一约束 (project_id, name, version) 不会因为删过版本而冲突。
        int version = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from platform.udf_resource
                 where project_id = ? and name = ?
                """, Integer.class, projectId, name);
        boolean active = jdbc.queryForObject("""
                select count(*) from platform.udf_resource
                 where project_id = ? and name = ? and active
                   and not jsonb_exists(metadata, 'deletedAt')
                """, Integer.class, projectId, name) == 0;

        String token = token();
        // key 是相对 {local-file-base}/udf 的路径；S3 对象 key 统一加 udf/ 前缀
        String key = TenantScope.current() + "/" + UUID.randomUUID() + "." + type;
        // 有对象存储时以 S3 为准：Flink 依赖需要 s3:// 地址（HTTP 需要 Hadoop，
        // 这套镜像没有）。同时保留一份本地副本，供后端 HTTP 下载给 Doris BE。
        String storageKey;
        String checksum;
        byte[] bytes;
        Map<String, Object> metadata;
        try {
            bytes = file.getBytes();
            checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            metadata = inspector.toMap(inspector.inspect(type, bytes, original));
            if (storage.s3Configured()) {
                storage.s3Put("udf/" + key, bytes);
                storageKey = storage.s3Uri("udf/" + key);
            } else {
                storageKey = key;
            }
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "保存资源失败: " + e.getMessage());
        }
        String metadataJson;
        try {
            metadataJson = mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            metadataJson = "{}";
        }
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into platform.udf_resource
                        (project_id, tenant_id, name, type, language, folder_id, version,
                         size_bytes, checksum, storage_key, download_token, original_filename,
                         uploader, active, metadata)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    returning id
                    """, Long.class, projectId, TenantScope.current(), name, type, language,
                    folderId, version, file.getSize(), checksum, storageKey, token,
                    safeFilename(original, type), uploader, active, metadataJson);
        } catch (DuplicateKeyException e) {
            // 两个请求同时上传同名资源时都会算出同一个 max(version)+1，唯一键只允许一个成功。
            // 这是并发冲突而不是服务端错误：返回 409 让调用方重试，并清掉本次已写入的文件，避免孤儿。
            discardUploaded(storageKey, key);
            throw ApiException.conflict("同名资源的版本正在被并发上传，请稍后重试");
        }
        recordEvent(projectId, id, name, version, "UPLOAD", uploader,
                "type=" + type + " size=" + file.getSize() + " active=" + active);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("name", name);
        out.put("type", type);
        out.put("language", language);
        out.put("folderId", folderId);
        out.put("version", version);
        out.put("active", active);
        out.put("size", file.getSize());
        out.put("checksum", checksum);
        out.put("metadata", metadata);
        return out;
    }

    /** 指定启用版本：切换后所有已发布函数改用新资源（新作业生效，旧作业不受影响）。 */
    @Transactional
    public Map<String, Object> activate(long resourceId) {
        return activate(resourceId, null);
    }

    /**
     * 指定启用版本。
     *
     * <p>并发与失败回滚：同名资源的启用/切换会先对全部版本行加锁（{@code for update}），
     * 避免两个请求同时把不同版本置为 active 撞上「同名只有一个启用版本」的唯一索引；
     * Doris 侧函数重建失败时，先把已发布函数按<b>原启用版本</b>恢复，再让事务回滚启用标记，
     * 否则会出现「新版本没启起来、旧版本也被清掉」，同名资源没有任何启用版本。</p>
     */
    @Transactional
    public Map<String, Object> activate(long resourceId, String actor) {
        Map<String, Object> row = ownedResource(resourceId);
        long projectId = num(row.get("project_id"));
        String name = String.valueOf(row.get("name"));
        // 锁住同名资源的全部未删版本，串行化并发启用；否则 unique(project_id,name) where active 会报 500。
        jdbc.queryForList("""
                select id from platform.udf_resource
                 where project_id = ? and name = ?
                   and not jsonb_exists(metadata, 'deletedAt')
                 order by id
                   for update
                """, Long.class, projectId, name);
        Map<String, Object> previous = jdbc.queryForList("""
                select * from platform.udf_resource
                 where project_id = ? and name = ? and active
                   and not jsonb_exists(metadata, 'deletedAt')
                """, projectId, name).stream().findFirst().orElse(null);
        if (previous != null && num(previous.get("id")) == resourceId) {
            // 已经是启用版本：不重复重建 Doris 函数（重建期间旧作业的类加载不受影响）。
            return Map.of("ok", true, "name", name, "version", row.get("version"),
                    "recreatedFunctions", 0, "unchanged", true);
        }
        jdbc.update("""
                update platform.udf_resource set active = false
                 where project_id = ? and name = ? and active
                """, projectId, name);
        // 加软删条件：拿到锁之前目标版本可能被并发软删，不能把已删除行重新置为启用
        // （它会被查询过滤掉，却仍占用「同名只能有一个 active」的部分唯一索引）。
        int activated = jdbc.update("""
                update platform.udf_resource set active = true
                 where id = ? and project_id = ?
                   and not jsonb_exists(metadata, 'deletedAt')
                """, resourceId, projectId);
        if (activated == 0) {
            throw ApiException.notFound("UDF 资源不存在: " + resourceId);
        }
        // Doris 是全局函数：新版本必须重新注册；失败要把启用状态回滚，避免「界面说切了、引擎还是旧 jar」。
        // Python 资源只用于 Flink，不需要在 Doris 侧重注册。
        int recreated = 0;
        if ("java".equals(String.valueOf(row.get("language")))) {
            try {
                recreated = recreatePublishedDorisFunctions(projectId, name, row);
            } catch (RuntimeException e) {
                // Doris 可能已经重建了部分函数：先按原启用版本恢复，再抛错触发数据库事务回滚。
                if (previous != null) {
                    try {
                        recreatePublishedDorisFunctions(projectId, name, previous);
                    } catch (RuntimeException restore) {
                        log.warn("rollback doris functions after activate failure failed resource={}: {}",
                                name, restore.getMessage());
                    }
                }
                throw e;
            }
        }
        recordEvent(projectId, resourceId, name,
                row.get("version") instanceof Number n ? n.intValue() : null,
                "ACTIVATE", actor, "version=" + row.get("version"));
        return Map.of("ok", true, "name", name, "version", row.get("version"),
                "recreatedFunctions", recreated);
    }

    /** 把某个资源名下的已发布 Doris 函数按指定资源版本重建，返回重建数量。 */
    private int recreatePublishedDorisFunctions(long projectId, String name,
                                                Map<String, Object> resource) {
        int recreated = 0;
        for (Map<String, Object> fn : jdbc.queryForList("""
                select * from platform.udf_function
                 where project_id = ? and resource_name = ? and status = 'PUBLISHED'
                """, projectId, name)) {
            if (isDorisFunction(fn)) {
                createDorisFunction(fn, resource);
                recreated++;
            }
        }
        return recreated;
    }

    /**
     * 停用某个版本：把 active 置 false，不删资源、不动文件。
     *
     * <p>停用后「同名资源没有启用版本」，注册/发布新函数会给出明确提示；
     * 再启用任意版本即可恢复。若该资源名还有<b>已发布</b>函数，停用会让
     * Flink 后续提交取不到依赖，因此先拒绝并提示先停用函数。</p>
     *
     * <p>事件表 action 约束（V91）只有 UPLOAD/ACTIVATE/DELETE/MOVE，停用不写资源事件，
     * 由控制器记一条会话审计；要单独留痕需新增迁移扩 action，见报告。</p>
     */
    public Map<String, Object> deactivate(long resourceId) {
        Map<String, Object> row = ownedResource(resourceId);
        long projectId = num(row.get("project_id"));
        String name = String.valueOf(row.get("name"));
        if (!Boolean.TRUE.equals(row.get("active"))) {
            return Map.of("ok", true, "name", name, "version", row.get("version"),
                    "active", false, "unchanged", true);
        }
        Integer published = jdbc.queryForObject("""
                select count(*) from platform.udf_function
                 where project_id = ? and resource_name = ? and status = 'PUBLISHED'
                """, Integer.class, projectId, name);
        if (published != null && published > 0) {
            throw ApiException.conflict("有 " + published + " 个已发布函数正在使用资源 " + name
                    + "；请先停用这些函数，或直接启用另一个版本");
        }
        jdbc.update("""
                update platform.udf_resource set active = false
                 where id = ? and project_id = ?
                """, resourceId, projectId);
        return Map.of("ok", true, "name", name, "version", row.get("version"),
                "active", false);
    }

    @Transactional
    public void deleteResource(long resourceId) {
        deleteResource(resourceId, null);
    }

    /**
     * 软删资源版本：行与文件保留，在 metadata 写入 deletedAt/deletedBy，并记 DELETE 事件。
     *
     * <p>为什么软删：资源是版本化二进制，函数按「资源名 + 启用版本」引用，
     * 直接物理删除后事件里的 resource_id 也会被置空、无法回看当时删的是哪一行。
     * 列表/版本/下载/启用查询统一用 {@code jsonb_exists(...)} 反向过滤，界面上等价于删除。</p>
     */
    @Transactional
    public void deleteResource(long resourceId, String actor) {
        Map<String, Object> row = ownedResource(resourceId);
        long projectId = num(row.get("project_id"));
        String name = String.valueOf(row.get("name"));
        // 与 activate 使用同一组行锁：否则「引用检查 → 软删」之间被并发启用插队，
        // 会把已发布函数正在使用的版本删掉（引擎后续取不到 jar）。
        List<Map<String, Object>> locked = jdbc.queryForList("""
                select * from platform.udf_resource
                 where project_id = ? and name = ?
                   and not jsonb_exists(metadata, 'deletedAt')
                 order by id
                   for update
                """, projectId, name);
        Map<String, Object> fresh = locked.stream()
                .filter(item -> num(item.get("id")) == resourceId)
                .findFirst().orElse(null);
        if (fresh == null) {
            throw ApiException.notFound("UDF 资源不存在: " + resourceId);
        }
        if (Boolean.TRUE.equals(fresh.get("active"))) {
            // 只有「已发布」函数真正依赖启用版本；草稿/已停用函数不取资源，
            // 与 deactivate 的口径保持一致，否则少量历史函数会让启用版本永远删不掉。
            Integer references = jdbc.queryForObject("""
                    select count(*) from platform.udf_function
                     where project_id = ? and resource_name = ? and status = 'PUBLISHED'
                    """, Integer.class, projectId, name);
            if (references != null && references > 0) {
                throw ApiException.conflict(
                        "该版本正在启用且被 " + references + " 个已发布函数引用，请先停用函数或切换版本");
            }
        }
        recordEvent(projectId, resourceId, name,
                fresh.get("version") instanceof Number n ? n.intValue() : null,
                "DELETE", actor, "active=" + fresh.get("active"));
        jdbc.update("""
                update platform.udf_resource
                   set active = false,
                       metadata = metadata || jsonb_build_object(
                           'deletedAt', to_jsonb(now()),
                           'deletedBy', to_jsonb(coalesce(?, '')))
                 where id = ?
                   and project_id in (select p.id from platform.project p
                                       where p.tenant_id = ?)
                """, actor == null ? "" : actor, resourceId, TenantScope.current());
    }

    // ---------------------------------------------------------------- 资源列表 / 下载 / 版本 / 引用

    /**
     * 资源列表。为了兼容 v447：不带 page/size 时返回原来的 List；带分页参数时返回
     * {@code {items,total,page,size}}。folderId 支持 all/root/数字。
     */
    public Object listResources(long projectId, String folderIdRaw, String keyword,
                                Integer page, Integer size) {
        requireProject(projectId);
        boolean all = folderIdRaw == null || folderIdRaw.isBlank()
                || "all".equalsIgnoreCase(folderIdRaw);
        boolean root = !all && ("root".equalsIgnoreCase(folderIdRaw)
                || "0".equals(folderIdRaw));
        Long folderId = null;
        if (!all && !root) {
            try {
                folderId = Long.parseLong(folderIdRaw);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("folderId 只能是 all / root / 数字");
            }
            requireFolder(projectId, folderId);
        }

        StringBuilder where = new StringBuilder(
                " where r.project_id = ? and " + NOT_DELETED_R);
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (!all) {
            if (root) {
                where.append(" and r.folder_id is null");
            } else {
                where.append(" and r.folder_id = ?");
                args.add(folderId);
            }
        }
        String search = LikePattern.contains(keyword);
        if (search != null) {
            where.append(" and (lower(r.name) like ?"
                    + " or lower(coalesce(r.original_filename, '')) like ?)");
            args.add(search);
            args.add(search);
        }

        String select = """
                select r.id, r.name, r.type, r.language,
                       r.folder_id as "folderId", f.path as "folderPath",
                       r.version, r.size_bytes as "size", r.checksum, r.active,
                       r.original_filename as "filename", r.uploader,
                       r.created_at as "createdAt", r.metadata::text as "metadata",
                       (select count(*) from platform.udf_function fn
                         where fn.project_id = r.project_id
                           and fn.resource_name = r.name) as "references"
                  from platform.udf_resource r
                  left join platform.udf_folder f on f.id = r.folder_id
                """ + where + " order by r.name, r.version desc";

        boolean paged = page != null || size != null;
        if (!paged) {
            return mapRows(jdbc.queryForList(select, args.toArray()));
        }
        // D19：分页参数按平台契约校验（page 从 1、size 1–500），非法值 400。
        // 原来 page=0 被静默改成 1、size=501 被静默改成 200，客户端无法察觉参数被改写，
        // 且与项目/工作流/数据服务列表（同一组非法值报 400）口径不一致。
        com.lab.dataworks.common.Paging.requireValid(page, size);
        int p = com.lab.dataworks.common.Paging.pageOf(page);
        int s = com.lab.dataworks.common.Paging.sizeOf(size, DEFAULT_PAGE_SIZE);
        Integer total = jdbc.queryForObject("select count(*) from platform.udf_resource r"
                + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(com.lab.dataworks.common.Paging.offset(p, s));
        List<Map<String, Object>> items = mapRows(jdbc.queryForList(
                select + " limit ? offset ?", pageArgs.toArray()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    public Map<String, Object> references(long resourceId) {
        Map<String, Object> row = ownedResource(resourceId);
        long projectId = num(row.get("project_id"));
        String name = String.valueOf(row.get("name"));
        List<Map<String, Object>> functions = jdbc.queryForList("""
                select id, name, full_name as "fullName", engine, language, status,
                       created_by as "createdBy", created_at as "createdAt",
                       published_by as "publishedBy", published_at as "publishedAt"
                  from platform.udf_function
                 where project_id = ? and resource_name = ?
                 order by id
                """, projectId, name);
        long published = functions.stream()
                .filter(f -> "PUBLISHED".equals(String.valueOf(f.get("status"))))
                .count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resourceId", resourceId);
        out.put("name", name);
        out.put("count", functions.size());
        out.put("published", published);
        out.put("functions", functions);
        return out;
    }

    public List<Map<String, Object>> versions(long resourceId) {
        Map<String, Object> row = ownedResource(resourceId);
        long projectId = num(row.get("project_id"));
        String name = String.valueOf(row.get("name"));
        return jdbc.queryForList("""
                select id, version, type, language, folder_id as "folderId",
                       size_bytes as "size", checksum, active,
                       original_filename as "filename", uploader,
                       created_at as "createdAt"
                  from platform.udf_resource
                 where project_id = ? and name = ?
                   and not jsonb_exists(metadata, 'deletedAt')
                 order by version desc
                """, projectId, name);
    }

    /** 资源版本变更记录（上传/启用/删除/移动），按资源名聚合全部版本。 */
    public List<Map<String, Object>> events(long resourceId) {
        Map<String, Object> row = ownedResource(resourceId);
        long projectId = num(row.get("project_id"));
        String name = String.valueOf(row.get("name"));
        return jdbc.queryForList("""
                select id, resource_id as "resourceId", resource_name as "name",
                       version, action, actor, detail, created_at as "createdAt"
                  from platform.udf_resource_event
                 where tenant_id = ? and project_id = ? and resource_name = ?
                 order by id desc limit 200
                """, TenantScope.current(), projectId, name);
    }

    /** 写版本变更事件；失败只记日志，不影响上传/启用/删除主流程。 */
    public void recordEvent(long projectId, Long resourceId, String name, Integer version,
                            String action, String actor, String detail) {
        try {
            jdbc.update("""
                    insert into platform.udf_resource_event
                        (tenant_id, project_id, resource_id, resource_name, version,
                         action, actor, detail)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, TenantScope.current(), projectId, resourceId, name, version,
                    action, actor, detail);
        } catch (Exception e) {
            log.warn("record udf resource event failed resource={} action={}: {}",
                    name, action, e.getMessage());
        }
    }

    /** 会话内下载：先按项目归属校验，不允许跨租户。 */
    public DownloadFile download(long resourceId) {
        return readFile(ownedResource(resourceId));
    }

    /** 引擎侧下载：token 鉴权，不需要登录。 */
    public DownloadFile downloadByToken(long id, String token) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from platform.udf_resource
                 where id = ? and not jsonb_exists(metadata, 'deletedAt')
                """, id);
        if (rows.isEmpty() || token == null
                || !MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                String.valueOf(rows.get(0).get("download_token"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw ApiException.notFound("资源不存在");
        }
        return readFile(rows.get(0));
    }

    public record DownloadFile(byte[] bytes, String filename, String contentType) {
    }

    private List<Map<String, Object>> mapRows(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object raw = row.get("metadata");
            if (raw instanceof String text && !text.isBlank()) {
                try {
                    row.put("metadata", mapper.readValue(text, Map.class));
                } catch (Exception ignored) {
                    row.put("metadata", Map.of());
                }
            } else if (!(raw instanceof Map)) {
                row.put("metadata", Map.of());
            }
            row.put("downloadUrl", "/api/udf/resources/" + row.get("id") + "/download");
        }
        return rows;
    }

    private DownloadFile readFile(Map<String, Object> row) {
        String stored = String.valueOf(row.get("storage_key"));
        byte[] bytes;
        if (stored.startsWith("s3://")) {
            bytes = storage.s3Get(s3KeyOf(stored));
        } else {
            Path path = resolve(stored);
            if (!Files.isRegularFile(path)) {
                throw ApiException.notFound("资源文件不存在");
            }
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException e) {
                throw new ApiException(500, "读取资源失败: " + e.getMessage());
            }
        }
        String type = row.get("type") == null ? "jar" : String.valueOf(row.get("type"));
        String filename = safeFilename(row.get("original_filename") == null
                ? "udf." + type : String.valueOf(row.get("original_filename")), type);
        return new DownloadFile(bytes, filename, contentType(type));
    }

    // ---------- 函数 ----------

    /**
     * BUG-TC4-6：不再静默吞掉未知字段（acceptance-rules 第 4 条「不许静默」）。
     * 以前 body 里多带 {@code env} 之类的字段会 200 收单再丢掉，
     * 用户以为指定了生产环境，实际按默认路径执行；现在多一个字段就 400。
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record FunctionRequest(String resourceName, String engine, String name,
                                  String className, String returnType, List<String> argTypes,
                                  String language) {
    }

    public Map<String, Object> createFunction(long projectId, FunctionRequest req, String author) {
        requireProject(projectId);
        String language = resolveLanguage(projectId, req);
        validateFunction(projectId, req, language);
        String engine = req.engine().trim().toUpperCase(Locale.ROOT);
        try {
            Long id = jdbc.queryForObject("""
                    insert into platform.udf_function
                        (project_id, tenant_id, resource_name, engine, language, name, full_name,
                         class_name, return_type, arg_types, status, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                    returning id
                    """, Long.class, projectId, TenantScope.current(),
                    req.resourceName().trim().toLowerCase(Locale.ROOT), engine, language,
                    req.name().trim().toLowerCase(Locale.ROOT),
                    fullName(projectId, req.name()),
                    req.className().trim(), req.returnType().trim().toUpperCase(Locale.ROOT),
                    argTypes(req.argTypes()), author);
            return Map.of("ok", true, "id", id, "language", language);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("同一项目下该函数名已注册: " + req.name());
        }
    }

    public void updateFunction(long functionId, FunctionRequest req) {
        Map<String, Object> fn = ownedFunction(functionId);
        if (!"DRAFT".equals(String.valueOf(fn.get("status")))) {
            throw ApiException.badRequest("只有草稿状态的函数可以修改，请先停用");
        }
        long projectId = num(fn.get("project_id"));
        String language = resolveLanguage(projectId, req);
        validateFunction(projectId, req, language);
        jdbc.update("""
                update platform.udf_function
                   set resource_name = ?, engine = ?, language = ?, name = ?, full_name = ?,
                       class_name = ?, return_type = ?, arg_types = ?, updated_at = now()
                 where id = ?
                """, req.resourceName().trim().toLowerCase(Locale.ROOT),
                req.engine().trim().toUpperCase(Locale.ROOT), language,
                req.name().trim().toLowerCase(Locale.ROOT), fullName(projectId, req.name()),
                req.className().trim(), req.returnType().trim().toUpperCase(Locale.ROOT),
                argTypes(req.argTypes()), functionId);
    }

    /** 发布：Doris 侧真正 CREATE GLOBAL FUNCTION；Flink 侧在作业提交时注入依赖。 */
    public Map<String, Object> publish(long functionId, String user) {
        Map<String, Object> fn = ownedFunction(functionId);
        if ("PUBLISHED".equals(String.valueOf(fn.get("status")))) {
            return Map.of("ok", true, "status", "PUBLISHED", "unchanged", true);
        }
        long projectId = num(fn.get("project_id"));
        String engine = String.valueOf(fn.get("engine"));
        String language = fn.get("language") == null
                ? "java" : String.valueOf(fn.get("language"));
        // D59：历史草稿里可能有「Python + Doris」的行（校验上线前注册的），发布前再挡一次，
        // 并留下 FAILED 审计（由控制器 auditAround 回填），便于发现误配置
        requireEngineSupportsLanguage(engine, language);
        Map<String, Object> resource = activeResource(projectId,
                String.valueOf(fn.get("resource_name")));
        if ("DORIS".equals(engine)) {
            createDorisFunction(fn, resource);
        }
        jdbc.update("""
                update platform.udf_function
                   set status = 'PUBLISHED', published_by = ?, published_at = now(),
                       updated_at = now()
                 where id = ?
                """, user, functionId);
        return Map.of("ok", true, "status", "PUBLISHED", "language", language, "engine", engine,
                "note", "FLINK".equals(engine)
                        ? ("python".equals(language)
                        ? "Flink Python 函数在作业提交时注入 py/zip/requirements 依赖"
                        : "Flink 函数在作业提交时自动注入 jar")
                        : "已在 Doris 注册全局函数");
    }

    public Map<String, Object> offline(long functionId) {
        Map<String, Object> fn = ownedFunction(functionId);
        if (isDorisFunction(fn) && "PUBLISHED".equals(String.valueOf(fn.get("status")))) {
            dropDorisFunction(fn);
        }
        jdbc.update("""
                update platform.udf_function
                   set status = 'OFFLINE', updated_at = now() where id = ?
                """, functionId);
        return Map.of("ok", true, "status", "OFFLINE");
    }

    public void deleteFunction(long functionId) {
        Map<String, Object> fn = ownedFunction(functionId);
        if (isDorisFunction(fn) && "PUBLISHED".equals(String.valueOf(fn.get("status")))) {
            dropDorisFunction(fn);
        }
        jdbc.update("""
                delete from platform.udf_function
                 where id = ?
                   and project_id in (select p.id from platform.project p
                                       where p.tenant_id = ?)
                """, functionId, TenantScope.current());
    }

    // ---------- 查询页 / 编辑器使用 ----------

    /** 可用函数（已发布）——自动补全与「未授权提示」都用这一份。 */
    public List<Map<String, Object>> usable(long projectId) {
        requireProject(projectId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, name, full_name as "fullName", engine, language,
                       return_type as "returnType", arg_types as "argTypes",
                       resource_name as "resourceName"
                  from platform.udf_function
                 where project_id = ? and status = 'PUBLISHED'
                 order by full_name
                """, projectId);
        rows.forEach(UdfService::applyEnvScope);
        return rows;
    }

    /**
     * REQ-TC4-1 第二条：函数的「环境作用域」回显（TC-4 用例 UD-03 FAIL）。
     *
     * <p>立案事实：{@code GET /api/udf/projects/{id}/functions} 的响应里没有任何
     * env / 库归属字段，用户会以为「在开发项目发布」＝「只影响开发环境」。实际是：</p>
     * <ul>
     *   <li>Doris 侧走 {@code CREATE GLOBAL FUNCTION}（集群级），开发项目发布的函数
     *       在生产库<b>立刻就可调用</b>，发布这一步不含环境语义；</li>
     *   <li>Flink 侧是作业提交时 {@code ADD JAR} 注入，作用域是那个作业。</li>
     * </ul>
     *
     * <p>所以这里不回填一个假的 {@code env=DEV}（那才是误导），而是把作用域如实说出来：
     * {@code envScope}（GLOBAL / JOB）、{@code envIsolation=false} 与一句 {@code envNote}。
     * 真正的按环境隔离需要产品定口径（按环境分命名空间或按项目连接发布），不在本次范围。</p>
     */
    static void applyEnvScope(Map<String, Object> row) {
        boolean doris = "DORIS".equalsIgnoreCase(String.valueOf(row.get("engine")));
        row.put("envScope", doris ? "GLOBAL" : "JOB");
        // 两种引擎都做不到 DEV/PROD 隔离：Doris 是集群级，Flink 的函数只要注册在本项目，
        // 同一项目的生产作业也会加载它。如实写 false，别让调用方以为有隔离。
        row.put("envIsolation", false);
        row.put("envNote", doris
                ? "Doris 函数以 CREATE GLOBAL FUNCTION 发布，作用域是整个集群："
                        + "开发环境发布后生产库同样可调用，没有 DEV/PROD 隔离"
                : "Flink 函数以 ADD JAR 在作业提交时注入，作用域是该作业；"
                        + "同项目的生产作业也会加载它，没有 DEV/PROD 隔离");
    }

    /**
     * 执行前校验：SQL 里出现的 {@code pN_xxx(...)} 必须是本租户已发布函数。
     *
     * <p>不校验会让用户看到 Doris/Flink 的「函数不存在」原文；更糟的是别人的项目
     * 可能用同名函数，结果看起来「能跑」其实是另一个项目的实现。</p>
     */
    public void checkUsage(Long projectId, String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        // 先抹掉字符串/注释，避免注释里的「参考 p1_fadd(...)」或字符串字面量被误判成调用。
        // 允许反引号/双引号包名：用户在 SQL 里写 `p1_fadd`(id) 也要认
        String code = UdfSqlRules.maskNonCode(sql);
        Matcher matcher = Pattern.compile(
                "(?i)[`\"]?\\bp(\\d+)_([a-z][a-z0-9_]*)[`\"]?\\s*\\(").matcher(code);
        while (matcher.find()) {
            long ownerProject = Long.parseLong(matcher.group(1));
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            boolean sameProject = projectId != null && ownerProject == projectId;
            // 默认租户（平台自身）可以用任何项目的函数；其他租户只能用「本租户项目」
            // 且「就是当前查询项目」的函数。不存在与不属于统一 403 + 明确提示。
            if (!com.lab.dataworks.common.TenantGuard.platform()
                    && !(sameProject && projectOwned(ownerProject))) {
                throw new ApiException(403, "无权使用函数 p" + ownerProject + "_" + name
                        + "：它属于其他项目或租户，请改用本项目已发布的函数");
            }
            Integer published = jdbc.queryForObject("""
                    select count(*) from platform.udf_function
                     where project_id = ? and name = ? and status = 'PUBLISHED'
                    """, Integer.class, ownerProject, name);
            if (published == null || published == 0) {
                throw ApiException.badRequest("函数 p" + ownerProject + "_" + name
                        + " 还没有发布，请到「函数管理」发布后再使用");
            }
        }
    }

    /**
     * Flink 作业提交前的注入：Java 用 {@code ADD JAR}；Python 用
     * {@code ADD FILE} + {@code python.files/python.archives/python.requirements}。
     * 只注入脚本里真正引用到的函数，避免每个作业都背上一堆无关依赖。
     */
    public String flinkPreamble(long projectId, String sql) {
        List<Map<String, Object>> functions = jdbc.queryForList("""
                select f.name, f.full_name, f.class_name, f.language,
                       r.storage_key, r.type, r.original_filename, r.metadata::text as metadata
                  from platform.udf_function f
                  join platform.udf_resource r
                    on r.project_id = f.project_id and r.name = f.resource_name and r.active
                   and not jsonb_exists(r.metadata, 'deletedAt')
                 where f.project_id = ? and f.engine = 'FLINK' and f.status = 'PUBLISHED'
                 order by f.full_name
                """, projectId);
        if (functions.isEmpty() || sql == null) {
            return "";
        }
        Set<String> jars = new LinkedHashSet<>();
        Set<String> pythonFiles = new LinkedHashSet<>();
        Set<String> pythonFilesConfig = new LinkedHashSet<>();
        Set<String> pythonArchivesConfig = new LinkedHashSet<>();
        Set<String> pythonRequirementsConfig = new LinkedHashSet<>();
        StringBuilder ddl = new StringBuilder();
        boolean hasPython = false;

        for (Map<String, Object> fn : functions) {
            String full = String.valueOf(fn.get("full_name"));
            // 只在真正的代码里找调用；注释/字符串里提到函数名不应注入依赖。
            if (!UdfSqlRules.calls(sql, full)) {
                continue;
            }
            String uri = String.valueOf(fn.get("storage_key"));
            if (!uri.startsWith("s3://")) {
                // 这套 Flink 镜像没有 Hadoop：http/file 之外的 scheme 也要集群本地可见，
                // 因此 Flink UDF 依赖对象存储（platform.storage.s3）。
                throw new ApiException(400,
                        "Flink 函数 " + full + " 的依赖不在对象存储里，无法注入作业；"
                                + "请确认平台已配置 MinIO/S3（platform.storage.s3）");
            }
            String language = fn.get("language") == null
                    ? "java" : String.valueOf(fn.get("language"));
            String entry = String.valueOf(fn.get("class_name"));
            if ("python".equals(language)) {
                hasPython = true;
                String type = fn.get("type") == null ? "py" : String.valueOf(fn.get("type"));
                String filename = safeFilename(fn.get("original_filename") == null
                        ? "udf." + type : String.valueOf(fn.get("original_filename")), type);
                pythonFiles.add(uri);
                switch (type) {
                    case "py" -> pythonFilesConfig.add(filename);
                    case "txt" -> pythonRequirementsConfig.add(filename);
                    case "zip", "whl" -> {
                        pythonArchivesConfig.add(filename);
                        String req = requirementsPath(fn.get("metadata"));
                        if (req != null && !req.isBlank()) {
                            pythonRequirementsConfig.add(req);
                        }
                    }
                    default -> throw new ApiException(400,
                            "Python 函数 " + full + " 的资源类型 " + type
                                    + " 不能注入 Flink；请上传 .py/.zip/.whl");
                }
                ddl.append("CREATE TEMPORARY FUNCTION `").append(full).append("` AS '")
                        .append(entry).append("' LANGUAGE PYTHON;\n");
            } else {
                jars.add(uri);
                ddl.append("CREATE TEMPORARY FUNCTION `").append(full).append("` AS '")
                        .append(entry).append("' LANGUAGE JAVA;\n");
            }
        }
        if (jars.isEmpty() && !hasPython) {
            return "";
        }
        // 必须用 ADD JAR：它把 jar 加进**会话 classloader**，Janino 代码生成才能解析到
        // 用户类。实测 1.20.1 的 SQL Gateway：
        //   SET 'pipeline.jars' / session properties pipeline.jars → ClassNotFound；
        //   CREATE FUNCTION ... USING JAR → 函数能注册，但 INSERT 时代码生成报
        //   "Cannot determine simple type name"（jar 不在编译 classpath）。
        // ADD JAR 是唯一让「注册 + 代码生成 + 作业运行」全部走通的写法。
        StringBuilder out = new StringBuilder();
        for (String uri : jars) {
            out.append("ADD JAR '").append(uri).append("';\n");
        }
        for (String uri : pythonFiles) {
            out.append("ADD FILE '").append(uri).append("';\n");
        }
        if (!pythonFilesConfig.isEmpty()) {
            out.append("SET 'python.files' = '")
                    .append(String.join(",", pythonFilesConfig)).append("';\n");
        }
        if (!pythonArchivesConfig.isEmpty()) {
            out.append("SET 'python.archives' = '")
                    .append(String.join(",", pythonArchivesConfig)).append("';\n");
        }
        if (!pythonRequirementsConfig.isEmpty()) {
            out.append("SET 'python.requirements' = '")
                    .append(pythonRequirementsConfig.iterator().next()).append("';\n");
        }
        if (hasPython) {
            out.append("SET 'python.client.executable' = '")
                    .append(pythonExecutable).append("';\n");
            out.append("SET 'python.executable' = '")
                    .append(pythonExecutable).append("';\n");
        }
        return out + ddl.toString();
    }

    private String requirementsPath(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            Map<?, ?> meta = mapper.readValue(String.valueOf(raw), Map.class);
            Object path = meta.get("requirementsPath");
            return path == null ? null : String.valueOf(path);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 内部 ----------

    private void createDorisFunction(Map<String, Object> fn, Map<String, Object> resource) {
        if ("python".equals(String.valueOf(fn.get("language")))) {
            throw ApiException.badRequest("Doris 不支持 Python UDF，请改用 Flink");
        }
        String signature = dorisSignature(fn);
        // 反引号只包函数名，不能把参数一起包进去（Doris 会把 `f(INT)` 当成一个标识符）
        String sql = "CREATE GLOBAL FUNCTION `" + fn.get("full_name") + "`"
                + "(" + argsOf(fn) + ") RETURNS "
                + String.valueOf(fn.get("return_type")) + " PROPERTIES("
                + "\"file\"=\"" + jarUrl(resource) + "\","
                + "\"symbol\"=\"" + String.valueOf(fn.get("class_name")) + "\","
                + "\"type\"=\"JAVA_UDF\")";
        // 重新发布 / 切换版本时同名函数已存在：先删再建（Doris 没有 CREATE OR REPLACE GLOBAL FUNCTION）
        dropDorisFunction(fn);
        try {
            doris.execAdmin(sql);
            log.info("doris udf created function={} resource={}", signature, resource.get("id"));
        } catch (RuntimeException e) {
            throw new ApiException(400, "Doris 注册函数失败: " + e.getMessage());
        }
    }

    private boolean isDorisFunction(Map<String, Object> fn) {
        return "DORIS".equals(String.valueOf(fn.get("engine")))
                && !"python".equals(String.valueOf(fn.get("language")));
    }

    private void dropDorisFunction(Map<String, Object> fn) {
        String signature = dorisSignature(fn);
        try {
            doris.execAdmin("DROP GLOBAL FUNCTION IF EXISTS `" + fn.get("full_name")
                    + "`(" + argsOf(fn) + ")");
        } catch (RuntimeException e) {
            // 函数本来就不存在 / Doris 不可用：删除是幂等操作，只记日志
            log.warn("doris drop udf failed function={}: {}", signature, e.getMessage());
        }
    }

    /** Doris 函数签名（日志用）：{@code p{projectId}_{name}(ARG, ARG)}。 */
    private String dorisSignature(Map<String, Object> fn) {
        return fn.get("full_name") + "(" + argsOf(fn) + ")";
    }

    private String argsOf(Map<String, Object> fn) {
        return fn.get("arg_types") == null ? "" : String.valueOf(fn.get("arg_types"));
    }

    private String jarUrl(Map<String, Object> row) {
        String type = row.get("type") == null ? "jar" : String.valueOf(row.get("type"));
        String filename = safeFilename(row.get("original_filename") == null
                ? "udf." + type : String.valueOf(row.get("original_filename")), type);
        Object id = row.get("resource_id") == null ? row.get("id") : row.get("resource_id");
        return jarBaseUrl + "/api/udf/jars/" + id + "/" + row.get("download_token")
                + "/" + filename;
    }

    /**
     * 语言不能由前端随便填：优先取请求里的 language，否则按资源已有的 language 推断。
     * 同名资源在 upload 阶段已经禁止混用 Java/Python，所以正常情况下只有一个值。
     */
    private String resolveLanguage(long projectId, FunctionRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.resourceName() == null || !NAME.matcher(
                req.resourceName().trim().toLowerCase(Locale.ROOT)).matches()) {
            throw ApiException.badRequest("资源名不合法");
        }
        String requested = req.language() == null || req.language().isBlank()
                ? null : req.language().trim().toLowerCase(Locale.ROOT);
        if (requested != null && !Set.of("java", "python").contains(requested)) {
            throw ApiException.badRequest("language 只能是 java 或 python");
        }
        List<String> languages = jdbc.queryForList("""
                select distinct language from platform.udf_resource
                 where project_id = ? and name = ?
                   and not jsonb_exists(metadata, 'deletedAt')
                """, String.class, projectId,
                req.resourceName().trim().toLowerCase(Locale.ROOT));
        if (languages.isEmpty()) {
            throw ApiException.notFound("资源不存在: " + req.resourceName());
        }
        if (requested == null) {
            if (languages.size() > 1) {
                throw ApiException.badRequest("资源存在多种语言版本，请显式指定 language");
            }
            return languages.get(0);
        }
        if (!languages.contains(requested)) {
            throw ApiException.badRequest("资源 " + req.resourceName() + " 是 "
                    + String.join("/", languages) + " 资源，不能按 " + requested + " 注册");
        }
        return requested;
    }

    private void validateFunction(long projectId, FunctionRequest req, String language) {
        if (!"DORIS".equalsIgnoreCase(req.engine()) && !"FLINK".equalsIgnoreCase(req.engine())) {
            throw ApiException.badRequest("引擎只能是 DORIS 或 FLINK");
        }
        if (req.name() == null || !NAME.matcher(req.name().trim().toLowerCase(Locale.ROOT)).matches()) {
            throw ApiException.badRequest(
                    "函数名必须以小写字母开头，只含小写字母、数字、下划线（2-48 位）");
        }
        requireEngineSupportsLanguage(req.engine(), language);
        if ("python".equals(language)) {
            if (req.className() == null || !PYTHON_ENTRY.matcher(req.className().trim()).matches()
                    || req.className().trim().length() > 255) {
                throw ApiException.badRequest(
                        "Python 入口不合法（例如 my_module.my_func 或 pkg.module.my_func）");
            }
        } else if (req.className() == null || !CLASS.matcher(req.className().trim()).matches()
                || req.className().trim().length() > 255) {
            throw ApiException.badRequest("类名不合法（例如 com.example.AddUdf）");
        }
        if (req.returnType() == null || !TYPE.matcher(req.returnType().trim()).matches()) {
            throw ApiException.badRequest("返回类型不合法: " + req.returnType());
        }
        for (String arg : req.argTypes() == null ? List.<String>of() : req.argTypes()) {
            if (arg == null || !TYPE.matcher(arg.trim()).matches()) {
                throw ApiException.badRequest("参数类型不合法: " + arg);
            }
        }
        // 资源必须存在：resolveLanguage 已经查过；这里保留二次校验，防止并发删除。
        Integer exists = jdbc.queryForObject("""
                select count(*) from platform.udf_resource
                 where project_id = ? and name = ? and language = ?
                   and not jsonb_exists(metadata, 'deletedAt')
                """, Integer.class, projectId,
                req.resourceName().trim().toLowerCase(Locale.ROOT), language);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("资源不存在: " + req.resourceName());
        }
    }

    public Map<String, Object> ownedResource(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.* from platform.udf_resource r
                 where r.id = ?
                   and r.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                   and not jsonb_exists(r.metadata, 'deletedAt')
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("UDF 资源不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> ownedFunction(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select f.* from platform.udf_function f
                 where f.id = ?
                   and f.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("UDF 函数不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> activeResource(long projectId, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from platform.udf_resource
                 where project_id = ? and name = ? and active
                   and not jsonb_exists(metadata, 'deletedAt')
                """, projectId, name);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("资源 " + name + " 没有启用的版本，请先上传并启用");
        }
        return rows.get(0);
    }

    private boolean projectOwned(long projectId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, TenantScope.current());
        return n != null && n > 0;
    }

    private void requireProject(long projectId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private void requireFolder(long projectId, long folderId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.udf_folder
                 where id = ? and project_id = ?
                """, Integer.class, folderId, projectId);
        if (n == null || n == 0) {
            throw ApiException.notFound("文件夹不存在: " + folderId);
        }
    }

    /** 入库失败时丢弃本次已写入的文件，避免并发冲突留下无主对象。 */
    private void discardUploaded(String storageKey, String relativeKey) {
        try {
            if (storageKey != null && storageKey.startsWith("s3://")) {
                storage.s3Delete(s3KeyOf(storageKey));
            } else {
                Files.deleteIfExists(resolve(relativeKey));
            }
        } catch (Exception e) {
            // 清理失败不影响返回给调用方的 409；只记日志便于运维排查孤儿文件。
            log.warn("discard uploaded udf file failed key={}: {}", relativeKey, e.getMessage());
        }
    }

    /** {@code s3://bucket/udf/tenant/uuid.jar} → {@code udf/tenant/uuid.jar}。 */
    private static String s3KeyOf(String stored) {
        int start = stored.indexOf('/', 5);
        return start < 0 ? stored : stored.substring(start + 1);
    }

    private Path resolve(String key) {
        Path root = baseDir.toAbsolutePath().normalize();
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw ApiException.badRequest("非法的存储路径");
        }
        return path;
    }

    private String fullName(long projectId, String name) {
        return ("p" + projectId + "_" + name.trim().toLowerCase(Locale.ROOT));
    }

    private String argTypes(List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String arg : args) {
            out.add(arg.trim().toUpperCase(Locale.ROOT));
        }
        return String.join(",", out);
    }

    private static String extension(String filename) {
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExt(String filename) {
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String safeFilename(String value, String type) {
        String name = value == null ? "" : value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String fallback = "udf." + (type == null || type.isBlank() ? "jar" : type);
        return FILENAME.matcher(name).matches() ? name : fallback;
    }

    private String contentType(String type) {
        return switch (type == null ? "jar" : type) {
            case "py" -> "text/x-python;charset=UTF-8";
            case "txt" -> "text/plain;charset=UTF-8";
            case "zip", "whl" -> "application/zip";
            default -> "application/java-archive";
        };
    }

    private static String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }
}
