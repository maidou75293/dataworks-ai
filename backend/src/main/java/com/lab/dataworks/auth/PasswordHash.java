package com.lab.dataworks.auth;

import com.lab.dataworks.common.ApiException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 口令哈希。格式：
 * <code>pbkdf2_sha256$迭代次数$salt(base64)$hash(base64)</code>。
 * 不引入额外依赖，也不保存明文。
 */
final class PasswordHash {

    private static final String PREFIX = "pbkdf2_sha256";
    // P3-9：OWASP 对 PBKDF2-HMAC-SHA256 的建议下限是 600k。旧哈希仍可验证，
    // 登录成功后由 UserService 按 needsRehash() 自动升级，不需要强制改密。
    private static final int ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY = hash("dummy-password-for-timing");

    private PasswordHash() {
    }

    static String hash(String raw) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] digest = pbkdf2(raw, salt, ITERATIONS);
        Base64.Encoder enc = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + ITERATIONS + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(digest);
    }

    static boolean verify(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !PREFIX.equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            Base64.Decoder dec = Base64.getDecoder();
            byte[] salt = dec.decode(parts[2]);
            byte[] expected = dec.decode(parts[3]);
            byte[] actual = pbkdf2(raw, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            // P3-8：只有哈希串本身不合法（格式/迭代次数/base64 坏）才当密码错误。
            // PBKDF2 基础设施异常（jce provider 不可用等）会被包装成
            // IllegalStateException，必须继续抛出去，不能被吞成「密码错误」。
            return false;
        }
    }

    /** 旧哈希的迭代次数低于当前强度时，登录成功后透明升级（P3-9）。 */
    static boolean needsRehash(String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            return Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 用户名不存在时也跑一次同成本哈希，避免通过响应时间枚举账号。 */
    static void dummyVerify(String raw) {
        verify(raw, DUMMY);
    }

    static void validate(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("密码不能为空");
        }
        if (raw.length() < 8) {
            throw ApiException.badRequest("密码至少 8 位");
        }
        if (raw.length() > 128) {
            throw ApiException.badRequest("密码不能超过 128 位");
        }
        boolean letter = raw.chars().anyMatch(Character::isLetter);
        boolean digit = raw.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw ApiException.badRequest("密码必须同时包含字母和数字");
        }
    }

    private static byte[] pbkdf2(String raw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令哈希失败", e);
        }
    }
}
