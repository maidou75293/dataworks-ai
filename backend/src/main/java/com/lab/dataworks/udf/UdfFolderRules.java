package com.lab.dataworks.udf;

import com.lab.dataworks.common.ApiException;

import java.util.regex.Pattern;

/**
 * 多级文件夹的纯规则：命名、路径拼接、父子环检测、非空删除策略。
 *
 * <p>抽成无状态静态方法，是为了让「移动会不会成环」「非空删除要不要 force」
 * 这类判定能直接单测，不必起 Spring/JdbcTemplate。{@link UdfFolderService} 只负责查库，
 * 判定一律走这里，避免同一条规则在 SQL 与内存里各写一遍。</p>
 */
final class UdfFolderRules {

    /** 首字符必须是字母/数字/下划线；后续允许空格、点、横线（与界面提示一致）。 */
    private static final Pattern NAME = Pattern.compile(
            "^[\\p{L}\\p{N}_][\\p{L}\\p{N}_ .-]{0,63}$");
    private static final int MAX_NAME = 64;
    private static final int MAX_PATH = 1024;

    private UdfFolderRules() {
    }

    static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME || !NAME.matcher(name).matches()
                || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw ApiException.badRequest(
                    "文件夹名不合法（1-64 位，字母/数字/中文/空格/点/横线/下划线，不能含 / 或 ..）");
        }
        return name;
    }

    /**
     * 子文件夹的绝对路径：父为空（根目录）时 {@code /a}，否则 {@code /a/b}。
     * 同时校验路径总长，防止深层嵌套把 varchar(1024) 撑爆。
     */
    static String childPath(String parentPath, String name) {
        String path;
        if (parentPath == null || parentPath.isBlank()) {
            path = "/" + name;
        } else {
            path = (parentPath.endsWith("/") ? parentPath : parentPath + "/") + name;
        }
        requirePath(path);
        return path;
    }

    /**
     * {@code candidate} 是否就是 {@code subtreeRoot} 或位于它下面。
     *
     * <p>按路径段比较：{@code /ab} 不是 {@code /a} 的子目录。空值一律 false。</p>
     */
    static boolean insideSubtree(String candidate, String subtreeRoot) {
        if (candidate == null || subtreeRoot == null) {
            return false;
        }
        String root = subtreeRoot.length() > 1 && subtreeRoot.endsWith("/")
                ? subtreeRoot.substring(0, subtreeRoot.length() - 1) : subtreeRoot;
        return candidate.equals(root) || candidate.startsWith(root + "/");
    }

    /**
     * 移动前的环检测：不能移到自己下面，也不能移到自己的子孙目录下。
     *
     * @param selfId           被移动文件夹 id（可为 null，仅做路径判定）
     * @param targetParentId   目标父文件夹 id（null=根目录）
     * @param selfPath         被移动文件夹的当前路径
     * @param targetParentPath 目标父文件夹的当前路径（null=根目录）
     */
    static void checkMoveTarget(Long selfId, Long targetParentId,
                                String selfPath, String targetParentPath) {
        if (selfId != null && targetParentId != null
                && selfId.longValue() == targetParentId.longValue()) {
            throw ApiException.badRequest("不能把文件夹移到它自己下面");
        }
        if (insideSubtree(targetParentPath, selfPath)) {
            throw ApiException.badRequest("不能把文件夹移到自己的子孙目录下");
        }
    }

    /**
     * 非空删除策略：默认拒绝（409），force=true 才级联。
     *
     * <p>选「默认拒绝 + force 级联文件夹」而不是「直接级联」：资源是版本化二进制，
     * 误删会连带破坏函数引用，所以必须让调用方显式确认。资源本身不删，
     * 由 {@code udf_resource_folder_fk on delete set null} 移到根目录。</p>
     *
     * @param subtreeFolders 含自身在内的整棵子树文件夹数
     * @param resources      这些文件夹里直接挂的资源数
     */
    static void checkDelete(boolean force, int subtreeFolders, int resources) {
        if (!force && (subtreeFolders > 1 || resources > 0)) {
            throw ApiException.conflict("文件夹非空：" + Math.max(0, subtreeFolders - 1)
                    + " 个子文件夹、" + resources
                    + " 个资源；确认可 force=true 级联（资源会移到根目录）");
        }
    }

    static void requirePath(String path) {
        if (path == null || path.length() > MAX_PATH) {
            throw ApiException.badRequest("文件夹路径过长（上限 " + MAX_PATH + "）");
        }
    }
}
