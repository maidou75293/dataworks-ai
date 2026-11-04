package com.lab.dataworks.mosbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UX-MEDIAURL-7 · 收口重写器纯函数单测（无 Spring/网络，行为表与红绿锁闸2逐行对齐）。
 */
class MosMediaUrlsTest {

    private static final Set<String> UP = MosMediaUrls.allowedHostPorts(
            "http://10.99.99.11:30095", "192.168.7.7, 192.168.7.7:9900, [fd00::1]:8090");

    @Test
    void allowedSet_canonFamily8090_upstreamBase_envExtras() {
        assertTrue(UP.contains("127.0.0.1:8090"));
        assertTrue(UP.contains("localhost:8090"));
        assertTrue(UP.contains("::1:8090"));
        assertTrue(UP.contains("0.0.0.0:8090"));
        assertTrue(UP.contains("172.17.0.1:8090"));
        assertTrue(UP.contains("10.99.99.11:30095"));
        assertTrue(UP.contains("192.168.7.7:8090"), "无端口追加条目按默认媒体端口 8090");
        assertTrue(UP.contains("192.168.7.7:9900"));
        assertTrue(UP.contains("fd00::1:8090"), "方括号 IPv6 剥括号入集");
        assertFalse(UP.contains("10.99.99.11:8090"), "上游端口不是 8090 时不得顺手扩集");
    }

    @Test
    void allowedSet_baseParseDefaultsAndGarbageSafe() {
        assertTrue(MosMediaUrls.allowedHostPorts("https://mos.internal/", null).contains("mos.internal:443"));
        assertTrue(MosMediaUrls.allowedHostPorts("mos.internal:8123", null).contains("mos.internal:8123"));
        // 坏配置不炸通道，也不引入误伤条目
        Set<String> junk = MosMediaUrls.allowedHostPorts("not a url ][", "bad:port:extra, [::1]oops");
        assertTrue(junk.contains("127.0.0.1:8090"), "自址族必须永远在集内");
        assertTrue(MosMediaUrls.allowedHostPorts(null, null).contains("::1:8090"));
    }

    @Test
    void relativizeText_decisionTable() {
        assertEquals("/media/x.png", MosMediaUrls.relativizeText("http://127.0.0.1:8090/media/x.png", UP));
        assertEquals("/media/x.png", MosMediaUrls.relativizeText("http://LOCALHOST:8090/media/x.png", UP),
                "host 大小写不敏感");
        assertEquals("/media/x.png", MosMediaUrls.relativizeText("http://[::1]:8090/media/x.png", UP));
    }

    @Test
    void relativizeText_defaultPort80_notInSet_passthrough() {
        // 172.17.0.1 不带端口 → 隐含 80，不在 {x:8090} 集内：透传，不猜
        assertEquals("http://172.17.0.1/media/x.png",
                MosMediaUrls.relativizeText("http://172.17.0.1/media/x.png", UP));
    }

    @Test
    void relativizeText_keepsEverythingElse() {
        String[] keep = {
                "https://cdn.example.com/media/cat.jpg",
                "http://127.0.0.1:9000/media/w.png",
                "/media/rel.png",
                "http://127.0.0.1:8090/data/other",
                "http://127.0.0.1:8090/mediaX/y",
                "data:image/png;base64,AAAA",
                "blob:abc",
        };
        for (String s : keep) {
            assertEquals(s, MosMediaUrls.relativizeText(s, UP), "不该动: " + s);
        }
    }

    @Test
    void relativizeText_percentEncodingPreservedVerbatim() {
        assertEquals("/media/%E4%B8%AD%20a%23b.png",
                MosMediaUrls.relativizeText("http://localhost:8090/media/%E4%B8%AD%20a%23b.png", UP));
        // 尾串（路径+查询）逐字符保留
        assertEquals("/media/x.png?a=1&b=%2F2",
                MosMediaUrls.relativizeText("http://127.0.0.1:8090/media/x.png?a=1&b=%2F2", UP));
    }

    @Test
    void relativizeText_multipleHitsAndHugePortJunk() {
        String in = "{\"a\":\"http://127.0.0.1:8090/media/1.png\",\"b\":\"http://10.99.99.11:30095/media/2.png\"}";
        assertEquals("{\"a\":\"/media/1.png\",\"b\":\"/media/2.png\"}", MosMediaUrls.relativizeText(in, UP));
        // 端口位塞超长数字：解析失败=当不命中，绝不抛异常炸通道
        String junk = "http://127.0.0.1:99999999999999999999/media/x.png";
        assertEquals(junk, MosMediaUrls.relativizeText(junk, UP));
    }

    @Test
    void relativize_bytesSafety() {
        byte[] body = "{\"u\":\"http://127.0.0.1:8090/media/x.png\"}".getBytes(StandardCharsets.UTF_8);
        byte[] out = MosMediaUrls.relativize(body, UP);
        assertEquals("{\"u\":\"/media/x.png\"}", new String(out, StandardCharsets.UTF_8));
        // 改写后长度变化必须重编码 UTF-8（含中文键值）
        byte[] cn = "{\"名\":\"http://localhost:8090/media/图.png\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"名\":\"/media/图.png\"}", new String(MosMediaUrls.relativize(cn, UP), StandardCharsets.UTF_8));
        // 非 UTF-8（如 0xFF 起头/压缩流）：原样返回，绝不产生 U+FFFD 污染
        byte[] binary = new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 't', 't', 'p'};
        assertArrayEquals(binary, MosMediaUrls.relativize(binary, UP));
        // 空体与空集合：恒等返回原引用（零拷贝路径）
        byte[] empty = new byte[0];
        assertArrayEquals(empty, MosMediaUrls.relativize(empty, UP));
        assertEquals(body, MosMediaUrls.relativize(body, Set.of()));
    }

    @Test
    void isJson_coversJsonFamilies() {
        assertTrue(MosMediaUrls.isJson("application/json"));
        assertTrue(MosMediaUrls.isJson("application/json; charset=utf-8"));
        assertTrue(MosMediaUrls.isJson("application/problem+JSON"));
        assertTrue(MosMediaUrls.isJson("application/ndjson"));
        assertFalse(MosMediaUrls.isJson("text/plain"));
        assertFalse(MosMediaUrls.isJson("image/png"));
        assertFalse(MosMediaUrls.isJson(null));
    }

    @Test
    void location_nullSafeAndRelativePreserved() {
        assertNull(MosMediaUrls.relativizeLocation(null, UP));
        assertEquals("/media/t.png", MosMediaUrls.relativizeLocation("http://127.0.0.1:8090/media/t.png", UP));
        assertEquals("/api/other", MosMediaUrls.relativizeLocation("/api/other", UP));
        assertEquals("http://cdn.example.com/media/x",
                MosMediaUrls.relativizeLocation("http://cdn.example.com/media/x", UP));
    }
}
