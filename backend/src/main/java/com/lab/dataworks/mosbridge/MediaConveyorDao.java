package com.lab.dataworks.mosbridge;

import java.util.List;
import java.util.Map;

/**
 * V190 搬运台账 + V102 资产行的读取端口（REQ-FW-5）。
 *
 * <p>与 {@link MosFlywheelDao} 同构：全部查询带租户条件（by-id 审计 / check-tenant-sql CI
 * 口径）；asset 读取也放这里，单测就能用内存桩判定「清单/去重/续传/失败中断」。</p>
 */
public interface MediaConveyorDao {

    /** 台账行（三段各记：asset→item→task）。 */
    record TransferRow(long id, String tenantId, long assetId, String assetUri, String sha256,
                       Long sizeBytes, String mediaRel, String lsUrl, String itemRef, String taskRef,
                       String status, int attempts, String lastError, boolean pushed) {
    }

    /** platform.asset 的搬运所需列（V102；status 过滤由调用方 SQL 里带）。 */
    record AssetRow(long id, String tenantId, String uri, String sha256, Long sizeBytes, String mediaType) {
    }

    /** 按 id 集合读 ACTIVE 资产行（带租户条件）；返回值缺哪个 id，调用方据此报行不静默。 */
    List<AssetRow> findAssets(String tenantId, List<Long> assetIds);

    TransferRow findByAsset(String tenantId, long assetId);

    /** upsert：unique (tenant_id, asset_id) 幂等；成功搬运写 OK+rel，失败写 FAILED+error 并 attempts+1。 */
    void upsertResult(String tenantId, long assetId, String assetUri, String sha256, Long sizeBytes,
                      String mediaRel, String lsUrl, boolean ok, String error, String createdBy);

    /** FW-1 钩子推送成功后回填 item/task 两段与 pushed 标记。 */
    void markPushed(String tenantId, List<Long> assetIds, String itemRef, String taskRef);

    /** 台账查询面（status 可空=全部；分页契约 v449：page≥1、size 1–500，校验在 Service 层过 Paging.requireValid）。 */
    List<TransferRow> listLedger(String tenantId, String status, int page, int size);

    long countLedger(String tenantId, String status);

    /** 从 uri 尾巴取扩展名（V102 uri=s3://media/.../<sha256>.<ext>）；非法扩展一律给空。 */
    static String extOf(String uri) {
        if (uri == null) {
            return "";
        }
        int slash = uri.lastIndexOf('/');
        int dot = uri.lastIndexOf('.');
        if (dot <= slash || dot < 0 || dot >= uri.length() - 1) {
            return "";
        }
        String tail = uri.substring(dot + 1);
        return tail.matches("[A-Za-z0-9]{1,8}") ? tail.toLowerCase(java.util.Locale.ROOT) : "";
    }
}
