package com.example.relay.user.infrastructure;

import com.example.relay.user.domain.PasswordResetToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE password_reset_tokens
                SET used_at = :now
                WHERE user_id = :userId
                AND used_at IS NULL
            """, nativeQuery = true)
    int invalidateAllForUser(UUID userId, Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE password_reset_tokens
                SET used_at = :now
                WHERE token_hash = :tokenHash
                AND used_at IS NULL
                AND expires_at > :now
            """, nativeQuery = true)
    int consume(String tokenHash, Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE password_reset_tokens
                SET reset_email_dispatched_at = :now
                WHERE id = :tokenId
                AND reset_email_dispatched_at IS NULL
            """, nativeQuery = true)
    int claimResetEmailDispatch(UUID tokenId, Instant now);

    List<PasswordResetToken> findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore(
            Instant expiresAfter, Instant updatedBefore, Limit limit);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                DELETE FROM password_reset_tokens
                WHERE expires_at < :threshold
            """, nativeQuery = true)
    int deleteExpiredBefore(Instant threshold);
}
