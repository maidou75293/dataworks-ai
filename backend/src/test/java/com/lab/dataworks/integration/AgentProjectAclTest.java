package com.lab.dataworks.integration;

import com.lab.dataworks.auth.InternalTokenService;
import com.lab.dataworks.auth.RbacService;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-28：助手接口的项目参数必须按租户校验；跨租户/不存在统一 404。
 */
class AgentProjectAclTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentAccess access = new AgentAccess(jdbc, mock(RbacService.class),
            mock(AgentSettings.class), mock(InternalTokenService.class));

    @Test
    void projectInCallerTenantPasses() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        AgentAccess.Caller caller =
                new AgentAccess.Caller("tenant-a", "alice", AgentSettings.SYSTEM, true);
        assertDoesNotThrow(() -> access.requireProject(caller, 7L));
    }

    @Test
    void crossTenantProjectIs404() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        AgentAccess.Caller caller =
                new AgentAccess.Caller("tenant-a", "alice", AgentSettings.SYSTEM, true);
        ApiException e = assertThrows(ApiException.class,
                () -> access.requireProject(caller, 7L));
        assertEquals(404, e.getStatus(), e.getMessage());
        // 查询必须带调用者租户，不能只按 id 判断项目存在
        verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq(7L), eq("tenant-a"));
    }

    @Test
    void missingProjectIs404ForBrowserSession() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
        ApiException e = assertThrows(ApiException.class,
                () -> access.requireProject("tenant-b", 9L));
        assertEquals(404, e.getStatus(), e.getMessage());
    }
}
