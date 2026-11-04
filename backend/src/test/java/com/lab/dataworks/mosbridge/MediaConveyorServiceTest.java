package com.lab.dataworks.mosbridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-5 搬运器判定（卡面验收：清单 / 去重 / 断点续传 / 先搬后推）。
 *
 * <p>与 {@link MosFlywheelServiceTest} 同构：内存桩 DAO + 假网关 + 假本体源，
 * 断言台账行为，不打真实 mos / MinIO。</p>
 *
 * 跑法：{@code mvn -q -Dtest=MediaConveyorServiceTest test}（mosbridge 包）
 */
class MediaConveyorServiceTest {

    private static final String TENANT = "t1";

    private MemDao dao;
    private FakeMediaGateway gateway;
    private FakeSource source;
    private MediaConveyorService svc;

    @BeforeEach
    void setUp() {
        dao = new MemDao();
        gateway = new FakeMediaGateway();
        source = new FakeSource();
        svc = new MediaConveyorService(dao, gateway, source);
    }

    private MediaConveyorDao.AssetRow asset(long id, String sha, String ext) {
        return new MediaConveyorDao.AssetRow(id, TENANT,
                "s3://media/" + TENANT + "/202610/" + sha + "." + ext, sha, 100L, "IMAGE");
    }

    // ---------- ① 清单 ----------

    @Test
    void planDistinguishesMissingFirstAndSkip() {
        dao.assets.add(asset(1, "a".repeat(64), "jpg"));
        assertEquals("MISSING", svc.plan(TENANT, List.of(99L)).get(0).action());
        assertEquals("TRANSFER", svc.plan(TENANT, List.of(1L)).get(0).action());

        svc.transfer(TENANT, List.of(1L), "u");
        MediaConveyorService.PlanRow skip = svc.plan(TENANT, List.of(1L)).get(0);
        assertEquals("SKIP_OK", skip.action());
        assertEquals("image/202610/x", skip.mediaRel());
    }

    @Test
    void planRejectsBadInputExplicitly() {
        assertThrows(Exception.class, () -> svc.plan(TENANT, List.of()));
        assertThrows(Exception.class, () -> svc.plan(TENANT, List.of(0L)));
        assertThrows(Exception.class, () -> svc.plan("", List.of(1L)));
    }

    // ---------- ② 去重：同 src checksum 不重传 ----------

    @Test
    void sameShaNotReuploaded() {
        dao.assets.add(asset(1, "b".repeat(64), "png"));
        svc.transfer(TENANT, List.of(1L), "u");
        svc.transfer(TENANT, List.of(1L), "u");
        assertEquals(1, gateway.calls);
        assertEquals(1, gateway.uploaded.size());
    }

    @Test
    void shaChangeForcesRetransfer() {
        MediaConveyorDao.AssetRow a = asset(1, "c".repeat(64), "jpg");
        dao.assets.add(a);
        svc.transfer(TENANT, List.of(1L), "u");
        dao.assets.set(0, asset(1, "d".repeat(64), "jpg"));   // 内容寻址变化（同 id 换 sha）
        svc.transfer(TENANT, List.of(1L), "u");
        assertEquals(2, gateway.calls);
        assertEquals("d".repeat(64), dao.ledger.get(1L).substring(0, 64));
    }

    // ---------- ③ 断点续传 ----------

    @Test
    void failedRowRetriedThenOk() {
        dao.assets.add(asset(7, "e".repeat(64), "jpg"));
        gateway.failNext = true;
        assertThrows(RuntimeException.class, () -> svc.transfer(TENANT, List.of(7L), "u"));
        assertEquals("FAILED", dao.status.get(7L));
        assertEquals(1, dao.attempts.get(7L));
        assertEquals("RETRY_FAILED", svc.plan(TENANT, List.of(7L)).get(0).action());

        Map<Long, String> rels = svc.transfer(TENANT, List.of(7L), "u");
        assertEquals("OK", dao.status.get(7L));
        assertEquals(2, dao.attempts.get(7L));
        assertEquals("image/202610/x", rels.get(7L));
        assertNull(dao.lastError.get(7L));
    }

    // ---------- ④ 先搬后推：任一失败整批抛 ----------

    @Test
    void anyFailureAbortsWholeBatch() {
        dao.assets.add(asset(1, "f".repeat(64), "jpg"));
        dao.assets.add(asset(2, "0".repeat(64), "png"));
        gateway.failForShaPrefix = "00";
        var ex = assertThrows(com.lab.dataworks.common.ApiException.class,
                () -> svc.ensureMediaForPush(TENANT, List.of(1L, 2L), "u"));
        assertEquals(502, ex.getStatus());
        assertTrue(ex.getMessage().contains("整批不推"));
        assertEquals("OK", dao.status.get(1L));       // 成功行仍入台账（下次 SKIP）
        assertEquals("FAILED", dao.status.get(2L));
    }

