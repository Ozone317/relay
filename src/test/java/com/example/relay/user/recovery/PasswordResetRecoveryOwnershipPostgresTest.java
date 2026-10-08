package com.example.relay.user.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.example.relay.common.scheduling.ScheduledCallbackRunner;
import com.example.relay.common.security.SecureTokenGenerator;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringBootTest
@Import(PasswordResetRecoveryOwnershipPostgresTest.LockObservationConfiguration.class)
@EnableTestBackgroundExecution({})
@TestPropertySource(properties = {"relay.password-reset.email-recovery.interval=1s",
        "relay.password-reset.email-recovery.grace=1s", "relay.password-reset.email-recovery.max-recovery-window=3h",
        "JWT_SECRET=test-only-jwt-secret-for-recovery-tests-32bytes"})
class PasswordResetRecoveryOwnershipPostgresTest implements SharedPostgresContainer {

    private static final long STAGE_TIMEOUT_SECONDS = 10;
    private static final long FUTURE_TIMEOUT_SECONDS = 15;
    private static final long GATE_HOLDER_TIMEOUT_SECONDS = 90;
    private static final long CLEANUP_TIMEOUT_SECONDS = 30;
    private static final ThreadLocal<RecoveryLockObservation> RECOVERY_LOCK_OBSERVATION = new ThreadLocal<>();

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PasswordResetTokenService passwordResetTokenService;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @MockitoSpyBean
    private PasswordResetTokenService passwordResetTokenServiceSpy;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetEmailRecoveryProperties recoveryProperties;

    @Autowired
    private ScheduledCallbackRunner scheduledCallbackRunner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecureTokenGenerator secureTokenGenerator;

    @MockitoSpyBean
    private EmailDispatchPublisher emailDispatchPublisher;

