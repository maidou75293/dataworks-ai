package com.lab.dataworks.udf;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 函数管理：资源上传/下载/版本、函数注册/发布/停用、多级文件夹。
 *
 * <p>{@code /api/udf/jars/**} 不走登录（Doris BE / Flink TaskManager 没有会话），
 * 由不可猜测的下载 token 鉴权；界面下载走带会话的
 * {@code /api/udf/resources/{id}/download}，不把 token 暴露给浏览器。</p>
 */
@RestController
@RequestMapping("/api/udf")
public class UdfController {

    private final UdfService udf;
    private final UdfFolderService folders;
    private final JdbcTemplate jdbc;
    private final SessionService sessions;

    public UdfController(UdfService udf, UdfFolderService folders,
                         JdbcTemplate jdbc, SessionService sessions) {
        this.udf = udf;
        this.folders = folders;
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    // ---------- 资源 ----------

    /**
     * 不带 page/size：返回原来的 List（兼容 v447）；带分页：返回
     * {@code {items,total,page,size}}。folderId 支持 all / root / 数字。
     *
     * <p>D19：分页参数按平台契约校验（{@code page} 从 1、{@code size} 1–500，非法值 400，
     * 不再静默改成 1/200），分页响应补 {@code X-Total-Count} 响应头；
     * body 里的 {@code total} 保持不变（前端读的是 body）。</p>
     */
    @GetMapping("/projects/{projectId}/resources")
    public Object resources(@PathVariable long projectId,
                            @RequestParam(required = false) String folderId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size,
                            jakarta.servlet.http.HttpServletResponse response) {
        requireProject(projectId);
        Object out = udf.listResources(projectId, folderId, keyword, page, size);
        if (out instanceof Map<?, ?> map && map.get("total") != null) {
            response.setHeader("X-Total-Count", String.valueOf(map.get("total")));
        }
        return out;
    }

    @PostMapping("/projects/{projectId}/resources")
    @RequireAtom("udf:write")
    public Map<String, Object> upload(@PathVariable long projectId,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "name", required = false) String name,
                                      @RequestParam(value = "folderId", required = false) Long folderId,
                                      HttpServletRequest http) {
        Map<String, Object> out = udf.upload(projectId, name, file, user(http), folderId);
        audit(http, "UDF_UPLOAD", "p" + projectId + "/" + out.get("name"),
                "v" + out.get("version") + " " + out.get("type")
                        + " " + out.get("size") + "B");
        return out;
    }

    @PostMapping("/resources/{id}/activate")
    @RequireAtom("udf:publish")
    public Map<String, Object> activate(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> out = udf.activate(id, user(http));
        audit(http, "UDF_RESOURCE_ACTIVATE", String.valueOf(out.get("name")),
                "v" + out.get("version"));
        return out;
    }

    /**
     * 停用某个启用中的版本（active=false，不删资源/不动文件）。
     * 有已发布函数引用该资源名时 409，避免 Flink 下次提交悄悄取不到依赖。
     */
    @PostMapping("/resources/{id}/deactivate")
    @RequireAtom("udf:publish")
    public Map<String, Object> deactivate(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> out = udf.deactivate(id);
        audit(http, "UDF_RESOURCE_DEACTIVATE", String.valueOf(out.get("name")),
                "v" + out.get("version"));
        return out;
    }

    @DeleteMapping("/resources/{id}")
    @RequireAtom("udf:write")
    public Map<String, Object> deleteResource(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = udf.ownedResource(id);
        // S13：删资源（含 S3 文件删除）原来先做后记，中途失败一条痕迹都不留。
        // 先记 PENDING 再执行，失败回填 FAILED。
        return sessions.auditAround(user(http), "UDF_RESOURCE_DELETE",
                String.valueOf(row.get("name")), "v" + row.get("version") + " id " + id,
                AuthInterceptor.clientIp(http), () -> {
                    udf.deleteResource(id, user(http));
                    return Map.of("ok", true);
                });
    }

    @PostMapping("/resources/{id}/move")
    @RequireAtom("udf:write")
    public Map<String, Object> moveResource(@PathVariable long id,
                                            @RequestBody(required = false) MoveResourceRequest req,
                                            HttpServletRequest http) {
        Long folderId = req == null ? null : req.folderId();
        Map<String, Object> out = folders.moveResource(id, folderId, user(http));
        audit(http, "UDF_RESOURCE_MOVE", String.valueOf(id),
                "folderId=" + (folderId == null ? 0 : folderId));
        return out;
    }

    @GetMapping("/resources/{id}/references")
    public Map<String, Object> references(@PathVariable long id) {
        return udf.references(id);
    }

    @GetMapping("/resources/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable long id) {
        return udf.versions(id);
    }

    @GetMapping("/resources/{id}/events")
    public List<Map<String, Object>> events(@PathVariable long id) {
        return udf.events(id);
    }

    /** 界面下载：带登录会话，按项目归属校验；不暴露引擎下载 token。 */
    @GetMapping("/resources/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        UdfService.DownloadFile file = udf.download(id);
        return fileResponse(file);
    }

    // ---------- 文件夹 ----------

    @GetMapping("/projects/{projectId}/tree")
    public Map<String, Object> tree(@PathVariable long projectId) {
        return folders.tree(projectId);
    }

    @PostMapping("/projects/{projectId}/folders")
    @RequireAtom("udf:write")
    public Map<String, Object> createFolder(@PathVariable long projectId,
                                            @RequestBody FolderRequest req,
                                            HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Map<String, Object> out = folders.create(projectId, req.parentId(), req.name(), user(http));
        audit(http, "UDF_FOLDER_CREATE", "p" + projectId + "/" + out.get("path"),
                "parentId=" + req.parentId());
        return out;
    }

    @PutMapping("/folders/{id}")
    @RequireAtom("udf:write")
    public Map<String, Object> renameFolder(@PathVariable long id,
                                            @RequestBody FolderRequest req,
                                            HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Map<String, Object> out = folders.rename(id, req.name());
        audit(http, "UDF_FOLDER_RENAME", String.valueOf(id),
                "name=" + out.get("name") + " path=" + out.get("path"));
        return out;
    }

    @PostMapping("/folders/{id}/move")
    @RequireAtom("udf:write")
    public Map<String, Object> moveFolder(@PathVariable long id,
                                          @RequestBody(required = false) MoveFolderRequest req,
                                          HttpServletRequest http) {
        Long parentId = req == null ? null : req.parentId();
        Map<String, Object> out = folders.move(id, parentId);
        audit(http, "UDF_FOLDER_MOVE", String.valueOf(id),
                "parentId=" + parentId + " path=" + out.get("path"));
        return out;
    }

    @DeleteMapping("/folders/{id}")
    @RequireAtom("udf:write")
    public Map<String, Object> deleteFolder(@PathVariable long id,
                                            @RequestParam(defaultValue = "false") boolean force,
                                            HttpServletRequest http) {
        // 先按租户归属校验（跨租户/不存在统一 404），再做级联；服务层还会再查一次，
        // 这里显式调用是为了让删除入口的租户前置在控制器层可审计（CI check-destructive-tenant）。
        folders.ownedFolder(id);
        Map<String, Object> out = folders.delete(id, force);
        audit(http, "UDF_FOLDER_DELETE", String.valueOf(id),
                "force=" + force + " folders=" + out.get("deletedFolders")
                        + " movedResources=" + out.get("movedResources"));
        return out;
    }

    // ---------- 函数 ----------

    @GetMapping("/projects/{projectId}/functions")
    public List<Map<String, Object>> functions(@PathVariable long projectId) {
        requireProject(projectId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select f.id, f.resource_name as "resourceName", f.engine, f.language, f.name,
                       f.full_name as "fullName", f.class_name as "className",
                       f.return_type as "returnType", f.arg_types as "argTypes",
                       f.status, f.created_by as "createdBy", f.created_at as "createdAt",
                       f.published_by as "publishedBy", f.published_at as "publishedAt",
                       (select r.version from platform.udf_resource r
                         where r.project_id = f.project_id and r.name = f.resource_name and r.active)
                           as "activeVersion"
                  from platform.udf_function f
                 where f.project_id = ?
                 order by f.id desc
                """, projectId);
        // REQ-TC4-1：把「作用域 = 集群 GLOBAL / 无环境隔离」如实回显（UD-03）
        rows.forEach(UdfService::applyEnvScope);
        return rows;
    }

    /** 查询页/编辑器自动补全：只返回已发布函数。 */
    @GetMapping("/projects/{projectId}/usable")
    public List<Map<String, Object>> usable(@PathVariable long projectId) {
        return udf.usable(projectId);
    }

    @PostMapping("/projects/{projectId}/functions")
    @RequireAtom("udf:write")
    public Map<String, Object> createFunction(@PathVariable long projectId,
                                              @RequestBody UdfService.FunctionRequest req,
                                              HttpServletRequest http) {
        Map<String, Object> out = udf.createFunction(projectId, req, user(http));
        audit(http, "UDF_FUNCTION_CREATE", "p" + projectId + "/" + req.name(),
                req.engine() + " " + out.get("language"));
        return out;
    }

    @PutMapping("/functions/{id}")
    @RequireAtom("udf:write")
    public Map<String, Object> updateFunction(@PathVariable long id,
                                              @RequestBody UdfService.FunctionRequest req,
                                              HttpServletRequest http) {
        udf.updateFunction(id, req);
        audit(http, "UDF_FUNCTION_UPDATE", String.valueOf(req.name()),
                req.engine() + " " + req.language());
        return Map.of("ok", true);
    }

    @PostMapping("/functions/{id}/publish")
    @RequireAtom("udf:publish")
    public Map<String, Object> publish(@PathVariable long id, HttpServletRequest http) {
        // D59：改成先记后做——被拒绝的发布尝试（例如 Python 函数发 Doris）必须留一条 FAILED，
        // 否则「谁在什么时候想发一个注定失败的函数」在审计里查不到。
        return sessions.auditAround(user(http), "UDF_FUNCTION_PUBLISH", "f" + id,
                "id " + id, AuthInterceptor.clientIp(http), () -> udf.publish(id, user(http)),
                out -> String.valueOf(out.get("status"))
                        + " engine=" + out.get("engine") + " language=" + out.get("language"));
    }

    @PostMapping("/functions/{id}/offline")
    @RequireAtom("udf:publish")
    public Map<String, Object> offline(@PathVariable long id, HttpServletRequest http) {
        // S12：detail 原来为 null；S13：原来先做后记——Doris 侧 DROP FUNCTION 一失败
        // 就一条都不留。改成先记后做，detail 至少带上函数 id。
        return sessions.auditAround(user(http), "UDF_FUNCTION_OFFLINE", "f" + id, "id " + id,
                AuthInterceptor.clientIp(http), () -> udf.offline(id));
    }

    @DeleteMapping("/functions/{id}")
    @RequireAtom("udf:write")
    public Map<String, Object> deleteFunction(@PathVariable long id, HttpServletRequest http) {
        // S13：删函数（Doris/Flink 侧还要 DROP FUNCTION）先做后记 → 失败无痕，改为先记后做。
        return sessions.auditAround(user(http), "UDF_FUNCTION_DELETE", "f" + id, "id " + id,
                AuthInterceptor.clientIp(http), () -> {
                    udf.deleteFunction(id);
                    return Map.of("ok", true);
                });
    }

    // ---------- 引擎侧下载（无会话，token 鉴权） ----------

    @GetMapping("/jars/{id}/{token}/{filename:.+}")
    public ResponseEntity<Resource> jar(@PathVariable long id, @PathVariable String token,
                                        @PathVariable String filename) {
        return fileResponse(udf.downloadByToken(id, token));
    }

    private ResponseEntity<Resource> fileResponse(UdfService.DownloadFile file) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(file.contentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(type)
                .contentLength(file.bytes().length)
                .body(new ByteArrayResource(file.bytes()));
    }

    private void requireProject(long projectId) {
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private String user(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private void audit(HttpServletRequest request, String action, String target, String detail) {
        sessions.audit(user(request), action, target, detail, "SUCCESS",
                AuthInterceptor.clientIp(request));
    }

    public record FolderRequest(Long parentId, String name) {
    }

    public record MoveFolderRequest(Long parentId) {
    }

    public record MoveResourceRequest(Long folderId) {
    }
}
