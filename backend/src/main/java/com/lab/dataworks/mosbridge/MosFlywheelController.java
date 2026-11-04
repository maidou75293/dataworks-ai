package com.lab.dataworks.mosbridge;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REQ-FW-2 产物回环入口：发起跟踪、台账查询、事件回看、手动催一轮。
 *
 * <p>闸门 = 权限点 mos:flywheel（V170 登记；与网关的 mos:access 语义不同，见 V170 注释）。
 * 发起 job 本身走 /api/mos/** 代理（REQ-UNIFY-1），这里只负责「提交后把 job 交进轮询」
 * 与回环产物的查询面。</p>
 */
@RestController
@RequestMapping("/api/mosbridge/flywheel")
public class MosFlywheelController {

    private final MosFlywheelService flywheel;
    private final MosGateway gateway;
    private final SessionService sessions;

    public MosFlywheelController(MosFlywheelService flywheel, MosGateway gateway,
                                 SessionService sessions) {
        this.flywheel = flywheel;
        this.gateway = gateway;
        this.sessions = sessions;
    }

    @PostMapping("/jobs")
    @RequireAtom("mos:flywheel")
    public Map<String, Object> trackJob(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest http) {
        String jobId = text(body, "jobId");
        Long projectId = number(body, "projectId");
        MosFlywheelDao.WatchRow w;
        try {
            w = flywheel.trackJob(jobId, TenantScope.of(http), projectId, user(http));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        audit(http, "MOS_FW_TRACK_JOB", jobId, "watch=" + w.id());
        return watchView(w);
    }

    @PostMapping("/evals")
    @RequireAtom("mos:flywheel")
    public Map<String, Object> trackEval(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest http) {
        String evalId = text(body, "evalId");
        Long projectId = number(body, "projectId");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> mappings =
                body != null && body.get("mappings") instanceof List<?> l
                        ? (List<Map<String, String>>) l : null;
        MosFlywheelDao.WatchRow w;
        try {
            w = flywheel.trackEval(evalId, mappings, TenantScope.of(http), projectId, user(http));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        audit(http, "MOS_FW_TRACK_EVAL", evalId, "watch=" + w.id());
        return watchView(w);
    }

    @GetMapping("/ledger")
    @RequireAtom("mos:flywheel")
    public List<Map<String, Object>> ledger(HttpServletRequest http) {
        return flywheel.ledger(TenantScope.of(http));
    }

    @GetMapping("/watch")
    @RequireAtom("mos:flywheel")
    public List<Map<String, Object>> watch(@RequestParam(defaultValue = "true") boolean openOnly,
                                           HttpServletRequest http) {
        return flywheel.watches(TenantScope.of(http), openOnly);
    }

    /** 事件列表。watchId 必须属于当前租户（dao 内联校验，越权=空列表）。 */
    @GetMapping("/events")
    @RequireAtom("mos:flywheel")
    public List<Map<String, Object>> events(@RequestParam long watchId, HttpServletRequest http) {
        return flywheel.events(watchId, TenantScope.of(http));
    }

    /** 手动催一轮（验收/排障用；网关未配置时 503 明说原因，不空转 200）。 */
    @PostMapping("/poll")
    @RequireAtom("mos:flywheel")
    public Map<String, Object> poll(HttpServletRequest http) {
        if (!gateway.enabled()) {
            throw new ApiException(503, "mos 网关未配置：" + gateway.disabledReason());
        }
        int handled = flywheel.pollOnce();
        audit(http, "MOS_FW_POLL", "-", "handled=" + handled);
        return Map.of("handled", handled);
    }

    // ---------- 小工具（与 UdfController 同口径） ----------

    private String user(HttpServletRequest request) {
        Object v = request.getAttribute("username");
        return v == null ? "system" : String.valueOf(v);
    }

    private void audit(HttpServletRequest request, String action, String target, String detail) {
        sessions.audit(user(request), action, target, detail);
    }

    private static Map<String, Object> watchView(MosFlywheelDao.WatchRow w) {
        return Map.of("watchId", w.id(), "kind", w.kind(), "extId", w.extId(),
                "status", w.status());
    }

    private static String text(Map<String, Object> body, String field) {
        Object v = body == null ? null : body.get(field);
        return v == null ? null : String.valueOf(v);
    }

    private static Long number(Map<String, Object> body, String field) {
        Object v = body == null ? null : body.get(field);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " 必须是数字");
        }
    }
}
