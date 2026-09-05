package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.RefreshTokenGenerator;
import com.example.relay.user.domain.RefreshToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidRefreshTokenException;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    private RefreshTokenService underTest;

    private User user;
    private Instant now;

    @BeforeEach
    void setUp() {
        underTest = new RefreshTokenService(refreshTokenRepository, refreshTokenGenerator, new AuthProperties());
        user = new User("daksh@example.com", "hash");
        now = Instant.now();
    }

    private RefreshToken liveToken() {
        return new RefreshToken(user, "hashed", now.plus(30, ChronoUnit.DAYS), now);
    }

    @Test
    void issue_persistsTheHashAndReturnsTheRawToken() {
        when(refreshTokenGenerator.generateRawToken()).thenReturn("raw");
        when(refreshTokenGenerator.hash("raw")).thenReturn("hashed");

        String raw = underTest.issue(user, now);

        assertEquals("raw", raw);
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertEquals("hashed", saved.getValue().getTokenHash());
        assertEquals(now.plus(30, ChronoUnit.DAYS), saved.getValue().getExpiresAt());
    }

    @Test
    void validateAndSlide_throws_whenNoTokenSupplied() {
        assertThrows(InvalidRefreshTokenException.class, () -> underTest.validateAndSlide(null, now));
        verify(refreshTokenRepository, never()).slide(any(), any());
    }

    @Test
    void validateAndSlide_throws_whenHashMatchesNoRow() {
        when(refreshTokenGenerator.hash("raw")).thenReturn("hashed");
        when(refreshTokenRepository.findByTokenHash("hashed")).thenReturn(Optional.empty());

        assertThrows(InvalidRefreshTokenException.class, () -> underTest.validateAndSlide("raw", now));
    }

    @Test
    void validateAndSlide_throws_whenTheIdleWindowHasLapsed() {
        RefreshToken expired = new RefreshToken(user, "hashed", now.minusSeconds(1), now);
        when(refreshTokenGenerator.hash("raw")).thenReturn("hashed");
        when(refreshTokenRepository.findByTokenHash("hashed")).thenReturn(Optional.of(expired));

        assertThrows(InvalidRefreshTokenException.class, () -> underTest.validateAndSlide("raw", now));
        verify(refreshTokenRepository, never()).slide(any(), any());
    }

    @Test
    void validateAndSlide_slidesTheWindowAndReturnsTheUser_onTheHappyPath() {
        RefreshToken live = liveToken();
        when(refreshTokenGenerator.hash("raw")).thenReturn("hashed");
        when(refreshTokenRepository.findByTokenHash("hashed")).thenReturn(Optional.of(live));

        User result = underTest.validateAndSlide("raw", now);

        assertSame(user, result);
        verify(refreshTokenRepository).slide(live.getId(), now.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void revoke_isANoOp_whenNoCookieWasSent() {
        underTest.revoke(null, now);

        verify(refreshTokenRepository, never()).revokeByTokenHash(any(), any());
    }

    @Test
    void revoke_revokesByHash() {
        when(refreshTokenGenerator.hash("raw")).thenReturn("hashed");

        underTest.revoke("raw", now);

        verify(refreshTokenRepository).revokeByTokenHash("hashed", now);
    }

    @Test
    void revokeAll_delegatesToTheRepository() {
        UUID userId = UUID.randomUUID();
        when(refreshTokenRepository.revokeAllByUserId(userId, now)).thenReturn(3);

        assertEquals(3, underTest.revokeAll(userId, now));
    }
}
