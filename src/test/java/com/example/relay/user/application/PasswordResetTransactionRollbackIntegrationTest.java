package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class PasswordResetTransactionRollbackIntegrationTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetTokenService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private com.example.relay.common.security.SecureTokenGenerator secureTokenGenerator;

    // Every other table that can hold a row transitively referencing users(id), cleaned up here in
    // FK-safe (children-first) order before userRepository.deleteAll() below - not because this
    // test's own scenario touches any of them, but because this shared Postgres container is reused
    // across every @SpringBootTest class in the suite (see SharedPostgresContainer's javadoc), and a
    // blanket "delete every user" only succeeds once nothing anywhere still references one.
    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private User user;
    private String rawToken;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        subscriptionRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("rollback-test@example.com", "original-hash"));
        // A real raw token, so the consume UPDATE genuinely matches and updates this row before the
        // encoder throws below - a consume that matched zero rows would throw
        // InvalidOrExpiredResetTokenException before ever reaching the encoder, proving nothing
        // about rollback.
        rawToken = secureTokenGenerator.generateRawToken();
        Instant now = Instant.now();
        passwordResetTokenRepository
                .save(new PasswordResetToken(user, secureTokenGenerator.hash(rawToken), now.plusSeconds(1800), now));
    }

    /**
     * password_reset_tokens (Task 4) postdates several existing @SpringBootTest classes' own cleanup routines, which
     * know nothing about it and so cannot delete a user this test leaves behind - this class's own user/token rows are
     * cleaned up here so no later-running test class sharing this container (see SharedPostgresContainer's javadoc)
     * trips over them.
     */
    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void aFailureAfterConsumeButBeforeCommit_rollsBackThePasswordAndTheTokenConsumption() {
        // Force a failure strictly after the consume UPDATE has run but before the transaction
        // commits, by making the password encoder throw.
        when(passwordEncoder.encode(anyString())).thenThrow(new RuntimeException("simulated encoder failure"));

        assertThrows(RuntimeException.class,
                () -> underTest.consumeAndResetPassword(rawToken, "newPassword123", Instant.now()));

        User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
        assertEquals("original-hash", reloadedUser.getPasswordHash());

        PasswordResetToken reloadedToken =
                passwordResetTokenRepository.findByTokenHash(secureTokenGenerator.hash(rawToken)).orElseThrow();
        assertNull(reloadedToken.getUsedAt(), "the consume UPDATE must have rolled back with everything else");

        assertEquals(0, refreshTokenRepository.count(), "no session should have been revoked either");
    }
}
