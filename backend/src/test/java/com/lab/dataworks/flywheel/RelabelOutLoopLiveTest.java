package com.lab.dataworks.flywheel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.mosbridge.MosClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-CHAIN-1 卡面验收「真实 mos 上一次：封批→出环 PUSHED→relabel 集 items=批行数
 * （答案空=待标注）；同批重试不新增同名数据集」的可重复用例（形态同 REQ-FW-1
 * {@code AnnotationPushLiveTest}：门控 + 口令从环境注入不入库）。
 *
 * <p>开关：{@code CHAIN1_LIVE=1} 才执行，缺省 SKIP 不炸常规构建。所需环境：</p>
 * <pre>
 *   CHAIN1_LIVE=1
 *   CHAIN1_MOS_BASE=http://127.0.0.1:8091     # 与被测代码同源的新实例（跑法见 scripts/e2e/chain-relabel-live.sh）
 *   CHAIN1_MOS_USER / CHAIN1_MOS_PASS         # mos 账号（夹具=种子 ${MOS_ADMIN_USER}/${MOS_ADMIN_PASSWORD}，已登记 mos/README.md:22）
 *   CHAIN1_PG_URL=... CHAIN1_PG_USER=... CHAIN1_PG_PASS=...  # V180 三表（同脚本起的一次性 postgres:16-alpine）
 * </pre>
 * <p>断言面：①旧载荷（只带 user 轮 jsonl）在真 mos 仍 400——复现原缺陷做红检对照；
 * ②封批→出环成功 mos_task_ref=ds@0、items=批行数且答案全空；③同台账键再推=复用同名集、
 * 零新建、条目不翻倍。跑完自动删夹具数据集，证据行 {@code [BUG-CHAIN-1][EVIDENCE]} 前缀。</p>
 */
@EnabledIfEnvironmentVariable(named = "CHAIN1_LIVE", matches = "1")
class RelabelOutLoopLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void outLoopClosesOnRealMosWithRetryIdempotency() throws Exception {
        String base = require("CHAIN1_MOS_BASE");
        MosClient client = new MosClient(base, require("CHAIN1_MOS_USER"),
                require("CHAIN1_MOS_PASS"), 20000);
        JdbcTemplate pg = new JdbcTemplate(new DriverManagerDataSource(
                require("CHAIN1_PG_URL"), require("CHAIN1_PG_USER"), require("CHAIN1_PG_PASS")));
        String tenant = "m5chain1-" + Long.toHexString(System.currentTimeMillis());
        seedPool(pg, tenant);

        // —— ① 红检对照：旧口径载荷（每行只带 user 轮的 jsonl）在真 mos 必须仍被 400 拒——
        //（这是 REQ-CHAIN-E2E-1 环6 台账 last_error 的原话形态；不拒=对端口径又变了、本卡前提失效）
        String legacyJsonl = "{\"messages\":[{\"role\":\"user\",\"content\":\""
                + UUID.randomUUID() + "\"}]}";
        byte[] file = legacyJsonl.getBytes(StandardCharsets.UTF_8);
        String probeName = "zz-chain1-redcheck-" + System.currentTimeMillis();
        JsonNode created = read(exchange(client, "POST", "/api/datasets",
                MAPPER.writeValueAsBytes(java.util.Map.of("name", probeName))));
        String probeId = created.path("id").asText();
        MosClient.Upstream bad = exchange(client, "POST",
                "/api/datasets/" + probeId + "/items/upload", multipart(file));
        String badText = bad.body() == null ? "" : new String(bad.body(), StandardCharsets.UTF_8);
        assertEquals(400, bad.status(),
                "红检：旧载荷（只带 user 轮 jsonl）在真 mos 必须仍 400——不红=对端口径又变、本卡前提失效");
        assertTrue(badText.contains("没有解析到问答"),
                "必须复现台账 last_error 原文口径，实际=" + badText);
        exchange(client, "DELETE", "/api/datasets/" + probeId, null);
        System.out.println("[BUG-CHAIN-1][EVIDENCE] 红检：只带 user 轮的 jsonl 真 mos 仍 400「"
                + badText.trim() + "」");

        // —— ② 真封批→出环：固定 3 行、阈值 3 ——
        pg.update("insert into platform.setting (setting_key, setting_value, updated_by) "
                + "values ('flywheel.badcase.batch_size','3','chain1-live') "
                + "on conflict (setting_key) do update set setting_value='3'");
        MosClientRelabelGateway gateway = new MosClientRelabelGateway(client);
        RelabelTriggerService trigger = new RelabelTriggerService(pg, new FlywheelSettings(pg),
                provider(gateway));
        int batches = trigger.triggerOnce();
        assertEquals(1, batches, "达阈值应封 1 批");
        Long batchId = pg.queryForObject(
                "select id from platform.flywheel_relabel_batch where tenant_id=? order by id desc limit 1",
                Long.class, tenant);
        JsonNode batch = MAPPER.valueToTree(pg.queryForMap(
                "select status, mos_task_ref, coalesce(last_error,'') last_error from platform.flywheel_relabel_batch where id=?",
                batchId));
        assertEquals("PUSHED", batch.path("status").asText(),
                "出环必须成功（卡面验收），失败原因=" + batch.path("last_error").asText());
        String ref = batch.path("mos_task_ref").asText();
        String dsId = ref.split("@")[0];
        assertTrue(dsId.startsWith("ds_"), "对方主键=ds_xxx（契约 §10 坑 2），实际=" + ref);
        System.out.println("[BUG-CHAIN-1][EVIDENCE] 出环 PUSHED：ledger=badcase:" + tenant + ":1 ref=" + ref);

