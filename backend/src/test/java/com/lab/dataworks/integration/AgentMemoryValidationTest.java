package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手记忆写入的输入校验：D15（非法 expiresAt 先校验再动数据 + 事务）与
 * D22（非法 scope 不再静默降级成 user）。
 *
 * <p>D15 的原状：{@code upsert} 先把同 key 的旧记忆置 {@code superseded}，再 insert 新行；
 * insert 的 {@code ?::timestamptz} 遇到 {@code 2026-02-30} 抛异常，而第一步已经提交
 * （方法没有事务），于是一次拼错日期就静默丢一条已保存的记忆，接口还回 409
 * 「违反唯一性或外键约束」把排查带偏。</p>
 */
class AgentMemoryValidationTest {

    private JdbcTemplate jdbc;
    private AgentMemoryController controller;
    private HttpServletRequest http;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // D34：控制器多了 SessionService（撤回记忆要留审计）；本用例只测写入校验，给个 mock 即可
        controller = new AgentMemoryController(jdbc,
                mock(com.lab.dataworks.auth.SessionService.class));
        http = mock(HttpServletRequest.class);
        when(http.getAttribute("username")).thenReturn("bug_b_user");
        // tenantOf(user) 走这条查询
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
                any(Object[].class))).thenReturn(List.of("default"));
    }

    // ------------------------------------------------------------------ D15

    @Test
    void expiryAcceptsIsoDateAndDateTime() {
        assertEquals("2026-11-01", AgentMemoryController.checkExpiry("2026-11-01"));
        assertEquals("2026-11-01T10:00:00", AgentMemoryController.checkExpiry("2026-11-01T10:00:00"));
        assertEquals("2026-11-01 10:00:00", AgentMemoryController.checkExpiry("2026-11-01 10:00:00"));
        assertEquals("2026-11-01T10:00:00+08:00",
                AgentMemoryController.checkExpiry("2026-11-01T10:00:00+08:00"));
        assertEquals("2026-11-01T02:00:00Z", AgentMemoryController.checkExpiry("2026-11-01T02:00:00Z"));
        // 空值＝不过期
        assertNull(AgentMemoryController.checkExpiry(null));
        assertNull(AgentMemoryController.checkExpiry("  "));
    }

    @Test
    void expiryRejectsImpossibleAndMalformedDates() {
        for (String value : new String[]{"2026-02-30", "2026-13-01", "not-a-date",
                "2026/11/01", "1730000000000", "2026-11-01T99:00:00"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> AgentMemoryController.checkExpiry(value),
                    "非法 expiresAt 必须 400: " + value);
            assertEquals(400, e.getStatus());
            assertTrue(e.getMessage().contains("expiresAt"), e.getMessage());
        }
    }

    @Test
    void invalidExpiryDoesNotTouchData() {
        // 关键回归：校验必须在「归档旧记忆」之前发生，一条 update 都不能发出去
        AgentMemoryController.MemoryRequest req = new AgentMemoryController.MemoryRequest(
                "user", null, "bug_b_mem", "replace-bbb", "2026-02-30",
                null, null, null, null);

        ApiException e = assertThrows(ApiException.class, () -> controller.remember(req, http));
        assertEquals(400, e.getStatus(), "非法日期是 400，不是 409 数据冲突");
        assertTrue(e.getMessage().contains("expiresAt"), e.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void rememberRunsInOneTransaction() throws Exception {
        // 归档 + 插入 + 审计必须同生共死：少了事务，第二步失败就会留下「旧的没了、新的没进」
        assertNotNull(AgentMemoryController.class
                        .getMethod("remember", AgentMemoryController.MemoryRequest.class,
                                HttpServletRequest.class)
                        .getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "remember 必须带 @Transactional，否则非法输入会静默丢一条记忆");
    }

    // ------------------------------------------------------------------ D22

    @Test
    void scopeAcceptsKnownValues() {
        assertEquals("user", AgentMemoryController.scopeOf("user"));
        assertEquals("user", AgentMemoryController.scopeOf(null));
        assertEquals("user", AgentMemoryController.scopeOf("  "));
        assertEquals("project", AgentMemoryController.scopeOf("project"));
        assertEquals("project", AgentMemoryController.scopeOf(" PROJECT "));
        assertEquals("platform", AgentMemoryController.scopeOf("platform"));
        // system 是旧写法，仍然等价于 platform
        assertEquals("platform", AgentMemoryController.scopeOf("system"));
    }

    @Test
    void scopeRejectsUnknownValueInsteadOfSilentUser() {
        for (String value : new String[]{"galaxy", "global", "tenant", "org", "user2"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> AgentMemoryController.scopeOf(value),
                    "非法 scope 必须 400，不能静默按 user 存: " + value);
            assertEquals(400, e.getStatus());
            assertTrue(e.getMessage().contains(value), e.getMessage());
            assertTrue(e.getMessage().contains("user") && e.getMessage().contains("project")
                    && e.getMessage().contains("platform"), "提示要给出可选值: " + e.getMessage());
        }
    }
}
