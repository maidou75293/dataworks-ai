package com.lab.dataworks.asset;

import com.lab.dataworks.asset.AssetFolderRules.Grant;
import com.lab.dataworks.asset.AssetFolderRules.Principal;
import com.lab.dataworks.asset.AssetFolderService.Snapshot;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资产目录纯规则（V110）：命名/路径/环检测 + 目录授权判定。
 *
 * <p>守卫要能「红得起来」：每一条默认开放、主体匹配、创建者/管理侧兜底、
 * 祖先链可见性的正反两个方向都有断言，把实现改坏必有一条 FAIL。</p>
 */
class AssetFolderRulesTest {

    private static ApiException error(Runnable action, int expectedStatus) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(expectedStatus, e.getStatus(), e.getMessage());
        return e;
    }

    private static Principal user(String username, String... roles) {
        return new Principal(username, Set.of(roles), Set.of("asset:read", "asset:write"));
    }

    private static Principal admin(String username) {
        return new Principal(username, Set.of(), Set.of("asset:read", "asset:write", "user:manage"));
    }

    // ---------------------------------------------------------------- 命名与路径

    @Test
    void normalizeNameTrimsAndAcceptsCommonChars() {
        assertEquals("素材", AssetFolderRules.normalizeName("  素材 "));
        assertEquals("retail_v2", AssetFolderRules.normalizeName("retail_v2"));
        assertEquals("a.b-c d", AssetFolderRules.normalizeName("a.b-c d"));
    }

    @Test
    void normalizeNameRejectsTraversalAndSeparators() {
        error(() -> AssetFolderRules.normalizeName(".."), 400);
        error(() -> AssetFolderRules.normalizeName("a/../b"), 400);
        error(() -> AssetFolderRules.normalizeName("a/b"), 400);
        error(() -> AssetFolderRules.normalizeName("a\\b"), 400);
        error(() -> AssetFolderRules.normalizeName("  "), 400);
        error(() -> AssetFolderRules.normalizeName("x".repeat(65)), 400);
    }

    @Test
    void childPathJoinsWithSlash() {
        assertEquals("/a", AssetFolderRules.childPath(null, "a"));
        assertEquals("/a/b", AssetFolderRules.childPath("/a", "b"));
        error(() -> AssetFolderRules.childPath("/".repeat(1100), "x"), 400);
    }

    @Test
    void insideSubtreeComparesByPathSegment() {
        // 前缀比较必须按路径段：/ab 不是 /a 的子目录
        assertFalse(AssetFolderRules.insideSubtree("/ab", "/a"));
        assertTrue(AssetFolderRules.insideSubtree("/a/b", "/a"));
        assertTrue(AssetFolderRules.insideSubtree("/a", "/a"));
        assertFalse(AssetFolderRules.insideSubtree(null, "/a"));
        assertFalse(AssetFolderRules.insideSubtree("/a", null));
    }

    @Test
    void checkMoveTargetRejectsSelfAndDescendant() {
        error(() -> AssetFolderRules.checkMoveTarget(3L, 3L, "/a", "/a"), 400);
        error(() -> AssetFolderRules.checkMoveTarget(3L, 9L, "/a", "/a/b"), 400);
        // 合法：移到同名前缀的兄弟、移到别的子树、移到根
        AssetFolderRules.checkMoveTarget(3L, 10L, "/a", "/ab");
        AssetFolderRules.checkMoveTarget(3L, 10L, "/a", "/c/d");
        AssetFolderRules.checkMoveTarget(3L, null, "/a", null);
    }

    // ---------------------------------------------------------------- 授权判定

    @Test
    void emptyGrantsMeansOpenDefault() {
        Principal p = user("zhang");
        assertTrue(AssetFolderRules.folderReadable(List.of(), p, false));
        assertTrue(AssetFolderRules.folderWritable(List.of(), p, false));
    }

    @Test
    void grantMatchesByUserNameCaseInsensitively() {
        Grant g = new Grant("USER", "Zhang", true, false);
        assertTrue(AssetFolderRules.folderReadable(List.of(g), user("zhang"), false));
        assertFalse(AssetFolderRules.folderWritable(List.of(g), user("zhang"), false));
        assertFalse(AssetFolderRules.folderReadable(List.of(g), user("li"), false));
        assertFalse(AssetFolderRules.folderWritable(List.of(g), user("li"), false));
    }

    @Test
    void grantMatchesByRoleCodeOfCurrentTenant() {
        Grant g = new Grant("ROLE", "DEVELOPER", true, true);
        assertTrue(AssetFolderRules.folderReadable(List.of(g), user("a", "DEVELOPER", "VIEWER"), false));
        assertTrue(AssetFolderRules.folderWritable(List.of(g), user("a", "DEVELOPER"), false));
        assertFalse(AssetFolderRules.folderReadable(List.of(g), user("a", "VIEWER"), false));
        assertFalse(AssetFolderRules.folderWritable(List.of(g), user("a", "VIEWER"), false));
    }

    @Test
    void creatorAndAdminKeepAccessWhenGrantsCloseFolder() {
        Grant g = new Grant("USER", "someone-else", true, true);
        assertTrue(AssetFolderRules.folderReadable(List.of(g), user("zhang"), true), "创建者兜底可读");
        assertTrue(AssetFolderRules.folderWritable(List.of(g), user("zhang"), true), "创建者兜底可写");
        assertTrue(AssetFolderRules.folderReadable(List.of(g), admin("root"), false), "管理侧可读");
        assertTrue(AssetFolderRules.folderWritable(List.of(g), admin("root"), false), "管理侧可写");
        assertFalse(AssetFolderRules.folderReadable(List.of(g), user("zhang"), false));
    }

    @Test
    void writeGrantImpliesReadAfterNormalize() {
        Grant g = new Grant("USER", "zhang", false, true).normalized();
        assertTrue(g.canRead(), "can_write 自动蕴含 can_read，避免出现「能传看不到」");
        assertTrue(AssetFolderRules.folderReadable(List.of(g), user("zhang"), false));
        assertTrue(AssetFolderRules.folderWritable(List.of(g), user("zhang"), false));
    }

    @Test
    void unknownSubjectTypeNeverMatches() {
        Grant g = new Grant("GROUP", "dept-1", true, true);
        assertFalse(AssetFolderRules.folderReadable(List.of(g), user("zhang"), false));
    }

    // ---------------------------------------------------------------- 内容层判定（S11 两层口径）

    @Test
    void assetOwnerAlwaysCanAccessContent() {
        Principal li = user("li");
        // 「我的上传」（folder_id=NULL）：owner 放行
        assertTrue(AssetFolderRules.assetContentReadable(li, "Li", null, false),
                "owner 大小写不敏感命中，虚拟根无需目录授权");
        // 目录内资产：owner 优先，即便目录内容授权关闭
        assertTrue(AssetFolderRules.assetContentReadable(li, "li", 7L, false));
    }

    @Test
    void foreignRootAssetIsContentLockedForOthers() {
        Principal li = user("li");
        assertFalse(AssetFolderRules.assetContentReadable(li, "zhang", null, false),
                "他人「我的上传」：非 owner 一律无内容权限（S11 评审确认，预签名应 403）");
        assertFalse(AssetFolderRules.assetContentReadable(li, null, null, false));
        assertFalse(AssetFolderRules.assetContentReadable(li, "", null, false));
    }

    @Test
    void folderAssetsFollowFolderGrantsAtContentLayer() {
        Principal li = user("li");
        // 无授权行的默认开放目录：folderReadable 传 true → 内容可取
        assertTrue(AssetFolderRules.assetContentReadable(li, "zhang", 3L, true));
        // 已关闭（自身或祖先无读权）：非 owner 403
        assertFalse(AssetFolderRules.assetContentReadable(li, "zhang", 3L, false));
    }

    @Test
    void adminLikeBypassesContentLayer() {
        assertTrue(AssetFolderRules.assetContentReadable(admin("root"), "zhang", null, false),
                "管理侧权限点对他人「我的上传」仍可达（防锁死口径与目录判定一致）");
    }

    // ---------------------------------------------------------------- 写路径归属（S11 收口）

    @Test
    void foreignRootAssetCannotBeMovedOrDeletedByOthers() {
        Principal li = user("li");
        assertFalse(AssetFolderRules.rootAssetRemovable(li, "zhang"),
                "他人「我的上传」根资产：非 owner 不得挪走/删走（挪走即可取内容 = 绕锁）");
        assertFalse(AssetFolderRules.rootAssetRemovable(li, null));
        assertTrue(AssetFolderRules.rootAssetRemovable(li, "Li"), "owner 大小写不敏感");
        assertTrue(AssetFolderRules.rootAssetRemovable(admin("root"), "zhang"), "管理侧兜底");
    }

    // ---------------------------------------------------------------- 祖先链（内容路径）

    private static Snapshot snapshot(Map<Long, AssetFolderService.Folder> folders,
                                     Map<Long, List<Grant>> grants) {
        return new Snapshot(folders, grants, Map.of(), 0);
    }

    @Test
    void closedAncestorHidesWholeSubtree() {
        // /team1（只授权 zhang） → /team1/private（未配置授权）
        Map<Long, AssetFolderService.Folder> folders = new LinkedHashMap<>();
        folders.put(1L, new AssetFolderService.Folder(1L, null, "team1", "/team1", "boss"));
        folders.put(2L, new AssetFolderService.Folder(2L, 1L, "private", "/team1/private", "boss"));
        Map<Long, List<Grant>> grants = Map.of(1L,
                List.of(new Grant("USER", "zhang", true, true)));
        Snapshot snap = snapshot(folders, grants);

        Principal zhang = user("zhang");
        Principal li = user("li");
        Principal boss = user("boss");

        assertTrue(snap.readable(2L, zhang));
        assertTrue(snap.writable(2L, zhang), "子目录默认开放，且 zhang 可写父目录");
        assertFalse(snap.readable(2L, li), "祖先被关闭 → 整棵子树内容路径断开（S11：目录仍可见，取内容 403）");
        assertFalse(snap.readable(1L, li));
        assertTrue(snap.readable(1L, boss), "创建者对父目录兜底");
        // 内容层组合判定：li 对 /team1/private 内的资产无内容权；zhang/boss 有
        assertFalse(AssetFolderRules.assetContentReadable(li, "someone", 2L, snap.readable(2L, li)));
        assertTrue(AssetFolderRules.assetContentReadable(zhang, "someone", 2L, snap.readable(2L, zhang)));
        assertTrue(AssetFolderRules.assetContentReadable(boss, "someone", 1L, snap.readable(1L, boss)));
    }

    @Test
    void closedChildDoesNotAffectParent() {
        Map<Long, AssetFolderService.Folder> folders = new LinkedHashMap<>();
        folders.put(1L, new AssetFolderService.Folder(1L, null, "pub", "/pub", "boss"));
        folders.put(2L, new AssetFolderService.Folder(2L, 1L, "secret", "/pub/secret", "boss"));
        Map<Long, List<Grant>> grants = Map.of(2L,
                List.of(new Grant("USER", "insider", true, false)));
        Snapshot snap = snapshot(folders, grants);

        Principal outsider = user("outsider");
        assertTrue(snap.readable(1L, outsider), "授权不向下继承，父目录不受影响");
        assertFalse(snap.readable(2L, outsider));
        assertFalse(snap.writable(2L, outsider));
        assertTrue(snap.manageable(1L, outsider), "父目录无授权行，默认开放可管理");
    }

    @Test
    void readOnlyMemberCanSeeButNotManage() {
        Map<Long, AssetFolderService.Folder> folders = new LinkedHashMap<>();
        folders.put(1L, new AssetFolderService.Folder(1L, null, "docs", "/docs", "boss"));
        Map<Long, List<Grant>> grants = Map.of(1L,
                List.of(new Grant("ROLE", "VIEWER", true, false)));
        Snapshot snap = snapshot(folders, grants);
        Principal v = user("vi", "VIEWER");
        assertTrue(snap.readable(1L, v));
        assertFalse(snap.writable(1L, v));
        assertFalse(snap.manageable(1L, v), "只读主体不能配授权，防被反手锁死");
        assertTrue(snap.manageable(1L, admin("boss2")), "管理侧权限点兜底");
    }

    @Test
    void chainRejectsCorruptedParentLoop() {
        Map<Long, AssetFolderService.Folder> folders = new LinkedHashMap<>();
        folders.put(1L, new AssetFolderService.Folder(1L, 2L, "a", "/a", "x"));
        folders.put(2L, new AssetFolderService.Folder(2L, 1L, "b", "/b", "x"));
        Snapshot snap = snapshot(folders, Map.of());
        ApiException e = assertThrows(ApiException.class, () -> snap.readable(1L, user("u")));
        assertEquals(500, e.getStatus(), "脏数据成环要报可诊断错误，不能死循环");
    }
}
