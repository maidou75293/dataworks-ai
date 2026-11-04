package com.lab.dataworks.datasource;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.metadata.GravitinoCatalogNaming;
import com.lab.dataworks.metadata.GravitinoClient;
import com.lab.dataworks.metadata.GravitinoException;
import com.lab.dataworks.metadata.MetadataSettings;
import com.lab.dataworks.secret.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DORIS 数据源 → Gravitino Catalog 的单向同步（V101）。
 *
 * <p>同步是「投影」而不是事务的一部分：Gravitino 不可达时数据源必须保存成功，
 * 只把 {@code gravitino_status=error} 与可诊断原因写回行上，管理员修好后再保存一次
 * 即可重试。写回失败本身也不会抛出，不能因为状态列写不进去把用户的数据源操作打挂。</p>
 *
 * <p>幂等：Catalog 名由租户 + 数据源名确定性生成。已存在则 ALTER（更新 jdbc 属性），
 * 不存在才 CREATE；CREATE 撞 409（并发/上一次超时其实已建好）时退化为 ALTER。</p>
 */
@Service
public class GravitinoCatalogSync {

    private static final Logger log = LoggerFactory.getLogger(GravitinoCatalogSync.class);

    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_SYNCED = "synced";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_DISABLED = "disabled";

    public static final String CODE_CONFLICT = "GRAVITINO_CATALOG_CONFLICT";
    public static final String CODE_CONFIG = "GRAVITINO_CONFIG_INVALID";

    private final JdbcTemplate jdbc;
    private final GravitinoClient client;
    private final MetadataSettings settings;
    private final SecretCipher cipher;
    private final String provider;
    /** 内置 Doris 读取用的平台级 Catalog（与 MetadataCatalogResolver 同一个配置）。 */
    private final String builtinCatalog;
    /**
     * 内置 Doris 元数据同步使用的只读账号（dw_meta_ro）。
     *
     * <p>业务账号 dataworks 有 ALL，不应该出现在只读的元数据通道上；但数据源保存 /
     * 手工 sync 会把 {@code platform.datasource} 里的业务凭据投影到 Catalog。
     * 这里对内置 Doris 强制改用 meta RO，避免一次同步就把 Gravitino catalog 的凭据
     * 改回业务账号。外部 Doris 数据源仍用自己的凭据。</p>
     */
    private final String metaRoUser;
    private final String metaRoPassword;

    @Autowired
    public GravitinoCatalogSync(JdbcTemplate jdbc, GravitinoClient client,
                                MetadataSettings settings, SecretCipher cipher,
                                @Value("${gravitino.doris-provider:jdbc-doris}") String provider,
                                @Value("${gravitino.builtin-catalog:doris_dev}") String builtinCatalog,
                                @Value("${doris.meta-ro-user:}") String metaRoUser,
                                @Value("${doris.meta-ro-password:}") String metaRoPassword) {
        this.jdbc = jdbc;
        this.client = client;
        this.settings = settings;
        this.cipher = cipher;
        this.provider = provider == null || provider.isBlank() ? "jdbc-doris" : provider.trim();
        this.builtinCatalog = builtinCatalog == null ? "" : builtinCatalog.trim();
        this.metaRoUser = metaRoUser == null ? "" : metaRoUser.trim();
        this.metaRoPassword = metaRoPassword == null ? "" : metaRoPassword;
    }

    /** 测试用：不配置 meta RO 时等价于旧行为（回落数据源凭据）。 */
    GravitinoCatalogSync(JdbcTemplate jdbc, GravitinoClient client,
                         MetadataSettings settings, SecretCipher cipher,
                         String provider, String builtinCatalog) {
        this(jdbc, client, settings, cipher, provider, builtinCatalog, "", "");
    }

    /** 数据源行（密文口令只在本服务内解密，不返回给接口层）。 */
    record DatasourceRow(long id, String tenantId, String name, String type,
                         String jdbcUrl, String username, String password, String catalog,
                         boolean builtin) {
    }

