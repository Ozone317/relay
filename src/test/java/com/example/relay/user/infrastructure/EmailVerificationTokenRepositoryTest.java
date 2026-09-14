package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class EmailVerificationTokenRepositoryTest implements SharedPostgresContainer {

    @Autowired
    private EmailVerificationTokenRepository underTest;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        underTest.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void findByTokenHash_findsAPersistedToken() {
        User user = userRepository.saveAndFlush(new User("token-repo@example.com", "hash"));
        Instant now = Instant.now();
        EmailVerificationToken token =
                underTest.saveAndFlush(new EmailVerificationToken(user, "hashed-value", now.plusSeconds(3600), now));

        assertEquals(token.getId(), underTest.findByTokenHash("hashed-value").orElseThrow().getId());
    }

    @Test
    void invalidateAllForUser_marksEveryUnusedTokenUsed() {
        User user = userRepository.saveAndFlush(new User("invalidate@example.com", "hash"));
        Instant now = Instant.now();
        EmailVerificationToken token =
                underTest.saveAndFlush(new EmailVerificationToken(user, "hash-a", now.plusSeconds(3600), now));

        int updated = underTest.invalidateAllForUser(user.getId(), now);

        assertEquals(1, updated);
        assertTrue(underTest.findByTokenHash("hash-a").orElseThrow().getUsedAt() != null);
    }

    @Test
    void consume_marksTokenUsed_andReturnsZero_onASecondAttempt() {
        User user = userRepository.saveAndFlush(new User("consume@example.com", "hash"));
        Instant now = Instant.now();
        underTest.saveAndFlush(new EmailVerificationToken(user, "hash-b", now.plusSeconds(3600), now));

        int firstAttempt = underTest.consume("hash-b", now);
        int secondAttempt = underTest.consume("hash-b", now);

        assertEquals(1, firstAttempt);
        assertEquals(0, secondAttempt);
    }

    @Test
    void consume_returnsZero_forAnExpiredToken() {
        User user = userRepository.saveAndFlush(new User("expired@example.com", "hash"));
        Instant now = Instant.now();
        underTest.saveAndFlush(new EmailVerificationToken(user, "hash-c", now.minusSeconds(1), now.minusSeconds(3600)));

        assertEquals(0, underTest.consume("hash-c", now));
    }
}
