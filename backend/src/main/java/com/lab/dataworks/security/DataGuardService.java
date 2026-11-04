package com.lab.dataworks.security;

import com.lab.dataworks.auth.RbacService;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantDataScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.lab.dataworks.common.LikePattern;

/**
 * 数据分级与脱敏（设计见 docs/权限模型抽象设计-20261024.md §10）。
 *
 * <p>两道门：动作权限（能不能查）× 数据分级（能查哪张表/哪一列）。
 * 表级 L1/L2 直接放行，L3/L4 需要审批通过的授权（或负责人、安全角色）。
 * 列级按 mask_rule 脱敏，只有 {@code column:unmask} 能看明文。</p>
 *
 * <p>网关三档：OFF / LOG（只判定不拦截，默认）/ ENFORCE。默认 LOG 是为了上线前
 * 先用真实流量看「会拦谁、会遮哪列」，确认没有误伤再切 ENFORCE。</p>
 */
@Component
public class DataGuardService {

    public static final String OFF = "OFF";
    public static final String LOG = "LOG";
    public static final String ENFORCE = "ENFORCE";
    public static final String MODE_KEY = "data_guard_mode";
    public static final String PERMISSION_MODE_KEY = "permission_mode";
    public static final String PERMISSION_ENFORCE_KEY = "permission_enforce";
    public static final String PERMISSION_THRESHOLD_KEY = "permission_small_result_threshold";
    public static final String PERMISSION_OFF = "OFF";
    public static final String PERMISSION_TABLE = "TABLE";
    public static final String PERMISSION_COLUMN = "COLUMN";

    // ---------- v483：项目级安全策略（V96，优先级：项目 > 租户 > 平台） ----------
    /** 项目不覆盖，完全继承上一级。 */
    public static final String PROJECT_INHERIT = "INHERIT";
    public static final String SOURCE_PROJECT = "PROJECT";
    public static final String SOURCE_TENANT = "TENANT";
    public static final String SOURCE_PLATFORM = "PLATFORM";
    /** 租户级开关的 setting 键前缀（当前没有管理入口，属预留能力）。 */
    private static final String TENANT_MODE_PREFIX = "permission_mode.tenant.";
    private static final String PLATFORM_DEFAULT_COLUMN_LEVEL = "L2";
    private static final long PROJECT_SECURITY_TTL_MS = 5_000L;
    private static final Set<String> PROJECT_MODES = Set.of(
            PROJECT_INHERIT, PERMISSION_OFF, PERMISSION_TABLE, PERMISSION_COLUMN);

