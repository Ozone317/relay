package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.user.EmailVerificationProperties;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailVerificationTokenServiceTest {

    private EmailVerificationTokenRepository emailVerificationTokenRepository;
    private UserRepository userRepository;
    private SecureTokenGenerator secureTokenGenerator;
    private EmailVerificationTokenService underTest;

    @BeforeEach
    void setUp() {
        emailVerificationTokenRepository = mock(EmailVerificationTokenRepository.class);
        userRepository = mock(UserRepository.class);
        secureTokenGenerator = mock(SecureTokenGenerator.class);
        EmailVerificationProperties properties = new EmailVerificationProperties();
        properties.setTokenTtl(Duration.ofHours(24));
        properties.setBaseUrl("https://example.com/verify-email");

        underTest = new EmailVerificationTokenService(emailVerificationTokenRepository, userRepository,
                secureTokenGenerator, properties);

        when(secureTokenGenerator.generateRawToken()).thenReturn("raw-token");
        when(secureTokenGenerator.hash("raw-token")).thenReturn("hashed-token");
        when(emailVerificationTokenRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issue_invalidatesOldTokensThenSavesANewOne() {
        User user = new User("issue-test@example.com", "hash");
        Instant now = Instant.now();

        EmailVerificationTokenService.IssuedVerificationToken result = underTest.issue(user, now);

        verify(emailVerificationTokenRepository).invalidateAllForUser(user.getId(), now);
        assertEquals("raw-token", result.rawToken());
        assertEquals("hashed-token", result.token().getTokenHash());
    }

    @Test
    void consumeAndVerify_throws_whenConsumeUpdatesZeroRows() {
        when(secureTokenGenerator.hash("bad-token")).thenReturn("bad-hash");
        when(emailVerificationTokenRepository.consume(eq("bad-hash"), any())).thenReturn(0);

        assertThrows(InvalidOrExpiredVerificationTokenException.class,
                () -> underTest.consumeAndVerify("bad-token", Instant.now()));

        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void consumeAndVerify_marksTheUserVerified_whenConsumeSucceeds() {
        User user = new User("confirm-test@example.com", "hash");
        EmailVerificationToken token =
                new EmailVerificationToken(user, "hashed-token", Instant.now().plusSeconds(3600), Instant.now());
        when(secureTokenGenerator.hash("raw-token")).thenReturn("hashed-token");
        when(emailVerificationTokenRepository.consume(eq("hashed-token"), any())).thenReturn(1);
        when(emailVerificationTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(token));

        EmailVerificationToken result = underTest.consumeAndVerify("raw-token", Instant.now());

        assertEquals(true, result.getUser().isEmailVerified());
        verify(userRepository).save(user);
    }
}
