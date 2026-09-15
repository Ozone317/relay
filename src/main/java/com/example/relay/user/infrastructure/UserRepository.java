package com.example.relay.user.infrastructure;

import com.example.relay.user.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /**
     * Atomically sets the account's real password and marks it verified, but ONLY if it is still
     * PENDING (email_verified = false). Shared by both EmailVerificationTokenService.consumeAndVerify
     * and PasswordResetTokenService.consumeAndResetPassword - see the design spec's
     * "first-activation-wins" section. This query's own affected-row count is the sole authority on
     * whether the account was still PENDING at the moment of the write. Bumps version so any
     * unrelated full-entity save racing this row's write correctly collides via optimistic locking
     * instead of silently losing.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE users
                SET password = :passwordHash,
                    email_verified = true,
                    version = version + 1
                WHERE id = :userId
                AND email_verified = false
            """, nativeQuery = true)
    int activateIfPending(UUID userId, String passwordHash);

    /**
     * Atomically sets the account's password, WITHOUT touching email_verified - used only by
     * PasswordResetTokenService.consumeAndResetPassword's "already ACTIVE" branch (an ordinary
     * password change, not an activation). Bumps version for the same reason as activateIfPending.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
                UPDATE users
                SET password = :passwordHash,
                    version = version + 1
                WHERE id = :userId
            """, nativeQuery = true)
    int setPasswordOnly(UUID userId, String passwordHash);

    /**
     * Pessimistic-write-locks this user's row and returns it. Callers use this ONLY to establish a
     * deadlock-free lock order before touching any token row - never to read or act on the returned
     * entity's mutable fields, and never to mutate/save it. See the design spec's "Lock ordering"
     * section: without acquiring this lock first, in the same order, in both
     * EmailVerificationTokenService.consumeAndVerify and
     * PasswordResetTokenService.consumeAndResetPassword, a verify-vs-reset race can deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> lockForUpdate(UUID userId);
}
