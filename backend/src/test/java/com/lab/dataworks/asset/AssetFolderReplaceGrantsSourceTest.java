package com.lab.dataworks.asset;

import com.lab.dataworks.asset.AssetFolderRules.Principal;
import com.lab.dataworks.auth.RbacService;
import com.lab.dataworks.auth.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S11-3 回归：目录管理页 PUT 全量替换（{@link AssetFolderService#replaceGrants}）
 * 只清 {@code source='MANUAL'} 行，绝不能把 {@code source='ACCESS'}（权限申请审批物化）
 * 一起删掉。
 *
 * <p>症状（修复前）：access_request 仍 APPROVED，但 asset_folder_grant 的 ACCESS 行
 * 已被一次 PUT 抹掉 → 用户访问目录内容 403「请提交权限申请」；重新申请又会撞 409
 * 「已有生效授权」——「已获批却 403 且无法自助恢复」。</p>
 *
 * <p>{@link FakeGrantTable} 模拟 asset_folder_grant 表：DELETE 是否只清 MANUAL
 * 完全取决于被测 SQL 文本里有没有 {@code and source = 'MANUAL'}——
 * 撤掉 {@link AssetFolderService#replaceGrants} 的过滤，第 1 条断言必红。</p>
 */
class AssetFolderReplaceGrantsSourceTest {

    private static final long FOLDER_ID = 7L;
    private static final String TENANT = "tenant_a";
    private static final String ADMIN = "boss";
    private static final String APPROVED_USER = "alice";   // 通过申请拿到 ACCESS 行
    private static final String MANUAL_USER = "bob";       // 目录管理员手工授权
    private static final String NEW_MANUAL_USER = "carol"; // PUT 提交的新手工主体

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RbacService rbac = mock(RbacService.class);
    private final FakeGrantTable table = new FakeGrantTable();
    private AssetFolderService folders;

    @BeforeEach
    void setUp() {
        TenantScope.set(TENANT);
        // 预置：同目录同时存在一条 MANUAL（手工）与一条 ACCESS（申请物化）授权
        table.insert(FOLDER_ID, "USER", MANUAL_USER, true, false, "MANUAL", ADMIN);
        table.insert(FOLDER_ID, "USER", APPROVED_USER, true, true, "ACCESS", ADMIN);

        folders = new AssetFolderService(jdbc, rbac);

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from platform.asset_folder_grant")) {
                return table.snapshotRows();
            }
            if (sql.contains("from platform.asset_folder")) {
                return List.of(folderRow(FOLDER_ID, ADMIN));
            }
            return List.of();   // asset 计数等
        });
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    // validateSubject → 任何主体都视为合法
                    if (sql.contains("from platform.app_user") || sql.contains("from platform.role")) {
                        return 1;
                    }
                    return 0;   // load(p) 的根资产 count
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            Object[] args = inv.getArguments();
            String lower = sql.toLowerCase();
            if (lower.startsWith("delete from platform.asset_folder_grant")) {
                long folderId = ((Number) args[1]).longValue();
                boolean manualOnly = sql.contains("source = 'MANUAL'");
                return table.delete(folderId, manualOnly);
            }
            if (lower.contains("insert into platform.asset_folder_grant")) {
                return table.applyInsert(sql, args);
            }
            return 0;
        });
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    @Test
    void replaceGrants_keepsAccessRows_whenDirectoryAdminSavesManually() {
        Principal admin = new Principal(ADMIN, Set.of(), Set.of());
        List<Map<String, Object>> input = List.of(grantInput("USER", NEW_MANUAL_USER, true, false));

        folders.replaceGrants(FOLDER_ID, input, admin);

        // 核心断言：撤掉 `and source='MANUAL'` 这一行必红。
        assertTrue(table.has(FOLDER_ID, "USER", APPROVED_USER, "ACCESS"),
                "S11-3：审批物化行被目录管理员 PUT 全量替换踩掉了，"
                        + "用户会陷入 access_request=APPROVED 但取内容 403 的死局");
        assertFalse(table.has(FOLDER_ID, "USER", MANUAL_USER, "MANUAL"),
                "手工授权行仍应被 PUT 覆盖");
        assertTrue(table.has(FOLDER_ID, "USER", NEW_MANUAL_USER, "MANUAL"));
    }

    @Test
    void replaceGrants_emptyList_clearsOnlyManualAndKeepsAccess() {
        // PUT 传空 = 撤销全部手工授权，回到默认开放；ACCESS 行不归它管。
        Principal admin = new Principal(ADMIN, Set.of(), Set.of());

        folders.replaceGrants(FOLDER_ID, List.of(), admin);

        assertFalse(table.has(FOLDER_ID, "USER", MANUAL_USER, "MANUAL"));
        assertTrue(table.has(FOLDER_ID, "USER", APPROVED_USER, "ACCESS"),
                "「撤销全部」也不能误伤审批物化行");
        assertEquals(1, table.size(), "目录里只剩 ACCESS 这一行");
    }

    @Test
    void replaceGrants_insertsExplicitSourceColumn_notRelyingOnDbDefault() {
        // 锁定 INSERT 显式带 source：将来若 default 漂移（或别的迁移改了列定义），
        // 这里仍然语义自洽。
        Principal admin = new Principal(ADMIN, Set.of(), Set.of());
        List<Map<String, Object>> input = List.of(grantInput("USER", NEW_MANUAL_USER, true, true));

        folders.replaceGrants(FOLDER_ID, input, admin);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sqlCap.capture(), any(Object[].class));
        boolean sawExplicitSource = sqlCap.getAllValues().stream()
                .anyMatch(s -> s.toLowerCase().contains("insert into platform.asset_folder_grant")
                        && s.toLowerCase().contains("source")
                        && s.contains("'MANUAL'"));
        assertTrue(sawExplicitSource,
                "S11-3 修法：INSERT 必须显式写 source='MANUAL'，不许靠 V112 列默认值");
    }

    // ---------------------------------------------------------------- 假表

    /**
     * asset_folder_grant 的内存替身。DELETE 行为取决于 SQL 文本里有没有
     * {@code source = 'MANUAL'} 谓词——这正是 S11-3 修复的关键开关。
     */
    private static final class FakeGrantTable {
        final List<Row> rows = new ArrayList<>();

        void insert(long folderId, String type, String subjectId,
                    boolean read, boolean write, String source, String createdBy) {
            rows.add(new Row(folderId, type, subjectId, read, write, source, createdBy));
        }

        int delete(long folderId, boolean manualOnly) {
            int n = 0;
            for (var it = rows.iterator(); it.hasNext(); ) {
                Row r = it.next();
                if (r.folderId != folderId) continue;
                if (manualOnly && !"MANUAL".equals(r.source)) continue;
                it.remove();
                n++;
            }
            return n;
        }

        /** args = [sql, folderId, tenant, subjectType, subjectId, canRead, canWrite, actor] */
        int applyInsert(String sql, Object[] args) {
            long folderId = ((Number) args[1]).longValue();
            String type = String.valueOf(args[3]);
            String subject = String.valueOf(args[4]);
            boolean read = Boolean.TRUE.equals(args[5]);
            boolean write = Boolean.TRUE.equals(args[6]);
            String actor = String.valueOf(args[7]);
            // 显式 source 列：SQL 文本含 'MANUAL' 字面量；不含则按 V112 默认（也是 MANUAL）。
            insert(folderId, type, subject, read, write, "MANUAL", actor);
            return 1;
        }

        List<Map<String, Object>> snapshotRows() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Row r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("folder_id", r.folderId);
                m.put("subject_type", r.subjectType);
                m.put("subject_id", r.subjectId);
                m.put("can_read", r.canRead);
                m.put("can_write", r.canWrite);
                out.add(m);
            }
            return out;
        }

        boolean has(long folderId, String type, String subjectId, String source) {
            return rows.stream().anyMatch(r ->
                    r.folderId == folderId
                            && r.subjectType.equals(type)
                            && r.subjectId.equals(subjectId)
                            && r.source.equals(source));
        }

        int size() {
            return rows.size();
        }
    }

    private static final class Row {
        final long folderId;
        final String subjectType;
        final String subjectId;
        final boolean canRead;
        final boolean canWrite;
        final String source;
        final String createdBy;

        Row(long folderId, String subjectType, String subjectId,
            boolean canRead, boolean canWrite, String source, String createdBy) {
            this.folderId = folderId;
            this.subjectType = subjectType;
            this.subjectId = subjectId;
            this.canRead = canRead;
            this.canWrite = canWrite;
            this.source = source;
            this.createdBy = createdBy;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Map<String, Object> folderRow(long id, String createdBy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("parent_id", null);
        m.put("name", "docs");
        m.put("path", "/docs");
        m.put("created_by", createdBy);
        return m;
    }

    private static Map<String, Object> grantInput(String type, String subject,
                                                  boolean read, boolean write) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subjectType", type);
        m.put("subjectId", subject);
        m.put("canRead", read);
        m.put("canWrite", write);
        return m;
    }
}
