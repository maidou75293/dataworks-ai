package com.lab.dataworks.flywheel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 问题文本指纹（REQ-FW-3②去重的简化口径）：参照 text-similarity 讨论的结论——
 * 不上向量，归一化后 sha256 即可。归一化 = 去首尾空白 + 连续空白压成一个空格 + 转小写。
 *
 * <p>注意语义是「精确重复去重」，不做近似归并；近似归并要上相似度，留后续卡。</p>
 */
final class QueryHasher {

    private QueryHasher() {
    }

    /** 归一化；空白文本返回 null（调用方按「不可成池」处理，不塞空串进哈希）。 */
    static String normalize(String question) {
        if (question == null) {
            return null;
        }
        String v = question.trim().replaceAll("\\s+", " ");
        return v.isEmpty() ? null : v.toLowerCase(Locale.ROOT);
    }

    /** 归一化后的 sha256 十六进制（64 位小写）；文本为空抛异常——禁止静默造出一个「空问题」哈希。 */
    static String hash(String question) {
        String normalized = normalize(question);
        if (normalized == null) {
            throw new IllegalArgumentException("question 不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 无 SHA-256", e);
        }
    }
}
