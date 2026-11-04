package com.lab.dataworks.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D46：质量闸门「谁在用、会拦住谁」的口径。
 *
 * <p>锁住四件事：</p>
 * <ol>
 *   <li>下游任务是**传递闭包**：闸门 → 直接下游 → 再下游，闸门不过整条链都不该跑，
 *       只列直接下游会让用户以为后面的任务不受影响；</li>
 *   <li>非闸门任务、没填 ruleCode 的闸门任务都不该出现在结果里（草稿不该污染列表）；</li>
 *   <li>环和悬空依赖不能让接口挂死或抛错；</li>
 *   <li>模型坏掉时返回空，而不是把整个「规则」列表打不开。</li>
 * </ol>
 */
class QualityGateUsageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<QualityController.GateUsage> parse(String modelJson) {
        return QualityController.gateUsages(MAPPER, modelJson);
    }

    @Test
    void listsGateWithTransitiveDownstream() {
        List<QualityController.GateUsage> usage = parse("""
                {"tasks":[
                   {"code":"extract","type":"SQL"},
                   {"code":"gate","type":"QUALITY_GATE","ruleCode":"dwd_order_rowcnt"},
                   {"code":"agg","type":"SQL"},
                   {"code":"push","type":"SHELL"}],
                 "edges":[
                   {"from":"extract","to":"gate"},
                   {"from":"gate","to":"agg"},
                   {"from":"agg","to":"push"}]}
                """);

        assertEquals(1, usage.size());
        QualityController.GateUsage u = usage.get(0);
        assertEquals("dwd_order_rowcnt", u.ruleCode());
        assertEquals("gate", u.gateTaskCode());
        // agg 是直接下游，push 隔着 agg 也算被拦住；上游 extract 不算
        assertEquals(List.of("agg", "push"), u.blockedTasks());
    }

    /** 类型大小写不敏感（渲染器用 toUpperCase 判定），多条闸门各自带自己的下游。 */
    @Test
    void multipleGatesAreReportedSeparately() {
        List<QualityController.GateUsage> usage = parse("""
                {"tasks":[
                   {"code":"g1","type":"quality_gate","ruleCode":" rule_a "},
                   {"code":"g2","type":"QUALITY_GATE","ruleCode":"rule_b"},
                   {"code":"t1","type":"SQL"},
                   {"code":"t2","type":"SQL"}],
                 "edges":[
                   {"from":"g1","to":"t1"},{"from":"g2","to":"t2"}]}
                """);

        assertEquals(2, usage.size());
        assertEquals("rule_a", usage.get(0).ruleCode(), "ruleCode 两侧空格要清掉，否则匹配不上规则编码");
        assertEquals(List.of("t1"), usage.get(0).blockedTasks());
        assertEquals(List.of("t2"), usage.get(1).blockedTasks());
    }

    @Test
    void ignoresNonGateAndUnboundTasks() {
        List<QualityController.GateUsage> usage = parse("""
                {"tasks":[
                   {"code":"sql1","type":"SQL"},
                   {"code":"g_no_rule","type":"QUALITY_GATE","ruleCode":"  "},
                   {"code":"","type":"QUALITY_GATE","ruleCode":"rule_c"}],
                 "edges":[{"from":"g_no_rule","to":"sql1"}]}
                """);

        // 没绑定规则编码的闸门＝还没配好，列出来只会误导；没有任务编码的同样丢弃
        assertTrue(usage.isEmpty(), "实际: " + usage);
    }

    /** 闸门在最末端时没有下游：必须是空列表，不能把上游算成被拦。 */
    @Test
    void gateAtTheEndHasNoDownstream() {
        List<QualityController.GateUsage> usage = parse("""
                {"tasks":[{"code":"a","type":"SQL"},{"code":"g","type":"QUALITY_GATE","ruleCode":"r"}],
                 "edges":[{"from":"a","to":"g"}]}
                """);

        assertEquals(1, usage.size());
        assertEquals(List.of(), usage.get(0).blockedTasks());
    }

    @Test
    void cycleAndDanglingEdgesDoNotHangOrThrow() {
        List<QualityController.GateUsage> usage = parse("""
                {"tasks":[{"code":"g","type":"QUALITY_GATE","ruleCode":"r"},
                          {"code":"x","type":"SQL"},{"code":"y","type":"SQL"}],
                 "edges":[{"from":"g","to":"x"},{"from":"x","to":"y"},
                          {"from":"y","to":"x"},{"from":"g","to":"ghost"},{"from":"g","to":"g"}]}
                """);

        assertEquals(1, usage.size());
        // 环只算一次；指向不存在任务的边、自环都忽略
        assertEquals(List.of("x", "y"), usage.get(0).blockedTasks());
    }

    @Test
    void brokenOrEmptyModelYieldsNothingInsteadOfError() {
        assertEquals(List.of(), parse(null));
        assertEquals(List.of(), parse(""));
        assertEquals(List.of(), parse("null"));
        assertEquals(List.of(), parse("{不是合法 JSON"));
        assertEquals(List.of(), parse("{}"));
        assertEquals(List.of(), parse("{\"tasks\":{}}"));
    }
}
