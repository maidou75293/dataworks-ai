package com.lab.dataworks.flywheel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-3③载荷单测；口径=BUG-CHAIN-1 修正后的 TSV：每行 {@code 问题<TAB>}、
 * 答案留空=待标注（mos 的 messages/q-a 分支要求双非空，只带 user 轮的 jsonl 必 400——
 * 现网探针 docs/evidence/chain1-probe-empty-a.txt）。行形净化与空问题拒发是回归锚。
 */
class RelabelJsonlTest {

    @Test
    void eachLineIsQuestionWithEmptyAnswerPerTsvContract() {
        String tsv = RelabelJsonl.poolTsv(List.of("昨日销量是多少", "库存周转天数"));
        String[] lines = tsv.split("\n");
        assertEquals(2, lines.length, "数据集=池内容：一行一条");
        assertEquals("昨日销量是多少\t", lines[0], "行形=问题<TAB>，答案空=待标注");
        assertEquals("库存周转天数\t", lines[1]);
        for (String line : lines) {
            assertTrue(line.endsWith("\t"), "尾随 Tab 是空答案的行形标记，不许被吃掉");
        }
    }

    @Test
    void tabAndNewlineInQuestionCannotBreakLineShape() {
        // 问题文本里的制表/换行是行形分隔符——净化成空格，行数必须保持 1:1
        String tsv = RelabelJsonl.poolTsv(List.of("第一\t问题\n第二行", "  空行环绕  "));
        String[] lines = tsv.split("\n");
        assertEquals(2, lines.length, "一条问题=一行，行内不得再含换行");
        assertEquals("第一 问题 第二行\t", lines[0]);
        assertEquals("空行环绕\t", lines[1], "首尾空白去掉（mos 入库前也 strip，先净一致）");
    }

    @Test
    void blankQuestionFailsLoudlyNotSilentlyDropped() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RelabelJsonl.poolTsv(List.of("正常", "   ")));
        assertTrue(e.getMessage().contains("空"), "空问题必须点名报错——静默发过去只会在对端被丢行");
        // List.of 自身禁 null 元素，构造含 null 的列表要用 Arrays.asList
        assertThrows(IllegalArgumentException.class,
                () -> RelabelJsonl.poolTsv(java.util.Arrays.asList((String) null)));
    }

    @Test
    void emptyBatchProducesEmptyPayload() {
        assertEquals("", RelabelJsonl.poolTsv(List.of()));
    }

    @Test
    void descriptionCarriesLedgerKeyCountAndTargetModel() {
        String desc = RelabelJsonl.taskDescription("badcase:t1:2", 50, "qwen-test");
        assertTrue(desc.contains("badcase:t1:2"), "台账键必须进描述，FW-1 幂等台账才对得上链");
        assertTrue(desc.contains("50"));
        assertTrue(desc.contains("qwen-test"));
        assertFalse(RelabelJsonl.taskDescription("badcase:t1:1", 3, "  ").contains("null"));
        assertTrue(RelabelJsonl.taskDescription("badcase:t1:1", 3, "").contains("未配置"),
                "目标模型未配置要显式写「未配置」，不许留空假装正常");
    }
}