    /**
     * 同步一个数据源。只处理 type=DORIS；其他类型原样返回 skipped。
     * 不抛异常：失败写 status/message 并在返回值里给出原因。
     */
    public Map<String, Object> sync(long datasourceId) {
        DatasourceRow row;
        try {
            row = load(datasourceId);
        } catch (RuntimeException e) {
            log.warn("gravitino sync load failed ds={}: {}", datasourceId, e.getMessage());
            return result(false, STATUS_ERROR, null, e.getMessage(), "failed");
        }
        if (!"DORIS".equalsIgnoreCase(row.type())) {
            return result(true, STATUS_UNKNOWN, row.catalog(), "非 DORIS 数据源，跳过 Gravitino 同步", "skipped");
        }
        if (!settings.gravitinoEnabled()) {
            String message = "metadata.source=direct：跳过 Gravitino 同步";
            remember(row, row.catalog(), STATUS_DISABLED, message);
            return result(true, STATUS_DISABLED, row.catalog(), message, "skipped");
        }

        // 内置 Doris 的读取用平台级 Catalog（默认 doris_dev），同步也必须落到同一个名字，
        // 否则「读取看 doris_dev、写入同步到租户 Catalog」两份元数据永远不会一致。
        String catalog = row.builtin() && !builtinCatalog.isBlank()
                ? builtinCatalog
                : GravitinoCatalogNaming.catalogName(row.tenantId(), row.name());
        List<Long> owners;
        try {
            owners = row.builtin() ? List.of() : jdbc.queryForList("""
                    select id from platform.datasource
                     where gravitino_catalog = ? and id <> ?
                    """, Long.class, catalog, datasourceId);
        } catch (RuntimeException e) {
            // 平台库查询抖动也不能把用户的数据源保存打挂：写回可诊断状态即可
            String message = "检查 Catalog 占用失败: " + e.getMessage();
            remember(row, row.catalog(), STATUS_ERROR, message);
            log.warn("gravitino owner check failed ds={} catalog={}: {}",
                    datasourceId, catalog, e.getMessage());
            return result(false, STATUS_ERROR, row.catalog(), message, "failed");
        }
        if (!owners.isEmpty()) {
            String message = CODE_CONFLICT + ": Catalog " + catalog
                    + " 已被数据源 " + owners.get(0) + " 占用，请修改数据源名后重试";
            // 冲突时绝不能把别人占用的 Catalog 写进本行：删除数据源时会误删别人的 Catalog
            remember(row, row.catalog(), STATUS_ERROR, message);
            log.warn("gravitino catalog name conflict ds={} catalog={} owner={}",
                    datasourceId, catalog, owners.get(0));
            return result(false, STATUS_ERROR, catalog, message, "failed");
        }

        try {
            Map<String, String> properties = properties(row);
            boolean renamed = false;
            // 数据源改名后 Catalog 名随之变化：用 rename 而不是删了重建，
            // 保留 catalog_id 下的元数据，也不会产生新的软删记录。
            // 内置 Doris 的平台级 Catalog 不参与改名（名字由配置固定）。
            String oldCatalog = row.catalog() == null ? "" : row.catalog().trim();
            if (!row.builtin() && !oldCatalog.isBlank()
                    && !oldCatalog.equalsIgnoreCase(catalog)) {
                try {
                    client.renameCatalog(oldCatalog, catalog);
                    renamed = true;
                } catch (GravitinoException e) {
                    if (!e.notFound()) {
                        throw e;
                    }
                    // 旧 Catalog 已不存在：按新建/更新继续，保持幂等
                    log.warn("old gravitino catalog gone during rename ds={} old={}",
                            datasourceId, oldCatalog);
                }
            }
            String operation;
            if (client.catalogExists(catalog)) {
                client.alterCatalog(catalog, properties);
                operation = renamed ? "renamed" : "updated";
            } else {
                try {
                    client.createCatalog(catalog, provider, properties,
                            "DataWorks datasource " + datasourceId + " (" + row.name() + ")");
                    operation = renamed ? "renamed" : "created";
                } catch (GravitinoException e) {
                    if (!e.conflict()) {
                        throw e;
                    }
                    // 并发保存或上一次请求超时但服务端已建好：退化为 ALTER，保持幂等
                    client.alterCatalog(catalog, properties);
                    operation = renamed ? "renamed" : "updated";
                }
            }
            remember(row, catalog, STATUS_SYNCED, null);
            return result(true, STATUS_SYNCED, catalog, null, operation);
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String message = reason + "（目标 Catalog: " + catalog + "）";
            // 失败时保留旧的 gravitino_catalog：改名场景下下次重试还要用旧名做 rename 兜底。
            // 新建失败时它本来就是 null，不能把未确认存在的目标名写进去。
            remember(row, row.catalog(), STATUS_ERROR, message);
            log.warn("gravitino sync failed ds={} catalog={}: {}", datasourceId, catalog, reason);
            return result(false, STATUS_ERROR, catalog, message, "failed");
        }
    }

