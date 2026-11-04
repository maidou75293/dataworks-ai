package com.lab.dataworks.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-TC6-14：权限批量审批（{@code POST /api/access/requests/bulk-approve|bulk-reject}）。
 *
 * <p>要钉住的口径：</p>
 * <ol>
 *   <li>预检在任何一单被处理之前整体 400：空 / 超 50 / id 非法（null、≤0）/ 重复 id；</li>
 *   <li>逐项复用单项 decide 闸门：不存在的 id 逐条回传 {id, ok:false, code:404,
 *       message:"申请不存在"}，其余项照常处理，不许静默；</li>
 *   <li>整批记一条汇总审计（ACCESS_REQUEST_BULK_APPROVE / _REJECT），
 *       全失败 result=FAILED，不产生「批量动作对不上账」；</li>
 *   <li>批量入口不新增任何绕闸逻辑：approve/reject 走同一个 bulkDecide，
 *       仅 status 不同。</li>
 * </ol>
 *
 * <p>全部 mock：不起 Spring、不连库（decisionRow 查不到行 = 天然的逐项失败样本）。</p>
 */
class AccessBulkDecisionTest {

    private JdbcTemplate jdbc;
    private SessionService sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        sessions = mock(SessionService.class);
        PermissionResolver permissions = mock(PermissionResolver.class);
        when(permissions.atoms(anyString(), any())).thenReturn(Set.of("access:approve"));
        AccessController controller = new AccessController(
                jdbc, sessions, permissions, mock(TransactionTemplate.class),
                mock(AccessExpiryJob.class), mock(AccessEscalationJob.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body(String idsJson, String comment) {
        return "{\"ids\":[" + idsJson + "]"
                + (comment == null ? "" : ",\"comment\":\"" + comment + "\"") + "}";
    }

    /** 生产里 AuthInterceptor 会写入 username 属性；standalone MockMvc 必须自己带上，
     * 否则 AccessController.user() 按「未登录」抛 401（这本身是正确行为）。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setAttribute("username", "approver");
            return request;
        });
    }

    private static Map<String, Object> json(MvcResult result) throws Exception {
        return new ObjectMapper().readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private static String message(MvcResult result) throws Exception {
        Object m = json(result).get("message");
        return m == null ? "" : String.valueOf(m);
    }

    /** 空 / 超 50 / id 非法 / 重复 id：整体 400，且一条都不进 decide（jdbc 零调用）。 */
    @Test
    void invalidIdListsFailWholeBatchBeforeProcessing() throws Exception {
        mvc.perform(post("/api/access/requests/bulk-approve")
                        .contentType(MediaType.APPLICATION_JSON).content(body("", null)))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("至少选择一条申请"), message(r)));

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append(900000 + i);
        }
        mvc.perform(post("/api/access/requests/bulk-approve")
                        .contentType(MediaType.APPLICATION_JSON).content(body(many.toString(), null)))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("最多 50"), message(r)));

        mvc.perform(post("/api/access/requests/bulk-approve")
                        .contentType(MediaType.APPLICATION_JSON).content(body("7,0", null)))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("id 不合法"), message(r)));

        mvc.perform(post("/api/access/requests/bulk-approve")
                        .contentType(MediaType.APPLICATION_JSON).content(body("7,null", null)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/access/requests/bulk-reject")
                        .contentType(MediaType.APPLICATION_JSON).content(body("7,7", null)))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("批量项重复"), message(r)));

        verify(jdbc, never()).queryForList(anyString(), (Object[]) any());
        verify(sessions, never()).audit(anyString(), eq("ACCESS_REQUEST_BULK_APPROVE"),
                anyString(), any(), anyString(), any());
    }

    /**
     * 逐项口径：不存在的申请 → 200，failed=N，每行 {id, ok:false, code:404, message 申请不存在}；
     * 汇总审计 result=FAILED。证明批量入口把单项闸门的返回原样搬进结果行，而不是吞掉。
     */
    @Test
    void missingIdsReportPerItemNotSilently() throws Exception {
        MvcResult result = mvc.perform(authed(post("/api/access/requests/bulk-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("400001,400002", "批量通过测试"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> out = json(result);
        assertEquals(2, out.get("total"));
        assertEquals(0, out.get("succeeded"));
        assertEquals(2, out.get("failed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals(400001, ((Number) results.get(0).get("id")).intValue());
        assertEquals(Boolean.FALSE, results.get(0).get("ok"));
        assertEquals(404, results.get(0).get("code"));
        assertTrue(String.valueOf(results.get(0).get("message")).contains("申请不存在"),
                String.valueOf(results.get(0)));
        verify(sessions).audit(anyString(), eq("ACCESS_REQUEST_BULK_APPROVE"),
                anyString(), any(), eq("FAILED"), any());
    }

    /** 批量驳回与批量通过共用同一预检/汇总路径（不新增第二套逻辑）。 */
    @Test
    void bulkRejectSharesPrecheckAndSummary() throws Exception {
        mvc.perform(post("/api/access/requests/bulk-reject")
                        .contentType(MediaType.APPLICATION_JSON).content(body("", null)))
                .andExpect(status().isBadRequest());

        MvcResult result = mvc.perform(authed(post("/api/access/requests/bulk-reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("400003", "资料不全"))))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(1, json(result).get("failed"));
        verify(sessions).audit(anyString(), eq("ACCESS_REQUEST_BULK_REJECT"),
                anyString(), any(), eq("FAILED"), any());
    }
}
