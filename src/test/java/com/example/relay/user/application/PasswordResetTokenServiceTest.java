package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.PasswordResetProperties;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredResetTokenException;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordResetTokenServiceTest {

    private PasswordResetTokenRepository passwordResetTokenRepository;
    private UserRepository userRepository;
    private SecureTokenGenerator secureTokenGenerator;
    private RefreshTokenService refreshTokenService;
    private PasswordEncoder passwordEncoder;
    private PasswordResetTokenService underTest;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        userRepository = mock(UserRepository.class);
        secureTokenGenerator = mock(SecureTokenGenerator.class);
        refreshTokenService = mock(RefreshTokenService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        PasswordResetProperties properties = new PasswordResetProperties();
        properties.setTokenTtl(Duration.ofMinutes(30));
        properties.setBaseUrl("https://example.com/reset-password");

        underTest = new PasswordResetTokenService(passwordResetTokenRepository, userRepository, secureTokenGenerator,
                refreshTokenService, passwordEncoder, properties);

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
    }

    @Test
    void consumeAndResetPassword_throws_whenConsumeUpdatesZeroRows() {
        when(secureTokenGenerator.hash("bad-token")).thenReturn("bad-hash");
        when(passwordResetTokenRepository.consume(eq("bad-hash"), any())).thenReturn(0);

        assertThrows(InvalidOrExpiredResetTokenException.class,
                () -> underTest.consumeAndResetPassword("bad-token", "newPassword123", Instant.now()));

        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAll(any(), any());
    }

    @Test
    void consumeAndResetPassword_updatesPasswordAndRevokesSessions_whenConsumeSucceeds() {
        User user = new User("confirm-test@example.com", "old-hash");
        PasswordResetToken token =
                new PasswordResetToken(user, "hashed-token", Instant.now().plusSeconds(1800), Instant.now());
        when(secureTokenGenerator.hash("raw-token")).thenReturn("hashed-token");
        when(passwordResetTokenRepository.consume(eq("hashed-token"), any())).thenReturn(1);
        when(passwordResetTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("newPassword123")).thenReturn("new-hash");

        PasswordResetToken result = underTest.consumeAndResetPassword("raw-token", "newPassword123", Instant.now());

        assertEquals("new-hash", result.getUser().getPasswordHash());
        verify(userRepository).save(user);
        verify(refreshTokenService).revokeAll(eq(user.getId()), any());
    }
}
