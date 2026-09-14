package com.example.relay.user.infrastructure;

import com.example.relay.user.domain.EmailVerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE email_verification_tokens
                SET used_at = :now
                WHERE user_id = :userId
                AND used_at IS NULL
            """, nativeQuery = true)
    int invalidateAllForUser(UUID userId, Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE email_verification_tokens
                SET used_at = :now
                WHERE token_hash = :tokenHash
                AND used_at IS NULL
                AND expires_at > :now
            """, nativeQuery = true)
    int consume(String tokenHash, Instant now);
}
