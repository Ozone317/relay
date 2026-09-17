package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Limit;

@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PasswordResetTokenRepositoryTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetTokenRepository underTest;

    @Autowired
    private UserRepository userRepository;

    private User user;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        user = userRepository.save(new User("reset-repo-test@example.com", "hash"));
    }

    private PasswordResetToken persisted(String tokenHash, Instant expiresAt) {
        return underTest.save(new PasswordResetToken(user, tokenHash, expiresAt, now));
    }

    @Test
    void firstRequestedAt_defaultsToNow_butCanBeCarriedForwardDistinctlyFromCreatedAt() {
        Instant original = now.minusSeconds(3600);
        PasswordResetToken recovered = underTest
                .save(new PasswordResetToken(user, "hash-recovered", now.plus(30, ChronoUnit.MINUTES), now, original));

        PasswordResetToken reloaded = underTest.findByTokenHash("hash-recovered").orElseThrow();
        assertEquals(original, reloaded.getFirstRequestedAt());
        assertEquals(now, reloaded.getCreatedAt());
    }

    @Test
    void findByTokenHash_returnsTheMatchingRow() {
        persisted("hash-a", now.plus(30, ChronoUnit.MINUTES));

        assertTrue(underTest.findByTokenHash("hash-a").isPresent());
    }

    @Test
    void consume_returns1thenZero_soTheSameTokenCannotBeConsumedTwice() {
        persisted("hash-b", now.plus(30, ChronoUnit.MINUTES));

        assertEquals(1, underTest.consume("hash-b", now));
        assertEquals(0, underTest.consume("hash-b", now));
    }

    @Test
    void consume_returnsZero_forAnExpiredToken() {
        persisted("hash-c", now.minus(1, ChronoUnit.MINUTES));

        assertEquals(0, underTest.consume("hash-c", now));
    }

    @Test
    void invalidateAllForUser_marksEveryUnusedTokenUsed_butNotAnAlreadyUsedOne() {
        PasswordResetToken alreadyUsed = persisted("hash-d", now.plus(30, ChronoUnit.MINUTES));
        underTest.consume("hash-d", now);
        persisted("hash-e", now.plus(30, ChronoUnit.MINUTES));

        int updated = underTest.invalidateAllForUser(user.getId(), now.plusSeconds(1));

        assertEquals(1, updated);
        assertTrue(underTest.findByTokenHash("hash-e").orElseThrow().getUsedAt() != null);
    }

    @Test
    void giveUpOn_returns1thenZero_soAGivenUpRowCanNeverBeGivenUpOnTwice() {
        PasswordResetToken token = persisted("hash-give-up", now.plus(30, ChronoUnit.MINUTES));

        assertEquals(1, underTest.giveUpOn(token.getId(), now));
        assertEquals(0, underTest.giveUpOn(token.getId(), now));
        assertTrue(underTest.findByTokenHash("hash-give-up").orElseThrow().getUsedAt() != null);
    }

    @Test
    void giveUpOn_doesNotReviveAnAlreadyUsedToken() {
        PasswordResetToken token = persisted("hash-already-used", now.plus(30, ChronoUnit.MINUTES));
        underTest.consume("hash-already-used", now);

        assertEquals(0, underTest.giveUpOn(token.getId(), now.plusSeconds(1)));
    }

    @Test
    void giveUpOn_doesNotTouchATokenThatHasAlreadyBeenDispatched() {
        PasswordResetToken token = persisted("hash-already-dispatched", now.plus(30, ChronoUnit.MINUTES));
        underTest.claimResetEmailDispatch(token.getId(), now);

        assertEquals(0, underTest.giveUpOn(token.getId(), now.plusSeconds(1)));
        assertTrue(underTest.findByTokenHash("hash-already-dispatched").orElseThrow().getUsedAt() == null,
                "a token whose email genuinely dispatched must never be retroactively invalidated by give-up");
    }

    @Test
    void claimResetEmailDispatch_returns1thenZero() {
        PasswordResetToken token = persisted("hash-f", now.plus(30, ChronoUnit.MINUTES));

        assertEquals(1, underTest.claimResetEmailDispatch(token.getId(), now));
        assertEquals(0, underTest.claimResetEmailDispatch(token.getId(), now));
    }

    @Test
    void dispatchRecoveryFinder_excludesDispatchedUsedAndExpiredRows() {
        PasswordResetToken candidate = persisted("hash-g", now.plus(30, ChronoUnit.MINUTES));
        PasswordResetToken dispatched = persisted("hash-h", now.plus(30, ChronoUnit.MINUTES));
        underTest.claimResetEmailDispatch(dispatched.getId(), now);
        PasswordResetToken used = persisted("hash-i", now.plus(30, ChronoUnit.MINUTES));
        underTest.consume("hash-i", now);
        persisted("hash-j", now.minus(1, ChronoUnit.MINUTES));

        List<PasswordResetToken> found =
                underTest.findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore(now,
                        now.plusSeconds(1), Limit.of(100));

        assertEquals(1, found.size());
        assertEquals(candidate.getId(), found.get(0).getId());
    }

    @Test
    void deleteExpiredBefore_removesOnlyRowsPastTheThreshold() {
        persisted("hash-k", now.minus(1, ChronoUnit.MINUTES));
        persisted("hash-l", now.plus(30, ChronoUnit.MINUTES));

        int deleted = underTest.deleteExpiredBefore(now);

        assertEquals(1, deleted);
        assertFalse(underTest.findByTokenHash("hash-k").isPresent());
        assertTrue(underTest.findByTokenHash("hash-l").isPresent());
    }
}
