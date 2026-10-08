package com.example.relay.user.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.example.relay.common.scheduling.ScheduledCallbackRunner;
import com.example.relay.email.EmailDispatchMessage;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.support.background.EnableTestBackgroundExecution;
import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.application.PasswordResetTokenService;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.sql.Timestamp;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("integration")
@SpringBootTest
@EnableTestBackgroundExecution({})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"relay.password-reset.email-recovery.interval=1s",
        "relay.password-reset.email-recovery.grace=1s",
        "relay.password-reset.email-recovery.max-recovery-window=3h",
        "JWT_SECRET=test-only-jwt-secret-for-recovery-tests-32bytes"})
class PasswordResetMaintenanceConcurrencyPostgresTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetEmailRecoverySweeper recoverySweeper;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordResetTokenCleanupProperties cleanupProperties;

    @MockitoSpyBean
    private PasswordResetTokenService passwordResetTokenService;

    @Autowired
    private PasswordResetEmailRecoveryProperties recoveryProperties;

    @Autowired
    private ScheduledCallbackRunner scheduledCallbackRunner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private EmailDispatchPublisher emailDispatchPublisher;

    private final ConcurrentLinkedQueue<EmailDispatchMessage> publications = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<IssuanceBackendObservation> issuanceBackendObservation = new ThreadLocal<>();

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        publications.clear();
        doAnswer(invocation -> {
                    publications.add(invocation.getArgument(0));
                    return null;
                })
                .when(emailDispatchPublisher)
                .publish(any(EmailDispatchMessage.class));
        doAnswer(invocation -> {
                    captureIssuanceBackend();
                    return invocation.callRealMethod();
                })
                .when(passwordResetTokenService)
                .issue(any(User.class), any(Instant.class));
        doAnswer(invocation -> {
                    captureIssuanceBackend();
                    return invocation.callRealMethod();
                })
                .when(passwordResetTokenService)
                .recoverCandidate(any(UUID.class), any(UUID.class), any(Duration.class), any(Duration.class));
    }

    @Test
    void concurrentRecoveryCallersPreserveOneUsableTokenAndPersistedRequestTime() throws Exception {
        User user = userRepository.save(new User("maintenance-race-" + UUID.randomUUID() + "@example.com", "hash"));
        String originalPasswordHash = user.getPasswordHash();
        Instant firstRequestedAt = Instant.ofEpochSecond(
                Instant.now().minus(Duration.ofMinutes(5)).getEpochSecond(), 123_456_789);
        Instant staleUpdatedAt = Instant.now().minusSeconds(60);
        PasswordResetToken stale = tokenRepository.save(new PasswordResetToken(user, "stale-" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt, firstRequestedAt));
        Instant persistedFirstRequestedAt = persistedFirstRequestedAt(stale.getId());
        jdbcTemplate.update("UPDATE password_reset_tokens SET updated_at = ? WHERE id = ?",
                Timestamp.from(staleUpdatedAt), stale.getId());
        Instant expiredAt = Instant.now().minus(cleanupProperties.getRetention()).minusSeconds(60);
        PasswordResetToken expired = tokenRepository.save(new PasswordResetToken(user,
                "expired-" + UUID.randomUUID(), expiredAt, expiredAt));

        CountDownLatch allCallersAtStateBoundary = new CountDownLatch(3);
        CountDownLatch releaseOperations = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            PasswordResetEmailRecoverySweeper firstSweeper = gatedRecoverySweeper(
                    allCallersAtStateBoundary, releaseOperations);
            PasswordResetEmailRecoverySweeper secondSweeper = gatedRecoverySweeper(
                    allCallersAtStateBoundary, releaseOperations);
            PasswordResetTokenCleanupTask cleanup = gatedCleanupTask(allCallersAtStateBoundary, releaseOperations);
            Future<?> firstRecovery = executor.submit(firstSweeper::sweep);
            Future<?> secondRecovery = executor.submit(secondSweeper::sweep);
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            Future<?> concurrentCleanup = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> cleanup.cleanup()));
            assertTrue(allCallersAtStateBoundary.await(10, TimeUnit.SECONDS),
                    "both recoveries must select the stale token while cleanup reaches its delete");
            releaseOperations.countDown();
            firstRecovery.get(15, TimeUnit.SECONDS);
            secondRecovery.get(15, TimeUnit.SECONDS);
            concurrentCleanup.get(15, TimeUnit.SECONDS);
        } finally {
            releaseOperations.countDown();
            executor.shutdownNow();
        }

        PasswordResetToken reloadedStale = tokenRepository.findById(stale.getId()).orElseThrow();
        assertThat(reloadedStale.getUsedAt()).isNotNull();
        assertThat(reloadedStale.getResetEmailDispatchedAt()).isNull();
        assertThat(tokenRepository.findById(expired.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE expires_at < ?", Integer.class,
                Timestamp.from(Instant.now().minus(cleanupProperties.getRetention())))).isZero();

        List<PasswordResetToken> rowsForUser = tokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .toList();
        List<PasswordResetToken> usable = rowsForUser.stream().filter(token -> token.getUsedAt() == null).toList();
        List<PasswordResetToken> invalidated = rowsForUser.stream().filter(token -> token.getUsedAt() != null).toList();
        assertThat(rowsForUser).as("only the candidate-owning recovery persists a successor").hasSize(2);
        assertThat(invalidated).as("only the exact observed candidate is retired").hasSize(1);
        assertThat(usable).as("the candidate-owning recovery leaves one usable credential").hasSize(1);
        assertThat(usable.getFirst().getFirstRequestedAt()).isEqualTo(persistedFirstRequestedAt);
        assertThat(publications).hasSize(1);
        assertThat(publications.peek().idempotencyKey()).isEqualTo(usable.getFirst().getId().toString());
        assertThat(publications).allMatch(publication -> rowsForUser.stream()
                .anyMatch(token -> token.getId().toString().equals(publication.idempotencyKey())));

        User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloadedUser.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(reloadedUser.isEmailVerified()).isFalse();
    }

    @Test
    void ordinaryResetRacingRecoveryLeavesOnlyTheSerializedWinnerUsable() throws Exception {
        User user = userRepository.save(new User("ordinary-recovery-race-" + UUID.randomUUID() + "@example.com",
                "unchanged-hash"));
        Instant firstRequestedAt = Instant.now().minus(Duration.ofMinutes(10));
        Instant staleUpdatedAt = Instant.now().minusSeconds(60);
        PasswordResetToken stale = tokenRepository.save(new PasswordResetToken(user, "stale-" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt, firstRequestedAt));
        Instant persistedFirstRequestedAt = persistedFirstRequestedAt(stale.getId());
        jdbcTemplate.update("UPDATE password_reset_tokens SET updated_at = ? WHERE id = ?",
                Timestamp.from(staleUpdatedAt), stale.getId());

        CountDownLatch blockerLocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch recoverySelectedCandidate = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        AtomicInteger recoveryBackendPid = new AtomicInteger();
        AtomicInteger ordinaryBackendPid = new AtomicInteger();
        CountDownLatch recoveryEnteredIssuance = new CountDownLatch(1);
        CountDownLatch ordinaryEnteredIssuance = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        AtomicInteger blockerPid = new AtomicInteger();
        Future<?> blocker = null;
        Future<?> recovery = null;
        Future<?> ordinaryRequest = null;
        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            blocker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                blockerPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, user.getId());
                blockerLocked.countDown();
                awaitRelease(releaseBlocker);
            }));
            assertTrue(blockerLocked.await(10, TimeUnit.SECONDS), "test transaction must hold the user row lock");

            PasswordResetEmailRecoverySweeper selectedRecovery = gatedRecoverySweeper(
                    recoverySelectedCandidate, releaseRecovery);
            recovery = withIssuanceObservation(executor, recoveryBackendPid, recoveryEnteredIssuance,
                    selectedRecovery::sweep);
            assertTrue(recoverySelectedCandidate.await(10, TimeUnit.SECONDS),
                    "recovery must have selected the stale candidate before issuance starts");
            releaseRecovery.countDown();
            ordinaryRequest = withIssuanceObservation(executor, ordinaryBackendPid, ordinaryEnteredIssuance,
                    () -> passwordResetService.issueAndDispatch(user));

            assertTrue(recoveryEnteredIssuance.await(10, TimeUnit.SECONDS),
                    "recovery must enter its real transactional issuance method");
            assertTrue(ordinaryEnteredIssuance.await(10, TimeUnit.SECONDS),
                    "ordinary reset must enter its real transactional issuance method");
            Set<Integer> intendedIssuancePids = Set.of(recoveryBackendPid.get(), ordinaryBackendPid.get());
            assertThat(intendedIssuancePids).as("the issuance paths must use separate database connections")
                    .hasSize(2).doesNotContain(blockerPid.get());
            assertBothExactIssuancePidsBlockedOnUserLock(
                    intendedIssuancePids, blockerPid.get());

            // Keep the independent transaction's user-row lock until both real issuance transactions are queued.
            releaseBlocker.countDown();
            blocker.get(15, TimeUnit.SECONDS);
            recovery.get(15, TimeUnit.SECONDS);
            ordinaryRequest.get(15, TimeUnit.SECONDS);
        } finally {
            releaseRecovery.countDown();
            releaseBlocker.countDown();
            executor.shutdownNow();
        }

        PasswordResetToken reloadedStale = tokenRepository.findById(stale.getId()).orElseThrow();
        assertThat(reloadedStale.getUsedAt()).isNotNull();
        List<PasswordResetToken> rowsForUser = tokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .toList();
        List<PasswordResetToken> successors = rowsForUser.stream()
                .filter(token -> !token.getId().equals(stale.getId()))
                .toList();
        List<PasswordResetToken> usable = rowsForUser.stream().filter(token -> token.getUsedAt() == null).toList();
        List<PasswordResetToken> invalidated = rowsForUser.stream().filter(token -> token.getUsedAt() != null).toList();
        assertThat(rowsForUser).as("stale row plus both successful issuance successors").hasSize(3);
        assertThat(successors).as("ordinary issuance and recovery each persist a distinct successor").hasSize(2);
        assertThat(reloadedStale.getUsedAt()).as("the selected stale candidate is superseded").isNotNull();
        assertThat(usable).as("the user-row lock serializes recovery against ordinary issuance").hasSize(1);
        assertThat(invalidated).as("stale and superseded successor are both invalidated").hasSize(2);
        assertThat(publications).hasSize(2);
        Set<String> publicationKeys = publications.stream().map(EmailDispatchMessage::idempotencyKey)
                .collect(Collectors.toSet());
        assertThat(publicationKeys).as("each successful issuance publishes its own successor id").hasSize(2)
                .containsExactlyInAnyOrderElementsOf(
                        successors.stream().map(token -> token.getId().toString()).toList());
        assertThat(usable.getFirst().getFirstRequestedAt()).satisfies(requestedAt -> {
            if (requestedAt.equals(persistedFirstRequestedAt)) {
                return;
            }
            assertThat(requestedAt).isAfter(persistedFirstRequestedAt);
        });

        User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloadedUser.getPasswordHash()).isEqualTo("unchanged-hash");
        assertThat(reloadedUser.isEmailVerified()).isFalse();
    }

    private Future<?> withIssuanceObservation(ExecutorService executor, AtomicInteger backendPid,
            CountDownLatch enteredIssuance, Runnable operation) {
        return executor.submit(() -> {
            issuanceBackendObservation.set(new IssuanceBackendObservation(backendPid, enteredIssuance));
            try {
                operation.run();
            } finally {
                issuanceBackendObservation.remove();
            }
        });
    }

    private void captureIssuanceBackend() {
        IssuanceBackendObservation observation = issuanceBackendObservation.get();
        if (observation == null) {
            return;
        }
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "issuance backend PID must be captured inside its real transaction");
        observation.backendPid().set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
        observation.enteredIssuance().countDown();
    }

    private void assertBothExactIssuancePidsBlockedOnUserLock(Set<Integer> expectedIssuancePids,
            int blockerBackendPid) {
        List<Integer> expectedPids = List.copyOf(expectedIssuancePids);
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10)).untilAsserted(() -> {
            List<Map<String, Object>> activities = jdbcTemplate.queryForList("""
                    SELECT pid, query, wait_event_type, cardinality(pg_blocking_pids(pid)) AS blocker_count,
                           ? = ANY(pg_blocking_pids(pid)) AS blocked_by_test_holder
                    FROM pg_stat_activity
                    WHERE pid IN (?, ?)
                    """, blockerBackendPid, expectedPids.get(0), expectedPids.get(1));
            Set<Integer> observedPids = activities.stream()
                    .map(activity -> ((Number) activity.get("pid")).intValue())
                    .collect(Collectors.toSet());
            assertThat(observedPids).as("pg_stat_activity must contain these exact issuance backends")
                    .containsExactlyInAnyOrderElementsOf(expectedIssuancePids);
            assertThat(activities).hasSize(2).allSatisfy(activity -> {
                assertThat(activity.get("wait_event_type")).isEqualTo("Lock");
                String query = activity.get("query").toString().toLowerCase();
                assertThat(query).contains("from users");
                assertThat(query.contains("for update") || query.contains("for no key update")).isTrue();
                assertThat(((Number) activity.get("blocker_count")).intValue()).isPositive();
            });
            assertThat(activities).anyMatch(activity -> Boolean.TRUE.equals(activity.get("blocked_by_test_holder")));
        });
    }

    private Instant persistedFirstRequestedAt(UUID tokenId) {
        return jdbcTemplate.queryForObject("SELECT first_requested_at FROM password_reset_tokens WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(), tokenId);
    }

    private PasswordResetEmailRecoverySweeper gatedRecoverySweeper(CountDownLatch arrived, CountDownLatch release) {
        PasswordResetTokenRepository gatedRepository = repositoryGatedAt(
                "findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore", arrived,
                release);
        return new PasswordResetEmailRecoverySweeper(gatedRepository, passwordResetService, recoveryProperties,
                scheduledCallbackRunner);
    }

    private PasswordResetTokenCleanupTask gatedCleanupTask(CountDownLatch arrived, CountDownLatch release) {
        PasswordResetTokenRepository gatedRepository = repositoryGatedAt("deleteExpiredBefore", arrived, release);
        return new PasswordResetTokenCleanupTask(gatedRepository, cleanupProperties, scheduledCallbackRunner,
                transactionManager);
    }

    private PasswordResetTokenRepository repositoryGatedAt(String methodName, CountDownLatch arrived,
            CountDownLatch release) {
        return (PasswordResetTokenRepository) Proxy.newProxyInstance(
                PasswordResetTokenRepository.class.getClassLoader(),
                new Class<?>[] {PasswordResetTokenRepository.class}, (proxy, method, arguments) -> {
                    try {
                        if (method.getName().equals(methodName) && methodName.equals("deleteExpiredBefore")) {
                            arrived.countDown();
                            awaitRelease(release);
                        }
                        Object result = method.invoke(tokenRepository, arguments);
                        if (method.getName().equals(methodName) && !methodName.equals("deleteExpiredBefore")) {
                            arrived.countDown();
                            awaitRelease(release);
                        }
                        return result;
                    } catch (InvocationTargetException targetException) {
                        throw targetException.getCause();
                    }
                });
    }

    @Test
    void concurrentCleanupCallersDeleteExpiredRowsWithoutASecondEligiblePass() throws Exception {
        User user = userRepository.save(new User("cleanup-race-" + UUID.randomUUID() + "@example.com", "hash"));
        Instant expiredAt = Instant.now().minus(cleanupProperties.getRetention()).minusSeconds(60);
        for (int index = 0; index < 4; index++) {
            tokenRepository.save(new PasswordResetToken(user, "expired-" + UUID.randomUUID(), expiredAt, expiredAt));
        }

        CountDownLatch bothCleanupCallersAtDelete = new CountDownLatch(2);
        CountDownLatch releaseCleanupCallers = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PasswordResetTokenCleanupTask firstTask = gatedCleanupTask(
                    bothCleanupCallersAtDelete, releaseCleanupCallers);
            PasswordResetTokenCleanupTask secondTask = gatedCleanupTask(
                    bothCleanupCallersAtDelete, releaseCleanupCallers);
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            Future<?> firstCleanup = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> firstTask.cleanup()));
            Future<?> secondCleanup = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> secondTask.cleanup()));
            assertTrue(bothCleanupCallersAtDelete.await(10, TimeUnit.SECONDS),
                    "both cleanup transactions must reach the delete before either executes it");
            releaseCleanupCallers.countDown();
            firstCleanup.get(15, TimeUnit.SECONDS);
            secondCleanup.get(15, TimeUnit.SECONDS);
        } finally {
            releaseCleanupCallers.countDown();
            executor.shutdownNow();
        }

        assertThat(tokenRepository.findAll()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE expires_at < ?", Integer.class,
                Timestamp.from(Instant.now().minus(cleanupProperties.getRetention())))).isZero();
        User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloadedUser.getPasswordHash()).isEqualTo("hash");
        assertThat(reloadedUser.isEmailVerified()).isFalse();
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent maintenance callers");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("maintenance test gate was interrupted", interrupted);
        }
    }

    private record IssuanceBackendObservation(AtomicInteger backendPid, CountDownLatch enteredIssuance) {}
}
