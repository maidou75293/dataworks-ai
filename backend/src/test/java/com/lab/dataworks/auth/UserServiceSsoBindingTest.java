package com.lab.dataworks.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceSsoBindingTest {

    @Test
    void builtinAccountsAreNeverReused() {
        assertFalse(UserService.ssoMayReuse("LOCAL", true, true));
        assertFalse(UserService.ssoMayReuse("SSO", true, true));
    }

    @Test
    void ssoAccountsAreReused() {
        assertTrue(UserService.ssoMayReuse("SSO", false, false));
        assertTrue(UserService.ssoMayReuse("SSO", false, true));
    }

    @Test
    void localAccountsRequireExplicitOptIn() {
        assertFalse(UserService.ssoMayReuse("LOCAL", false, false));
        assertFalse(UserService.ssoMayReuse(null, false, false));
        assertTrue(UserService.ssoMayReuse("LOCAL", false, true));
    }
}
