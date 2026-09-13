package com.example.relay.user.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PasswordResetTokenCleanupTaskTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetTokenCleanupTask underTest;

    @Autowired
    private PasswordResetTokenCleanupProperties properties;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(new User("cleanup-test@example.com", "hash"));
    }

    @Test
    void cleanup_deletesOnlyRowsOlderThanTheRetentionWindow() {
        Instant now = Instant.now();
        Duration retention = properties.getRetention();

        PasswordResetToken tooOld = passwordResetTokenRepository.save(new PasswordResetToken(user, "too-old-hash",
                now.minus(retention).minusSeconds(60), now.minus(retention).minusSeconds(60)));
        PasswordResetToken withinRetention = passwordResetTokenRepository.save(new PasswordResetToken(user,
                "within-retention-hash", now.minus(retention).plusSeconds(60), now.minus(retention).plusSeconds(60)));

        underTest.cleanup();

        assertFalse(passwordResetTokenRepository.findById(tooOld.getId()).isPresent());
        assertTrue(passwordResetTokenRepository.findById(withinRetention.getId()).isPresent());
    }
}
