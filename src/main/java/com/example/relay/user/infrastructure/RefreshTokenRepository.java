package com.example.relay.user.infrastructure;

import com.example.relay.user.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE refresh_tokens
                SET revoked_at = :now
                WHERE token_hash = :tokenHash
                AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeByTokenHash(String tokenHash, Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE refresh_tokens
                SET revoked_at = :now
                WHERE user_id = :userId
                AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeAllByUserId(UUID userId, Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE refresh_tokens
                SET expires_at = :expiresAt
                WHERE id = :id
                AND revoked_at IS NULL
            """, nativeQuery = true)
    int slide(UUID id, Instant expiresAt);
}
