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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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

    private static final long STAGE_TIMEOUT_SECONDS = 10;
    private static final long FUTURE_TIMEOUT_SECONDS = 15;
    private static final long GATE_HOLDER_TIMEOUT_SECONDS = 90;
    private static final long CLEANUP_TIMEOUT_SECONDS = 30;

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
    private final List<ExecutorService> workerExecutors = new CopyOnWriteArrayList<>();
    private final List<Future<?>> workerFutures = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> releaseGates = new CopyOnWriteArrayList<>();
    private final Set<UUID> fixtureUserIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fixtureTokenIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
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
        }).when(passwordResetTokenService).recoverCandidate(any(UUID.class), any(UUID.class), any(Duration.class),
                any(Duration.class));
    }

    @AfterEach
    void cleanUpFixture() {
        releaseGates.forEach(CountDownLatch::countDown);
        workerFutures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        workerExecutors.forEach(ExecutorService::shutdownNow);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLEANUP_TIMEOUT_SECONDS);
        try {
            for (ExecutorService executor : workerExecutors) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0 && executor.awaitTermination(remaining, TimeUnit.NANOSECONDS),
                        "all maintenance workers must terminate before fixture cleanup");
            }
            for (Future<?> future : workerFutures) {
                if (!future.isCancelled()) {
                    long remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0, "future joins must fit the cleanup deadline");
                    future.get(remaining, TimeUnit.NANOSECONDS);
                }
            }
            Set<UUID> scopedUsers = Set.copyOf(fixtureUserIds);
            Set<UUID> scopedTokens = ConcurrentHashMap.newKeySet();
            scopedTokens.addAll(fixtureTokenIds);
            tokenRepository.findAll().stream()
                    .filter(token -> scopedUsers.contains(token.getUser().getId()))
                    .map(PasswordResetToken::getId)
                    .forEach(scopedTokens::add);
            tokenRepository.deleteAllById(scopedTokens);
            userRepository.deleteAllById(scopedUsers);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted during maintenance worker cleanup", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("maintenance workers must finish before fixture cleanup", failure);
        } finally {
            workerExecutors.clear();
            workerFutures.clear();
            releaseGates.clear();
            fixtureUserIds.clear();
            fixtureTokenIds.clear();
        }
    }

    private User createUser(String email, String passwordHash) {
        User user = userRepository.save(new User(email, passwordHash));
        fixtureUserIds.add(user.getId());
        return user;
    }

    private PasswordResetToken saveToken(PasswordResetToken token) {
        PasswordResetToken saved = tokenRepository.save(token);
        fixtureTokenIds.add(saved.getId());
        return saved;
    }

    private ExecutorService newExecutor(int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        workerExecutors.add(executor);
        return executor;
    }

    private Future<?> submitTask(ExecutorService executor, Runnable task) {
        Future<?> future = executor.submit(task);
        workerFutures.add(future);
        return future;
    }

    private <T> Future<T> submitTask(ExecutorService executor, java.util.concurrent.Callable<T> task) {
        Future<T> future = executor.submit(task);
        workerFutures.add(future);
        return future;
    }

    private CountDownLatch trackedLatch(int count) {
        CountDownLatch latch = new CountDownLatch(count);
        releaseGates.add(latch);
        return latch;
    }

    @Test
    void concurrentRecoveryCallersPreserveOneUsableTokenAndPersistedRequestTime() throws Exception {
        User user = createUser("maintenance-race-" + UUID.randomUUID() + "@example.com", "hash");
        String originalPasswordHash = user.getPasswordHash();
        Instant firstRequestedAt = Instant.ofEpochSecond(
                Instant.now().minus(Duration.ofMinutes(5)).getEpochSecond(), 123_456_789);
        Instant staleUpdatedAt = Instant.now().minusSeconds(60);
        PasswordResetToken stale = saveToken(new PasswordResetToken(user, "stale-" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt, firstRequestedAt));
        Instant persistedFirstRequestedAt = persistedFirstRequestedAt(stale.getId());
        jdbcTemplate.update("UPDATE password_reset_tokens SET updated_at = ? WHERE id = ?",
                Timestamp.from(staleUpdatedAt), stale.getId());
        Instant expiredAt = Instant.now().minus(cleanupProperties.getRetention()).minusSeconds(60);
        PasswordResetToken expired = saveToken(new PasswordResetToken(user,
                "expired-" + UUID.randomUUID(), expiredAt, expiredAt));

        CountDownLatch allCallersAtStateBoundary = trackedLatch(3);
        CountDownLatch releaseOperations = trackedLatch(1);
        AtomicReference<List<UUID>> firstObservedCandidates = new AtomicReference<>();
        AtomicReference<List<UUID>> secondObservedCandidates = new AtomicReference<>();
        ExecutorService executor = newExecutor(3);
        try {
            PasswordResetEmailRecoverySweeper firstSweeper = gatedRecoverySweeper(
                    allCallersAtStateBoundary, releaseOperations,
                    candidates -> firstObservedCandidates.set(candidates.stream().map(PasswordResetToken::getId).toList()));
            PasswordResetEmailRecoverySweeper secondSweeper = gatedRecoverySweeper(
                    allCallersAtStateBoundary, releaseOperations,
                    candidates -> secondObservedCandidates.set(candidates.stream().map(PasswordResetToken::getId).toList()));
            PasswordResetTokenCleanupTask cleanup = gatedCleanupTask(allCallersAtStateBoundary, releaseOperations);
            Future<?> firstRecovery = submitTask(executor, firstSweeper::sweep);
            Future<?> secondRecovery = submitTask(executor, secondSweeper::sweep);
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            Future<?> concurrentCleanup = submitTask(executor, () -> transactionTemplate.executeWithoutResult(
                    status -> cleanup.cleanup()));
            assertTrue(allCallersAtStateBoundary.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "both recoveries must select the stale token while cleanup reaches its delete");
            assertThat(firstObservedCandidates.get()).containsExactly(stale.getId());
            assertThat(secondObservedCandidates.get()).containsExactly(stale.getId());
            releaseOperations.countDown();
            firstRecovery.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondRecovery.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            concurrentCleanup.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseOperations.countDown();
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
    void recoveryLocksUserBeforeTokenWhileWaitingForTheUser() throws Exception {
        User user = createUser("user-before-token-" + UUID.randomUUID() + "@example.com", "hash");
        PasswordResetToken stale = saveToken(new PasswordResetToken(user,
                "user-before-token-stale-" + UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(30)),
                Instant.now().minus(Duration.ofMinutes(2)), Instant.now().minus(Duration.ofMinutes(10))));

        CountDownLatch userLocked = trackedLatch(1);
        CountDownLatch releaseUser = trackedLatch(1);
        AtomicInteger userHolderPid = new AtomicInteger();
        AtomicInteger recoveryPid = new AtomicInteger();
        CountDownLatch recoveryEnteredTransaction = trackedLatch(1);
        ExecutorService executor = newExecutor(3);
        Future<?> userHolder = null;
        Future<?> recovery = null;
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            userHolder = submitTask(executor, () -> tx.executeWithoutResult(status -> {
                userHolderPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                userRepository.lockForUpdate(user.getId());
                userLocked.countDown();
                awaitRelease(releaseUser);
            }));
            assertTrue(userLocked.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "independent transaction must hold the user row");

            recovery = withIssuanceObservation(executor, recoveryPid, recoveryEnteredTransaction,
                    recoverySweeper::sweep);
            assertTrue(recoveryEnteredTransaction.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "recoverCandidate backend PID must be captured at transactional entry");
            assertThat(recoveryPid.get()).isNotEqualTo(userHolderPid.get());
            assertSingleBackendBlockedOnUserLock(recoveryPid.get(), userHolderPid.get());

            AtomicInteger tokenLockPid = new AtomicInteger();
            Future<?> tokenLock = submitTask(executor, () -> tx.executeWithoutResult(status -> {
                tokenLockPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbcTemplate.queryForObject("SELECT id FROM password_reset_tokens WHERE id = ? FOR UPDATE",
                        UUID.class, stale.getId());
            }));
            tokenLock.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(tokenLockPid.get()).isNotIn(userHolderPid.get(), recoveryPid.get());
            assertThat(recovery).as("recovery remains blocked on users while another backend locks T0").isNotDone();

            releaseUser.countDown();
            userHolder.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            recovery.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseUser.countDown();
        }

        List<PasswordResetToken> rowsForUser = tokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId())).toList();
        List<PasswordResetToken> usable = rowsForUser.stream().filter(token -> token.getUsedAt() == null).toList();
        assertThat(rowsForUser).hasSize(2);
        assertThat(tokenRepository.findById(stale.getId()).orElseThrow().getUsedAt()).isNotNull();
        assertThat(usable).hasSize(1);
        assertThat(publications).hasSize(1);
        assertThat(publications.peek().idempotencyKey()).isEqualTo(usable.getFirst().getId().toString());
    }

    private Future<?> withIssuanceObservation(ExecutorService executor, AtomicInteger backendPid,
            CountDownLatch enteredIssuance, Runnable operation) {
        return submitTask(executor, () -> {
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

    private void assertSingleBackendBlockedOnUserLock(int waitingPid, int blockerPid) {
        Awaitility.await().atMost(Duration.ofSeconds(STAGE_TIMEOUT_SECONDS)).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> {
            Map<String, Object> activity = jdbcTemplate.queryForMap("""
                    SELECT pid, query, wait_event_type, ? = ANY(pg_blocking_pids(pid)) AS blocked_by_user_holder
                    FROM pg_stat_activity WHERE pid = ?
                    """, blockerPid, waitingPid);
            assertThat(((Number) activity.get("pid")).intValue()).isEqualTo(waitingPid);
            assertThat(activity.get("wait_event_type")).isEqualTo("Lock");
            assertThat(activity.get("blocked_by_user_holder")).isEqualTo(true);
            String query = activity.get("query").toString().toLowerCase();
            assertThat(query).contains("from users").contains("for no key update");
                });
    }

    private Instant persistedFirstRequestedAt(UUID tokenId) {
        return jdbcTemplate.queryForObject("SELECT first_requested_at FROM password_reset_tokens WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(), tokenId);
    }

    private PasswordResetEmailRecoverySweeper gatedRecoverySweeper(CountDownLatch arrived, CountDownLatch release) {
        return gatedRecoverySweeper(arrived, release, ignored -> {});
    }

    private PasswordResetEmailRecoverySweeper gatedRecoverySweeper(CountDownLatch arrived, CountDownLatch release,
            Consumer<List<PasswordResetToken>> candidateObserver) {
        PasswordResetTokenRepository gatedRepository = repositoryGatedAt(
                "findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore", arrived,
                release, candidateObserver);
        return new PasswordResetEmailRecoverySweeper(gatedRepository, passwordResetService, recoveryProperties,
                scheduledCallbackRunner);
    }

    private PasswordResetTokenCleanupTask gatedCleanupTask(CountDownLatch arrived, CountDownLatch release) {
        PasswordResetTokenRepository gatedRepository = repositoryGatedAt("deleteExpiredBefore", arrived, release,
                ignored -> {});
        return new PasswordResetTokenCleanupTask(gatedRepository, cleanupProperties, scheduledCallbackRunner,
                transactionManager);
    }

    private PasswordResetTokenRepository repositoryGatedAt(String methodName, CountDownLatch arrived,
            CountDownLatch release, Consumer<List<PasswordResetToken>> resultObserver) {
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
                            @SuppressWarnings("unchecked")
                            List<PasswordResetToken> candidates = (List<PasswordResetToken>) result;
                            resultObserver.accept(candidates);
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
        User user = createUser("cleanup-race-" + UUID.randomUUID() + "@example.com", "hash");
        Instant expiredAt = Instant.now().minus(cleanupProperties.getRetention()).minusSeconds(60);
        for (int index = 0; index < 4; index++) {
            saveToken(new PasswordResetToken(user, "expired-" + UUID.randomUUID(), expiredAt, expiredAt));
        }

        CountDownLatch bothCleanupCallersAtDelete = trackedLatch(2);
        CountDownLatch releaseCleanupCallers = trackedLatch(1);
        ExecutorService executor = newExecutor(2);
        try {
            PasswordResetTokenCleanupTask firstTask = gatedCleanupTask(
                    bothCleanupCallersAtDelete, releaseCleanupCallers);
            PasswordResetTokenCleanupTask secondTask = gatedCleanupTask(
                    bothCleanupCallersAtDelete, releaseCleanupCallers);
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            Future<?> firstCleanup = submitTask(executor, () -> transactionTemplate.executeWithoutResult(
                    status -> firstTask.cleanup()));
            Future<?> secondCleanup = submitTask(executor, () -> transactionTemplate.executeWithoutResult(
                    status -> secondTask.cleanup()));
            assertTrue(bothCleanupCallersAtDelete.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "both cleanup transactions must reach the delete before either executes it");
            releaseCleanupCallers.countDown();
            firstCleanup.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondCleanup.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseCleanupCallers.countDown();
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
            if (!latch.await(GATE_HOLDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent maintenance callers");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("maintenance test gate was interrupted", interrupted);
        }
    }

    private record IssuanceBackendObservation(AtomicInteger backendPid, CountDownLatch enteredIssuance) {}
}
