package com.lab.dataworks.udf;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.ProjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件夹移动：目标路径重算 + 整棵子树前缀替换。
 *
 * <p>环检测（移到自己/子孙）由 {@link UdfFolderRulesTest} 覆盖；这里验证「允许的移动」
 * 会落两条 SQL：先改自身 parent_id/path，再用前缀替换更新子孙；移到根目录时
 * path 回落到 {@code /name}。</p>
 */
class UdfFolderServiceMoveTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private UdfFolderService folders;

    @BeforeEach
    void setUp() {
        TenantScope.set("tenant_a");
        folders = new UdfFolderService(jdbc,
                new UdfService(jdbc, mock(DorisAdminService.class),
                        mock(ProjectStorageService.class),
                        "build/udf-test", "http://backend.test", "python3"));
        // ownedFolder / requireFolder 走同一条 queryForList 重载，按 SQL 文本分流
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("select f.* from platform.udf_folder f")) {
                return List.of(folder(5L, "b", "/a/b", null));
            }
            if (sql.contains("select id, parent_id as \"parentId\"")) {
                return List.of(folder(9L, "x", "/x", null));
            }
            return List.of();
        });
        // 目标目录下没有同名文件夹
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    @Test
    void moveFolderRewritesOwnPathAndSubtreePrefix() {
        folders.move(5L, 9L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(sql.capture(), args.capture());

        List<Object[]> calls = args.getAllValues();
        // 1）自身：parent_id=9，path 由 /a/b 变为 /x/b
        assertEquals(9L, calls.get(0)[0]);
        assertEquals("/x/b", calls.get(0)[1]);
        assertEquals(5L, calls.get(0)[2]);

        // 2）整棵子树：把 oldPath 前缀替换为 newPath
        assertTrue(sql.getAllValues().get(1).contains("substring(path from"),
                sql.getAllValues().get(1));
        assertEquals("/x/b", calls.get(1)[0]);
        assertEquals("/a/b", calls.get(1)[1]);
        assertEquals(1L, calls.get(1)[2]);
    }

    @Test
    void moveToRootProducesSlashNamePath() {
        folders.move(5L, null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        Object[] move = args.getAllValues().get(0);
        assertNull(move[0]);
        assertEquals("/b", move[1]);
        assertEquals(5L, move[2]);
    }

    private static Map<String, Object> folder(long id, String name, String path, Long parentId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("project_id", 1L);
        row.put("parent_id", parentId);
        row.put("name", name);
        row.put("path", path);
        return row;
    }
}
