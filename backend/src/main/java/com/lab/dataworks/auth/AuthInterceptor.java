package com.lab.dataworks.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** request 属性：当前用户在当前租户下的权限点集合。 */
    public static final String ATOMS_ATTR = "atoms";

    /**
     * 只有平台自身（默认租户）才能用的权限点。
     * 内置角色是每个租户各一份的，ADMIN 模板里天然包含这两项。
     */
    private static final java.util.Set<String> PLATFORM_ONLY_ATOMS =
            java.util.Set.of("system:manage", "tenant:manage");

    private final SessionService sessions;
    private final UserService users;
    private final ObjectMapper mapper;
    private final PermissionResolver permissions;
    /**
     * /api/internal/** 的统一凭证：v449 P2-7 提到拦截器，先鉴权再进参数校验。
     * v471 G-P1-2：改为文件优先 + 5s TTL 热读（见 {@link InternalTokenService}），
     * 环境变量兜底；Secret 轮换后不用重启。
     */
    private final InternalTokenService internalTokens;

    /**
     * 只有部署在可信反向代理后面才应打开。默认 false：NodePort 直连时
     * X-Forwarded-For 完全由客户端控制，信任它会让登录锁定与审计 IP 可伪造。
     */
    private static volatile boolean trustForwardedFor = false;

    public AuthInterceptor(SessionService sessions, UserService users, ObjectMapper mapper,
                           PermissionResolver permissions,
                           @Value("${platform.trust-forwarded-for:false}") boolean trustForwarded,
                           InternalTokenService internalTokens) {
        this.sessions = sessions;
        this.users = users;
        this.mapper = mapper;
        this.permissions = permissions;
        this.internalTokens = internalTokens;
        trustForwardedFor = trustForwarded;
    }

    public static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }

    /**
     * 取调用方 IP。
     *
     * <p>默认只信 remoteAddr：链路上的 X-Forwarded-For 第一个值是客户端自己写的，
     * 拿它做登录锁定键等于把锁定功能交给攻击者（每次换一个值就能无限尝试）。</p>
     *
     * <p>明确部署在可信反向代理后面时设置 {@code platform.trust-forwarded-for=true}，
     * 取 XFF 最后一个值（由最近一跳代理追加，而不是客户端可伪造的第一段）。</p>
     */
    public static String clientIp(HttpServletRequest request) {
        // S13 把删数据源等入口接进 auditAround 后，单测以 null request 直调控制器；
        // 审计/登录链路拿不到请求时按 unknown 记录，不再 NPE（真实请求永远非空）。
        if (request == null) {
            return "unknown";
        }
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String value = parts[i].trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
        }
        return request.getRemoteAddr();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // BUG-TC6-7：只有映射到控制器方法（HandlerMethod）的 /api/** 才谈得上「权限声明」。
        // 未映射的路径会落到静态资源处理器（ResourceHttpRequestHandler，不是 HandlerMethod），
        // 以前因为没有权限注解被下面兜底拒成 403「接口未授权（缺少权限声明）」，
        // 把「接口不存在」误报成「你没权限」（GET 有默认 READ 权限点才碰巧走到 404，口径还不统一）。
        // 这里直接放行：资源处理器抛 NoResourceFoundException，由 GlobalExceptionHandler
        // 统一返回 404「接口不存在」。路径存在但方法不对的情况在更早的 handler 查找阶段
        // 已抛 HttpRequestMethodNotSupportedException（405），不会走到这里。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String path = request.getRequestURI();
        // 内部接口统一在拦截器鉴权：控制器里先校验参数再 check(token) 的顺序
        // 会让未认证方拿到 400 + 参数名（v449 P2-7）。
        if (path.startsWith("/api/internal/")) {
            // Prometheus 的 scrape_config 与 Alertmanager 的 webhook_config 都只支持
            // authorization: Bearer（不能自定义请求头），所以内部接口必须两种都认。
            // 之前只认 X-Internal-Token：dataworks-k8s 抓取长期 401（dw_* 指标全丢），
            // Alertmanager 推送系统告警也一直是 401（告警中心收不到系统告警）。
            if (!internalTokenMatches(request.getHeader("X-Internal-Token"))
                    && !internalTokenMatches(bearer(request))) {
                return deny(response, 401, "内部凭证无效", null);
            }
            return true;
        }
        if (path.equals("/api/auth/login") || path.equals("/api/health")
                || path.startsWith("/api/auth/sso/")
                // 数据服务是给外部系统调用的，鉴权由接口内的 API Token 完成
                || path.startsWith("/api/ds/")
                // UDF jar 由 Doris BE / Flink TM 直接下载，没有会话；接口内校验下载 token
                || path.startsWith("/api/udf/jars/")
                || path.startsWith("/api/quality/gate") || path.startsWith("/actuator")) {
            return true;
        }
        String token = bearer(request);
        SessionService.Session session = sessions.validate(token);
        if (session == null) {
            return deny(response, 401, "未登录或登录已过期", null);
        }
        String username = session.username();
        UserService.Account account = users.find(username);
        if (account == null) {
            // 用户被删除或库被重建：旧 token 不再有效
            sessions.revoke(token);
            return deny(response, 401, "账号不存在，请重新登录", null);
        }
        if (!account.active()) {
            sessions.revoke(token);
            return deny(response, 403, "账号已被禁用，请联系平台管理员", null);
        }
        request.setAttribute("username", username);
        request.setAttribute("role", account.role());
        // 租户上下文：显式 X-Tenant-Id 优先（必须是成员）；否则用会话绑定的租户
        // （v457 MT-P1-1：switch-tenant 签发的新 token 自带租户，刷新后仍生效）；
        // 都没有时回落到用户的默认租户。
        String requestedTenant = request.getHeader("X-Tenant-Id");
        String preferredTenant = requestedTenant == null || requestedTenant.isBlank()
                ? session.tenant() : requestedTenant;
        String tenant = permissions.resolveTenant(username, preferredTenant);
        if (tenant == null) {
            if (!permissions.hasActiveTenant(username)) {
                // D03：租户被删除后的孤儿账号，已有 token 也立即失效，不再回落 default
                sessions.revoke(token);
                sessions.audit(username, "ACCESS_DENIED", request.getMethod() + " " + path,
                        "账号不属于任何有效租户", "FAILED", clientIp(request));
                return deny(response, 401, "账号不属于任何有效租户，请联系平台管理员", null);
            }
            sessions.audit(username, "ACCESS_DENIED", request.getMethod() + " " + path,
                    "不属于租户: " + preferredTenant, "FAILED", clientIp(request));
            return deny(response, 403, "当前账号不属于该租户：" + preferredTenant, null);
        }
        request.setAttribute(TenantScope.ATTR, tenant);
        TenantScope.set(tenant);
        // 当前租户下的权限点：接口内部的细粒度判断（非注解能表达的）统一读这个属性
        java.util.Set<String> owned = permissions.atoms(username, tenant);
        request.setAttribute(ATOMS_ATTR, owned);

        // /api/auth/logout、/api/auth/me 只要求登录
        if (path.startsWith("/api/auth/")) {
            return true;
        }
        // D65：路径**没有对应的控制器方法**时不要在这里判权限，放行给路由层 → 404「接口不存在」。
        //
        // 缺陷现场（TC-5 T5-GEN-03 vs T5-GEN-04，v539 实测）：同一个不存在的 /api 路径，
        // GET 拿到 404「接口不存在」，POST 却拿到 403「接口未授权（缺少权限声明）」——
        // 因为下面的兜底是「非 GET 且没声明权限的接口一律拒绝」，而这条判定发生在
        // Spring 路由之前，不存在的 POST 路径根本还没走到 NoHandlerFound 那条通道。
        // 结果是把「URL 写错了」说成「你没权限」，调用方会去查授权而不是查地址。
        //
        // 安全性：本仓库没有 RouterFunction 式函数端点（grep RouterFunction 0 命中），
        // /api/** 下非 HandlerMethod 的只有「没匹配到任何接口」这一种；
        // 鉴权（会话、租户、账号状态）已全部在上面完成，这里放行不会跳过登录。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // 「任一权限点」入口（申请人/审批员/回收员共用页面）
        RequireAnyAtom anyAtom = anyAtomAnnotation(handler);
        if (anyAtom != null) {
            boolean platformOnly = java.util.Arrays.stream(anyAtom.value())
                    .allMatch(PLATFORM_ONLY_ATOMS::contains);
            if (platformOnly && !TenantScope.DEFAULT.equalsIgnoreCase(tenant)) {
                return deny(response, 403, "平台级操作仅平台管理员（默认租户）可执行", null);
            }
            boolean ok = java.util.Arrays.stream(anyAtom.value()).anyMatch(owned::contains);
            if (!ok) {
                sessions.audit(username, "ACCESS_DENIED", request.getMethod() + " " + path,
                        "缺少权限点（任一即可）: " + String.join(",", anyAtom.value()),
                        "FAILED", clientIp(request));
                return denyAtom(response, java.util.List.of(String.join("|", anyAtom.value())));
            }
            return true;
        }
        // 新注解优先：按权限点判定（第二期），两者并存时只看 RequireAtom
        RequireAtom atom = atomAnnotation(handler);
        if (atom != null) {
            // 平台级权限点：内置 ADMIN 角色在每个租户都有一份，如果不在这里收口，
            // tenant_b 的 ADMIN 也能停集群服务、删别的租户（v447 全量 by-id 审计）。
            java.util.List<String> platformOnly = java.util.Arrays.stream(atom.value())
                    .filter(PLATFORM_ONLY_ATOMS::contains).toList();
            if (!platformOnly.isEmpty() && !TenantScope.DEFAULT.equalsIgnoreCase(tenant)) {
                sessions.audit(username, "ACCESS_DENIED", request.getMethod() + " " + path,
                        "平台级操作仅默认租户可执行: " + String.join(",", platformOnly),
                        "FAILED", clientIp(request));
                return deny(response, 403, "平台级操作仅平台管理员（默认租户）可执行", null);
            }
            java.util.List<String> missing = java.util.Arrays.stream(atom.value())
                    .filter(a -> !owned.contains(a)).sorted().toList();
            if (!missing.isEmpty()) {
                sessions.audit(username, "ACCESS_DENIED", request.getMethod() + " " + path,
                        "缺少权限点: " + String.join(",", missing), "FAILED", clientIp(request));
                return denyAtom(response, missing);
            }
            return true;
        }
        Permission required = requiredPermission(request, handler);
        if (required == null) {
            // 非 GET 且没有标注权限的接口一律拒绝：新增写接口忘了标注也不会裸奔
            sessions.audit(username, "ACCESS_DENIED", request.getMethod() + " " + path,
                    "接口未声明权限，已按默认拒绝处理", "FAILED", clientIp(request));
            return deny(response, 403, "接口未授权（缺少权限声明）", null);
        }
        if (!permissions.has(username, account, required)) {
            sessions.audit(username, "ACCESS_DENIED", request.getMethod() + " " + path,
                    "缺少权限: " + required.name(), "FAILED", clientIp(request));
            return deny(response, 403, "无权限执行该操作（需要：" + required.label() + "）", required);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantScope.clear();
    }

    private RequireAnyAtom anyAtomAnnotation(Object handler) {
        if (handler instanceof HandlerMethod method) {
            RequireAnyAtom annotation = method.getMethodAnnotation(RequireAnyAtom.class);
            if (annotation == null) {
                annotation = method.getBeanType().getAnnotation(RequireAnyAtom.class);
            }
            return annotation;
        }
        return null;
    }

    private RequireAtom atomAnnotation(Object handler) {
        if (handler instanceof HandlerMethod method) {
            RequireAtom annotation = method.getMethodAnnotation(RequireAtom.class);
            if (annotation == null) {
                annotation = method.getBeanType().getAnnotation(RequireAtom.class);
            }
            return annotation;
        }
        return null;
    }

    private boolean internalTokenMatches(String token) {
        return internalTokens.matches(token);
    }

    private boolean denyAtom(HttpServletResponse response, java.util.List<String> missing)
            throws Exception {
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // D65：走 GlobalExceptionHandler 的唯一错误体构造（{timestamp,status,message}），
        // 以前这里自己写 {status,message} 两键，客户端统一解析 timestamp 会 KeyError
        Map<String, Object> body = com.lab.dataworks.common.GlobalExceptionHandler
                .errorBody(403, "无权限执行该操作（缺少权限点：" + String.join(",", missing) + "）");
        body.put("requiredAtoms", missing);
        response.getWriter().write(mapper.writeValueAsString(body));
        return false;
    }

    private Permission requiredPermission(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod method) {
            RequirePermission annotation = method.getMethodAnnotation(RequirePermission.class);
            if (annotation == null) {
                annotation = method.getBeanType().getAnnotation(RequirePermission.class);
            }
            if (annotation != null) {
                return annotation.value();
            }
        }
        return "GET".equalsIgnoreCase(request.getMethod()) ? Permission.READ : null;
    }

    private boolean deny(HttpServletResponse response, int status, String message,
                         Permission required) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // D65：与路由层/控制器层同一份错误体构造，不再各写一套键
        Map<String, Object> body = com.lab.dataworks.common.GlobalExceptionHandler
                .errorBody(status, message);
        if (required != null) {
            body.put("requiredPermission", required.name());
        }
        mapper.writeValue(response.getWriter(), body);
        return false;
    }
}
