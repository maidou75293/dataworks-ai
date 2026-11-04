package com.lab.dataworks.prefect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量闸门渲染：CUSTOM_SQL 必须带只读守卫，UNIQUE 口径与 Java 侧一致。
 *
 * <p>生成的 Python 会真的编译一遍（有 python 时），并直接调用 _guard_readonly
 * 验证危险 SQL 被拒——这是「守卫要验过它能红」的一部分。</p>
 */
class PrefectFlowRendererQualityGateTest {

    private static final String MODEL = """
            {"tasks":[{"code":"qg","name":"质量闸门","type":"QUALITY_GATE","ruleCode":"r1"}],
             "edges":[]}
            """;

    private String render() {
        return new PrefectFlowRenderer(new ObjectMapper()).render(
                1L, "demo", "wf_demo", "演示工作流", "DEV", MODEL, "", 1, "demo_wf").python();
    }

    @Test
    void generatedFlowContainsReadonlyGuardAndAlignedUniqueRule() {
        String python = render();
        assertTrue(python.contains("def _guard_readonly(sql)"), "缺少只读守卫");
        assertTrue(python.contains("_guard_readonly(sql.replace(\"${biz_date}\", biz_date))"),
                "CUSTOM_SQL 必须经过守卫");
        assertTrue(python.contains("count({column_name}) - count(distinct {column_name})"),
                "UNIQUE 必须用 count(col) 排除 NULL");
        assertTrue(python.contains("RANGE rule needs min or max"), "RANGE 缺省边界要拒绝");
    }

    /** TC7-G1：调度闸门（内嵌 Python）必须和 Java 侧同步支持四类新规则，否则页面能跑、定时就炸。 */
    @Test
    void generatedFlowHandlesNewQualityRuleTypes() {
        String python = render();
        assertTrue(python.contains("rule_type == \"VOLATILITY\""), "Flow 缺 VOLATILITY 分支");
        assertTrue(python.contains("rule_type == \"REGEX\""), "Flow 缺 REGEX 分支");
        assertTrue(python.contains("rule_type == \"ENUM\""), "Flow 缺 ENUM 分支");
        assertTrue(python.contains("rule_type == \"DISCRETE\""), "Flow 缺 DISCRETE 分支");
        // 行为口径的关键字：基线按 row_count= 约定解析、缺基线不判定、两种正则方言都在
        assertTrue(python.contains("row_count=([0-9]+)"), "VOLATILITY 基线必须解析 row_count 约定");
        assertTrue(python.contains("基线缺失"), "缺基线要在明细里说明");
        assertTrue(python.contains("!~"), "REGEX 缺 PG 方言");
        assertTrue(python.contains("regexp"), "REGEX 缺 Doris/MySQL 方言");
        assertTrue(python.contains("is not null"), "REGEX/ENUM 必须排除 NULL（口径同 UNIQUE）");
        assertTrue(python.contains("count(distinct"), "DISCRETE 必须数 distinct");
    }

    /**
     * TC7-G2：定时调度由 Prefect 直接建 run，不经过后端触发口，
     * 所以生成的 Flow 必须在**提交任何任务之前**做一次上游强规则检查。
     */
    @Test
    void generatedFlowChecksUpstreamQualityBeforeSubmittingTasks() {
        String python = render();
        assertTrue(python.contains("def _check_quality_blocks(biz_date)"), "缺少跨工作流阻断检查函数");
        assertTrue(python.contains("/api/internal/quality/blockers"), "阻断检查必须走后端内部接口");
        assertTrue(python.contains("上游强规则未通过，下游任务不调度"), "阻断要给出明确原因");
        // 只断言「函数存在」会被 def 行满足：必须断言**调用点**（有缩进的第二次出现）
        // 且调用点在第一个 .submit( 之前——红检实测过这个区别。
        int first = python.indexOf("_check_quality_blocks(biz_date)");
        int last = python.lastIndexOf("_check_quality_blocks(biz_date)");
        int submit = python.indexOf(".submit(biz_date");
        assertTrue(first >= 0, "缺少跨工作流阻断检查");
        // 只有 def 行时 first == last；必须有第二个出现（调用点）
        assertTrue(last > first, "Flow 没有调用阻断检查（只定义了函数）");
        assertTrue(submit > 0 && last < submit, "阻断检查必须在提交任务之前，否则下游已经跑了");
    }

    @Test
    void generatedFlowCompilesAndGuardRejectsWrites(@TempDir Path dir) throws Exception {
        String pythonExe = findPython();
        Assumptions.assumeTrue(pythonExe != null, "本机没有 python，跳过编译校验");

        Path flow = dir.resolve("flow.py");
        Files.writeString(flow, render(), StandardCharsets.UTF_8);
        Path driver = dir.resolve("driver.py");
        Files.writeString(driver, DRIVER, StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(pythonExe, "-I", driver.toString(), flow.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "python 校验超时");
        assertEquals(0, process.exitValue(), output);
    }

    private static String findPython() {
        for (String candidate : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 换下一个
            }
        }
        return null;
    }

    private static final String DRIVER = """
            import sys, types
            prefect = types.ModuleType("prefect")
            prefect.flow = lambda *a, **k: (lambda f: f)
            prefect.task = lambda *a, **k: (lambda f: f)
            sys.modules["prefect"] = prefect
            logging = types.ModuleType("prefect.logging")
            logging.get_run_logger = lambda: (lambda *a, **k: None)
            sys.modules["prefect.logging"] = logging

            ns = {}
            src = open(sys.argv[1], encoding="utf-8").read()
            exec(compile(src, sys.argv[1], "exec"), ns)
            guard = ns["_guard_readonly"]
            guard("select 1")
            guard("select 'a;b' from t")
            guard("select 1 -- ;\\n")
            bad = ["delete from t", "select 1; drop table t",
                   "select 1 -- x\\n; drop table t", "/*! select 1 */",
                   "select * from t for update"]
            for sql in bad:
                try:
                    guard(sql)
                except Exception:
                    continue
                print("GUARD DID NOT REJECT:", sql)
                sys.exit(1)
            print("OK")
            """;
}