    @Test
    void missingAssetCountsAsFailureAndAborts() {
        // DL-F5 / 发现 5：纯本地资产缺失是前置条件问题 → 400，不再冒用 502
        dao.assets.add(asset(1, "1".repeat(64), "jpg"));
        var ex = assertThrows(com.lab.dataworks.common.ApiException.class,
                () -> svc.transfer(TENANT, List.of(1L, 404L), "u"));
        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("整批不推"));
        assertTrue(ex.getMessage().contains("不存在"), "文案保留资产缺失原因：" + ex.getMessage());
        assertEquals("OK", dao.status.get(1L));
        assertFalse(dao.ledger.containsKey(404L));    // MISSING 不落台账（不是搬运失败）
    }

    @Test
    void allMissingBatchIs400NotGatewayError() {
        // 整批都不存在（如 assetIds=[999999999] 的现场复现）→ 400
        var ex = assertThrows(com.lab.dataworks.common.ApiException.class,
                () -> svc.transfer(TENANT, List.of(999999999L), "u"));
        assertEquals(400, ex.getStatus());
        assertEquals(0, gateway.calls);               // 没有触碰网关
    }

    @Test
    void mixedMissingAndUpstreamFailureStays502() {
        // 有真·网关失败时整批仍按 502（监控口径），文案附本地缺失数
        dao.assets.add(asset(1, "0".repeat(64), "png"));
        gateway.failForShaPrefix = "00";
        var ex = assertThrows(com.lab.dataworks.common.ApiException.class,
                () -> svc.transfer(TENANT, List.of(1L, 404L), "u"));
        assertEquals(502, ex.getStatus());
        assertTrue(ex.getMessage().contains("另有本地资产缺失 1 个"), ex.getMessage());
    }

    @Test
    void disabledGatewayIs503() {
        dao.assets.add(asset(1, "2".repeat(64), "jpg"));
        gateway.enabled = false;
        assertEquals(503, assertThrows(com.lab.dataworks.common.ApiException.class,
                () -> svc.transfer(TENANT, List.of(1L), "u")).getStatus());
    }

    // ---------- ⑤ 命名 / 回填 / 租户过滤 ----------

    @Test
    void contentAddressedName() {
        String sha = "3".repeat(64);
        assertEquals(sha.substring(0, 12) + ".jpg",
                MediaConveyorService.nameOf(asset(1, sha, "jpg")));
        assertEquals("", MediaConveyorDao.extOf("s3://media/t/202610/" + sha));
    }

    @Test
    void markPushedNeedsOneRef() {
        dao.assets.add(asset(1, "4".repeat(64), "jpg"));
        svc.transfer(TENANT, List.of(1L), "u");
        assertThrows(Exception.class, () -> svc.markPushed(TENANT, List.of(1L), null, " "));
        assertEquals(1, svc.markPushed(TENANT, List.of(1L), "ds_a@v1", "12/34"));
        assertTrue(dao.pushed.get(1L));
        assertEquals("ds_a@v1", dao.itemRef.get(1L));
    }

    @Test
    void assetsAreTenantScoped() {
        dao.assets.add(new MediaConveyorDao.AssetRow(1, "other",
                "s3://media/other/202610/x.jpg", "x", 1L, "IMAGE"));
        assertEquals("MISSING", svc.plan(TENANT, List.of(1L)).get(0).action());
    }

    private static void assertFalse(boolean v) {
        org.junit.jupiter.api.Assertions.assertFalse(v);
    }

    // ---------- 内存桩 ----------

    static class MemDao implements MediaConveyorDao {
        final List<AssetRow> assets = new ArrayList<>();
        final Map<Long, String> ledger = new HashMap<>();      // assetId -> sha（验证 upsert 覆盖）
        final Map<Long, String> status = new HashMap<>();
        final Map<Long, Integer> attempts = new HashMap<>();
        final Map<Long, String> lastError = new HashMap<>();
        final Map<Long, Boolean> pushed = new HashMap<>();
        final Map<Long, String> itemRef = new HashMap<>();
        final Map<Long, String> rel = new HashMap<>();

        public List<AssetRow> findAssets(String tenantId, List<Long> ids) {
            List<AssetRow> out = new ArrayList<>();
            for (AssetRow a : assets) {
                if (a.tenantId().equals(tenantId) && ids.contains(a.id())) {
                    out.add(a);
                }
            }
            return out;
        }

        public TransferRow findByAsset(String tenantId, long assetId) {
            if (!status.containsKey(assetId)) {
                return null;
            }
            return new TransferRow(assetId, tenantId, assetId, "s3://media/x", ledger.get(assetId),
                    100L, rel.get(assetId), null, itemRef.get(assetId), null,
                    status.get(assetId), attempts.getOrDefault(assetId, 0),
                    lastError.get(assetId), pushed.getOrDefault(assetId, false));
        }

        public void upsertResult(String tenantId, long assetId, String assetUri, String sha256,
                                 Long sizeBytes, String mediaRel, String lsUrl, boolean ok,
                                 String error, String createdBy) {
            ledger.put(assetId, sha256);
            status.put(assetId, ok ? "OK" : "FAILED");
            attempts.merge(assetId, 1, Integer::sum);
            lastError.put(assetId, ok ? null : error);
            if (ok) {
                rel.put(assetId, mediaRel);
            }
        }

        public void markPushed(String tenantId, List<Long> assetIds, String ir, String tr) {
            for (Long id : assetIds) {
                pushed.put(id, true);
                itemRef.put(id, ir);
            }
        }

        public List<TransferRow> listLedger(String tenantId, String st, int page, int size) {
            return List.of();
        }

        public long countLedger(String tenantId, String st) {
            return status.size();
        }
    }

    static class FakeMediaGateway implements MosMediaGateway {
        int calls;
        boolean enabled = true;
        boolean failNext;
        String failForShaPrefix;
        final List<String> uploaded = new ArrayList<>();

        public boolean enabled() {
            return enabled;
        }

        public String disabledReason() {
            return "测试桩未配置";
        }

        public Map<String, Object> uploadMedia(byte[] bytes, String filename, String folder) {
            calls++;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("模拟网络中断");
            }
            if (failForShaPrefix != null && filename.startsWith(failForShaPrefix)) {
                throw new IllegalStateException("模拟对象读取/上传失败 " + filename);
            }
            uploaded.add(filename);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", "image/202610/x");
            m.put("ls_url", "http://172.19.15.189:8090/media/image/202610/x");
            return m;
        }
    }

    static class FakeSource implements AssetBytesSource {
        public byte[] read(String uri) {
            return "bytes".getBytes();
        }
    }
}
