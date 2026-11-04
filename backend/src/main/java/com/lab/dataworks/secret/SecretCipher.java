package com.lab.dataworks.secret;

import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感信息落盘加密（AES-256-GCM）。
 *
 * <p>格式：<code>enc:v1:base64(nonce[12] || ciphertext || tag[16])</code>。
 * 历史明文值（没有前缀）保持可读，启动时由 {@link SecretMigration} 统一加密。</p>
 *
 * <p>密钥来自 <code>PLATFORM_SECRET_KEY</code>：优先按 base64 解成 32 字节，
 * 否则对字符串做 SHA-256。未配置密钥时拒绝启动，避免悄悄退回明文存储。</p>
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${platform.secret-key:}") String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "PLATFORM_SECRET_KEY 未配置：拒绝启动，避免敏感信息以明文落库");
        }
        this.key = new SecretKeySpec(parseKey(configured.trim()), "AES");
        log.info("敏感信息加密已启用（AES-256-GCM）");
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, blob, 0, nonce.length);
            System.arraycopy(encrypted, 0, blob, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new IllegalStateException("敏感信息加密失败", e);
        }
    }

    /** 历史明文原样返回；前缀值解密失败说明密钥不对或数据损坏。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(blob, 0, nonce, 0, NONCE_BYTES);
            byte[] ciphertext = new byte[blob.length - NONCE_BYTES];
            System.arraycopy(blob, NONCE_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("敏感信息解密失败（密钥不匹配或数据损坏）");
            throw new ApiException(500, "敏感信息解密失败，请检查平台密钥 PLATFORM_SECRET_KEY");
        }
    }

    private static byte[] parseKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 不是 base64：按口令处理
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化平台密钥", e);
        }
    }
}
