package com.lab.dataworks.flywheel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-3 回环段单测（卡面验收③：批量到阈值→mos 建任务，用 mock 网关断言；
 * FW-1 的 MosRelabelGateway 缺席时批次保持 READY、不伪造成功）。
 */
class RelabelTriggerTest {

    private static final class FixedSettings extends FlywheelSettings {
        int batch = 2;
        String model = "qwen-prod";

        FixedSettings() {
            super(new JdbcTemplate());
        }

        @Override
        public int batchSize() {
            return batch;
        }

        @Override
        public String targetModel() {
            return model;
        }
    }

    private static final class GatewayStub implements ObjectProvider<MosRelabelGateway> {
        MosRelabelGateway gw;
        final List<String> ledgerKeys = new ArrayList<>();
        String lastPoolTsv;
        String lastDescription;
        RuntimeException fail;

        @Override
        public MosRelabelGateway getObject() {
            if (gw == null) {
                throw new IllegalStateException("no gateway");
            }
            return gw;
        }

        @Override
        public MosRelabelGateway getObject(Object... args) {
            return getObject();
        }

        @Override
        public MosRelabelGateway getIfAvailable() {
            return gw;
        }

        @Override
        public MosRelabelGateway getIfUnique() {
            return gw;
        }

        GatewayStub() {
            gw = (ledgerKey, description, poolTsv) -> {
                if (fail != null) {
                    throw fail;
                }
                ledgerKeys.add(ledgerKey);
                lastDescription = description;
                lastPoolTsv = poolTsv;
                return "ds_mock" + ledgerKeys.size() + "@v1";
            };
        }
    }

    /** 内存版 badcase_pool / flywheel_relabel_batch，状态迁移语义照 SQL 写。 */
    private static final class FakeJdbc extends JdbcTemplate {
        final List<Map<String, Object>> pool = new ArrayList<>();
        final List<Map<String, Object>> batches = new ArrayList<>();
        long batchSeq = 100;

