package com.lab.dataworks.alert;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.Permission;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.RequirePermission;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 告警通知渠道：Webhook 列表、新建、删除、测试。 */
@RestController
@RequestMapping("/api")
public class NotifyController {

    private final JdbcTemplate jdbc;
    private final NotifyService notify;
    private final com.lab.dataworks.secret.SecretCipher cipher;
    private final SessionService sessions;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public NotifyController(JdbcTemplate jdbc, NotifyService notify,
                            com.lab.dataworks.secret.SecretCipher cipher,
                            SessionService sessions) {
        this.jdbc = jdbc;
        this.notify = notify;
        this.cipher = cipher;
        this.sessions = sessions;
    }

    public record ChannelRequest(Long projectId, String name, String type, String url,
                                 String secret, Map<String, Object> config,
                                 Boolean enabled) {
    }

    private static final java.util.Set<String> TYPES = java.util.Set.of(
            "WEBHOOK", "DINGTALK", "FEISHU", "EMAIL");

    /**
     * 渠道列表：SMTP 密码与签名密钥只返回「是否已配置」，不回传原值。
     *
     * <p>S4（2026-10-26 扫描 P1）：这个 GET 原本一个权限点都没挂，任何登录用户
     * （含默认租户的 VIEWER）都能列出全部渠道目标主机。现在要求 {@code alert:read}，
     * 完整地址仍然只有 {@code notify:manage / alert:manage} 看得到。</p>
     */
    @GetMapping("/notify-channels")
    @RequireAtom("alert:read")
    public List<Map<String, Object>> list(jakarta.servlet.http.HttpServletRequest request) {
        // v447：非默认租户只看得到自己项目下的渠道（project_id 为空的渠道是平台级）
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, project_id as "projectId", name, type, url, enabled, secret,
                       config::text as "config", created_at as "createdAt"
                from platform.notify_channel c
                where (c.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (c.project_id is null and ?))
                order by id
                """, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>(row);
            Object rawConfig = item.remove("config");
            java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
            if (rawConfig != null && !String.valueOf(rawConfig).isBlank()) {
                try {
                    config = json.readValue(String.valueOf(rawConfig),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    java.util.Map<String, Object>>() { });
                } catch (Exception ignored) {
                    // 脏数据按空配置返回，不影响列表
                }
            }
            item.put("hasPassword", config.get("password") != null
                    && !String.valueOf(config.get("password")).isBlank());
            config.remove("password");
            item.put("config", config);
            Object secret = item.remove("secret");
            item.put("hasSecret", secret != null && !String.valueOf(secret).isBlank());
            // Webhook URL 本身就是凭据（钉钉/飞书的 token 在 URL 里）：
            // 只有能管理告警渠道的角色才看完整地址。
            if (!canManage(request) && item.get("url") != null) {
                item.put("url", maskUrl(String.valueOf(item.get("url"))));
            }
            out.add(item);
        }
        return out;
    }

    private boolean canManage(jakarta.servlet.http.HttpServletRequest request) {
        // 值班主任（alert:manage）与数据管家（notify:manage）都能配通知渠道
        return com.lab.dataworks.auth.AtomCheck.any(request, "notify:manage", "alert:manage");
    }

    private String maskUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return "…";
            }
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            return scheme + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + (uri.getRawPath() != null && !uri.getRawPath().isBlank() ? "/…" : "");
        } catch (Exception e) {
            return "…";
        }
    }

    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw ApiException.badRequest("config 不是合法 JSON");
        }
    }

    @PostMapping("/notify-channels")
    @RequireAtom("notify:manage")
    public Map<String, Object> create(@RequestBody ChannelRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("渠道名称不能为空");
        }
        // D20：名称/地址长度在入口就挡住，别等落库撞约束再报 409
        if (req.name().trim().length() > 64) {
            throw ApiException.badRequest("渠道名称不能超过 64 个字符");
        }
        if (req.url() != null && req.url().length() > 512) {
            throw ApiException.badRequest("渠道地址不能超过 512 个字符");
        }
        String type = req.type() == null || req.type().isBlank()
                ? "WEBHOOK" : req.type().trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("渠道类型只能是 " + String.join(" / ", TYPES));
        }
        if ("EMAIL".equals(type)) {
            Map<String, Object> cfg = req.config() == null ? Map.of() : req.config();
            if (cfg.get("host") == null || cfg.get("from") == null || cfg.get("to") == null) {
                throw ApiException.badRequest("EMAIL 渠道需要 config.host / from / to");
            }
        } else if (req.url() == null || !req.url().startsWith("http")) {
            throw ApiException.badRequest("Webhook 地址必须以 http/https 开头");
        }
        // 地址在创建时就校验：坏渠道一旦入队，每 30 秒都会失败并阻塞后续投递
        try {
            NotifyService.validateTarget(type, req.url(), req.config());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (req.projectId() != null) {
            new com.lab.dataworks.common.TenantGuard(jdbc).requireProject(req.projectId());
        } else if (!com.lab.dataworks.common.TenantGuard.platform()) {
            throw new ApiException(403, "平台级通知渠道仅平台管理员可配置，请选择所属项目");
        }
        // 密码与签名密钥落库前加密：列表接口不回传，只有发送时解密
        java.util.Map<String, Object> config = req.config() == null
                ? null : new java.util.LinkedHashMap<>(req.config());
        if (config != null && config.get("password") != null) {
            config.put("password", cipher.encrypt(String.valueOf(config.get("password"))));
        }
        String secret = req.secret() == null ? null : cipher.encrypt(req.secret());
        Long id = jdbc.queryForObject("""
                insert into platform.notify_channel
                    (project_id, name, type, url, secret, config, enabled)
                values (?, ?, ?, ?, ?, ?::json, ?)
                returning id
                """, Long.class, req.projectId(), req.name(), type,
                req.url() == null ? "" : req.url(), secret,
                config == null ? null : writeJson(config),
                req.enabled() == null || req.enabled());
        return Map.of("id", id == null ? 0 : id);
    }

    /**
     * 删除通知渠道（物理删除）。
     *
     * <p>D34（2026-10-26）：这条 delete 原先一条审计都不写——渠道被谁在什么时候删掉，
     * 事后在安全中心查不到；而渠道一旦被删，对应告警就静默不发，属于「断监控」级别的动作。
     * 现在沿用 S13 破坏性动作口径：先落一条 {@code NOTIFY_CHANNEL_DELETE}（目标
     * {@code n<id>}）的 PENDING 审计，再执行删除——成功回填 SUCCESS，并把删前读到的
     * 渠道名/类型/归属追加进 detail（删完就查不到了）；不存在 404、底层 JDBC 失败都回填
     * FAILED + 原因，异常原样上抛。</p>
     *
     * <p>业务语义不变：仍然是物理删除，状态码口径不变（归属不符与不存在都回 404）。</p>
     */
    @DeleteMapping("/notify-channels/{id}")
    @RequireAtom("notify:manage")
    public Map<String, Object> delete(@PathVariable long id,
                                      jakarta.servlet.http.HttpServletRequest request) {
        // 删前读到的渠道信息放在 holder 里：auditAround 的成功回调只能看到返回值，
        // 而返回值要保持前端已有的 {ok,id} 形状，不能塞渠道名进去。
        java.util.concurrent.atomic.AtomicReference<String> channel =
                new java.util.concurrent.atomic.AtomicReference<>("");
        return sessions.auditAround(user(request), "NOTIFY_CHANNEL_DELETE", "n" + id,
                "删除通知渠道", AuthInterceptor.clientIp(request),
                () -> doDelete(id, channel), ignored -> channel.get());
    }

    private Map<String, Object> doDelete(long id,
                                         java.util.concurrent.atomic.AtomicReference<String> channel) {
        Map<String, Object> row = ownedChannel(id);
        channel.set("渠道「" + row.get("name") + "」类型 " + row.get("type")
                + " 归属 " + (row.get("projectId") == null ? "平台级" : "项目 " + row.get("projectId")));
        int n = jdbc.update("delete from platform.notify_channel where id = ?", id);
        if (n == 0) {
            throw ApiException.notFound("渠道不存在: " + id);
        }
        return Map.of("ok", true, "id", id);
    }

    private String user(jakarta.servlet.http.HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    /**
     * 渠道归属：存在且（平台级 only 默认租户，或属于当前租户的项目）。
     *
     * <p>D34：返回值从 void 改成行数据，供删除接口在动手之前取渠道名/类型写进审计；
     * 判定点与 404 口径不变（启停、测试通道仍按原样调用）。</p>
     */
    // 占位符顺序 = c.id, p.tenant_id, platform 布尔（BUG-TC6-8 热修：原参数按
    // (tenant, platform, id) 传入，id 绑进 `and ?` 触发 PG「argument of AND must be
    // type boolean」，渠道删除/启停/测试全部 500）
    private Map<String, Object> ownedChannel(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select name, type, project_id as "projectId"
                from platform.notify_channel c
                 where c.id = ?
                   and (c.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                        or (c.project_id is null and ?))
                """, id, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        if (rows.isEmpty()) {
            throw ApiException.notFound("渠道不存在: " + id);
        }
        return rows.get(0);
    }

