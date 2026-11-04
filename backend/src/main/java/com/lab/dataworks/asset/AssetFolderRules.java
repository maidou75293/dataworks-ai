package com.lab.dataworks.asset;

import com.lab.dataworks.common.ApiException;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 资产目录的纯规则：命名/路径/环检测 + 目录授权判定（{@link Acl}）。
 *
 * <p>与 {@code UdfFolderRules} 同样的理由抽成无状态静态方法：「移动会不会成环」
 * 「这个用户对这个目录算不算可读/可写」这类判定不起 Spring/JdbcTemplate 就能单测。
 * {@link AssetFolderService} 只负责查库，判定一律走这里，
 * 避免同一条规则在 SQL 与内存里各写一遍。</p>
 */
final class AssetFolderRules {

    /** 首字符必须是字母/数字/下划线；后续允许空格、点、横线（与界面提示一致）。 */
    private static final Pattern NAME = Pattern.compile(
            "^[\\p{L}\\p{N}_][\\p{L}\\p{N}_ .-]{0,63}$");
    private static final int MAX_NAME = 64;
    private static final int MAX_PATH = 1024;
    /** 父链遍历保护：正常情况下移动规则已禁止成环，这里只是防御脏数据导致死循环。 */
    private static final int MAX_DEPTH = 64;

    private AssetFolderRules() {
    }

