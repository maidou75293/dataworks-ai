package com.lab.dataworks.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.AtomCheck;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.Role;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.AutoCode;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.secret.SecretCipher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据服务：把只读 SQL 发布成 HTTP API。
 *
 * 平台内接口（需登录）：/api/services/**
 * 对外调用接口（Token 鉴权）：/api/ds/{code}
 */
@RestController
public class DataServiceController {

    private static final Set<String> METHODS = Set.of("GET", "POST");
    private static final Set<String> TYPES = Set.of(
            "STRING", "INT", "LONG", "DECIMAL", "BOOL", "DATE", "DATETIME");
    private static final int MAX_ROWS = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final SecretCipher cipher;
    private final com.lab.dataworks.security.DataGuardService dataGuard;

    public DataServiceController(JdbcTemplate jdbc, SessionService sessions, ObjectMapper mapper,
                                 SecretCipher cipher,
                                 com.lab.dataworks.security.DataGuardService dataGuard) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.mapper = mapper;
        this.cipher = cipher;
        this.dataGuard = dataGuard;
    }

    public record ParamSpec(String name, String type, Boolean required, String defaultValue) {
    }

    public record ServiceRequest(Long projectId, String code, String name, String method, Long datasourceId,
                                 String description, String sqlText, List<ParamSpec> params) {
    }

    public record TestRequest(Long projectId, Long datasourceId, String sqlText,
                              List<ParamSpec> params, Map<String, Object> values) {
    }

    // ---------- 平台内管理 ----------

    /**
     * v449 P2：统一分页契约（page 从 1 开始，size 1–500；不传 size 仍返回全量）。
     * 传 size 时回 {@code X-Total-Count}。
     */
    @GetMapping("/api/services")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          jakarta.servlet.http.HttpServletResponse response) {
        String base = """
                select s.id, s.project_id, p.name as project_name, s.code, s.name, s.method,
                       s.datasource_id, d.name as datasource_name, d.ds_type as datasource_type,
                       s.description, s.sql_text, s.params_json, s.status, s.version, s.api_token,
                       s.created_by, s.created_at, s.updated_at, s.published_at,
                       s.smoke_hash, s.smoke_ok_at,
                       rel.version as release_version, rel.published_at as release_published_at,
                       rel.method as release_method, rel.datasource_id as release_datasource_id,
                       rel.sql_text as release_sql_text, rel.params_json as release_params_json,
                       (select count(*) from platform.data_service_call c
                         where c.service_id = s.id and c.created_at > now() - interval '24 hours') as calls_24h,
                       (select count(*) from platform.data_service_call c
                         where c.service_id = s.id and c.status_code >= 400
                           and c.created_at > now() - interval '24 hours') as errors_24h
                from platform.data_service s
                join platform.project p on p.id = s.project_id
                left join lateral (
                    select * from platform.data_service_release r
                    where r.service_id = s.id order by r.version desc limit 1
                ) rel on true
                left join platform.datasource d on d.id = s.datasource_id
                where s.project_id in (select p2.id from platform.project p2 where p2.tenant_id = ?)
                """;
        String sql = projectId == null ? base : base + " and s.project_id = ?";
        List<Object> args = new java.util.ArrayList<>();
        args.add(com.lab.dataworks.auth.TenantScope.current());
        if (projectId != null) {
            args.add(projectId);
        }
        if (size != null) {
            if (size < 1 || size > 500) {
                throw ApiException.badRequest("size 取值 1–500");
            }
            int pageNo = page == null ? 1 : page;
            if (pageNo < 1) {
                throw ApiException.badRequest("page 从 1 开始");
            }
            Long total = jdbc.queryForObject(
                    "select count(*) from platform.data_service s where "
                            + "s.project_id in (select p.id from platform.project p where p.tenant_id = ?)"
                            + (projectId == null ? "" : " and s.project_id = ?"),
                    Long.class, projectId == null
                            ? new Object[]{com.lab.dataworks.auth.TenantScope.current()}
                            : new Object[]{com.lab.dataworks.auth.TenantScope.current(), projectId});
            response.setHeader("X-Total-Count", String.valueOf(total == null ? 0 : total));
            sql = sql + " order by s.id desc limit ? offset ?";
            args.add(size);
            // D12：offset 用 long，page=2147483647 不再 int 溢出成 409「数据冲突」
            args.add(com.lab.dataworks.common.Paging.offset(pageNo, size));
        } else {
            sql = sql + " order by s.id desc";
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        rows.forEach(this::decorate);
        return rows;
    }

    @GetMapping("/api/services/{id}")
    public Map<String, Object> detail(@PathVariable long id, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.*, p.name as project_name, d.name as datasource_name, d.ds_type as datasource_type,
                       rel.version as release_version, rel.published_at as release_published_at,
                       rel.method as release_method, rel.datasource_id as release_datasource_id,
                       rel.sql_text as release_sql_text, rel.params_json as release_params_json
                from platform.data_service s
                join platform.project p on p.id = s.project_id
                left join lateral (
                    select * from platform.data_service_release r
                    where r.service_id = s.id order by r.version desc limit 1
                ) rel on true
                left join platform.datasource d on d.id = s.datasource_id
                where s.id = ? and p.tenant_id = ?
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据服务不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        decorate(row);
        // 详情接口不再返回明文 Token（v447 P0-3：外部审计可跨租户读到 Token 并直接取数）。
        // 明文只在发布 / 重置 Token 的响应里一次性返回；详情只有 token_masked。
        return row;
    }

    private boolean canPublish(HttpServletRequest request) {
        return AtomCheck.has(request, "service:publish");
    }

    @PostMapping("/api/services")
    @RequireAtom("service:write")
    public Map<String, Object> create(@RequestBody ServiceRequest req, HttpServletRequest http) {
        validate(req, false);
        requireProject(req.projectId());
        // REQ-DW-9：编码留空即自动生成（前缀_日期_随机后缀），显式传入仍按原校验走
        String code = resolveCode(req.code(), "svc");
        checkCodeUnique(req.projectId(), code, null);
        checkDatasource(req.projectId(), req.datasourceId());
        try {
            jdbc.update("""
                    insert into platform.data_service
                        (project_id, code, name, method, datasource_id, description, sql_text,
                         params_json, created_by, tenant_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, req.projectId(), code, req.name().trim(),
                    req.method().toUpperCase(Locale.ROOT), req.datasourceId(),
                    req.description(), req.sqlText().trim(), paramsJson(req.params()),
                    String.valueOf(http.getAttribute("username")),
                    com.lab.dataworks.auth.TenantScope.current());
        } catch (DuplicateKeyException e) {
            // 查重与插入之间仍可能被并发穿过：唯一键兜底成 409，而不是 500。
            throw ApiException.conflict("服务编码已存在: " + code);
        }
        sessions.audit(user(http), "SERVICE_CREATE", code, req.name());
        return Map.of("ok", true);
    }

    @PutMapping("/api/services/{id}")
    @RequireAtom("service:write")
    public Map<String, Object> update(@PathVariable long id, @RequestBody ServiceRequest req,
                                      HttpServletRequest http) {
        find(id);
        validate(req);
        requireProject(req.projectId());
        checkCodeUnique(req.projectId(), req.code(), id);
        checkDatasource(req.projectId(), req.datasourceId());
        int updated = jdbc.update("""
                update platform.data_service
                set project_id = ?, code = ?, name = ?, method = ?, datasource_id = ?,
                    description = ?, sql_text = ?, params_json = ?, updated_at = now()
                where id = ?
                  and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, req.projectId(), req.code().trim(), req.name().trim(),
                req.method().toUpperCase(Locale.ROOT), req.datasourceId(),
                req.description(), req.sqlText().trim(), paramsJson(req.params()), id,
                com.lab.dataworks.auth.TenantScope.current());
        if (updated == 0) {
            throw ApiException.notFound("数据服务不存在: " + id);
        }
        // 开发态内容变了 → 之前的 DEV 跑通作废，必须重新测试才能发布。
        // 生产快照不受影响：线上调用仍使用上一次发布的版本。
        Map<String, Object> fresh = find(id);
        String hash = contentHash(fresh.get("method"), fresh.get("datasource_id"),
                fresh.get("sql_text"), fresh.get("params_json"));
        if (!hash.equals(fresh.get("smoke_hash"))) {
            jdbc.update("""
                    update platform.data_service
                    set smoke_hash = null, smoke_ok_at = null, smoke_by = null
                    where id = ? and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                    """, id, com.lab.dataworks.auth.TenantScope.current());
        }
        sessions.audit(user(http), "SERVICE_UPDATE", req.code(), null);
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/services/{id}")
    @RequireAtom("service:delete")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        // S12 + S13：SERVICE_DELETE 原来先做后记且 detail=null——事后看不出删的是
        // 哪个状态、哪个发布版本的服务，删除失败还一条不留。先记 PENDING 再执行，
        // 失败回填 FAILED（异常原样上抛，状态码口径不变）。
        String detail = "id=" + id + " name=" + row.get("name")
                + " status=" + row.get("status") + " version=" + row.get("version");
        return sessions.auditAround(user(http), "SERVICE_DELETE", String.valueOf(row.get("code")),
                detail, AuthInterceptor.clientIp(http), () -> {
                    int deleted = jdbc.update("""
                            delete from platform.data_service
                             where id = ?
                               and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                            """, id, com.lab.dataworks.auth.TenantScope.current());
                    if (deleted == 0) {
                        throw ApiException.notFound("数据服务不存在: " + id);
                    }
                    return Map.of("ok", true);
                });
    }

    public record SmokeRequest(Map<String, Object> values) {
    }

    /**
     * DEV 跑通：用**已保存的开发态定义**真实执行一次 SQL。
     * 通过后记录内容指纹；发布时指纹必须与当前开发态一致。
     */
    @PostMapping("/api/services/{id}/smoke")
    @RequireAtom("service:write")
    public Map<String, Object> smoke(@PathVariable long id,
                                     @RequestBody(required = false) SmokeRequest req,
                                     HttpServletRequest http) {
        Map<String, Object> svc = find(id);
        Object rawDs = svc.get("datasource_id");
        if (!(rawDs instanceof Number dsId)) {
            throw ApiException.badRequest("请先选择数据源，再在 DEV 跑通");
        }
        Map<String, Object> ds = findDatasource(dsId.longValue());
        String hash = contentHash(svc.get("method"), svc.get("datasource_id"),
                svc.get("sql_text"), svc.get("params_json"));
        QueryResult result = execute(String.valueOf(ds.get("jdbc_url")),
                String.valueOf(ds.get("username")),
                cipher.decrypt(String.valueOf(ds.get("password"))),
                String.valueOf(svc.get("sql_text")),
                parseParams((String) svc.get("params_json")),
                req == null || req.values() == null ? Map.of() : req.values());
        List<Map<String, Object>> rows = guarded(svc, result,
                String.valueOf(http.getAttribute("username")));
        String actor = String.valueOf(http.getAttribute("username"));
        jdbc.update("""
                update platform.data_service
                set smoke_hash = ?, smoke_ok_at = now(), smoke_by = ?
                where id = ?
                  and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, hash, actor, id, com.lab.dataworks.auth.TenantScope.current());
        sessions.audit(actor, "SERVICE_SMOKE", String.valueOf(svc.get("code")),
                "rows=" + rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("smokeOk", true);
        out.put("columns", result.columns());
        out.put("rows", rows.size() > 20 ? rows.subList(0, 20) : rows);
        out.put("rowCount", rows.size());
        out.put("truncated", result.truncated());
        out.put("elapsedMs", result.elapsedMs());
        return out;
    }

    @PostMapping("/api/services/{id}/publish")
    @RequireAtom("service:publish")
    public Map<String, Object> publish(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> svc = find(id);
        String hash = contentHash(svc.get("method"), svc.get("datasource_id"),
                svc.get("sql_text"), svc.get("params_json"));
        Map<String, Object> latest = latestRelease(id);
        String releaseHash = latest == null ? null : contentHash(latest.get("method"),
                latest.get("datasource_id"), latest.get("sql_text"), latest.get("params_json"));
        // 内容与生产快照一致（例如下线后重新上线）可以直接发布，不需要重复 DEV 跑通；
        // 有改动则必须先测试：测试通过后指纹才会等于当前内容。
        if (!hash.equals(releaseHash) && !hash.equals(svc.get("smoke_hash"))) {
            throw ApiException.badRequest(
                    "请先在开发环境跑通：跑通后才能发布（修改后需重新跑通）");
        }
        // 内容与生产快照一致（下线后重新上线 / 重复点发布）不再新增版本
        boolean unchanged = hash.equals(releaseHash);
        int next;
        if (unchanged) {
            next = ((Number) latest.get("version")).intValue();
        } else {
            next = latest == null ? 1 : ((Number) latest.get("version")).intValue() + 1;
            jdbc.update("""
                    insert into platform.data_service_release
                        (service_id, version, method, datasource_id, sql_text, params_json,
                         published_at, published_by)
                    values (?, ?, ?, ?, ?, ?, now(), ?)
                    """, id, next, svc.get("method"), svc.get("datasource_id"),
                    svc.get("sql_text"), svc.get("params_json"),
                    String.valueOf(http.getAttribute("username")));
        }
        String stored = jdbc.queryForObject("""
                select api_token from platform.data_service
                 where id = ?
                   and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, String.class, id, com.lab.dataworks.auth.TenantScope.current());
        // v449 P2：明文 Token 只在「首次发布生成」时返回一次；之后重复发布只回 masked，
        // 和详情/列表口径一致。要重新取明文请走 POST /services/{id}/token（会轮换）。
        boolean firstPublish = stored == null || stored.isBlank();
        String token = firstPublish ? newToken() : cipher.decrypt(stored);
        int published = jdbc.update("""
                update platform.data_service
                set status = 'PUBLISHED', api_token = ?, published_at = now(), version = ?
                where id = ?
                  and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, cipher.encrypt(token), next, id,
                com.lab.dataworks.auth.TenantScope.current());
        if (published == 0) {
            throw ApiException.notFound("数据服务不存在: " + id);
        }
        sessions.audit(user(http), "SERVICE_PUBLISH", "s" + id,
                "v" + next + (unchanged ? "（内容未变）" : ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("version", next);
        out.put("unchanged", unchanged);
        if (firstPublish) {
            out.put("token", token);
        } else {
            out.put("tokenMasked", maskToken(token));
        }
        return out;
    }

    /**
     * 历史发布版本：按版本倒序，{@code current} 标出线上快照版本。
     *
     * <p>BUG-TC6-3：发布后看不到历史版本，也无从回滚。只回 SQL 预览（200 字符），
     * 完整脚本按版本号走回滚或对外调用链路获取。</p>
     */
    @GetMapping("/api/services/{id}/releases")
    public List<Map<String, Object>> releases(@PathVariable long id) {
        find(id); // 租户/项目归属校验
        return jdbc.queryForList("""
                select r.version, r.method, r.datasource_id, r.published_by, r.published_at,
                       length(r.sql_text) as sql_length,
                       left(r.sql_text, 200) as sql_preview,
                       (r.version = s.version) as current
                  from platform.data_service_release r
                  join platform.data_service s on s.id = r.service_id
                 where r.service_id = ?
                 order by r.version desc
                """, id);
    }

    /**
     * 按历史版本回滚：以旧快照内容生成一个新版本，并把开发态同步成那份内容。
     *
     * <p>回滚的是历史上已发布过的版本，跳过「先在 DEV 跑通」闸门（与工作流 rollback 同口径）。
     * 新版本号 = max+1，不覆盖历史。回滚后草稿 == 最新快照，重复发布走「内容未变」分支。</p>
     */
    @PostMapping("/api/services/{id}/rollback/{version}")
    @RequireAtom("service:publish")
    public Map<String, Object> rollback(@PathVariable long id, @PathVariable int version,
                                        HttpServletRequest http) {
        Map<String, Object> svc = find(id);
        List<Map<String, Object>> found = jdbc.queryForList("""
                select * from platform.data_service_release
                 where service_id = ? and version = ?
                """, id, version);
        if (found.isEmpty()) {
            throw ApiException.notFound("版本不存在: v" + version);
        }
        Map<String, Object> old = found.get(0);
        Object datasourceId = old.get("datasource_id");
        // 快照里的数据源必须仍属于该服务所在项目：数据源被删/被移走后回滚，
        // 线上会立刻变成一个跑不起来的服务（调用才报错，比现在拒绝更贵）。
        if (datasourceId != null) {
            Long ok = jdbc.queryForObject("""
                    select count(*) from platform.datasource where id = ? and project_id = ?
                    """, Long.class, datasourceId, svc.get("project_id"));
            if (ok == null || ok == 0) {
                throw ApiException.conflict("版本 v" + version
                        + " 引用的数据源已不存在或已移出本项目，不能回滚");
            }
        }
        Integer max = jdbc.queryForObject("""
                select coalesce(max(version), 0) from platform.data_service_release
                 where service_id = ?
                """, Integer.class, id);
        int next = (max == null ? 0 : max) + 1;
        try {
            jdbc.update("""
                    insert into platform.data_service_release
                        (service_id, version, method, datasource_id, sql_text, params_json,
                         published_at, published_by)
                    values (?, ?, ?, ?, ?, ?, now(), ?)
                    """, id, next, old.get("method"), datasourceId, old.get("sql_text"),
                    old.get("params_json"), user(http));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("已有并发发布（版本冲突），请刷新后重试");
        }
        int updated = jdbc.update("""
                update platform.data_service
                   set method = ?, datasource_id = ?, sql_text = ?, params_json = ?,
                       status = 'PUBLISHED', version = ?, published_at = now(), updated_at = now()
                 where id = ?
                   and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, old.get("method"), datasourceId, old.get("sql_text"),
                old.get("params_json"), next, id, com.lab.dataworks.auth.TenantScope.current());
        if (updated == 0) {
            throw ApiException.notFound("数据服务不存在: " + id);
        }
        sessions.audit(user(http), "SERVICE_ROLLBACK", "s" + id,
                "to v" + version + " as v" + next);
        return Map.of("ok", true, "version", next, "rolledBackTo", version);
    }

    @PostMapping("/api/services/{id}/offline")
    @RequireAtom("service:publish")
    public Map<String, Object> offline(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        // S12 + S13：SERVICE_OFFLINE 原来先做后记且 detail=null——看不出下线的是
        // 哪个服务、从什么状态、下线的是第几版快照。先记 PENDING 再执行，失败回填 FAILED。
        String detail = "id=" + id + " code=" + row.get("code") + " name=" + row.get("name")
                + " 原状态=" + row.get("status") + " 发布版本=v" + row.get("version");
        return sessions.auditAround(user(http), "SERVICE_OFFLINE", "s" + id,
                detail, AuthInterceptor.clientIp(http), () -> {
                    int offlined = jdbc.update("""
                            update platform.data_service set status = 'OFFLINE', updated_at = now()
                             where id = ?
                               and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                            """, id, com.lab.dataworks.auth.TenantScope.current());
                    if (offlined == 0) {
                        throw ApiException.notFound("数据服务不存在: " + id);
                    }
                    return Map.of("ok", true);
                });
    }

    @PostMapping("/api/services/{id}/token")
    @RequireAtom("service:token")
    public Map<String, Object> rotateToken(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> row = find(id);
        String token = newToken();
        // S12 + S13：SERVICE_TOKEN_ROTATE 原来先做后记且 detail=null——轮换是凭据类
        // 破坏性动作，必须能回答「什么时候换的、换成了哪个」。detail 只记脱敏指纹，
        // 不记明文/token 密文；先记 PENDING 再执行，失败回填 FAILED。
        String detail = "id=" + id + " code=" + row.get("code") + " status=" + row.get("status");
        return sessions.auditAround(user(http), "SERVICE_TOKEN_ROTATE", "s" + id,
                detail, AuthInterceptor.clientIp(http), () -> {
                    int rotated = jdbc.update("""
                            update platform.data_service set api_token = ?, updated_at = now()
                             where id = ?
                               and project_id in (select p.id from platform.project p where p.tenant_id = ?)
                            """, cipher.encrypt(token), id, com.lab.dataworks.auth.TenantScope.current());
                    if (rotated == 0) {
                        throw ApiException.notFound("数据服务不存在: " + id);
                    }
                    return Map.of("ok", true, "token", token);
                },
                out -> "新 token 指纹=" + maskToken(String.valueOf(out.get("token"))));
    }

    /** 不落库的表单测试：用当前表单内容直接执行。 */
    @PostMapping("/api/services/test")
    @RequireAtom("service:write")
    public Map<String, Object> test(@RequestBody TestRequest req, HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.datasourceId() == null) {
            throw ApiException.badRequest("请选择数据源");
        }
        if (req.projectId() == null) {
            throw ApiException.badRequest("请选择项目");
        }
        // 数据源必须属于该项目，不能拿别的项目的连接串执行 SQL
        checkDatasource(req.projectId(), req.datasourceId());
        Map<String, Object> ds = findDatasource(req.datasourceId());
        QueryResult result = execute(String.valueOf(ds.get("jdbc_url")), String.valueOf(ds.get("username")),
                cipher.decrypt(String.valueOf(ds.get("password"))), req.sqlText(), req.params(), req.values());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("columns", result.columns());
        out.put("rows", result.rows());
        out.put("rowCount", result.rows().size());
        out.put("truncated", result.truncated());
        out.put("elapsedMs", result.elapsedMs());
        sessions.audit(user(http), "SERVICE_TEST", req.datasourceId().toString(), null);
        return out;
    }

    @GetMapping("/api/services/{id}/calls")
    public List<Map<String, Object>> calls(@PathVariable long id, @RequestParam(defaultValue = "50") int limit) {
        // 调用日志含 IP 与错误原文，同样不能跨租户读
        find(id);
        return jdbc.queryForList("""
                select id, caller, ip, status_code, rows_returned, elapsed_ms, error, created_at
                from platform.data_service_call where service_id = ?
                order by id desc limit ?
                """, id, Math.min(Math.max(limit, 1), 200));
    }

    // ---------- 对外调用 ----------

    @RequestMapping(value = "/api/ds/{code}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> call(@PathVariable String code,
                                                    HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        long start = System.currentTimeMillis();
        Map<String, Object> values = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (!"token".equals(k) && v.length > 0) values.put(k, v[0]);
        });
        if (body != null) {
            Object nested = body.get("params");
            if (nested instanceof Map<?, ?> map) {
                map.forEach((k, v) -> values.put(String.valueOf(k), v));
            } else {
                values.putAll(body);
            }
        }
        String ip = AuthInterceptor.clientIp(request);
        Map<String, Object> svc = null;
        try {
            svc = jdbc.queryForMap("""
                    select s.id, s.project_id, s.code, s.name, s.status,
                           p.tenant_id as tenant_code, s.created_by,
                           s.api_token, rel.version as release_version, rel.method,
                           rel.datasource_id, rel.sql_text, rel.params_json,
                           d.jdbc_url, d.username as ds_user, d.password as ds_password
                    from platform.data_service s
                    join platform.project p on p.id = s.project_id
                    left join lateral (
                        select * from platform.data_service_release r
                        where r.service_id = s.id order by r.version desc limit 1
                    ) rel on true
                    left join platform.datasource d on d.id = rel.datasource_id
                    where s.code = ?
                    """, code);
        } catch (Exception ignored) {
            // 下面统一按 404 返回
        }
        if (svc == null) {
            throw new ApiException(404, "服务不存在: " + code);
        }
        long serviceId = ((Number) svc.get("id")).longValue();
        if (!"PUBLISHED".equals(svc.get("status")) || svc.get("release_version") == null) {
            record(serviceId, tenantOf(svc), code, "api:" + code, ip, 403, 0, System.currentTimeMillis() - start,
                    "服务未发布");
            throw new ApiException(403, "服务未发布或已下线: " + code);
        }
        String token = tokenFrom(request);
        String expected = svc.get("api_token") == null
                ? null : cipher.decrypt(String.valueOf(svc.get("api_token")));
        if (token == null || expected == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            record(serviceId, tenantOf(svc), code, "api:" + code, ip, 401, 0, System.currentTimeMillis() - start,
                    "Token 无效");
            throw new ApiException(401, "Token 无效，请在请求头传 X-API-Token");
        }
        // D23：发布快照里声明的 method 属于对外契约，必须校验。
        // 路由同时接受 GET/POST（POST 才能带 JSON body），原来不看声明值，
        // 于是「声明 GET」的服务用 POST 也 200——网关/缓存按 GET 配的策略会漏掉这条路径。
        // 放在 Token 校验之后：未鉴权的调用方仍然只看到 401，不能借 405 探测服务的声明方式。
        String mismatch = methodMismatch(svc.get("method") == null
                ? null : String.valueOf(svc.get("method")), request.getMethod());
        if (mismatch != null) {
            record(serviceId, tenantOf(svc), code, "api:" + code, ip, 405, 0,
                    System.currentTimeMillis() - start, mismatch);
            sessions.audit("api:" + code, "SERVICE_CALL", code, mismatch, "FAILED", ip);
            throw new ApiException(405, "服务 " + code + "：" + mismatch);
        }
        if (svc.get("jdbc_url") == null) {
            record(serviceId, tenantOf(svc), code, "api:" + code, ip, 400, 0, System.currentTimeMillis() - start,
                    "服务未绑定数据源");
            throw new ApiException(400, "服务未绑定数据源");
        }
        try {
            List<ParamSpec> params = parseParams((String) svc.get("params_json"));
            QueryResult result = execute(String.valueOf(svc.get("jdbc_url")),
                    String.valueOf(svc.get("ds_user")),
                    cipher.decrypt(String.valueOf(svc.get("ds_password"))),
                    String.valueOf(svc.get("sql_text")), params, values);
            // 数据出口统一过分级网关：API Token 没有「使用者」，按服务创建者（created_by）
            // 的身份判权——服务能取到什么数据，不能超过创建者本人能看的范围。
            List<Map<String, Object>> rows = guarded(svc, result,
                    String.valueOf(svc.get("created_by")));
            long elapsed = System.currentTimeMillis() - start;
            record(serviceId, tenantOf(svc), code, "api:" + code, ip, 200, rows.size(), elapsed, null);
            sessions.audit("api:" + code, "SERVICE_CALL", code, "rows=" + rows.size(),
                    "SUCCESS", ip);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("columns", result.columns());
            out.put("rows", rows);
            out.put("rowCount", rows.size());
            out.put("truncated", result.truncated());
            out.put("elapsedMs", elapsed);
            return ResponseEntity.ok(out);
        } catch (ApiException e) {
            record(serviceId, tenantOf(svc), code, "api:" + code, ip, e.getStatus(), 0,
                    System.currentTimeMillis() - start, e.getMessage());
            sessions.audit("api:" + code, "SERVICE_CALL", code, e.getMessage(), "FAILED", ip);
            throw e;
        } catch (Exception e) {
            record(serviceId, tenantOf(svc), code, "api:" + code, ip, 500, 0,
                    System.currentTimeMillis() - start, e.getMessage());
            sessions.audit("api:" + code, "SERVICE_CALL", code, e.getMessage(), "FAILED", ip);
            throw new ApiException(500, "SQL 执行失败: " + e.getMessage());
        }
    }

    /**
     * 内部调用（MCP data.* 工具）：跳过每服务 API Token，由内部凭证与租户/用户审计兜底。
     * 仍然只允许已发布服务，并复用同一套只读校验与调用记录。
     */
    public Map<String, Object> callInternal(String code, Map<String, Object> values, String caller) {
        long start = System.currentTimeMillis();
        Map<String, Object> svc;
        try {
            svc = jdbc.queryForMap("""
                    select s.id, s.project_id, s.code, s.name, s.status,
                           p.tenant_id as tenant_code, s.created_by,
                           s.api_token, rel.version as release_version, rel.method,
                           rel.datasource_id, rel.sql_text, rel.params_json,
                           d.jdbc_url, d.username as ds_user, d.password as ds_password
                    from platform.data_service s
                    join platform.project p on p.id = s.project_id
                    left join lateral (
                        select * from platform.data_service_release r
                        where r.service_id = s.id order by r.version desc limit 1
                    ) rel on true
                    left join platform.datasource d on d.id = rel.datasource_id
                    where s.code = ?
                    """, code);
        } catch (Exception e) {
            throw new ApiException(404, "服务不存在: " + code);
        }
        long serviceId = ((Number) svc.get("id")).longValue();
        if (!"PUBLISHED".equals(svc.get("status")) || svc.get("release_version") == null) {
            record(serviceId, tenantOf(svc), code, caller, "internal", 403, 0,
                    System.currentTimeMillis() - start, "服务未发布");
            throw new ApiException(403, "服务未发布或已下线: " + code);
        }
        if (svc.get("jdbc_url") == null) {
            record(serviceId, tenantOf(svc), code, caller, "internal", 400, 0,
                    System.currentTimeMillis() - start, "服务未绑定数据源");
            throw new ApiException(400, "服务未绑定数据源");
        }
        try {
            List<ParamSpec> params = parseParams((String) svc.get("params_json"));
            Map<String, Object> input = values == null ? Map.of() : values;
            QueryResult result = execute(String.valueOf(svc.get("jdbc_url")),
                    String.valueOf(svc.get("ds_user")),
                    cipher.decrypt(String.valueOf(svc.get("ds_password"))),
                    String.valueOf(svc.get("sql_text")), params, input);
            // 助手/MCP 走内部凭证，但数据出口同样按调用者（caller）的身份脱敏
            List<Map<String, Object>> guardedRows =
                    guarded(svc, result, callerPrincipal(caller));
            long elapsed = System.currentTimeMillis() - start;
            // data.query_page：在已取回的有界结果集上分页（最多 MAX_ROWS 行）。
            // D12：page 用 int 直接乘会在超大值上溢出成负数，subList 抛
            // IndexOutOfBounds → 落到通用 409「违反唯一性或外键约束」，完全误导。
            // 这里用 long 计算再夹紧，超出总页数返回空列表。
            int page = Math.max(1, intValue(input.get("page"), 1));
            int pageSize = Math.max(1, Math.min(500, intValue(input.get("pageSize"), 50)));
            List<Map<String, Object>> all = guardedRows;
            long rawFrom = (long) (page - 1) * pageSize;
            int from = rawFrom >= all.size() ? all.size() : (int) rawFrom;
            int to = Math.min(all.size(), from + pageSize);
            List<Map<String, Object>> pageRows = all.subList(from, to);
            record(serviceId, tenantOf(svc), code, caller, "internal", 200, pageRows.size(), elapsed, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("code", code);
            out.put("columns", result.columns());
            out.put("rows", pageRows);
            out.put("rowCount", pageRows.size());
            out.put("totalRows", all.size());
            out.put("page", page);
            out.put("pageSize", pageSize);
            out.put("truncated", result.truncated());
            out.put("elapsedMs", elapsed);
            return out;
        } catch (ApiException e) {
            record(serviceId, tenantOf(svc), code, caller, "internal", e.getStatus(), 0,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        } catch (Exception e) {
            record(serviceId, tenantOf(svc), code, caller, "internal", 500, 0,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new ApiException(500, "SQL 执行失败: " + e.getMessage());
        }
    }

    // ---------- 执行与校验 ----------

    /**
     * 数据服务结果的统一出口检查：分级判定 + 字段脱敏。
     *
     * <p>外部调用方只有 API Token，没有用户身份，因此按服务创建者 {@code created_by}
     * 判权；助手/MCP 的内部调用有真实调用者（{@code agent:用户名}），按调用者判权。
     * 租户从服务自身取，不能依赖当前线程上下文（{@code /api/ds/*} 不走登录拦截器）。</p>
     */
    private List<Map<String, Object>> guarded(Map<String, Object> svc, QueryResult result,
                                              String principal) {
        String tenant = svc.get("tenant_code") == null
                ? com.lab.dataworks.auth.TenantScope.DEFAULT
                : String.valueOf(svc.get("tenant_code"));
        com.lab.dataworks.auth.TenantScope.set(tenant);
        try {
            // v483：有 project_id 时按项目策略判定（服务本身挂在项目下）。
            Object rawProject = svc.get("project_id");
            Long projectId = rawProject instanceof Number n ? n.longValue() : null;
            String svcSql = String.valueOf(svc.get("sql_text"));
            com.lab.dataworks.security.DataGuardService.Guard guard =
                    dataGuard.inspect(principal, svcSql, projectId);
            dataGuard.logDecision(principal, guard, null, "data-service");
            if (guard.systemBlocked()) {
                throw new ApiException(403,
                        "系统库仅平台管理员可读；审计数据请使用平台审计接口 /api/security/audit。");
            }
            if (guard.blocked()) {
                throw ApiException.badRequest(
                        "服务 SQL 无法安全解析，已按安全策略拒绝；请改写成显式表名");
            }
            if (guard.denied()) {
                throw new ApiException(403, "服务涉及的数据表超出创建者可访问范围（"
                        + String.join("、", guard.wouldDeny()) + "），请调整 SQL 或申请授权");
            }
            // TC7-G6：带服务定义的原始 SQL 回溯结果列来源，改名列不再漏遮
            return dataGuard.maskMaps(principal, guard, result.columns(), result.rows(),
                    projectId, svcSql);
        } finally {
            com.lab.dataworks.auth.TenantScope.clear();
        }
    }

    /** 内部调用方形如 {@code agent:admin}，取冒号后的用户名做权限点解析。 */
    private static String callerPrincipal(String caller) {
        if (caller == null || caller.isBlank()) {
            return "agent";
        }
        int idx = caller.indexOf(':');
        return idx < 0 ? caller : caller.substring(idx + 1);
    }

    record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated, long elapsedMs) {
    }

    static QueryResult execute(String url, String user, String password, String rawSql,
                                List<ParamSpec> params, Map<String, Object> values) {
        url = JdbcUrlGuard.validate(url);
        String sql = checkReadOnly(rawSql);
        List<ParamSpec> specs = params == null ? List.of() : params;
        List<String> order = new ArrayList<>();
        String prepared = rewrite(sql, order);
        List<Object> args = new ArrayList<>();
        for (String name : order) {
            ParamSpec spec = specs.stream()
                    .filter(p -> p.name() != null && p.name().equals(name))
                    .findFirst().orElse(null);
            Object value = values == null ? null : values.get(name);
            if (value == null || String.valueOf(value).isBlank()) {
                value = spec == null ? null : spec.defaultValue();
            }
            if ((value == null || String.valueOf(value).isBlank())
                    && spec != null && Boolean.TRUE.equals(spec.required())) {
                throw ApiException.badRequest("缺少必填参数: " + name);
            }
            args.add(coerce(value, spec == null ? "STRING" : spec.type()));
        }
        long start = System.currentTimeMillis();
        try {
            DriverManager.setLoginTimeout(5);
            Properties props = new Properties();
            props.setProperty("user", user);
            props.setProperty("password", password == null ? "" : password);
            // 单位按驱动不同：PG 是秒、MySQL 是毫秒（用错等于没超时）
            com.lab.dataworks.common.JdbcTimeouts.apply(props, url, 5, 30);
            try (Connection conn = DriverManager.getConnection(url, props)) {
                try {
                    conn.setReadOnly(true);
                } catch (Exception ignored) {
                    // 部分驱动（Doris/MySQL）不支持，忽略
                }
                try (PreparedStatement ps = conn.prepareStatement(prepared)) {
                    ps.setQueryTimeout(15);
                    ps.setMaxRows(MAX_ROWS + 1);
                    for (int i = 0; i < args.size(); i++) {
                        ps.setObject(i + 1, args.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int count = meta.getColumnCount();
                        List<String> columns = new ArrayList<>();
                        for (int i = 1; i <= count; i++) {
                            columns.add(meta.getColumnLabel(i));
                        }
                        List<Map<String, Object>> rows = new ArrayList<>();
                        boolean truncated = false;
                        while (rs.next()) {
                            if (rows.size() >= MAX_ROWS) {
                                truncated = true;
                                break;
                            }
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= count; i++) {
                                row.put(columns.get(i - 1), plain(rs.getObject(i)));
                            }
                            rows.add(row);
                        }
                        return new QueryResult(columns, rows, truncated,
                                System.currentTimeMillis() - start);
                    }
                }
            }
        } catch (SQLException e) {
            // 超时不是调用方的「请求格式错」：原来统一报 400 + 驱动的英文原文
            // （Doris 实测 15 秒后 `Statement cancelled due to timeout or client request`），
            // 调用方会以为参数写错了。单列 504 + 中文提示，便于重试与排障。
            String text = e.getMessage() == null ? "" : e.getMessage();
            String lower = text.toLowerCase(Locale.ROOT);
            String state = e.getSQLState() == null ? "" : e.getSQLState();
            if (e instanceof SQLTimeoutException || "HYT00".equals(state) || "HYT01".equals(state)
                    || lower.contains("timeout") || lower.contains("timed out")
                    || text.contains("Statement cancelled")) {
                throw new ApiException(504,
                        "查询超时（超过 15 秒），请缩小查询范围或增加过滤条件后重试");
            }
            throw new ApiException(400, "SQL 执行失败: " + text);
        }
    }

    /**
     * 只允许单条 SELECT / WITH，防止把写操作发布成接口。
     *
     * <p>复用集成模块的只读守卫：它会同时按 MySQL / PostgreSQL 两种字符串与注释
     * 语义检查，并扫描 {@code "pg_read_file"(...)} 这类带副作用的函数调用。
     * 不再维护第二份关键字黑名单（两份规则必然漂移）。</p>
     */
    static String checkReadOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        String sql = raw.trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        com.lab.dataworks.integration.SqlTemplates.guardReadOnly(sql, "数据服务");
        return sql;
    }

    /**
     * 把注释、单引号字符串、双引号标识符、美元引用字符串替换成空格，
     * 只保留可执行的 SQL 结构，用于关键字检查。
     */
    static String codeOnly(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '"') {
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '"') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length()
                        && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, sql.length());
                out.append(' ');
            } else if (c == '$') {
                int j = i + 1;
                while (j < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                if (j < sql.length() && sql.charAt(j) == '$') {
                    String tag = sql.substring(i, j + 1);
                    int end = sql.indexOf(tag, j + 1);
                    if (end >= 0) {
                        i = end + tag.length();
                        out.append(' ');
                        continue;
                    }
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 把 :name 命名参数改成 ?，并按出现顺序记录参数名。
     * 跳过字符串字面量与 PostgreSQL 的 :: 类型转换。
     */
    static String rewrite(String sql, List<String> order) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            if (c == ':' && !inSingle && !inDouble) {
                boolean cast = i + 1 < sql.length() && sql.charAt(i + 1) == ':';
                boolean prevColon = i > 0 && sql.charAt(i - 1) == ':';
                if (!cast && !prevColon && i + 1 < sql.length()
                        && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                    int j = i + 1;
                    while (j < sql.length()
                            && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                        j++;
                    }
                    order.add(sql.substring(i + 1, j));
                    out.append('?');
                    i = j - 1;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private int intValue(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Object coerce(Object value, String type) {
        if (value == null) {
            return null;
        }
        String t = type == null ? "STRING" : type.toUpperCase(Locale.ROOT);
        try {
            return switch (t) {
                case "INT" -> Integer.valueOf(String.valueOf(value));
                case "LONG" -> Long.valueOf(String.valueOf(value));
                case "DECIMAL" -> new BigDecimal(String.valueOf(value));
                case "BOOL" -> toBoolean(value);
                // PostgreSQL 不会把 varchar 隐式转成 date，日期列必须按 DATE 绑定，
                // 否则会报 "operator does not exist: date = character varying"
                case "DATE" -> java.sql.Date.valueOf(String.valueOf(value));
                case "DATETIME" -> Timestamp.valueOf(
                        String.valueOf(value).replace('T', ' ').length() == 16
                                ? String.valueOf(value).replace('T', ' ') + ":00"
                                : String.valueOf(value).replace('T', ' '));
                default -> String.valueOf(value);
            };
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("参数类型不匹配: " + value + " 不能转成 " + t);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("参数格式不匹配: " + value + " 不能转成 " + t);
        }
    }

    /** true 的写法（小写比较）：JSON 布尔、true/on/y/yes/1 */
    private static final Set<String> TRUE_WORDS = Set.of("true", "on", "y", "yes", "1");
    /** false 的写法：JSON 布尔、false/off/n/no/0 */
    private static final Set<String> FALSE_WORDS = Set.of("false", "off", "n", "no", "0");

    /**
     * D11：BOOL 参数只认真正的布尔写法。
     *
     * <p>原来用 {@code Boolean.valueOf(String)}——它对任何非法字符串都返回 {@code false}
     * 且不抛异常，所以 {@code flag=not-a-bool}、{@code flag=真} 都会静默按 false 执行，
     * 调用方拿到一份「过滤条件全关」的结果集却以为参数生效了。INT / DATE 分支本来就是
     * 非法值 400，这里对齐同一口径：能识别的写法才转换，其余 400「参数类型不匹配」。</p>
     *
     * <p>接受的写法按 Web 框架惯例（Spring relaxed binding 同一组）：
     * {@code true/on/y/yes/1} 与 {@code false/off/n/no/0}，忽略大小写与首尾空格。</p>
     */
    static Object toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (TRUE_WORDS.contains(text)) {
            return Boolean.TRUE;
        }
        if (FALSE_WORDS.contains(text)) {
            return Boolean.FALSE;
        }
        throw ApiException.badRequest("参数类型不匹配: " + value + " 不能转成 BOOL");
    }

    /**
     * D23：发布快照里声明的调用方式必须与实际请求方法一致。
     *
     * <p>路由 {@code /api/ds/{code}} 同时接受 GET 与 POST（POST 才能带 JSON body），
     * 原来不看声明值，于是「声明 GET」的服务用 POST 也 200——对外契约与发布快照不一致，
     * 网关/缓存按 GET 配的策略会漏掉这条路径。</p>
     *
     * @return {@code null} 表示通过；否则是可直接回给调用方的中文原因
     */
    static String methodMismatch(String declaredMethod, String actualMethod) {
        String declared = declaredMethod == null
                ? "" : declaredMethod.trim().toUpperCase(Locale.ROOT);
        if (declared.isEmpty()) {
            // 历史发布记录没有 method 值：不拦，避免把存量服务打断
            return null;
        }
        String actual = actualMethod == null ? "" : actualMethod.trim().toUpperCase(Locale.ROOT);
        return declared.equals(actual) ? null
                : "声明的调用方式是 " + declared + "，不支持 "
                  + (actual.isEmpty() ? "未知方法" : actual);
    }

    static Object plain(Object value) throws SQLException {
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString().replace('T', ' ');
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof byte[]) {
            return "<binary>";
        }
        return value;
    }

    private void validate(ServiceRequest req) {
        validate(req, true);
    }

    /** REQ-DW-9：创建时编码可留空（自动生成），更新必须显式带原编码。 */
    private void validate(ServiceRequest req, boolean codeRequired) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (req.projectId() == null) {
            throw ApiException.badRequest("请选择项目");
        }
        String code = req.code() == null ? "" : req.code().trim();
        if (code.isEmpty()) {
            if (codeRequired) {
                throw ApiException.badRequest("服务编码不能为空");
            }
        } else if (!code.matches("[A-Za-z][A-Za-z0-9_]{1,63}")) {
            throw ApiException.badRequest("服务编码必须以字母开头，只含字母/数字/下划线，2-64 位");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("服务名称不能为空");
        }
        if (req.name().trim().length() > 128) {
            throw ApiException.badRequest("服务名称不能超过 128 个字符");
        }
        if (req.method() == null || !METHODS.contains(req.method().toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("请求方法只能是 GET / POST");
        }
        checkReadOnly(req.sqlText());
        Set<String> paramNames = new HashSet<>();
        for (ParamSpec p : req.params() == null ? List.<ParamSpec>of() : req.params()) {
            if (p.name() == null || !p.name().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw ApiException.badRequest("参数名不合法: " + p.name());
            }
            if (!paramNames.add(p.name())) {
                throw ApiException.badRequest("参数名重复: " + p.name());
            }
            String type = p.type() == null ? "STRING" : p.type().toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw ApiException.badRequest(
                        "参数类型只能是 STRING / INT / LONG / DECIMAL / BOOL / DATE / DATETIME");
            }
        }
    }

    /**
     * REQ-DW-9：编码留空时按规则自动生成（svc_yyyyMMdd_6位随机），对全局唯一键
     * {@code uk_data_service_code} 查重、最多重试 5 次；显式传入的编码原样返回（正则已在 validate 校验）。
     */
    private String resolveCode(String input, String prefix) {
        if (input != null && !input.isBlank()) {
            return input.trim();
        }
        for (int i = 0; i < 5; i++) {
            String candidate = AutoCode.generate(prefix);
            Long n = jdbc.queryForObject(
                    "select count(*) from platform.data_service where code = ?", Long.class, candidate);
            if (n == null || n == 0) {
                return candidate;
            }
        }
        throw ApiException.conflict("自动生成服务编码失败，请重试");
    }

    private void checkCodeUnique(long projectId, String code, Long excludeId) {
        Long n = excludeId == null
                ? jdbc.queryForObject("""
                        select count(*) from platform.data_service where project_id = ? and code = ?
                        """, Long.class, projectId, code.trim())
                : jdbc.queryForObject("""
                        select count(*) from platform.data_service
                        where project_id = ? and code = ? and id <> ?
                        """, Long.class, projectId, code.trim(), excludeId);
        if (n != null && n > 0) {
            throw ApiException.conflict("服务编码已存在: " + code);
        }
        // 对外 URL 是 /api/ds/{code}，必须全局唯一，否则无法确定调用哪个服务
        Long global = excludeId == null
                ? jdbc.queryForObject("""
                        select count(*) from platform.data_service where code = ?
                        """, Long.class, code.trim())
                : jdbc.queryForObject("""
                        select count(*) from platform.data_service where code = ? and id <> ?
                        """, Long.class, code.trim(), excludeId);
        if (global != null && global > 0) {
            throw ApiException.conflict("服务编码已被其他项目使用（调用地址全局唯一）: " + code);
        }
    }

    private void requireProject(long projectId) {
        // 只校验「项目存在」等于允许往别人租户的项目里塞服务（P0-3 第 5 步）
        Long n = jdbc.queryForObject("""
                select count(*) from platform.project where id = ? and tenant_id = ?
                """, Long.class, projectId, com.lab.dataworks.auth.TenantScope.current());
        if (n == null || n == 0) {
            throw ApiException.notFound("项目不存在: " + projectId);
        }
    }

    private void checkDatasource(long projectId, Long datasourceId) {
        if (datasourceId == null) {
            throw ApiException.badRequest("请选择数据源");
        }
        Long n = jdbc.queryForObject("""
                select count(*) from platform.datasource where id = ? and project_id = ?
                """, Long.class, datasourceId, projectId);
        if (n == null || n == 0) {
            throw ApiException.badRequest("数据源不属于该项目: " + datasourceId);
        }
    }

    private Map<String, Object> findDatasource(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select jdbc_url, username, password from platform.datasource where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> find(long id) {
        // 按项目归属判定：update/delete/offline/token/smoke/publish 全部走这里，
        // 一处补齐就堵住整族 by-id 越权（v447 P0-3）。
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.*, s.tenant_id as tenant_code from platform.data_service s
                 where s.id = ?
                   and s.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                """, id, com.lab.dataworks.auth.TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据服务不存在: " + id);
        }
        return rows.get(0);
    }

    /** 生产态快照：对外调用只读这一份。 */
    private Map<String, Object> latestRelease(long serviceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from platform.data_service_release
                where service_id = ? order by version desc limit 1
                """, serviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 开发态内容指纹（方法 + 数据源 + SQL + 参数定义）。
     * 任一项变化，之前的 DEV 跑通就失效，必须重新测试才能发布。
     */
    private static String contentHash(Object method, Object datasourceId,
                                      Object sqlText, Object paramsJson) {
        String raw = String.valueOf(method) + "\n" + String.valueOf(datasourceId) + "\n"
                + String.valueOf(sqlText) + "\n" + String.valueOf(paramsJson);
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private void decorate(Map<String, Object> row) {
        String draftHash = contentHash(row.get("method"), row.get("datasource_id"),
                row.get("sql_text"), row.get("params_json"));
        String releaseHash = null;
        if (row.get("release_sql_text") != null) {
            releaseHash = contentHash(row.remove("release_method"),
                    row.remove("release_datasource_id"),
                    row.remove("release_sql_text"), row.remove("release_params_json"));
        }
        boolean published = "PUBLISHED".equals(String.valueOf(row.get("status")));
        row.put("draft_dirty", published && releaseHash != null && !draftHash.equals(releaseHash));
        row.put("smoke_ok", draftHash.equals(row.get("smoke_hash")));

        row.put("params", parseParams((String) row.remove("params_json")));
        // 列表里不把完整 Token 带出去，详情与发布返回才带
        String stored = (String) row.remove("api_token");
        if (stored != null && !stored.isBlank()) {
            String token = cipher.decrypt(stored);
            row.put("token_masked", token.length() <= 10
                    ? "…"
                    : token.substring(0, 6) + "…" + token.substring(token.length() - 4));
        }
    }

    private List<ParamSpec> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<ParamSpec>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String paramsJson(List<ParamSpec> params) {
        try {
            return mapper.writeValueAsString(params == null ? List.of() : params);
        } catch (Exception e) {
            throw ApiException.badRequest("参数定义无法序列化");
        }
    }

    private String tokenFrom(HttpServletRequest request) {
        String header = request.getHeader("X-API-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return request.getParameter("token");
    }

    private static String maskToken(String token) {
        return token.length() <= 10
                ? "…" : token.substring(0, 6) + "…" + token.substring(token.length() - 4);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void record(long serviceId, String tenant, String code, String caller, String ip,
                        int status, int rows, long elapsed, String error) {
        try {
            // caller/ip 是 varchar(64)：超长会让 insert 失败并被吞掉，
            // 调用记录、24h 统计、安全中心都会静默缺失。
            jdbc.update("""
                    insert into platform.data_service_call
                        (service_id, service_code, caller, ip, status_code, rows_returned,
                         elapsed_ms, error, tenant_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, serviceId, cut(code, 64), cut(caller, 64), cut(ip, 64), status, rows, elapsed,
                    error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error,
                    tenant);
        } catch (Exception e) {
            // 调用日志失败不能影响接口本身，但不能再静默
            org.slf4j.LoggerFactory.getLogger(DataServiceController.class)
                    .warn("record data service call failed service={}: {}", code, e.getMessage());
        }
    }

    /** 调用记录的租户：取服务所属项目的租户（服务删除后历史也留在正确租户下）。 */
    private static String tenantOf(Map<String, Object> svc) {
        Object value = svc == null ? null : svc.get("tenant_code");
        return value == null || String.valueOf(value).isBlank()
                ? com.lab.dataworks.auth.TenantScope.DEFAULT : String.valueOf(value);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String user(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }
}
