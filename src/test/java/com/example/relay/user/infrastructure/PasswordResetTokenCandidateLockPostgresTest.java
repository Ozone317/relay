package com.example.relay.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.support.background.EnableTestBackgroundExecution;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringBootTest
@EnableTestBackgroundExecution({})
class PasswordResetTokenCandidateLockPostgresTest implements SharedPostgresContainer {
    private static final Duration COORDINATOR_STAGE_TIMEOUT = Duration.ofSeconds(10);
    // The coordinator can use three 10-second stages (T1 completion, C startup, PostgreSQL observation).
    private static final Duration HOLDER_RELEASE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration TASK_TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void candidateLockBlocksOnlyTheSameTokenRow() throws Exception {
        User user = new User("candidate-lock-" + UUID.randomUUID() + "@example.com", "hash");
        Instant now = Instant.now();
        PasswordResetToken t0 = new PasswordResetToken(
                user, "candidate-t0-" + UUID.randomUUID(), now.plusSeconds(1800), now);
        PasswordResetToken t1 = new PasswordResetToken(
                user, "candidate-t1-" + UUID.randomUUID(), now.plusSeconds(1800), now);

        CountDownLatch t0Locked = new CountDownLatch(1);
        CountDownLatch releaseT0 = new CountDownLatch(1);
        CountDownLatch t0CompetitorAttempting = new CountDownLatch(1);
        AtomicInteger t0BackendPid = new AtomicInteger();
        AtomicInteger competitorBackendPid = new AtomicInteger();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<?> holder = null;
        Future<?> independentCandidate = null;
        Future<?> sameCandidate = null;
        try {
            userRepository.save(user);
            tokenRepository.save(t0);
            tokenRepository.save(t1);

            holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                t0BackendPid.set(backendPid());
                assertThat(tokenRepository.findByIdForUpdate(t0.getId())).isPresent();
                t0Locked.countDown();
                await(releaseT0, "release T0 holder", HOLDER_RELEASE_TIMEOUT);
            }));
            assertTrue(t0Locked.await(COORDINATOR_STAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "transaction A must lock T0");

            independentCandidate = executor.submit(() -> transactions.executeWithoutResult(status ->
                    assertThat(tokenRepository.findByIdForUpdate(t1.getId())).isPresent()));
            independentCandidate.get(COORDINATOR_STAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(releaseT0.getCount()).as("transaction A must still hold T0").isEqualTo(1);

            sameCandidate = executor.submit(() -> transactions.executeWithoutResult(status -> {
                competitorBackendPid.set(backendPid());
                t0CompetitorAttempting.countDown();
                assertThat(tokenRepository.findByIdForUpdate(t0.getId())).isPresent();
            }));
            assertTrue(t0CompetitorAttempting.await(
                    COORDINATOR_STAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "transaction C must begin its T0 lock request");
            assertThat(competitorBackendPid.get()).isNotEqualTo(t0BackendPid.get());
            assertBlockedBy(competitorBackendPid.get(), t0BackendPid.get());
            assertThat(sameCandidate.isDone()).as("transaction C must remain blocked while A holds T0").isFalse();

            releaseT0.countDown();
            holder.get(COORDINATOR_STAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            sameCandidate.get(COORDINATOR_STAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            releaseT0.countDown();
            assertTasksTerminated(executor, holder, independentCandidate, sameCandidate);
            jdbcTemplate.update("DELETE FROM password_reset_tokens WHERE id IN (?, ?)", t0.getId(), t1.getId());
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE id IN (?, ?)", Integer.class,
                t0.getId(), t1.getId())).as("the test must remove only its token fixtures").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, user.getId()))
                .as("the test must remove its user fixture").isZero();
    }

    private int backendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private void assertBlockedBy(int waiterPid, int blockerPid) {
        Awaitility.await().atMost(COORDINATOR_STAGE_TIMEOUT).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            Map<String, Object> activity = jdbcTemplate.queryForMap("""
                    SELECT wait_event_type, ? = ANY(pg_blocking_pids(pid)) AS blocked_by_holder
                    FROM pg_stat_activity
                    WHERE pid = ?
                    """, blockerPid, waiterPid);
            assertThat(activity.get("wait_event_type")).isEqualTo("Lock");
            assertThat(activity.get("blocked_by_holder")).isEqualTo(true);
        });
    }

    private static void await(CountDownLatch latch, String gate, Duration timeout) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("timed out waiting to " + gate);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting to " + gate, interrupted);
        }
    }

    private static void assertTasksTerminated(ExecutorService executor, Future<?>... tasks) {
        long deadline = System.nanoTime() + TASK_TERMINATION_TIMEOUT.toNanos();
        boolean interrupted = false;
        for (Future<?> task : tasks) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
            }
        }
        executor.shutdownNow();
        for (Future<?> task : tasks) {
            if (task == null) {
                continue;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                task.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (CancellationException | ExecutionException | TimeoutException ignored) {
                // Cancellation and worker failures are expected during failure-path teardown.
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        boolean terminated = false;
        while (System.nanoTime() < deadline && !terminated) {
            long remainingNanos = deadline - System.nanoTime();
            try {
                terminated = executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        assertTrue(terminated, "all lock-test worker transactions must terminate before fixture cleanup");
    }
}
