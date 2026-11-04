package com.lab.dataworks.integration;

import com.jcraft.jsch.ChannelSftp;
import com.lab.dataworks.common.ApiException;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Pattern;

/**
 * 文件级增量：SeaTunnel 文件连接器每次都把 {@code path} 下所有文件读一遍，本身不记状态。
 * 这里在平台侧补两件事：
 *
 * <ol>
 *   <li>运行前把目录里的文件登记到 {@code platform.sync_task_file_state}；
 *       没有新文件就不提交作业（避免空跑和重复读）。</li>
 *   <li>作业成功后把处理过的文件移动到归档目录（默认 {@code <path>_done/yyyy-MM-dd}），
 *       下次扫描自然只剩新文件。</li>
 * </ol>
 *
 * <p>只在 {@code sourceConfig.file_incremental = true} 时生效，支持 SftpFile / FtpFile。
 */
@Service
public class FileIncrementService {

    private static final Logger log = LoggerFactory.getLogger(FileIncrementService.class);

    /** 命中即为“本次需要注意的文件数”。applicable=false 表示该任务没开文件级增量。 */
    public record Prepared(boolean applicable, int newFiles, String message) {
        static Prepared notApplicable() {
            return new Prepared(false, 0, null);
        }
    }

    private final JdbcTemplate jdbc;

    public FileIncrementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isFileSource(String sourceType) {
        return "SftpFile".equals(sourceType) || "FtpFile".equals(sourceType)
                || "LocalFile".equals(sourceType) || "S3File".equals(sourceType);
    }

