package com.lab.dataworks.udf;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UDF 多级文件夹：树、新建、重命名、移动、删除、资源移动。
 *
 * <p>路径 {@code path} 存绝对路径（根目录的子文件夹是 {@code /a}，孙目录是 {@code /a/b}），
 * 重命名/移动时用前缀替换一次性更新整棵子树，避免递归 N 次 SQL。资源移动只改
 * {@code udf_resource.folder_id}，不影响资源名/版本/函数引用。</p>
 *
 * <p>命名/路径/环检测/非空删除的判定统一在 {@link UdfFolderRules}（纯逻辑，可单测）。
 * 软删资源（{@code metadata.deletedAt}）不计入目录资源数与「非空」判断。</p>
 */
@Service
public class UdfFolderService {

    private final JdbcTemplate jdbc;
    private final UdfService udf;

    public UdfFolderService(JdbcTemplate jdbc, UdfService udf) {
        this.jdbc = jdbc;
        this.udf = udf;
    }

    // ---------------------------------------------------------------- 树

    public Map<String, Object> tree(long projectId) {
        requireProject(projectId);
        List<Map<String, Object>> folders = jdbc.queryForList("""
                select id, parent_id as "parentId", name, path,
                       created_by as "createdBy", created_at as "createdAt"
                  from platform.udf_folder
                 where project_id = ?
                 order by path
                """, projectId);
        Map<Long, Integer> direct = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select r.folder_id, count(*) as cnt
                  from platform.udf_resource r
                 where r.project_id = ? and r.folder_id is not null
                   and not jsonb_exists(r.metadata, 'deletedAt')
                 group by r.folder_id
                """, projectId)) {
            direct.put(num(row.get("folder_id")), ((Number) row.get("cnt")).intValue());
        }
        Integer rootResources = jdbc.queryForObject("""
                select count(*) from platform.udf_resource r
                 where r.project_id = ? and r.folder_id is null
                   and not jsonb_exists(r.metadata, 'deletedAt')
                """, Integer.class, projectId);

        // 按 path 从深到浅累加：子目录一定比父目录长，先处理子目录即可一次算完。
        List<Map<String, Object>> ordered = new ArrayList<>(folders);
        ordered.sort((a, b) -> Integer.compare(
                String.valueOf(b.get("path")).length(), String.valueOf(a.get("path")).length()));
        Map<Long, Integer> totals = new HashMap<>(direct);
        Map<Long, Long> parentOf = new HashMap<>();
        for (Map<String, Object> row : folders) {
            long id = num(row.get("id"));
            parentOf.put(id, row.get("parentId") == null ? null : num(row.get("parentId")));
        }
        for (Map<String, Object> row : ordered) {
            long id = num(row.get("id"));
            int own = direct.getOrDefault(id, 0);
            totals.put(id, totals.getOrDefault(id, own));
            Long parent = parentOf.get(id);
            if (parent != null) {
                totals.merge(parent, totals.get(id), Integer::sum);
            }
        }
        for (Map<String, Object> row : folders) {
            long id = num(row.get("id"));
            row.put("resourceCount", direct.getOrDefault(id, 0));
            row.put("totalResourceCount", totals.getOrDefault(id, 0));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("rootResources", rootResources == null ? 0 : rootResources);
        out.put("totalResources", rootResources == null ? 0 : rootResources
                + direct.values().stream().mapToInt(Integer::intValue).sum());
        out.put("folders", folders);
        return out;
    }

    // ---------------------------------------------------------------- 写

    @Transactional
    public Map<String, Object> create(long projectId, Long parentId, String rawName, String user) {
        requireProject(projectId);
        String name = UdfFolderRules.normalizeName(rawName);
        Map<String, Object> parent = parentId == null ? null : requireFolder(projectId, parentId);
        String path = UdfFolderRules.childPath(
                parent == null ? null : String.valueOf(parent.get("path")), name);
        if (siblingExists(projectId, parentId, name, -1L)) {
            throw ApiException.conflict("同一目录下已有文件夹: " + name);
        }
        try {
            Long id = jdbc.queryForObject("""
                    insert into platform.udf_folder
                        (project_id, tenant_id, parent_id, name, path, created_by)
                    values (?, ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, projectId, TenantScope.current(), parentId, name, path, user);
            return requireFolder(projectId, id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一目录下已有文件夹: " + name);
        }
    }

    @Transactional
    public Map<String, Object> rename(long id, String rawName) {
        Map<String, Object> folder = ownedFolder(id);
        long projectId = num(folder.get("project_id"));
        String name = UdfFolderRules.normalizeName(rawName);
        Long parentId = folder.get("parent_id") == null ? null : num(folder.get("parent_id"));
        if (siblingExists(projectId, parentId, name, id)) {
            throw ApiException.conflict("同一目录下已有文件夹: " + name);
        }
        String oldPath = String.valueOf(folder.get("path"));
        String parentPath = parentId == null ? "" : parentPath(projectId, parentId);
        String newPath = UdfFolderRules.childPath(parentPath.isEmpty() ? null : parentPath, name);
        try {
            jdbc.update("update platform.udf_folder set name = ? where id = ?", name, id);
            updateSubtreePath(projectId, oldPath, newPath);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一目录下已有文件夹: " + name);
        }
        return requireFolder(projectId, id);
    }