    /**
     * 类型从 DORIS 改走后清理：尽力删除 Gravitino Catalog，并清空映射列。
     * 返回本次删除的 Catalog（没有则 null）。
     */
    public Map<String, Object> detach(long datasourceId) {
        DatasourceRow row;
        try {
            row = load(datasourceId);
        } catch (RuntimeException e) {
            log.warn("gravitino detach load failed ds={}: {}", datasourceId, e.getMessage());
            return result(true, STATUS_UNKNOWN, null, e.getMessage(), "skipped");
        }
        List<String> targets = safeTargets(datasourceId, row);
        if (!targets.isEmpty()) {
            deleteQuietly(datasourceId, targets, repairProperties(row));
        }
        String catalog = targets.isEmpty() ? null : targets.get(targets.size() - 1);
        try {
            jdbc.update("""
                    update platform.datasource
                       set gravitino_catalog = null,
                           gravitino_status = 'unknown',
                           gravitino_message = '类型已改为非 DORIS，已停止 Gravitino 同步',
                           gravitino_synced_at = null
                     where id = ?
                       and project_id in (select p.id from platform.project p
                                           where p.tenant_id = ?)
                    """, datasourceId, TenantScope.current());
        } catch (RuntimeException e) {
            log.warn("clear gravitino mapping failed ds={}: {}", datasourceId, e.getMessage());
        }
        return result(true, STATUS_UNKNOWN, catalog, null,
                targets.isEmpty() ? "skipped" : "removed");
    }

    /**
     * 删除数据源前先软删 Gravitino Catalog。顺序满足「先外部、后平台行」；
     * 失败只告警（平台行仍会删除），运维可按审计/日志里的 catalog 名补删。
     */
    public void remove(long datasourceId) {
        DatasourceRow row;
        try {
            row = load(datasourceId);
        } catch (RuntimeException e) {
            log.warn("gravitino remove load failed ds={}: {}", datasourceId, e.getMessage());
            return;
        }
        List<String> targets = safeTargets(datasourceId, row);
        deleteQuietly(datasourceId, targets, repairProperties(row));
    }

    /**
     * {@link #deleteTargets} 崩溃时退回「只删已登记的名字」：清理是尽力而为，
     * 不能因为一次 SQL 抖动把数据源的删除/改类型也一起打挂。
     */
    private List<String> safeTargets(long datasourceId, DatasourceRow row) {
        try {
            return deleteTargets(row);
        } catch (RuntimeException e) {
            log.warn("resolve gravitino delete targets failed ds={}: {}",
                    datasourceId, e.getMessage());
            String stored = row.catalog() == null ? "" : row.catalog().trim();
            return stored.isBlank() ? List.of() : List.of(stored);
        }
    }

