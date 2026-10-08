package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.relay.email.EmailDispatchMessage;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.support.background.EnableTestBackgroundExecution;
import com.example.relay.user.PasswordResetProperties;
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
import org.mockito.ArgumentCaptor;
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
    private PasswordResetService passwordResetService;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoSpyBean
    private EmailDispatchPublisher emailDispatchPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordResetProperties passwordResetProperties;

    private User user;

    @AfterEach
    void tearDown() {
        if (user != null) {
            jdbcTemplate.update("DELETE FROM password_reset_tokens WHERE user_id = ?", user.getId());
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
        }
    }

    @Test
    void failedSuccessorPersistence_rollsBackExactCandidateRetirement() {
        user = userRepository.saveAndFlush(new User("recovery-rollback-" + UUID.randomUUID() + "@example.com", "hash"));
        Instant createdAt = Instant.now().minus(Duration.ofMinutes(2));
        Instant firstRequestedAt = Instant.now().minus(Duration.ofMinutes(5));
        PasswordResetToken candidate = passwordResetTokenRepository
                .saveAndFlush(new PasswordResetToken(user, "recovery-rollback-" + UUID.randomUUID(),
                        Instant.now().plus(Duration.ofMinutes(30)), createdAt, firstRequestedAt));
        UUID candidateId = candidate.getId();
        Instant persistedFirstRequestedAt =
                jdbcTemplate.queryForObject("SELECT first_requested_at FROM password_reset_tokens WHERE id = ?",
                        java.sql.Timestamp.class, candidateId).toInstant();

        doAnswer(invocation -> {
            PasswordResetToken attempted = invocation.getArgument(0);
            if (!attempted.getId().equals(candidateId)) {
                throw new IllegalStateException("simulated successor persistence failure");
            }
            return invocation.callRealMethod();
        }).when(passwordResetTokenRepository).saveAndFlush(any(PasswordResetToken.class));

        assertThrows(IllegalStateException.class, () -> underTest.recoverCandidate(candidateId, user.getId(),
                Duration.ofSeconds(1), Duration.ofHours(1)));

        ArgumentCaptor<Instant> retirementTime = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<PasswordResetToken> attemptedSuccessor = ArgumentCaptor.forClass(PasswordResetToken.class);
        InOrder order = inOrder(passwordResetTokenRepository);
        order.verify(passwordResetTokenRepository).giveUpOn(eq(candidateId), retirementTime.capture());
        order.verify(passwordResetTokenRepository).saveAndFlush(attemptedSuccessor.capture());
        Instant decisionTime = retirementTime.getValue();
        PasswordResetToken successor = attemptedSuccessor.getValue();
        assertNotEquals(candidateId, successor.getId(), "the flush attempt must be for a distinct successor row");
        assertEquals(decisionTime, successor.getCreatedAt(),
                "the exact retirement timestamp must also be the successor creation time");
        assertEquals(decisionTime.plus(passwordResetProperties.getTokenTtl()), successor.getExpiresAt(),
                "the successor expiry must use the same captured decision instant");

        Map<String, Object> t0 = jdbcTemplate.queryForMap("""
                SELECT used_at, reset_email_dispatched_at, first_requested_at
                FROM password_reset_tokens
                WHERE id = ?
                """, candidateId);
        assertNull(t0.get("used_at"), "T0 retirement must roll back when successor persistence fails");
        assertNull(t0.get("reset_email_dispatched_at"), "T0 must remain undispatched after rollback");
        Instant storedFirstRequestedAt = ((java.sql.Timestamp) t0.get("first_requested_at")).toInstant();
        assertEquals(persistedFirstRequestedAt, storedFirstRequestedAt, "the recovery-chain origin must not change");
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM password_reset_tokens
                WHERE user_id = ? AND id <> ?
                """, Integer.class, user.getId(), candidateId), "no successor may survive the failed transaction");
    }

    @Test
    void outerRecoveryPath_propagatesSuccessorFailure_rollsBackCandidateAndNeverPublishes() {
        user = userRepository
                .saveAndFlush(new User("outer-recovery-rollback-" + UUID.randomUUID() + "@example.com", "hash"));
        Instant createdAt = Instant.now().minus(Duration.ofMinutes(2));
        Instant firstRequestedAt = Instant.now().minus(Duration.ofMinutes(5));
        PasswordResetToken candidate = passwordResetTokenRepository
                .saveAndFlush(new PasswordResetToken(user, "outer-recovery-rollback-" + UUID.randomUUID(),
                        Instant.now().plus(Duration.ofMinutes(30)), createdAt, firstRequestedAt));
        UUID candidateId = candidate.getId();
        Instant persistedFirstRequestedAt =
                jdbcTemplate.queryForObject("SELECT first_requested_at FROM password_reset_tokens WHERE id = ?",
                        java.sql.Timestamp.class, candidateId).toInstant();

        doAnswer(invocation -> {
            PasswordResetToken attempted = invocation.getArgument(0);
            if (!attempted.getId().equals(candidateId)) {
                throw new IllegalStateException("simulated outer successor persistence failure");
            }
            return invocation.callRealMethod();
        }).when(passwordResetTokenRepository).saveAndFlush(any(PasswordResetToken.class));

        assertThrows(IllegalStateException.class, () -> passwordResetService.issueAndDispatchForRecovery(candidateId,
                user.getId(), Duration.ofSeconds(1), Duration.ofHours(1)));

        ArgumentCaptor<Instant> retirementTime = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<PasswordResetToken> attemptedSuccessor = ArgumentCaptor.forClass(PasswordResetToken.class);
        InOrder recoverySteps = inOrder(passwordResetTokenRepository);
        recoverySteps.verify(passwordResetTokenRepository).giveUpOn(eq(candidateId), retirementTime.capture());
        recoverySteps.verify(passwordResetTokenRepository).saveAndFlush(attemptedSuccessor.capture());
        assertNotEquals(candidateId, attemptedSuccessor.getValue().getId());
        assertEquals(retirementTime.getValue(), attemptedSuccessor.getValue().getCreatedAt());

        Map<String, Object> t0 = jdbcTemplate.queryForMap("""
                SELECT used_at, reset_email_dispatched_at, first_requested_at
                FROM password_reset_tokens
                WHERE id = ?
                """, candidateId);
        assertNull(t0.get("used_at"), "T0 retirement must roll back through the outer service path");
        assertNull(t0.get("reset_email_dispatched_at"), "T0 remains undispatched after rollback");
        Instant storedFirstRequestedAt = ((java.sql.Timestamp) t0.get("first_requested_at")).toInstant();
        assertEquals(persistedFirstRequestedAt, storedFirstRequestedAt, "the recovery-chain origin must not change");
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM password_reset_tokens
                WHERE user_id = ?
                """, Integer.class, user.getId()), "no successor may survive the failed transaction");
        verify(emailDispatchPublisher, never()).publish(any(EmailDispatchMessage.class));
    }
}