    static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME || !NAME.matcher(name).matches()
                || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw ApiException.badRequest(
                    "目录名不合法（1-64 位，字母/数字/中文/空格/点/横线/下划线，不能含 / 或 ..）");
        }
        return name;
    }

    /** 子目录绝对路径：父为空（虚拟根）时 {@code /a}，否则 {@code /a/b}；同时校验总长。 */
    static String childPath(String parentPath, String name) {
        String path;
        if (parentPath == null || parentPath.isBlank()) {
            path = "/" + name;
        } else {
            path = (parentPath.endsWith("/") ? parentPath : parentPath + "/") + name;
        }
        if (path.length() > MAX_PATH) {
            throw ApiException.badRequest("目录路径过长（上限 " + MAX_PATH + "）");
        }
        return path;
    }

    /** {@code candidate} 是否就是 {@code subtreeRoot} 或位于它下面（按路径段比较，/ab 不算 /a 的子目录）。 */
    static boolean insideSubtree(String candidate, String subtreeRoot) {
        if (candidate == null || subtreeRoot == null) {
            return false;
        }
        String root = subtreeRoot.length() > 1 && subtreeRoot.endsWith("/")
                ? subtreeRoot.substring(0, subtreeRoot.length() - 1) : subtreeRoot;
        return candidate.equals(root) || candidate.startsWith(root + "/");
    }

    /** 移动前环检测：目标父目录不能是自己，也不能是自己的子孙。 */
    static void checkMoveTarget(Long selfId, Long targetParentId,
                                String selfPath, String targetParentPath) {
        if (selfId != null && targetParentId != null
                && selfId.longValue() == targetParentId.longValue()) {
            throw ApiException.badRequest("不能把目录移到它自己下面");
        }
        if (insideSubtree(targetParentPath, selfPath)) {
            throw ApiException.badRequest("不能把目录移到自己的子孙目录下");
        }
    }

    static int maxDepth() {
        return MAX_DEPTH;
    }

    // ---------------------------------------------------------------- 授权判定

    /** 授权主体类型。 */
    static final String SUBJECT_USER = "USER";
    static final String SUBJECT_ROLE = "ROLE";

    /** 一条授权：USER 按 username 匹配，ROLE 按本租户角色编码匹配。 */
    record Grant(String subjectType, String subjectId, boolean canRead, boolean canWrite) {

        /** 存储前归一：写必然可读（否则会出现「能上传却看不到」的怪状态）。 */
        Grant normalized() {
            return new Grant(subjectType, subjectId == null ? "" : subjectId.trim(),
                    canRead || canWrite, canWrite);
        }
    }

    /** 判定所需的调用方身份快照。 */
    record Principal(String username, Set<String> roleCodes, Set<String> atoms) {

        /**
         * 管理侧兜底：平台管理员（system:manage / tenant:manage）或租户管理员（user:manage，
         * 内置 ADMIN 模板的唯一代表点）不受目录授权约束，防止把自己人锁在门外。
         */
        boolean adminLike() {
            return atoms.contains("system:manage")
                    || atoms.contains("tenant:manage")
                    || atoms.contains("user:manage");
        }

        boolean matches(Grant grant) {
            if (grant.subjectId() == null || grant.subjectId().isBlank()) {
                return false;
            }
            if (SUBJECT_USER.equalsIgnoreCase(grant.subjectType())) {
                return grant.subjectId().equalsIgnoreCase(username);
            }
            if (SUBJECT_ROLE.equalsIgnoreCase(grant.subjectType())) {
                return roleCodes.stream().anyMatch(c -> c.equalsIgnoreCase(grant.subjectId()));
            }
            return false;
        }
    }

    /**
     * **内容层**判定：预签名下载、文件本体、详情敏感字段（uri / sha256 / url）。
     *
     * <ul>
     *   <li>管理侧权限点持有者放行（防锁死，与目录判定同一口径）；</li>
     *   <li>owner（上传者 {@code created_by}）视为对自有资产有内容权限；</li>
     *   <li>虚拟根「我的上传」（{@code folder_id = NULL}）不做目录级授权：
     *       仅 owner / 管理侧可取内容——同租户他人一律 403；</li>
     *   <li>目录内资产沿用目录授权（无授权行 = 本租户默认开放；
     *       {@code folderReadable} 传入「自身及全部祖先」的路径判定结果）。</li>
     * </ul>
     *
     * <p>目录层（树 / 列表 / 详情元数据：名称、类型、大小、归属）不做本判定——
     * 租户内全员可见，由 {@code AssetService} 直接下发。</p>
     */
    static boolean assetContentReadable(Principal p, String owner, Long folderId,
                                        boolean folderReadable) {
        if (p.adminLike()) {
            return true;
        }
        if (owner != null && !owner.isBlank() && owner.equalsIgnoreCase(p.username())) {
            return true;
        }
        if (folderId == null) {
            return false; // 「我的上传」：非 owner / 非管理侧一律无内容权限
        }
        return folderReadable;
    }

    /**
     * 写路径归属判定：<b>能不能把这条资产挪走 / 删走</b>（S11 收口，终验会话报的洞）。
     *
     * <ul>
     *   <li>目录内资产：由目录<b>写</b>判定（{@link #folderWritable}）负责，不走这里；</li>
     *   <li>「我的上传」（{@code folderId = null}）：没有目录可判定，按<b>归属</b>收口——
     *       仅 owner（上传者）或管理侧。同租户其他持 {@code asset:write} 的成员一律否。</li>
     * </ul>
     *
     * <p>判定线必须和内容层一致：拿不到内容的人如果能把它挪进自己有内容权的目录，
     * 挪完 {@code /url} 就直接 200——虚拟根的内容锁等于没有。</p>
     */
    static boolean rootAssetRemovable(Principal p, String owner) {
        return assetContentReadable(p, owner, null, false);
    }

    /**
     * 单个目录的可读判定（不含祖先链）——现仅用于**内容层与写操作**收口
     * （预签名、上传、移动、删除）；目录/列表可见性不再走它（S11 评审确认）。
     * 管理员或创建者兜底 → 无授权行默认开放 → 有授权行需匹配到 can_read（或 can_write）。
     */
    static boolean folderReadable(List<Grant> grants, Principal p, boolean isCreator) {
        if (p.adminLike() || isCreator) {
            return true;
        }
        if (grants == null || grants.isEmpty()) {
            return true;
        }
        return grants.stream().anyMatch(g -> p.matches(g) && (g.canRead() || g.canWrite()));
    }

    /** 单个目录的可写/可管理判定（不含祖先链）：规则同上，命中条件换成 can_write。 */
    static boolean folderWritable(List<Grant> grants, Principal p, boolean isCreator) {
        if (p.adminLike() || isCreator) {
            return true;
        }
        if (grants == null || grants.isEmpty()) {
            return true;
        }
        return grants.stream().anyMatch(g -> p.matches(g) && g.canWrite());
    }

    /** 授权行是否等价（用于校验重复主体）。 */
    static String subjectKey(Grant grant) {
        return (grant.subjectType() == null ? "" : grant.subjectType().trim().toUpperCase())
                + ":" + (grant.subjectId() == null ? "" : grant.subjectId().trim().toLowerCase());
    }
}
