package com.lab.dataworks.tablemanage;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D29 回归：{@code GET /api/tables/ddl-jobs?status=<非法值>} 必须 400，不再静默 200 空数组。
 *
 * <p>缺陷现场：同一类接口里 {@code /api/changes?status=NOPE} 报 400「status 只能是 …」、
 * {@code /api/ops/instances?state=NOPE} 报 400「不支持的状态: nope」，只有 DDL 工单列表
 * 把拼错的状态当成「按这个状态过滤」，返回空集，用户以为「没有工单」。</p>
 *
 * <p>合法值与 V88 的 {@code table_ddl_job_status_check} 逐字对齐，用例直接从迁移文件里
 * 解析这份清单比对：以后 DB 约束加了新状态而过滤清单没跟上，这条用例就会红。</p>
 */
class TableDdlJobStatusFilterTest {

    @Test
    void unknownStatusIsRejectedWithTheLegalList() {
        ApiException e = assertThrows(ApiException.class,
                () -> TableDdlService.normalizeJobStatusFilter("NOPE"));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().startsWith("status 只能是"),
                "文案要与 /api/changes 同口径，实际：" + e.getMessage());
        // 合法值全部回显，用户不用去翻代码
        for (String legal : TableDdlService.JOB_STATUSES) {
            assertTrue(e.getMessage().contains(legal), "应回显合法值 " + legal);
        }
        assertTrue(e.getMessage().contains("NOPE"), "应回显收到的值: " + e.getMessage());
    }

    @Test
    void legalStatusesAreAcceptedAndNormalized() {
        for (String legal : TableDdlService.JOB_STATUSES) {
            assertEquals(legal, TableDdlService.normalizeJobStatusFilter(legal), legal);
            // 缺陷复现里出现过 status=finished：小写是合法值，只是大小写不同，不能被 400 打死
            assertEquals(legal,
                    TableDdlService.normalizeJobStatusFilter(legal.toLowerCase(Locale.ROOT)),
                    legal + " 的小写");
            assertEquals(legal, TableDdlService.normalizeJobStatusFilter("  " + legal + " "),
                    legal + " 前后空格要 trim");
        }
    }

    @Test
    void emptyStatusMeansNoFilter() {
        // 未传 / 空串 / 纯空格 = 不过滤，不能变成 400（否则列表页默认查询就坏了）
        assertTrue(TableDdlService.normalizeJobStatusFilter(null) == null);
        assertTrue(TableDdlService.normalizeJobStatusFilter("") == null);
        assertTrue(TableDdlService.normalizeJobStatusFilter("   ") == null);
    }

    @Test
    void legalListMatchesDatabaseConstraint() throws Exception {
        Set<String> fromMigration = statusesInV88Constraint();
        assertEquals(fromMigration, TableDdlService.JOB_STATUSES,
                "过滤清单与 V88 的 status CHECK 约束漂移了");
    }

    /** 解析 V88 迁移里的 table_ddl_job_status_check 取值清单。 */
    private static Set<String> statusesInV88Constraint() throws Exception {
        String sql;
        try (InputStream in = TableDdlJobStatusFilterTest.class.getResourceAsStream(
                "/db/migration/V88__table_ddl_job.sql")) {
            assertTrue(in != null, "迁移文件不在 classpath：/db/migration/V88__table_ddl_job.sql");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("table_ddl_job_status_check\\s+check\\s*\\((.*?)\\)\\s*,",
                Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "没解析到 table_ddl_job_status_check，V88 文案变了？");
        Set<String> out = new LinkedHashSet<>();
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(m.group(1));
        while (quoted.find()) {
            out.add(quoted.group(1));
        }
        assertTrue(out.size() >= 5, "状态清单解析异常: " + out);
        return out;
    }
}
