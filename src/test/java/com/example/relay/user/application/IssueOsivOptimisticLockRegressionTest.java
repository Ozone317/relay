package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression test for a bug introduced by commit f048ec3 (which added {@code userRepository.lockForUpdate} as the
 * first statement of both {@code issue()} methods to close an AB-BA deadlock - see
 * EmailVerificationTokenService.issue's and PasswordResetTokenService's private issue()'s javadoc): under this
 * project's default {@code spring.jpa.open-in-view=true}, {@code PasswordResetService.requestReset}/
 * {@code issueAndDispatch} and {@code EmailVerificationService.resend} both load their {@code User} via
 * {@code findByEmail} OUTSIDE any transaction, so it stays MANAGED in the request-bound persistence context. When
 * {@code issue()}'s own transaction then calls {@code lockForUpdate} on that SAME entity instance, it is a
 * lock-mode UPGRADE on an already-cached entity (Hibernate re-validates its cached {@code version} against the row
 * it just locked), not a fresh load - so a concurrent write that bumps the row's version in between throws
 * {@code ObjectOptimisticLockingFailureException}, uncaught, surfacing as a 500 on two unauthenticated,
 * security-sensitive endpoints.
 *
 * <p>
 * This test simulates the OSIV-bound persistence context the same way {@code OpenEntityManagerInViewInterceptor}
 * does - manually opening an {@link jakarta.persistence.EntityManager} and binding it to
 * {@link TransactionSynchronizationManager} for the current thread BEFORE calling {@code findByEmail} - because a
 * plain {@code @SpringBootTest} method-call test (no real HTTP request) gets a fresh transaction-scoped
 * EntityManager per call and can never exhibit this bug; only {@code DeliveryReplayHttpIntegrationTest} in this
 * codebase reproduces OSIV via a real embedded HTTP request, but that round-trip cost isn't needed here since the
 * failure only depends on the User staying managed across two calls on the same thread, which manual binding
 * reproduces exactly. The concurrent write is a genuinely separate, already-committed transaction, executed on a
 * separate thread (its own connection, its own Hibernate Session) so the version bump is real and visible - not a
 * nested transaction on the same thread, which would just join the OSIV session and never actually commit
 * independently.
 */
@Tag("integration")
@SpringBootTest
class IssueOsivOptimisticLockRegressionTest implements SharedPostgresContainer {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PasswordResetTokenService passwordResetTokenService;

    @Autowired
    private EmailVerificationTokenService emailVerificationTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private User user;
    private EntityManager boundOsivEntityManager;

    @AfterEach
    void cleanUp() {
        // Unbind and close the manually-opened OSIV-simulating EntityManager BEFORE anything else -
        // otherwise it stays bound to this test thread (TransactionSynchronizationManager is a
        // ThreadLocal, and surefire/JUnit typically reuses the same thread across test methods in a
        // class), and the next test's own transactional repository calls would either reuse this
        // stale EntityManager or blow up on "already a value bound for that key".
        if (boundOsivEntityManager != null) {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            boundOsivEntityManager.close();
            boundOsivEntityManager = null;
        }
        if (user == null) {
            return;
        }
        passwordResetTokenRepository.findAll().stream().filter(t -> t.getUser().getId().equals(user.getId()))
                .forEach(passwordResetTokenRepository::delete);
        emailVerificationTokenRepository.findAll().stream().filter(t -> t.getUser().getId().equals(user.getId()))
                .forEach(emailVerificationTokenRepository::delete);
        userRepository.deleteById(user.getId());
    }

    /**
     * Reproduces the regression against {@code PasswordResetTokenService}'s private {@code issue(User, Instant,
     * Instant)}, called (as production code does) with a {@code User} that is managed but STALE relative to the row
     * a concurrent write just committed. Before the fix (detach before lockForUpdate): throws
     * ObjectOptimisticLockingFailureException. After the fix: passes.
     */
    @Test
    void passwordResetIssue_withStaleOsivManagedUser_doesNotThrowOptimisticLockingFailure() throws Exception {
        user = userRepository.saveAndFlush(new User("osiv-reset-" + UUID.randomUUID() + "@example.com", "hash"));
        User osivManagedUser = simulateOsivFindByEmail(user.getEmail());

        bumpVersionOnASeparateCommittedTransaction(user.getId());

        assertDoesNotThrow(() -> passwordResetTokenService.issue(osivManagedUser, Instant.now()));
    }

    /**
     * Same regression, against {@code EmailVerificationTokenService.issue}, mirroring how
     * {@code EmailVerificationService.resend} calls it with a {@code findByEmail}-loaded User.
     */
    @Test
    void emailVerificationIssue_withStaleOsivManagedUser_doesNotThrowOptimisticLockingFailure() throws Exception {
        user = userRepository.saveAndFlush(new User("osiv-verify-" + UUID.randomUUID() + "@example.com", "hash"));
        User osivManagedUser = simulateOsivFindByEmail(user.getEmail());

        bumpVersionOnASeparateCommittedTransaction(user.getId());

        assertDoesNotThrow(() -> emailVerificationTokenService.issue(osivManagedUser, Instant.now()));
    }

    /**
     * Binds a manually-opened EntityManager to the current thread exactly like
     * {@code OpenEntityManagerInViewInterceptor} does for the lifetime of an HTTP request, then performs the
     * unlocked {@code findByEmail} lookup through it, leaving the returned User MANAGED in that bound context for
     * the rest of this thread's execution - reproducing the real request-bound OSIV persistence context without
     * needing a real embedded HTTP round-trip.
     */
    private User simulateOsivFindByEmail(String email) {
        boundOsivEntityManager = entityManagerFactory.createEntityManager();
        EntityManagerHolder holder = new EntityManagerHolder(boundOsivEntityManager);
        TransactionSynchronizationManager.bindResource(entityManagerFactory, holder);
        return userRepository.findByEmail(email).orElseThrow();
    }

    /**
     * Bumps the row's {@code @Version} via a genuinely separate, already-committed transaction on a separate
     * thread - its own connection, its own Hibernate Session - so it is indistinguishable from an unrelated
     * concurrent write by another request, not a nested transaction on this thread (which would just join the
     * bound OSIV session and never independently commit).
     */
    private void bumpVersionOnASeparateCommittedTransaction(UUID userId) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> userRepository.setPasswordOnly(userId, "concurrently-changed-hash")));
            future.get();
        } finally {
            executor.shutdown();
        }
    }
}
