package com.lab.dataworks.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** S13：先落库、还没出结果的审计行标记（回填成 SUCCESS / FAILED）。 */
    public static final String RESULT_PENDING = "PENDING";

    private final JdbcTemplate jdbc;
    private final int sessionHours;
    private final SecureRandom random = new SecureRandom();

    public SessionService(JdbcTemplate jdbc, @Value("${platform.session-hours:12}") int sessionHours) {
        this.jdbc = jdbc;
        this.sessionHours = sessionHours;
    }

    /** 会话里的身份：用户名 + 当前生效租户（切换租户后由新 token 携带）。 */
    public record Session(String username, String tenant) {
    }

    public String create(String username) {
        return create(username, null);
    }

    public String create(String username, String tenant) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        // 库里只存 token 的 SHA-256：拿到 app_session 读取权限（备份、误查、
        // 作业 Pod 的共享 DB 账号）也不能直接冒用在线会话。
        jdbc.update("""
                insert into platform.app_session (token, username, tenant, expires_at)
                values (?, ?, ?, now() + (? || ' hours')::interval)
                """, hash(token), username, tenant, String.valueOf(sessionHours));
        // 单用户最多保留 5 个在线会话（多设备够用），避免会话无限堆积
        jdbc.update("""
                delete from platform.app_session
                where username = ? and id not in (
                    select id from platform.app_session
                    where username = ? order by created_at desc, id desc limit 5
                )
                """, username, username);
        return token;
    }

    public Session validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        List<Session> rows = jdbc.query("""
                select username, tenant from platform.app_session
                where token = ? and expires_at > now()
                """, (rs, i) -> new Session(rs.getString("username"), rs.getString("tenant")),
                hash(token));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void revoke(String token) {
        if (token != null) {
            jdbc.update("delete from platform.app_session where token = ?", hash(token));
        }
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public void cleanup() {
        jdbc.update("delete from platform.app_session where expires_at <= now()");
    }

    public void audit(String username, String action, String target, String detail) {
        audit(username, action, target, detail, "SUCCESS", null);
    }

    public void audit(String username, String action, String target, String detail,
                      String result, String ip) {
        auditAs(resolveTenant(username), username, action, target, detail, result, ip);
    }

    /**
     * 以指定租户的视角写审计（v460 F-P1-6）。
     *
     * <p>跨租户审批/回收时，操作人租户能看到这条记录，资源所属租户却看不到。
     * 这类动作会额外写一条「资源租户视角」的审计，让资源方可以追溯。</p>
     */
    public void auditAs(String tenant, String username, String action, String target,
                        String detail, String result, String ip) {
        // 审计字段长度有限（username/target/action 是 varchar），超长输入不能把主流程带崩。
        // v448：带上租户维度，审计列表/统计才能按租户收口（登录等无 request 上下文的
        // 调用回落到用户默认租户）。
        jdbc.update("""
                insert into platform.audit_log
                    (username, action, target, detail, result, ip, tenant_id)
                values (?, ?, ?, ?, ?, ?, ?)
                """, cut(username, 64), cut(action, 64), cut(target, 256), detail,
                cut(result == null ? "SUCCESS" : result, 16), cut(ip, 64),
                cut(tenant == null || tenant.isBlank() ? TenantScope.DEFAULT : tenant, 64));
    }

    /**
     * S13：破坏性动作的统一审计——「先记后做，失败也记 FAILED」。
     *
     * <p>原状：各控制器都是「动作做完再补一条审计」，动作一抛异常就一条都不留
     * （删数据源 / 删项目 / 删 UDF / 运维级删除全这一路），事后安全中心回答不了
     * 「谁在什么时候试过删它」。这里先落一条 {@code result=PENDING} 的审计
     * （带租户/IP/主体类型，走 {@link #auditAs} 同一口径），执行完再回填终态：</p>
     *
     * <ul>
     *   <li>成功 → {@code SUCCESS}；</li>
     *   <li>抛异常（含 409「还有引用」、404、底层 JDBC 失败）→ {@code FAILED} + 失败原因，异常原样抛出；</li>
     *   <li>进程在动作中途挂掉 → 那条 PENDING 就是唯一线索。</li>
     * </ul>
     *
     * <p>审计自身写失败（库不可用等）不影响业务结果，只 {@code log.error} 留痕，
     * 与项目既有口径一致；PENDING 行没写进去时终态会补一条独立记录。</p>
     */
    public <T> T auditAround(String username, String action, String target, String detail,
                             String ip, Supplier<T> work) {
        return auditAround(username, action, target, detail, ip, work, ignored -> null);
    }

    /**
     * 同上，成功时把 {@code outcomeDetail} 生成的补充信息追加进 detail
     * （例如节点驱逐完成后才知道的 pods 清单）。补充信息本身取失败不影响业务结果。
     */
    public <T> T auditAround(String username, String action, String target, String detail,
                             String ip, Supplier<T> work,
                             java.util.function.Function<T, String> outcomeDetail) {
        long id = auditPending(username, action, target, detail, ip);
        try {
            T out = work.get();
            String extra = null;
            try {
                extra = outcomeDetail.apply(out);
            } catch (RuntimeException e) {
                log.warn("审计补充信息生成失败 action={} target={}: {}", action, target, e.getMessage());
            }
            auditSettle(id, username, action, target, detail, ip, "SUCCESS", extra);
            return out;
        } catch (RuntimeException e) {
            auditSettle(id, username, action, target, detail, ip, "FAILED", "失败：" + reasonOf(e));
            throw e;
        }
    }

    /** 写入「先记」的那条 PENDING 审计，返回审计 id；写失败返回 -1（只记日志，不挡业务）。 */
    public long auditPending(String username, String action, String target, String detail, String ip) {
        try {
            Long id = jdbc.queryForObject("""
                    insert into platform.audit_log
                        (username, action, target, detail, result, ip, tenant_id)
                    values (?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, cut(username, 64), cut(action, 64), cut(target, 256), detail,
                    cut(RESULT_PENDING, 16), cut(ip, 64),
                    cut(resolveTenant(username), 64));
            return id == null ? -1L : id;
        } catch (RuntimeException e) {
            log.error("审计「先记」写入失败 action={} target={}: {}", action, target, e);
            return -1L;
        }
    }

    /** 回填终态；{@code auditId <= 0}（先记没成功）时补一条独立记录。 */
    public void auditSettle(long auditId, String username, String action, String target,
                            String detail, String ip, String result, String suffix) {
        String finalDetail = detail == null || detail.isBlank() ? suffix
                : (suffix == null || suffix.isBlank() ? detail : detail + "；" + suffix);
        try {
            if (auditId > 0) {
                jdbc.update("update platform.audit_log set result = ?, detail = ? where id = ?",
                        cut(result, 16), finalDetail, auditId);
                return;
            }
            audit(username, action, target, finalDetail, result, ip);
        } catch (RuntimeException e) {
            log.error("审计回填终态失败 action={} target={} result={}: {}",
                    action, target, result, e);
        }
    }

    /** 失败原因：ApiException 用业务文案，其它异常带上类名，避免审计里只有一句 null。 */
    private static String reasonOf(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        if (!(e instanceof com.lab.dataworks.common.ApiException)
                && !message.startsWith(e.getClass().getSimpleName())) {
            message = e.getClass().getSimpleName() + ": " + message;
        }
        // detail 是 text，但一条几百 KB 的 JDBC 报错会把审计列表拖死，截到可定位即可
        return message.length() > 500 ? message.substring(0, 500) + "…(共" + message.length() + "字符)"
                : message;
    }

    /** 当前请求租户优先；没有 request 上下文（登录、定时任务）时取用户默认租户。 */
    private String resolveTenant(String username) {
        String current = TenantScope.currentOrNull();
        if (current != null && !current.isBlank()) {
            return current;
        }
        if (username == null || username.isBlank()) {
            return TenantScope.DEFAULT;
        }
        try {
            List<String> rows = jdbc.queryForList("""
                    select t.code
                      from platform.app_user u
                      join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
                      join platform.tenant t on t.id = tm.tenant_id and t.status = 'ACTIVE'
                     where u.username = ?
                     order by tm.is_default desc, t.code
                     limit 1
                    """, String.class, username);
            return rows.isEmpty() ? TenantScope.DEFAULT : rows.get(0);
        } catch (Exception e) {
            return TenantScope.DEFAULT;
        }
    }

    /**
     * 列宽截断（S13）：值放不进列宽时保留前缀，并带上「原长度」标记。
     *
     * <p>旧实现是 {@code value.substring(0, max)}：被截断的 target 看起来与完整值一模一样，
     * 事后既不知道它被截过、也不知道原始值多长，等于失去定位能力
     * （例如 {@code catalog.schema.table.一长串分区/字段说明} 被砍掉后半段，
     * 审计员无法据此找回那张表）。标记只加在截断值末尾，不会把 detail 撑爆。</p>
     */
    static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        String marker = "…(共" + value.length() + "字符)";
        if (max <= marker.length()) {
            // 列宽小到装不下标记（result / ip 这类短列）：退回硬截，至少不越界
            return value.substring(0, max);
        }
        return value.substring(0, max - marker.length()) + marker;
    }
}
