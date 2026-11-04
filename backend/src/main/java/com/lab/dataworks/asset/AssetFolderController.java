package com.lab.dataworks.asset;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多模态资产目录（V110）：目录树 CRUD + 目录级读写授权。
 *
 * <p>入口先过权限点（读=asset:read，写=asset:write），目录级判定在
 * {@link AssetFolderService}。S11 两层口径：目录/列表可见性对同租户全员开放，
 * 授权约束<b>内容层</b>（下载/预览/写）——无授权行 = 本租户默认开放；有授权行 =
 * 仅匹配主体（USER/ROLE）可取内容/写；创建者与管理侧权限点（system:manage、
 * tenant:manage、user:manage）始终保留兜底权。删除仅允许空目录。</p>
 */
@RestController
@RequestMapping("/api/asset-folders")
public class AssetFolderController {

    private final AssetFolderService folders;
    private final SessionService sessions;

    public AssetFolderController(AssetFolderService folders, SessionService sessions) {
        this.folders = folders;
        this.sessions = sessions;
    }

    /** 目录树（S11 目录层：本租户目录对全员下发；canRead=内容层读权，附 directCount / canWrite / canManage）。 */
    @GetMapping
    @RequireAtom("asset:read")
    public Map<String, Object> tree(HttpServletRequest http) {
        return folders.tree(folders.principal(http));
    }

    /** 授权弹窗的人员/角色候选（本租户成员与角色；入口按 asset:write 收口）。 */
    @GetMapping("/grant-options")
    @RequireAtom("asset:write")
    public Map<String, Object> grantOptions(HttpServletRequest http) {
        return folders.grantOptions(folders.principal(http));
    }

    /** 新建目录：body {@code {"name": "...", "parentId": N|0|null}}；parentId 空/0 = 根。 */
    @PostMapping
    @RequireAtom("asset:write")
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest http) {
        AssetFolderRules.Principal p = folders.principal(http);
        Map<String, Object> out = folders.create(asFolderId(body.get("parentId")),
                String.valueOf(body.getOrDefault("name", "")), p);
        sessions.audit(p.username(), "ASSET_FOLDER_CREATE", String.valueOf(out.get("id")),
                "path=" + out.get("path"), "SUCCESS", AuthInterceptor.clientIp(http));
        return out;
    }

    /**
     * 改名 + 移动：{@code name} 非空才改名；{@code parentId} 字段出现才移动（0/null=根）。
     * 移动到自己的子孙下 → 400；目标目录同名 → 409；需要目录管理权。
     */
    @PutMapping("/{id}")
    @RequireAtom("asset:write")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest http) {
        AssetFolderRules.Principal p = folders.principal(http);
        boolean parentProvided = body.containsKey("parentId");
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        Map<String, Object> out = folders.update(id, name,
                asFolderId(body.get("parentId")), parentProvided, p);
        sessions.audit(p.username(), "ASSET_FOLDER_UPDATE", String.valueOf(id),
                "name=" + out.get("name") + " path=" + out.get("path")
                        + " parentId=" + (out.get("parentId") == null ? 0 : out.get("parentId")),
                "SUCCESS", AuthInterceptor.clientIp(http));
        return out;
    }

    /** 删除目录：仅空目录（无子目录、无 ACTIVE 资产），否则 409。 */
    @DeleteMapping("/{id}")
    @RequireAtom("asset:write")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        AssetFolderRules.Principal p = folders.principal(http);
        Map<String, Object> out = folders.delete(id, p);
        sessions.audit(p.username(), "ASSET_FOLDER_DELETE", String.valueOf(id),
                "ok", "SUCCESS", AuthInterceptor.clientIp(http));
        return out;
    }

    /** 查看目录授权（权限查看）：需要目录管理权（创建者 / 可写主体 / 管理侧权限点）。 */
    @GetMapping("/{id}/grants")
    @RequireAtom("asset:read")
    public Map<String, Object> grants(@PathVariable long id, HttpServletRequest http) {
        return folders.grants(id, folders.principal(http));
    }

    /**
     * 全量替换目录授权：body {@code {"grants":[{"subjectType":"USER|ROLE",
     * "subjectId":"...","canRead":true,"canWrite":false}]}}；空列表 = 撤销全部授权、回到默认开放。
     */
    @PutMapping("/{id}/grants")
    @RequireAtom("asset:write")
    @SuppressWarnings("unchecked")
    public Map<String, Object> replaceGrants(@PathVariable long id,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest http) {
        AssetFolderRules.Principal p = folders.principal(http);
        Object raw = body.get("grants");
        if (raw != null && !(raw instanceof List<?>)) {
            throw ApiException.badRequest("grants 必须是数组");
        }
        List<Map<String, Object>> grants = (List<Map<String, Object>>) raw;
        Map<String, Object> out = folders.replaceGrants(id, grants, p);
        sessions.audit(p.username(), "ASSET_FOLDER_GRANT", String.valueOf(id),
                "count=" + (grants == null ? 0 : grants.size())
                        + " subjects=" + (grants == null ? "" : String.valueOf(grants)),
                "SUCCESS", AuthInterceptor.clientIp(http));
        return out;
    }

    /** parentId 统一归一：非数字/负数 400；0 或 null 由 service 当根目录处理。 */
    private static Long asFolderId(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("parentId 需为数字（0 = 根目录）: " + value);
        }
    }
}
