package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S13：审计与业务动作的顺序，以及截断后可定位。
 *
 * <p>三件事各自要有证据：</p>
 * <ol>
 *   <li><b>先记后做</b>：破坏性动作先落一条 {@code PENDING} 审计，执行完再回填终态。
 *       原状是「动作做完再补审计」，动作一抛异常就一条都不留；进程中途被杀同样无痕。</li>
 *   <li><b>失败记 FAILED</b>：动作抛异常（含 409 引用检查）时那条审计变成 {@code FAILED}
 *       并带上失败原因，异常原样抛出，不能被审计吞掉。</li>
 *   <li><b>截断留标记</b>：{@code target} 超长时旧实现直接 {@code substring}，
 *       截过的值和完整值长得一模一样，事后无从判断被截了多少。</li>
 * </ol>
 */
class SessionServiceAuditTest {

    private JdbcTemplate jdbc;

    /** PENDING 插入返回给定 id 的 mock（返回 null 时走「先记失败」的兜底分支）。 */
    private SessionService serviceReturning(Long id) {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(id);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        return new SessionService(jdbc, 12);
    }

    // -------------------------------------------------------- 先记后做

    @Test
    void auditIsWrittenBeforeTheActionAndSettledAfter() {
        SessionService sessions = serviceReturning(7L);
        List<String> trace = new ArrayList<>();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(inv -> {
            trace.add("pending");
            return 7L;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            trace.add(sql.startsWith("update platform.audit_log") ? "settle" : "other-update");
            return 1;
        });

        String out = sessions.auditAround("admin", "DATASOURCE_DELETE", "old_ds", "id 11", "10.0.0.1",
                () -> {
                    trace.add("work");
                    return "ok";
                });

        assertEquals("ok", out);
        // 顺序就是 S13 的验收点：先有审计，才动手；动完手才回填
        assertEquals(List.of("pending", "work", "settle"), trace);
    }

    @Test
    void pendingRowCarriesTenantAndIp() {
        SessionService sessions = serviceReturning(7L);
        sessions.auditAround("admin", "NODE_DRAIN", "kNode1", "dryRun=false", "10.0.0.9",
                () -> "done");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), args.capture());
        assertTrue(sql.getValue().contains("tenant_id"), "先记的审计必须带租户（S12 同口径）");
        assertTrue(sql.getValue().contains("returning id"), "要能拿到 id 才能回填终态");
        List<Object> bound = List.of(args.getValue());
        assertEquals("PENDING", bound.get(4), "动作执行前应是一条 PENDING");
        assertEquals("10.0.0.9", bound.get(5));
        // username, action, target, detail, result, ip, tenant_id
        assertEquals("NODE_DRAIN", bound.get(1));
        assertTrue(String.valueOf(bound.get(6)).length() > 0, "tenant_id 不能为 null：NULL = ? 永假");
    }

    // -------------------------------------------------------- 失败也记

    @Test
    void failureSettlesFailedAndRethrows() {
        SessionService sessions = serviceReturning(7L);
        ApiException thrown = assertThrows(ApiException.class, () -> sessions.auditAround(
                "admin", "DATASOURCE_DELETE", "old_ds", "id 11", null, () -> {
                    throw ApiException.conflict("还有 2 个看板图表使用该数据源");
                }));
        assertEquals(409, thrown.getStatus(), "业务异常必须原样抛出，不能被审计逻辑改掉");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update platform.audit_log"), args.capture());
        List<Object> bound = List.of(args.getValue());
        assertEquals("FAILED", bound.get(0));
        assertTrue(String.valueOf(bound.get(1)).contains("失败：还有 2 个看板图表使用该数据源"),
                "失败原因要进 detail，否则等于只知道「失败了」: " + bound.get(1));
    }

    @Test
    void successDetailKeepsOutcomeSuffix() {
        SessionService sessions = serviceReturning(7L);
        sessions.auditAround("admin", "NODE_DRAIN", "kNode1", "dryRun=false", null,
                () -> java.util.Map.of("pods", List.of("a", "b")),
                out -> "pods=" + out.get("pods"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update platform.audit_log"), args.capture());
        List<Object> bound = List.of(args.getValue());
        assertEquals("SUCCESS", bound.get(0));
        assertEquals("dryRun=false；pods=[a, b]", bound.get(1));
    }

    @Test
    void pendingWriteFailureDoesNotBreakBusinessButSettlesAsNewRow() {
        SessionService sessions = serviceReturning(null);   // 拿不到 id ＝ 先记没成功
        String out = sessions.auditAround("admin", "SERVICE_DELETE", "backend", "dw-dev/deployment",
                null, () -> "ok");

        assertEquals("ok", out);
        // 兜底：终态补一条独立记录，而不是悄悄丢掉整条审计
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("insert into platform.audit_log"), sql.getValue());
        // 兜底走完整 audit(...)：绑定参数里有 ip=null，只能用 Arrays.asList（List.of 拒绝 null）
        assertEquals("SUCCESS", java.util.Arrays.asList(args.getValue()).get(4));
    }

    // -------------------------------------------------------- 截断可定位

    @Test
    void truncatedTargetKeepsPrefixAndLengthMarker() {
        String longValue = "t".repeat(500);
        String cut = SessionService.cut(longValue, 256);

        assertEquals(256, cut.length(), "不能超过列宽");
        assertTrue(cut.startsWith("tttttt"), "前缀要留着，至少知道是哪张表/哪个对象");
        assertTrue(cut.contains("共500字符"), "必须能看出被截过、原来多长: " + cut);
    }

    @Test
    void shortAndNullValuesAreUntouched() {
        assertNull(SessionService.cut(null, 256));
        assertEquals("abc", SessionService.cut("abc", 256));
        assertEquals("x".repeat(256), SessionService.cut("x".repeat(256), 256));
        // 列宽小到一个标记都放不下（result / ip）：只能硬截，但绝不越界
        assertTrue(SessionService.cut("y".repeat(30), 8).length() <= 8);
    }

    @Test
    void auditUsesTheMarkedCutForTarget() {
        SessionService sessions = serviceReturning(7L);
        sessions.audit("admin", "TABLE_DROP", "d".repeat(300), null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        String target = String.valueOf(args.getValue()[2]);
        assertEquals(256, target.length());
        assertTrue(target.contains("共300字符"), target);
    }
}
