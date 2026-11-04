package com.lab.dataworks.tablemanage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.security.DataGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-TC6-14：表管理批量删表（{@code POST /api/tables/bulk-drop}）。
 *
 * <p>卡内硬要求「批量与单项必须同闸门、同文案、同状态机校验」，要钉住的口径：</p>
 * <ol>
 *   <li>预检（空清单 / 超 100 / 重复项 / 非法名 / 确认串不符）→ 400，
 *       且 <b>一张表都不许被 drop</b>（exec never called）；</li>
 *   <li>一次性总确认必须手打全部 {@code db.table} 逗号清单（顺序同请求体），
 *       与单项删除「输入表名确认」同源——只是确认动作合并成一次；</li>
 *   <li>预检通过后逐项执行：单项失败（Unknown table → 404 映射，同 D63 文案口径）
 *       不阻断其余项，逐条回传 {database, table, ok, code, message}，不许静默；</li>
 *   <li>审计逐项落账（TABLE_DROP SUCCESS/FAILED）+ 整批一条汇总（TABLE_DROP_BULK，
 *       全成 SUCCESS / 全败 FAILED / 有成败 PARTIAL）；有成功才失效元数据缓存。</li>
 * </ol>
 *
 * <p>全部 mock：不起 Spring、不连 Doris。</p>
 */
class TableBulkDropTest {

    private static final String DB = "audtze9k_db";

    private DorisAdminService doris;
    private SessionService sessions;
    private MetadataReader metadata;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        metadata = mock(MetadataReader.class);
        // 发布线合流（BUG-TC6-14 × BUG-TC6-5）：main 侧构造已加 TableDdlService（生产闸门
        // 走 ddl.gateEnv）。mock 默认 gateEnv 返回 null → dropOne 按 *_prd 命名兜底判 DEV，
        // 测试库 audtze9k_db 不受闸门影响，批量断言口径与 wm4 分支上一致。
        TableController controller = new TableController(
                mock(JdbcTemplate.class), doris, mock(DorisQueryService.class), sessions,
                mock(TenantDataScope.class), mock(WarehouseResolver.class), metadata,
                mock(DataGuardService.class), mock(TableDdlService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String items(String... tables) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tables.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"database\":\"").append(DB).append("\",\"table\":\"")
                    .append(tables[i]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String body(String confirm, String itemsJson) {
        return "{\"projectId\":1,\"items\":" + itemsJson + ",\"confirm\":\"" + confirm + "\"}";
    }

    private static String expectedConfirm(String... tables) {
        List<String> fulls = new ArrayList<>();
        for (String t : tables) {
            fulls.add(DB + "." + t);
        }
        return String.join(",", fulls);
    }

    private static Map<String, Object> json(MvcResult result) throws Exception {
        return new ObjectMapper().readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private static String message(MvcResult result) throws Exception {
        Object m = json(result).get("message");
        return m == null ? "" : String.valueOf(m);
    }

    /** 确认串不符 → 400，且一张都不许动（预检在所有删除之前）。 */
    @Test
    void wrongConfirmRejectsWholeBatchBeforeAnyDrop() throws Exception {
        MvcResult result = mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("wrong", items("a1", "a2"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertTrue(message(result).contains("批量删除确认"), message(result));
        verify(doris, never()).exec(any(), anyString());
        verify(metadata, never()).invalidateProject(any());
    }

    /** 重复项（含仅大小写不同）→ 400：防同一表连删两次产生一真一假两条审计。 */
    @Test
    void duplicateItemsRejectedCaseInsensitively() throws Exception {
        mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", items("dup", "DUP"))))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("批量项重复"), message(r)));
        verify(doris, never()).exec(any(), anyString());
    }

    /** 空清单 → 400；超过 100 张 → 400（分批操作，与前端上限一致）。 */
    @Test
    void emptyAndOversizedBatchRejected() throws Exception {
        mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("至少选择一张表"), message(r)));

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"database\":\"").append(DB).append("\",\"table\":\"t").append(i).append("\"}");
        }
        many.append(']');
        mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", many.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("最多 100"), message(r)));
        verify(doris, never()).exec(any(), anyString());
    }

    /** 非法表名混在批量里 → 整批 400（requireName 与单项同源），不部分执行。 */
    @Test
    void invalidNameFailsWholeBatch() throws Exception {
        mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", items("ok_name", "1bad-name"))))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertTrue(
                        message(r).contains("表名不合法"), message(r)));
        verify(doris, never()).exec(any(), anyString());
    }

    /** 全成功：逐项 drop + 逐项 SUCCESS 审计 + 一条汇总审计 + 缓存失效一次。 */
    @Test
    void happyPathDropsEachTableAndAudits() throws Exception {
        String confirm = expectedConfirm("a1", "a2");
        MvcResult result = mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(confirm, items("a1", "a2"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> out = json(result);
        assertEquals(2, out.get("total"));
        assertEquals(2, out.get("succeeded"));
        assertEquals(0, out.get("failed"));
        verify(doris).exec(any(), contains("drop table `" + DB + "`.`a1`"));
        verify(doris).exec(any(), contains("drop table `" + DB + "`.`a2`"));
        verify(sessions, times(2)).audit(any(), eq("TABLE_DROP"), any(), any(), eq("SUCCESS"), any());
        verify(sessions).audit(any(), eq("TABLE_DROP_BULK"), any(), any(), eq("SUCCESS"), any());
        verify(metadata).invalidateProject(any());
    }

    /**
     * 部分失败不静默：不存在的表按单项同口径映射 404「表不存在」，
     * 其余照常删除；汇总 PARTIAL；缓存仍要失效（有成功项）。
     */
    @Test
    void missingTableReportsPerItemWithoutBlockingOthers() throws Exception {
        doThrow(new ApiException(502, "Doris 执行失败: errCode = 2, detailMessage = Unknown table 'gone'"))
                .when(doris).exec(any(), contains("`gone`"));
        String confirm = expectedConfirm("ok1", "gone");
        MvcResult result = mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(confirm, items("ok1", "gone"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> out = json(result);
        assertEquals(1, out.get("succeeded"));
        assertEquals(1, out.get("failed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals(Boolean.TRUE, results.get(0).get("ok"));
        assertEquals(Boolean.FALSE, results.get(1).get("ok"));
        assertEquals(404, results.get(1).get("code"));
        assertTrue(String.valueOf(results.get(1).get("message")).contains("表不存在"),
                String.valueOf(results.get(1)));
        verify(sessions).audit(any(), eq("TABLE_DROP"), eq(DB + ".gone"), any(), eq("FAILED"), any());
        verify(sessions).audit(any(), eq("TABLE_DROP_BULK"), any(), any(), eq("PARTIAL"), any());
        verify(metadata).invalidateProject(any());
    }

    /** 确认串允许首尾空白（逐字符比较发生在 trim 后），内容不符仍 400。 */
    @Test
    void confirmAllowsSurroundingWhitespaceOnly() throws Exception {
        String confirm = "  " + expectedConfirm("w1") + "  ";
        mvc.perform(post("/api/tables/bulk-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(confirm, items("w1"))))
                .andExpect(status().isOk());
        verify(doris).exec(any(), contains("drop table `" + DB + "`.`w1`"));
    }
}
