package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.support.background.EnableTestBackgroundExecution;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = "JWT_SECRET=test-only-jwt-secret-for-recovery-tests-32bytes")
@EnableTestBackgroundExecution({})
class PasswordResetRecoveryRollbackPostgresTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetTokenService underTest;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User user;
    private PasswordResetToken candidate;

    @AfterEach
    void tearDown() {
        if (candidate != null) {
            jdbcTemplate.update("DELETE FROM password_reset_tokens WHERE id = ?", candidate.getId());
        }
        if (user != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
        }
    }

    @Test
    void failedSuccessorPersistence_rollsBackExactCandidateRetirement() {
        user = userRepository.saveAndFlush(new User("recovery-rollback-" + UUID.randomUUID() + "@example.com", "hash"));
        Instant createdAt = Instant.now().minus(Duration.ofMinutes(2));
        Instant firstRequestedAt = Instant.now().minus(Duration.ofMinutes(5));
        candidate = passwordResetTokenRepository
                .saveAndFlush(new PasswordResetToken(user, "recovery-rollback-" + UUID.randomUUID(),
                        Instant.now().plus(Duration.ofMinutes(30)), createdAt, firstRequestedAt));
        UUID candidateId = candidate.getId();
        Instant persistedFirstRequestedAt = jdbcTemplate.queryForObject(
                "SELECT first_requested_at FROM password_reset_tokens WHERE id = ?", java.sql.Timestamp.class,
                candidateId).toInstant();

        doAnswer(invocation -> {
            PasswordResetToken attempted = invocation.getArgument(0);
            if (!attempted.getId().equals(candidateId)) {
                throw new IllegalStateException("simulated successor persistence failure");
            }
            return invocation.callRealMethod();
        }).when(passwordResetTokenRepository).saveAndFlush(any(PasswordResetToken.class));

        assertThrows(IllegalStateException.class, () -> underTest.recoverCandidate(candidateId, user.getId(),
                Duration.ofSeconds(1), Duration.ofHours(1)));

        InOrder order = inOrder(passwordResetTokenRepository);
        order.verify(passwordResetTokenRepository).giveUpOn(eq(candidateId), any(Instant.class));
        order.verify(passwordResetTokenRepository).saveAndFlush(any(PasswordResetToken.class));

        Map<String, Object> t0 = jdbcTemplate.queryForMap("""
                SELECT used_at, reset_email_dispatched_at, first_requested_at
                FROM password_reset_tokens
                WHERE id = ?
                """, candidateId);
        assertNull(t0.get("used_at"), "T0 retirement must roll back when successor persistence fails");
        assertNull(t0.get("reset_email_dispatched_at"), "T0 must remain undispatched after rollback");
        Instant storedFirstRequestedAt = ((java.sql.Timestamp) t0.get("first_requested_at")).toInstant();
        assertEquals(persistedFirstRequestedAt, storedFirstRequestedAt,
                "the recovery-chain origin must not change");
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM password_reset_tokens
                WHERE user_id = ? AND id <> ?
                """, Integer.class, user.getId(), candidateId), "no successor may survive the failed transaction");
    }
}
