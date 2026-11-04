package com.lab.dataworks.mosbridge;

import com.lab.dataworks.auth.AtomCheck;
import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import com.lab.dataworks.query.DorisQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * REQ-FW-1 · 「推送到标注」入口：表查询出口挂点。
 *
 * <p>卡面文件域是「表查询出口挂点」：本控制器不复制查询链路，直接复用
 * {@link DorisQueryService#query(Long, String, int)}——与 {@code /api/query} 同一个出口、
 * 同一套项目级 Doris 路由（docs/数仓连接路由-20261025.md），推送动作不产生第二条查询路径。</p>
 *
 * <p>权限（all-of，RequireAtom.java:13）：能把数据推给对方平台，等于同时行使
 * 「跑查询」与「用 mos 通道」两种能力，故 {@code query:run} 与 {@code mos:access} 缺一不可。
 * 注意 {@code mos:access} 权限点随 REQ-UNIFY-1（V150）注册——该迁移未部署前本端点对所有人 403，
 * 属预期闸门而非 BUG；真推验证在 service 层直调完成，不依赖此闸门。</p>
 *
 * <p>SQL 形态收口（不许静默的延伸——防止把 DDL/DML 从推送口带进查询出口）：
 * 只允许单条 SELECT/WITH，多语句拼接显式 400。脱敏口径提示：本入口取的是查询出口返回的
 * 行集；需要按 L3/L4 脱敏后字段入环的场景，应先经 /api/query 出口审字段（数据分级联动归 FW-4 编排）。</p>
 *
 * <p>审计：每次推送记 {@code ANNOTATION_PUSH}（成功/失败都记，tenant_id 由 SessionService
 * 按操作人解析，口径同 REQ-UNIFY-1 的 MOS_ACCESS）。</p>
 */
@RestController
@RequestMapping("/api/annotation-push")
@RequireAtom({"query:run", "mos:access"})
public class AnnotationPushController {

    private final DorisQueryService doris;
    private final AnnotationPushService pusher;
    private final SessionService sessions;
    private final TenantGuard guard;

    public AnnotationPushController(DorisQueryService doris, AnnotationPushService pusher,
                                    SessionService sessions, TenantGuard guard) {
        this.doris = doris;
        this.pusher = pusher;
        this.sessions = sessions;
        this.guard = guard;
    }

    /** 推送请求：sql+projectId 走表查询出口；qColumn/aColumn/refColumn 为 §3.1 映射列名。 */
    public record PushRequest(String sql, Long projectId, String dsId,
                              String qColumn, String aColumn, String refColumn, Integer maxRows) {
    }

    @PostMapping
    public Map<String, Object> push(@RequestBody PushRequest req, HttpServletRequest http) {
        if (req == null || req.sql() == null || req.sql().isBlank()) {
            throw ApiException.badRequest("sql 必填（从表查询出口取数的 SELECT/WITH）");
        }
        String sql = requireReadOnly(req.sql());
        if (req.dsId() == null || req.dsId().isBlank()) {
            throw ApiException.badRequest("dsId 必填（对方数据集主键 ds_xxx）");
        }
        if (req.qColumn() == null || req.qColumn().isBlank()
                || req.aColumn() == null || req.aColumn().isBlank()) {
            throw ApiException.badRequest("qColumn / aColumn 必填（问题列/答案列，契约 §3.1）");
        }
        int maxRows = req.maxRows() == null ? 1000 : req.maxRows();
        if (maxRows < 1 || maxRows > 10000) {
            throw ApiException.badRequest("maxRows 允许 1–10000（收到 " + req.maxRows() + "）");
        }
        if (req.projectId() != null) {
            guard.requireProject(req.projectId());
        }
        String username = (String) http.getAttribute("username");
        String operator = username == null || username.isBlank() ? "unknown" : username;
        String tenant = TenantScope.of(http);

        DorisQueryService.Result result = doris.query(req.projectId(), sql, maxRows);
        String target = "ds=" + req.dsId().trim() + " rows=" + result.rowCount();
        String ip = AuthInterceptor.clientIp(http);
        AnnotationPushService.PushReport rep;
        try {
            rep = pusher.push(tenant, operator, sql, req.dsId(),
                    result, req.qColumn(), req.aColumn(), req.refColumn());
        } catch (ApiException e) {
            sessions.audit(operator, "ANNOTATION_PUSH", target,
                    "HTTP " + e.getStatus() + " " + e.getMessage(), "FAILED", ip);
            throw e;
        } catch (RuntimeException e) {
            sessions.audit(operator, "ANNOTATION_PUSH", target,
                    "异常 " + e.getClass().getSimpleName() + " " + e.getMessage(), "FAILED", ip);
            throw e;
        }
        sessions.audit(operator, "ANNOTATION_PUSH", target,
                "inserted=" + rep.inserted() + " duplicated=" + rep.duplicated()
                        + " version=" + rep.version(), "SUCCESS", ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dsId", rep.dsId());
        out.put("version", rep.version());
        out.put("inserted", rep.inserted());
        out.put("duplicated", rep.duplicated());
        out.put("empty", rep.empty());
        out.put("total", rep.total());
        return out;
    }

    /**
     * 只读单语句闸门（包级可见，供单测不起 Spring 钉住）。
     * 允许：select/with 开头、尾部单个分号；其余（多语句、DML/DDL）一律 400。
     */
    static String requireReadOnly(String raw) {
        String sql = raw.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw ApiException.badRequest("推送入口只允许单条语句（检测到分号拼接）");
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            throw ApiException.badRequest("推送入口只允许只读 SELECT/WITH 查询（收到："
                    + sql.substring(0, Math.min(sql.length(), 40)) + "…）");
        }
        return sql;
    }

    /** AtomCheck 留给拦截器被绕过场景的单测口径；现网由 @RequireAtom all-of 判定。 */
    static boolean hasBothAtoms(HttpServletRequest request) {
        return AtomCheck.has(request, "query:run") && AtomCheck.has(request, "mos:access");
    }
}
