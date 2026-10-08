package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredResetTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

class PasswordResetTokenServiceTest {

    private PasswordResetTokenRepository passwordResetTokenRepository;
    private EmailVerificationTokenRepository emailVerificationTokenRepository;
    private UserRepository userRepository;
    private SecureTokenGenerator secureTokenGenerator;
    private RefreshTokenService refreshTokenService;
    private EntityManager entityManager;
    private PasswordResetTokenService underTest;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        emailVerificationTokenRepository = mock(EmailVerificationTokenRepository.class);
        userRepository = mock(UserRepository.class);
        secureTokenGenerator = mock(SecureTokenGenerator.class);
        refreshTokenService = mock(RefreshTokenService.class);
        entityManager = mock(EntityManager.class);
        PasswordResetProperties properties = new PasswordResetProperties();
        properties.setTokenTtl(Duration.ofMinutes(30));
        properties.setBaseUrl("https://example.com/reset-password");

        underTest = new PasswordResetTokenService(passwordResetTokenRepository, emailVerificationTokenRepository,
                userRepository, secureTokenGenerator, refreshTokenService, properties, entityManager);

        when(secureTokenGenerator.generateRawToken()).thenReturn("raw-token");
        when(secureTokenGenerator.hash("raw-token")).thenReturn("hashed-token");
        when(passwordResetTokenRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issue_invalidatesOldTokensThenSavesANewOne() {
        User user = new User("issue-test@example.com", "hash");
        Instant now = Instant.now();

        PasswordResetTokenService.IssuedResetToken result = underTest.issue(user, now);

        verify(passwordResetTokenRepository).invalidateAllForUser(user.getId(), now);
        assertEquals("raw-token", result.rawToken());
        assertEquals("hashed-token", result.token().getTokenHash());
        assertEquals(now, result.token().getFirstRequestedAt(),
                "an ordinary user-initiated issuance always starts its own fresh recovery window");
    }

    @Test
    void recoverCandidate_returnsLostRace_whenUserIsMissing() {
        UUID userId = UUID.randomUUID();
        when(userRepository.lockForUpdate(userId)).thenReturn(Optional.empty());

        var result = underTest.recoverCandidate(UUID.randomUUID(), userId, Duration.ofSeconds(5), Duration.ofHours(1));

        assertEquals(PasswordResetTokenService.RecoveryOutcome.LOST_RACE, result.outcome());
        verify(passwordResetTokenRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void recoverCandidate_returnsLostRace_whenCandidateIsMissing() {
        User user = user("missing-candidate@example.com");
        UUID candidateId = UUID.randomUUID();
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.empty());

        var result = underTest.recoverCandidate(candidateId, user.getId(), Duration.ofSeconds(5), Duration.ofHours(1));

        assertEquals(PasswordResetTokenService.RecoveryOutcome.LOST_RACE, result.outcome());
    }

    @Test
    void recoverCandidate_returnsLostRace_whenCandidateBelongsToAnotherUser() {
        User user = user("observed@example.com");
        User otherUser = user("other@example.com");
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        PasswordResetToken candidate = candidate(otherUser, decisionTime.minusSeconds(20), decisionTime.plusSeconds(60),
                decisionTime.minusSeconds(30), decisionTime.minusSeconds(30), null, null);
        UUID candidateId = UUID.randomUUID();
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));

        var result = recoverAt(candidateId, user, decisionTime, Duration.ofSeconds(5), Duration.ofHours(1));

        assertEquals(PasswordResetTokenService.RecoveryOutcome.LOST_RACE, result.outcome());
        verify(passwordResetTokenRepository, never()).giveUpOn(any(), any());
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
    }

