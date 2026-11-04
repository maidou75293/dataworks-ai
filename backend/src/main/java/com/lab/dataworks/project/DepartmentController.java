package com.lab.dataworks.project;

import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 部门管理：部门编码 / 部门ID / 表名前缀用于生成项目编码与 Doris 库名。
 *
 * <p>项目编码 = 部门编码_部门ID_业务英文名（gx_01_gongxiang）；
 * Doris 库 = 业务英文名_dev / 业务英文名_prd。
 */
@RestController
@RequestMapping("/api/departments")
@RequireAtom("project:read")
public class DepartmentController {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9]{1,7}$");
    private static final Pattern DEPT_ID = Pattern.compile("^[0-9]{1,8}$");
    private static final Pattern TABLE_PREFIX = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    private final JdbcTemplate jdbc;
    private final SessionService sessions;

    public DepartmentController(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    public record DepartmentRequest(String code, String deptId, String name,
                                    String tablePrefix, String ownerUser, String description) {
    }

    private static final String SELECT = """
            select d.id, d.code, d.dept_id, d.name, d.table_prefix, d.owner_user,
                   d.description, d.created_at,
                   (select count(*) from platform.project p where p.department_id = d.id)
                       as project_count
            from platform.department d
            """;

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(SELECT + " order by d.code, d.dept_id");
    }

    @PostMapping
    @RequireAtom("department:manage")
    public Map<String, Object> create(@RequestBody DepartmentRequest req, HttpServletRequest http) {
        requirePlatform();
        Normalized n = normalize(req);
        try {
            jdbc.update("""
                    insert into platform.department (code, dept_id, name, table_prefix, owner_user, description)
                    values (?, ?, ?, ?, ?, ?)
                    """, n.code(), n.deptId(), n.name(), n.tablePrefix(), n.ownerUser(), n.description());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("部门编码或部门ID已存在: " + n.code() + " / " + n.deptId());
        }
        sessions.audit(username(http), "DEPARTMENT_CREATE", n.code() + "_" + n.deptId(), n.name());
        return jdbc.queryForMap(SELECT + " where d.code = ? and d.dept_id = ?", n.code(), n.deptId());
    }

    @PutMapping("/{id}")
    @RequireAtom("department:manage")
    public Map<String, Object> update(@PathVariable long id, @RequestBody DepartmentRequest req,
                                      HttpServletRequest http) {
        requirePlatform();
        Normalized n = normalize(req);
        List<Map<String, Object>> existingRows = jdbc.queryForList(
                "select code, dept_id from platform.department where id = ?", id);
        if (existingRows.isEmpty()) {
            throw ApiException.notFound("部门不存在: " + id);
        }
        Integer used = jdbc.queryForObject(
                "select count(*) from platform.project where department_id = ?", Integer.class, id);
        Map<String, Object> existing = existingRows.get(0);
        // 已有项目时编码/部门ID是项目编码的一部分，改了会让项目编码名不副实
        if (used != null && used > 0
                && (!n.code().equals(existing.get("code")) || !n.deptId().equals(existing.get("dept_id")))) {
            throw ApiException.conflict("部门下已有 " + used + " 个项目，部门编码和部门ID不可修改");
        }
        int updated;
        try {
            updated = jdbc.update("""
                    update platform.department
                       set code = ?, dept_id = ?, name = ?, table_prefix = ?, owner_user = ?, description = ?
                     where id = ?
                    """, n.code(), n.deptId(), n.name(), n.tablePrefix(), n.ownerUser(), n.description(), id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("部门编码或部门ID已存在: " + n.code() + " / " + n.deptId());
        }
        if (updated == 0) {
            throw ApiException.notFound("部门不存在: " + id);
        }
        sessions.audit(username(http), "DEPARTMENT_UPDATE", n.code() + "_" + n.deptId(), n.name());
        return jdbc.queryForMap(SELECT + " where d.id = ?", id);
    }

    @DeleteMapping("/{id}")
    @RequireAtom("department:manage")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        requirePlatform();
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " where d.id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("部门不存在: " + id);
        }
        Integer used = jdbc.queryForObject(
                "select count(*) from platform.project where department_id = ?", Integer.class, id);
        if (used != null && used > 0) {
            throw ApiException.conflict("部门下还有 " + used + " 个项目，先移除或改挂部门后再删");
        }
        jdbc.update("delete from platform.department where id = ?", id);
        sessions.audit(username(http), "DEPARTMENT_DELETE",
                String.valueOf(rows.get(0).get("code")), String.valueOf(rows.get(0).get("name")));
        return Map.of("ok", true);
    }

    /** 部门是平台级组织（项目编码的一部分），只有默认租户可改（v447）。 */
    private void requirePlatform() {
        if (!com.lab.dataworks.common.TenantGuard.platform()) {
            throw new ApiException(403, "部门是平台级配置，只有平台管理员（默认租户）可以修改");
        }
    }

    private Normalized normalize(DepartmentRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String code = trim(req.code()).toLowerCase();
        if (!CODE.matcher(code).matches()) {
            throw ApiException.badRequest("部门编码为 2-8 位小写字母/数字，且以字母开头，例如 gx");
        }
        String deptId = trim(req.deptId());
        if (!DEPT_ID.matcher(deptId).matches()) {
            throw ApiException.badRequest("部门ID为 1-8 位数字，例如 01");
        }
        String name = trim(req.name());
        if (name.isEmpty() || name.length() > 64) {
            throw ApiException.badRequest("部门名称不能为空且不超过 64 个字符");
        }
        String prefix = trim(req.tablePrefix()).toLowerCase();
        if (prefix.isEmpty()) {
            prefix = code + "_";
        }
        if (!prefix.endsWith("_")) {
            prefix = prefix + "_";
        }
        if (!TABLE_PREFIX.matcher(prefix).matches()) {
            throw ApiException.badRequest("表名前缀以小写字母开头，只含小写字母、数字、下划线，例如 gx_");
        }
        String owner = trim(req.ownerUser());
        return new Normalized(code, deptId, name, prefix,
                owner.isEmpty() ? null : owner, trim(req.description()));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String username(HttpServletRequest request) {
        Object value = request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private record Normalized(String code, String deptId, String name, String tablePrefix,
                              String ownerUser, String description) {
    }
}
