package com.example.relay.user.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void newUser_isUnverifiedByDefault() {
        User user = new User("new@example.com", "hash");

        assertFalse(user.isEmailVerified());
    }

    @Test
    void markEmailVerified_setsEmailVerifiedTrue() {
        User user = new User("new@example.com", "hash");

        user.markEmailVerified();

        assertTrue(user.isEmailVerified());
    }
}
