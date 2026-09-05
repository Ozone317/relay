package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.user.domain.RefreshToken;
import com.example.relay.user.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
public class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository underTest;

    @Autowired
    private UserRepository userRepository;

    private User user;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        user = userRepository.save(new User("daksh@example.com", "hash"));
    }

    private RefreshToken persisted(String tokenHash) {
        return underTest.save(new RefreshToken(user, tokenHash, now.plus(30, ChronoUnit.DAYS), now));
    }

    @Test
    void findByTokenHash_returnsTheMatchingRow() {
        persisted("hash-a");

        Optional<RefreshToken> found = underTest.findByTokenHash("hash-a");

        assertTrue(found.isPresent());
        assertEquals("hash-a", found.get().getTokenHash());
    }

    @Test
    void findByTokenHash_returnsEmpty_whenNothingMatches() {
        assertTrue(underTest.findByTokenHash("no-such-hash").isEmpty());
    }

    @Test
    void revokeByTokenHash_returns1thenZero_soADoubleClickCannotOverwriteTheFirstTimestamp() {
        persisted("hash-b");

        assertEquals(1, underTest.revokeByTokenHash("hash-b", now));
        assertEquals(0, underTest.revokeByTokenHash("hash-b", now.plusSeconds(60)));
    }

    @Test
    void revokeAllByUserId_revokesEveryUnrevokedRowForThatUser() {
        persisted("hash-c");
        persisted("hash-d");

        assertEquals(2, underTest.revokeAllByUserId(user.getId(), now));
        assertEquals(0, underTest.revokeAllByUserId(user.getId(), now));
    }

    @Test
    void slide_movesExpiresAtForward() {
        RefreshToken token = persisted("hash-e");
        Instant later = now.plus(31, ChronoUnit.DAYS);

        assertEquals(1, underTest.slide(token.getId(), later));

        assertEquals(later, underTest.findByTokenHash("hash-e").orElseThrow().getExpiresAt());
    }

    @Test
    void slide_doesNothing_onARevokedRow() {
        RefreshToken token = persisted("hash-f");
        underTest.revokeByTokenHash("hash-f", now);

        assertEquals(0, underTest.slide(token.getId(), now.plus(31, ChronoUnit.DAYS)));
    }
}
