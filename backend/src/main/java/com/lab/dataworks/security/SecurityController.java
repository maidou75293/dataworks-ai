package com.lab.dataworks.security;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.lab.dataworks.common.LikePattern;

/**
 * 安全中心：审计日志、在线会话、登录失败与敏感操作统计、数据服务调用审计。
 *
 * <p><b>v448（第二轮审计 P1-1 / P2-2）</b>：这些接口之前是全平台口径，
 * tenant_b 的 ADMIN 能读到 admin 的操作记录、IP、在线会话与全局统计。
 * 现在审计按 {@code audit_log.tenant_id} 收口，会话与统计按成员/项目归属收口；
 * 默认租户（平台自身）仍看全量。</p>
 */
@RestController
@RequestMapping("/api/security")
@RequireAtom("security:read")
public class SecurityController {

    /** 需要重点关注的敏感动作 */
    private static final List<String> SENSITIVE = List.of(
            "PUBLISH_REQUEST", "ROLLBACK", "PROJECT_DELETE", "DATASOURCE_DELETE",
            "SERVICE_PUBLISH", "SERVICE_OFFLINE", "SERVICE_DELETE", "SERVICE_TOKEN_ROTATE");

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final PermissionResolver permissions;

    public SecurityController(JdbcTemplate jdbc, SessionService sessions,
                              PermissionResolver permissions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.permissions = permissions;
    }

    /**
     * D07：吊销他人会话前比较双方在当前租户的权限点集合——
     * 目标账号只要有任一权限点不在调用方集合内，就视为「权限更高」，拒绝。
     * 同权限（集合相等）与更低权限（目标 ⊆ 调用方）放行，自己的会话总是同集合。
     */
    static boolean allowsRevoke(java.util.Set<String> actorAtoms, java.util.Set<String> targetAtoms) {
        return actorAtoms != null && targetAtoms != null && actorAtoms.containsAll(targetAtoms);
    }

    private static boolean platform() {
        return TenantGuard.platform();
    }

