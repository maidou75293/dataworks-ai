package com.lab.dataworks.mosbridge;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.Paging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 中台 → mos 媒体搬运器（REQ-FW-5 第三节，docs/integration/media-ref.md）。
 *
 * <p>三条硬规则：</p>
 * <ol>
 *   <li><b>去重</b>：台账 {@code status=OK} 且 sha256 与资产行一致 → 不重传（V102 内容寻址，
 *       sha 变了就是换了货，必须重搬）；</li>
 *   <li><b>断点续传</b>：清单先落台账再搬，重跑只搬 PENDING/FAILED 与新增行；</li>
 *   <li><b>先搬后推</b>：{@link #ensureMediaForPush} 是 FW-1 推送器的时序钩子——
 *       任一媒体搬运失败就抛异常，推送器不得继续推任务（防 LS 空图），失败行留在台账可重试。</li>
 * </ol>
 */
@Service
public class MediaConveyorService {

    private static final Logger log = LoggerFactory.getLogger(MediaConveyorService.class);
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private final MediaConveyorDao dao;
    private final MosMediaGateway mos;
    private final AssetBytesSource source;

    public MediaConveyorService(MediaConveyorDao dao, MosMediaGateway mos, AssetBytesSource source) {
        this.dao = dao;
        this.mos = mos;
        this.source = source;
    }

    /** 清单行：action ∈ SKIP_OK / TRANSFER / RETRY_FAILED / MISSING。 */
    public record PlanRow(long assetId, String action, String reason, String sha256, String mediaRel) {
    }

    /**
     * FW-1 时序钩子契约（media-ref.md §3.2）：推送含媒体引用的 jsonl 之前调用，
     * 全部搬运成功才返回 {@code assetId → mos 媒体库相对路径}；任何一行失败抛
     * {@link ApiException}，调用方必须放弃本次推送。
     *
     * <p>错误码口径（DL-1 发现 5 / DL-F5）：本地前置条件失败（资产在租户内不存在）
     * 抛 <b>400</b>；mos 网关/搬运失败抛 <b>502</b>——502 只留给真·上游故障，
     * 免得监控把数据问题误归类成依赖故障。</p>
     */
    public Map<Long, String> ensureMediaForPush(String tenantId, List<Long> assetIds, String createdBy) {
        return transfer(tenantId, assetIds, createdBy);
    }

    /** 干跑清单（不落库、不出站），回答「这批会搬哪些、跳哪些」。 */
    public List<PlanRow> plan(String tenantId, List<Long> assetIds) {
        require(tenantId, assetIds);
        List<MediaConveyorDao.AssetRow> assets = dao.findAssets(tenantId, assetIds);
        Map<Long, MediaConveyorDao.AssetRow> byId = new LinkedHashMap<>();
        for (MediaConveyorDao.AssetRow a : assets) {
            byId.put(a.id(), a);
        }
        List<PlanRow> out = new ArrayList<>();
        for (Long id : assetIds) {
            MediaConveyorDao.AssetRow a = byId.get(id);
            if (a == null) {
                out.add(new PlanRow(id, "MISSING", "租户内不存在该 ACTIVE 资产（软删/越权/没这条）", null, null));
                continue;
            }
            MediaConveyorDao.TransferRow t = dao.findByAsset(tenantId, id);
            if (t != null && "OK".equals(t.status()) && Objects.equals(t.sha256(), a.sha256())) {
                out.add(new PlanRow(id, "SKIP_OK", "同 src checksum 已搬，不重传", a.sha256(), t.mediaRel()));
            } else if (t != null && "OK".equals(t.status())) {
                out.add(new PlanRow(id, "TRANSFER", "台账 sha 与资产不一致（内容换了），重搬", a.sha256(), null));
            } else if (t != null) {
                out.add(new PlanRow(id, "RETRY_FAILED", "上次失败续传（attempts=" + t.attempts() + "）",
                        a.sha256(), null));
            } else {
                out.add(new PlanRow(id, "TRANSFER", "首次搬运", a.sha256(), null));
            }
        }
        return out;
    }

    /**
     * 执行搬运：逐行「读本体 → POST /api/media/upload → 台账 upsert」。
     * 一行失败不阻断其余行搬运（台账更完整），但收尾必抛——整批不推。
     *
     * <p>失败分两类分别映射（DL-F5）：资产缺失属本地前置条件 → 400；
     * 读本体/上传等真·上游失败 → 502。两类同时存在时按 502（上游确实在坏），
     * 并在文案里附缺失数。</p>
     */
    public Map<Long, String> transfer(String tenantId, List<Long> assetIds, String createdBy) {
        require(tenantId, assetIds);
        if (!mos.enabled()) {
            throw new ApiException(503, "mos 网关未配置：" + mos.disabledReason());
        }
        List<PlanRow> plan = plan(tenantId, assetIds);
        Map<Long, MediaConveyorDao.AssetRow> assets = new LinkedHashMap<>();
        for (MediaConveyorDao.AssetRow a : dao.findAssets(tenantId, assetIds)) {
            assets.put(a.id(), a);
        }
        Map<Long, String> rels = new LinkedHashMap<>();
        List<String> missingFailures = new ArrayList<>();   // 本地前置条件：租户内无该 ACTIVE 资产
        List<String> upstreamFailures = new ArrayList<>();  // 真·上游：读本体 / mos 上传失败
        String folder = "中台资产/" + LocalDate.now().format(YYYYMM);
        for (PlanRow p : plan) {
            if ("SKIP_OK".equals(p.action())) {
                rels.put(p.assetId(), p.mediaRel());
                continue;
            }
            MediaConveyorDao.AssetRow a = assets.get(p.assetId());
            if (a == null) {
                missingFailures.add("asset " + p.assetId() + "：" + p.reason());
                continue;
            }
            try {
                byte[] bytes = source.read(a.uri());
                String name = nameOf(a);
                Map<String, Object> meta = mos.uploadMedia(bytes, name, folder);
                String rel = String.valueOf(meta.get("path"));
                if (rel.isBlank() || "null".equals(rel)) {
                    throw new IllegalStateException("mos 上传 200 但 path 为空");
                }
                dao.upsertResult(tenantId, a.id(), a.uri(), a.sha256(), a.sizeBytes(),
                        rel, str(meta.get("ls_url")), true, null, createdBy);
                rels.put(a.id(), rel);
            } catch (Exception e) {
                dao.upsertResult(tenantId, a.id(), a.uri(), a.sha256(), a.sizeBytes(),
                        null, null, false, e.toString(), createdBy);
                upstreamFailures.add("asset " + a.id() + "：" + e);
                log.warn("媒体搬运失败 asset={} tenant={}：{}", a.id(), tenantId, e.toString());
            }
        }
        if (!missingFailures.isEmpty() || !upstreamFailures.isEmpty()) {
            // DL-F5 / DL-1 发现 5：只有本地缺失时是参数/数据问题（400）；一旦有真·上游
            // 失败就按 502 报（监控口径：502=mos 网关故障），文案里附缺失数便于排查。
            if (upstreamFailures.isEmpty()) {
                throw ApiException.badRequest("媒体搬运前置条件不满足 " + missingFailures.size()
                        + " 个，整批不推（本地资产缺失，非网关故障）："
                        + String.join("；", missingFailures));
            }
            String missingNote = missingFailures.isEmpty() ? ""
                    : "；另有本地资产缺失 " + missingFailures.size() + " 个："
                            + String.join("；", missingFailures);
            throw new ApiException(502, "媒体搬运失败 " + upstreamFailures.size()
                    + " 个，整批不推（防 LS 空图）：" + String.join("；", upstreamFailures) + missingNote);
        }
        return rels;
    }

    /** 推送成功后回填 item/task 两段（台账三行各记，media-ref.md §3.3）。 */
    public int markPushed(String tenantId, List<Long> assetIds, String itemRef, String taskRef) {
        require(tenantId, assetIds);
        if ((itemRef == null || itemRef.isBlank()) && (taskRef == null || taskRef.isBlank())) {
            throw ApiException.badRequest("itemRef / taskRef 至少给一个");
        }
        dao.markPushed(tenantId, assetIds, itemRef, taskRef);
        return assetIds.size();
    }

    /**
     * 台账回看。分页走 v449 统一契约（DL-F4 / DL-1 发现 4）：
     * {@code page} 从 1 开始、{@code size} 1–500，非法值 400，不静默夹紧；
     * 响应头 {@code X-Total-Count} 由 Controller 补（同 AssetController 口径）。
     */
    public Map<String, Object> ledger(String tenantId, String status, int page, int size) {
        Paging.requireValid(page, size);
        List<MediaConveyorDao.TransferRow> rows = dao.listLedger(tenantId, status, page, size);
        List<Map<String, Object>> views = new ArrayList<>();
        for (MediaConveyorDao.TransferRow r : rows) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", r.id());
            v.put("assetId", r.assetId());
            v.put("sha256", r.sha256());
            v.put("sizeBytes", r.sizeBytes());
            v.put("mediaRel", r.mediaRel());
            v.put("itemRef", r.itemRef());
            v.put("taskRef", r.taskRef());
            v.put("status", r.status());
            v.put("attempts", r.attempts());
            v.put("lastError", r.lastError());
            v.put("pushed", r.pushed());
            views.add(v);
        }
        return Map.of("total", dao.countLedger(tenantId, status), "rows", views);
    }

    // ---------- 小工具 ----------

    private static void require(String tenantId, List<Long> assetIds) {
        if (tenantId == null || tenantId.isBlank()) {
            throw ApiException.badRequest("缺租户上下文");
        }
        if (assetIds == null || assetIds.isEmpty()) {
            throw ApiException.badRequest("assetIds 不能为空");
        }
        if (assetIds.size() > 500) {
            throw ApiException.badRequest("一次最多搬 500 个资产（分页推进）");
        }
        for (Long id : assetIds) {
            if (id == null || id <= 0) {
                throw ApiException.badRequest("assetIds 含非法 id：" + id);
            }
        }
    }

    /** mos 侧文件名=内容寻址短名：{@code <sha12>[.<ext>]}；uuid8 前缀由对方加（media.py:672-677）。 */
    static String nameOf(MediaConveyorDao.AssetRow a) {
        String sha = a.sha256() == null ? "asset-" + a.id() : a.sha256().substring(0, Math.min(12, a.sha256().length()));
        String ext = MediaConveyorDao.extOf(a.uri());
        return ext.isEmpty() ? sha : sha + "." + ext;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
