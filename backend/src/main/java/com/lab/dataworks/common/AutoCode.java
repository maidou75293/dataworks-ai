package com.lab.dataworks.common;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * REQ-DW-9：编号自动生成规则（全局口径）。
 *
 * <p>规则为 {@code 前缀_yyyyMMdd_xxxxxx}：6 位随机后缀字母表剔除易混字符（i/l/o、0/1），
 * 结果天然满足各模块编码校验正则 {@code [A-Za-z][A-Za-z0-9_]{1,63}}，可直接用作 URL 路径
 * 片段（如 {@code /api/ds/{code}}）。每日后缀空间 32^6 ≈ 10 亿；唯一性由各表唯一键兜底，
 * 调用方「查重不过夜、插入再重试」即可。</p>
 */
public final class AutoCode {

    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AutoCode() {
    }

    /** 生成一个形如 {@code svc_20261027_k3f9xq} 的编码。 */
    public static String generate(String prefix) {
        StringBuilder sb = new StringBuilder(prefix)
                .append('_').append(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
                .append('_');
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