    private final ConcurrentLinkedQueue<EmailDispatchMessage> publications = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<IssuanceObservation> issuanceObservation = new ThreadLocal<>();
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
        }).when(emailDispatchPublisher).publish(any(EmailDispatchMessage.class));
        doAnswer(invocation -> {
            captureIssuanceBackend();
            return invocation.callRealMethod();
        }).when(passwordResetTokenServiceSpy).issue(any(User.class), any(Instant.class));
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
                        "all race workers must terminate before fixture cleanup");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("could not prove race workers terminated before fixture cleanup", interrupted);
        }

        List<Throwable> workerFailures = new ArrayList<>();
        boolean interruptedWhileJoining = false;
        for (Future<?> future : workerFutures) {
            if (!future.isCancelled()) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    interruptedWhileJoining = true;
                    workerFailures.add(interrupted);
                } catch (java.util.concurrent.ExecutionException failure) {
                    workerFailures.add(failure.getCause() == null ? failure : failure.getCause());
                } catch (java.util.concurrent.TimeoutException failure) {
                    workerFailures.add(failure);
                }
            }
        }

        List<Throwable> cleanupFailures = deleteScopedFixtures();
        if (cleanupFailures.isEmpty()) {
            clearFixtureTracking();
        }
        if (interruptedWhileJoining) {
            Thread.currentThread().interrupt();
        }
        if (!cleanupFailures.isEmpty()) {
            cleanupFailures.addAll(workerFailures);
            throw failures("scoped race fixture cleanup failed", cleanupFailures);
        }
        if (!workerFailures.isEmpty()) {
            throw failures("race worker failed after scoped fixture cleanup", workerFailures);
        }
    }

    private List<Throwable> deleteScopedFixtures() {
        Set<UUID> scopedUsers = Set.copyOf(fixtureUserIds);
        Set<UUID> scopedTokens = ConcurrentHashMap.newKeySet();
        scopedTokens.addAll(fixtureTokenIds);
        List<Throwable> failures = new ArrayList<>();
        try {
            tokenRepository.findAll().stream().filter(token -> scopedUsers.contains(token.getUser().getId()))
                    .map(PasswordResetToken::getId).forEach(scopedTokens::add);
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        try {
            tokenRepository.deleteAllById(scopedTokens);
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        try {
            userRepository.deleteAllById(scopedUsers);
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        return failures;
    }

    private void clearFixtureTracking() {
        workerExecutors.clear();
        workerFutures.clear();
        releaseGates.clear();
        fixtureUserIds.clear();
        fixtureTokenIds.clear();
    }

    private static AssertionError failures(String message, List<Throwable> causes) {
        AssertionError error = new AssertionError(message);
        causes.forEach(error::addSuppressed);
        return error;
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
    void staleSelectedCandidateCannotInvalidateNewerDispatchConfirmedUserRequest() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        User user = createUser("candidate-race-" + UUID.randomUUID() + "@example.com", "hash");
        Instant firstRequestedAt = Instant.now().minus(Duration.ofMinutes(10));
        Instant staleUpdatedAt = Instant.now().minus(Duration.ofMinutes(2));
        PasswordResetToken t0 = saveToken(new PasswordResetToken(user, "t0-" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt, firstRequestedAt));

        CountDownLatch t0Selected = trackedLatch(1);
        CountDownLatch releaseRecovery = trackedLatch(1);
        PasswordResetEmailRecoverySweeper gatedSweeper = new PasswordResetEmailRecoverySweeper(
                gateAfterCandidateSelection(t0Selected, releaseRecovery, t0.getId()), passwordResetService,
                recoveryProperties, scheduledCallbackRunner);

        ExecutorService executor = newExecutor(1);
        Future<?> recovery = null;
        try {
            recovery = submitTask(executor, gatedSweeper::sweep);
            assertTrue(t0Selected.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the real PostgreSQL candidate query must return T0 before the user request starts");

            Future<?> ordinary = submitTask(newExecutor(1), () -> passwordResetService.issueAndDispatch(user));
            ordinary.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            EmailDispatchMessage t1Publication = publications.remove();
            UUID t1Id = UUID.fromString(t1Publication.idempotencyKey());
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            int dispatchClaims =
                    transactionTemplate.execute(status -> tokenRepository.claimResetEmailDispatch(t1Id, Instant.now()));
            assertThat(dispatchClaims).isOne();

            releaseRecovery.countDown();
            recovery.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            PasswordResetToken reloadedT0 = tokenRepository.findById(t0.getId()).orElseThrow();
            PasswordResetToken reloadedT1 = tokenRepository.findById(t1Id).orElseThrow();
            List<PasswordResetToken> rows = tokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId())).toList();
            List<PasswordResetToken> usable = rows.stream().filter(token -> token.getUsedAt() == null).toList();

            assertThat(rows).as("only T0 and the legitimate T1 exist").hasSize(2);
            assertThat(reloadedT0.getUsedAt()).as("the newer user request legitimately superseded T0").isNotNull();
            assertThat(reloadedT1.getResetEmailDispatchedAt()).isNotNull();
            assertThat(reloadedT1.getUsedAt()).isNull();
            assertThat(usable).as("only the newer confirmed request remains usable").hasSize(1);
            assertThat(usable.getFirst().getId()).isEqualTo(t1Id);
            assertThat(publications).as("no recovery publication follows the user-request publication").isEmpty();
        } finally {
            releaseRecovery.countDown();
        }
    }

    @Test
    void ordinaryRequestCommitsAfterCandidateScanAndWinsWithoutDispatchConfirmation() throws Exception {
        User user = createUser("ordinary-first-" + UUID.randomUUID() + "@example.com", "hash");
        PasswordResetToken t0 = staleCandidate(user, "ordinary-first-t0");
        RecoveryGate gate = startGatedRecovery(t0.getId());
        try {
            assertTrue(gate.selected().await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "recovery must select T0 before T1 commits");
            ExecutorService ordinaryExecutor = newExecutor(1);
            Future<?> ordinaryRequest = submitTask(ordinaryExecutor, () -> passwordResetService.issueAndDispatch(user));
            ordinaryRequest.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<EmailDispatchMessage> issuedPublications = List.copyOf(publications);
            assertThat(issuedPublications).hasSize(1);
            UUID t1Id = UUID.fromString(issuedPublications.getFirst().idempotencyKey());

            gate.release().countDown();
            gate.future().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<PasswordResetToken> rows = rowsFor(user);
            assertThat(rows).hasSize(2);
            assertThat(tokenRepository.findById(t0.getId()).orElseThrow().getUsedAt()).isNotNull();
            PasswordResetToken t1 = tokenRepository.findById(t1Id).orElseThrow();
            assertThat(t1.getUsedAt()).isNull();
            assertThat(t1.getResetEmailDispatchedAt()).isNull();
            assertThat(publications).as("the ordinary request is the sole publication").hasSize(1);
        } finally {
            gate.close();
        }
    }

    @Test
    void recoveryWinsThenOrdinaryRequestWaitsOnItsUserLock() throws Exception {
        User user = createUser("recovery-first-" + UUID.randomUUID() + "@example.com", "hash");
        PasswordResetToken t0 = staleCandidate(user, "recovery-first-t0");
        CountDownLatch lockedT0 = trackedLatch(1);
        CountDownLatch releaseRecovery = trackedLatch(1);
        AtomicInteger recoveryPid = new AtomicInteger();
        RecoveryLockObservation observation =
                new RecoveryLockObservation(t0.getId(), recoveryPid, lockedT0, releaseRecovery);
        CountDownLatch ordinaryEnteredIssue = trackedLatch(1);
        AtomicInteger ordinaryPid = new AtomicInteger();
        ExecutorService executor = newExecutor(2);
        Future<?> recovery = null;
        Future<?> ordinary = null;
        try {
            PasswordResetEmailRecoverySweeper gatedSweeper = new PasswordResetEmailRecoverySweeper(
                    gateAfterCandidateSelection(new CountDownLatch(0), new CountDownLatch(0), t0.getId()),
                    passwordResetService, recoveryProperties, scheduledCallbackRunner);
            recovery = submitTask(executor, () -> {
                RECOVERY_LOCK_OBSERVATION.set(observation);
                try {
                    gatedSweeper.sweep();
                } finally {
                    RECOVERY_LOCK_OBSERVATION.remove();
                }
            });
            assertTrue(lockedT0.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "recovery must own T0 before ordinary issuance starts");
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> jdbcTemplate
                    .queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", UUID.class, user.getId())))
                    .as("recovery's transaction must retain the exact user row lock").satisfies(failure -> {
                        Throwable root = failure;
                        while (root.getCause() != null) {
                            root = root.getCause();
                        }
                        assertThat(root).isInstanceOf(PSQLException.class);
                        assertThat(((PSQLException) root).getSQLState()).isEqualTo("55P03");
                    });
            ordinary = submitTask(executor, () -> {
                issuanceObservation.set(new IssuanceObservation(ordinaryPid, ordinaryEnteredIssue));
                try {
                    passwordResetService.issueAndDispatch(user);
                } finally {
                    issuanceObservation.remove();
                }
            });
            assertTrue(ordinaryEnteredIssue.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "ordinary request must enter issue transaction");
            assertThat(ordinaryPid.get()).isNotEqualTo(recoveryPid.get());
            assertThat(ordinary).as("ordinary issuance must remain inside its user-lock query").isNotDone();
            assertBlockedBy(recoveryPid.get(), ordinaryPid.get(), "from users", "for no key update");

            releaseRecovery.countDown();
            recovery.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ordinary.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<PasswordResetToken> rows = rowsFor(user);
            List<PasswordResetToken> successors =
                    rows.stream().filter(token -> !token.getId().equals(t0.getId())).toList();
            List<PasswordResetToken> usable = rows.stream().filter(token -> token.getUsedAt() == null).toList();
            List<PasswordResetToken> invalidated = rows.stream().filter(token -> token.getUsedAt() != null).toList();
            assertThat(rows).hasSize(3);
            assertThat(tokenRepository.findById(t0.getId()).orElseThrow().getUsedAt()).isNotNull();
            assertThat(successors).hasSize(2);
            assertThat(usable).hasSize(1);
            assertThat(invalidated).as("T0 and the recovery successor are invalidated by the later request").hasSize(2);
            assertThat(publications).hasSize(2);
            assertThat(publications.stream().map(EmailDispatchMessage::idempotencyKey).toList())
                    .containsExactlyInAnyOrderElementsOf(
                            successors.stream().map(token -> token.getId().toString()).toList());
            assertThat(publications.stream().map(EmailDispatchMessage::idempotencyKey).toList())
                    .contains(usable.getFirst().getId().toString());
        } finally {
            releaseRecovery.countDown();
        }
    }

    @Test
    void recoveryWinsThenDispatchConfirmationWaitsOnItsTokenLock() throws Exception {
        User user = createUser("confirm-after-recovery-" + UUID.randomUUID() + "@example.com", "hash");
        PasswordResetToken t0 = staleCandidate(user, "confirm-after-recovery-t0");
        CountDownLatch lockedT0 = trackedLatch(1);
        CountDownLatch releaseRecovery = trackedLatch(1);
        AtomicInteger recoveryPid = new AtomicInteger();
        RecoveryLockObservation observation =
                new RecoveryLockObservation(t0.getId(), recoveryPid, lockedT0, releaseRecovery);
        AtomicInteger confirmationPid = new AtomicInteger();
        CountDownLatch confirmationStarted = trackedLatch(1);
        ExecutorService executor = newExecutor(2);
        Future<?> recovery = null;
        Future<Integer> confirmation = null;
        try {
            PasswordResetEmailRecoverySweeper gatedSweeper = new PasswordResetEmailRecoverySweeper(
                    gateAfterCandidateSelection(new CountDownLatch(0), new CountDownLatch(0), t0.getId()),
                    passwordResetService, recoveryProperties, scheduledCallbackRunner);
            recovery = submitTask(executor, () -> {
                RECOVERY_LOCK_OBSERVATION.set(observation);
                try {
                    gatedSweeper.sweep();
                } finally {
                    RECOVERY_LOCK_OBSERVATION.remove();
                }
            });
            assertTrue(lockedT0.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "recovery must own T0 before confirmation starts");
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            confirmation = submitTask(executor, () -> tx.execute(status -> {
                confirmationPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                confirmationStarted.countDown();
                return tokenRepository.claimResetEmailDispatch(t0.getId(), Instant.now());
            }));
            assertTrue(confirmationStarted.await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "confirmation transaction must start");
            assertThat(confirmationPid.get()).isNotEqualTo(recoveryPid.get());
            assertBlockedBy(recoveryPid.get(), confirmationPid.get(), "update password_reset_tokens",
                    "reset_email_dispatched_at");

            releaseRecovery.countDown();
            recovery.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(confirmation.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isOne();

            PasswordResetToken reloadedT0 = tokenRepository.findById(t0.getId()).orElseThrow();
            List<PasswordResetToken> rows = rowsFor(user);
            List<PasswordResetToken> usable = rows.stream().filter(token -> token.getUsedAt() == null).toList();
            assertThat(reloadedT0.getUsedAt()).isNotNull();
            assertThat(reloadedT0.getResetEmailDispatchedAt()).isNotNull();
            assertThat(rows).hasSize(2);
            assertThat(usable).hasSize(1);
            assertThat(usable.getFirst().getId()).isNotEqualTo(t0.getId());
            assertThat(publications).hasSize(1);
            assertThat(publications.peek().idempotencyKey()).isEqualTo(usable.getFirst().getId().toString());
        } finally {
            releaseRecovery.countDown();
        }
    }

    @Test
    void dispatchConfirmationOfSelectedCandidateWinsBeforeRecoveryLock() throws Exception {
        User user = createUser("dispatch-race-" + UUID.randomUUID() + "@example.com", "hash");
        PasswordResetToken candidate = staleCandidate(user, "dispatch-race");
        RecoveryGate gate = startGatedRecovery(candidate.getId());
        try {
            assertTrue(gate.selected().await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            Future<Integer> dispatchConfirmation = submitTask(newExecutor(1), () -> transactionTemplate
                    .execute(status -> tokenRepository.claimResetEmailDispatch(candidate.getId(), Instant.now())));
            assertThat(dispatchConfirmation.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isOne();

            gate.release().countDown();
            gate.future().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            PasswordResetToken reloaded = tokenRepository.findById(candidate.getId()).orElseThrow();
            assertThat(reloaded.getResetEmailDispatchedAt()).isNotNull();
            assertThat(reloaded.getUsedAt()).isNull();
            assertThat(tokenRepository.findAll()).hasSize(1);
            assertThat(publications).isEmpty();
        } finally {
            gate.close();
        }
    }

    @Test
    void consumptionOfSelectedCandidateWinsBeforeRecoveryLock() throws Exception {
        User user = createUser("consume-race-" + UUID.randomUUID() + "@example.com", "hash");
        String rawToken = secureTokenGenerator.generateRawToken();
        Instant staleUpdatedAt = Instant.now().minus(Duration.ofMinutes(2));
        PasswordResetToken candidate = saveToken(new PasswordResetToken(user, secureTokenGenerator.hash(rawToken),
                Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt,
                Instant.now().minus(Duration.ofMinutes(10))));
        RecoveryGate gate = startGatedRecovery(candidate.getId());
        try {
            assertTrue(gate.selected().await(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            passwordResetTokenService.consumeAndResetPassword(rawToken, "new-hash", Instant.now());

            gate.release().countDown();
            gate.future().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            PasswordResetToken reloaded = tokenRepository.findById(candidate.getId()).orElseThrow();
            assertThat(reloaded.getUsedAt()).isNotNull();
            assertThat(tokenRepository.findAll()).hasSize(1);
            assertThat(publications).isEmpty();
        } finally {
            gate.close();
        }
    }

    private PasswordResetToken staleCandidate(User user, String prefix) {
        Instant staleUpdatedAt = Instant.now().minus(Duration.ofMinutes(2));
        return saveToken(new PasswordResetToken(user, prefix + "-" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt,
                Instant.now().minus(Duration.ofMinutes(10))));
    }

    private List<PasswordResetToken> rowsFor(User user) {
        return tokenRepository.findAll().stream().filter(token -> token.getUser().getId().equals(user.getId()))
                .toList();
    }

    private void captureIssuanceBackend() {
        IssuanceObservation observation = issuanceObservation.get();
        if (observation == null) {
            return;
        }
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "ordinary issuance PID must be captured inside its real transaction");
        observation.backendPid().set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
        observation.entered().countDown();
    }

    private void assertBlockedBy(int blockerPid, int blockedPid, String queryFragment, String secondFragment) {
        Awaitility.await().atMost(Duration.ofSeconds(STAGE_TIMEOUT_SECONDS)).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> {
                    Map<String, Object> activity = jdbcTemplate.queryForMap("""
                            SELECT waiting.pid, waiting.state, waiting.query, waiting.wait_event_type,
                                   pg_blocking_pids(waiting.pid) AS blocking_pids,
                                   ? = ANY(pg_blocking_pids(waiting.pid)) AS blocked_by_expected,
                                   holder.state AS holder_state, holder.query AS holder_query
                            FROM pg_stat_activity waiting CROSS JOIN pg_stat_activity holder
                            WHERE waiting.pid = ? AND holder.pid = ?
                            """, blockerPid, blockedPid, blockerPid);
                    assertThat(((Number) activity.get("pid")).intValue()).isEqualTo(blockedPid);
                    assertThat(activity.get("wait_event_type")).as("PostgreSQL activity: %s", activity)
                            .isEqualTo("Lock");
                    String query = activity.get("query").toString().toLowerCase();
                    assertThat(query).contains(queryFragment).contains(secondFragment);
                    assertThat(activity.get("blocked_by_expected")).isEqualTo(true);
                });
    }

    private RecoveryGate startGatedRecovery(UUID expectedCandidateId) {
        CountDownLatch selected = trackedLatch(1);
        CountDownLatch release = trackedLatch(1);
        PasswordResetEmailRecoverySweeper gatedSweeper = new PasswordResetEmailRecoverySweeper(
                gateAfterCandidateSelection(selected, release, expectedCandidateId), passwordResetService,
                recoveryProperties, scheduledCallbackRunner);
        ExecutorService executor = newExecutor(1);
        return new RecoveryGate(selected, release, submitTask(executor, gatedSweeper::sweep));
    }

    private PasswordResetTokenRepository gateAfterCandidateSelection(CountDownLatch selected, CountDownLatch release,
            UUID expectedCandidateId) {
        return (PasswordResetTokenRepository) Proxy.newProxyInstance(
                PasswordResetTokenRepository.class.getClassLoader(),
                new Class<?>[] {PasswordResetTokenRepository.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(tokenRepository, arguments);
                        if (method.getName().equals(
                                "findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore")) {
                            @SuppressWarnings("unchecked")
                            List<PasswordResetToken> candidates = (List<PasswordResetToken>) result;
                            assertThat(candidates.stream().map(PasswordResetToken::getId).toList())
                                    .as("the real candidate scan must return exactly T0 before the gate")
                                    .containsExactly(expectedCandidateId);
                            selected.countDown();
                            awaitRelease(release);
                        }
                        return result;
                    } catch (InvocationTargetException targetException) {
                        throw targetException.getCause();
                    }
                });
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(GATE_HOLDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release stale recovery");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("stale recovery gate was interrupted", interrupted);
        }
    }

    private record RecoveryLockObservation(UUID candidateId, AtomicInteger backendPid, CountDownLatch locked,
            CountDownLatch release) {
    }

    private record IssuanceObservation(AtomicInteger backendPid, CountDownLatch entered) {
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class LockObservationConfiguration {

        @Bean
        LockObservationAspect lockObservationAspect(JdbcTemplate jdbcTemplate) {
            return new LockObservationAspect(jdbcTemplate);
        }
    }

    @Aspect
    static class LockObservationAspect {

        private final JdbcTemplate jdbcTemplate;

        LockObservationAspect(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Around("execution(* com.example.relay.user.infrastructure.PasswordResetTokenRepository.findByIdForUpdate(..))")
        Object pauseAfterProductionCandidateLock(ProceedingJoinPoint invocation) throws Throwable {
            Object result = invocation.proceed();
            RecoveryLockObservation observation = RECOVERY_LOCK_OBSERVATION.get();
            UUID candidateId = invocation.getArgs()[0] instanceof UUID id ? id : null;
            if (observation != null && observation.candidateId().equals(candidateId)) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                        "production candidate lock must complete inside recovery's transaction");
                observation.backendPid().set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                observation.locked().countDown();
                awaitRelease(observation.release());
            }
            return result;
        }
    }

    private record RecoveryGate(CountDownLatch selected, CountDownLatch release,
            Future<?> future) implements AutoCloseable {

        @Override
        public void close() {
            release.countDown();
        }
    }
}
