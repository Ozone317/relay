package com.example.relay.user.recovery;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("integration")
@SpringBootTest
@EnableTestBackgroundExecution({})
@TestPropertySource(properties = {"relay.password-reset.email-recovery.interval=1s",
        "relay.password-reset.email-recovery.grace=1s",
        "relay.password-reset.email-recovery.max-recovery-window=3h",
        "JWT_SECRET=test-only-jwt-secret-for-recovery-tests-32bytes"})
class PasswordResetRecoveryOwnershipPostgresTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PasswordResetTokenService passwordResetTokenService;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

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
    }

    @Test
    void staleSelectedCandidateCannotInvalidateNewerDispatchConfirmedUserRequest() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class))
                .isEqualTo("read committed");
        User user = userRepository.save(new User("candidate-race-" + UUID.randomUUID() + "@example.com", "hash"));
        Instant firstRequestedAt = Instant.now().minus(Duration.ofMinutes(10));
        Instant staleUpdatedAt = Instant.now().minus(Duration.ofMinutes(2));
        PasswordResetToken t0 = tokenRepository.saveAndFlush(new PasswordResetToken(user,
                "t0-" + UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt,
                firstRequestedAt));

        CountDownLatch t0Selected = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        PasswordResetEmailRecoverySweeper gatedSweeper = new PasswordResetEmailRecoverySweeper(
                gateAfterCandidateSelection(t0Selected, releaseRecovery), passwordResetService, recoveryProperties,
                scheduledCallbackRunner);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> recovery = executor.submit(gatedSweeper::sweep);
            assertTrue(t0Selected.await(10, TimeUnit.SECONDS),
                    "the real PostgreSQL candidate query must return T0 before the user request starts");

            passwordResetService.issueAndDispatch(user);
            EmailDispatchMessage t1Publication = publications.remove();
            UUID t1Id = UUID.fromString(t1Publication.idempotencyKey());
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            int dispatchClaims = transactionTemplate.execute(status ->
                    tokenRepository.claimResetEmailDispatch(t1Id, Instant.now()));
            assertThat(dispatchClaims).isOne();

            releaseRecovery.countDown();
            recovery.get(15, TimeUnit.SECONDS);

            PasswordResetToken reloadedT0 = tokenRepository.findById(t0.getId()).orElseThrow();
            PasswordResetToken reloadedT1 = tokenRepository.findById(t1Id).orElseThrow();
            List<PasswordResetToken> rows = tokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .toList();
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
            executor.shutdownNow();
        }
    }

    @Test
    void dispatchConfirmationOfSelectedCandidateWinsBeforeRecoveryLock() throws Exception {
        User user = userRepository.save(new User("dispatch-race-" + UUID.randomUUID() + "@example.com", "hash"));
        PasswordResetToken candidate = staleCandidate(user, "dispatch-race");
        RecoveryGate gate = startGatedRecovery();
        try {
            assertTrue(gate.selected().await(10, TimeUnit.SECONDS));
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            int dispatchClaims = transactionTemplate.execute(status ->
                    tokenRepository.claimResetEmailDispatch(candidate.getId(), Instant.now()));
            assertThat(dispatchClaims).isOne();

            gate.release().countDown();
            gate.future().get(15, TimeUnit.SECONDS);

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
        User user = userRepository.save(new User("consume-race-" + UUID.randomUUID() + "@example.com", "hash"));
        String rawToken = secureTokenGenerator.generateRawToken();
        Instant staleUpdatedAt = Instant.now().minus(Duration.ofMinutes(2));
        PasswordResetToken candidate = tokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(rawToken), Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt,
                Instant.now().minus(Duration.ofMinutes(10))));
        RecoveryGate gate = startGatedRecovery();
        try {
            assertTrue(gate.selected().await(10, TimeUnit.SECONDS));
            passwordResetTokenService.consumeAndResetPassword(rawToken, "new-hash", Instant.now());

            gate.release().countDown();
            gate.future().get(15, TimeUnit.SECONDS);

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
        return tokenRepository.saveAndFlush(new PasswordResetToken(user, prefix + "-" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(30)), staleUpdatedAt,
                Instant.now().minus(Duration.ofMinutes(10))));
    }

    private RecoveryGate startGatedRecovery() {
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PasswordResetEmailRecoverySweeper gatedSweeper = new PasswordResetEmailRecoverySweeper(
                gateAfterCandidateSelection(selected, release), passwordResetService, recoveryProperties,
                scheduledCallbackRunner);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return new RecoveryGate(selected, release, executor, executor.submit(gatedSweeper::sweep));
    }

    private PasswordResetTokenRepository gateAfterCandidateSelection(CountDownLatch selected, CountDownLatch release) {
        return (PasswordResetTokenRepository) Proxy.newProxyInstance(
                PasswordResetTokenRepository.class.getClassLoader(),
                new Class<?>[] {PasswordResetTokenRepository.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(tokenRepository, arguments);
                        if (method.getName().equals(
                                "findByResetEmailDispatchedAtIsNullAndUsedAtIsNullAndExpiresAtAfterAndUpdatedAtBefore")) {
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
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release stale recovery");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("stale recovery gate was interrupted", interrupted);
        }
    }

    private record RecoveryGate(CountDownLatch selected, CountDownLatch release, ExecutorService executor,
            Future<?> future) implements AutoCloseable {

        @Override
        public void close() {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
