package com.example.relay.user.application;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.RefreshTokenGenerator;
import com.example.relay.user.domain.RefreshToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidRefreshTokenException;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final AuthProperties authProperties;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
            RefreshTokenGenerator refreshTokenGenerator, AuthProperties authProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.authProperties = authProperties;
    }

    @Transactional
    public String issue(User user, Instant now) {
        String rawToken = refreshTokenGenerator.generateRawToken();
        refreshTokenRepository.save(new RefreshToken(user, refreshTokenGenerator.hash(rawToken),
                now.plus(authProperties.getRefreshIdleWindow()), now));
        return rawToken;
    }

    @Transactional
    public User validateAndSlide(String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException("No refresh token supplied");
        }

        RefreshToken token = refreshTokenRepository.findByTokenHash(refreshTokenGenerator.hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is not recognised"));

        if (token.getRevokedAt() != null) {
            throw new InvalidRefreshTokenException("Refresh token has been revoked");
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        refreshTokenRepository.slide(token.getId(), now.plus(authProperties.getRefreshIdleWindow()));
        return token.getUser();
    }

    @Transactional
    public void revoke(String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.revokeByTokenHash(refreshTokenGenerator.hash(rawToken), now);
    }

    @Transactional
    public int revokeAll(UUID userId, Instant now) {
        return refreshTokenRepository.revokeAllByUserId(userId, now);
    }
}
