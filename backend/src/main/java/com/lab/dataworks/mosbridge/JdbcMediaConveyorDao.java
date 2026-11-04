package com.lab.dataworks.mosbridge;

import com.lab.dataworks.common.Paging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V190 台账的 Jdbc 实现（REQ-FW-5）。
 *
 * <p>所有语句显式带 {@code tenant_id = ?}；asset 读取同样带租户条件且只认 ACTIVE
 * （软删行 status='DELETED' 不搬，缺行由 Service 层报「哪几个 id 找不到」，不许静默跳过）。</p>
 */
@Repository
public class JdbcMediaConveyorDao implements MediaConveyorDao {

    private final JdbcTemplate jdbc;

    public JdbcMediaConveyorDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AssetRow> findAssets(String tenantId, List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        StringBuilder ph = new StringBuilder();
        Object[] args = new Object[assetIds.size() + 1];
        args[0] = tenantId;
        for (int i = 0; i < assetIds.size(); i++) {
            ph.append(i == 0 ? "?" : ",?");
            args[i + 1] = assetIds.get(i);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant_id as "tenantId", uri, sha256, size_bytes as "sizeBytes",
                       media_type as "mediaType"
                from platform.asset
                where tenant_id = ? and status = 'ACTIVE' and id in (%s)""".formatted(ph), args);
        List<AssetRow> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new AssetRow(((Number) r.get("id")).longValue(), (String) r.get("tenantId"),
                    (String) r.get("uri"), (String) r.get("sha256"),
                    r.get("sizeBytes") == null ? null : ((Number) r.get("sizeBytes")).longValue(),
                    (String) r.get("mediaType")));
        }
        return out;
    }

    @Override
    public TransferRow findByAsset(String tenantId, long assetId) {
        List<TransferRow> rows = toRows(jdbc.queryForList(
                SELECT_LEDGER + " where tenant_id = ? and asset_id = ?", tenantId, assetId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void upsertResult(String tenantId, long assetId, String assetUri, String sha256,
                             Long sizeBytes, String mediaRel, String lsUrl, boolean ok,
                             String error, String createdBy) {
        int updated = jdbc.update("""
                update platform.media_transfer_ledger
                set asset_uri = ?, sha256 = ?, size_bytes = ?,
                    media_rel = coalesce(?, media_rel), ls_url = coalesce(?, ls_url),
                    status = ?, attempts = attempts + 1, last_error = ?,
                    pushed = case when ? then pushed else false end,
                    updated_at = now()
                where tenant_id = ? and asset_id = ?
                """, assetUri, sha256, sizeBytes, mediaRel, lsUrl,
                ok ? "OK" : "FAILED", ok ? null : trimErr(error), ok, tenantId, assetId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("""
                    insert into platform.media_transfer_ledger
                        (tenant_id, asset_id, asset_uri, sha256, size_bytes, media_rel, ls_url,
                         status, attempts, last_error, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, tenantId, assetId, assetUri, sha256, sizeBytes, mediaRel, lsUrl,
                    ok ? "OK" : "FAILED", ok ? null : trimErr(error), createdBy);
        } catch (Exception raced) {
            // 并发首次搬运撞 unique (tenant_id, asset_id)：读回再更新一次，保持幂等。
            jdbc.update("""
                    update platform.media_transfer_ledger
                    set asset_uri = ?, sha256 = ?, size_bytes = ?,
                        media_rel = coalesce(?, media_rel), ls_url = coalesce(?, ls_url),
                        status = ?, attempts = attempts + 1, last_error = ?, updated_at = now()
                    where tenant_id = ? and asset_id = ?
                    """, assetUri, sha256, sizeBytes, mediaRel, lsUrl,
                    ok ? "OK" : "FAILED", ok ? null : trimErr(error), tenantId, assetId);
        }
    }

    @Override
    public void markPushed(String tenantId, List<Long> assetIds, String itemRef, String taskRef) {
        for (Long id : assetIds) {
            jdbc.update("""
                    update platform.media_transfer_ledger
                    set item_ref = coalesce(?, item_ref), task_ref = coalesce(?, task_ref),
                        pushed = true, updated_at = now()
                    where tenant_id = ? and asset_id = ? and status = 'OK'
                    """, itemRef, taskRef, tenantId, id);
        }
    }

    @Override
    public List<TransferRow> listLedger(String tenantId, String status, int page, int size) {
        // page/size 已由 MediaConveyorService.ledger() 过 Paging.requireValid（DL-F4），
        // 这里只管取数；offset 用 Paging.offset（long），大 page 不能 int 乘法溢出（D12 同源坑）。
        int p = Paging.pageOf(page);
        int s = size;
        long off = Paging.offset(p, s);
        if (status == null || status.isBlank()) {
            return toRows(jdbc.queryForList(SELECT_LEDGER
                    + " where tenant_id = ? order by updated_at desc limit ? offset ?",
                    tenantId, s, off));
        }
        return toRows(jdbc.queryForList(SELECT_LEDGER
                + " where tenant_id = ? and status = ? order by updated_at desc limit ? offset ?",
                tenantId, status, s, off));
    }

    @Override
    public long countLedger(String tenantId, String status) {
        Long n = (status == null || status.isBlank())
                ? jdbc.queryForObject("""
                    select count(*) from platform.media_transfer_ledger where tenant_id = ?
                    """, Long.class, tenantId)
                : jdbc.queryForObject("""
                    select count(*) from platform.media_transfer_ledger
                    where tenant_id = ? and status = ?
                    """, Long.class, tenantId, status);
        return n == null ? 0 : n;
    }

    private static final String SELECT_LEDGER = """
            select id, tenant_id as "tenantId", asset_id as "assetId", asset_uri as "assetUri",
                   sha256, size_bytes as "sizeBytes", media_rel as "mediaRel", ls_url as "lsUrl",
                   item_ref as "itemRef", task_ref as "taskRef", status, attempts,
                   last_error as "lastError", pushed
            from platform.media_transfer_ledger""";

    private static List<TransferRow> toRows(List<Map<String, Object>> rows) {
        List<TransferRow> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new TransferRow(
                    ((Number) r.get("id")).longValue(),
                    (String) r.get("tenantId"),
                    ((Number) r.get("assetId")).longValue(),
                    (String) r.get("assetUri"),
                    (String) r.get("sha256"),
                    r.get("sizeBytes") == null ? null : ((Number) r.get("sizeBytes")).longValue(),
                    (String) r.get("mediaRel"),
                    (String) r.get("lsUrl"),
                    (String) r.get("itemRef"),
                    (String) r.get("taskRef"),
                    (String) r.get("status"),
                    r.get("attempts") == null ? 0 : ((Number) r.get("attempts")).intValue(),
                    (String) r.get("lastError"),
                    Boolean.TRUE.equals(r.get("pushed"))));
        }
        return out;
    }

    private static String trimErr(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 500));
    }
}