    /**
     * 删除时要清理的 Catalog 名：已登记的名字必须删；确定性命名的目标名在
     * 「rename 成功但属性更新失败 / 创建超时但服务端已建好」时也存在，需要一并兜底。
     * 目标名已被别的数据源登记时不动，避免误删别人的 Catalog。
     */
    private List<String> deleteTargets(DatasourceRow row) {
        // 内置 Doris 用平台级 Catalog（doris_dev），它不是这个数据源「拥有」的，
        // 任何清理路径都不能删；内置数据源本身也不允许删除/改类型。
        if (row.builtin()) {
            return List.of();
        }
        List<String> targets = new java.util.ArrayList<>();
        String stored = row.catalog() == null ? "" : row.catalog().trim();
        if (!stored.isBlank()) {
            targets.add(stored);
        }
        if (!settings.gravitinoEnabled()) {
            return targets;
        }
        String candidate = GravitinoCatalogNaming.catalogName(row.tenantId(), row.name());
        if (targets.stream().anyMatch(candidate::equalsIgnoreCase)) {
            return targets;
        }
        List<Long> owners = jdbc.queryForList("""
                select id from platform.datasource
                 where gravitino_catalog = ? and id <> ?
                """, Long.class, candidate, row.id());
        if (owners.isEmpty()) {
            targets.add(candidate);
        }
        return targets;
    }

    /**
     * 删除前先刷新凭据：Gravitino 的 DropCatalogDispatcher 会用自己的 properties
     * 建连做清理，口令为空/过期时会 Access denied 导致删不掉。能拿到本数据源
     * 凭据时先 ALTER 一次再删除（幂等；GET 不回传敏感属性，无法读回判断）。
     */
    private void deleteQuietly(long datasourceId, List<String> targets,
                               Map<String, String> repair) {
        for (String catalog : targets) {
            ensureCredentials(catalog, repair);
            try {
                client.deleteCatalog(catalog);
            } catch (RuntimeException e) {
                log.warn("drop gravitino catalog before datasource delete failed ds={} catalog={}: {}",
                        datasourceId, catalog, e.getMessage());
            }
        }
    }

    void ensureCredentials(String catalog, Map<String, String> repair) {
        if (catalog == null || catalog.isBlank() || repair == null || repair.isEmpty()) {
            return;
        }
        try {
            // Gravitino 的 GET 不会返回 jdbc-user / jdbc-password（敏感属性被隐藏），
            // 所以不能靠读回判断「是否齐全」：只要 Catalog 存在就写一次（幂等），
            // 保证 DropCatalogDispatcher 用的是当前有效的口令。
            Map<String, Object> detail = client.getCatalog(catalog);
            if (detail == null) {
                return;
            }
            client.alterCatalog(catalog, repair);
            log.info("refreshed gravitino catalog credentials before drop: {}", catalog);
        } catch (RuntimeException e) {
            // 修复失败不阻断删除：删除本身会报出真实原因，运维可按日志补
            log.warn("repair gravitino catalog credentials failed catalog={}: {}",
                    catalog, e.getMessage());
        }
    }

    private Map<String, String> repairProperties(DatasourceRow row) {
        try {
            return properties(row);
        } catch (RuntimeException e) {
            log.warn("no datasource credentials for catalog repair ds={}: {}",
                    row.id(), e.getMessage());
            return Map.of();
        }
    }

    // ---------------------------------------------------------------- 内部

