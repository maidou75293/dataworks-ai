package com.lab.dataworks.baseline;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 任务基线（SLA 预警）的配置与事件查询。
 *
 * <p>写入用 {@code ops:run}（与手工触发/运维操作同一批角色），查询沿用登录态；
 * 所有按 id 的读写都通过 {@link BaselineService} 带 {@code tenant_id} 条件。</p>
 */
@RestController
@RequestMapping("/api")
public class BaselineController {

    private final BaselineService baselines;
    private final SessionService sessions;

    public BaselineController(BaselineService baselines, SessionService sessions) {
        this.baselines = baselines;
        this.sessions = sessions;
    }

    @GetMapping("/baselines")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long projectId) {
        return baselines.list(projectId);
    }

    @GetMapping("/baselines/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return baselines.get(id);
    }

    @PostMapping("/baselines")
    @RequireAtom("ops:run")
    public Map<String, Object> create(@RequestBody BaselineService.BaselineRequest req,
                                      HttpServletRequest http) {
        Map<String, Object> out = baselines.create(req, user(http));
        sessions.audit(user(http), "BASELINE_CREATE", "b" + out.get("id"),
                out.get("projectCode") + " " + out.get("name") + " 承诺 " + out.get("deadlineTime")
                        + " 提前 " + out.get("warnBeforeMin") + " 分钟");
        return out;
    }

    @PutMapping("/baselines/{id}")
    @RequireAtom("ops:run")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody BaselineService.BaselineRequest req,
                                      HttpServletRequest http) {
        Map<String, Object> out = baselines.update(id, req, user(http));
        sessions.audit(user(http), "BASELINE_UPDATE", "b" + id,
                out.get("name") + " 承诺 " + out.get("deadlineTime")
                        + " 成员 " + out.get("memberCount"));
        return out;
    }

    @DeleteMapping("/baselines/{id}")
    @RequireAtom("ops:run")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest http) {
        baselines.delete(id, user(http));
        sessions.audit(user(http), "BASELINE_DELETE", "b" + id, "id=" + id);
        return Map.of("ok", true, "id", id);
    }

    @GetMapping("/baselines/{id}/events")
    public List<Map<String, Object>> events(@PathVariable long id,
                                            @RequestParam(required = false) String date) {
        return baselines.events(id, date);
    }

    /**
     * 立即评估一次。与每分钟定时任务走完全相同的 {@code evaluate} 路径；
     * 验收和排障不用等整分钟，也便于复现「预测 → 违约 → 恢复」的完整时间线。
     */
    @PostMapping("/baselines/{id}/evaluate")
    @RequireAtom("ops:run")
    public Map<String, Object> evaluate(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> out = baselines.evaluate(id);
        sessions.audit(user(http), "BASELINE_EVALUATE", "b" + id,
                "status=" + out.get("status") + " predicted=" + out.get("predictedFinish"));
        return out;
    }

    private String user(HttpServletRequest request) {
        Object u = request.getAttribute("username");
        return u == null ? "unknown" : String.valueOf(u);
    }
}
