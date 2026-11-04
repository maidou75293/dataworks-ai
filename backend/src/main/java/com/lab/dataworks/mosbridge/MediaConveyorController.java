package com.lab.dataworks.mosbridge;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REQ-FW-5 搬运器入口：清单干跑、批量搬运、台账回看、推送后回填。
 *
 * <p>闸门 = 权限点 {@code mos:media}（V190 登记）。写操作语义见
 * docs/integration/media-ref.md §3：先搬成功才有得推，失败整批不推。</p>
 */
@RestController
@RequestMapping("/api/mosbridge/media")
public class MediaConveyorController {

    private final MediaConveyorService conveyor;
    private final MosMediaGateway gateway;
    private final SessionService sessions;

    public MediaConveyorController(MediaConveyorService conveyor, MosMediaGateway gateway,
                                   SessionService sessions) {
        this.conveyor = conveyor;
        this.gateway = gateway;
        this.sessions = sessions;
    }

    /** 干跑清单：会搬/会跳/缺行，三态一目了然（验收「清单」口径）。 */
    @PostMapping("/plan")
    @RequireAtom("mos:media")
    public Map<String, Object> plan(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest http) {
        List<Long> ids = assetIds(body);
        List<MediaConveyorService.PlanRow> rows = conveyor.plan(TenantScope.of(http), ids);
        List<Map<String, Object>> views = new ArrayList<>();
        for (MediaConveyorService.PlanRow r : rows) {
            views.add(Map.of("assetId", r.assetId(), "action", r.action(),
                    "reason", r.reason() == null ? "" : r.reason(),
                    "mediaRel", r.mediaRel() == null ? "" : r.mediaRel()));
        }
        return Map.of("rows", views);
    }

    /** 批量搬运（幂等：同 sha 不重传；断点：FAILED 行重试）。返回 assetId → mos rel。 */
    @PostMapping("/transfer")
    @RequireAtom("mos:media")
    public Map<String, Object> transfer(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest http) {
        if (!gateway.enabled()) {
            throw new ApiException(503, "mos 网关未配置：" + gateway.disabledReason());
        }
        List<Long> ids = assetIds(body);
        String tenant = TenantScope.of(http);
        Map<Long, String> rels = conveyor.transfer(tenant, ids, user(http));
        audit(http, "MOS_MEDIA_TRANSFER", String.valueOf(ids.size()), "ok=" + rels.size());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        rels.forEach((k, v) -> out.put(String.valueOf(k), v));
        return Map.of("gateway", "ok", "transferred", out);
    }

    /** FW-1 推送器成功后的回填口：台账三行各记（item_ref/task_ref/pushed）。 */
    @PostMapping("/pushed")
    @RequireAtom("mos:media")
    public Map<String, Object> pushed(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest http) {
        List<Long> ids = assetIds(body);
        int n = conveyor.markPushed(TenantScope.of(http), ids,
                text(body, "itemRef"), text(body, "taskRef"));
        audit(http, "MOS_MEDIA_PUSHED", String.valueOf(ids.size()), "refs=" + n);
        return Map.of("marked", n);
    }

    /** 台账回看：分页契约 v449（page 从 1、size 1–500，非法 400 在 service 挡），总数进响应头 X-Total-Count。 */
    @GetMapping("/ledger")
    @RequireAtom("mos:media")
    public ResponseEntity<Map<String, Object>> ledger(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "50") int size,
                                                      HttpServletRequest http) {
        Map<String, Object> body = conveyor.ledger(TenantScope.of(http), status, page, size);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(body.getOrDefault("total", 0)))
                .body(body);
    }

    // ---------- 小工具（与 MosFlywheelController 同口径） ----------

    private static List<Long> assetIds(Map<String, Object> body) {
        Object v = body == null ? null : body.get("assetIds");
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            throw ApiException.badRequest("assetIds 必须是数组且非空");
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                ids.add(n.longValue());
            } else {
                try {
                    ids.add(Long.parseLong(String.valueOf(o).trim()));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("assetIds 含非数字项：" + o);
                }
            }
        }
        return ids;
    }

    private String user(HttpServletRequest request) {
        Object v = request.getAttribute("username");
        return v == null ? "system" : String.valueOf(v);
    }

    private void audit(HttpServletRequest request, String action, String target, String detail) {
        sessions.audit(user(request), action, target, detail);
    }

    private static String text(Map<String, Object> body, String field) {
        Object v = body == null ? null : body.get(field);
        return v == null ? null : String.valueOf(v);
    }
}
