package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.PasswordResetTokenService;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PasswordResetConcurrentRequestPostgresTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetTokenService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

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

    private User user;

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
        user = userRepository.save(new User("concurrent-request@example.com", "hash"));
    }

    /**
     * This test deliberately leaves 2 unused, undispatched, unexpired tokens behind (that is the whole point of the
     * race it proves) - unlike this class's own @BeforeEach, which only guards against rows left by OTHER classes,
     * these particular rows must not survive to be seen by a later class's own (unscoped) query, e.g.
     * PasswordResetTokenRepositoryTest's dispatch-recovery finder scans the whole table with no per-test-class filter.
     */
    @AfterEach
    void tearDown() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void twoConcurrentIssueCalls_bothSucceed_eachCreatingItsOwnRow() throws InterruptedException {
        // "Exactly one active token" is a best-effort property, not DB-constraint-enforced (design
        // spec docs/superpowers/specs/2026-09-12-password-reset-design.md:493-496, "State
        // explicitly in the test whether 'exactly one active token' is a guarantee or a best-effort
        // property (it's the latter per Section 4 above - not DB-constraint-enforced)").
        //
        // issue()'s invalidate-then-insert is not atomic against a concurrent issue() for the same
        // user: invalidateAllForUser() runs at the START of its own transaction, before its own
        // insert, so with no pre-existing token, two genuinely concurrent issue() calls each
        // invalidate zero rows (there is nothing yet to invalidate - the other call's insert isn't
        // visible before it commits) and then both insert. Verified empirically (deterministic
        // across repeated runs, not flaky): both rows are legitimately left unused after a genuine
        // race. This test proves both calls succeed and each creates its own row; it deliberately
        // does NOT assert an unused-token upper bound, since none is guaranteed by the spec or the
        // implementation.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Runnable issueOnce = () -> {
            readyLatch.countDown();
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            underTest.issue(user, Instant.now());
        };

        executor.submit(issueOnce);
        executor.submit(issueOnce);
        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertTrue(finished, "both issue() calls must finish within the timeout");

        List<PasswordResetToken> allTokens = passwordResetTokenRepository.findAll();
        assertEquals(2, allTokens.size(), "both requests succeed and both create a row");
    }
}