        JsonNode items = read(exchange(client, "GET", "/api/datasets/" + dsId + "/items", null));
        assertEquals(3, items.size(), "relabel 集 items=批行数（BUG-CHAIN-1 核心断言）");
        for (JsonNode item : items) {
            assertEquals("", item.path("a").asText(), "答案必须为空=待标注（金标由标注员产出）：" + item);
            assertTrue(item.path("q").asText().startsWith("链路验收-"), "问题文本必须原样进集：" + item);
        }
        System.out.println("[BUG-CHAIN-1][EVIDENCE] items=" + items.size() + " 且答案全空（待标注）");

        // —— ③ 同台账键重试：复用同名集、零新建、条目不翻倍 ——
        int before = countSets(client, "relabel-badcase:" + tenant + ":1");
        String ref2 = gateway.createRelabelTask("badcase:" + tenant + ":1", "重试复验",
                RelabelJsonl.poolTsv(List.of("链路验收-q1-" + tenant, "链路验收-q2-" + tenant,
                        "链路验收-q3-" + tenant)));
        assertEquals(ref.split("@")[0], ref2.split("@")[0], "重试必须落在同一数据集");
        assertEquals(before, countSets(client, "relabel-badcase:" + tenant + ":1"),
                "同批重试不新增同名数据集（出血回归锚）");
        JsonNode itemsAfter = read(exchange(client, "GET", "/api/datasets/" + dsId + "/items", null));
        assertEquals(3, itemsAfter.size(), "重试后条目不翻倍（对方按 q 去重兜底）");
        System.out.println("[BUG-CHAIN-1][EVIDENCE] 重试幂等：同名集=" + before + "→"
                + countSets(client, "relabel-badcase:" + tenant + ":1") + "，items 保持 "
                + itemsAfter.size());

        exchange(client, "DELETE", "/api/datasets/" + dsId, null);   // 夹具自清
    }

    // ---------- 夹具与通道 ----------

    private static void seedPool(JdbcTemplate pg, String tenant) {
        for (int i = 1; i <= 3; i++) {
            pg.update("insert into platform.badcase_pool (tenant_id, source_event_id, question, "
                            + "question_hash, signal) values (?, ?, ?, md5(?), 'NO_ANSWER')",
                    tenant, System.nanoTime() + i, "链路验收-q" + i + "-" + tenant, "q" + i + tenant);
        }
    }

    private static int countSets(MosClient client, String name) throws Exception {
        JsonNode arr = read(exchange(client, "GET", "/api/datasets", null));
        int n = 0;
        for (JsonNode d : arr) {
            if (name.equals(d.path("name").asText())) {
                n++;
            }
        }
        return n;
    }

    private static MosClient.Upstream exchange(MosClient client, String method, String path, byte[] body)
            throws Exception {
        String ctype = body != null && path.endsWith("/items/upload")
                ? "multipart/form-data; boundary=----chain1Live" : "application/json";
        return client.exchange("m5-chain1-live", method, path, body,
                body == null ? null : ctype);
    }

    private static byte[] multipart(byte[] fileBytes) {
        String head = "------chain1Live\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"redcheck.jsonl\"\r\nContent-Type: application/x-ndjson\r\n\r\n";
        String tail = "\r\n------chain1Live--\r\n";
        return concat(head.getBytes(StandardCharsets.UTF_8), fileBytes,
                tail.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    private static JsonNode read(MosClient.Upstream up) throws Exception {
        String text = up.body() == null ? "" : new String(up.body(), StandardCharsets.UTF_8);
        if (up.status() < 200 || up.status() >= 300) {
            throw new IllegalStateException("HTTP " + up.status() + " " + text);
        }
        return MAPPER.readTree(text);
    }

    private static ObjectProvider<MosRelabelGateway> provider(MosRelabelGateway gw) {
        return new ObjectProvider<>() {
            @Override
            public MosRelabelGateway getObject() {
                return gw;
            }

            @Override
            public MosRelabelGateway getObject(Object... args) {
                return gw;
            }

            @Override
            public MosRelabelGateway getIfAvailable() {
                return gw;
            }

            @Override
            public MosRelabelGateway getIfUnique() {
                return gw;
            }
        };
    }

    private static String require(String env) {
        String v = System.getenv(env);
        assertNotNull(v, "缺少环境变量 " + env);
        assertTrue(!v.isBlank(), "缺少环境变量 " + env);
        return v;
    }
}