    @SuppressWarnings("unchecked")
    public boolean enabled(Map<String, Object> task) {
        Object cfg = task.get("sourceConfig");
        if (!(cfg instanceof Map)) {
            return false;
        }
        Object v = ((Map<String, Object>) cfg).get("file_incremental");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(Map<String, Object> task) {
        Object cfg = task.get("sourceConfig");
        return cfg instanceof Map ? (Map<String, Object>) cfg : Map.of();
    }

    /**
     * 运行前登记文件，返回需要处理的文件数。文件级增量未开启时 applicable=false。
     *
     * @throws IllegalStateException 列目录失败（此时不提交作业，避免全量重读造成重复）
     */
    public Prepared prepare(long taskId, Map<String, Object> task, String batchId) {
        if (!enabled(task)) {
            return Prepared.notApplicable();
        }
        String type = String.valueOf(task.get("sourceType"));
        requireSupported(type);
        Map<String, Object> cfg = config(task);
        String dir = str(cfg.get("path"));
        if (dir == null) {
            throw new IllegalStateException("文件级增量：sourceConfig.path 不能为空");
        }
        Pattern filter = compileFilter(cfg.get("file_filter_pattern"));
        String archiveName = archiveDirName(cfg);

        List<RemoteFile> files;
        try (RemoteFs fs = open(type, cfg)) {
            files = list(fs, dir, archiveName, filter);
        } catch (Exception e) {
            throw new IllegalStateException("文件级增量列目录失败: " + e.getMessage(), e);
        }
        int added = 0;
        for (RemoteFile f : files) {
            // 已存在的记录不动；归档失败（ERROR）或文件暂时消失（MISSING）后
            // 又出现在目录里，都要重置为 PENDING 重新处理。
            added += jdbc.update("""
                    insert into platform.sync_task_file_state
                        (task_id, file_path, file_size, file_mtime, status, batch_id)
                    values (?, ?, ?, ?, 'PENDING', ?)
                    on conflict (task_id, file_path, file_size) do update
                        set status = 'PENDING'
                        where platform.sync_task_file_state.status in ('ERROR', 'MISSING')
                    """, taskId, f.path(), f.size(),
                    f.mtime() == null ? null : new Timestamp(f.mtime()), batchId);
        }
        // 本次运行的批次标记覆盖所有 PENDING 行：上次失败的遗留文件也归这一批处理，
        // 成功归档时只搬走本作业登记过的行，不会误动并发作业登记的文件。
        if (batchId != null) {
            jdbc.update("""
                    update platform.sync_task_file_state
                    set batch_id = ?
                    where task_id = ? and status = 'PENDING'
                    """, batchId, taskId);
        }
        // 只统计目录里仍然存在的 PENDING：归档成功但状态没写回的行不算新文件
        Set<String> listed = new HashSet<>();
        for (RemoteFile f : files) {
            listed.add(f.path());
        }
        List<String> pendingPaths = jdbc.queryForList("""
                select file_path from platform.sync_task_file_state
                where task_id = ? and status = 'PENDING'
                """, String.class, taskId);
        int newFiles = (int) pendingPaths.stream().filter(listed::contains).count();
        log.info("file increment task={} type={} dir={} scanned={} inserted={} pending={}",
                taskId, type, dir, files.size(), added, newFiles);
        return new Prepared(true, newFiles, newFiles == 0 ? "没有新文件，跳过本次运行" : null);
    }

    /**
     * 作业提交后把本次批次登记的文件绑定到作业名。归档只处理这个作业的行，
     * 避免并发运行 A 成功时把 B 尚未读取的文件也搬走。
     */
    public void bindBatch(long taskId, String batchId, String jobName) {
        if (batchId == null || jobName == null) {
            return;
        }
        int bound = jdbc.update("""
                update platform.sync_task_file_state
                set job_name = ?
                where task_id = ? and batch_id = ? and status = 'PENDING'
                """, jobName, taskId, batchId);
        log.info("file increment task={} batch={} bound to job={} files={}",
                taskId, batchId, jobName, bound);
    }

    /**
     * 作业成功后归档：把本作业登记、仍是 PENDING 的文件移到 {@code <archive>/yyyy-MM-dd/}，
     * 标记 PROCESSED。失败不影响作业本身（作业已经成功），只记日志并在表里留下记录。
     */
    public int archive(long taskId, Map<String, Object> task, String jobName) {
        if (!enabled(task) || jobName == null || jobName.isBlank()) {
            return 0;
        }
        List<Map<String, Object>> rows = pendingForJob(taskId, jobName);
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, Object> cfg = config(task);
        String dir = str(cfg.get("path"));
        String targetDir = archiveTarget(cfg);
        int moved = 0;
        try (RemoteFs fs = open(String.valueOf(task.get("sourceType")), cfg)) {
            fs.mkdirs(targetDir);
            for (Map<String, Object> row : rows) {
                String path = String.valueOf(row.get("filePath"));
                String name = path.substring(path.lastIndexOf('/') + 1);
                // 保留源目录下的相对路径：a/x.csv 与 b/x.csv 不能都落到 archive/x.csv
                String base = dir == null ? "" : (dir.endsWith("/") ? dir : dir + "/");
                String relative = path.startsWith(base) && path.length() > base.length()
                        ? path.substring(base.length()) : name;
                String target = targetDir + "/" + relative;
                try {
                    int slash = target.lastIndexOf('/');
                    if (slash > 0) {
                        fs.mkdirs(target.substring(0, slash));
                    }
                    if (fs.exists(target)) {
                        target = targetDir + "/" + relative + "." + jobName;
                    }
                    // 同一 job 内仍重名时继续加序号，避免覆盖
                    int suffix = 1;
                    while (fs.exists(target)) {
                        target = targetDir + "/" + relative + "." + jobName + "." + suffix++;
                    }
                    fs.rename(path, target);
                    jdbc.update("""
                            update platform.sync_task_file_state
                            set status = 'PROCESSED', processed_at = now(),
                                job_name = ?, archived_path = ?
                            where id = ?
                            """, jobName, target, row.get("id"));
                    moved++;
                } catch (Exception e) {
                    boolean missing = isMissing(e);
                    jdbc.update("""
                            update platform.sync_task_file_state
                            set status = ?, processed_at = now(), job_name = ?
                            where id = ?
                            """, missing ? "MISSING" : "ERROR", jobName, row.get("id"));
                    log.warn("archive file failed task={} file={}: {}", taskId, path, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("archive skipped task={} dir={}: {}", taskId, dir, e.getMessage());
            return moved;
        }
        log.info("archive done task={} dir={} -> {} files={}", taskId, dir, targetDir, moved);
        return moved;
    }

    /**
     * 本作业待归档的文件。必须同时按 task_id 与 job_name 过滤：只按任务取 PENDING
     * 会把并发作业登记的、尚未读取的文件一起搬进归档目录。
     */
    List<Map<String, Object>> pendingForJob(long taskId, String jobName) {
        return jdbc.queryForList("""
                select id, file_path as "filePath", file_size as "fileSize"
                from platform.sync_task_file_state
                where task_id = ? and job_name = ? and status = 'PENDING'
                order by id
                """, taskId, jobName);
    }

    /** 最近登记过的文件，供接口展示“本次将处理哪些文件”。 */
    public List<Map<String, Object>> recentFiles(long taskId, int limit) {
        return jdbc.queryForList("""
                select file_path as "filePath", file_size as "fileSize", status,
                       job_name as "jobName", archived_path as "archivedPath",
                       discovered_at as "discoveredAt", processed_at as "processedAt"
                from platform.sync_task_file_state
                where task_id = ?
                order by id desc limit ?
                """, taskId, limit);
    }

    private static void requireSupported(String type) {
        if (!"SftpFile".equals(type) && !"FtpFile".equals(type) && !"S3File".equals(type)
                && !"LocalFile".equals(type)) {
            throw new IllegalStateException(
                    "文件级增量支持 SftpFile / FtpFile / S3File / LocalFile，当前源类型为 " + type);
        }
    }

    // ---- 远端文件操作 ----

    private record RemoteFile(String path, long size, Long mtime) {
    }

    interface RemoteFs extends AutoCloseable {
        record Entry(String name, boolean directory, long size, Long mtime) {
        }

        List<Entry> list(String dir) throws Exception;

        void mkdirs(String dir) throws Exception;

        boolean exists(String path) throws Exception;

        void rename(String from, String to) throws Exception;

        @Override
        void close();
    }

    private RemoteFs open(String type, Map<String, Object> cfg) {
        if ("S3File".equals(type)) {
            return S3Fs.open(cfg);
        }
        if ("LocalFile".equals(type)) {
            return LocalFs.open(cfg);
        }
        String host = str(cfg.get("host"));
        String user = first(str(cfg.get("user")), str(cfg.get("username")));
        String password = str(cfg.get("password"));
        if (host == null || user == null || password == null) {
            throw new IllegalStateException("文件级增量：数据源缺少 host/user/password");
        }
        if ("FtpFile".equals(type)) {
            int port = intOf(cfg.get("port"), 21);
            return FtpFs.open(host, port, user, password);
        }
        int port = intOf(cfg.get("port"), 22);
        return SftpFs.open(host, port, user, password);
    }

    private List<RemoteFile> list(RemoteFs fs, String dir, String archiveName,
                                  Pattern filter) throws Exception {
        List<RemoteFile> out = new ArrayList<>();
        listInto(fs, dir, archiveName, filter, out);
        return out;
    }

    private void listInto(RemoteFs fs, String dir, String archiveName,
                          Pattern filter, List<RemoteFile> out) throws Exception {
        for (RemoteFs.Entry e : fs.list(dir)) {
            if (".".equals(e.name()) || "..".equals(e.name())) {
                continue;
            }
            String path = dir.endsWith("/") ? dir + e.name() : dir + "/" + e.name();
            if (e.directory()) {
                // 归档目录不再扫，否则会把处理过的文件又读一遍
                if (!e.name().equals(archiveName)) {
                    listInto(fs, path, archiveName, filter, out);
                }
                continue;
            }
            String baseName = e.name().substring(e.name().lastIndexOf('/') + 1);
            if (filter != null && !filter.matcher(baseName).matches()) {
                continue;
            }
            out.add(new RemoteFile(path, e.size(), e.mtime()));
        }
    }

    private boolean isMissing(Exception e) {
        if (e instanceof SftpException sftp) {
            return sftp.id == ChannelSftp.SSH_FX_NO_SUCH_FILE;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("no such file") || msg.contains("not found")
                || msg.contains("550");
    }

    static final class SftpFs implements RemoteFs {
        private final Session session;
        private final ChannelSftp sftp;

        private SftpFs(Session session, ChannelSftp sftp) {
            this.session = session;
            this.sftp = sftp;
        }

        static SftpFs open(String host, int port, String user, String password) {
            Session session = null;
            ChannelSftp sftp = null;
            try {
                JSch jsch = new JSch();
                session = jsch.getSession(user, host, port);
                session.setPassword(password);
                session.setConfig("StrictHostKeyChecking", "no");
                session.setTimeout(20000);
                session.connect(20000);
                sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect(20000);
                return new SftpFs(session, sftp);
            } catch (Exception e) {
                // 通道建立失败时 session 已经连上，不 disconnect 会泄漏 socket/线程
                if (sftp != null) {
                    try {
                        sftp.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                if (session != null) {
                    try {
                        session.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                throw new IllegalStateException(
                        "SFTP 连接失败 " + host + ":" + port + " " + e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public List<Entry> list(String dir) throws Exception {
            List<Entry> out = new ArrayList<>();
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(dir);
            for (ChannelSftp.LsEntry e : entries) {
                SftpATTRS attrs = e.getAttrs();
                out.add(new Entry(e.getFilename(), attrs.isDir(), attrs.getSize(),
                        (long) attrs.getMTime() * 1000));
            }
            return out;
        }

        @Override
        public void mkdirs(String dir) throws Exception {
            StringBuilder cur = new StringBuilder();
            for (String p : dir.split("/")) {
                if (p.isEmpty()) {
                    continue;
                }
                cur.append('/').append(p);
                try {
                    sftp.stat(cur.toString());
                } catch (SftpException e) {
                    sftp.mkdir(cur.toString());
                }
            }
        }

        @Override
        public boolean exists(String path) {
            try {
                sftp.stat(path);
                return true;
            } catch (SftpException e) {
                return false;
            }
        }

        @Override
        public void rename(String from, String to) throws Exception {
            sftp.rename(from, to);
        }

        @Override
        public void close() {
            sftp.disconnect();
            session.disconnect();
        }
    }

    static final class FtpFs implements RemoteFs {
        private final FTPClient client;

        private FtpFs(FTPClient client) {
            this.client = client;
        }

        static FtpFs open(String host, int port, String user, String password) {
            FTPClient client = new FTPClient();
            try {
                client.setConnectTimeout(20000);
                client.setDataTimeout(java.time.Duration.ofSeconds(60));
                client.connect(host, port);
                if (!client.login(user, password)) {
                    throw new IOException("登录失败: " + client.getReplyString());
                }
                client.enterLocalPassiveMode();
                client.setFileType(FTP.BINARY_FILE_TYPE);
                return new FtpFs(client);
            } catch (Exception e) {
                try {
                    client.disconnect();
                } catch (Exception ignored) {
                    // 连接本来就失败了
                }
                throw new IllegalStateException(
                        "FTP 连接失败 " + host + ":" + port + " " + e.getMessage(), e);
            }
        }

        @Override
        public List<Entry> list(String dir) throws Exception {
            List<Entry> out = new ArrayList<>();
            FTPFile[] entries = client.listFiles(dir);
            if (entries == null) {
                throw new IOException("列目录失败: " + client.getReplyString());
            }
            for (FTPFile e : entries) {
                Long mtime = e.getTimestamp() == null ? null : e.getTimestamp().getTimeInMillis();
                out.add(new Entry(e.getName(), e.isDirectory(), e.getSize(), mtime));
            }
            return out;
        }

        @Override
        public void mkdirs(String dir) throws Exception {
            StringBuilder cur = new StringBuilder();
            for (String p : dir.split("/")) {
                if (p.isEmpty()) {
                    continue;
                }
                cur.append('/').append(p);
                String path = cur.toString();
                client.makeDirectory(path);
                // 已存在时返回 550，忽略即可；后面的 rename 会暴露真实问题
                client.getReplyString();
            }
        }

        @Override
        public boolean exists(String path) throws Exception {
            FTPFile[] found = client.listFiles(path);
            return found != null && found.length > 0;
        }

        @Override
        public void rename(String from, String to) throws Exception {
            if (!client.rename(from, to)) {
                throw new IOException("rename 失败: " + client.getReplyString());
            }
        }

        @Override
        public void close() {
            try {
                client.logout();
            } catch (Exception ignored) {
                // 已断开
            }
            try {
                client.disconnect();
            } catch (Exception ignored) {
                // 已断开
            }
        }
    }

    /**
     * S3 / MinIO：没有目录概念，列目录用前缀，归档 = copyObject + deleteObject。
     * 配置沿用 SeaTunnel S3File 的字段名：bucket（可带 s3a://）、path、endpoint、accessKey、secretKey。
     */
    static final class S3Fs implements RemoteFs {
        private final S3Client client;
        private final String bucket;

        private S3Fs(S3Client client, String bucket) {
            this.client = client;
            this.bucket = bucket;
        }

        static S3Fs open(Map<String, Object> cfg) {
            String bucket = String.valueOf(cfg.get("bucket"));
            if (bucket == null || "null".equals(bucket)) {
                throw new IllegalStateException("文件级增量：S3 源缺少 bucket");
            }
            bucket = bucket.replaceFirst("^s3a?://", "").replaceAll("/+$", "");
            String endpoint = first(String.valueOf(cfg.getOrDefault("endpoint", "")),
                    String.valueOf(cfg.getOrDefault("fs.s3a.endpoint", "")));
            String accessKey = first(str(cfg.get("accessKey")), str(cfg.get("access_key")));
            String secretKey = first(str(cfg.get("secretKey")), str(cfg.get("secret_key")));
            if (endpoint == null || accessKey == null || secretKey == null) {
                throw new IllegalStateException(
                        "文件级增量：S3 源缺少 endpoint / accessKey / secretKey");
            }
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                endpoint = "http://" + endpoint;
            }
            String region = first(str(cfg.get("region")), "us-east-1");
            S3Client client = S3Client.builder()
                    .endpointOverride(java.net.URI.create(endpoint))
                    .region(Region.of(region))
                    // MinIO 只支持 path-style 虚拟主机
                    .forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
            return new S3Fs(client, bucket);
        }

        private static String key(String path) {
            String p = path == null ? "" : path.trim();
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            return p.replaceAll("/+$", "");
        }

        @Override
        public List<Entry> list(String dir) {
            String prefix = key(dir);
            if (!prefix.isEmpty()) {
                prefix = prefix + "/";
            }
            List<Entry> out = new ArrayList<>();
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).build();
            for (ListObjectsV2Response page : client.listObjectsV2Paginator(request)) {
                for (S3Object o : page.contents()) {
                    String k = o.key();
                    if (k.equals(prefix) || k.endsWith("/")) {
                        continue;
                    }
                    String name = k.substring(prefix.length());
                    out.add(new Entry(name, false, o.size() == null ? 0 : o.size(),
                            o.lastModified() == null ? null : o.lastModified().toEpochMilli()));
                }
            }
            return out;
        }

        @Override
        public void mkdirs(String dir) {
            // S3 没有目录，写对象时自动带前缀
        }

        @Override
        public boolean exists(String path) {
            try {
                client.headObject(HeadObjectRequest.builder()
                        .bucket(bucket).key(key(path)).build());
                return true;
            } catch (NoSuchKeyException e) {
                return false;
            } catch (S3Exception e) {
                return e.statusCode() != 404;
            }
        }

        @Override
        public void rename(String from, String to) {
            client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(key(from))
                    .destinationBucket(bucket).destinationKey(key(to))
                    .build());
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(key(from)).build());
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /**
     * 本地文件：backend 与作业 Pod 共享同一个 NFS 挂载点（默认 /data/local）。
     * 只允许操作共享根目录内的路径，避免误删宿主机文件。
     */
    static final class LocalFs implements RemoteFs {
        /** 允许的共享根：任务配置只能选它下面的子目录，不能把 root 指到 /etc。 */
        private static final java.nio.file.Path BASE = java.nio.file.Paths
                .get(System.getProperty("platform.local-file-base", "/data/local"))
                .toAbsolutePath().normalize();

        private final java.nio.file.Path root;

        private LocalFs(java.nio.file.Path root) {
            this.root = root;
        }

        static LocalFs open(Map<String, Object> cfg) {
            String raw = cfg.get("shared_root") == null
                    ? "/data/local" : String.valueOf(cfg.get("shared_root"));
            java.nio.file.Path configured = java.nio.file.Paths.get(raw);
            java.nio.file.Path root = (configured.isAbsolute() ? configured
                    : BASE.resolve(configured)).toAbsolutePath().normalize();
            if (!root.startsWith(BASE)) {
                throw new IllegalStateException(
                        "本地文件增量：共享目录必须在 " + BASE + " 下，实际 " + root);
            }
            if (!java.nio.file.Files.isDirectory(root)) {
                throw new IllegalStateException(
                        "本地文件增量：共享目录不存在 " + root + "（需要 NFS/PVC 同时挂到 backend 与作业 Pod）");
            }
            try {
                root = root.toRealPath();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("本地文件增量：无法解析共享目录 " + root, e);
            }
            return new LocalFs(root);
        }

        private java.nio.file.Path resolve(String path) {
            java.nio.file.Path configured = java.nio.file.Paths.get(path);
            // 相对路径按 root 解析；以前按进程工作目录解析，任务写相对路径会被判成越界
            java.nio.file.Path p = (configured.isAbsolute() ? configured
                    : root.resolve(configured)).toAbsolutePath().normalize();
            if (!p.startsWith(root)) {
                throw new IllegalStateException(
                        "本地文件增量：路径必须在共享目录 " + root + " 下，实际 " + p);
            }
            // 防符号链接跳出 root：存在的目标取真实路径；不存在的目标校验父目录
            try {
                java.nio.file.Path real = p.toRealPath();
                if (!real.startsWith(root)) {
                    throw new IllegalStateException("本地文件增量：路径经符号链接越界 " + real);
                }
                return real;
            } catch (java.nio.file.NoSuchFileException e) {
                java.nio.file.Path parent = p.getParent();
                if (parent != null) {
                    try {
                        if (!parent.toRealPath().startsWith(root)) {
                            throw new IllegalStateException("本地文件增量：父目录越界 " + parent);
                        }
                    } catch (java.io.IOException ignored) {
                        // 父目录也不存在：后续操作会自然失败，不再放大判断
                    }
                }
                return p;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("本地文件增量：无法解析路径 " + p, e);
            }
        }

        @Override
        public List<Entry> list(String dir) throws Exception {
            java.nio.file.Path d = resolve(dir);
            List<Entry> out = new ArrayList<>();
            try (var stream = java.nio.file.Files.list(d)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                    var attrs = java.nio.file.Files.readAttributes(p,
                            java.nio.file.attribute.BasicFileAttributes.class);
                    out.add(new Entry(p.getFileName().toString(), attrs.isDirectory(),
                            attrs.size(), attrs.lastModifiedTime().toMillis()));
                }
            }
            return out;
        }

        @Override
        public void mkdirs(String dir) throws Exception {
            java.nio.file.Files.createDirectories(resolve(dir));
        }

        @Override
        public boolean exists(String path) {
            return java.nio.file.Files.exists(resolve(path));
        }

        @Override
        public void rename(String from, String to) throws Exception {
            java.nio.file.Files.move(resolve(from), resolve(to));
        }

        @Override
        public void close() {
            // 无连接
        }
    }

    // ---- 配置 ----

    private String archiveDirName(Map<String, Object> cfg) {
        String archive = str(cfg.get("archive_path"));
        if (archive == null) {
            return "done";
        }
        return archive.substring(archive.lastIndexOf('/') + 1);
    }

    /**
     * 归档目录默认放在源目录**旁边**（{@code <path>_done}），不能放在源目录内部：
     * SeaTunnel 文件源会递归读取子目录，归档到内部会被下次运行重新读入（实测报主键冲突）。
     */
    private String archiveTarget(Map<String, Object> cfg) {
        String archive = str(cfg.get("archive_path"));
        String base = archive == null ? siblingDone(str(cfg.get("path"))) : archive;
        // 后端容器默认 UTC：凌晨 0–8 点归档会落到前一天，和业务日期不一致
        return base.replaceAll("/+$", "") + "/"
                + LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    static String siblingDone(String path) {
        String p = path.replaceAll("/+$", "");
        int slash = p.lastIndexOf('/');
        if (slash <= 0) {
            return p + "_done";
        }
        return p.substring(0, slash) + "/" + p.substring(slash + 1) + "_done";
    }

    public static boolean archiveInsideSource(Map<String, Object> sourceConfig) {
        Object rawPath = sourceConfig.get("path");
        Object rawArchive = sourceConfig.get("archive_path");
        if (rawPath == null || rawArchive == null) {
            return false;
        }
        String path = String.valueOf(rawPath).replaceAll("/+$", "");
        String archive = String.valueOf(rawArchive).replaceAll("/+$", "");
        return archive.equals(path) || archive.startsWith(path + "/");
    }

    /**
     * P3-29：非法正则必须明确报 400，不能静默降级成「不过滤」——
     * 否则用户以为只处理部分文件，实际把目录里所有文件都读了一遍。
     */
    static Pattern compileFilter(Object raw) {
        String p = str(raw);
        if (p == null) {
            return null;
        }
        try {
            return Pattern.compile(p);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw ApiException.badRequest("file_filter_pattern 不是合法正则: " + p
                    + "（" + e.getDescription() + "）");
        }
    }

    private static int intOf(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }
}
