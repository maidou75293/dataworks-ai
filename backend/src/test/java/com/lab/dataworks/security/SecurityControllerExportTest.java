package com.lab.dataworks.security;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC7-G3 回归：审计日志导出端点的契约静态面（守卫=scripts/e2e/tc7-g3-audit-export-test.py，
 * 契约=docs/测试-TC7-G3.md 第四节；本单测钉住守卫跑不到/部署前可先验的部分）。
 *
 * <ol>
 *   <li>方法级 {@code @RequireAtom({"security:read","security:export"})}——
 *       缺方法级注解会落回类级只读判定，导出面形同无独立权限点（契约第 5 条）；</li>
 *   <li>{@code @GetMapping("/audit/export")} 路径钉死（契约第 1 条，换路径先与 t5 对齐）；</li>
 *   <li>未知/缺失 format → 400 且 message 可读（R07 面，不许 404/静默默认）；</li>
 *   <li>CSV/JSON 单元格的转义正确性（detail 里常有逗号/引号/换行，转义错=导出面坏）。</li>
 * </ol>
 */
class SecurityControllerExportTest {

    @Test
    void exportEndpointPinned() throws Exception {
        GetMapping mapping = exportMethod().getAnnotation(GetMapping.class);
        assertNotNull(mapping, "exportAudit 必须挂 @GetMapping");
        assertEquals("/audit/export", mapping.value()[0]);
    }

    @Test
    void exportRequiresExportAtomAligned() {
        RequireAtom atom = exportMethod().getAnnotation(RequireAtom.class);
        assertNotNull(atom, "exportAudit 必须有方法级 @RequireAtom，否则落回类级 security:read");
        List<String> required = List.of(atom.value());
        assertTrue(required.contains("security:export"), "导出必须单独要求 security:export: " + required);
        assertTrue(required.contains("security:read"), "仍要求在安全中心可见: " + required);
    }

    @Test
    void formatCsvJsonAcceptedCaseInsensitive() {
        assertEquals("csv", SecurityController.normalizeExportFormat("CSV"));
        assertEquals("json", SecurityController.normalizeExportFormat(" Json "));
    }

    @Test
    void formatMissingOrUnknownFails400Readable() {
        for (String bad : new String[]{null, "", "   ", "xlsx", "csv,json"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> SecurityController.normalizeExportFormat(bad), "应拒绝: " + bad);
            assertEquals(400, e.getStatus(), "未知 format 必须 400（不是 404/500）: " + bad);
            assertTrue(e.getMessage().contains("csv") && e.getMessage().contains("json"),
                    "message 须提示支持格式: " + e.getMessage());
        }
    }

    @Test
    void csvCellQuotedAndDoubled() {
        assertEquals("\"\"", SecurityController.csvCell(null));
        assertEquals("\"123\"", SecurityController.csvCell(123L));
        assertEquals("\"plain\"", SecurityController.csvCell("plain"));
        assertEquals("\"a,b\"", SecurityController.csvCell("a,b"));
        assertEquals("\"he said \"\"hi\"\"\"", SecurityController.csvCell("he said \"hi\""));
        assertEquals("\"line1\nline2\"", SecurityController.csvCell("line1\nline2"));
    }

    @Test
    void jsonValueTypesAndEscapes() {
        assertEquals("null", SecurityController.jsonValue(null));
        assertEquals("42", SecurityController.jsonValue(42L));
        assertEquals("true", SecurityController.jsonValue(true));
        assertEquals("\"a\\\"b\"", SecurityController.jsonValue("a\"b"));
        assertEquals("\"c\\\\d\"", SecurityController.jsonValue("c\\d"));
        assertEquals("\"x\\ny\"", SecurityController.jsonValue("x\ny"));
        assertEquals("\"\\u0001\"", SecurityController.jsonValue("\u0001"));
    }

    private static java.lang.reflect.Method exportMethod() {
        try {
            return SecurityController.class.getMethod("exportAudit",
                    String.class, String.class, String.class, String.class, Boolean.class, String.class,
                    HttpServletRequest.class, HttpServletResponse.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "SecurityController.exportAudit(String,String,String,String,Boolean,String,"
                            + "HttpServletRequest,HttpServletResponse) 签名变了（契约第 1 条要求筛选参数与列表同名同义）", e);
        }
    }
}