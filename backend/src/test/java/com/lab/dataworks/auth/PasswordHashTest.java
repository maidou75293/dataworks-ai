package com.lab.dataworks.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-8 / P3-9：哈希串异常不能被吞成「密码正确」，旧迭代次数要能识别并升级。
 */
class PasswordHashTest {

    @Test
    void hashRoundTripAndIterationMarkedCurrent() {
        String stored = PasswordHash.hash("abc12345");
        assertTrue(PasswordHash.verify("abc12345", stored));
        assertFalse(PasswordHash.verify("wrong123", stored));
        // 新哈希已经是当前强度，登录时不需要再写一次库
        assertFalse(PasswordHash.needsRehash(stored));
    }

    @Test
    void malformedHashIsTreatedAsPasswordMismatch() {
        assertFalse(PasswordHash.verify("abc12345", "not-a-hash"));
        // 迭代次数不是数字：属于哈希串本身损坏，按密码错误处理
        assertFalse(PasswordHash.verify("abc12345", "pbkdf2_sha256$abc$AA$AA"));
    }

    @Test
    void legacyIterationCountNeedsRehash() {
        // 旧版本 120k 次的哈希串：格式合法但强度不足，登录成功后应透明升级
        assertTrue(PasswordHash.needsRehash("pbkdf2_sha256$120000$c2FsdA$aGFzaA"));
        assertFalse(PasswordHash.needsRehash(null));
        assertFalse(PasswordHash.needsRehash("garbage"));
    }
}
