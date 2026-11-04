package com.lab.dataworks.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DW-9 回归：自动生成的编码必须过各模块编码校验正则、后缀字母表受限、且大量生成不重号。
 * 正则与 DataServiceController / BiController 内联校验一致，收紧时需同步。
 */
class AutoCodeTest {

    @Test
    void matchesCodePattern() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int i = 0; i < 200; i++) {
            String code = AutoCode.generate("svc");
            assertTrue(code.matches("[A-Za-z][A-Za-z0-9_]{1,63}"), code);
            assertTrue(code.startsWith("svc_" + date + "_"), code);
        }
    }

    @Test
    void suffixOnlyFromUnambiguousAlphabet() {
        String code = AutoCode.generate("dash");
        String suffix = code.substring(code.lastIndexOf('_') + 1);
        assertEquals(6, suffix.length());
        assertTrue(suffix.matches("[abcdefghjkmnpqrstuvwxyz23456789]{6}"), suffix);
    }

    @Test
    void noDuplicatesInSample() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            assertTrue(seen.add(AutoCode.generate("svc")), "duplicate at #" + i);
        }
    }
}