        void poolNew(long id, String tenant, String question) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", id);
            r.put("tenant_id", tenant);
            r.put("question", question);
            r.put("status", "NEW");
            r.put("batch_id", null);
            pool.add(r);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            // PENDING_BATCHES_SQL 是无参调用——JdbcTemplate 会选中固定 arity 重载，一并转给 varargs 假实现
            return queryForList(sql, new Object[0]);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("group by tenant_id")) {
                int need = ((Number) args[0]).intValue();
                Map<String, Long> counts = new HashMap<>();
                for (Map<String, Object> r : pool) {
                    if ("NEW".equals(r.get("status"))) {
                        counts.merge((String) r.get("tenant_id"), 1L, Long::sum);
                    }
                }
                List<Map<String, Object>> out = new ArrayList<>();
                counts.forEach((tenant, c) -> {
                    if (c >= need) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("tenant_id", tenant);
                        m.put("new_count", c);
                        out.add(m);
                    }
                });
                return out;
            }
            if (sql.contains("next_round")) {
                long max = 0;
                for (Map<String, Object> b : batches) {
                    if (b.get("tenant_id").equals(args[0])) {
                        max = Math.max(max, ((Number) b.get("round_no")).longValue());
                    }
                }
                return List.of(Map.of("next_round", max + 1));
            }
            if (sql.contains("select id, question")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> r : pool) {
                    if ("NEW".equals(r.get("status")) && r.get("tenant_id").equals(args[0])) {
                        out.add(r);
                    }
                }
                return out;
            }
            if (sql.contains("from platform.flywheel_relabel_batch")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> b : batches) {
                    if ("READY".equals(b.get("status"))) {
                        out.add(b);
                    }
                }
                return out;
            }
            if (sql.contains("select question") && sql.contains("batch_id")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> r : pool) {
                    if (args[0].equals(r.get("batch_id"))) {
                        out.add(r);
                    }
                }
                return out;
            }
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            // INSERT_BATCH_SQL：args=(tenant, ledgerKey, round, itemCount)
            Map<String, Object> b = new HashMap<>();
            b.put("id", ++batchSeq);
            b.put("tenant_id", args[0]);
            b.put("ledger_key", args[1]);
            b.put("round_no", args[2]);
            b.put("item_count", args[3]);
            b.put("status", "READY");
            b.put("mos_task_ref", null);
            b.put("last_error", null);
            batches.add(b);
            return (T) b.get("id");
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("set status = 'BATCHED'")) {
                int n = 0;
                for (Map<String, Object> r : pool) {
                    if ("NEW".equals(r.get("status")) && r.get("tenant_id").equals(args[1])) {
                        r.put("status", "BATCHED");
                        r.put("batch_id", args[0]);
                        n++;
                    }
                }
                return n;
            }
            if (sql.contains("mos_task_ref")) {
                for (Map<String, Object> b : batches) {
                    if (b.get("id").equals(args[1])) {
                        b.put("status", "PUSHED");
                        b.put("mos_task_ref", args[0]);
                        b.put("last_error", null);
                    }
                }
                return 1;
            }
            if (sql.contains("set last_error")) {
                for (Map<String, Object> b : batches) {
                    if (b.get("id").equals(args[1])) {
                        b.put("last_error", args[0]);
                    }
                }
                return 1;
            }
            if (sql.contains("where batch_id = ? and status = 'BATCHED'")) {
                int n = 0;
                for (Map<String, Object> r : pool) {
                    if (args[0].equals(r.get("batch_id")) && "BATCHED".equals(r.get("status"))) {
                        r.put("status", "PUSHED");
                        n++;
                    }
                }
                return n;
            }
            return 0;
        }
    }

    @Test
    void batchThresholdReachedCreatesMosTaskViaGateway() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.poolNew(1, "t1", "昨日销量是多少");
        jdbc.poolNew(2, "t1", "库存周转天数");
        GatewayStub gw = new GatewayStub();
        RelabelTriggerService trigger =
                new RelabelTriggerService(jdbc, new FixedSettings(), gw);

        int created = trigger.triggerOnce();

        assertEquals(1, created);
        assertEquals(1, jdbc.batches.size());
        Map<String, Object> batch = jdbc.batches.get(0);
        assertEquals("badcase:t1:1", batch.get("ledger_key"), "台账键=badcase→relabel 链，FW-1 幂等接住下一圈");
        assertEquals("PUSHED", batch.get("status"));
        assertEquals("ds_mock1@v1", batch.get("mos_task_ref"));
        assertEquals(1, gw.ledgerKeys.size(), "一批一次调用，不重复推送");
        // BUG-CHAIN-1 载荷口径：每行 问题<TAB>（答案空=待标注）——旧「只带 user 轮 jsonl」
        // 会被 mos 400 拒（_qa_of_obj 要求 q/a 双非空），断言行形而不是 JSON 结构
        String[] payloadLines = gw.lastPoolTsv.split("\n");
        assertEquals(2, payloadLines.length, "数据集=池内容：一行一条");
        assertEquals("昨日销量是多少\t", payloadLines[0]);
        assertEquals("库存周转天数\t", payloadLines[1]);
        assertTrue(gw.lastDescription.contains("qwen-prod"), "目标模型名必须写进任务描述");
        assertTrue(gw.lastDescription.contains("badcase:t1:1"));
        assertTrue(jdbc.pool.stream().allMatch(r -> "PUSHED".equals(r.get("status"))),
                "推送后池行释放 NEW 占位，同 query 才能进下一圈");
    }

    @Test
    void belowThresholdDoesNothing() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.poolNew(1, "t1", "只有 1 条");
        GatewayStub gw = new GatewayStub();
        FixedSettings settings = new FixedSettings();
        settings.batch = 2;

        int created = new RelabelTriggerService(jdbc, settings, gw).triggerOnce();

        assertEquals(0, created);
        assertTrue(jdbc.batches.isEmpty());
        assertTrue(gw.ledgerKeys.isEmpty(), "未达阈值不许惊动 mos");
    }

    @Test
    void missingGatewayKeepsBatchReadyThenPushesWhenItAppears() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.poolNew(1, "t1", "a");
        jdbc.poolNew(2, "t1", "b");
        GatewayStub gw = new GatewayStub();
        MosRelabelGateway real = gw.gw;
        gw.gw = null; // FW-1 mosbridge 未合入的本构建
        RelabelTriggerService trigger = new RelabelTriggerService(jdbc, new FixedSettings(), gw);

        assertEquals(1, trigger.triggerOnce(), "照样封批落台账");
        assertEquals("READY", jdbc.batches.get(0).get("status"), "无桥接实现=保持 READY，不伪造成功");
        assertTrue(jdbc.pool.stream().allMatch(r -> "BATCHED".equals(r.get("status"))));

        gw.gw = real; // 桥接上线
        assertEquals(0, trigger.triggerOnce(), "已封批不再重复封");
        assertEquals("PUSHED", jdbc.batches.get(0).get("status"), "READY 批次在下一圈被推走");
    }

    @Test
    void gatewayFailureKeepsReadyAndRecordsErrorForNextRound() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.poolNew(1, "t1", "a");
        jdbc.poolNew(2, "t1", "b");
        GatewayStub gw = new GatewayStub();
        gw.fail = new RuntimeException("connection reset");

        new RelabelTriggerService(jdbc, new FixedSettings(), gw).triggerOnce();

        Map<String, Object> batch = jdbc.batches.get(0);
        assertEquals("READY", batch.get("status"), "推送失败不许置终态");
        assertTrue(String.valueOf(batch.get("last_error")).contains("connection reset"),
                "失败原因必须落台账可回查");
        assertNotPushed(jdbc);
    }

    @Test
    void nextRoundAfterPushUsesIncrementedLedgerKey() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.poolNew(1, "t1", "a");
        jdbc.poolNew(2, "t1", "b");
        GatewayStub gw = new GatewayStub();
        RelabelTriggerService trigger = new RelabelTriggerService(jdbc, new FixedSettings(), gw);
        trigger.triggerOnce();

        jdbc.poolNew(3, "t1", "c");
        jdbc.poolNew(4, "t1", "d");
        trigger.triggerOnce();

        assertEquals("badcase:t1:2", jdbc.batches.get(1).get("ledger_key"), "圈次只增不复用");
    }

    private static void assertNotPushed(FakeJdbc jdbc) {
        assertTrue(jdbc.pool.stream().allMatch(r -> "BATCHED".equals(r.get("status"))),
                "没推成功就不许把池行标成 PUSHED");
    }
}
