package com.lab.dataworks.asset;

import com.lab.dataworks.asset.AssetFolderRules.Grant;
import com.lab.dataworks.asset.AssetFolderRules.Principal;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RbacService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多模态资产目录（V110）：目录树、新建/改名/移动/删除（仅空目录）、目录授权。
 *
 * 起按两层口径执行）：</p>
 * <ul>
 *   <li><b>目录层不拦</b>：目录树/资产列表（名称、类型、大小、归属）对本租户全员下发。</li>
 *   <li><b>内容层拦</b>：目录读授权现在约束「内容」——预签名下载、文件本体、
 *       详情敏感字段（uri/sha256/url）；目录没有授权行 = 对本租户默认开放内容，
 *       有授权行 = 仅匹配主体可取内容。</li>
 *   <li>授权只作用于本目录，不向下继承；但内容读取要求「自身及全部祖先」都可读，
 *       所以关闭父目录会连带断掉整棵子树的<b>内容</b>路径（目录本身仍然可见）。</li>
 *   <li>目录创建者与管理侧权限点持有者（system:manage / tenant:manage / user:manage）
 *       始终保留该目录的读/写/管理权（防锁死，判定在 {@link AssetFolderRules}）。</li>
 * </ul>
 *
 * <p>路径 {@code path} 物化（{@code /a}、{@code /a/b}），改名/移动用前缀替换一次更新整棵子树，
 * 与 {@code UdfFolderService} 同一套路。租户隔离：所有读写都带 {@code tenant_id} 条件。</p>
 */
@Service
public class AssetFolderService {

    /** 单目录授权主体上限：列表弹窗够用，也防止把 PUT 变成灌库接口。 */
    public static final int MAX_GRANTS = 100;

    private final JdbcTemplate jdbc;
    private final RbacService rbac;

    public AssetFolderService(JdbcTemplate jdbc, RbacService rbac) {
        this.jdbc = jdbc;
        this.rbac = rbac;
    }

    // ---------------------------------------------------------------- 身份

    /** 从请求属性组装判定主体：用户名 + 本租户权限点 + 本租户角色编码。 */
    @SuppressWarnings("unchecked")
    public Principal principal(HttpServletRequest http) {
        Object name = http == null ? null : http.getAttribute("username");
        String username = name == null ? "unknown" : String.valueOf(name);
        Object atoms = http == null ? null : http.getAttribute(AuthInterceptor.ATOMS_ATTR);
        Set<String> atomSet = atoms instanceof Set<?> s
                ? (Set<String>) s : Set.<String>of();
        Set<String> roles = new LinkedHashSet<>(rbac.roles(username, TenantScope.current()));
        return new Principal(username, roles, atomSet);
    }

    // ---------------------------------------------------------------- 快照（每请求一份）

    /** 目录 + 授权 + 目录内资产数的请求内快照：内容层 ACL 判定与写判定共用一次查询。 */
    public static final class Snapshot {
        final Map<Long, Folder> folders;
        final Map<Long, List<Grant>> grants;
        final Map<Long, Integer> directCounts;
        final int rootCount;

        Snapshot(Map<Long, Folder> folders, Map<Long, List<Grant>> grants,
                 Map<Long, Integer> directCounts, int rootCount) {
            this.folders = folders;
            this.grants = grants;
            this.directCounts = directCounts;
            this.rootCount = rootCount;
        }

        Folder folder(long id) {
            return folders.get(id);
        }

        /** 目录是否存在于本租户快照（跨租户 id 与不存在一律 false → 404）。 */
        boolean contains(long id) {
            return folders.containsKey(id);
        }

        /** 祖先链（含自身），从深到浅；脏数据成环时走满 MAX_DEPTH 直接判定非法。 */
        List<Folder> chain(long folderId) {
            List<Folder> chain = new ArrayList<>();
            Folder cur = folders.get(folderId);
            if (cur == null) {
                throw ApiException.notFound("目录不存在: " + folderId);
            }
            Set<Long> seen = new HashSet<>();
            while (cur != null) {
                if (!seen.add(cur.id())) {
                    throw new ApiException(500, "目录父链成环（数据异常），请联系管理员: " + folderId);
                }
                chain.add(cur);
                if (chain.size() > AssetFolderRules.maxDepth()) {
                    throw new ApiException(500, "目录层级过深（数据异常）: " + folderId);
                }
                cur = cur.parentId() == null ? null : folders.get(cur.parentId());
            }
            return chain;
        }

