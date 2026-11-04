package com.lab.dataworks.access;

import com.lab.dataworks.auth.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 离岗回收（D52）：人离开了租户，他在这个租户里拿到的生效授权必须跟着收回。
 *
 * <p>为什么必须有：判定侧（{@code DataGuardService.grantsFor}）按
 * {@code requester + requester_tenant + status='APPROVED'} 取授权，
 * <b>不再校验这个人当前还在不在该租户</b>——移除成员只删了 {@code tenant_member}，
 * 授权行还在。实测被移出租户的人仍能查询到自己此前获批的表，
 * 直到 90 天有效期自己走完。所以这里把「移出租户」显式变成一次回收。</p>
 *
 * <p>与 {@code UserService.revokeAllGrants}（删账号/禁用）的分工：那条路径回收这个人的
 * <b>全部</b>授权（账号都不存在了），动作名沿用 {@code ACCESS_REQUEST_REVOKE_ON_DELETE}
 * （已有回归脚本按它断言，见 scripts/e2e/accept-wave1-p0.py:175，不改名）；
 * 本服务只回收**指定租户**下的授权，账号在别的租户仍然有效，动作名
 * {@code ACCESS_AUTO_REVOKE}，detail 里带原因。</p>
 *
 * <p>审批中的单子（PENDING/PENDING_SECOND）不在这里动：那是审批人的判定对象，
 * 系统替人驳回等于把审批职责拿走；移出租户后申请人自然看不到那条申请。</p>
 */
@Service
public class AccessRevokeService {

    private static final Logger log = LoggerFactory.getLogger(AccessRevokeService.class);

    /** 审计动作名：离岗/交接导致的自动回收。 */
    static final String AUTO_REVOKE_ACTION = "ACCESS_AUTO_REVOKE";

    private final JdbcTemplate jdbc;
    private final SessionService sessions;

    public AccessRevokeService(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    /**
     * 回收某人在某租户下的全部生效授权。
     *
     * @param username   被回收授权的人
     * @param tenantCode 租户编码（授权按 {@code requester_tenant} 归属）
     * @param actor      执行人（运维/管理员账号），落进 {@code revoked_by} 与审计
     * @param reason     原因文案，例如「移出租户 tenant_a」
     * @return 回收条数
     */
    public int revokeForTenant(String username, String tenantCode, String actor, String reason) {
        if (username == null || username.isBlank() || tenantCode == null || tenantCode.isBlank()) {
            return 0;
        }
        List<Map<String, Object>> revoked = jdbc.queryForList("""
                update platform.access_request
                   set status = 'REVOKED', revoked_by = ?, revoked_at = now()
                 where requester = ? and requester_tenant = ? and status = 'APPROVED'
                returning id, resource_type, catalog, schema_name, table_name, column_name,
                          permission, expires_at
                """, actor == null ? "SYSTEM" : actor, username, tenantCode);
        for (Map<String, Object> row : revoked) {
            long id = ((Number) row.get("id")).longValue();
            String detail = reason + "；资源 " + AccessExpiryJob.resourceText(row)
                    + " " + row.get("permission")
                    + "；原到期时间 " + (row.get("expires_at") == null ? "未记录（按决定时间 +90 天）"
                                              : row.get("expires_at"))
                    + "；回收人=" + (actor == null ? "SYSTEM" : actor);
            // 事件与审计跟着状态变更一起落；写失败就整体回滚（调用方在事务里），
            // 不能出现「status 已 REVOKED 但审计缺失」的半套，也不能静默吞掉继续回收剩下的
            jdbc.update("""
                    insert into platform.access_request_event (request_id, action, actor, detail)
                    values (?, ?, ?, ?)
                    """, id, "AUTO_REVOKE", actor == null ? "SYSTEM" : actor, detail);
            AccessExpiryJob.closeNotices(jdbc, id, actor == null ? "SYSTEM" : actor);
            sessions.audit(actor == null ? "system" : actor, AUTO_REVOKE_ACTION,
                    String.valueOf(id), "被回收人=" + username + "；" + detail,
                    "SUCCESS", null);
        }
        if (!revoked.isEmpty()) {
            log.info("access grants auto-revoked: user={} tenant={} count={}",
                    username, tenantCode, revoked.size());
        }
        return revoked.size();
    }
}
