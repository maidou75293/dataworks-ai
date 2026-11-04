package com.lab.dataworks.security;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据分级与脱敏的管理接口（设计见 docs/权限模型抽象设计-20261024.md §10）。
 *
 * <ul>
 *   <li>查看定级目录：任意读权限（级别人人可见，避免「看不到级别」的隐性风险）；</li>
 *   <li>改表级别：{@code data:classify}（SECURITY_ADMIN「安全审核员」/ ADMIN；
 *       REQ-DW-12 收敛后旧 STEWARD 的权限点已并入安全审核员）；</li>
 *   <li>改字段脱敏：{@code mask:manage}（SECURITY_ADMIN / ADMIN）；</li>
 *   <li>切换网关模式：{@code system:manage}，且只有平台管理员（默认租户）能改。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/data-classification")
public class DataClassifyController {

    private final DataGuardService guard;
    private final SessionService sessions;
    private final TenantGuard tenantGuard;

    public DataClassifyController(DataGuardService guard, SessionService sessions,
                                  TenantGuard tenantGuard) {
        this.guard = guard;
        this.sessions = sessions;
        this.tenantGuard = tenantGuard;
    }

    public record LevelInput(String dataLevel) {
    }

    public record RegisterInput(String catalog, String schemaName, String tableName,
                                String ownerUser, String dataLevel) {
    }

    public record ColumnsInput(List<Map<String, Object>> columns) {
    }

    /**
     * @param confirm 切到 OFF（关闭全平台脱敏）必须显式确认，避免一次误操作/一条请求
     *                就把所有租户的敏感列变成明文。
     */
    public record ModeInput(String dataGuardMode, Boolean confirm) {
    }

    @GetMapping("/tables")
    public Map<String, Object> tables(@RequestParam(required = false) String keyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", TenantScope.current());
        out.put("tables", guard.tables(keyword));
        out.put("levels", List.of(
                Map.of("code", "L1", "name", "公开"),
                Map.of("code", "L2", "name", "内部"),
                Map.of("code", "L3", "name", "敏感"),
                Map.of("code", "L4", "name", "机密")));
        return out;
    }

    @GetMapping("/guard")
    public Map<String, Object> guardMode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", guard.mode());
        out.put("modes", List.of(
                Map.of("code", "OFF", "name", "关闭", "description", "不判定，等价升级前行为"),
                Map.of("code", "LOG", "name", "只记录", "description", "判定并记录本会拦截/脱敏的内容，但原样返回"),
                Map.of("code", "ENFORCE", "name", "强制", "description", "真正拦截无权限的表、对敏感列脱敏")));
        return out;
    }

    @PutMapping("/guard")
    @RequireAtom("system:manage")
    public Map<String, Object> setGuardMode(@RequestBody ModeInput input,
                                            HttpServletRequest request) {
        if (input == null || input.dataGuardMode() == null) {
            throw ApiException.badRequest("dataGuardMode 不能为空");
        }
        // v448 P0-3：这是全平台唯一的脱敏开关。第二轮审计实测 tenant_b 的 ADMIN
        // （自带 mask:manage）一条 PUT 就把它改成 OFF，全平台敏感列变明文。
        // 两道锁：① 权限点换成 system:manage（拦截器对非默认租户 403）；② 控制器里再判一次。
        tenantGuard.requirePlatform();
        String before = guard.mode();
        String mode = input.dataGuardMode().trim().toUpperCase(java.util.Locale.ROOT);
        // 二次确认只针对「从非 OFF 切到 OFF」；已经是 OFF 时重复提交（脚本收尾恢复）
        // 不需要再确认，否则会把既有回归脚本的恢复步骤挡成 400。
        if ("OFF".equals(mode) && !"OFF".equals(before) && !Boolean.TRUE.equals(input.confirm())) {
            throw ApiException.badRequest(
                    "关闭数据分级网关会让全平台敏感列变为明文，请传 confirm=true 二次确认");
        }
        guard.setMode(input.dataGuardMode(), actor(request));
        sessions.audit(actor(request), "DATA_GUARD_MODE", "platform",
                before + " → " + guard.mode(), "SUCCESS", AuthInterceptor.clientIp(request));
        return Map.of("ok", true, "mode", guard.mode());
    }

    @PostMapping("/tables")
    @RequireAtom("data:classify")
    public Map<String, Object> register(@RequestBody RegisterInput input,
                                        HttpServletRequest request) {
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        // D01：租户 ADMIN / STEWARD 不能把其他租户的库登记到自己名下。
        guard.requireSchemaManageable(actor(request), input.catalog(), input.schemaName());
        return guard.register(input.catalog(), input.schemaName(), input.tableName(),
                input.ownerUser(), input.dataLevel(), actor(request));
    }

    @PutMapping("/tables/{id}/level")
    @RequireAtom("data:classify")
    public Map<String, Object> setLevel(@PathVariable long id, @RequestBody LevelInput input,
                                        HttpServletRequest request) {
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        guard.setLevel(id, input.dataLevel(), actor(request));
        return Map.of("ok", true, "dataLevel", input.dataLevel());
    }

    @PutMapping("/tables/{id}/columns")
    @RequireAtom("mask:manage")
    public Map<String, Object> setColumns(@PathVariable long id,
                                          @RequestBody ColumnsInput input,
                                          HttpServletRequest request) {
        List<DataGuardService.LevelChange> changes =
                guard.setColumns(id, input == null ? null : input.columns(), actor(request));
        // 列等级变更单独留痕：旧 → 新，便于追溯「谁把敏感列降级成公开」。
        for (DataGuardService.LevelChange change : changes) {
            sessions.audit(actor(request), "COLUMN_LEVEL_UPDATE", change.target(),
                    change.oldLevel() + " → " + change.newLevel(), "SUCCESS",
                    AuthInterceptor.clientIp(request));
        }
        return Map.of("ok", true, "levelChanges", changes.size());
    }

    @DeleteMapping("/tables/{id}")
    @RequireAtom("data:classify")
    public Map<String, Object> delete(@PathVariable long id) {
        guard.deleteTable(id);
        return Map.of("ok", true);
    }

    private static String actor(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("username"));
    }
}