    @Transactional
    public Map<String, Object> move(long id, Long newParentId) {
        Map<String, Object> folder = ownedFolder(id);
        long projectId = num(folder.get("project_id"));
        if (newParentId != null && newParentId == id) {
            throw ApiException.badRequest("不能把文件夹移到它自己下面");
        }
        Map<String, Object> newParent = newParentId == null
                ? null : requireFolder(projectId, newParentId);
        String oldPath = String.valueOf(folder.get("path"));
        String targetParentPath = newParent == null ? null : String.valueOf(newParent.get("path"));
        // 环检测：目标父目录不能是自己或自己的子孙（/ab 不算 /a 的子目录）。
        UdfFolderRules.checkMoveTarget(id, newParentId, oldPath, targetParentPath);
        String name = String.valueOf(folder.get("name"));
        if (siblingExists(projectId, newParentId, name, id)) {
            throw ApiException.conflict("目标目录下已有同名文件夹: " + name);
        }
        String newPath = UdfFolderRules.childPath(targetParentPath, name);
        try {
            jdbc.update("""
                    update platform.udf_folder
                       set parent_id = ?, path = ?
                     where id = ?
                    """, newParentId, newPath, id);
            updateSubtreePath(projectId, oldPath, newPath);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("目标目录下已有同名文件夹: " + name);
        }
        return requireFolder(projectId, id);
    }

    /**
     * 删除文件夹。非空默认 409；force=true 删除整棵子树，资源不删除、移到根目录。
     * 资源是版本化二进制，隐式删除会连带破坏函数引用，所以 force 只级联文件夹。
     */
    @Transactional
    public Map<String, Object> delete(long id, boolean force) {
        Map<String, Object> folder = ownedFolder(id);
        long projectId = num(folder.get("project_id"));
        List<Long> subtree = subtreeIds(projectId, id);
        int folderCount = subtree.size();
        int resourceCount = jdbc.queryForObject("""
                select count(*) from platform.udf_resource r
                 where r.project_id = ? and r.folder_id in
                       (select id from platform.udf_folder where id = ?
                          or path = ? or path like ? || '/%')
                   and not jsonb_exists(r.metadata, 'deletedAt')
                """, Integer.class, projectId, id,
                folder.get("path"), folder.get("path"));
        UdfFolderRules.checkDelete(force, folderCount, resourceCount);
        jdbc.update("""
                delete from platform.udf_folder
                 where id = ?
                   and project_id in (select p.id from platform.project p
                                       where p.tenant_id = ?)
                """, id, TenantScope.current());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("deletedFolders", folderCount);
        out.put("movedResources", resourceCount);
        return out;
    }

    /** 资源换目录；folderId=null 表示根目录。 */
    public Map<String, Object> moveResource(long resourceId, Long folderId) {
        return moveResource(resourceId, folderId, null);
    }

    public Map<String, Object> moveResource(long resourceId, Long folderId, String actor) {
        Map<String, Object> resource = udf.ownedResource(resourceId);
        long projectId = num(resource.get("project_id"));
        if (folderId != null) {
            requireFolder(projectId, folderId);
        }
        jdbc.update("update platform.udf_resource set folder_id = ? where id = ?",
                folderId, resourceId);
        udf.recordEvent(projectId, resourceId, String.valueOf(resource.get("name")),
                resource.get("version") instanceof Number n ? n.intValue() : null,
                "MOVE", actor, "folderId=" + (folderId == null ? 0 : folderId));
        return Map.of("ok", true, "id", resourceId,
                "folderId", folderId == null ? 0 : folderId);
    }

    // ---------------------------------------------------------------- 内部

    public Map<String, Object> ownedFolder(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select f.* from platform.udf_folder f
                 where f.id = ?
                   and f.project_id in (select p.id from platform.project p
                                         where p.tenant_id = ?)
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("文件夹不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> requireFolder(long projectId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, parent_id as "parentId", name, path,
                       created_by as "createdBy", created_at as "createdAt"
                  from platform.udf_folder
                 where id = ? and project_id = ?
                """, id, projectId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("文件夹不存在: " + id);
        }
        return rows.get(0);
    }

    private boolean siblingExists(long projectId, Long parentId, String name, long excludeId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.udf_folder
                 where project_id = ?
                   and coalesce(parent_id, 0) = coalesce(cast(? as bigint), 0)
                   and lower(name) = lower(?)
                   and id <> ?
                """, Integer.class, projectId, parentId, name, excludeId);
        return n != null && n > 0;
    }

    private void updateSubtreePath(long projectId, String oldPath, String newPath) {
        jdbc.update("""
                update platform.udf_folder
                   set path = ? || substring(path from char_length(?) + 1)
                 where project_id = ?
                   and (path = ? or path like ? || '/%')
                """, newPath, oldPath, projectId, oldPath, oldPath);
    }

    private String parentPath(long projectId, long parentId) {
        Map<String, Object> parent = requireFolder(projectId, parentId);
        return String.valueOf(parent.get("path"));
    }

    private List<Long> subtreeIds(long projectId, long id) {
        return jdbc.queryForList("""
                with recursive sub as (
                    select id from platform.udf_folder where id = ? and project_id = ?
                    union all
                    select f.id from platform.udf_folder f
                      join sub on f.parent_id = sub.id
                     where f.project_id = ?
                )
                select id from sub
                """, Long.class, id, projectId, projectId);
    }

    private void requireProject(long projectId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Integer.class, projectId, TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

}
