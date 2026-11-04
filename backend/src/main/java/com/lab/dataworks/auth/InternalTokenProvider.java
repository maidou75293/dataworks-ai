package com.lab.dataworks.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内部接口凭证（`/api/internal/**`）的取值来源。
 *
 * <p>v471 G-P1-2：以前是构造时 {@code @Value} 注入的环境变量，只在启动时读一次。
 * Secret 轮换后 Prometheus 拿到新值、后端还认旧值，抓取会**永久 401**，只能重启修复。</p>
 *
 * <p>现在优先读挂载的 Secret 文件（kubelet 会把更新后的 Secret 同步到投影卷），
 * 带 TTL 缓存（默认 5s），过期后重读；环境变量保留作兜底（本地开发 / 未挂卷时）。
 * 总生效延迟 ≈ kubelet 投影同步（通常几十秒）+ TTL（≤5s），但**不需要重启后端**。</p>
 */
class InternalTokenProvider {

    private final String filePath;
    private final String envToken;
    private final long ttlMs;

    private volatile String cached;
    private volatile long cachedAt;

    InternalTokenProvider(String filePath, String envToken, long ttlMs) {
        this.filePath = filePath == null ? "" : filePath;
        this.envToken = envToken == null ? "" : envToken;
        this.ttlMs = ttlMs <= 0 ? 5000L : ttlMs;
    }

    /** 当前生效的令牌：文件优先，文件缺失/空白时回落到环境变量。 */
    String current() {
        long now = System.currentTimeMillis();
        String value = cached;
        if (value != null && now - cachedAt < ttlMs) {
            return value;
        }
        String fromFile = readFile();
        String resolved = fromFile != null && !fromFile.isBlank() ? fromFile : envToken;
        cached = resolved;
        cachedAt = now;
        return resolved;
    }

    /** 常量时间比较，避免按字节提前返回泄漏前缀。 */
    boolean matches(String candidate) {
        String expected = current();
        if (expected.isBlank() || candidate == null || candidate.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private String readFile() {
        if (filePath.isBlank()) {
            return null;
        }
        try {
            // Secret 文件末尾常带换行；kubectl create secret --from-file 也会保留原文件的换行
            return Files.readString(Path.of(filePath), StandardCharsets.UTF_8)
                    .replaceAll("[\\r\\n]+$", "")
                    .trim();
        } catch (IOException | RuntimeException e) {
            // 文件不存在/不可读：回落到环境变量，不阻断鉴权
            return null;
        }
    }
}