    public record EnabledRequest(Boolean enabled) {
    }

    /** 启停渠道（只改 enabled，不用回传密钥）。 */
    @PostMapping("/notify-channels/{id}/enabled")
    @RequireAtom("notify:manage")
    public Map<String, Object> toggle(@PathVariable long id,
                                      @RequestBody EnabledRequest req) {
        if (req == null || req.enabled() == null) {
            throw ApiException.badRequest("enabled 不能为空");
        }
        ownedChannel(id);
        int n = jdbc.update("""
                update platform.notify_channel set enabled = ? where id = ?
                """, req.enabled(), id);
        if (n == 0) {
            throw ApiException.notFound("渠道不存在: " + id);
        }
        return Map.of("ok", true, "id", id, "enabled", req.enabled());
    }

    @PostMapping("/notify-channels/{id}/test")
    @RequireAtom("notify:manage")
    public Map<String, Object> test(@PathVariable long id) {
        ownedChannel(id);
        return notify.test(id);
    }

    /**
     * 最近的投递记录，便于排查“为什么没收到”。
     *
     * <p>S4：投递记录里的 {@code response} 是目标服务的响应体原文（最长 500 字符）。
     * 读这条记录至少要 {@code alert:read}；要看响应体必须是
     * {@code notify:manage / alert:manage}（下面的遮罩循环）。</p>
     */
    @GetMapping("/alert-deliveries")
    @RequireAtom("alert:read")
    public Map<String, Object> deliveries(@RequestParam(required = false) Long eventId,
                                          jakarta.servlet.http.HttpServletRequest request) {
        // BUG-TC6-8：带上事件标题，值班员在投递记录页签不用对着事件号猜是哪条告警
        String sql = """
                select d.id, d.event_id as "eventId", d.channel_id as "channelId",
                       c.name as "channelName", e.title as "eventTitle",
                       d.status, d.http_status as "httpStatus",
                       d.event_seq as "eventSeq",
                       d.response, d.attempts, d.next_retry_at as "nextRetryAt",
                       d.created_at as "createdAt"
                from platform.alert_delivery d
                join platform.notify_channel c on c.id = d.channel_id
                left join platform.alert_event e on e.id = d.event_id
                where (c.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (c.project_id is null and ?))
                """ + (eventId == null ? "" : " and d.event_id = ? ")
                + " order by d.id desc limit 100";
        var rows = eventId == null
                ? jdbc.queryForList(sql, com.lab.dataworks.auth.TenantScope.current(),
                        com.lab.dataworks.common.TenantGuard.platform())
                : jdbc.queryForList(sql, com.lab.dataworks.auth.TenantScope.current(),
                        com.lab.dataworks.common.TenantGuard.platform(), eventId);
        // 响应体可能包含内网服务内容（Webhook 回显）：只有能管理告警渠道的角色可见，
        // 其他角色只保留状态码。与渠道列表的 URL 脱敏口径一致。
        if (!canManage(request)) {
            for (Map<String, Object> row : rows) {
                row.put("response", null);
            }
        }
        return Map.of("count", rows.size(), "deliveries", rows);
    }

