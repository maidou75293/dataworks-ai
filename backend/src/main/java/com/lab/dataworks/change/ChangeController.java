package com.lab.dataworks.change;

import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.RequireAnyAtom;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SQL 变更审核 v1（inbox/007）+ D31 撤回/作废 + D53 回滚闭环的接口。
 *
 * <ul>
 *   <li>{@code POST /api/sql/classify}：判断是否需要审批（不落库）；</li>
 *   <li>{@code POST /api/changes}：建单（PENDING，同时生成回滚方案）；</li>
 *   <li>{@code GET  /api/changes?status=}：列表（默认只看自己的 + 待我审的）；</li>
 *   <li>{@code POST /api/changes/{id}/approve}：通过后立即执行
 *       （irreversible 单必须带 acknowledgeIrreversible=true，否则 400）；</li>
 *   <li>{@code POST /api/changes/{id}/reject}：驳回；</li>
 *   <li>{@code POST /api/changes/{id}/cancel}：D31 撤回——提交人撤回自己的 PENDING 单；</li>
 *   <li>{@code DELETE /api/changes/{id}}：D31 作废——审批账号把本租户 PENDING 单
 *       置 CANCELLED（状态迁移，不物理删）。</li>
 *   <li>{@code POST /api/changes/{id}/rollback}：D53 发起回滚（生成新变更单，走同一套审批）；</li>
 *   <li>{@code GET  /api/changes/backups}：D53 备份表清单（保留 30 天口径 + 清理保护标记）；</li>
 *   <li>{@code DELETE /api/changes/backups?table=}：D53 手动清理（最近 7 天 400）。</li>
 * </ul>
 *
 * <p>不做 005/006 里的环境分级、SLA、双签、冷静期、策略表、EXPLAIN 预检；
 * 不做自动回滚（D53：任何回滚都必须审批）。</p>
 */
@RestController
public class ChangeController {

    private final ChangeService changes;
    private final PermissionResolver permissions;

    public ChangeController(ChangeService changes, PermissionResolver permissions) {
        this.changes = changes;
        this.permissions = permissions;
    }

    /** 建单/判定请求：datasourceId 必填；projectId 可选（缺省取数据源所属项目）。 */
    public record SubmitRequest(Long datasourceId, Long projectId, String sql) {
    }

    /**
     * 审批意见：通过、驳回都只带一句可选说明。
     *
     * <p>D53：{@code acknowledgeIrreversible} = 审批人已勾选「已知不可逆」；
     * irreversible 的单子不勾选一律 400（旧客户端只发 comment → 视为未勾选）。</p>
     */
    public record Decision(String comment, Boolean acknowledgeIrreversible) {
    }

    @PostMapping("/api/sql/classify")
    @RequireAtom("change:submit")
    public Map<String, Object> classify(@RequestBody(required = false) SubmitRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        return changes.classify(req.datasourceId(), req.projectId(), req.sql());
    }

    @PostMapping("/api/changes")
    @RequireAtom("change:submit")
    public Map<String, Object> create(@RequestBody(required = false) SubmitRequest req,
                                      HttpServletRequest http) {
        if (req == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        return changes.create(req.datasourceId(), req.projectId(), req.sql(), user(http));
    }

    @GetMapping("/api/changes")
    @RequireAnyAtom({"change:submit", "change:approve"})
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String status, HttpServletRequest http) {
        String user = user(http);
        boolean canApprove = permissions.atoms(user, TenantScope.current())
                .contains("change:approve");
        return changes.list(user, canApprove, status);
    }

    @PostMapping("/api/changes/{id}/approve")
    @RequireAtom("change:approve")
    public Map<String, Object> approve(@PathVariable long id,
                                       @RequestBody(required = false) Decision decision,
                                       HttpServletRequest http) {
        return changes.approve(id, user(http),
                decision == null ? null : decision.comment(),
                decision != null && Boolean.TRUE.equals(decision.acknowledgeIrreversible()));
    }

    /** D53：发起回滚——按原单的 inverse_sql 生成一张新变更单（走同一套审批）。 */
    @PostMapping("/api/changes/{id}/rollback")
    @RequireAtom("change:submit")
    public Map<String, Object> rollback(@PathVariable long id, HttpServletRequest http) {
        return changes.rollback(id, user(http));
    }

    /** D53：备份表清单（源表/备份表/时间/大小 + 清理保护标记）。 */
    @GetMapping("/api/changes/backups")
    @RequireAnyAtom({"change:submit", "change:approve"})
    public Map<String, Object> backups(HttpServletRequest http) {
        String user = user(http);
        boolean canApprove = permissions.atoms(user, TenantScope.current())
                .contains("change:approve");
        return changes.backups(user, canApprove);
    }

    /** D53：手动清理一张备份表（最近 7 天内的备份 400；仅内置 Doris 支持在线清理）。 */
    @DeleteMapping("/api/changes/backups")
    @RequireAtom("change:approve")
    public Map<String, Object> cleanupBackup(@RequestParam String table,
                                             HttpServletRequest http) {
        // TenantScope 显式传入：cleanup 的归属校验按当前租户收口（check-destructive-tenant CI）
        return changes.cleanupBackup(table, user(http), TenantScope.current());
    }

    @PostMapping("/api/changes/{id}/cancel")
    @RequireAtom("change:submit")
    public Map<String, Object> cancel(@PathVariable long id, HttpServletRequest http) {
        return changes.cancel(id, user(http));
    }

    /**
     * D31 作废：{@code change:approve} 持有人把本租户仍 PENDING 的单置 CANCELLED。
     * 状态迁移不物理删——单据是审计对象；显式传 {@link TenantScope#current()} 供
     * 服务层做租户条件（check-destructive-tenant CI 口径）。
     */
    @DeleteMapping("/api/changes/{id}")
    @RequireAtom("change:approve")
    public Map<String, Object> voidChange(@PathVariable long id, HttpServletRequest http) {
        return changes.voidChange(id, user(http), TenantScope.current());
    }

    @PostMapping("/api/changes/{id}/reject")
    @RequireAtom("change:approve")
    public Map<String, Object> reject(@PathVariable long id,
                                      @RequestBody(required = false) Decision decision,
                                      HttpServletRequest http) {
        return changes.reject(id, user(http), decision == null ? null : decision.comment());
    }

    private String user(HttpServletRequest http) {
        Object value = http.getAttribute("username");
        if (value == null) {
            throw new ApiException(401, "未登录");
        }
        return String.valueOf(value);
    }
}
