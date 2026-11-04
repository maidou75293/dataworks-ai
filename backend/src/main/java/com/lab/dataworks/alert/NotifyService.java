package com.lab.dataworks.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 告警通知：把未投递的告警事件按渠道推送出去，落 {platform.alert_delivery} 记录。
 *
 * <p>同样用「轮询 + 唯一键去重」：{@code (event_id, channel_id)} 唯一，
 * 重复扫描不会重复推送。渠道类型：
 *
 * <ul>
 *   <li>WEBHOOK：原样 POST JSON；</li>
 *   <li>DINGTALK / FEISHU：按各自协议组装消息体并做 HMAC-SHA256 签名；</li>
 *   <li>EMAIL：SMTP 发信，参数在 {@code config}（host/port/username/password/from/to）。</li>
 * </ul>
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    private final JdbcTemplate jdbc;
    /** 渠道密码 / 签名密钥落库前加密，发信和签名时再解密。 */
    private final com.lab.dataworks.secret.SecretCipher cipher;
    private final ObjectMapper json = new ObjectMapper();
    private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public NotifyService(JdbcTemplate jdbc, com.lab.dataworks.secret.SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Scheduled(fixedDelayString = "${platform.notify-eval-ms:30000}")
    public void scheduledDeliver() {
        try {
            int sent = deliverPending();
            if (sent > 0) {
                log.info("alert notify: delivered {}", sent);
            }
        } catch (Exception e) {
            log.warn("alert notify failed: {}", e.getMessage());
        }
    }

    /** 处理最近 2 天内、尚未投递到某个启用渠道的事件，返回成功条数。 */
    public int deliverPending() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                select e.id as "eventId", e.firing_seq as "eventSeq",
                       e.rule_type as "ruleType", e.severity,
                       e.title, e.detail, e.created_at as "createdAt",
                       c.id as "channelId", c.type, c.url, c.secret,
                       c.config::text as "config"
                from platform.alert_event e
                cross join platform.notify_channel c
                where c.enabled = true
                  and (c.project_id is null or c.project_id = e.project_id)
                  and e.created_at > now() - interval '2 days'
                  and not exists (
                      select 1 from platform.alert_delivery d
                      where d.event_id = e.id and d.channel_id = c.id
                        and d.event_seq = e.firing_seq
                        and (d.status = 'SENT'
                             or d.attempts >= 5
                             or d.next_retry_at > now()))
                order by e.id, c.id
                limit 100
                """);
        int sent = 0;
        for (Map<String, Object> row : pending) {
            Delivery result = deliverFor(row, eventPayload(row));
            // 失败按 1/2/4/8/16 分钟退避，最多 5 次；成功则清空重试时间
            int backoffSeconds = "SENT".equals(result.status()) ? 0 : backoffSeconds(
                    attemptsOf(row.get("eventId"), row.get("channelId"),
                            row.get("eventSeq")) + 1);
            jdbc.update("""
                    insert into platform.alert_delivery
                        (event_id, channel_id, event_seq, status, http_status, response,
                         attempts, next_retry_at)
                    values (?, ?, ?, ?, ?, ?, 1,
                            case when ? = 0 then null else now() + ? * interval '1 second' end)
                    on conflict (event_id, channel_id, event_seq) do update
                        set status = excluded.status,
                            http_status = excluded.http_status,
                            response = excluded.response,
                            attempts = platform.alert_delivery.attempts + 1,
                            next_retry_at = excluded.next_retry_at,
                            created_at = now()
                    """, row.get("eventId"), row.get("channelId"), row.get("eventSeq"),
                    result.status(), result.httpStatus(), trim(result.response()),
                    backoffSeconds, backoffSeconds);
            if ("SENT".equals(result.status())) {
                sent++;
            } else {
                log.warn("alert notify failed event={} channel={}: {}",
                        row.get("eventId"), row.get("channelId"), result.response());
            }
        }
        return sent;
    }

    /** 渠道测试：发一条固定 payload，不走事件表。 */
    public Map<String, Object> test(long channelId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, name, type, url, secret, config::text as "config"
                from platform.notify_channel where id = ?
                """, channelId);
        if (rows.isEmpty()) {
            return Map.of("ok", false, "message", "渠道不存在: " + channelId);
        }
        Map<String, Object> ch = rows.get(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "TEST");
        payload.put("title", "data-ai-agent 通知测试");
        payload.put("detail", "如果你收到这条消息，说明渠道 " + ch.get("name") + " 可用");
        Delivery d = deliverFor(ch, payload);
        // 不回显目标响应体：渠道测试可被用来探测内网服务并读取返回内容
        return Map.of("ok", "SENT".equals(d.status()), "status", d.status(),
                "httpStatus", d.httpStatus() == null ? 0 : d.httpStatus(),
                "message", "SENT".equals(d.status()) ? "发送成功" : "发送失败，请检查渠道配置或查看投递记录");
    }

    private Map<String, Object> eventPayload(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ALERT");
        payload.put("eventId", row.get("eventId"));
        payload.put("ruleType", row.get("ruleType"));
        payload.put("severity", row.get("severity"));
        payload.put("title", row.get("title"));
        payload.put("detail", row.get("detail"));
        payload.put("createdAt", String.valueOf(row.get("createdAt")));
        return payload;
    }

    private record Delivery(String status, Integer httpStatus, String response) {
    }

    private Delivery deliverFor(Map<String, Object> channel, Map<String, Object> payload) {
        String type = channel.get("type") == null ? "WEBHOOK"
                : String.valueOf(channel.get("type")).toUpperCase();
        try {
            // 历史数据是明文，decrypt 对明文原样返回；新写入的是 enc:v1: 密文
            Map<String, Object> ch = new LinkedHashMap<>(channel);
            if (ch.get("secret") != null) {
                ch.put("secret", cipher.decrypt(String.valueOf(ch.get("secret"))));
            }
            // config 从 DB 取出来是 TEXT，必须先解析成 Map；以前这里判断
            // instanceof Map 永远不成立，EMAIL 的 host 因此从未被检查。
            Map<String, Object> config = parseConfig(ch.get("config"));
            if ("EMAIL".equals(type)) {
                ensureSafeHost(String.valueOf(config.get("host")));
            } else {
                ensureSafeHost(String.valueOf(ch.get("url")));
            }
            return switch (type) {
                case "EMAIL" -> sendMail(ch, payload);
                case "DINGTALK" -> sendDingTalk(ch, payload);
                case "FEISHU" -> sendFeishu(ch, payload);
                default -> sendJson(String.valueOf(ch.get("url")),
                        (String) ch.get("secret"), payload);
            };
        } catch (Exception e) {
            // 单个渠道配置错误/解密失败不能中断整批投递
            return new Delivery("FAILED", null, type + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 创建渠道时提前校验目标，避免坏渠道进入投递队列后每轮都失败。 */
    static void validateTarget(String type, String url, Map<String, Object> config) {
        if ("EMAIL".equalsIgnoreCase(type)) {
            ensureSafeHost(config == null ? null : String.valueOf(config.get("host")));
        } else {
            ensureSafeHost(url);
        }
    }

    /**
     * 通知/邮件目标地址校验（S4，2026-10-26 泳道 3 扫描 P1）。
     *
     * <p>旧实现是一份硬编码黑名单：只挡 {@code localhost / 127.x / 169.254 / 0.0.0.0 / ::1}，
     * 实测内网网段、Pod/Service 网段、集群 Service DNS、以及元数据地址的
     * IPv4-mapped 写法（{@code [::ffff:169.254.169.254]}）全部放行，
     * 告警渠道就成了 SSRF 探测通道，投递记录里还会存下目标的响应体。</p>
     *
     * <p>现在直接复用 {@link com.lab.dataworks.common.JdbcUrlGuard} 的出网判定，
     * 但<b>不吃数据源的出网白名单</b>：白名单是给「连数据库」用的（内置 Doris FE、
     * 演示库都靠它），不能顺手变成「任意 HTTP 出网」的通行证。公网目标
     * （钉钉/飞书/自建 webhook）不受影响。</p>
     */
    static void ensureSafeHost(String target) {
        String value = target == null ? "" : target.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("通知地址不能为空");
        }
        try {
            com.lab.dataworks.common.JdbcUrlGuard.validateHttpTarget(value);
        } catch (com.lab.dataworks.common.ApiException e) {
            // 保持 IllegalArgumentException 契约：控制器按 400 返回，投递侧记 FAILED
            throw new IllegalArgumentException("通知地址" + e.getMessage());
        }
    }

    // ---- Webhook 系 ----

    private Delivery sendJson(String url, String secret, Map<String, Object> payload)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (secret != null && !secret.isBlank()) {
            builder.header("X-Webhook-Secret", secret);
        }
        HttpResponse<String> resp = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
        return new Delivery(ok ? "SENT" : "FAILED", resp.statusCode(), resp.body());
    }

    /** 钉钉自定义机器人：timestamp + HMAC-SHA256(secret, timestamp+"\n"+secret) 拼到 URL。 */
    private Delivery sendDingTalk(Map<String, Object> channel, Map<String, Object> payload)
            throws Exception {
        String url = String.valueOf(channel.get("url"));
        String secret = (String) channel.get("secret");
        if (secret != null && !secret.isBlank()) {
            long ts = System.currentTimeMillis();
            String toSign = ts + "\n" + secret;
            String sign = URLEncoder.encode(hmacBase64(secret, toSign),
                    StandardCharsets.UTF_8);
            url = url + (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + sign;
        }
        Map<String, Object> body = Map.of(
                "msgtype", "text",
                "text", Map.of("content", text(payload)));
        return postJsonProvider(url, null, body);
    }

    /**
     * 飞书自定义机器人：签名放 body（timestamp 秒 + HMAC-SHA256(secret, timestamp+"\n"+secret)）。
     */
    private Delivery sendFeishu(Map<String, Object> channel, Map<String, Object> payload)
            throws Exception {
        String url = String.valueOf(channel.get("url"));
        String secret = (String) channel.get("secret");
        Map<String, Object> body = new LinkedHashMap<>();
        if (secret != null && !secret.isBlank()) {
            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            body.put("timestamp", ts);
            // HMAC key = secret，data = timestamp + "\n" + secret（飞书自定义机器人签名）
            body.put("sign", hmacBase64(secret, ts + "\n" + secret));
        }
        body.put("msg_type", "text");
        body.put("content", Map.of("text", text(payload)));
        return postJsonProvider(url, null, body);
    }

    /**
     * 钉钉/飞书的业务错误是 HTTP 200 + errcode/code != 0（签名错误、关键词不匹配等）。
     * 只看 HTTP 状态会把这类失败记成 SENT，从而不再重试。
     */
    private Delivery postJsonProvider(String url, String secret, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (secret != null && !secret.isBlank()) {
            builder.header("X-Webhook-Secret", secret);
        }
        HttpResponse<String> resp = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        boolean httpOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
        boolean bodyOk = providerSuccess(resp.body());
        return new Delivery(httpOk && bodyOk ? "SENT" : "FAILED", resp.statusCode(), resp.body());
    }

    /** 钉钉看 errcode，飞书看 code/StatusCode；没有这些字段的响应按 HTTP 状态判断。 */
    static boolean providerSuccess(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = PROVIDER_JSON.readTree(body);
            if (node.has("errcode")) {
                return node.path("errcode").asInt(-1) == 0;
            }
            if (node.has("code")) {
                return node.path("code").asInt(-1) == 0;
            }
            if (node.has("StatusCode")) {
                return node.path("StatusCode").asInt(-1) == 0;
            }
            return true;
        } catch (Exception e) {
            // 非 JSON 响应（例如自建代理）没有业务码可看
            return true;
        }
    }

    private Delivery postJson(String url, String secret, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (secret != null && !secret.isBlank()) {
            builder.header("X-Webhook-Secret", secret);
        }
        HttpResponse<String> resp = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
        return new Delivery(ok ? "SENT" : "FAILED", resp.statusCode(), resp.body());
    }

    private static String text(Map<String, Object> payload) {
        return "[" + payload.getOrDefault("severity", "INFO") + "] "
                + payload.getOrDefault("title", "")
                + (payload.get("detail") == null ? "" : "\n" + payload.get("detail"));
    }

    private static String hmacBase64(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- 邮件 ----

    @SuppressWarnings("unchecked")
    private Delivery sendMail(Map<String, Object> channel, Map<String, Object> payload) {
        Map<String, Object> cfg = new LinkedHashMap<>(parseConfig(channel.get("config")));
        if (cfg.get("password") != null) {
            cfg.put("password", cipher.decrypt(String.valueOf(cfg.get("password"))));
        }
        String host = str(cfg.get("host"));
        String to = str(cfg.get("to"));
        String from = str(cfg.get("from"));
        if (host == null || to == null || from == null) {
            return new Delivery("FAILED", null, "EMAIL 渠道缺少 host/from/to");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        // 默认 JRE 字符集是 ASCII，中文主题/正文会变成 ????
        sender.setDefaultEncoding("UTF-8");
        sender.setHost(host);
        sender.setPort(intOf(cfg.get("port"), 25));
        String user = str(cfg.get("username"));
        if (user != null) {
            sender.setUsername(user);
            sender.setPassword(str(cfg.get("password")));
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", user != null ? "true" : "false");
        props.put("mail.smtp.starttls.enable",
                String.valueOf(Boolean.TRUE.equals(cfg.get("starttls"))));
        props.put("mail.mime.charset", "UTF-8");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "8000");
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to.split("\\s*,\\s*"));
            message.setSubject(String.valueOf(payload.getOrDefault("title", "data-ai-agent 告警")));
            message.setText(text(payload));
            sender.send(message);
            return new Delivery("SENT", null, "smtp " + host + " -> " + to);
        } catch (Exception e) {
            return new Delivery("FAILED", null, e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return json.readValue(String.valueOf(raw), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private int attemptsOf(Object eventId, Object channelId, Object eventSeq) {
        // 首次投递时还没有记录，queryForObject 会抛 "expected 1, actual 0"
        List<Integer> rows = jdbc.queryForList("""
                select attempts from platform.alert_delivery
                where event_id = ? and channel_id = ? and event_seq = ?
                """, Integer.class, eventId, channelId,
                eventSeq == null ? 1 : eventSeq);
        return rows.isEmpty() || rows.get(0) == null ? 0 : rows.get(0);
    }

    /** 退避：第 n 次失败后等 2^(n-1) 分钟，封顶 60 分钟。 */
    static int backoffSeconds(int attempt) {
        long minutes = 1L << Math.min(Math.max(attempt - 1, 0), 6);
        return (int) Math.min(minutes * 60, 3600);
    }

    private static int intOf(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
