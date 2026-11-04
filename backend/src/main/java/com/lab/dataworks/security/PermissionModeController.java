package com.lab.dataworks.security;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限控制总开关（v482）。
 *
 * <p>mode：OFF（关闭，全部放行但留审计）/ TABLE（只按表）/ COLUMN（表 + 列级）；
 * enforce：LOG（只记录）/ ENFORCE（强制）。配置存 platform.setting，5 秒内热生效。</p>
 */
@RestController
@RequestMapping("/api/security/permission-mode")
public class PermissionModeController {

    public record Input(String mode, String enforce, Integer smallResultThreshold, Object confirm) {
    }

    private final DataGuardService guard;
    private final SessionService sessions;

    public PermissionModeController(DataGuardService guard, SessionService sessions) {
        this.guard = guard;
        this.sessions = sessions;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", guard.permissionMode());
        out.put("enforce", guard.permissionEnforce());
        out.put("smallResultThreshold", guard.smallResultThreshold());
        out.put("modes", List.of(
                Map.of("code", "OFF", "name", "关闭",
                        "description", "所有数据对登录用户可见，仅留审计"),
                Map.of("code", "TABLE", "name", "表级",
                        "description", "按表授权，表中所有列一致对待"),
                Map.of("code", "COLUMN", "name", "字段级",
                        "description", "按列分级，L3/L4 列需申请授权")));
        out.put("enforces", List.of(
                Map.of("code", "LOG", "name", "仅记录", "description", "判定并记录，但不拦截、不脱敏"),
                Map.of("code", "ENFORCE", "name", "强制执行", "description", "真正拦截与脱敏")));
        out.put("effectiveMode", guard.mode());
        return out;
    }

    /**
     * 切换开关。切到 OFF 属于高风险操作：必须 confirm=true 或 confirm="CONFIRM"，
     * 写审计并标记风险；其他模式不要求确认。
     */
    @PutMapping
    @RequireAtom("system:manage")
    public Map<String, Object> put(@RequestBody Input in, HttpServletRequest request) {
        if (in == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String mode = in.mode() == null ? "" : in.mode().trim().toUpperCase(java.util.Locale.ROOT);
        if (mode.isEmpty()) {
            throw ApiException.badRequest("mode 不能为空");
        }
        boolean off = DataGuardService.PERMISSION_OFF.equals(mode);
        if (off && !confirmed(in.confirm())) {
            throw ApiException.badRequest("关闭权限控制属于高风险操作，请传 confirm=true（页面需输入 CONFIRM）");
        }
        guard.setPermission(mode, in.enforce(), in.smallResultThreshold());
        String actor = String.valueOf(request.getAttribute("username"));
        String detail = "mode=" + guard.permissionMode()
                + " enforce=" + guard.permissionEnforce()
                + " threshold=" + guard.smallResultThreshold();
        if (off) {
            detail = "[高风险] 关闭权限控制；" + detail;
        }
        sessions.audit(actor, "PERMISSION_MODE_UPDATE", mode, detail, "SUCCESS",
                AuthInterceptor.clientIp(request));
        return get();
    }

    private static boolean confirmed(Object confirm) {
        if (confirm instanceof Boolean b) {
            return b;
        }
        return confirm != null && "CONFIRM".equalsIgnoreCase(String.valueOf(confirm).trim());
    }
}