    private static String tenant() {
        return TenantScope.current();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        String today = "(date_trunc('day', now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai')";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loginSuccessToday", count("""
                select count(*) from platform.audit_log
                where action = 'LOGIN' and created_at >= """ + today + " and (? or tenant_id = ?)",
                platform(), tenant()));
        out.put("loginFailedToday", count("""
                select count(*) from platform.audit_log
                where action = 'LOGIN_FAILED' and created_at >= """ + today
                + " and (? or tenant_id = ?)", platform(), tenant()));
        String placeholders = String.join(",", java.util.Collections.nCopies(SENSITIVE.size(), "?"));
        Object[] sensitiveArgs = new Object[SENSITIVE.size() + 2];
        SENSITIVE.toArray(sensitiveArgs);
        sensitiveArgs[SENSITIVE.size()] = platform();
        sensitiveArgs[SENSITIVE.size() + 1] = tenant();
        out.put("sensitiveToday", count("""
                select count(*) from platform.audit_log
                where created_at >= """ + today + " and action in (" + placeholders + ")"
                + " and (? or tenant_id = ?)", sensitiveArgs));
        out.put("failedToday", count("""
                select count(*) from platform.audit_log
                where result = 'FAILED' and created_at >= """ + today + " and (? or tenant_id = ?)",
                platform(), tenant()));
        out.put("auditToday", count("""
                select count(*) from platform.audit_log
                where created_at >= """ + today + " and (? or tenant_id = ?)", platform(), tenant()));
        // 在线会话按成员关系收口；平台 staff（同时属于默认租户）的会话只给平台看
        out.put("activeSessions", count("""
                select count(*) from platform.app_session s
                where s.expires_at > now()
                  and (? or (exists (
                          select 1 from platform.app_user u
                            join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                            join platform.tenant t on t.id = tm.tenant_id
                           where u.username = s.username and t.code = ?)
                      and not exists (
                          select 1 from platform.app_user u
                            join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                            join platform.tenant t on t.id = tm.tenant_id
                           where u.username = s.username and t.code = 'default')))
                """, platform(), tenant()));
        out.put("publishedServices", count("""
                select count(*) from platform.data_service s
                 join platform.project p on p.id = s.project_id
                where s.status = 'PUBLISHED' and (? or p.tenant_id = ?)
                """, platform(), tenant()));
        // 调用历史直接按调用发生时记录的 tenant_id 统计：服务删除后历史仍然计入
        out.put("serviceCallsToday", count("""
                select count(*) from platform.data_service_call c
                where c.created_at >= """ + today + " and (? or c.tenant_id = ?)",
                platform(), tenant()));
        out.put("serviceErrorsToday", count("""
                select count(*) from platform.data_service_call c
                where c.status_code >= 400 and c.created_at >= """ + today
                + " and (? or c.tenant_id = ?)", platform(), tenant()));
        // 已加密的密码以 enc:v1: 开头（SecretCipher），只统计没有前缀的明文
        out.put("plaintextPasswords", count("""
                select count(*) from platform.datasource d
                 join platform.project p on p.id = d.project_id
                where d.password is not null and d.password <> ''
                  and d.password not like 'enc:v1:%' and (? or p.tenant_id = ?)
                """, platform(), tenant()));
        return out;
    }

    /** 分页模式下的默认每页条数：沿用本接口旧 {@code limit} 的默认值 100，避免行为突变。 */
    private static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * 审计列表。
     *
     * <p>D19：之前只有 {@code limit}，{@code page/size} 直接被忽略——
     * {@code ?page=1&size=1} 仍然回 100 条，前端换统一分页组件就拿不到总数。
     * 现在按平台契约（{@code page} 从 1、{@code size} 1–500，非法值 400）启用分页，
     * 分页响应带 {@code X-Total-Count}；<b>不带 page/size 时保持旧结构</b>（裸数组 + limit），
     * 安全中心页面（{@code web/src/pages/Security.tsx} 读的就是数组）不受影响。
     * 结构与项目列表 / UDF 资源列表一致：{@code {items,total,page,size}}。</p>
     */
    /** 审计筛选条件（列表与导出共用一份语义，TC7-G3：导出必须跟随筛选，不落口径分裂）。 */
    private static final class Filter {
        final String where;
        final List<Object> args;

        Filter(String where, List<Object> args) {
            this.where = where;
            this.args = args;
        }
    }

    private static Filter auditFilter(String username, String action, String result,
                                      String keyword, Boolean sensitive) {
        StringBuilder where = new StringBuilder(" where (? or tenant_id = ?)");
        List<Object> args = new java.util.ArrayList<>();
        args.add(platform());
        args.add(tenant());
        if (username != null && !username.isBlank()) {
            where.append(" and username ilike ?");
            args.add(LikePattern.contains(username));
        }
        if (action != null && !action.isBlank()) {
            where.append(" and action = ?");
            args.add(action.trim());
        }
        if (result != null && !result.isBlank()) {
            where.append(" and result = ?");
            args.add(result.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (coalesce(target, '') ilike ? or coalesce(detail, '') ilike ? or coalesce(ip, '') ilike ?)");
            String like = LikePattern.contains(keyword);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        // 「今日敏感操作」卡片：按同一份动作白名单过滤
        if (Boolean.TRUE.equals(sensitive)) {
            where.append(" and action in (").append(String.join(",",
                    java.util.Collections.nCopies(SENSITIVE.size(), "?"))).append(")");
            args.addAll(SENSITIVE);
        }
        return new Filter(where.toString(), args);
    }

    @GetMapping("/audit")
    public Object audit(@RequestParam(required = false) String username,
                        @RequestParam(required = false) String action,
                        @RequestParam(required = false) String result,
                        @RequestParam(required = false) String keyword,
                        @RequestParam(required = false) Boolean sensitive,
                        @RequestParam(defaultValue = "100") int limit,
                        @RequestParam(required = false) Integer page,
                        @RequestParam(required = false) Integer size,
                        jakarta.servlet.http.HttpServletResponse response) {
        Filter filter = auditFilter(username, action, result, keyword, sensitive);
        String where = filter.where;
        List<Object> args = filter.args;
        String select = """
                select id, username, action, target, detail, result, ip, actor_type,
                       tenant_id, created_at
                from platform.audit_log""" + where + " order by id desc";

        boolean paged = page != null || size != null;
        if (!paged) {
            // 旧口径：裸数组 + limit（前端仍走这条分支，行为与 v449 一致）
            List<Object> legacyArgs = new java.util.ArrayList<>(args);
            legacyArgs.add(Math.min(Math.max(limit, 1), 500));
            return jdbc.queryForList(select + " limit ?", legacyArgs.toArray());
        }
        // D19：分页参数按平台契约校验，非法值 400，不做静默夹紧
        com.lab.dataworks.common.Paging.requireValid(page, size);
        int p = com.lab.dataworks.common.Paging.pageOf(page);
        int s = com.lab.dataworks.common.Paging.sizeOf(size, DEFAULT_PAGE_SIZE);
        Long total = jdbc.queryForObject("select count(*) from platform.audit_log" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(com.lab.dataworks.common.Paging.offset(p, s));
        List<Map<String, Object>> items = jdbc.queryForList(select + " limit ? offset ?",
                pageArgs.toArray());
        long count = total == null ? items.size() : total;
        response.setHeader("X-Total-Count", String.valueOf(count));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", count);
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    /** 筛选下拉选项：最近 30 天出现过的动作 / 用户（同租户口径）。 */
    @GetMapping("/audit/options")
    public Map<String, Object> auditOptions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actions", jdbc.queryForList("""
                select distinct action from platform.audit_log
                where created_at > now() - interval '30 days' and (? or tenant_id = ?)
                order by action
                """, String.class, platform(), tenant()));
        out.put("users", jdbc.queryForList("""
                select distinct username from platform.audit_log
                where created_at > now() - interval '30 days' and username is not null
                  and (? or tenant_id = ?)
                order by username
                """, String.class, platform(), tenant()));
        return out;
    }

    /**
     * TC7-G3：审计日志导出（CSV/JSON 流式全量）。
     *
     * <p>契约 {@code docs/测试-TC7-G3.md} 第四节：
     * ① {@code format=csv|json} 必填，未知格式 400 可读；
     * ② 筛选参数与 {@code GET /audit} 同名同义（{@link #auditFilter} 共用一份构造）；
     * ③ 租户口径与列表一致（平台全量 / 普通租户只看自己），导出不得越租户；
     * ④ <b>导出动作自身落审计</b>：成功导出记一条 {@code AUDIT_EXPORT}，
     * detail 含本次 format 与筛选范围——没有这条，导出本身就是漏面（卡面原话）；
     * ⑤ 权限点 {@code security:export}（V119），授予面与 security:read 对齐。
     * 游标（id 降序）分块写出，全量不截断；不用分页参数（导出语义=范围内全部）。</p>
     */
    @GetMapping("/audit/export")
    @RequireAtom({"security:read", "security:export"})
    public void exportAudit(@RequestParam(required = false) String username,
                            @RequestParam(required = false) String action,
                            @RequestParam(required = false) String result,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Boolean sensitive,
                            @RequestParam(required = false) String format,
                            HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        String fmt = normalizeExportFormat(format);
        Filter filter = auditFilter(username, action, result, keyword, sensitive);
        String filename = "audit-log-" + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "." + fmt;
        response.setContentType("csv".equals(fmt) ? "text/csv;charset=UTF-8" : "application/json;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        java.io.OutputStream out = response.getOutputStream();
        boolean csv = "csv".equals(fmt);
        long n = 0;
        if (csv) {
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});   // Excel UTF-8 BOM
            out.write((String.join(",", EXPORT_COLS) + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            out.write('[');
        }
        Long cursor = null;
        while (true) {
            List<Object> args = new java.util.ArrayList<>(filter.args);
            String sql = "select id, username, action, target, detail, result, ip, actor_type,"
                    + " tenant_id, created_at from platform.audit_log" + filter.where;
            if (cursor != null) {
                sql += " and id < ?";
                args.add(cursor);
            }
            args.add(EXPORT_CHUNK);
            List<Map<String, Object>> chunk = jdbc.queryForList(sql + " order by id desc limit ?",
                    args.toArray());
            if (chunk.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : chunk) {
                if (csv) {
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < EXPORT_COLS.size(); i++) {
                        if (i > 0) {
                            line.append(',');
                        }
                        line.append(csvCell(row.get(EXPORT_COLS.get(i))));
                    }
                    out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    if (n > 0) {
                        out.write(',');
                    }
                    StringBuilder obj = new StringBuilder(64).append('{');
                    for (int i = 0; i < EXPORT_COLS.size(); i++) {
                        if (i > 0) {
                            obj.append(',');
                        }
                        obj.append('"').append(EXPORT_COLS.get(i)).append("\":")
                                .append(jsonValue(row.get(EXPORT_COLS.get(i))));
                    }
                    out.write(obj.append('}').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                n++;
            }
            cursor = ((Number) chunk.get(chunk.size() - 1).get("id")).longValue();
            if (chunk.size() < EXPORT_CHUNK) {
                break;
            }
        }
        if (!csv) {
            out.write(']');
        }
        out.flush();
        String scope = "username=" + nz(username) + ",action=" + nz(action) + ",result=" + nz(result)
                + ",keyword=" + nz(keyword) + ",sensitive=" + (sensitive == null ? "" : sensitive);
        sessions.audit(String.valueOf(request.getAttribute("username")), "AUDIT_EXPORT",
                "audit_log", "format=" + fmt + " 筛选[" + scope + "] 导出行数=" + n, "SUCCESS",
                AuthInterceptor.clientIp(request));
    }

    /** 导出列集合与顺序（契约 10 列；守卫按集合相等判定，顺序不限）。 */
    private static final List<String> EXPORT_COLS = List.of(
            "id", "username", "action", "target", "detail", "result", "ip", "actor_type",
            "tenant_id", "created_at");

    /** 游标分块大小：全量流式写出的内存上限口径（一次驻留一块）。 */
    private static final int EXPORT_CHUNK = 1000;

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** format 校验：仅 csv/json（大小写与首尾空白宽容），其余 400 且 message 提示支持格式（R07）。 */
    static String normalizeExportFormat(String format) {
        String fmt = format == null ? "" : format.trim().toLowerCase();
        if (!"csv".equals(fmt) && !"json".equals(fmt)) {
            throw ApiException.badRequest("format 必填且仅支持 csv 或 json（当前: "
                    + (nz(format).isBlank() ? "空" : format.trim()) + "）");
        }
        return fmt;
    }

    /** CSV 单元格：一律加引号、内部引号翻倍（RFC4180），逗号/换行不破坏列结构。 */
    static String csvCell(Object v) {
        if (v == null) {
            return "\"\"";
        }
        return "\"" + String.valueOf(v).replace("\"", "\"\"") + "\"";
    }

    /** JSON 值：数字/布尔裸写，字符串转义（含控制字符的 backslash-u 四位十六进制形式），null 原样。 */
    static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        // 只列在线会话；绝不返回 token 本身，页面只需要能吊销。
        // v447：非默认租户只看本租户成员的会话（会话表没有 tenant 列，按成员关系过滤）。
        // v448：同时属于默认租户的平台 staff 会话只给平台看，避免租户管理员踢掉平台管理员。
        return jdbc.queryForList("""
                select s.id, s.username, s.created_at, s.expires_at, true as active
                from platform.app_session s
                where s.expires_at > now()
                  and (? or (exists (
                          select 1 from platform.app_user u
                            join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                            join platform.tenant t on t.id = tm.tenant_id
                           where u.username = s.username and t.code = ?)
                      and not exists (
                          select 1 from platform.app_user u
                            join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                            join platform.tenant t on t.id = tm.tenant_id
                           where u.username = s.username and t.code = 'default')))
                order by s.created_at desc
                """, platform(), tenant());
    }

    /**
     * D07：吊销会话是破坏性动作，方法级注解覆盖类级的只读判定——
     * 既要能看安全中心（security:read），也要有成员管理权（user:manage）；
     * 且只能下线「权限不高于自己」的账号（内置 AUDITOR 等只读角色踢不掉管理员）。
     */
    @DeleteMapping("/sessions/{id}")
    @RequireAtom({"security:read", "user:manage"})
    public Map<String, Object> revoke(@PathVariable long id, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.username from platform.app_session s
                 where s.id = ?
                   and (? or (exists (
                           select 1 from platform.app_user u
                             join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                             join platform.tenant t on t.id = tm.tenant_id
                            where u.username = s.username and t.code = ?)
                       and not exists (
                           select 1 from platform.app_user u
                             join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                             join platform.tenant t on t.id = tm.tenant_id
                            where u.username = s.username and t.code = 'default')))
                """, id, platform(), tenant());
        if (rows.isEmpty()) {
            throw ApiException.notFound("会话不存在: " + id);
        }
        Object username = rows.get(0).get("username");
        String actor = String.valueOf(request.getAttribute("username"));
        if (!String.valueOf(username).equals(actor)) {
            java.util.Set<String> targetAtoms = permissions.atoms(String.valueOf(username), tenant());
            if (!allowsRevoke(permissions.atoms(actor, tenant()), targetAtoms)) {
                sessions.audit(actor, "SESSION_REVOKE", String.valueOf(username),
                        "目标账号权限更高，拒绝下线（session " + id + "）", "FAILED",
                        AuthInterceptor.clientIp(request));
                throw new ApiException(403,
                        "无权限执行该操作（只能下线本租户内权限不高于自己的账号）");
            }
        }
        jdbc.update("delete from platform.app_session where id = ?", id);
        sessions.audit(String.valueOf(request.getAttribute("username")), "SESSION_REVOKE",
                String.valueOf(username), "session " + id, "SUCCESS",
                AuthInterceptor.clientIp(request));
        return Map.of("ok", true);
    }

    private Long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