        private boolean selfReadable(Folder f, Principal p) {
            return AssetFolderRules.folderReadable(
                    grants.getOrDefault(f.id(), List.of()), p,
                    p.username().equalsIgnoreCase(f.createdBy()));
        }

        private boolean selfWritable(Folder f, Principal p) {
            return AssetFolderRules.folderWritable(
                    grants.getOrDefault(f.id(), List.of()), p,
                    p.username().equalsIgnoreCase(f.createdBy()));
        }

        /** 内容读取/取预览：自身与全部祖先都可读（无授权=开放）。
         * S11 评审确认后该判定只作用于**内容层**（预签名/文件本体/详情敏感字段）与写操作，
         * 不再决定目录/列表是否可见（租户内全员可见）。 */
        boolean readable(long folderId, Principal p) {
            return chain(folderId).stream().allMatch(f -> selfReadable(f, p));
        }

        /** 上传/移入/删除目录内资产：路径可读 + 本目录可写。 */
        boolean writable(long folderId, Principal p) {
            return readable(folderId, p) && selfWritable(folders.get(folderId), p);
        }

        /** 管理目录本身（改名/移动/删除/配授权）：本目录可写即可，不要求路径可见，防锁死。 */
        boolean manageable(long folderId, Principal p) {
            return selfWritable(folders.get(folderId), p);
        }
    }

    /** 目录元数据（判定用精简视图）。 */
    record Folder(long id, Long parentId, String name, String path, String createdBy) {
    }

