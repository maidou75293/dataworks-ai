package com.lab.dataworks.flywheel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REQ-FW-3②去重指纹单测：归一化口径（大小写/空白不敏感）+ sha256 稳定 + 空文本禁止成池。
 */
class QueryHasherTest {

    @Test
    void normalizeCollapsesWhitespaceAndLowercases() {
        assertEquals("查一下 昨日 销量", QueryHasher.normalize("  查一下   昨日  销量 "));
        assertNull(QueryHasher.normalize(null));
        assertNull(QueryHasher.normalize("   "));
    }

    @Test
    void sameQuerySameHash_replayDedupes() {
        assertEquals(QueryHasher.hash("Hello   World"), QueryHasher.hash("hello world"),
                "同 query 重放必须撞同一个哈希（部分唯一索引挡住=不进池）");
        assertEquals(64, QueryHasher.hash("q").length());
    }

    @Test
    void differentQueriesDifferentHash() {
        assertNotEquals(QueryHasher.hash("昨日销量"), QueryHasher.hash("昨日销售额"));
    }

    @Test
    void blankQueryRejectedNotSilentlyHashed() {
        assertThrows(IllegalArgumentException.class, () -> QueryHasher.hash("  "));
    }
}
