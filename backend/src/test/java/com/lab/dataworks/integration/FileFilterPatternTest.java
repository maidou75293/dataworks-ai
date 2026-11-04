package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-29：非法 file_filter_pattern 必须报 400，不能静默变成「不过滤」。
 */
class FileFilterPatternTest {

    @Test
    void validPatternKeepsFiltering() {
        Pattern p = FileIncrementService.compileFilter(".*\\.csv");
        assertTrue(p.matcher("orders.csv").matches());
    }

    @Test
    void blankPatternMeansNoFilter() {
        assertNull(FileIncrementService.compileFilter(null));
        assertNull(FileIncrementService.compileFilter("   "));
    }

    @Test
    void invalidPatternIsBadRequest() {
        ApiException e = assertThrows(ApiException.class,
                () -> FileIncrementService.compileFilter("[unclosed"));
        assertEquals(400, e.getStatus(), e.getMessage());
    }
}
