package com.lab.dataworks.flywheel;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-3 采集+成池+去重单测（卡面验收①②：灌 3 条 mock 低质事件→池内 3 行；
 * 同 query 重放不进池）。沿用 access 包 FakeJdbc 套路：内存表模拟唯一索引语义。
 */
class BadcaseCollectorTest {

    /** 固定参数版 settings——同时充当游标存储。 */
    private static final class FixedSettings extends FlywheelSettings {
        double threshold = 0.6;
        long cursor = 0L;

        FixedSettings() {
            super(new JdbcTemplate());
        }

        @Override
        public double confidenceThreshold() {
            return threshold;
        }

        @Override
        public long cursorId() {
            return cursor;
        }

        @Override
        public void setCursorId(long cursor) {
            this.cursor = cursor;
        }
    }

    /** 假 JDBC：usage_query_event 按卡面三条规则过滤，badcase_pool 带两层唯一约束。 */
    private static final class FakeJdbc extends JdbcTemplate {
        final List<Map<String, Object>> events = new ArrayList<>();
        /** 池中已有的 (tenant,hash)——模拟 uq_badcase_pool_open_query（NEW 窗口）。 */
        final Set<String> openQueries = new HashSet<>();
        /** 已采集事件 id——模拟 uq_badcase_pool_event。 */
        final Set<Long> usedEvents = new HashSet<>();
        final List<Object[]> insertedArgs = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (!sql.contains("platform.usage_query_event")) {
                return List.of();
            }
            long cursor = ((Number) args[0]).longValue();
            double threshold = ((Number) args[1]).doubleValue();
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Map<String, Object> e : events) {
                long id = ((Number) e.get("id")).longValue();
                long rows = ((Number) e.getOrDefault("result_rows", 0L)).longValue();
                String fb = (String) e.get("feedback");
                Double conf = e.get("confidence") == null ? null : ((Number) e.get("confidence")).doubleValue();
                if (id > cursor && (rows == 0 || "down".equalsIgnoreCase(fb == null ? "" : fb.trim())
                        || (conf != null && conf < threshold))) {
                    hits.add(e);
                }
            }
            return hits;
        }

        @Override
        public int update(String sql, Object... args) {
            if (!sql.contains("into platform.badcase_pool")) {
                return 0;
            }
            String tenant = (String) args[0];
            long eventId = ((Number) args[2]).longValue();
            String hash = (String) args[4];
            if (usedEvents.contains(eventId) || openQueries.contains(tenant + "|" + hash)) {
                return 0; // on conflict do nothing
            }
            usedEvents.add(eventId);
            openQueries.add(tenant + "|" + hash);
            insertedArgs.add(args);
            return 1;
        }
    }

    private static Map<String, Object> event(long id, String tenant, long resultRows,
                                             String feedback, Double confidence, String question) {
        Map<String, Object> e = new HashMap<>();
        e.put("id", id);
        e.put("tenant_id", tenant);
        e.put("project_id", 7L);
        e.put("result_rows", resultRows);
        e.put("feedback", feedback);
        e.put("confidence", confidence);
        e.put("answer_model", "qwen-prod");
        e.put("question", question);
        return e;
    }

    @Test
    void threeMockLowQualityEventsProduceThreePoolRows() {
        FakeJdbc jdbc = new FakeJdbc();
        FixedSettings settings = new FixedSettings();
        jdbc.events.add(event(1, "t1", 0, null, null, "昨日销量是多少"));          // 无答案
        jdbc.events.add(event(2, "t1", 10, "DOWN", null, "库存查询怎么这么慢"));   // 点踩
        jdbc.events.add(event(3, "t1", 5, null, 0.42, "本月新增用户"));            // 低置信度
        jdbc.events.add(event(4, "t1", 3, "UP", 0.95, "正常问题不进池"));           // 高质，扫描即排除
        jdbc.events.add(event(5, "t1", 0, null, null, null));                      // 无答案但缺 question=不可成池

        BadcaseCollectorService.CollectStats stats =
                new BadcaseCollectorService(jdbc, settings).collectOnce();

        assertEquals(3, stats.inserted(), "3 条低质事件必须成 3 行池");
        assertEquals(1, stats.skippedNoQuestion(), "缺 question 的行要计数推进游标，不许静默吞");
        assertEquals(0, stats.deduped());
        assertEquals(5, stats.cursor(), "游标必须越过跳过的行（否则每圈重扫）");
        assertEquals(3, jdbc.openQueries.size());
        // 信号归类正确
        assertEquals("NO_ANSWER", jdbc.insertedArgs.get(0)[5]);
        assertEquals("DISLIKE", jdbc.insertedArgs.get(1)[5]);
        assertEquals("LOW_CONFIDENCE", jdbc.insertedArgs.get(2)[5]);
    }

    @Test
    void replayOfSameQueryIsNotPooledAgain() {
        FakeJdbc jdbc = new FakeJdbc();
        FixedSettings settings = new FixedSettings();
        jdbc.events.add(event(1, "t1", 0, null, null, "昨日销量是多少"));
        BadcaseCollectorService collector = new BadcaseCollectorService(jdbc, settings);
        collector.collectOnce();

        // 同 query 换了个事件再来（重放/重复上报）：归一化后哈希相同，撞 uq_badcase_pool_open_query → 不进池
        jdbc.events.add(event(2, "t1", 0, null, null, "  昨日销量是多少  "));
        BadcaseCollectorService.CollectStats second = collector.collectOnce();

        assertEquals(0, second.inserted(), "同 query 同轮重放必须被去重");
        assertEquals(1, second.deduped());
        assertEquals(1, jdbc.openQueries.size(), "池内仍只有 1 行");
    }

    @Test
    void secondRoundAfterPushAllowsSameQueryAgain() {
        // 池行封批推走后（openQueries 清空=NEW 窗口释放），下一圈同 query 允许重新进池
        FakeJdbc jdbc = new FakeJdbc();
        FixedSettings settings = new FixedSettings();
        jdbc.events.add(event(1, "t1", 0, null, null, "昨日销量是多少"));
        new BadcaseCollectorService(jdbc, settings).collectOnce();
        jdbc.openQueries.clear(); // 模拟批次 PUSHED 后部分唯一索引不再占位
        jdbc.events.add(event(2, "t1", 0, null, null, "昨日销量是多少"));
        BadcaseCollectorService.CollectStats next = new BadcaseCollectorService(jdbc, settings).collectOnce();
        assertEquals(1, next.inserted(), "下一圈新事件应能成池——版本推进由 FW-1 台账接住");
    }

    @Test
    void thresholdFromSettingsFlipsLowConfidence() {
        FakeJdbc jdbc = new FakeJdbc();
        FixedSettings settings = new FixedSettings();
        settings.threshold = 0.5; // sys_config 调阈值：0.55 不再算低质
        jdbc.events.add(event(1, "t1", 8, null, 0.55, "调阈值后这条不该进池"));
        BadcaseCollectorService.CollectStats stats =
                new BadcaseCollectorService(jdbc, settings).collectOnce();
        assertEquals(0, stats.inserted());
        assertEquals(0, stats.cursor(),
                "不满足谓词的事件由 SQL 粗过滤直接排除、游标不越过——阈值调回 0.6 时历史低置信事件还能被补采（默认不做死的另一半含义）");
        settings.threshold = 0.6;
        assertEquals(1, new BadcaseCollectorService(jdbc, settings).collectOnce().inserted(),
                "阈值调回即入池");
    }

    @Test
    void scanSqlGrammarSurvivesTextBlockSpaceStripping() {
        // BUG-VER-V555-1 回归：text block 吞行尾空格，v555 曾拼出 "limit1000" 现网每 tick 语法错。
        // FakeJdbc 不执行真实 SQL，mock 绿灯掩盖不了这条——limit 前后必须是真空格。
        String sql = BadcaseCollectorService.SCAN_SQL;
        assertTrue(sql.endsWith(" limit " + BadcaseCollectorService.SCAN_LIMIT),
                "SCAN_SQL 必须以 \" limit \" + 数字 结尾，实际结尾：" + sql.substring(Math.max(0, sql.length() - 30)));
        assertFalse(sql.contains("limit" + BadcaseCollectorService.SCAN_LIMIT), "limit 与数字之间不许无空格");
        assertTrue(sql.matches("(?s).*order by id\\s+limit\\s+\\d+"), "order by 与 limit 之间必须有空白分隔");
    }
}
