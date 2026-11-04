package com.lab.dataworks.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v471 G-P1-2：内部凭证必须能从文件热读——Secret 换值后不重启也要生效。
 * TTL 用 50ms 便于测试；生产默认 5s。
 */
class InternalTokenProviderTest {

    @TempDir
    Path dir;

    @Test
    void fileValueIsPickedUpAfterTtlWithoutRestart() throws Exception {
        Path file = dir.resolve("internal-token");
        Files.writeString(file, "old-token\n", StandardCharsets.UTF_8);
        InternalTokenProvider provider = new InternalTokenProvider(file.toString(), "env-token", 50);

        assertTrue(provider.matches("old-token"), "文件里的值应生效");
        assertFalse(provider.matches("env-token"), "文件优先于环境变量");

        // 换值：TTL 内仍要读缓存，过期后必须读到新值
        Files.writeString(file, "new-token", StandardCharsets.UTF_8);
        assertTrue(provider.matches("old-token"), "TTL 内允许旧值");
        Thread.sleep(120);
        assertTrue(provider.matches("new-token"), "TTL 过期后应读到新值");
        assertFalse(provider.matches("old-token"), "旧值应失效");
    }

    @Test
    void missingFileFallsBackToEnv() {
        InternalTokenProvider provider = new InternalTokenProvider(
                dir.resolve("not-there").toString(), "env-token", 50);
        assertEquals("env-token", provider.current());
        assertTrue(provider.matches("env-token"));
    }

    @Test
    void blankFileFallsBackToEnv() throws Exception {
        Path file = dir.resolve("blank");
        Files.writeString(file, "\n", StandardCharsets.UTF_8);
        InternalTokenProvider provider = new InternalTokenProvider(file.toString(), "env-token", 50);
        assertEquals("env-token", provider.current());
    }

    @Test
    void noTokenRejectsEverything() {
        InternalTokenProvider provider = new InternalTokenProvider("", "", 50);
        assertFalse(provider.matches("anything"));
        assertFalse(provider.matches(""));
        assertFalse(provider.matches(null));
    }
}
