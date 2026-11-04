package com.lab.dataworks.integration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DW-17 回归：运行结果摘要的纯渲染逻辑。
 *
 * <p>摘要契约四件事必须可判定：记录数取自哪个列、字段清单来源优先级、
 * 失败阶段归类（连接/校验/写入/抽取）、目标表缺失行为与 SyncTaskService
 * 的 schema_save_mode 缺省同源（CREATE_SCHEMA_WHEN_NOT_EXIST=自动建表）。</p>
 *
 * 跑法：{@code mvn -q -Dtest=SyncRunSummaryTest test}（integration 模块，见 docs/测试分层.md）
 */
class SyncRunSummaryTest {

    // ---------- 失败阶段归类 ----------

    @Test
    void stageConnectionWinsOverOtherKeywords() {
        // 「read ... connection refused」同时含抽取与连接关键词：连接优先
        assertEquals("连接", SyncRunSummaryService.classifyStage(
                "failed to read from source: Connection refused"));
        assertEquals("连接", SyncRunSummaryService.classifyStage("Communications link failure"));
        assertEquals("连接", SyncRunSummaryService.classifyStage("Access denied for user 'sync'"));
    }

    @Test
    void stageVerifyBeforeWrite() {
        // 行数不一致是校验阶段问题，不能被 write 关键词带偏成「写入」
        assertEquals("校验", SyncRunSummaryService.classifyStage("行数不一致：读取 100 / 写入 90"));
        assertEquals("校验", SyncRunSummaryService.classifyStage("verify FAILED: rows mismatch"));
    }

    @Test
    void stageWriteAndExtractAndUnknown() {
        assertEquals("写入", SyncRunSummaryService.classifyStage("sink write failed: stream load error"));
        assertEquals("写入", SyncRunSummaryService.classifyStage("Doris FE not available"));
        assertEquals("抽取", SyncRunSummaryService.classifyStage("Kafka topic demo_orders not found"));
        assertEquals("抽取", SyncRunSummaryService.classifyStage("query exceeded budget while fetching"));
        assertEquals("未知", SyncRunSummaryService.classifyStage("boom"));
        assertEquals("未知", SyncRunSummaryService.classifyStage(null));
    }

    @Test
    void failureOnlyForFailedLikeStatus() {
        assertNull(SyncRunSummaryService.failure("SUCCESS", null));
        assertNull(SyncRunSummaryService.failure("NEVER_RUN", null));
        assertNull(SyncRunSummaryService.failure("RUNNING", "transient connect blurb"));
        Map<String, Object> f = SyncRunSummaryService.failure("FAILED", "Connection refused");
        assertEquals("连接", f.get("stage"));
        assertEquals("Connection refused", f.get("reason"));
        // 停止/超时给专属阶段；空原因不假装成功
        assertEquals("已停止", SyncRunSummaryService.failure("STOPPED", null).get("stage"));
        Map<String, Object> to = SyncRunSummaryService.failure("TIMEOUT", "");
        assertEquals("超时", to.get("stage"));
        assertTrue(String.valueOf(to.get("reason")).contains("查看运行日志"));
    }

    // ---------- 字段清单 ----------

    @Test
    void fieldsFromSourceSchemaDeclared() {
        Map<String, Object> kafka = Map.of("schema",
                new LinkedHashMap<>(Map.of("fields", ordered("order_id", "string", "amount", "double"))));
        List<Map<String, String>> f = SyncRunSummaryService.extractFields(
                kafka, Map.of(), List.of("select * from whatever"));
        assertEquals(2, f.size());
        assertEquals("order_id", f.get(0).get("name"));
        assertEquals("string", f.get(0).get("type"));
    }

    @Test
    void fieldsFallBackToSelectColumns() {
        List<Map<String, String>> f = SyncRunSummaryService.extractFields(
                Map.of(), Map.of(), List.of(
                        "insert into tgt values (?)",                       // 非 SELECT 跳过
                        "select distinct t.id, user_name as name, count(1), city from db.t t"));
        assertEquals(List.of("id", "name", "city"), f.stream().map(m -> m.get("name")).toList());
    }

    @Test
    void starSelectKeepsPlaceholder() {
        List<Map<String, String>> f = SyncRunSummaryService.parseSelectColumns("select * from t");
        assertEquals(1, f.size());
        assertEquals("*", f.get(0).get("name"));
    }

    @Test
    void selectInSubqueryParsesOuterColumns() {
        // 括号里的 from/逗号不能参与顶层切分
        List<Map<String, String>> f = SyncRunSummaryService.parseSelectColumns(
                "select id, (select max(v) from other) as last_v, amt from main");
        assertEquals(List.of("id", "last_v", "amt"), f.stream().map(m -> m.get("name")).toList());
    }

    @Test
    void unparsableSqlYieldsNoFields() {
        assertTrue(SyncRunSummaryService.parseSelectColumns("with x as (select 1)").isEmpty());
        assertTrue(SyncRunSummaryService.extractFields(Map.of(), Map.of(), List.of()).isEmpty());
    }

    // ---------- 落点与建表行为 ----------

    @Test
    void landingPathProjectDatabaseTable() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectName", "零售数仓");
        row.put("sinkDatabase", "gongxiang_dev");
        row.put("sinkTable", "gx_orders");
        row.put("sinkType", "Doris");
        Map<String, Object> l = SyncRunSummaryService.landing(row, Map.of());
        assertEquals("零售数仓 → gongxiang_dev → gx_orders", l.get("path"));
    }

    @Test
    void landingFallsBackToSinkConfigPath() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectName", "P");
        row.put("sinkType", "S3File");
        Map<String, Object> l = SyncRunSummaryService.landing(row, Map.of("path", "s3://corpus/out"));
        assertEquals("P → s3://corpus/out", l.get("path"));
    }

    @Test
    void tableBehaviorSameDefaultAsSinkRenderer() {
        // 缺省与 SyncTaskService 行内一致：不配置 = CREATE_SCHEMA_WHEN_NOT_EXIST = 自动建表
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST",
                SyncRunSummaryService.tableBehavior(Map.of()).get("mode"));
        assertTrue(String.valueOf(SyncRunSummaryService.tableBehavior(Map.of()).get("text"))
                .contains("自动建表"));
        assertEquals("报错（ERROR_WHEN_SCHEMA_NOT_EXIST）",
                SyncRunSummaryService.tableBehavior(
                        Map.of("schema_save_mode", "ERROR_WHEN_SCHEMA_NOT_EXIST")).get("text"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST",
                SyncRunSummaryService.tableBehavior(
                        Map.of("schemaSaveMode", "CREATE_SCHEMA_WHEN_NOT_EXIST")).get("mode"));
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
