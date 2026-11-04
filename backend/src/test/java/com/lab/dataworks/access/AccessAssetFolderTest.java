package com.lab.dataworks.access;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S11-2（V112）资产目录内容权接入申请链路的纯函数面：
 * resourceType 枚举校验（非法一律 400 且列全合法取值）、授权位口径
 * （WRITE 蕴含 READ、重算取并集）、path 展示快照截断。
 *
 * <p>HTTP/落库侧（查重、物化、回收重算、审批可见性）见
 * {@code scripts/e2e/access-asset-folder-test.py}（部署后跑）。</p>
 */
class AccessAssetFolderTest {

    @Test
    void acceptsAllFourResourceTypesCaseInsensitive() {
        assertEquals("DATABASE", AccessController.normalizeResourceType("database"));
        assertEquals("TABLE", AccessController.normalizeResourceType("  Table "));
        assertEquals("COLUMN", AccessController.normalizeResourceType("COLUMN"));
        // 本卡新增枚举：大小写/空白宽容，与库表同口径
        assertEquals("ASSET_FOLDER", AccessController.normalizeResourceType("asset_folder"));
    }

    @Test
    void illegalResourceTypeIs400AndListsEveryLegalValue() {
        for (String bad : new String[]{null, "", "   ", "VIEW", "ASSET", "TABLE_COLUMN"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> AccessController.normalizeResourceType(bad),
                    "非法资源类型必须显式 400，禁止静默降级: " + bad);
            assertEquals(400, e.getStatus());
            // 403 文案说「请提交权限申请」，400 就得把路说全：四个合法取值一个都不能少
            assertTrue(e.getMessage().contains("DATABASE"), e.getMessage());
            assertTrue(e.getMessage().contains("TABLE"), e.getMessage());
            assertTrue(e.getMessage().contains("COLUMN"), e.getMessage());
            assertTrue(e.getMessage().contains("ASSET_FOLDER"), e.getMessage());
        }
    }

    @Test
    void errorMessageEchoesReceivedValue() {
        ApiException e = assertThrows(ApiException.class,
                () -> AccessController.normalizeResourceType("Schema"));
        assertTrue(e.getMessage().contains("Schema"), e.getMessage());
        ApiException empty = assertThrows(ApiException.class,
                () -> AccessController.normalizeResourceType("  "));
        assertTrue(empty.getMessage().contains("（空）"), empty.getMessage());
    }

    @Test
    void writeGrantImpliesRead() {
        assertArrayEquals(new boolean[]{true, false}, AssetFolderGrants.grantBitsFor("READ"));
        assertArrayEquals(new boolean[]{true, true}, AssetFolderGrants.grantBitsFor("WRITE"));
        assertArrayEquals(new boolean[]{true, true}, AssetFolderGrants.grantBitsFor("write"),
                "与 AssetFolderRules.Grant.normalized() 一致：can_write 自动蕴含 can_read");
    }

    @Test
    void recomputeTakesUnionAndDropsWhenNothingRemains() {
        assertArrayEquals(new boolean[]{false, false}, AssetFolderGrants.recomputeBits(false, false),
                "无剩余有效申请 = 撤掉 ACCESS 行（回到 MANUAL/默认开放）");
        assertArrayEquals(new boolean[]{true, false}, AssetFolderGrants.recomputeBits(true, false));
        assertArrayEquals(new boolean[]{true, true}, AssetFolderGrants.recomputeBits(false, true),
                "只剩 WRITE 申请时读位也必须为真");
        assertArrayEquals(new boolean[]{true, true}, AssetFolderGrants.recomputeBits(true, true));
    }

    @Test
    void displayPathTruncatesToSchemaNameLimit() {
        assertEquals("/营收数据", AssetFolderGrants.displayPath(" /营收数据 "));
        assertEquals("", AssetFolderGrants.displayPath(null));
        String longPath = "/" + "字".repeat(300);
        String snapshot = AssetFolderGrants.displayPath(longPath);
        assertEquals(AssetFolderGrants.DISPLAY_PATH_MAX, snapshot.length(),
                "schema_name 是 varchar(128)，快照超长必截（真键是 folder_id，不影响判定/去重）");
        assertTrue(snapshot.endsWith("…"));
    }

    @Test
    void folderRequestStaysSingleApproval() {
        // levelOfRequest 对 ASSET_FOLDER 恒 L3：目录内容权与字段密级无关。
        // L3 + PENDING 通过 → 一步到 APPROVED、90 天（与库表普通资源同状态机，不自造第二套）。
        AccessController.ApprovalStep step =
                AccessController.approvalStep("APPROVED", "PENDING", "L3", "w2", null);
        assertEquals("APPROVED", step.status());
        assertEquals(90, step.grantDays());
    }
}
