package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
    void reissueForRecovery_invalidatesOldTokensThenSavesANewOne_carryingFirstRequestedAtForward() {
        User user = new User("recovery-test@example.com", "hash");
        Instant now = Instant.now();
        Instant originalFirstRequestedAt = now.minusSeconds(1800);

        PasswordResetTokenService.IssuedResetToken result =
                underTest.reissueForRecovery(user, now, originalFirstRequestedAt);

        verify(passwordResetTokenRepository).invalidateAllForUser(user.getId(), now);
        assertEquals("raw-token", result.rawToken());
        assertEquals(originalFirstRequestedAt, result.token().getFirstRequestedAt(),
                "recovery must carry the ORIGINAL request's timestamp forward, not restart the window at now");
        assertEquals(now, result.token().getCreatedAt(), "the reissued row's own createdAt is still genuinely new");
    }

    @Test
    void giveUpOnRecovery_delegatesToTheRepositorysAtomicGiveUp() {
        User user = new User("give-up-test@example.com", "hash");
        Instant now = Instant.now();
        java.util.UUID tokenId = java.util.UUID.randomUUID();
        when(passwordResetTokenRepository.giveUpOn(tokenId, now)).thenReturn(1);

        boolean result = underTest.giveUpOnRecovery(tokenId, now);

        assertEquals(true, result);
        verify(passwordResetTokenRepository).giveUpOn(tokenId, now);
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