    /**
     * 失败投递手动重投（BUG-TC6-8，2026-10-27）。
     *
     * <p>投递失败最多自动重试 5 次（{@code NotifyService.deliverPending()} 按
     * {@code attempts >= 5} 排除），耗尽后这条告警对该渠道就静默永久丢失——
     * 卡面要求给值班员一个「重投」入口：把 attempts 归零、next_retry_at 置 now()，
     * 下一轮 30 秒扫描即重新投递。</p>
     *
     * <p>判定口径：注解至少要 {@code alert:read}（与投递记录列表一致），方法内再按
     * 响应体可见性同口径收口——只有 {@code notify:manage / alert:manage} 能触发重投；
     * 只查/改本租户渠道的投递（与 {@link #deliveries} 同一 join + 租户条件）；
     * 仅 FAILED 可重投（SENT 无意义，400 明说），记录不存在或不属于本租户一律 404。</p>
     */
    @PostMapping("/alert-deliveries/{id}/retry")
    @RequireAtom("alert:read")
    public Map<String, Object> retryDelivery(@PathVariable long id,
                                             jakarta.servlet.http.HttpServletRequest request) {
        if (!canManage(request)) {
            throw new ApiException(403, "重投需要 notify:manage 或 alert:manage 权限");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.status
                from platform.alert_delivery d
                join platform.notify_channel c on c.id = d.channel_id
                where d.id = ?
                  and (c.project_id in (select p.id from platform.project p where p.tenant_id = ?)
                       or (c.project_id is null and ?))
                """, id, com.lab.dataworks.auth.TenantScope.current(),
                com.lab.dataworks.common.TenantGuard.platform());
        if (rows.isEmpty()) {
            throw ApiException.notFound("投递记录不存在: " + id);
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if (!"FAILED".equals(status)) {
            throw ApiException.badRequest("只有投递失败的记录能重投（当前状态 " + status + "）");
        }
        int updated = jdbc.update("""
                update platform.alert_delivery
                set attempts = 0, next_retry_at = now()
                where id = ?
                """, id);
        if (updated == 0) {
            throw ApiException.notFound("投递记录不存在: " + id);
        }
        return Map.of("ok", true, "id", id,
                "message", "已排入重投队列，调度器每 30 秒扫描一轮，稍后回到本页查看结果");
    }
}
