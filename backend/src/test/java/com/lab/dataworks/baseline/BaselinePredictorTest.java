package com.lab.dataworks.baseline;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaselinePredictorTest {

    private static BaselinePredictor.Node node(String code, double seconds) {
        return new BaselinePredictor.Node(code, seconds);
    }

    @Test
    void linearChainWaitsForUpstream() {
        List<BaselinePredictor.Node> nodes = List.of(node("a", 0), node("b", 20), node("c", 5));
        Map<String, List<String>> deps = Map.of("b", List.of("a"), "c", List.of("b"));
        assertEquals(25, BaselinePredictor.remainingSeconds(nodes, deps), 0.001);
    }

    @Test
    void criticalPathPicksLongestBranch() {
        // a 10s → b 20s → d 5s  /  a 10s → c 15s → d 5s
        List<BaselinePredictor.Node> nodes =
                List.of(node("a", 10), node("b", 20), node("c", 15), node("d", 5));
        Map<String, List<String>> deps = Map.of(
                "b", List.of("a"), "c", List.of("a"), "d", List.of("b", "c"));
        assertEquals(35, BaselinePredictor.remainingSeconds(nodes, deps), 0.001);
    }

    @Test
    void completedUpstreamDoesNotAddWait() {
        List<BaselinePredictor.Node> nodes = List.of(node("a", 0), node("b", 7));
        Map<String, List<String>> deps = Map.of("b", List.of("a"));
        assertEquals(7, BaselinePredictor.remainingSeconds(nodes, deps), 0.001);
    }

    @Test
    void cycleIsGuardedInsteadOfStackOverflow() {
        List<BaselinePredictor.Node> nodes = List.of(node("a", 10), node("b", 20));
        Map<String, List<String>> deps = Map.of("a", List.of("b"), "b", List.of("a"));
        double value = BaselinePredictor.remainingSeconds(nodes, deps);
        // 成环时回到入口的边按 0 处理，但不会无限递归；a 仍会计入自己的 10 秒
        assertEquals(30, value, 0.001);
    }

    @Test
    void emptyGraphIsZero() {
        assertEquals(0, BaselinePredictor.remainingSeconds(List.of(), Map.of()), 0.001);
    }

    @Test
    void lateMinutesRoundsToNearestMinute() {
        Instant deadline = Instant.parse("2026-10-26T00:00:00Z");
        assertEquals(3, BaselinePredictor.lateMinutes(deadline.plusSeconds(170), deadline));
        assertEquals(-2, BaselinePredictor.lateMinutes(deadline.minusSeconds(100), deadline));
        assertNull(BaselinePredictor.lateMinutes(null, deadline));
    }
}
