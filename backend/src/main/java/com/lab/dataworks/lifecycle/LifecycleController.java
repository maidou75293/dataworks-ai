package com.lab.dataworks.lifecycle;

import com.lab.dataworks.auth.RequireAnyAtom;
import com.lab.dataworks.auth.RequireAtom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 表生命周期接口（D3）。
 *
 * <p>权限：读写都要求 {@code system:manage}（当前只有平台管理员具备），
 * 因为删分区不可逆——等做成项目级策略再放宽。</p>
 */
@RestController
public class LifecycleController {

    private final LifecycleService lifecycle;

    public LifecycleController(LifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    public record SettingsRequest(Integer retentionDays, Integer coldAfterDays) {
    }

    @GetMapping("/api/tables/{id}/lifecycle")
    @RequireAtom("system:manage")
    public Map<String, Object> settings(@PathVariable long id) {
        return lifecycle.settings(id);
    }

    @PutMapping("/api/tables/{id}/lifecycle")
    @RequireAtom("system:manage")
    public Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) SettingsRequest req) {
        SettingsRequest body = req == null ? new SettingsRequest(null, null) : req;
        return lifecycle.updateSettings(id, body.retentionDays(), body.coldAfterDays());
    }

    /** 手动触发一轮；dryRun 默认 true，避免误删。 */
    @PostMapping("/api/tables/{id}/lifecycle/run")
    @RequireAtom("system:manage")
    public Map<String, Object> run(@PathVariable long id,
                                   @RequestParam(defaultValue = "true") boolean dryRun) {
        return lifecycle.run(dryRun, id);
    }

    @GetMapping("/api/ops/lifecycle/logs")
    @RequireAtom("system:manage")
    public List<Map<String, Object>> logs(@RequestParam(required = false) Long tableId,
                                          @RequestParam(defaultValue = "100") int limit) {
        return lifecycle.logs(tableId, limit);
    }

    // ---------------------------------------------------------------- D51：确认闸门

    /** 确认请求：按分区确认；空列表 = 拒绝（不许「一键全删」）。 */
    public record ConfirmRequest(Long tableId, List<String> partitions) {
    }

    /** 恢复请求：只能恢复 {@code lctrash_} 形态的回收分区。 */
    public record RecoverRequest(Long tableId, String trashName) {
    }

    /**
     * 待确认清单（D51 第 1 条）：到期但没人确认过的分区。
     *
     * <p>放宽到 {@code project:read}：表负责人通常没有 {@code system:manage}，
     * 让他看不到自己表的待删清单，「人工确认」就无从谈起。真正的写操作仍按
     * 表负责人 / 平台管理员在 service 里判。</p>
     */
    @GetMapping("/api/ops/lifecycle/pending")
    @RequireAnyAtom({"project:read", "system:manage"})
    public List<Map<String, Object>> pending() {
        return lifecycle.pending();
    }

    /** 回收中的分区（能查到被谁删的、什么时候删的）。 */
    @GetMapping("/api/ops/lifecycle/trashed")
    @RequireAnyAtom({"project:read", "system:manage"})
    public List<Map<String, Object>> trashed() {
        return lifecycle.trashed();
    }

    @PostMapping("/api/ops/lifecycle/confirm")
    @RequireAnyAtom({"project:manage", "system:manage"})
    public Map<String, Object> confirm(@RequestBody(required = false) ConfirmRequest req,
                                       jakarta.servlet.http.HttpServletRequest http) {
        if (req == null || req.tableId() == null) {
            throw com.lab.dataworks.common.ApiException.badRequest("tableId 不能为空");
        }
        if (req.partitions() == null || req.partitions().isEmpty()) {
            throw com.lab.dataworks.common.ApiException.badRequest(
                    "必须逐个勾选要删除的分区（不支持一键全删）");
        }
        return lifecycle.confirm(req.tableId(), req.partitions(), user(http), platformAdmin(http));
    }

    @PostMapping("/api/ops/lifecycle/recover")
    @RequireAnyAtom({"project:manage", "system:manage"})
    public Map<String, Object> recover(@RequestBody(required = false) RecoverRequest req,
                                       jakarta.servlet.http.HttpServletRequest http) {
        if (req == null || req.tableId() == null) {
            throw com.lab.dataworks.common.ApiException.badRequest("tableId 不能为空");
        }
        return lifecycle.recover(req.tableId(), req.trashName(), user(http), platformAdmin(http));
    }

    private static String user(jakarta.servlet.http.HttpServletRequest http) {
        Object value = http.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static boolean platformAdmin(jakarta.servlet.http.HttpServletRequest http) {
        return com.lab.dataworks.auth.AtomCheck.has(http, "system:manage");
    }
}
