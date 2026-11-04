package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DW-15 回归：Kafka 抽样字段推断与连接错误分类（纯函数，不连库）。
 *
 * <p>验收点：抽样消息 → 字段名/推断类型；观测缺失或 null → 可空；
 * 连接失败按 网络/凭据/权限/其它 给出分类文案，禁止静默吞错。</p>
 */
class SourceMetadataInferTest {

    private final SourceMetadataService svc = new SourceMetadataService(new ObjectMapper());

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cols(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("columns");
    }

    @Test
    void 基本类型与非JSON消息() {
        Map<String, Object> out = svc.inferFields(List.of(
                "{\"id\": 1, \"name\": \"abc\", \"ok\": true, \"w\": 1.5, \"tags\": [1], \"ext\": {\"a\":1}, \"nil\": null}",
                "not json",
                "[1,2,3]"));
        assertEquals(3, out.get("sampleCount"));
        assertEquals(1, out.get("parsedCount"), "非 JSON 与 JSON 数组不参与推断");
        Map<String, Map<String, Object>> byName = cols(out).stream().collect(
                java.util.stream.Collectors.toMap(c -> (String) c.get("name"), c -> c));
        assertEquals("INTEGER", byName.get("id").get("typeName"));
        assertEquals("STRING", byName.get("name").get("typeName"));
        assertEquals("BOOLEAN", byName.get("ok").get("typeName"));
        assertEquals("NUMBER", byName.get("w").get("typeName"));
        assertEquals("ARRAY", byName.get("tags").get("typeName"));
        assertEquals("OBJECT", byName.get("ext").get("typeName"));
        assertEquals("NULL", byName.get("nil").get("typeName"));
        assertEquals(true, byName.get("nil").get("nullable"));
        assertEquals(false, byName.get("id").get("nullable"));
    }

    @Test
    void 字段缺失视为可空且保留观测次数() {
        Map<String, Object> out = svc.inferFields(List.of(
                "{\"a\": 1, \"b\": \"x\"}",
                "{\"a\": 2}",
                "{\"a\": 3}"));
        Map<String, Map<String, Object>> byName = cols(out).stream().collect(
                java.util.stream.Collectors.toMap(c -> (String) c.get("name"), c -> c));
        assertEquals(3, byName.get("a").get("seenCount"));
        assertFalse((Boolean) byName.get("a").get("nullable"));
        assertEquals(1, byName.get("b").get("seenCount"));
        assertTrue((Boolean) byName.get("b").get("nullable"), "部分记录缺失 → 可空");
    }

    @Test
    void 同字段多类型合并展示() {
        Map<String, Object> out = svc.inferFields(List.of(
                "{\"v\": 1}", "{\"v\": \"s\"}"));
        Map<String, Map<String, Object>> byName = cols(out).stream().collect(
                java.util.stream.Collectors.toMap(c -> (String) c.get("name"), c -> c));
        assertEquals("INTEGER|STRING", byName.get("v").get("typeName"));
    }

    @Test
    void 全部非JSON时解析数为0() {
        Map<String, Object> out = svc.inferFields(List.of("abc", "114514"));
        assertEquals(0, out.get("parsedCount"));
        assertTrue(cols(out).isEmpty());
    }

    @Test
    void 连接错误分类网络() {
        Exception e = new SQLException(" Communications link failure: Connection timed out");
        assertEquals("网络", SourceMetadataService.classifyConnectionError(e));
        assertEquals("网络", SourceMetadataService.classifyConnectionError(
                new RuntimeException(new java.net.UnknownHostException("db.internal"))));
    }

    @Test
    void 连接错误分类凭据() {
        assertEquals("凭据", SourceMetadataService.classifyConnectionError(
                new SQLException("Access denied for user 'u'@'h' (using password: YES)")));
        assertEquals("凭据", SourceMetadataService.classifyConnectionError(
                new SQLException("FATAL: password authentication failed for user \"u\"")));
    }

    @Test
    void 连接错误分类权限() {
        assertEquals("权限", SourceMetadataService.classifyConnectionError(
                new SQLException("SELECT command denied to user 'u'@'h' for table 't'")));
        assertEquals("权限", SourceMetadataService.classifyConnectionError(
                new SQLException("Unknown database 'nope'")));
    }

    @Test
    void 认不出的异常归其它并保留原文() {
        Exception e = new SQLException("something brand new");
        assertEquals("其它", SourceMetadataService.classifyConnectionError(e));
        assertTrue(SourceMetadataService.rootText(e).contains("something brand new"));
    }
}
