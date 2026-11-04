package com.lab.dataworks.access;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ASSET_FOLDER 授权物化（V112，S11-2）：把「申请审批通过」落成
 * {@code platform.asset_folder_grant} 的一行 {@code source='ACCESS'} 授权；
 * 回收/到期时按「剩余有效申请」重算或撤掉该行。
 *
 * <p>口径与 asset 侧（V110 / {@code AssetFolderRules}）严格对齐：
 * 主体 USER（username，大小写不敏感）+ can_read/can_write；WRITE 蕴含 READ
 * （等价于 {@code Grant.normalized()}）；<b>只写本目录这一行，不向下继承</b>
 * （祖先链判定仍由 asset 侧负责）。{@code source='MANUAL'} 的行——目录管理页
 * PUT 全量替换写入的——本类一律不碰，手工授权与申请授权互不覆盖。</p>
 *
 * <p>撤销/过期不另建机制：有效性的事实来源始终是 {@code access_request}
 * （status=APPROVED 且未过 expires_at），grant 行只是物化缓存。
 * {@code sync} 的重算口径与 {@link AccessController} 各处查重 SQL 一致：
 * {@code coalesce(expires_at, decided_at + interval '90 days') > now()}。</p>
 *
 * <p>并发：同一 {@code (folder, requester)} 的物化/重算与状态变更必须同事务，
 * 且三条写路径（审批、回收、到期）统一「先 {@link #lockForSync} 再改
 * access_request 行」的加锁顺序——否则并发回收会拿旧快照把对方刚物化的授权位
 * 抹掉；批量到期与单条回收交错时会死锁。</p>
 */
final class AssetFolderGrants {

    /** schema_name 展示快照上限（platform.access_request.schema_name varchar(128)）。 */
    static final int DISPLAY_PATH_MAX = 128;

    private AssetFolderGrants() {
    }

    /** 授权位：READ → {读,写}={true,false}；WRITE → {true,true}（写必然可读）。 */
    static boolean[] grantBitsFor(String permission) {
        boolean write = "WRITE".equals(permission == null ? ""
                : permission.trim().toUpperCase(Locale.ROOT));
        return new boolean[]{true, write};
    }

    /** 重算授权位：对剩余有效申请取并集，WRITE 蕴含 READ；{false,false} 表示应撤行。 */
    static boolean[] recomputeBits(boolean remainingRead, boolean remainingWrite) {
        return new boolean[]{remainingRead || remainingWrite, remainingWrite};
    }

    /**
     * 目录 path 的展示快照：超 128 字符截断加省略号。仅用于申请单列表/审计可读，
     * 判定与去重一律以 folder_id 为真键，截断不影响正确性。
     */
    static String displayPath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.length() <= DISPLAY_PATH_MAX) {
            return value;
        }
        return value.substring(0, DISPLAY_PATH_MAX - 1) + "…";
    }

    /** 同一 (目录, 主体) 的物化/重算串行化；事务结束自动释放。必须在改 access_request 行之前调用。 */
    static void lockForSync(JdbcTemplate jdbc, long folderId, String requester) {
        String who = requester == null ? "" : requester.toLowerCase(Locale.ROOT);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class,
                "asset-folder-grant|" + folderId + "|" + who);
    }

    /**
     * 审批通过 → 物化/并入 ACCESS 行：与已有行做 OR 合并，<b>绝不把 true 降回 false</b>
     * （同一目录可能已有多条通过的申请，撤权统一走 {@link #sync}）。
     * 目录已被删除时 insert...select 源为空，静默 0 行——授权没了实体，申请历史仍在。
     */
    static void materialize(JdbcTemplate jdbc, long requestId, long folderId,
                            String requester, String permission, String actor) {
        boolean[] bits = grantBitsFor(permission);
        jdbc.update("""
                insert into platform.asset_folder_grant
                    (folder_id, tenant_id, subject_type, subject_id, can_read, can_write,
                     created_by, source, access_request_id)
                select f.id, f.tenant_id, 'USER', ?, ?, ?, ?, 'ACCESS', ?
                  from platform.asset_folder f
                 where f.id = ?
                on conflict (folder_id, subject_type, subject_id, source)
                do update set can_read = asset_folder_grant.can_read or excluded.can_read,
                              can_write = asset_folder_grant.can_write or excluded.can_write,
                              access_request_id = excluded.access_request_id
                """, requester, bits[0], bits[1], actor, requestId, folderId);
    }

    /**
     * 回收/到期后重算：以 access_request 里「仍 APPROVED 且未过期」的同目录同申请人
     * 申请为事实来源，重设或删除 ACCESS 行。必须在持有 {@link #lockForSync} 的事务内调用，
     * 且调用前当前行的状态已改（自然被排除在重算之外）。
     */
    static void sync(JdbcTemplate jdbc, long folderId, String requester, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(bool_or(permission = 'READ'), false) as remaining_read,
                       coalesce(bool_or(permission = 'WRITE'), false) as remaining_write,
                       max(id) as latest_id
                  from platform.access_request
                 where resource_type = 'ASSET_FOLDER' and status = 'APPROVED'
                   and folder_id = ? and lower(requester) = lower(?)
                   and coalesce(expires_at, decided_at + interval '90 days') > now()
                """, folderId, requester);
        boolean read = false;
        boolean write = false;
        Long latestId = null;
        if (!rows.isEmpty()) {
            read = Boolean.TRUE.equals(rows.get(0).get("remaining_read"));
            write = Boolean.TRUE.equals(rows.get(0).get("remaining_write"));
            latestId = rows.get(0).get("latest_id") instanceof Number n ? n.longValue() : null;
        }
        boolean[] bits = recomputeBits(read, write);
        jdbc.update("""
                delete from platform.asset_folder_grant
                 where folder_id = ? and subject_type = 'USER'
                   and lower(subject_id) = lower(?) and source = 'ACCESS'
                """, folderId, requester);
        if (!bits[0] && !bits[1]) {
            return; // 无剩余有效申请：撤掉 ACCESS 行，回到 MANUAL/默认开放状态
        }
        jdbc.update("""
                insert into platform.asset_folder_grant
                    (folder_id, tenant_id, subject_type, subject_id, can_read, can_write,
                     created_by, source, access_request_id)
                select f.id, f.tenant_id, 'USER', ?, ?, ?, ?, 'ACCESS', ?
                  from platform.asset_folder f
                 where f.id = ?
                """, requester, bits[0], bits[1], actor, latestId, folderId);
    }
}
