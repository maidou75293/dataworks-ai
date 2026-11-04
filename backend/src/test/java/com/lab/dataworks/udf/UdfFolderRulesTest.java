package com.lab.dataworks.udf;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多级文件夹纯规则：命名、路径拼接、父子环检测、非空删除策略。
 *
 * <p>对应 {@link UdfFolderService} 的写路径，不依赖数据库。重点验证两条容易出错的不变量：
 * 1）{@code /ab} 不是 {@code /a} 的子目录（前缀比较必须按路径段）；
 * 2）非空文件夹默认 409，必须 force 才级联。</p>
 */
class UdfFolderRulesTest {

    private static ApiException error(Runnable action, int expectedStatus) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(expectedStatus, e.getStatus(), e.getMessage());
        return e;
    }

    // ---------------------------------------------------------------- 命名

    @Test
    void normalizeNameTrimsAndAcceptsChineseAndSeparators() {
        assertEquals("实时", UdfFolderRules.normalizeName("  实时 "));
        assertEquals("retail_v2", UdfFolderRules.normalizeName("retail_v2"));
        assertEquals("a.b-c d", UdfFolderRules.normalizeName("a.b-c d"));
    }

    @Test
    void normalizeNameRejectsTraversalSeparatorsAndReservedStart() {
        error(() -> UdfFolderRules.normalizeName(".."), 400);
        error(() -> UdfFolderRules.normalizeName("a/../b"), 400);
        error(() -> UdfFolderRules.normalizeName("a/b"), 400);
        error(() -> UdfFolderRules.normalizeName("a\\b"), 400);
        error(() -> UdfFolderRules.normalizeName(""), 400);
        error(() -> UdfFolderRules.normalizeName(null), 400);
        error(() -> UdfFolderRules.normalizeName(".hidden"), 400);
        error(() -> UdfFolderRules.normalizeName("x".repeat(65)), 400);
    }

    // ---------------------------------------------------------------- 路径

    @Test
    void childPathJoinsParentAndName() {
        assertEquals("/a", UdfFolderRules.childPath(null, "a"));
        assertEquals("/a", UdfFolderRules.childPath("", "a"));
        assertEquals("/a", UdfFolderRules.childPath("  ", "a"));
        assertEquals("/a/b", UdfFolderRules.childPath("/a", "b"));
        assertEquals("/a/b", UdfFolderRules.childPath("/a/", "b"));
    }

    @Test
    void insideSubtreeMatchesWholePathSegmentsOnly() {
        assertTrue(UdfFolderRules.insideSubtree("/a", "/a"));
        assertTrue(UdfFolderRules.insideSubtree("/a/b", "/a"));
        assertTrue(UdfFolderRules.insideSubtree("/a/b/c", "/a"));
        assertFalse(UdfFolderRules.insideSubtree("/ab", "/a"),
                "/ab 与 /a 只是字符串前缀，不是子目录");
        assertFalse(UdfFolderRules.insideSubtree("/a", "/a/b"));
        assertFalse(UdfFolderRules.insideSubtree(null, "/a"));
        assertFalse(UdfFolderRules.insideSubtree("/a", null));
    }

    // ---------------------------------------------------------------- 环检测

    @Test
    void moveIntoItselfIsRejected() {
        ApiException e = error(() -> UdfFolderRules.checkMoveTarget(7L, 7L, "/a", null), 400);
        assertTrue(e.getMessage().contains("自己"), e.getMessage());
    }

    @Test
    void moveIntoOwnDescendantIsRejected() {
        ApiException e = error(
                () -> UdfFolderRules.checkMoveTarget(7L, 9L, "/a", "/a/b"), 400);
        assertTrue(e.getMessage().contains("子孙"), e.getMessage());
        // 目标父路径恰好等于自身路径（理论上只可能来自环）同样拒绝
        error(() -> UdfFolderRules.checkMoveTarget(7L, 9L, "/a", "/a"), 400);
    }

    @Test
    void moveToRootAncestorOrSiblingIsAllowed() {
        UdfFolderRules.checkMoveTarget(7L, null, "/a/b", null);
        UdfFolderRules.checkMoveTarget(7L, 3L, "/a/b", "/c");
        UdfFolderRules.checkMoveTarget(7L, 3L, "/a/b", "/a");
        // 同名前缀但是同级：/a → /ab 不是成环
        UdfFolderRules.checkMoveTarget(7L, 3L, "/a", "/ab");
    }

    // ---------------------------------------------------------------- 非空删除策略

    @Test
    void emptyFolderDeletesWithoutForce() {
        UdfFolderRules.checkDelete(false, 1, 0);
    }

    @Test
    void nonEmptyFolderRequiresForce() {
        ApiException byChildren = error(() -> UdfFolderRules.checkDelete(false, 2, 0), 409);
        assertTrue(byChildren.getMessage().contains("1 个子文件夹"), byChildren.getMessage());

        ApiException byResources = error(() -> UdfFolderRules.checkDelete(false, 1, 3), 409);
        assertTrue(byResources.getMessage().contains("3 个资源"), byResources.getMessage());
    }

    @Test
    void forceCascadesFoldersAndKeepsResources() {
        // force 只级联文件夹；资源由 FK on delete set null 移到根目录，不在这里删
        UdfFolderRules.checkDelete(true, 5, 9);
        UdfFolderRules.checkDelete(true, 1, 0);
    }
}
