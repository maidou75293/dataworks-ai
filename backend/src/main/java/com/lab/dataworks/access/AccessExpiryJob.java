package com.lab.dataworks.access;

import com.lab.dataworks.auth.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权到期处理：到期自动失效（EXPIRED）＋ 到期前提醒（D52）。
 *
 * <p>判定侧已经用 `expires_at > now()` 过滤，所以即使定时任务晚跑也不会越权；
 * 这里只是把状态显式化，方便列表/审计看到「已过期」而不是一直显示「已通过」。</p>
 *
 * <p>V112（S11-2）：ASSET_FOLDER 行过期时同事务重算 asset_folder_grant 的 ACCESS
 * 授权行（回收/到期沿用既有 Access 状态机，不另建第二套失效机制）。失效循环为
 * **逐行事务**并按「先 grant 锁、后改申请行」的顺序加锁——批量 update 一次锁 500 行
 * 再统一物化，会和并发的审批/回收交错成死锁；逐行小事务把窗口收敛到单行。</p>
 *
 * <p><b>提醒为什么要写 alert_event 而不是自己发 HTTP</b>：投递（HTTP/邮件/钉钉/飞书、
 * 失败退避重试、投递记录 `alert_delivery`）已经有一套在跑，另起一份就会变成
 * 「同一个通知两条实现」——这个仓库已经因为双实现吃过质量闸门的亏（审计 Q2/Q3）。
 * 这里只负责「决定该不该提醒」，发出去交给 {@code NotifyService} 的轮询。</p>
 *
 * <p><b>幂等</b>：同一授权同一提前量只发一次。去重键是
 * {@code alert_event.event_key = access-expiry:<申请id>:<Nd>}，靠
 * {@code not exists} 判定；整个批次跑在一个事务里并先取一把
 * {@code pg_advisory_xact_lock}——定时轮与运维手动触发可能同时跑，
 * 没有这把锁时两条同 key 的事件都能插进去（重复打扰申请人）。</p>
 */
