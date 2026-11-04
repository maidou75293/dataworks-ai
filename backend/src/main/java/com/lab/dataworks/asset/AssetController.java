package com.lab.dataworks.asset;

import com.lab.dataworks.asset.AssetFolderRules.Principal;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 多模态资产（图片 / PDF / 文本）：MinIO 存本体，平台存元数据。
 *
 * <p>分页契约与平台其他列表一致：{@code page} 从 1 开始、{@code size} 1–500、
 * 总数放响应头 {@code X-Total-Count}。</p>
 *
 * <p>V110 目录化 + S11 两层口径（评审确认 2026-10-26）：
 * <b>目录层</b>——列表/树/详情元数据对同租户全员可见，不再按读授权过滤条目；
 * <b>内容层</b>——预签名 {@code /url}、文件本体、详情敏感字段（uri/sha256/url）需要授权
 * （owner / 目录内容授权 / 管理侧），无授权 {@code /url} 返回 403 并提示提交权限申请。
 * 上传/移动/删除要求目录写权限；「我的上传」（folder_id=NULL）里的资产仅 owner/管理侧可移动或删除；
 * 删除回执不再回显对象 uri（只进审计）。跨租户一律 404。
 * {@code folderId} 约定：不传 = 全部；0 = 虚拟根「我的上传」；N = 指定目录。</p>
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assets;
    private final AssetFolderService folders;
    private final SessionService sessions;

    public AssetController(AssetService assets, AssetFolderService folders,
                           SessionService sessions) {
        this.assets = assets;
        this.folders = folders;
        this.sessions = sessions;
    }

    /** multipart 上传；同租户同目录同内容会直接复用已有资产（reused=true）。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireAtom("asset:write")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String mediaType,
                                      @RequestParam(required = false) String meta,
                                      @RequestParam(required = false) Long folderId,
                                      HttpServletRequest http) {
        Map<String, Object> out = assets.upload(file, mediaType, meta, user(http), folderId,
                folders.principal(http));
        sessions.audit(user(http), "ASSET_UPLOAD", String.valueOf(out.get("id")),
                "sha256=" + out.get("sha256") + " size=" + out.get("size_bytes")
                        + " reused=" + out.get("reused")
                        + " folder=" + (out.get("folder_id") == null ? 0 : out.get("folder_id")),
                "SUCCESS", com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return out;
    }

    @GetMapping
    @RequireAtom("asset:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false) String mediaType,
            @RequestParam(required = false) Long folderId,
            HttpServletRequest http) {
        Map<String, Object> body = assets.list(page, size, mediaType, folderId,
                folders.principal(http));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(body.getOrDefault("total", 0)))
                .body(body);
    }

    /** 详情：元数据（名称/类型/大小/归属）同租户全员可见；内容层字段按授权裁剪。 */
    @GetMapping("/{id}")
    @RequireAtom("asset:read")
    public Map<String, Object> detail(@PathVariable long id, HttpServletRequest http) {
        return assets.detail(id, folders.principal(http));
    }

    /** 预签名下载地址（10 分钟）——内容层入口：owner/目录内容授权/管理侧可取，无授权 403 提示提交权限申请。 */
    @GetMapping("/{id}/url")
    @RequireAtom("asset:read")
    public Map<String, Object> url(@PathVariable long id, HttpServletRequest http) {
        return assets.presignedUrl(id, folders.principal(http));
    }

    /** 移动资产：body {@code {"folderId": N}}；0 或缺省 = 移到「我的上传」。 */
    @PutMapping("/{id}/folder")
    @RequireAtom("asset:write")
    public Map<String, Object> move(@PathVariable long id,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest http) {
        Long folderId = asFolderId(body == null ? null : body.get("folderId"));
        Principal p = folders.principal(http);
        Map<String, Object> out = assets.move(id, folderId, p);
        sessions.audit(p.username(), "ASSET_MOVE", String.valueOf(id),
                "folderId=" + (out.get("folder_id") == null ? 0 : out.get("folder_id")),
                "SUCCESS", com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        return out;
    }

    @DeleteMapping("/{id}")
    @RequireAtom("asset:write")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> out = assets.delete(id, folders.principal(http));
        sessions.audit(user(http), "ASSET_DELETE", String.valueOf(id),
                "uri=" + out.get("uri"), "SUCCESS",
                com.lab.dataworks.auth.AuthInterceptor.clientIp(http));
        // S11：删除回执不是内容层入口。对象地址只进审计，不下发给调用方
        //（旧口径下 200 响应直接带 uri，等于给「拿不到预签名但想探测路径」的人留了口子）
        out.remove("uri");
        return out;
    }

    private String user(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    /** folderId 归一：缺省/0 = 虚拟根；非数字 400（不给 500）。 */
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
            throw com.lab.dataworks.common.ApiException.badRequest("folderId 需为数字（0 = 我的上传）: " + value);
        }
    }
}