    /**
     * 系统库：除了平台管理员，任何角色都不能读（D04）。
     *
     * <p>旧实现遇到这些 schema 直接 {@code continue}，等于完全不做分级判定，
     * 租户 VIEWER 都能读 Doris 全平台审计日志（__internal_schema.audit_log）、
     * mysql.user、information_schema 里的其他租户库名。审计请走平台接口
     * {@code /api/security/audit}（按租户收口），不要直连系统库。</p>
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "mysql", "__internal_schema", "sys", "platform");
    private static final Set<String> LEVELS = Set.of("L1", "L2", "L3", "L4");
    private static final Set<String> MASKS = Set.of(
            "NONE", "PLAIN", "MASK", "TOKEN", "FULL", "PARTIAL", "HASH", "NULL");

    // ---------- v478：输出层列脱敏（MASK / TOKEN / PLAIN） ----------
    /** 列规则缓存（按租户，30s TTL）：结果集处理不额外查库。 */
    private volatile Map<String, List<Map<String, String>>> columnRuleCache;
    private volatile String columnRuleTenant;
    private volatile long columnRuleAt;
    private static final long COLUMN_RULE_TTL_MS = 30_000;
    private final Map<String, String> tenantSalts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> SENSITIVITIES = Set.of(
            "NORMAL", "PII", "PHONE", "ID_CARD", "EMAIL", "NAME", "AMOUNT");

    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\n]*");
    private static final Pattern LITERALS = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern TABLE_KEYWORD = Pattern.compile("(?i)\\b(from|join)\\b");
    /** 支持 table / schema.table / catalog.schema.table（V100：Iceberg 湖表）。 */
    private static final Pattern IDENT = Pattern.compile(
            "^[`\"\\[]?([A-Za-z_][\\w$]*)[`\"\\]]?"
            + "(?:\\s*\\.\\s*[`\"\\[]?([A-Za-z_][\\w$]*)[`\"\\]]?)?"
            + "(?:\\s*\\.\\s*[`\"\\[]?([A-Za-z_][\\w$]*)[`\"\\]]?)?");
    private static final Pattern CLAUSE = Pattern.compile(
            "(?i)\\b(where|group\\s+by|order\\s+by|having|limit|union|on|using|offset|"
                    + "left|right|inner|outer|cross|full)\\b");
    private static final Pattern CTE = Pattern.compile(
            "(?is)\\bwith\\s+([\\w$]+)\\s+as\\s*\\(");

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DataGuardService.class);

    private final JdbcTemplate jdbc;
    private final RbacService rbac;
    private final SessionService sessions;
    /** D01：跨租户库归属判定（项目 dev/prod 库 + 纳管表回退）。 */
    private final TenantDataScope dataScope;
    // v460：默认 ENFORCE（fail-closed）。新环境没有 setting 行时不再回落到 LOG。
    // v482：权限控制总开关（OFF/TABLE/COLUMN）+ 生效方式（LOG/ENFORCE）+ 小结果集阈值
    private volatile String cachedPermissionMode = PERMISSION_TABLE;
    private volatile String cachedPermissionEnforce = ENFORCE;
    private volatile int cachedSmallThreshold = 10;
    private volatile long permissionCachedAt = 0L;
    private static final long PERMISSION_TTL_MS = 5_000L;

    // v483：租户级模式（setting: permission_mode.tenant.<code>），没有配置时为空。
    private volatile Map<String, String> cachedTenantModes = Map.of();
    // 项目安全策略缓存（5s，键含租户）：查询链路上每条 SQL 都要解析项目策略，避免重复查库。
    private final Map<String, CachedProjectSecurity> projectSecurityCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedProjectSecurity(long at, ProjectSecurity value) {
    }

    private volatile String cachedMode = ENFORCE;
    private volatile long cachedAt = 0L;
    private static final long TTL_MS = 3_000L;

    public DataGuardService(JdbcTemplate jdbc, RbacService rbac, SessionService sessions,
                            TenantDataScope dataScope) {
        this.jdbc = jdbc;
        this.rbac = rbac;
        this.sessions = sessions;
        this.dataScope = dataScope;
    }

    // ---------- 网关模式 ----------

    private void loadPermissionConfig(long now) {
        if (now - permissionCachedAt < PERMISSION_TTL_MS) {
            return;
        }
        synchronized (this) {
            if (now - permissionCachedAt < PERMISSION_TTL_MS) {
                return;
            }
            String mode = PERMISSION_TABLE;
            String enforce = ENFORCE;
            int threshold = 10;
            Map<String, String> tenantModes = new LinkedHashMap<>();
            try {
                for (Map<String, Object> row : jdbc.queryForList("""
                        select setting_key, setting_value from platform.setting
                        where setting_key in ('permission_mode', 'permission_enforce',
                                              'permission_small_result_threshold')
                           or setting_key like 'permission_mode.tenant.%'
                        """)) {
                    String key = str(row.get("setting_key"));
                    String value = str(row.get("setting_value")).trim();
                    if (key.startsWith(TENANT_MODE_PREFIX)) {
                        String tenant = key.substring(TENANT_MODE_PREFIX.length());
                        String m = normalizePermissionMode(value, null);
                        if (!tenant.isBlank() && m != null) {
                            tenantModes.put(tenant, m);
                        }
                    } else if (PERMISSION_MODE_KEY.equals(key)) {
                        String m = value.toUpperCase(Locale.ROOT);
                        if (PERMISSION_OFF.equals(m) || PERMISSION_TABLE.equals(m)
                                || PERMISSION_COLUMN.equals(m)) {
                            mode = m;
                        }
                    } else if (PERMISSION_ENFORCE_KEY.equals(key)) {
                        String e = value.toUpperCase(Locale.ROOT);
                        if (LOG.equals(e) || ENFORCE.equals(e)) {
                            enforce = e;
                        }
                    } else if (PERMISSION_THRESHOLD_KEY.equals(key)) {
                        try {
                            int t = Integer.parseInt(value);
                            if (t >= 1 && t <= 100000) {
                                threshold = t;
                            }
                        } catch (NumberFormatException ignored) {
                            // 非法阈值按默认 10
                        }
                    }
                }
                cachedPermissionMode = mode;
                cachedPermissionEnforce = enforce;
                cachedSmallThreshold = threshold;
                cachedTenantModes = tenantModes;
            } catch (Exception e) {
                // 读不到配置时保持上一次值（新环境默认 TABLE + ENFORCE）
                log.warn("读取权限开关失败（{}），沿用 {} / {}", e.getMessage(),
                        cachedPermissionMode, cachedPermissionEnforce);
            }
            permissionCachedAt = now;
        }
    }

    public String permissionMode() {
        loadPermissionConfig(System.currentTimeMillis());
        return cachedPermissionMode;
    }

    public String permissionEnforce() {
        loadPermissionConfig(System.currentTimeMillis());
        return cachedPermissionEnforce;
    }

    public int smallResultThreshold() {
        loadPermissionConfig(System.currentTimeMillis());
        return cachedSmallThreshold;
    }

    public void invalidatePermissionCache() {
        permissionCachedAt = 0L;
        cachedMode = ENFORCE;
        cachedAt = 0L;
    }

    /** 守卫用的旧三档，按新开关推导：OFF 关闭；LOG 只记录；其余 ENFORCE。 */
    public String mode() {
        String permissionMode = permissionMode();
        if (PERMISSION_OFF.equals(permissionMode)) {
            return OFF;
        }
        return LOG.equals(permissionEnforce()) ? LOG : ENFORCE;
    }

    // ---------- v483：项目级安全策略 ----------

    /**
     * 项目自身的安全配置（V96）。只存原始值，回退逻辑在 {@link #policy(Long)}。
     */
    public record ProjectSecurity(long projectId, String ownerUser, String securityMode,
                                  String defaultColumnLevel, Boolean columnControlRequired) {
    }

    /**
     * 一次解析的结果（守卫与 GET /security 共用）。
     *
     * <p>{@code effectiveMode} 是最终生效的粒度（OFF/TABLE/COLUMN）；
     * {@code enforce}（LOG/ENFORCE）与阈值仍只用平台配置——项目不覆盖生效方式。</p>
     */
    public record Policy(String effectiveMode, String source, String enforce,
                         String platformMode, String tenantMode, Long projectId,
                         String securityMode, String defaultColumnLevel,
                         Boolean columnControlRequired) {
    }

    /**
     * 解析优先级：项目 > 租户 > 平台默认。
     *
     * <ul>
     *   <li>column_control_required=true → COLUMN（覆盖项目粒度）；</li>
     *   <li>项目 security_mode != INHERIT → 用它；</li>
     *   <li>项目未设置/项目不存在 → 租户配置（如有）→ 平台 permission_mode。</li>
     * </ul>
     */
    public Policy policy(Long projectId) {
        loadPermissionConfig(System.currentTimeMillis());
        ProjectSecurity project = projectSecurity(projectId);
        String tenant = TenantScope.currentOrNull();
        String tenantMode = tenant == null ? null : cachedTenantModes.get(tenant);
        String projectMode = project == null ? PROJECT_INHERIT : project.securityMode();
        Boolean required = project == null ? null : project.columnControlRequired();
        String effective = resolveEffectiveMode(projectMode, required, tenantMode,
                cachedPermissionMode);
        String source = resolveSource(projectMode, required, tenantMode);
        return new Policy(effective, source, cachedPermissionEnforce, cachedPermissionMode,
                tenantMode, projectId, normalizeProjectMode(projectMode),
                project == null ? null : project.defaultColumnLevel(), required);
    }

    /**
     * 项目当前的安全配置；不存在或不属于当前租户返回 null（调用方统一按 404）。
     * 5 秒缓存，修改后由 {@link #invalidateProjectSecurity(Long)} 立即失效。
     */
    public ProjectSecurity projectSecurity(Long projectId) {
        if (projectId == null) {
            return null;
        }
        String tenant = TenantScope.current();
        // 缓存键必须带租户：跨租户传同一个 projectId 时不能读到别人的配置。
        String cacheKey = tenant + "#" + projectId;
        long now = System.currentTimeMillis();
        CachedProjectSecurity cached = projectSecurityCache.get(cacheKey);
        if (cached != null && now - cached.at() < PROJECT_SECURITY_TTL_MS) {
            return cached.value();
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    select id, owner_user, security_mode, default_column_level,
                           column_control_required
                      from platform.project
                     where id = ? and tenant_id = ?
                    """, projectId, tenant);
        } catch (RuntimeException e) {
            // 迁移未执行等异常不能把查询链路带崩：优先沿用上次已知配置（含过期缓存），
            // 完全没有历史值时按「项目未配置」回退平台默认。
            log.warn("读取项目安全策略失败（projectId={}）：{}，沿用{}", projectId,
                    e.getMessage(), cached == null ? "平台默认" : "上次配置");
            return cached == null ? null : cached.value();
        }
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        ProjectSecurity value = new ProjectSecurity(projectId, str(row.get("owner_user")),
                normalizeProjectMode(str(row.get("security_mode"))),
                levelTextOrNull(row.get("default_column_level")),
                boolOrNull(row.get("column_control_required")));
        projectSecurityCache.put(cacheKey, new CachedProjectSecurity(now, value));
        return value;
    }

    public void invalidateProjectSecurity(Long projectId) {
        if (projectId == null) {
            projectSecurityCache.clear();
        } else {
            String suffix = "#" + projectId;
            projectSecurityCache.keySet().removeIf(key -> key.endsWith(suffix));
        }
    }

    /** 守卫三档：项目解析后 OFF → OFF；否则生效方式仍取平台 LOG/ENFORCE。 */
    public String guardMode(Long projectId) {
        Policy policy = policy(projectId);
        if (PERMISSION_OFF.equals(policy.effectiveMode())) {
            return OFF;
        }
        return LOG.equals(policy.enforce()) ? LOG : ENFORCE;
    }

    /** 最终生效粒度（OFF/TABLE/COLUMN）；列级掩码只在 COLUMN 时生效。 */
    public String granularity(Long projectId) {
        return policy(projectId).effectiveMode();
    }

    /**
     * 按 Doris 库名找当前租户内的项目（dev_database / prod_database），
     * 用于「新增列默认等级跟随项目配置」。库名大小写不敏感。
     */
    public Long projectIdBySchema(String schema) {
        if (blank(schema)) {
            return null;
        }
        List<Long> rows = jdbc.queryForList("""
                select id from platform.project
                 where tenant_id = ?
                   and (lower(dev_database) = lower(?) or lower(prod_database) = lower(?))
                 order by id limit 1
                """, Long.class, TenantScope.current(), schema.trim(), schema.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新增列默认等级：项目配置优先，未配置/非法回落平台默认 L2。 */
    public String defaultColumnLevel(String schema) {
        try {
            Long projectId = projectIdBySchema(schema);
            if (projectId != null) {
                ProjectSecurity project = projectSecurity(projectId);
                if (project != null) {
                    return resolveDefaultColumnLevel(project.defaultColumnLevel());
                }
            }
        } catch (RuntimeException e) {
            // 查项目失败不能挡住列配置：回落平台默认，保证管理动作可用。
            log.warn("解析项目默认列等级失败（schema={}）：{}，回落平台默认 {}",
                    schema, e.getMessage(), PLATFORM_DEFAULT_COLUMN_LEVEL);
        }
        return PLATFORM_DEFAULT_COLUMN_LEVEL;
    }

    /** 平台默认列等级（V96 之前只有 L2）。 */
    public static String platformDefaultColumnLevel() {
        return PLATFORM_DEFAULT_COLUMN_LEVEL;
    }

    /** 有效粒度：column_control_required 最优先，其次项目，再次租户，最后平台。 */
    static String resolveEffectiveMode(String projectMode, Boolean columnControlRequired,
                                       String tenantMode, String platformMode) {
        if (Boolean.TRUE.equals(columnControlRequired)) {
            return PERMISSION_COLUMN;
        }
        String project = normalizeProjectMode(projectMode);
        if (!PROJECT_INHERIT.equals(project)) {
            return project;
        }
        String tenant = normalizePermissionMode(tenantMode, null);
        if (tenant != null) {
            return tenant;
        }
        String platform = normalizePermissionMode(platformMode, null);
        return platform == null ? PERMISSION_TABLE : platform;
    }

    /** 有效值来自哪一层（前端「继承自平台」提示用）。 */
    static String resolveSource(String projectMode, Boolean columnControlRequired,
                                String tenantMode) {
        if (Boolean.TRUE.equals(columnControlRequired)) {
            return SOURCE_PROJECT;
        }
        if (!PROJECT_INHERIT.equals(normalizeProjectMode(projectMode))) {
            return SOURCE_PROJECT;
        }
        return normalizePermissionMode(tenantMode, null) != null ? SOURCE_TENANT : SOURCE_PLATFORM;
    }

    /** 项目粒度：非法/空按 INHERIT（宁可跟随平台，也不静默放行）。 */
    static String normalizeProjectMode(String raw) {
        String value = str(raw).trim().toUpperCase(Locale.ROOT);
        return PROJECT_MODES.contains(value) ? value : PROJECT_INHERIT;
    }

    /** 写接口用的严格解析：非法值 400，不静默回退。 */
    public static String requireProjectMode(String raw) {
        String value = str(raw).trim().toUpperCase(Locale.ROOT);
        if (!PROJECT_MODES.contains(value)) {
            throw ApiException.badRequest("securityMode 只能是 INHERIT / OFF / TABLE / COLUMN");
        }
        return value;
    }

    static String normalizePermissionMode(String raw, String fallback) {
        String value = str(raw).trim().toUpperCase(Locale.ROOT);
        if (PERMISSION_OFF.equals(value) || PERMISSION_TABLE.equals(value)
                || PERMISSION_COLUMN.equals(value)) {
            return value;
        }
        return fallback;
    }

    /** 项目默认列等级：非法/未配置回落平台默认 L2。 */
    public static String resolveDefaultColumnLevel(String projectDefault) {
        String value = str(projectDefault).trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(value) ? value : PLATFORM_DEFAULT_COLUMN_LEVEL;
    }

    private static String levelTextOrNull(Object raw) {
        String value = str(raw).trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(value) ? value : null;
    }

    private static Boolean boolOrNull(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "t".equals(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equals(value) || "f".equals(value) || "0".equals(value)) {
            return false;
        }
        return null;
    }

    /** 写入权限开关（必须由 system:manage 调用；审计在调用方）。 */
    public void setPermission(String mode, String enforce, Integer threshold) {
        String m = mode == null ? PERMISSION_TABLE : mode.trim().toUpperCase(Locale.ROOT);
        if (!PERMISSION_OFF.equals(m) && !PERMISSION_TABLE.equals(m) && !PERMISSION_COLUMN.equals(m)) {
            throw ApiException.badRequest("mode 只允许 OFF / TABLE / COLUMN");
        }
        String e = enforce == null ? ENFORCE : enforce.trim().toUpperCase(Locale.ROOT);
        if (!LOG.equals(e) && !ENFORCE.equals(e)) {
            throw ApiException.badRequest("enforce 只允许 LOG / ENFORCE");
        }
        int t = threshold == null ? smallResultThreshold() : threshold;
        if (t < 1 || t > 100000) {
            throw ApiException.badRequest("smallResultThreshold 需在 1-100000");
        }
        upsertSetting(PERMISSION_MODE_KEY, m);
        upsertSetting(PERMISSION_ENFORCE_KEY, e);
        upsertSetting(PERMISSION_THRESHOLD_KEY, String.valueOf(t));
        // 兼容旧键：旧页面/脚本仍读 data_guard_mode
        upsertSetting(MODE_KEY, PERMISSION_OFF.equals(m) ? OFF : (LOG.equals(e) ? LOG : ENFORCE));
        invalidatePermissionCache();
    }

    private void upsertSetting(String key, String value) {
        jdbc.update("""
                insert into platform.setting (setting_key, setting_value, updated_by)
                values (?, ?, 'permission-mode') on conflict (setting_key)
                do update set setting_value = excluded.setting_value, updated_at = now(),
                              updated_by = excluded.updated_by
                """, key, value);
    }

    public void setMode(String mode, String actor) {
        String normalized = normalizeMode(mode);
        // 兼容旧 /guard 切换：写新键，保留当前 TABLE/COLUMN 粒度
        String granularity = permissionMode();
        if (OFF.equals(normalized)) {
            setPermission(PERMISSION_OFF, permissionEnforce(), smallResultThreshold());
        } else {
            setPermission(PERMISSION_OFF.equals(granularity) ? PERMISSION_TABLE : granularity,
                    normalized, smallResultThreshold());
        }
        if (!normalized.equals(mode)) {
            // 大小写/空白不同也算非法：开关值必须精确
            throw ApiException.badRequest("dataGuardMode 只能是 OFF / LOG / ENFORCE");
        }
        jdbc.update("""
                insert into platform.setting (setting_key, setting_value, updated_at, updated_by)
                values (?, ?, now(), ?)
                on conflict (setting_key) do update
                  set setting_value = excluded.setting_value,
                      updated_at = now(), updated_by = excluded.updated_by
                """, MODE_KEY, normalized, actor);
        cachedMode = normalized;
        cachedAt = System.currentTimeMillis();
    }

    private static String normalizeMode(String mode) {
        String value = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(OFF, LOG, ENFORCE).contains(value)) {
            throw ApiException.badRequest("dataGuardMode 只能是 OFF / LOG / ENFORCE");
        }
        return value;
    }

    // ---------- 定级目录 ----------

    public List<Map<String, Object>> tables(String keyword) {
        String tenant = TenantScope.current();
        String like = keyword == null || keyword.isBlank()
                ? null : LikePattern.contains(keyword.trim().toLowerCase(Locale.ROOT));
        StringBuilder sql = new StringBuilder("""
                select t.id, t.catalog, t.schema_name, t.table_name, t.owner_user,
                       t.description, t.data_level, t.level_updated_at, t.level_updated_by,
                       (select count(*) from platform.managed_column c where c.table_id = t.id)
                           as column_count
                from platform.managed_table t
                where t.tenant = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (like != null) {
            sql.append(" and (lower(t.schema_name || '.' || t.table_name) like ?")
                    .append(" or lower(t.table_name) like ?)");
            args.add(like);
            args.add(like);
        }
        sql.append(" order by t.schema_name, t.table_name");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            long id = ((Number) row.get("id")).longValue();
            List<Map<String, Object>> columnItems = columns(id);
            item.put("id", row.get("id"));
            item.put("catalog", row.get("catalog"));
            item.put("schemaName", row.get("schema_name"));
            item.put("tableName", row.get("table_name"));
            item.put("ownerUser", row.get("owner_user"));
            item.put("description", row.get("description"));
            // 表级等级不再手工设置：由列等级取最大值推导；没有列时才回落到 stored 值。
            item.put("dataLevel", deriveLevel(columnItems, str(row.get("data_level"))));
            item.put("levelUpdatedAt", row.get("level_updated_at"));
            item.put("levelUpdatedBy", row.get("level_updated_by"));
            item.put("columnCount", row.get("column_count"));
            item.put("columns", columnItems);
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> columns(long tableId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, column_name, data_type, sensitivity, mask_rule, data_level,
                       updated_at, updated_by
                from platform.managed_column where table_id = ? order by column_name
                """, tableId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("columnName", row.get("column_name"));
            item.put("dataType", row.get("data_type"));
            item.put("sensitivity", row.get("sensitivity"));
            item.put("maskRule", row.get("mask_rule"));
            item.put("dataLevel", row.get("data_level"));
            item.put("updatedBy", row.get("updated_by"));
            out.add(item);
        }
        return out;
    }

    public Map<String, Object> register(String catalog, String schemaName, String tableName,
                                        String ownerUser, String dataLevel, String actor) {
        if (blank(schemaName) || blank(tableName)) {
            throw ApiException.badRequest("schemaName / tableName 不能为空");
        }
        // 表级不再手填：登记时只落 stored 值（默认 L2），展示/判定以列推导为准。
        String level = normalizeLevel(dataLevel == null ? "L2" : dataLevel);
        String tenant = TenantScope.current();
        Integer exists = jdbc.queryForObject("""
                select count(*) from platform.managed_table
                where tenant = ? and catalog = ? and schema_name = ? and table_name = ?
                """, Integer.class, tenant, catalog == null ? "Doris" : catalog,
                schemaName.trim(), tableName.trim());
        if (exists != null && exists > 0) {
            throw ApiException.conflict("该表已登记: " + schemaName + "." + tableName);
        }
        Long id = jdbc.queryForObject("""
                insert into platform.managed_table
                    (tenant, catalog, schema_name, table_name, owner_user, data_level,
                     level_updated_at, level_updated_by)
                values (?, ?, ?, ?, ?, ?, now(), ?)
                returning id
                """, Long.class, tenant, catalog == null ? "Doris" : catalog,
                schemaName.trim(), tableName.trim(),
                blank(ownerUser) ? actor : ownerUser, level, actor);
        return Map.of("ok", true, "id", id, "dataLevel", level);
    }

    /**
     * 表级等级不再手工设置（4a）：等级由列等级推导，接口返回 400 引导用户去配列等级。
     */
    public void setLevel(long id, String dataLevel, String actor) {
        throw ApiException.badRequest("表级等级由字段等级推导，请到「字段分级」设置列等级");
    }

    /** 列等级变更，供 Controller 写 COLUMN_LEVEL_UPDATE 审计。 */
    public record LevelChange(String target, String oldLevel, String newLevel) {
    }

    public List<LevelChange> setColumns(long tableId, List<Map<String, Object>> columns,
                                        String actor) {
        List<Map<String, Object>> tables = jdbc.queryForList("""
                select schema_name, table_name from platform.managed_table
                where id = ? and tenant = ?
                """, tableId, TenantScope.current());
        if (tables.isEmpty()) {
            throw ApiException.notFound("表不存在: " + tableId);
        }
        String schemaName = str(tables.get(0).get("schema_name"));
        String prefix = schemaName + "." + str(tables.get(0).get("table_name")) + ".";
        // v483：老客户端不传 dataLevel 时，新增列用「该表所属项目」的默认列等级。
        String defaultLevel = defaultColumnLevel(schemaName);
        if (columns == null) {
            return List.of();
        }
        List<LevelChange> changes = new ArrayList<>();
        for (Map<String, Object> item : columns) {
            String name = str(item.get("columnName")).trim();
            if (name.isEmpty()) {
                continue;
            }
            String sensitivity = str(item.get("sensitivity")).isEmpty()
                    ? "NORMAL" : str(item.get("sensitivity")).toUpperCase(Locale.ROOT);
            String mask = str(item.get("maskRule")).isEmpty()
                    ? "NONE" : str(item.get("maskRule")).toUpperCase(Locale.ROOT);
            String level = levelOrNull(item.get("dataLevel"));
            if (!SENSITIVITIES.contains(sensitivity)) {
                throw ApiException.badRequest("未知的 sensitivity: " + sensitivity);
            }
            if (!MASKS.contains(mask)) {
                throw ApiException.badRequest("未知的 maskRule: " + mask);
            }
            String oldLevel = null;
            if (level != null) {
                List<String> old = jdbc.queryForList("""
                        select data_level from platform.managed_column
                        where table_id = ? and column_name = ?
                        """, String.class, tableId, name);
                oldLevel = old.isEmpty() ? null : old.get(0);
                jdbc.update("""
                        insert into platform.managed_column
                            (table_id, column_name, data_type, sensitivity, mask_rule,
                             data_level, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (table_id, column_name) do update
                          set sensitivity = excluded.sensitivity,
                              mask_rule = excluded.mask_rule,
                              data_type = excluded.data_type,
                              data_level = excluded.data_level,
                              updated_at = now(),
                              updated_by = excluded.updated_by
                        """, tableId, name, str(item.get("dataType")), sensitivity, mask,
                        level, actor);
                if (!level.equalsIgnoreCase(str(oldLevel))) {
                    changes.add(new LevelChange(prefix + name,
                            oldLevel == null || oldLevel.isBlank() ? "（未设置）" : oldLevel,
                            level));
                }
            } else {
                // 老客户端不传 dataLevel：新增列用项目默认等级（未配置为平台 L2），
                // 已有列保持原等级，避免误降级（coalesce 保留非空的存量值）。
                jdbc.update("""
                        insert into platform.managed_column
                            (table_id, column_name, data_type, sensitivity, mask_rule,
                             data_level, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (table_id, column_name) do update
                          set sensitivity = excluded.sensitivity,
                              mask_rule = excluded.mask_rule,
                              data_type = excluded.data_type,
                              data_level = coalesce(managed_column.data_level,
                                                    excluded.data_level),
                              updated_at = now(),
                              updated_by = excluded.updated_by
                        """, tableId, name, str(item.get("dataType")), sensitivity, mask,
                        defaultLevel, actor);
            }
        }
        // 列等级/规则变更后立即失效缓存，避免管理员改完 30 秒内还按旧规则输出
        columnRuleAt = 0L;
        return changes;
    }

    public void deleteTable(long id) {
        int n = jdbc.update("delete from platform.managed_table where id = ? and tenant = ?",
                id, TenantScope.current());
        if (n == 0) {
            throw ApiException.notFound("表不存在: " + id);
        }
    }

    // ---------- 查询网关 ----------

    /** 一次查询的判定结果；JSON 直接回给前端，便于解释「为什么被拦/被遮」。 */
    public record Guard(String mode, boolean enforced, boolean failClosed,
                        List<Map<String, Object>> tables,
                        List<String> wouldDeny, List<String> wouldMask,
                        boolean systemBlocked, List<String> crossTenantDenied) {
        public boolean denied() {
            return enforced && !failClosed && !wouldDeny.isEmpty();
        }

        public boolean blocked() {
            return enforced && failClosed;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", mode);
            out.put("enforced", enforced);
            out.put("parseFailed", failClosed);
            out.put("tables", tables);
            out.put("deniedTables", wouldDeny);
            out.put("maskedColumns", wouldMask);
            out.put("crossTenantTables", crossTenantDenied);
            return out;
        }
    }

    /**
     * 把「被拒 / 被脱敏」写进审计（v460 010 第 3 步）。
     * 被拒按 FAILED；脱敏按 SUCCESS，detail 里带表与列。
     */
    public void logDecision(String user, Guard guard, String ip, String channel) {
        try {
            if (OFF.equals(guard.mode())) {
                // 关闭权限控制时不做过滤/脱敏，但每次读仍要留痕
                sessions.audit(user, "DATA_GUARD_OFF_QUERY", channel,
                        "permission_mode=OFF：未做任何过滤与脱敏", "SUCCESS", ip);
                // D01 跨租户是硬挡：OFF 也必须留痕
                if (guard.crossTenantDenied() != null && !guard.crossTenantDenied().isEmpty()) {
                    sessions.audit(user, "DATA_GUARD_CROSS_TENANT_DENY",
                            String.join(",", guard.crossTenantDenied()),
                            "channel=" + channel + " mode=" + guard.mode()
                                    + " tenant=" + TenantScope.current(), "FAILED", ip);
                }
                return;
            }
            if (guard.crossTenantDenied() != null && !guard.crossTenantDenied().isEmpty()) {
                // D01：跨租户读取单独留痕，便于审计区分「缺授权」和「越租户」。
                sessions.audit(user, "DATA_GUARD_CROSS_TENANT_DENY",
                        String.join(",", guard.crossTenantDenied()),
                        "channel=" + channel + " mode=" + guard.mode()
                                + " tenant=" + TenantScope.current(), "FAILED", ip);
            }
            if (guard.denied()) {
                sessions.audit(user, "DATA_GUARD_DENY",
                        String.join(",", guard.wouldDeny()),
                        "channel=" + channel + " mode=" + guard.mode(), "FAILED", ip);
            }
            if (guard.enforced() && !guard.wouldMask().isEmpty()
                    && !rbac.permissions(user, TenantScope.current()).contains("column:unmask")) {
                // V100：审计里带来源 Catalog，湖表被脱敏时能直接看出是哪套湖
                String sources = guard.tables().stream()
                        .map(table -> qualified(str(table.get("catalog")),
                                str(table.get("schema")), str(table.get("table"))))
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .collect(java.util.stream.Collectors.joining(","));
                sessions.audit(user, "DATA_GUARD_MASK",
                        String.join(",", guard.wouldMask()),
                        "channel=" + channel + " mode=" + guard.mode()
                                + (sources.isBlank() ? "" : "; sources=" + sources),
                        "SUCCESS", ip);
            }
        } catch (RuntimeException e) {
            // S13：审计失败不能影响查询主流程，但也不能静默——「谁被拦了/谁被脱敏了」
            // 只有审计里知道，这条记录丢了就等于安全事件无痕，必须 error 级留痕（含堆栈）。
            log.error("数据分级网关审计写入失败 user={} channel={} mode={}: {}",
                    user, channel, guard.mode(), e);
        }
    }

    /** 是否是受保护的系统库（D04）。表管理/预览这类不走 SQL 网关的入口也要用它。 */
    public static boolean isSystemSchema(String schema) {
        return schema != null && SYSTEM_SCHEMAS.contains(schema.trim().toLowerCase(Locale.ROOT));
    }

    /** 当前用户能否读系统库：仅「默认租户 + system:manage」的平台管理员。 */
    public boolean systemSchemaReadable(String user) {
        return isPlatformAdmin(user);
    }

    /**
     * 平台管理员 = 默认租户 + {@code system:manage}（D01 口径）。
     *
     * <p>只有平台管理员可以跨租户读取；租户 ADMIN 即使带 {@code data:classify}
     * 也只能读本租户项目名下的库。</p>
     */
    public boolean isPlatformAdmin(String user) {
        String tenant = TenantScope.current();
        return TenantScope.DEFAULT.equalsIgnoreCase(tenant)
                && rbac.permissions(user, tenant).contains("system:manage");
    }

    /**
     * {@code data:classify} 的管理动作也要看目标库归属（D01）。
     *
     * <p>非平台管理员不能登记/操作其他租户的库。湖 Catalog 的租户归属由
     * {@code LakeCatalogService} 判定，这里只处理内表库名。</p>
     */
    public void requireSchemaManageable(String user, String catalog, String schema) {
        if (schema == null || schema.isBlank() || isPlatformAdmin(user)) {
            return;
        }
        if (catalog != null && !catalog.isBlank() && isLakeCatalog(catalog)) {
            return;
        }
        if (dataScope.belongsToOtherTenant(schema)) {
            throw new ApiException(403, "无权管理其他租户的库: " + schema);
        }
    }

    /** 供查询网关复用：当前用户能否跨租户（平台管理员才允许）。 */
    public boolean crossTenantBlocked(String user, String schema) {
        if (schema == null || schema.isBlank() || isPlatformAdmin(user)) {
            return false;
        }
        return dataScope.belongsToOtherTenant(schema);
    }

    /** 带 projectId 的版本：外部数仓不按本平台项目库名判归属。 */
    public boolean crossTenantBlocked(String user, String schema, Long projectId) {
        if (!usesBuiltinDoris(projectId)) {
            return false;
        }
        return crossTenantBlocked(user, schema);
    }

    /**
     * 本次查询是否落在平台内置的共享 Doris 上。
     *
     * <p>只有共享 Doris 的库名才由本平台项目声明归属；外部数仓的同名库不能按
     * {@code platform.project.dev_database} 判跨租户，否则会误伤项目级外部路由。</p>
     */
    private boolean usesBuiltinDoris(Long projectId) {
        if (projectId == null) {
            return true;
        }
        List<Boolean> rows = jdbc.queryForList("""
                select case
                         when p.warehouse_datasource_id is null
                              and p.doris_connection_id is null then true
                         when p.warehouse_datasource_id is null then false
                         else coalesce(d.config_json ->> 'builtin', 'false') = 'true'
                       end as builtin
                  from platform.project p
                  left join platform.datasource d on d.id = p.warehouse_datasource_id
                 where p.id = ?
                """, Boolean.class, projectId);
        return rows.isEmpty() || Boolean.TRUE.equals(rows.get(0));
    }

    public Guard inspect(String user, String sql) {
        return inspect(user, sql, null);
    }

    /** 按项目解析后的策略判定（projectId 为 null 时等价于平台全局配置）。 */
    public Guard inspect(String user, String sql, Long projectId) {
        String mode = guardMode(projectId);
        boolean enforce = ENFORCE.equals(mode);
        String tenant = TenantScope.current();
        Set<String> atoms = rbac.permissions(user, tenant);
        boolean classifier = atoms.contains("data:classify");
        boolean unmask = atoms.contains("column:unmask");
        // 平台管理员 = 默认租户 + system:manage（该权限点在拦截器里也只允许默认租户使用）
        boolean platformAdmin = isPlatformAdmin(user);

        Parsed parsed = parse(sql);
        // D04 的系统库保护是硬挡：即使项目把数据安全开关设成 OFF，也只允许平台管理员读。
        boolean systemBlocked = !platformAdmin && parsed.tables().stream().anyMatch(ref -> {
            String schema = ref.length > 2 ? ref[1] : ref[0];
            return schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT));
        });
        // D01：跨租户库归属也是硬挡，不受项目级 OFF 影响。只认「明确属于其他租户」，
        // 没有归属证据（外部数仓 / 未纳管共享库）保持原行为，避免误伤。
        // 只有平台内置的共享 Doris 才按本平台项目库名判归属；外部数仓的库名可能重名。
        boolean sharedDoris = usesBuiltinDoris(projectId);
        Map<String, Boolean> crossCache = new LinkedHashMap<>();
        List<String> crossTenant = parsed.parseFailed() || !sharedDoris ? List.of()
                : parsed.tables().stream()
                .filter(ref -> {
                    if (platformAdmin) {
                        return false;
                    }
                    String catalog = ref.length > 2 ? ref[0] : null;
                    String schema = ref.length > 2 ? ref[1] : ref[0];
                    if (schema == null || SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                        return false;
                    }
                    // 三段式湖表由 LakeCatalogService 按 Catalog 租户归属收口，不按内表库名判。
                    if (catalog != null && isLakeCatalog(catalog)) {
                        return false;
                    }
                    return crossCache.computeIfAbsent(schema,
                            key -> dataScope.belongsToOtherTenant(key));
                })
                .map(ref -> qualified(ref.length > 2 ? ref[0] : null,
                        ref.length > 2 ? ref[1] : ref[0],
                        ref.length > 2 ? ref[2] : ref[1]))
                .distinct()
                .toList();
        if (OFF.equals(mode)) {
            return new Guard(mode, false, false, List.of(), List.of(), List.of(),
                    systemBlocked, crossTenant);
        }
        if (parsed.parseFailed()) {
            return new Guard(mode, enforce, true, List.of(), List.of(), List.of(),
                    false, List.of());
        }
        List<Map<String, Object>> decisions = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        List<String> masks = new ArrayList<>();
        for (String[] ref : parsed.tables()) {
            String catalog = ref.length > 2 ? ref[0] : null;
            String schema = ref.length > 2 ? ref[1] : ref[0];
            String table = ref.length > 2 ? ref[2] : ref[1];
            if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                // D04：系统库不再是「跳过判定」，而是默认拒绝；只有平台管理员可读。
                boolean systemAllowed = platformAdmin;
                Map<String, Object> systemRow = new LinkedHashMap<>();
                systemRow.put("catalog", catalog);
                systemRow.put("schema", schema);
                systemRow.put("table", table);
                systemRow.put("level", "SYSTEM");
                systemRow.put("managed", false);
                systemRow.put("owner", false);
                systemRow.put("granted", false);
                systemRow.put("allowed", systemAllowed);
                systemRow.put("system", true);
                decisions.add(systemRow);
                if (!systemAllowed) {
                    denied.add(qualified(catalog, schema, table));
                }
                continue;
            }
            // D01：跨租户库一律拒绝，连 L1/L2 也不放行；classifier/授权只在本租户内生效。
            boolean cross = crossTenant.contains(qualified(catalog, schema, table));
            Map<String, Object> managed = findManaged(tenant, catalog, schema, table);
            String level = managed == null ? "L3" : str(managed.get("data_level"));
            boolean owner = managed != null && user.equals(str(managed.get("owner_user")));
            boolean granted = granted(user, tenant, catalog, schema, table);
            boolean allowed;
            if ("L1".equals(level) || "L2".equals(level)) {
                allowed = true;
            } else if ("L4".equals(level)) {
                allowed = owner || granted
                        || (classifier && unmask);
            } else {
                allowed = owner || granted || classifier;
            }
            if (cross) {
                allowed = false;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("catalog", catalog);
            row.put("schema", schema);
            row.put("table", table);
            row.put("level", level);
            row.put("managed", managed != null);
            row.put("owner", owner);
            row.put("granted", granted);
            row.put("crossTenant", cross);
            row.put("allowed", allowed);
            decisions.add(row);
            if (!allowed) {
                denied.add(qualified(catalog, schema, table));
            }
            if (managed != null && !unmask) {
                masks.addAll(maskedColumns(((Number) managed.get("id")).longValue()));
            }
        }
        List<String> wouldMask = masks.stream().distinct().toList();
        Guard guard = new Guard(mode, enforce, false, decisions,
                denied.stream().distinct().toList(), wouldMask, systemBlocked, crossTenant);
        // TC7-G6：列级脱敏需要把结果列回溯到源列。外层结果列枚举不出来的 SELECT
        // （Doris 方言、非 ASCII 裸别名、标量子查询等）判不出「敏感列从哪列出」，
        // 在 ENFORCE + 列级粒度 + 确实有待遮规则时按 fail-closed 拒绝执行。
        if (enforce && !unmask && PERMISSION_COLUMN.equals(granularity(projectId))
                && !effectiveRules(tenant, guard, user).isEmpty()) {
            ColumnLineage.Trace trace = ColumnLineage.trace(sql);
            if (trace.applicable() && !trace.parsed()) {
                return new Guard(mode, enforce, true, decisions,
                        guard.wouldDeny(), wouldMask, systemBlocked, crossTenant);
            }
        }
        return guard;
    }

    /** 按列名套脱敏；只有 ENFORCE 并且没有 column:unmask 时才会真正改值。 */
    public List<List<String>> mask(String user, Guard guard, List<String> columns,
                                   List<List<String>> rows) {
        return mask(user, guard, columns, rows, null, null);
    }

    /** 按项目解析后的粒度套脱敏（不传 sql = 旧口径，只按结果列名匹配）。 */
    public List<List<String>> mask(String user, Guard guard, List<String> columns,
                                   List<List<String>> rows, Long projectId) {
        return mask(user, guard, columns, rows, projectId, null);
    }

    /**
     * TC7-G6：带 SQL 的列级脱敏。结果列先按**回溯源列**（别名/表达式/子查询/CTE）
     * 判定，再回落结果列名；回溯不出来的 SELECT 有待遮规则时 400（fail-closed）。
     */
    public List<List<String>> mask(String user, Guard guard, List<String> columns,
                                   List<List<String>> rows, Long projectId, String sql) {
        if (!guard.enforced() || rows == null || rows.isEmpty()) {
            return rows;
        }
        // v482/v483：只有字段级模式才做列级掩码；TABLE / OFF 忽略列级规则
        if (!PERMISSION_COLUMN.equals(granularity(projectId))) {
            return rows;
        }
        String tenant = TenantScope.current();
        if (rbac.permissions(user, tenant).contains("column:unmask")) {
            return rows;
        }
        // 规则：MASK→***/NULL、TOKEN→稳定 HMAC、PARTIAL 沿用、PLAIN/NONE 不动。
        Map<String, Map<String, String>> rules = effectiveRules(tenant, guard, user);
        if (rules.isEmpty()) {
            return rows;
        }
        ColumnLineage.Trace trace = requireTrace(sql);
        List<Integer> indexes = new ArrayList<>();
        List<Map<String, String>> applied = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Map<String, String> rule = ruleFor(columns.get(i), trace, rules);
            if (rule != null) {
                indexes.add(i);
                applied.add(rule);
            }
        }
        if (indexes.isEmpty()) {
            return rows;
        }
        List<List<String>> masked = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> copy = new ArrayList<>(row);
            for (int k = 0; k < indexes.size(); k++) {
                int idx = indexes.get(k);
                if (idx < copy.size()) {
                    copy.set(idx, applyRule(copy.get(idx), applied.get(k), tenant));
                }
            }
            masked.add(copy);
        }
        return masked;
    }

    /**
     * 全租户列配置：列名（小写）→ {mask,type,sensitivity,schema,table,level,owner}。
     *
     * <p>只按结果列名匹配，不做 SQL 解析；同一列名出现在不同表时按表限定优先。
     * 30s 缓存只缓存「配置」；是否套规则还要看当前用户对表/列的授权，不能缓存。</p>
     */
    private Map<String, List<Map<String, String>>> columnRules(String tenant) {
        long now = System.currentTimeMillis();
        Map<String, List<Map<String, String>>> cached = columnRuleCache;
        if (cached != null && tenant.equals(columnRuleTenant)
                && now - columnRuleAt < COLUMN_RULE_TTL_MS) {
            return cached;
        }
        Map<String, List<Map<String, String>>> rules = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select c.column_name, c.sensitivity, c.mask_rule, c.data_type, c.data_level,
                       t.catalog, t.schema_name, t.table_name, t.owner_user
                from platform.managed_column c
                join platform.managed_table t on t.id = c.table_id
                where t.tenant = ?
                """, tenant)) {
            String name = str(row.get("column_name"));
            if (name.isBlank()) {
                continue;
            }
            String mask = str(row.get("mask_rule")).trim().toUpperCase(Locale.ROOT);
            String level = str(row.get("data_level")).trim().toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(level)) {
                // 等级异常/历史空值按 L3 处理，宁严勿松
                level = "L3";
            }
            Map<String, String> rule = Map.of(
                    "mask", mask,
                    "type", str(row.get("data_type")),
                    "sensitivity", str(row.get("sensitivity")).trim()
                            .toUpperCase(Locale.ROOT),
                    "catalog", str(row.get("catalog")),
                    "schema", str(row.get("schema_name")),
                    "table", str(row.get("table_name")),
                    "level", level,
                    "owner", str(row.get("owner_user")));
            rules.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(rule);
        }
        columnRuleCache = rules;
        columnRuleTenant = tenant;
        columnRuleAt = now;
        return rules;
    }

    /**
     * 本查询、本用户实际生效的列规则。
     *
     * <p>判定顺序：列等级 L1/L2 → 明文；L3/L4 → 表负责人 / TABLE 授权 / COLUMN 授权
     * （表+列都匹配）/ column:unmask → 明文；否则按 mask_rule，未配置时
     * L3 默认 TOKEN、L4 默认 MASK。</p>
     */
    private Map<String, Map<String, String>> effectiveRules(String tenant, Guard guard,
                                                            String user) {
        Map<String, List<Map<String, String>>> all = columnRules(tenant);
        if (all.isEmpty()) {
            return Map.of();
        }
        Set<String> tables = tableKeys(guard);
        List<Map<String, Object>> grants = grantsFor(user, tenant);
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, String>>> entry : all.entrySet()) {
            String column = entry.getKey();
            for (Map<String, String> config : entry.getValue()) {
                String table = str(config.get("table")).toLowerCase(Locale.ROOT);
                String schema = str(config.get("schema")).toLowerCase(Locale.ROOT);
                String catalog = str(config.get("catalog")).toLowerCase(Locale.ROOT);
                boolean hit = tables.isEmpty()
                        || tables.contains(table)
                        || tables.contains(schema + "." + table)
                        || (!catalog.isBlank()
                            && tables.contains(catalog + "." + schema + "." + table));
                if (!hit) {
                    continue;
                }
                String level = config.get("level");
                if ("L1".equals(level) || "L2".equals(level)) {
                    break;
                }
                boolean owner = user != null && user.equals(config.get("owner"));
                if (owner || authorized(grants, schema, table, column)) {
                    break;
                }
                String mask = effectiveMask(level, config.get("mask"));
                if (mask == null) {
                    break;
                }
                Map<String, String> rule = new LinkedHashMap<>();
                rule.put("rule", mask);
                rule.put("type", config.get("type"));
                rule.put("sensitivity", config.get("sensitivity"));
                rule.put("schema", config.get("schema"));
                rule.put("table", config.get("table"));
                rule.put("level", level);
                out.putIfAbsent(column, rule);
                break;
            }
        }
        return out;
    }

    /**
     * TC7-G6：结果列 → 生效规则。先按回溯源列名匹配（改名列在这里被兜住），
     * 再回落结果列名（{@code select *} 的标签就是物理列名）。sql 为 null 时
     * {@link ColumnLineage} 返回 NOT_APPLICABLE，等价旧「只按结果列名」口径。
     */
    static Map<String, String> ruleFor(String rawLabel, ColumnLineage.Trace trace,
                                       Map<String, Map<String, String>> rules) {
        String label = rawLabel == null ? "" : rawLabel.toLowerCase(Locale.ROOT);
        for (String origin : trace.originsOf(label)) {
            Map<String, String> rule = rules.get(origin);
            if (rule != null) {
                return rule;
            }
        }
        return rules.get(label);
    }

    /** TC7-G6：输出口径统一的回溯入口；SELECT 枚举不出结果列来源 → 400。 */
    private static ColumnLineage.Trace requireTrace(String sql) {
        ColumnLineage.Trace trace = ColumnLineage.trace(sql);
        if (trace.applicable() && !trace.parsed()) {
            throw ApiException.badRequest("无法安全确定各结果列的来源列（改名列/表达式可能绕过脱敏），"
                    + "已按列级脱敏策略拒绝；请改用显式列名与 ASCII 别名后重试");
        }
        return trace;
    }

    private static Set<String> tableKeys(Guard guard) {
        Set<String> tables = new LinkedHashSet<>();
        if (guard != null) {
            for (Map<String, Object> decision : guard.tables()) {
                String catalog = str(decision.get("catalog")).toLowerCase(Locale.ROOT);
                String schema = str(decision.get("schema")).toLowerCase(Locale.ROOT);
                String table = str(decision.get("table")).toLowerCase(Locale.ROOT);
                if (!table.isBlank()) {
                    tables.add(table);
                    if (!schema.isBlank()) {
                        tables.add(schema + "." + table);
                    }
                    if (!catalog.isBlank() && !schema.isBlank() && isLakeCatalog(catalog)) {
                        tables.add(catalog + "." + schema + "." + table);
                    }
                }
            }
        }
        return tables;
    }

    /** 当前用户在本租户内所有仍然有效的表/列授权。 */
    private List<Map<String, Object>> grantsFor(String user, String tenant) {
        if (user == null || user.isBlank()) {
            return List.of();
        }
        // 与 granted() 保持同一口径：历史 expires_at 为空按 decided_at + 90 天。
        return jdbc.queryForList("""
                select resource_type, schema_name, table_name, column_name
                from platform.access_request
                where requester = ? and status = 'APPROVED'
                  and resource_type in ('TABLE', 'COLUMN')
                  and coalesce(expires_at, decided_at + interval '90 days') > now()
                  and (requester_tenant = ? or requester_tenant is null)
                """, user, tenant);
    }

    private static boolean authorized(List<? extends Map<String, ?>> grants, String schema,
                                      String table, String column) {
        for (Map<String, ?> grant : grants) {
            if (permits(grant, schema, table, column)) {
                return true;
            }
        }
        return false;
    }

    /** TABLE 授权覆盖整表；COLUMN 授权只覆盖表+列都匹配的那一列。 */
    static boolean permits(Map<String, ?> grant, String schema, String table, String column) {
        String type = str(grant.get("resource_type")).trim().toUpperCase(Locale.ROOT);
        if (!lower(schema).equals(lower(str(grant.get("schema_name"))))) {
            return false;
        }
        if (!lower(table).equals(lower(str(grant.get("table_name"))))) {
            return false;
        }
        if ("TABLE".equals(type)) {
            return true;
        }
        if ("COLUMN".equals(type)) {
            String granted = lower(str(grant.get("column_name")));
            return !granted.isEmpty() && granted.equals(lower(column));
        }
        return false;
    }

    /**
     * 列等级 + 脱敏规则 → 实际规则；null 表示明文。
     * L1/L2 一律明文；L3/L4 显式 PLAIN 视为配置者明确要明文，未配置/ NONE
     * 分别回落 TOKEN / MASK。
     */
    static String effectiveMask(String level, String maskRule) {
        String lvl = str(level).trim().toUpperCase(Locale.ROOT);
        String mask = str(maskRule).trim().toUpperCase(Locale.ROOT);
        if ("L1".equals(lvl) || "L2".equals(lvl)) {
            return null;
        }
        if ("PLAIN".equals(mask)) {
            return null;
        }
        if (mask.isEmpty() || "NONE".equals(mask) || !MASKS.contains(mask)) {
            return "L4".equals(lvl) ? "MASK" : "TOKEN";
        }
        return mask;
    }

    /** 表级等级 = 列等级最大值；没有列时回落到 stored 值。 */
    static String deriveLevel(List<Map<String, Object>> columns, String storedLevel) {
        String best = null;
        if (columns != null) {
            for (Map<String, Object> column : columns) {
                String level = str(column.get("dataLevel")).trim().toUpperCase(Locale.ROOT);
                if (!LEVELS.contains(level)) {
                    continue;
                }
                if (best == null || levelRank(level) > levelRank(best)) {
                    best = level;
                }
            }
        }
        if (best != null) {
            return best;
        }
        String stored = str(storedLevel).trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(stored) ? stored : "L3";
    }

    private static int levelRank(String level) {
        return switch (level) {
            case "L1" -> 1;
            case "L2" -> 2;
            case "L3" -> 3;
            case "L4" -> 4;
            default -> 0;
        };
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 本响应实际会脱敏的列名（供前端提示与 maskedColumns）。 */
    public List<String> maskedColumnNames(String user, Guard guard, List<String> columns) {
        return maskedColumnNames(user, guard, columns, null, null);
    }

    /** 按项目解析后的粒度计算会被脱敏的列（不传 sql = 旧口径，只按结果列名）。 */
    public List<String> maskedColumnNames(String user, Guard guard, List<String> columns,
                                          Long projectId) {
        return maskedColumnNames(user, guard, columns, projectId, null);
    }

    /** TC7-G6：带 SQL 时按回溯源列判定（改名列也计入提示）。 */
    public List<String> maskedColumnNames(String user, Guard guard, List<String> columns,
                                          Long projectId, String sql) {
        if (!guard.enforced() || columns == null || columns.isEmpty()
                || !PERMISSION_COLUMN.equals(granularity(projectId))) {
            return List.of();
        }
        String tenant = TenantScope.current();
        if (rbac.permissions(user, tenant).contains("column:unmask")) {
            return List.of();
        }
        Map<String, Map<String, String>> rules = effectiveRules(tenant, guard, user);
        if (rules.isEmpty()) {
            return List.of();
        }
        ColumnLineage.Trace trace = requireTrace(sql);
        List<String> out = new ArrayList<>();
        for (String column : columns) {
            if (column != null && ruleFor(column, trace, rules) != null) {
                out.add(column);
            }
        }
        return out;
    }

    /** 本响应中走 TOKEN/HASH 的列（与 maskedColumns 区分：令牌可参与分组/关联）。 */
    public List<String> tokenizedColumnNames(String user, Guard guard, List<String> columns) {
        return tokenizedColumnNames(user, guard, columns, null, null);
    }

    /** 按项目解析后的粒度计算令牌列（不传 sql = 旧口径，只按结果列名）。 */
    public List<String> tokenizedColumnNames(String user, Guard guard, List<String> columns,
                                             Long projectId) {
        return tokenizedColumnNames(user, guard, columns, projectId, null);
    }

    /** TC7-G6：带 SQL 时按回溯源列判定令牌列。 */
    public List<String> tokenizedColumnNames(String user, Guard guard, List<String> columns,
                                             Long projectId, String sql) {
        if (!guard.enforced() || columns == null || columns.isEmpty()
                || !PERMISSION_COLUMN.equals(granularity(projectId))) {
            return List.of();
        }
        String tenant = TenantScope.current();
        if (rbac.permissions(user, tenant).contains("column:unmask")) {
            return List.of();
        }
        Map<String, Map<String, String>> rules = effectiveRules(tenant, guard, user);
        if (rules.isEmpty()) {
            return List.of();
        }
        ColumnLineage.Trace trace = requireTrace(sql);
        List<String> out = new ArrayList<>();
        for (String column : columns) {
            if (column == null) {
                continue;
            }
            Map<String, String> rule = ruleFor(column, trace, rules);
            if (rule != null) {
                String kind = rule.get("rule");
                if ("TOKEN".equals(kind) || "HASH".equals(kind)) {
                    out.add(column);
                }
            }
        }
        return out;
    }

    /** 数值/日期列在 MASK 规则下返回 NULL，而不是字符串 ***。 */
    private static boolean numericLike(String type) {
        if (type == null) {
            return false;
        }
        String t = type.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("tinyint") || t.startsWith("smallint") || t.startsWith("int")
                || t.startsWith("bigint") || t.startsWith("largeint") || t.startsWith("decimal")
                || t.startsWith("float") || t.startsWith("double") || t.startsWith("date")
                || t.startsWith("datetime") || t.startsWith("timestamp") || t.startsWith("time")
                || t.startsWith("boolean") || t.startsWith("bit");
    }

    private String applyRule(String value, Map<String, String> rule, String tenant) {
        if (value == null) {
            return null;
        }
        String mask = rule.get("rule");
        String type = rule.get("type");
        return switch (mask) {
            case "TOKEN", "HASH" -> token(tenant, value);
            case "MASK", "FULL" -> numericLike(type) ? null : "***";
            case "PARTIAL" -> numericLike(type) ? null : partial(value, rule.get("sensitivity"));
            case "NULL" -> null;
            default -> value;
        };
    }

    /** 稳定令牌：hmac_sha256(租户 salt, 值) 的前 16 位十六进制。同值同 token。 */
    private String token(String tenant, String value) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    tenantSalt(tenant).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            return "***";
        }
    }

    /** 租户 salt：存 platform.setting（mask.salt.租户），首次使用时生成。 */
    private String tenantSalt(String tenant) {
        String cached = tenantSalts.get(tenant);
        if (cached != null) {
            return cached;
        }
        String key = "mask.salt." + tenant;
        List<String> values = jdbc.queryForList(
                "select setting_value from platform.setting where setting_key = ?", String.class, key);
        String salt = values.isEmpty() ? null : values.get(0);
        if (salt == null || salt.isBlank()) {
            byte[] raw = new byte[32];
            new java.security.SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            salt = sb.toString();
            try {
                jdbc.update("""
                        insert into platform.setting (setting_key, setting_value, updated_by)
                        values (?, ?, 'system') on conflict (setting_key) do nothing
                        """, key, salt);
            } catch (RuntimeException ignored) {
                // 并发首次生成：以库里已有值为准
            }
            values = jdbc.queryForList(
                    "select setting_value from platform.setting where setting_key = ?", String.class, key);
            salt = values.isEmpty() ? salt : values.get(0);
        }
        tenantSalts.put(tenant, salt);
        return salt;
    }

    /**
     * 给「按列名组织成 Map 的结果」套脱敏（数据服务返回的是 Map 行）。
     * {@link #mask} 把整行转成字符串列表，会改变数字类型，数据服务不能这么干。
     */
    public List<Map<String, Object>> maskMaps(String user, Guard guard, List<String> columns,
                                              List<Map<String, Object>> rows) {
        return maskMaps(user, guard, columns, rows, null);
    }

    /** 按项目解析后的粒度套脱敏（数据服务/BI 出口，不传 sql = 旧口径）。 */
    public List<Map<String, Object>> maskMaps(String user, Guard guard, List<String> columns,
                                              List<Map<String, Object>> rows, Long projectId) {
        return maskMaps(user, guard, columns, rows, projectId, null);
    }

    /**
     * TC7-G6：数据服务/BI 出口带 SQL 时同样按回溯源列判定。
     *
     * <p>注意：map 的行按**标签**取值，改名列在 map 里的 key 是别名；脱敏后写回
     * 同一个 key，不会新增/改名列。</p>
     */
    public List<Map<String, Object>> maskMaps(String user, Guard guard, List<String> columns,
                                              List<Map<String, Object>> rows, Long projectId,
                                              String sql) {
        if (!guard.enforced() || rows == null || rows.isEmpty()
                || !PERMISSION_COLUMN.equals(granularity(projectId))) {
            return rows;
        }
        String tenant = TenantScope.current();
        if (rbac.permissions(user, tenant).contains("column:unmask")) {
            return rows;
        }
        Map<String, Map<String, String>> rules = effectiveRules(tenant, guard, user);
        if (rules.isEmpty()) {
            return rows;
        }
        ColumnLineage.Trace trace = requireTrace(sql);
        List<String> hit = new ArrayList<>();
        List<Map<String, String>> applied = new ArrayList<>();
        for (String column : columns) {
            Map<String, String> rule = column == null ? null : ruleFor(column, trace, rules);
            if (rule != null) {
                hit.add(column);
                applied.add(rule);
            }
        }
        if (hit.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (int i = 0; i < hit.size(); i++) {
                Object value = copy.get(hit.get(i));
                copy.put(hit.get(i), applyRule(value == null ? null : String.valueOf(value),
                        applied.get(i), tenant));
            }
            out.add(copy);
        }
        return out;
    }

    private List<String> maskedColumns(long tableId) {
        // 只用于 inspect 的解释性输出：L3/L4 且不是显式 PLAIN 的列都算「会被遮」。
        return jdbc.queryForList("""
                select column_name from platform.managed_column
                where table_id = ? and data_level in ('L3', 'L4')
                  and coalesce(mask_rule, '') <> 'PLAIN'
                order by column_name
                """, String.class, tableId);
    }

    private Map<String, Object> findManaged(String tenant, String catalog, String schema,
                                            String table) {
        List<Map<String, Object>> rows;
        // 表级拦截只看 stored 等级（存量语义）；列级等级只用于输出脱敏与展示。
        // 否则一张 stored=L2 的表只要有一列 L4，整表就会被 403，列级授权/掩码永远走不到。
        String derived = "t.data_level as data_level";
        boolean lake = isLakeCatalog(catalog);
        if (lake && schema != null && !schema.isBlank()) {
            // V100：湖表按 catalog + schema + table 精确匹配，避免和同名内表串味
            rows = jdbc.queryForList("select t.id, " + derived
                    + ", t.owner_user from platform.managed_table t"
                    + " where t.tenant = ? and lower(t.catalog) = lower(?)"
                    + " and t.schema_name = ? and t.table_name = ? order by t.id limit 1",
                    tenant, catalog, schema, table);
        } else if (lake) {
            rows = jdbc.queryForList("select t.id, " + derived
                    + ", t.owner_user from platform.managed_table t"
                    + " where t.tenant = ? and lower(t.catalog) = lower(?)"
                    + " and t.table_name = ? order by t.id limit 1",
                    tenant, catalog, table);
        } else if (schema == null || schema.isBlank()) {
            rows = jdbc.queryForList("select t.id, " + derived
                    + ", t.owner_user from platform.managed_table t"
                    + " where t.tenant = ? and t.table_name = ? order by t.id limit 1",
                    tenant, table);
        } else {
            rows = jdbc.queryForList("select t.id, " + derived
                    + ", t.owner_user from platform.managed_table t"
                    + " where t.tenant = ? and t.schema_name = ? and t.table_name = ?"
                    + " order by t.id limit 1", tenant, schema, table);
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean granted(String user, String tenant, String catalog, String schema,
                            String table) {
        // v460 F-P0-2：授权必须同时满足「资源属于当前租户」。否则 A 租户审批人放行的
        // 跨租户授权会在这里对 B 租户资源生效（findManaged 查不到按 L3 处理，却被 granted 放走）。
        // F-P2-3：历史 expires_at 为空按 decided_at + 90 天，不再永不过期。
        boolean lake = isLakeCatalog(catalog);
        boolean owned = findManaged(tenant, catalog, schema, table) != null;
        if (!owned && schema != null) {
            Integer any = jdbc.queryForObject("""
                    select count(*) from platform.managed_table
                    where tenant = ? and schema_name = ?
                    """, Integer.class, tenant, schema);
            owned = any != null && any > 0;
        }
        if (!owned) {
            return false;
        }
        List<Map<String, Object>> grants = jdbc.queryForList("""
                select resource_type, catalog, schema_name, table_name
                from platform.access_request
                where requester = ? and status = 'APPROVED'
                  and resource_type in ('DATABASE', 'TABLE')
                  and coalesce(expires_at, decided_at + interval '90 days') > now()
                  and (requester_tenant = ? or requester_tenant is null)
                """, user, tenant);
        for (Map<String, Object> grant : grants) {
            // V100：湖表授权必须来源同一个 Catalog；内表忽略历史里写的 Doris/PG。
            String grantCatalog = str(grant.get("catalog"));
            if (lake) {
                if (!catalog.equalsIgnoreCase(grantCatalog)) {
                    continue;
                }
            } else if (!grantCatalog.isBlank() && !isInternalCatalog(grantCatalog)) {
                continue;
            }
            // v460 F-P2-6 观察项：与纳管表匹配保持同一口径（精确匹），大小写不同
            // 的表不能被已授权表「覆盖」，否则未纳管的同名表会被过度授权。
            if (schema != null && !schema.equals(str(grant.get("schema_name")))) {
                continue;
            }
            if ("DATABASE".equals(str(grant.get("resource_type")))) {
                return true;
            }
            if ("COLUMN".equals(str(grant.get("resource_type")))) {
                // 列级授权只放行那一列，不能用来查询整表
                continue;
            }
            if (table.equals(str(grant.get("table_name")))) {
                return true;
            }
        }
        return false;
    }

    /** 外部 Catalog（Iceberg 等）；internal / doris 视为内表。 */
    private static boolean isLakeCatalog(String catalog) {
        return catalog != null && !catalog.isBlank() && !isInternalCatalog(catalog);
    }

    private static boolean isInternalCatalog(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return true;
        }
        return "internal".equalsIgnoreCase(catalog) || "doris".equalsIgnoreCase(catalog)
                || "pg".equalsIgnoreCase(catalog) || "postgres".equalsIgnoreCase(catalog);
    }

    private static String qualified(String catalog, String schema, String table) {
        StringBuilder out = new StringBuilder();
        if (catalog != null && !catalog.isBlank() && !isInternalCatalog(catalog)) {
            out.append(catalog).append('.');
        }
        if (schema != null && !schema.isBlank()) {
            out.append(schema).append('.');
        }
        return out.append(table).toString();
    }

    // ---------- SQL 解析（务实版：解析失败一律拒绝） ----------

    public record Parsed(List<String[]> tables, boolean parseFailed) {
    }

    static Parsed parse(String sql) {
        String text = COMMENTS.matcher(sql == null ? "" : sql).replaceAll(" ");
        text = LITERALS.matcher(text).replaceAll("''");
        Set<String> ctes = new LinkedHashSet<>();
        Matcher cte = CTE.matcher(text);
        while (cte.find()) {
            ctes.add(cte.group(1).toLowerCase(Locale.ROOT));
        }
        boolean sawFrom = false;
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> out = new ArrayList<>();
        Matcher matcher = TABLE_KEYWORD.matcher(text);
        while (matcher.find()) {
            sawFrom = true;
            // 只向后看有限长度，避免把整条 SQL 当表名；也保证后续 from/join 还能匹配到
            int limit = Math.min(text.length(), matcher.end() + 500);
            String head = text.substring(matcher.end(), limit).stripLeading();
            if (head.startsWith("(")) {
                continue;
            }
            // 只取到下一个子句关键字为止；中间可能有多张表（逗号分隔）
            Matcher clause = CLAUSE.matcher(head);
            int end = clause.find() ? clause.start() : head.length();
            for (String part : splitTopLevel(head.substring(0, end))) {
                String candidate = part.trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                Matcher ident = IDENT.matcher(candidate);
                if (!ident.find()) {
                    continue;
                }
                String first = ident.group(1);
                String second = ident.group(2);
                String third = ident.group(3);
                if (first == null) {
                    continue;
                }
                String catalog;
                String schema;
                String table;
                if (third != null) {
                    // V100：catalog.schema.table（Iceberg 湖表）
                    catalog = first;
                    schema = second;
                    table = third;
                } else if (second != null) {
                    catalog = null;
                    schema = first;
                    table = second;
                } else {
                    catalog = null;
                    schema = null;
                    table = first;
                }
                if (ctes.contains(table.toLowerCase(Locale.ROOT)) && schema == null) {
                    continue;
                }
                String key = (catalog == null ? "" : catalog.toLowerCase(Locale.ROOT) + ".")
                        + (schema == null ? "" : schema.toLowerCase(Locale.ROOT) + ".")
                        + table.toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    out.add(new String[]{catalog, schema, table});
                }
            }
        }
        // 出现 from/join 却一张表都没解析出来 → fail-closed（宁可不给查）
        return new Parsed(out, sawFrom && out.isEmpty());
    }

    /** 按顶层逗号切分（括号/引号内的逗号不算）。 */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (char ch : text.toCharArray()) {
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        parts.add(current.toString());
        return parts;
    }

    // ---------- 脱敏规则 ----------

    static String apply(String value, Map<String, String> rule) {
        if (value == null) {
            return null;
        }
        String mask = rule.get("rule");
        String sensitivity = rule.get("sensitivity");
        switch (mask) {
            case "NULL" -> {
                return null;
            }
            case "FULL" -> {
                return "***";
            }
            case "HASH" -> {
                return hash(value);
            }
            case "PARTIAL" -> {
                return partial(value, sensitivity);
            }
            default -> {
                return value;
            }
        }
    }

    private static String partial(String value, String sensitivity) {
        if (value.isEmpty()) {
            return value;
        }
        return switch (sensitivity) {
            case "PHONE" -> value.length() >= 7
                    ? value.substring(0, 3) + "****" + value.substring(value.length() - 4)
                    : "***";
            case "ID_CARD" -> value.length() >= 10
                    ? value.substring(0, 6) + "********" + value.substring(value.length() - 4)
                    : "***";
            case "EMAIL" -> {
                int at = value.indexOf('@');
                yield at > 0
                        ? value.charAt(0) + "***" + value.substring(at)
                        : "***";
            }
            case "NAME" -> value.charAt(0) + "*";
            default -> {
                if (value.length() <= 2) {
                    yield "*";
                }
                if (value.length() <= 6) {
                    yield value.charAt(0) + "***";
                }
                yield value.substring(0, 3) + "****" + value.substring(value.length() - 4);
            }
        };
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex + "…";
        } catch (Exception e) {
            return "***";
        }
    }

    private static String normalizeLevel(String level) {
        String value = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(value)) {
            throw ApiException.badRequest("dataLevel 只能是 L1 / L2 / L3 / L4");
        }
        return value;
    }

    /** 列级 dataLevel：允许不传（返回 null，保留原等级）。 */
    static String levelOrNull(Object raw) {
        String value = str(raw).trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (!LEVELS.contains(value)) {
            throw ApiException.badRequest("dataLevel 只能是 L1 / L2 / L3 / L4");
        }
        return value;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 供测试/排障用：把解析结果暴露出来（不对外开接口）。支持 catalog.schema.table。 */
    public List<String> parsedTables(String sql) {
        return parse(sql).tables().stream().map(ref -> {
            StringBuilder out = new StringBuilder();
            if (ref.length > 2 && ref[0] != null && !ref[0].isBlank()) {
                out.append(ref[0]).append('.');
            }
            String schema = ref.length > 2 ? ref[1] : ref[0];
            String table = ref.length > 2 ? ref[2] : ref[1];
            if (schema != null && !schema.isBlank()) {
                out.append(schema).append('.');
            }
            return out.append(table).toString();
        }).toList();
    }
}
