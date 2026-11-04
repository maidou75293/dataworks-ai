package com.lab.dataworks.flywheel;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-4 控制器静态校验器回归（不起 Spring，口径同 FW-1 requireReadOnly）：
 * badcase 信号回写的三个入参解析——判定权在 FW-3 采集面，这里只保证「进来的形状合法」。
 */
class FlywheelOrchestratorControllerTest {

    @Test
    void feedbackOnlyUpOrDown() {
        assertEquals("UP", FlywheelOrchestratorController.normalizeFeedback("up"));
        assertEquals("DOWN", FlywheelOrchestratorController.normalizeFeedback(" Down "));
        assertNull(FlywheelOrchestratorController.normalizeFeedback(null));
        assertNull(FlywheelOrchestratorController.normalizeFeedback(""));
        assertThrows(ApiException.class,
                () -> FlywheelOrchestratorController.normalizeFeedback("meh"));
    }

    @Test
    void confidenceZeroToOne() {
        assertEquals(new BigDecimal("0.3"), FlywheelOrchestratorController.parseConfidence("0.3"));
        assertEquals(new BigDecimal("1"), FlywheelOrchestratorController.parseConfidence(" 1 "));
        assertNull(FlywheelOrchestratorController.parseConfidence(""));
        ApiException e = assertThrows(ApiException.class,
                () -> FlywheelOrchestratorController.parseConfidence("1.5"));
        assertTrue(e.getMessage().contains("0–1"));
        assertThrows(ApiException.class, () -> FlywheelOrchestratorController.parseConfidence("高"));
    }

    @Test
    void resultRowsDefaultsToHasAnswerNotNoAnswer() {
        // 0 行 = NO_ANSWER 坏样本信号：缺省必须按「有答案」记，不默认造 badcase
        assertEquals(1, FlywheelOrchestratorController.parseResultRows(null));
        assertEquals(1, FlywheelOrchestratorController.parseResultRows(""));
        assertEquals(0, FlywheelOrchestratorController.parseResultRows(0));
        assertEquals(42, FlywheelOrchestratorController.parseResultRows("42"));
        assertThrows(ApiException.class, () -> FlywheelOrchestratorController.parseResultRows("-2"));
        assertThrows(ApiException.class, () -> FlywheelOrchestratorController.parseResultRows("abc"));
    }

    /**
     * BUG-FW6-2 防回归断言：runView 键集合必须覆盖 api/flywheel.ts FlywheelRun 全部 camelCase 字段。
     * FW6-1/FW6-2 两次同类漏项都是「单测全内存假件、不经 HTTP 视图」的盲区——字段清单直接读 TS 源，
     * 不在测试里硬编码（硬编码清单会跟着腐烂）。TS 侧加字段而 runView 忘补，本用例即红。
     */
    @Test
    void runViewCoversEveryFlywheelRunTsField() throws IOException {
        FlywheelRunDao.Run run = new FlywheelRunDao.Run(4L, "t1", 1L, 2L, "WAIT_GATE", "DEPLOY", "EVAL",
                "{}", "9", "v3", "job_1", "ev_1", "model_1", "small_model", "dep_4",
                Instant.ofEpochMilli(1761627000000L), 7L, "task_7", 8L, "boom", "alice",
                Instant.ofEpochMilli(1761620000000L));
        Map<String, Object> view = FlywheelOrchestratorController.runView(run);

        List<String> fields = flywheelRunTsFields();
        for (String f : fields) {
            assertTrue(view.containsKey(f), "runView 缺 TS 声明字段：" + f);
        }
        assertEquals(1761627000000L, view.get("deployTsMs"));
        assertEquals(1761620000000L, view.get("createdMs"));
    }

    /** 解析 flywheel.ts 的 export type FlywheelRun 字段名；解析异常必须显式炸，不许静默空集。 */
    private static List<String> flywheelRunTsFields() throws IOException {
        Path ts = null;
        for (String cand : new String[]{"../web/src/api/flywheel.ts", "web/src/api/flywheel.ts"}) {
            Path p = Path.of(cand);
            if (Files.exists(p)) {
                ts = p;
                break;
            }
        }
        if (ts == null) {
            throw new IllegalStateException("找不到 web/src/api/flywheel.ts（cwd="
                    + Path.of("").toAbsolutePath() + "）——契约断言失去依据，禁止静默跳过");
        }
        String src = Files.readString(ts);
        int begin = src.indexOf("export type FlywheelRun = {");
        if (begin < 0) {
            throw new IllegalStateException("flywheel.ts 里 FlywheelRun 类型结构变了，同步改本用例");
        }
        int end = src.indexOf("\n}", begin);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("^\\s+([A-Za-z][A-Za-z0-9]*)\\??:", Pattern.MULTILINE)
                .matcher(src.substring(begin, end));
        while (m.find()) {
            out.add(m.group(1));
        }
        assertTrue(out.size() >= 15, "FlywheelRun 字段解析异常（仅 " + out.size() + " 个）");
        return out;
    }
}