@Component
public class AccessExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(AccessExpiryJob.class);

    /** 到期前提醒的提前量（天）：D52 要求 7 天与 1 天各一次。 */
    static final int[] REMIND_WINDOWS_DAYS = {7, 1};

    /** 单轮每档最多处理多少条，防止一次扫描把队列打满。 */
    private static final int BATCH_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final TransactionTemplate tx;

    public AccessExpiryJob(JdbcTemplate jdbc, SessionService sessions, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${access.expiry-ms:3600000}", initialDelay = 60000)
    public void expire() {
        try {
            // v460 F-P2-3/P2-7：历史 expires_at 为空按 decided_at + 90 天；逐条审计，
            // 避免「status 已 EXPIRED 但没有审计」。候选只是快照，真正置 EXPIRED 时
            // 再按 status='APPROVED' 兜底（期间可能已被回收/撤销）。
            List<Map<String, Object>> candidates = jdbc.queryForList("""
                    select id, requester, resource_type, folder_id,
                           coalesce(expires_at, decided_at + interval '90 days') as expires_at
                      from platform.access_request
                     where status = 'APPROVED'
                       and coalesce(expires_at, decided_at + interval '90 days') <= now()
                     order by id
                     limit 500
                    """);
            if (candidates.isEmpty()) {
                return;
            }
            int flipped = 0;
            for (Map<String, Object> c : candidates) {
                Long id = longOf(c.get("id"));
                String requester = String.valueOf(c.get("requester"));
                Long folderId = "ASSET_FOLDER".equals(String.valueOf(c.get("resource_type")))
                        ? longOf(c.get("folder_id")) : null;
                Boolean done = id == null ? Boolean.FALSE : tx.execute(ignored -> {
                    if (folderId != null) {
                        AssetFolderGrants.lockForSync(jdbc, folderId, requester);
                    }
                    int n = jdbc.update("""
                            update platform.access_request
                               set status = 'EXPIRED',
                                   comment = coalesce(comment, '') || ' [已到期自动失效]',
                                   expires_at = coalesce(expires_at, decided_at + interval '90 days')
                             where id = ? and status = 'APPROVED'
                            """, id);
                    if (n > 0 && folderId != null) {
                        AssetFolderGrants.sync(jdbc, folderId, requester, "system");
                    }
                    return n > 0;
                });
                if (Boolean.TRUE.equals(done)) {
                    flipped++;
                    sessions.audit(requester, "ACCESS_REQUEST_EXPIRE", String.valueOf(id),
                            "授权到期自动失效（expiresAt=" + c.get("expires_at") + "）",
                            "SUCCESS", null);
                    // D52：到期了就把这条授权的未关闭提醒事件收掉，别让告警中心堆一堆「即将到期」
                    closeNotices(jdbc, id, "access-expiry-job");
                }
            }
            if (flipped > 0) {
                log.info("access grants expired: {}", flipped);
            }
        } catch (RuntimeException e) {
            log.warn("access expiry job failed: {}", e.getMessage());
        }
    }

    /**
     * 定时轮：按 7 天 / 1 天两档各扫一次。
     *
     * <p>提醒失败不能把到期失效那一轮带崩，所以这里只记日志；
     * 单档内部是事务性的（要么该档全部落库要么整档回滚）。</p>
     */
    @Scheduled(fixedDelayString = "${access.remind-ms:3600000}", initialDelay = 120000)
    public void remindScheduled() {
        try {
            Map<String, Object> out = notifyExpiring(REMIND_WINDOWS_DAYS, "SYSTEM");
            int total = (int) out.getOrDefault("total", 0);
            if (total > 0) {
                log.info("access expiry notices queued: {}", out);
            }
        } catch (RuntimeException e) {
            log.warn("access expiry remind failed: {}", e.getMessage());
        }
    }

    /**
     * 发到期提醒。
     *
     * @param windowsDays 提前量（天），一档一个 key；运维手动触发时只跑指定的那一档
     * @param actor       审计里的动作发起人（定时轮为 {@code SYSTEM}）
     * @return 每档发出的条数与合计
     */
    public Map<String, Object> notifyExpiring(int[] windowsDays, String actor) {
        Map<String, Object> perWindow = new LinkedHashMap<>();
        if (windowsDays == null || windowsDays.length == 0) {
            return Map.of("windows", perWindow, "total", 0);
        }
        Integer total = tx.execute(ignored -> {
            // 一把批次锁：定时轮与手动触发不会同时看到「都还没发过」而各插一条
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class,
                    "access-expiry-notice");
            int sent = 0;
            for (int days : windowsDays) {
                if (days <= 0) {
                    continue;
                }
                int n = notifyOneWindow(days, actor);
                perWindow.put(days + "d", n);
                sent += n;
            }
            return sent;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windows", perWindow);
        out.put("total", total == null ? 0 : total);
        return out;
    }

    /**
     * 一档（例如「到期前 7 天」）：挑出还在有效期内、剩余时间落在这一档、
     * 且这一档还没提醒过的授权，逐条落 {@code alert_event} + {@code access_request_event}。
     *
     * <p>提前量之间不重复发同一档：key 里带档名（{@code 7d} / {@code 1d}），
     * 所以同一条授权一共只会收到 2 条提醒。</p>
     */
    private int notifyOneWindow(int days, String actor) {
        String node = noticeSuffix(days);
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select a.id, a.requester, a.approver, a.resource_type as "resourceType",
                       a.catalog, a.schema_name as "schemaName", a.table_name as "tableName",
                       a.column_name as "columnName", a.permission,
                       coalesce(a.expires_at, a.decided_at + interval '90 days') as "expiresAt",
                       (coalesce(a.expires_at, a.decided_at + interval '90 days') - now())::text as "remaining"
                  from platform.access_request a
                 where a.status = 'APPROVED'
                   and coalesce(a.expires_at, a.decided_at + interval '90 days') > now()
                   and coalesce(a.expires_at, a.decided_at + interval '90 days')
                       <= now() + (? * interval '1 day')
                   and not exists (
                       select 1 from platform.alert_event e
                        where e.event_key = 'access-expiry:' || a.id || ':' || ?)
                 order by coalesce(a.expires_at, a.decided_at + interval '90 days')
                 limit ?
                """, days, node, BATCH_LIMIT);
        for (Map<String, Object> row : candidates) {
            long id = ((Number) row.get("id")).longValue();
            String key = noticeKey(id, days);
            String resource = resourceText(row);
            String title = "授权 " + node.replace("d", "") + " 天内到期：" + row.get("requester")
                    + " 的 " + resource;
            String detail = "申请单 #" + id + "；资源 " + resource
                    + "；权限 " + row.get("permission")
                    + "；到期时间 " + row.get("expiresAt")
                    + "；剩余 " + row.get("remaining")
                    + "；申请人=" + row.get("requester")
                    + "；审批人=" + (row.get("approver") == null ? "-" : row.get("approver"))
                    + "；续期入口：权限申请页 →「续期」";
            jdbc.update("""
                    insert into platform.alert_event
                        (rule_name, rule_type, severity, title, detail, event_key, source, status)
                    values ('授权到期提醒', 'ACCESS_EXPIRY', 'WARNING', ?, ?, ?, 'ACCESS', 'OPEN')
                    """, title, detail, key);
            // 提醒记录落在申请单事件上，列表里能看到「已提醒过几次、什么时候」
            jdbc.update("""
                    insert into platform.access_request_event (request_id, action, actor, detail)
                    values (?, ?, ?, ?)
                    """, id, "NOTICE_" + node, actor, detail);
        }
        return candidates.size();
    }

    /** 档名：{@code 7d} / {@code 1d}。同一授权每档只发一次，去重键就带它。 */
    static String noticeSuffix(int windowDays) {
        return windowDays + "d";
    }

    /**
     * 提醒事件的去重键：{@code access-expiry:<申请id>:<档名>}。
     *
     * <p>SQL 里用 {@code 'access-expiry:' || a.id || ':' || ?} 拼同一个形状，
     * 两边必须一致——所以这里做成常量拼接的单一出处，测试也钉它。</p>
     */
    static String noticeKey(long requestId, int windowDays) {
        return "access-expiry:" + requestId + ":" + noticeSuffix(windowDays);
    }

    /** 资源文本：库 / 表 / 列三种粒度都拼得出来。 */
    static String resourceText(Map<String, Object> row) {
        String type = String.valueOf(row.get("resourceType"));
        StringBuilder sb = new StringBuilder()
                .append(row.get("catalog")).append('.').append(row.get("schemaName"));
        if (!"DATABASE".equals(type)) {
            sb.append('.').append(row.get("tableName"));
            if ("COLUMN".equals(type) && row.get("columnName") != null) {
                sb.append('.').append(row.get("columnName"));
            }
        }
        return sb.toString();
    }

    /** 关闭某一条授权的所有未关闭提醒事件（续期/回收/撤销/到期时调用）。 */
    static void closeNotices(JdbcTemplate jdbc, long requestId, String by) {
        jdbc.update("""
                update platform.alert_event
                   set status = 'CLOSED', closed_by = ?, closed_at = now()
                 where rule_type = 'ACCESS_EXPIRY' and status = 'OPEN'
                   and event_key like ?
                """, by, "access-expiry:" + requestId + ":%");
    }

    private static Long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