    private DatasourceRow load(long datasourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.name, d.ds_type, d.jdbc_url, d.username, d.password,
                       d.gravitino_catalog, p.tenant_id,
                       coalesce(d.config_json ->> 'builtin', 'false') = 'true' as builtin
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where d.id = ? and p.tenant_id = ?
                """, datasourceId, TenantScope.current());
        if (rows.isEmpty()) {
            throw new IllegalStateException("数据源不存在或不属于当前租户: " + datasourceId);
        }
        Map<String, Object> row = rows.get(0);
        return new DatasourceRow(
                number(row.get("id")),
                str(row.get("tenant_id")),
                str(row.get("name")),
                str(row.get("ds_type")),
                str(row.get("jdbc_url")),
                str(row.get("username")),
                str(row.get("password")),
                str(row.get("gravitino_catalog")),
                Boolean.TRUE.equals(row.get("builtin")));
    }

    private Map<String, String> properties(DatasourceRow row) {
        if (row.jdbcUrl() == null || row.jdbcUrl().isBlank()) {
            throw new IllegalStateException(CODE_CONFIG + ": DORIS 数据源缺少 jdbc-url");
        }
        String[] credentials = effectiveCredentials(row);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("jdbc-url", dorisJdbcUrl(row.jdbcUrl()));
        properties.put("jdbc-user", credentials[0]);
        properties.put("jdbc-password", credentials[1] == null ? "" : credentials[1]);
        // Doris 走 MySQL 协议；与 scripts/ops/gravitino-init.sh 的现有 catalog 保持一致
        properties.put("jdbc-driver", "com.mysql.cj.jdbc.Driver");
        return properties;
    }

    /**
     * 内置 Doris 优先用 meta RO；外部数据源仍用自己的凭据。
     * 返回 {@code [user, password]}；密码已解密（meta RO 密码本身就是明文 Secret）。
     */
    private String[] effectiveCredentials(DatasourceRow row) {
        if (useMetaRo(row.builtin(), metaRoUser, metaRoPassword)) {
            return new String[]{metaRoUser, metaRoPassword};
        }
        if (row.username() == null || row.username().isBlank()) {
            throw new IllegalStateException(CODE_CONFIG + ": DORIS 数据源缺少用户名");
        }
        String password;
        try {
            password = cipher.decrypt(row.password());
        } catch (RuntimeException e) {
            throw new IllegalStateException(CODE_CONFIG + ": 数据源密码无法解密，请重新输入密码后保存");
        }
        return new String[]{row.username(), password};
    }

    /**
     * 是否用 meta RO 覆盖内置 Doris 的同步凭据：只有「内置 + 用户名密码都配置」才切换。
     * 未配置时回落数据源凭据，保证老集群升级/外部数据源行为不变。
     */
    static boolean useMetaRo(boolean builtin, String metaUser, String metaPassword) {
        return builtin
                && metaUser != null && !metaUser.isBlank()
                && metaPassword != null && !metaPassword.isBlank();
    }

    /**
     * Doris Catalog 必须连 FE 的 MySQL 查询端口 9030：
     * 数据源里写成 FE HTTP 端口 8030 或没写端口时，Gravitino 建连会失败。
     * 其他显式端口（代理/自定义）保持原样，交给用户判断。
     */
    static String dorisJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        String value = url.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "^(jdbc:(?:mysql|mariadb)://[^/:]+)(?::(\\d+))?(/.*)?$",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value);
        if (!m.matches()) {
            return value;
        }
        String port = m.group(2);
        if (port == null || "8030".equals(port)) {
            return m.group(1) + ":9030" + (m.group(3) == null ? "" : m.group(3));
        }
        return value;
    }

    private void remember(DatasourceRow row, String catalog, String status, String message) {
        String text = message == null ? null
                : (message.length() > 1000 ? message.substring(0, 1000) : message);
        try {
            jdbc.update("""
                    update platform.datasource
                       set gravitino_catalog = ?, gravitino_status = ?, gravitino_message = ?,
                           gravitino_synced_at = case when ? = 'synced'
                                                      then now() else gravitino_synced_at end
                     where id = ?
                       and project_id in (select p.id from platform.project p
                                           where p.tenant_id = ?)
                    """, catalog, status, text, status, row.id(), row.tenantId());
        } catch (RuntimeException e) {
            log.warn("update gravitino status failed ds={}: {}", row.id(), e.getMessage());
        }
    }

    private static Map<String, Object> result(boolean ok, String status, String catalog,
                                              String message, String operation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("status", status);
        out.put("catalog", catalog);
        out.put("message", message);
        out.put("operation", operation);
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }
}