    /** 加载当前租户的全部目录与授权（DEV 规模下单请求一次，够用且判定一致）。 */
    public Snapshot load(Principal p) {
        String tenant = TenantScope.current();
        Map<Long, Folder> folders = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select id, parent_id, name, path, created_by
                  from platform.asset_folder
                 where tenant_id = ?
                 order by path
                """, tenant)) {
            folders.put(num(row.get("id")), new Folder(
                    num(row.get("id")),
                    row.get("parent_id") == null ? null : num(row.get("parent_id")),
                    String.valueOf(row.get("name")),
                    String.valueOf(row.get("path")),
                    row.get("created_by") == null ? null : String.valueOf(row.get("created_by"))));
        }
        Map<Long, List<Grant>> grants = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select folder_id, subject_type, subject_id, can_read, can_write
                  from platform.asset_folder_grant
                 where tenant_id = ?
                 order by id
                """, tenant)) {
            grants.computeIfAbsent(num(row.get("folder_id")), k -> new ArrayList<>())
                    .add(new Grant(String.valueOf(row.get("subject_type")),
                            String.valueOf(row.get("subject_id")),
                            Boolean.TRUE.equals(row.get("can_read")),
                            Boolean.TRUE.equals(row.get("can_write"))));
        }
        Map<Long, Integer> directCounts = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select folder_id, count(*) as cnt
                  from platform.asset
                 where tenant_id = ? and status = 'ACTIVE' and folder_id is not null
                 group by folder_id
                """, tenant)) {
            directCounts.put(num(row.get("folder_id")), ((Number) row.get("cnt")).intValue());
        }
        Integer root = jdbc.queryForObject("""
                select count(*) from platform.asset
                 where tenant_id = ? and status = 'ACTIVE' and folder_id is null
                """, Integer.class, tenant);
        return new Snapshot(folders, grants, directCounts, root == null ? 0 : root);
    }

    // ---------------------------------------------------------------- 403 断言

    /** 目录不存在/跨租户 → 404；存在但内容层不可读（自身或祖先被关闭）→ 403。 */
    public void requireReadable(Snapshot snap, long folderId, Principal p) {
        if (!snap.contains(folderId)) {
            throw ApiException.notFound("目录不存在: " + folderId);
        }
        if (!snap.readable(folderId, p)) {
            throw new ApiException(403, "无权读写目录「" + snap.folder(folderId).path()
                    + "」的内容：该目录已配置授权且你没有读权限，请提交权限申请");
        }
    }

    /** 目录不存在/跨租户 → 404；不可读 → 403；可读但不可写 → 403。 */
    public void requireWritable(Snapshot snap, long folderId, Principal p) {
        requireReadable(snap, folderId, p);
        if (!snap.writable(folderId, p)) {
            throw new ApiException(403, "目录「" + snap.folder(folderId).path()
                    + "」为只读：上传/移动/删除需要该目录的写权限");
        }
    }

    private void requireManageable(Snapshot snap, long folderId, Principal p) {
        if (!snap.contains(folderId)) {
            throw ApiException.notFound("目录不存在: " + folderId);
        }
        if (!snap.manageable(folderId, p)) {
            throw new ApiException(403, "你不是该目录的创建者，也没有该目录的写权限，不能管理目录");
        }
    }

    // ---------------------------------------------------------------- 树

    /**
     * 目录树（S11 两层口径·目录层）：本租户全部目录对全员下发，不再按读权限隐藏；
     * {@code canRead} 表示**内容层**是否有读权（预签名/文件本体/详情敏感字段），
     * 前端据此显示锁标志并在预览时提示「提交权限申请」。跨租户目录依旧不可见
     * （SQL 带 {@code tenant_id} 条件）。
     */
    public Map<String, Object> tree(Principal p) {
        String tenant = TenantScope.current();
        Snapshot snap = load(p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select id, parent_id as "parentId", name, path,
                       created_by as "createdBy", created_at as "createdAt"
                  from platform.asset_folder
                 where tenant_id = ?
                 order by path
                """, tenant)) {
            long id = num(row.get("id"));
            row.put("directCount", snap.directCounts.getOrDefault(id, 0));
            row.put("hasGrants", !snap.grants.getOrDefault(id, List.of()).isEmpty());
            Folder f = snap.folder(id);
            row.put("canRead", snap.readable(id, p)); // 内容层判定，不再影响目录可见性
            row.put("canWrite", snap.writable(id, p));
            row.put("canManage", snap.manageable(id, p));
            row.put("mine", f != null && p.username().equalsIgnoreCase(f.createdBy()));
            out.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("folders", out);
        body.put("rootCount", snap.rootCount);
        body.put("rootWritable", true); // 虚拟根「我的上传」不做目录级授权，仅 asset:write 权限点控制
        return body;
    }

    // ---------------------------------------------------------------- 写操作

    @Transactional
    public Map<String, Object> create(Long parentId, String rawName, Principal p) {
        Snapshot snap = load(p);
        String name = AssetFolderRules.normalizeName(rawName);
        Folder parent = null;
        if (parentId != null && parentId != 0L) {
            requireWritable(snap, parentId, p); // 新子目录必须建在「可读且可写」的目录下
            parent = snap.folder(parentId);
        }
        String path = AssetFolderRules.childPath(parent == null ? null : parent.path(), name);
        requireNoSibling(parent == null ? null : parent.id(), name, -1L);
        try {
            Long id = jdbc.queryForObject("""
                    insert into platform.asset_folder
                        (tenant_id, parent_id, name, path, created_by)
                    values (?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, TenantScope.current(),
                    parent == null ? null : parent.id(), name, path, p.username());
            return detail(id == null ? -1L : id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一目录下已有同名目录: " + name);
        }
    }

    /**
     * 改名与移动合并的 PUT：
     * {@code rawName} 非空才改名；{@code parentProvided} 为真才移动（{@code parentId} null/0=虚拟根）。
     */
    @Transactional
    public Map<String, Object> update(long id, String rawName, Long parentId,
                                      boolean parentProvided, Principal p) {
        Snapshot snap = load(p);
        if (!snap.contains(id)) {
            throw ApiException.notFound("目录不存在: " + id);
        }
        requireManageable(snap, id, p);
        Folder cur = snap.folder(id);
        String name = cur.name();
        if (rawName != null && !rawName.isBlank()) {
            name = AssetFolderRules.normalizeName(rawName);
        }
        Long targetParent = cur.parentId();
        boolean moving = parentProvided && !sameParent(cur.parentId(), parentId);
        if (moving) {
            targetParent = normalizeParentId(parentId);
        }
        if (!name.equals(cur.name()) || moving) {
            requireNoSibling(moving ? targetParent : cur.parentId(), name, id);
        }
        try {
            applyRename(snap, cur, name);
            if (moving) {
                applyMove(p, id, targetParent);
            }
        } catch (DuplicateKeyException e) {
            // 并发改名/移动撞了同名唯一索引或 path 唯一索引
            throw ApiException.conflict("同一目录下已有同名目录: " + name);
        }
        return detail(id);
    }

    private void applyRename(Snapshot snap, Folder cur, String name) {
        if (name.equals(cur.name())) {
            return;
        }
        String oldPath = cur.path();
        Folder sameParentFolder = cur.parentId() == null ? null : snap.folder(cur.parentId());
        String parentPath = sameParentFolder == null ? null : sameParentFolder.path();
        String newPath = AssetFolderRules.childPath(parentPath, name);
        jdbc.update("update platform.asset_folder set name = ? where id = ? and tenant_id = ?",
                name, cur.id(), TenantScope.current());
        updateSubtreePath(oldPath, newPath);
    }

    private void applyMove(Principal p, long id, Long targetParent) {
        if (targetParent != null) {
            requireWritable(load(p), targetParent, p); // 移入可读可写的目录
        }
        Snapshot snap = load(p); // 改名后 path 变了，重新取快照再算移动
        Folder moved = snap.folder(id);
        if (moved == null) {
            throw ApiException.notFound("目录不存在: " + id);
        }
        String oldPath = moved.path();
        Folder newParent = targetParent == null ? null : snap.folder(targetParent);
        if (newParent == null && targetParent != null) {
            throw ApiException.notFound("目标目录不存在: " + targetParent);
        }
        AssetFolderRules.checkMoveTarget(id, targetParent, oldPath,
                newParent == null ? null : newParent.path());
        String newPath = AssetFolderRules.childPath(newParent == null ? null : newParent.path(),
                moved.name());
        jdbc.update("""
                update platform.asset_folder set parent_id = ?, path = ?
                 where id = ? and tenant_id = ?
                """, targetParent, newPath, id, TenantScope.current());
        updateSubtreePath(oldPath, newPath);
    }

    /** 删除：仅空目录（无子目录、无 ACTIVE 资产），需管理权。 */
    @Transactional
    public Map<String, Object> delete(long id, Principal p) {
        Snapshot snap = load(p);
        if (!snap.contains(id)) {
            throw ApiException.notFound("目录不存在: " + id);
        }
        requireManageable(snap, id, p);
        String tenant = TenantScope.current();
        int children = jdbc.queryForObject("""
                select count(*) from platform.asset_folder where tenant_id = ? and parent_id = ?
                """, Integer.class, tenant, id);
        int assets = jdbc.queryForObject("""
                select count(*) from platform.asset
                 where tenant_id = ? and folder_id = ? and status = 'ACTIVE'
                """, Integer.class, tenant, id);
        if (children > 0 || assets > 0) {
            throw ApiException.conflict("目录非空：" + children + " 个子目录、" + assets
                    + " 个资产；请先移走后再删除（只允许删除空目录）");
        }
        // 软删资产（status='DELETED'）不在上面的「非空」判定里，但 V110 的外键
        // `asset.folder_id → asset_folder.id on delete restrict` 照样拦它：
        // 把目录里的资产删光 → 目录显示空 → 删目录必得 409「违反唯一性或外键约束」，
        // 而本方法是 @Transactional，回滚后这个目录永远删不掉（界面上没有「彻底删除」入口）。
        // 这里只把<b>墓碑行</b>从目录上摘下来（无任何读取路径按 DELETED 行的 folder_id 过滤，
        // 去重查询也只认 status='ACTIVE'），ACTIVE 资产仍旧挡住删除，语义不变。
        jdbc.update("""
                update platform.asset set folder_id = null
                 where tenant_id = ? and folder_id = ? and status <> 'ACTIVE'
                """, tenant, id);
        // 授权行随 folder_id 的 on delete cascade 一起清掉
        try {
            jdbc.update("delete from platform.asset_folder where id = ? and tenant_id = ?", id, tenant);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发：判空之后又有人往这个目录放了 ACTIVE 资产
            throw ApiException.conflict("目录刚被放入新的资产，无法删除；请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        return out;
    }

    // ---------------------------------------------------------------- 授权

    public Map<String, Object> grants(long id, Principal p) {
        Snapshot snap = load(p);
        if (!snap.contains(id)) {
            throw ApiException.notFound("目录不存在: " + id);
        }
        requireManageable(snap, id, p);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Grant g : snap.grants.getOrDefault(id, List.of())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subjectType", g.subjectType());
            row.put("subjectId", g.subjectId());
            row.put("canRead", g.canRead());
            row.put("canWrite", g.canWrite());
            row.put("displayName", displayName(g));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folderId", id);
        out.put("path", snap.folder(id).path());
        out.put("openDefault", rows.isEmpty());
        out.put("note", rows.isEmpty()
                ? "未配置授权：本租户成员（凭 asset:read / asset:write）默认可读写该目录内资产的内容"
                        + "（目录与列表本就对全员可见，授权只约束下载/预览）"
                : "已配置授权：仅下列主体（及目录创建者、管理侧权限点持有者）可下载/预览/写该目录内资产；"
                        + "无权限成员仍能看到目录与条目，取内容时 403 并提示提交权限申请");
        out.put("grants", rows);
        return out;
    }

    /** 全量替换目录授权（PUT 语义）：传空列表 = 撤销全部授权、回到默认开放。 */
    @Transactional
    public Map<String, Object> replaceGrants(long id, List<Map<String, Object>> inputs,
                                             Principal p) {
        Snapshot snap = load(p);
        if (!snap.contains(id)) {
            throw ApiException.notFound("目录不存在: " + id);
        }
        requireManageable(snap, id, p);
        List<Grant> grants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String tenant = TenantScope.current();
        List<Map<String, Object>> safe = inputs == null ? List.of() : inputs;
        if (safe.size() > MAX_GRANTS) {
            throw ApiException.badRequest("单目录授权主体最多 " + MAX_GRANTS + " 个");
        }
        for (Map<String, Object> row : safe) {
            String type = row.get("subjectType") == null ? ""
                    : String.valueOf(row.get("subjectType")).trim().toUpperCase();
            String subjectId = row.get("subjectId") == null ? ""
                    : String.valueOf(row.get("subjectId")).trim();
            if (!AssetFolderRules.SUBJECT_USER.equals(type)
                    && !AssetFolderRules.SUBJECT_ROLE.equals(type)) {
                throw ApiException.badRequest("subjectType 只能是 USER 或 ROLE");
            }
            if (subjectId.isEmpty() || subjectId.length() > 64) {
                throw ApiException.badRequest("subjectId 需为 1-64 个字符");
            }
            boolean canRead = asBoolean(row.get("canRead"));
            boolean canWrite = asBoolean(row.get("canWrite"));
            Grant g = new Grant(type, subjectId, canRead, canWrite).normalized();
            if (!g.canRead() && !g.canWrite()) {
                throw ApiException.badRequest("授权至少要勾选读或写：" + subjectId);
            }
            if (!seen.add(AssetFolderRules.subjectKey(g))) {
                throw ApiException.badRequest("同一主体重复授权：" + subjectId);
            }
            validateSubject(type, subjectId, tenant);
            grants.add(g);
        }
        // S11-3：目录管理页 PUT 只清 MANUAL 行；ACCESS 行由审批物化（AssetFolderGrants），
        // 不能被全量替换踩掉——否则 access_request 仍 APPROVED 但用户 403 且无法自助恢复。
        jdbc.update("delete from platform.asset_folder_grant where folder_id = ? and source = 'MANUAL'", id);
        for (Grant g : grants) {
            jdbc.update("""
                    insert into platform.asset_folder_grant
                        (folder_id, tenant_id, subject_type, subject_id, can_read, can_write, created_by, source)
                    values (?, ?, ?, ?, ?, ?, ?, 'MANUAL')
                    """, id, tenant, g.subjectType(), g.subjectId(),
                    g.canRead(), g.canWrite(), p.username());
        }
        return grants(id, p);
    }

    /** 授权弹窗的人员/角色候选（仅本租户；入口按 asset:write 收口）。 */
    public Map<String, Object> grantOptions(Principal p) {
        String tenant = TenantScope.current();
        List<Map<String, Object>> users = jdbc.queryForList("""
                select u.username as "subjectId",
                       coalesce(nullif(u.display_name, ''), u.username) as "displayName"
                  from platform.app_user u
                  join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                  join platform.tenant t on t.id = tm.tenant_id and t.code = ?
                 where u.status = 'ACTIVE'
                 order by u.username
                """, tenant);
        List<Map<String, Object>> roles = jdbc.queryForList("""
                select r.code as "subjectId", r.name as "displayName", r.builtin
                  from platform.role r
                  join platform.tenant t on t.id = r.tenant_id
                 where t.code = ? and r.status = 'ACTIVE'
                 order by r.builtin desc, r.code
                """, tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", users);
        out.put("roles", roles);
        out.put("myRoles", new ArrayList<>(p.roleCodes()));
        return out;
    }

    private void validateSubject(String type, String subjectId, String tenant) {
        if (AssetFolderRules.SUBJECT_USER.equals(type)) {
            Integer n = jdbc.queryForObject("""
                    select count(*)
                      from platform.app_user u
                      join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                      join platform.tenant t on t.id = tm.tenant_id
                     where t.code = ? and u.username = ? and u.status = 'ACTIVE'
                    """, Integer.class, tenant, subjectId);
            if (n == null || n == 0) {
                throw ApiException.badRequest("用户不是本租户的有效成员: " + subjectId);
            }
        } else {
            Integer n = jdbc.queryForObject("""
                    select count(*)
                      from platform.role r
                      join platform.tenant t on t.id = r.tenant_id
                     where t.code = ? and r.code = ? and r.status = 'ACTIVE'
                    """, Integer.class, tenant, subjectId);
            if (n == null || n == 0) {
                throw ApiException.badRequest("本租户没有该角色（或已停用）: " + subjectId);
            }
        }
    }

    private String displayName(Grant g) {
        try {
            if (AssetFolderRules.SUBJECT_USER.equals(g.subjectType())) {
                List<String> names = jdbc.queryForList("""
                        select coalesce(nullif(display_name, ''), username)
                          from platform.app_user where username = ?
                        """, String.class, g.subjectId());
                return names.isEmpty() ? g.subjectId() + "（已注销）" : names.get(0);
            }
            List<String> names = jdbc.queryForList("""
                    select r.name from platform.role r
                     join platform.tenant t on t.id = r.tenant_id
                    where t.code = ? and r.code = ?
                    """, String.class, TenantScope.current(), g.subjectId());
            return names.isEmpty() ? g.subjectId() + "（已删除）" : names.get(0);
        } catch (RuntimeException e) {
            return g.subjectId();
        }
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, parent_id as "parentId", name, path,
                       created_by as "createdBy", created_at as "createdAt"
                  from platform.asset_folder
                 where id = ? and tenant_id = ?
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("目录不存在: " + id);
        }
        return rows.get(0);
    }

    /** 同租户同父目录下名称唯一（大小写不敏感）；parentId=null 表示虚拟根。 */
    private void requireNoSibling(Long parentId, String name, long excludeId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.asset_folder
                 where tenant_id = ?
                   and coalesce(parent_id, 0) = coalesce(cast(? as bigint), 0)
                   and lower(name) = lower(?)
                   and id <> ?
                """, Integer.class, TenantScope.current(), parentId, name, excludeId);
        if (n != null && n > 0) {
            throw ApiException.conflict("同一目录下已有同名目录: " + name);
        }
    }

    private void updateSubtreePath(String oldPath, String newPath) {
        jdbc.update("""
                update platform.asset_folder
                   set path = ? || substring(path from char_length(?) + 1)
                 where tenant_id = ?
                   and (path = ? or path like ? || '/%')
                """, newPath, oldPath, TenantScope.current(), oldPath, oldPath);
    }

    private static Long normalizeParentId(Long raw) {
        return raw == null || raw == 0L ? null : raw;
    }

    private static boolean sameParent(Long current, Long requested) {
        Long norm = normalizeParentId(requested);
        return norm == null ? current == null : norm.equals(current);
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b ? b
                : value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }
}
