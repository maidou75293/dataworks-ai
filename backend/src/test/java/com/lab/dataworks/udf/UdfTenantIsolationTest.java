package com.lab.dataworks.udf;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.ProjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UDF 资源/文件夹的租户隔离（服务层）。
 *
 * <p>隔离靠 SQL：by-id 查询必须同时带 {@code id} 与
 * {@code project_id in (select ... where p.tenant_id = ?)}，跨租户与不存在同形返回 404，
 * 并且归属校验失败时不能继续写库。这里用 mock 的 JdbcTemplate 模拟「查不到」，
 * 验证判定与「失败不落写」两点；纯 SQL 过滤（含软删）由 e2e 覆盖。</p>
 */
class UdfTenantIsolationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private UdfService udf;
    private UdfFolderService folders;

    @BeforeEach
    void setUp() {
        TenantScope.set("tenant_b");
        udf = new UdfService(jdbc, mock(DorisAdminService.class),
                mock(ProjectStorageService.class), "build/udf-test",
                "http://backend.test", "python3");
        folders = new UdfFolderService(jdbc, udf);
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    @Test
    void ownedResourceCarriesTenantConditionAndReturns404() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class, () -> udf.ownedResource(42L));
        assertEquals(404, e.getStatus(), e.getMessage());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        String text = sql.getValue();
        assertTrue(text.contains("r.id = ?"), text);
        assertTrue(text.contains("p.tenant_id = ?"), text);
        assertTrue(text.contains("jsonb_exists(r.metadata, 'deletedAt')"),
                "软删行必须与不存在同形（404）: " + text);
        assertEquals(42L, args.getValue()[0]);
        assertEquals("tenant_b", args.getValue()[1]);
    }

    @Test
    void ownedFolderOutsideTenantIs404() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class, () -> folders.ownedFolder(9L));
        assertEquals(404, e.getStatus(), e.getMessage());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("p.tenant_id = ?"), sql.getValue());
    }

    @Test
    void activateResourceAcrossTenantIs404AndDoesNotWrite() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThrows(ApiException.class, () -> udf.activate(42L, "u"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void moveResourceAcrossTenantIs404AndDoesNotWrite() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThrows(ApiException.class, () -> folders.moveResource(42L, null, "u"));
        verify(jdbc, never()).update(contains("set folder_id = ?"), any(Object[].class));
    }

    @Test
    void renameAndDeleteFolderAcrossTenantAre404() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertEquals(404, assertThrows(ApiException.class,
                () -> folders.rename(9L, "新名字")).getStatus());
        assertEquals(404, assertThrows(ApiException.class,
                () -> folders.delete(9L, true)).getStatus());
    }

    @Test
    void createFolderUnderForeignParentIs404() {
        // 项目归属校验通过（count=1），但父文件夹查不到 → 404
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class,
                () -> folders.create(1L, 9L, "子目录", "u"));
        assertEquals(404, e.getStatus(), e.getMessage());
        // 父目录不存在时不能插入文件夹
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