    @Test
    void recoverCandidate_returnsLostRace_whenCandidateIsUsedDispatchedExpiredOrNotStale() {
        User user = user("invalid-candidates@example.com");
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        Instant old = decisionTime.minusSeconds(20);
        PasswordResetToken[] invalid = {
                candidate(user, old, decisionTime.plusSeconds(60), old, old, decisionTime, null),
                candidate(user, old, decisionTime.plusSeconds(60), old, old, null, decisionTime),
                candidate(user, old, decisionTime, old, old, null, null),
                candidate(user, decisionTime.minusSeconds(5), decisionTime.plusSeconds(60), old, old, null, null)};

        for (PasswordResetToken candidate : invalid) {
            UUID candidateId = UUID.randomUUID();
            when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
            when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));

            var result = recoverAt(candidateId, user, decisionTime, Duration.ofSeconds(5), Duration.ofHours(1));

            assertEquals(PasswordResetTokenService.RecoveryOutcome.LOST_RACE, result.outcome());
        }
        verify(passwordResetTokenRepository, never()).giveUpOn(any(), any());
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
    }

    @Test
    void recoverCandidate_returnsExhausted_andRetiresOnlyCandidate_atWindowBoundary() {
        User user = user("exhausted@example.com");
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        Instant cutoff = decisionTime.minus(Duration.ofHours(1));
        PasswordResetToken candidate = candidate(user, decisionTime.minusSeconds(20), decisionTime.plusSeconds(60),
                decisionTime.minusSeconds(20), cutoff, null, null);
        UUID candidateId = UUID.randomUUID();
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));
        when(passwordResetTokenRepository.giveUpOn(candidateId, decisionTime)).thenReturn(1);

        var result = recoverAt(candidateId, user, decisionTime, Duration.ofSeconds(5), Duration.ofHours(1));

        assertEquals(PasswordResetTokenService.RecoveryOutcome.EXHAUSTED, result.outcome());
        verify(passwordResetTokenRepository).giveUpOn(candidateId, decisionTime);
        verify(passwordResetTokenRepository, times(1)).giveUpOn(candidateId, decisionTime);
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
        verify(passwordResetTokenRepository, never()).invalidateAllForUser(any(), any());
    }

    @Test
    void recoverCandidate_reissues_andCarriesLockedFirstRequestedAt() {
        User user = user("reissued@example.com");
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        Instant firstRequestedAt = decisionTime.minusSeconds(300);
        PasswordResetToken candidate = candidate(user, decisionTime.minusSeconds(20), decisionTime.plusSeconds(60),
                decisionTime.minusSeconds(20), firstRequestedAt, null, null);
        UUID candidateId = UUID.randomUUID();
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));
        when(passwordResetTokenRepository.giveUpOn(candidateId, decisionTime)).thenReturn(1);

        var result = recoverAt(candidateId, user, decisionTime, Duration.ofSeconds(5), Duration.ofHours(1));

        assertEquals(PasswordResetTokenService.RecoveryOutcome.REISSUED, result.outcome());
        assertEquals(firstRequestedAt, result.issuedToken().token().getFirstRequestedAt());
        assertEquals(decisionTime, result.issuedToken().token().getCreatedAt());
        verify(passwordResetTokenRepository).giveUpOn(candidateId, decisionTime);
        verify(passwordResetTokenRepository, times(1)).giveUpOn(candidateId, decisionTime);
        verify(passwordResetTokenRepository, never()).invalidateAllForUser(any(), any());
    }

    @Test
    void recoverCandidate_locksUserBeforeCandidate() {
        User user = user("lock-order@example.com");
        UUID candidateId = UUID.randomUUID();
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        PasswordResetToken candidate = candidate(user, decisionTime.minusSeconds(20), decisionTime.plusSeconds(60),
                decisionTime.minusSeconds(20), decisionTime.minusSeconds(30), null, null);
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));
        when(passwordResetTokenRepository.giveUpOn(candidateId, decisionTime)).thenReturn(1);

        recoverAt(candidateId, user, decisionTime, Duration.ofSeconds(5), Duration.ofHours(1));

        InOrder order = inOrder(userRepository, passwordResetTokenRepository);
        order.verify(userRepository).lockForUpdate(user.getId());
        order.verify(passwordResetTokenRepository).findByIdForUpdate(candidateId);
    }

    @Test
    void recoverCandidate_returnsLostRace_whenExactRetirementUpdatesZeroRows() {
        User user = user("retirement-race@example.com");
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        PasswordResetToken candidate = candidate(user, decisionTime.minusSeconds(20), decisionTime.plusSeconds(60),
                decisionTime.minusSeconds(20), decisionTime.minusSeconds(30), null, null);
        UUID candidateId = UUID.randomUUID();
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));
        when(passwordResetTokenRepository.giveUpOn(candidateId, decisionTime)).thenReturn(0);

        var result = recoverAt(candidateId, user, decisionTime, Duration.ofSeconds(5), Duration.ofHours(1));

        assertEquals(PasswordResetTokenService.RecoveryOutcome.LOST_RACE, result.outcome());
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
    }

    @Test
    void recoverCandidate_returnsLostRace_whenExhaustedCandidateRetirementUpdatesZeroRows() {
        User user = user("exhausted-retirement-race@example.com");
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        Instant cutoff = decisionTime.minus(Duration.ofHours(1));
        PasswordResetToken candidate = candidate(user, decisionTime.minusSeconds(20), decisionTime.plusSeconds(60),
                decisionTime.minusSeconds(20), cutoff, null, null);
        UUID candidateId = UUID.randomUUID();
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));
        when(passwordResetTokenRepository.giveUpOn(candidateId, decisionTime)).thenReturn(0);

        var result = recoverAt(candidateId, user, decisionTime, Duration.ofSeconds(5), Duration.ofHours(1));

        assertEquals(PasswordResetTokenService.RecoveryOutcome.LOST_RACE, result.outcome());
        verify(passwordResetTokenRepository, times(1)).giveUpOn(candidateId, decisionTime);
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
        verify(passwordResetTokenRepository, never()).invalidateAllForUser(any(), any());
    }

    @Test
    void recoverCandidate_capturesDecisionTime_onlyAfterBothLocks() {
        User user = user("decision-time-order@example.com");
        UUID candidateId = UUID.randomUUID();
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        PasswordResetToken candidate = candidate(user, decisionTime.minusSeconds(20), decisionTime.plusSeconds(60),
                decisionTime.minusSeconds(20), decisionTime.minusSeconds(30), null, null);
        AtomicInteger decisionTimeReads = new AtomicInteger();

        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenAnswer(invocation -> {
                decisionTimeReads.incrementAndGet();
                return decisionTime;
            });
            when(userRepository.lockForUpdate(user.getId())).thenAnswer(invocation -> {
                assertEquals(0, decisionTimeReads.get(), "the user lock must complete before decision time is read");
                return Optional.of(user);
            });
            when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenAnswer(invocation -> {
                assertEquals(0, decisionTimeReads.get(),
                        "the candidate lock must complete before decision time is read");
                return Optional.of(candidate);
            });
            when(passwordResetTokenRepository.giveUpOn(candidateId, decisionTime)).thenReturn(1);

            var result =
                    underTest.recoverCandidate(candidateId, user.getId(), Duration.ofSeconds(5), Duration.ofHours(1));

            assertEquals(PasswordResetTokenService.RecoveryOutcome.REISSUED, result.outcome());
            assertEquals(1, decisionTimeReads.get(), "recovery must capture exactly one decision instant");
        }
    }

    @Test
    void recoverCandidate_appliesStrictGraceExpiryAndRecoveryWindowBoundaries() {
        User user = user("boundaries@example.com");
        Instant decisionTime = Instant.parse("2026-10-08T10:00:00Z");
        Duration grace = Duration.ofSeconds(5);
        Duration maxWindow = Duration.ofHours(1);
        Instant graceCutoff = decisionTime.minus(grace);
        Instant recoveryCutoff = decisionTime.minus(maxWindow);

        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff.minusNanos(1),
                decisionTime.plusNanos(1), recoveryCutoff.plusNanos(1),
                PasswordResetTokenService.RecoveryOutcome.REISSUED);
        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff, decisionTime.plusNanos(1),
                recoveryCutoff.plusNanos(1), PasswordResetTokenService.RecoveryOutcome.LOST_RACE);
        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff.plusNanos(1), decisionTime.plusNanos(1),
                recoveryCutoff.plusNanos(1), PasswordResetTokenService.RecoveryOutcome.LOST_RACE);
        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff.minusSeconds(1),
                decisionTime.minusNanos(1), recoveryCutoff.plusNanos(1),
                PasswordResetTokenService.RecoveryOutcome.LOST_RACE);
        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff.minusSeconds(1), decisionTime,
                recoveryCutoff.plusNanos(1), PasswordResetTokenService.RecoveryOutcome.LOST_RACE);
        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff.minusSeconds(1),
                decisionTime.plusNanos(1), recoveryCutoff.minusNanos(1),
                PasswordResetTokenService.RecoveryOutcome.EXHAUSTED);
        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff.minusSeconds(1),
                decisionTime.plusNanos(1), recoveryCutoff, PasswordResetTokenService.RecoveryOutcome.EXHAUSTED);
        assertRecoveryOutcome(user, decisionTime, grace, maxWindow, graceCutoff.minusSeconds(1),
                decisionTime.plusNanos(1), recoveryCutoff.plusNanos(1),
                PasswordResetTokenService.RecoveryOutcome.REISSUED);
    }

    private void assertRecoveryOutcome(User user, Instant decisionTime, Duration grace, Duration maxWindow,
            Instant updatedAt, Instant expiresAt, Instant firstRequestedAt,
            PasswordResetTokenService.RecoveryOutcome expected) {
        UUID candidateId = UUID.randomUUID();
        PasswordResetToken candidate = candidate(user, updatedAt, expiresAt, updatedAt, firstRequestedAt, null, null);
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));
        when(passwordResetTokenRepository.giveUpOn(candidateId, decisionTime)).thenReturn(1);

        assertEquals(expected, recoverAt(candidateId, user, decisionTime, grace, maxWindow).outcome());
    }

    private PasswordResetTokenService.RecoveryAttempt recoverAt(UUID candidateId, User user, Instant decisionTime,
            Duration grace, Duration maxWindow) {
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(decisionTime);
            return underTest.recoverCandidate(candidateId, user.getId(), grace, maxWindow);
        }
    }

    private static User user(String email) {
        return new User(email, "hash");
    }

    private static PasswordResetToken candidate(User user, Instant updatedAt, Instant expiresAt, Instant createdAt,
            Instant firstRequestedAt, Instant usedAt, Instant dispatchedAt) {
        PasswordResetToken candidate = mock(PasswordResetToken.class);
        when(candidate.getUser()).thenReturn(user);
        when(candidate.getUpdatedAt()).thenReturn(updatedAt);
        when(candidate.getExpiresAt()).thenReturn(expiresAt);
        when(candidate.getCreatedAt()).thenReturn(createdAt);
        when(candidate.getFirstRequestedAt()).thenReturn(firstRequestedAt);
        when(candidate.getUsedAt()).thenReturn(usedAt);
        when(candidate.getResetEmailDispatchedAt()).thenReturn(dispatchedAt);
        return candidate;
    }

    @Test
    void consumeAndResetPassword_throws_whenTheTokenDoesNotExist() {
        when(secureTokenGenerator.hash("bad-token")).thenReturn("bad-hash");
        when(passwordResetTokenRepository.findByTokenHash("bad-hash")).thenReturn(Optional.empty());

        assertThrows(InvalidOrExpiredResetTokenException.class,
                () -> underTest.consumeAndResetPassword("bad-token", "encoded-password", Instant.now()));

        verify(userRepository, never()).activateIfPending(any(), any());
        verify(refreshTokenService, never()).revokeAll(any(), any());
    }

    @Test
    void consumeAndResetPassword_throws_whenConsumeUpdatesZeroRows() {
        User user = new User("stale-token@example.com", "hash");
        PasswordResetToken token =
                new PasswordResetToken(user, "hashed-token", Instant.now().plusSeconds(1800), Instant.now());
        when(secureTokenGenerator.hash("raw-token")).thenReturn("hashed-token");
        when(passwordResetTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(token));
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.consume(eq("hashed-token"), any())).thenReturn(0);

        assertThrows(InvalidOrExpiredResetTokenException.class,
                () -> underTest.consumeAndResetPassword("raw-token", "encoded-password", Instant.now()));

        verify(userRepository, never()).activateIfPending(any(), any());
        verify(refreshTokenService, never()).revokeAll(any(), any());
    }

    @Test
    void consumeAndResetPassword_activatesAndInvalidatesVerificationTokens_whenAccountWasPending() {
        User user = new User("confirm-test@example.com", "old-hash");
        PasswordResetToken token =
                new PasswordResetToken(user, "hashed-token", Instant.now().plusSeconds(1800), Instant.now());
        when(secureTokenGenerator.hash("raw-token")).thenReturn("hashed-token");
        when(passwordResetTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(token));
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.consume(eq("hashed-token"), any())).thenReturn(1);
        when(userRepository.activateIfPending(user.getId(), "new-hash")).thenReturn(1);

        PasswordResetToken result = underTest.consumeAndResetPassword("raw-token", "new-hash", Instant.now());

        assertEquals(token, result);
        // The detach MUST happen, and MUST precede lockForUpdate - see consumeAndResetPassword's javadoc.
        // PasswordResetToken#user is a default-EAGER @ManyToOne, so findByTokenHash alone leaves a managed,
        // version-stamped User in the persistence context; without detaching it first, lockForUpdate degrades from
        // a fresh SELECT ... FOR UPDATE into a lock-mode UPGRADE that re-checks that stale version. Ordering is the
        // whole point - a detach placed after the lock would be useless - hence InOrder, not a bare verify().
        InOrder inOrder = inOrder(entityManager, userRepository);
        inOrder.verify(entityManager).detach(user);
        inOrder.verify(userRepository).lockForUpdate(user.getId());
        verify(userRepository).activateIfPending(user.getId(), "new-hash");
        verify(userRepository, never()).setPasswordOnly(any(), any());
        verify(emailVerificationTokenRepository).invalidateAllForUser(eq(user.getId()), any());
        verify(passwordResetTokenRepository).invalidateAllForUser(eq(user.getId()), any());
        verify(refreshTokenService).revokeAll(eq(user.getId()), any());
    }

    @Test
    void consumeAndResetPassword_setsPasswordOnly_whenAccountWasAlreadyActive() {
        User user = new User("active-confirm-test@example.com", "old-hash");
        PasswordResetToken token =
                new PasswordResetToken(user, "hashed-token", Instant.now().plusSeconds(1800), Instant.now());
        when(secureTokenGenerator.hash("raw-token")).thenReturn("hashed-token");
        when(passwordResetTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(token));
        when(userRepository.lockForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.consume(eq("hashed-token"), any())).thenReturn(1);
        when(userRepository.activateIfPending(user.getId(), "new-hash")).thenReturn(0);

        underTest.consumeAndResetPassword("raw-token", "new-hash", Instant.now());

        // Asserted on this branch too, not just the activation branch above: this IS Case E's code path (an
        // already-ACTIVE account taking an ordinary password change via setPasswordOnly), the exact scenario where
        // a missing detach caused a second, wholly-valid reset token to be spuriously rejected.
        InOrder inOrder = inOrder(entityManager, userRepository);
        inOrder.verify(entityManager).detach(user);
        inOrder.verify(userRepository).lockForUpdate(user.getId());
        verify(userRepository).setPasswordOnly(user.getId(), "new-hash");
        verify(emailVerificationTokenRepository, never()).invalidateAllForUser(any(), any());
        verify(passwordResetTokenRepository, never()).invalidateAllForUser(any(), any());
        verify(refreshTokenService).revokeAll(eq(user.getId()), any());
    }
}
