package com.example.relay.support.background;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.RelayApplication;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.attempt.infrastructure.ReadyWorkRepositoryImpl;
import com.example.relay.deliveryengine.dispatcher.ReadyWorkDispatcher;
import com.example.relay.deliveryengine.publisher.ReadyPublishOutcome;
import com.example.relay.deliveryengine.publisher.ReadyTaskPublisher;
import com.example.relay.deliveryengine.reconciliation.ReconciliationSweeper;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.deliveryengine.retry.RetryScheduler;
import com.example.relay.support.SharedPostgresContainer;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("integration")
@SpringBootTest(classes = RelayApplication.class)
@TestPropertySource(properties = {
        "relay.reconciliation.in-flight-grace=1s",
        "relay.reconciliation.dead-letter-grace=1h",
        "relay.reconciliation.interval=1h"
})
class BackgroundExecutionCrossContextRegressionTest implements SharedPostgresContainer {

    private static final TestContextManager ORDINARY_SOURCE = new TestContextManager(OrdinarySourceFixture.class);

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationSweeper reconciliationSweeper;
    private final List<UUID> victimUserIds = new ArrayList<>();

    @Autowired
    BackgroundExecutionCrossContextRegressionTest(
            JdbcTemplate jdbcTemplate, ReconciliationSweeper reconciliationSweeper) {
        this.jdbcTemplate = jdbcTemplate;
        this.reconciliationSweeper = reconciliationSweeper;
    }

    @BeforeAll
    static void openOrdinarySourceContext() throws Exception {
        ORDINARY_SOURCE.beforeTestClass();
    }

    @AfterAll
    static void closeOrdinarySourceContext() throws Exception {
        ORDINARY_SOURCE.getTestContext().markApplicationContextDirty(null);
        ORDINARY_SOURCE.afterTestClass();
    }

    @BeforeEach
    void removeVictimRows() {
        jdbcTemplate.update("DELETE FROM attempts");
    }

    @AfterEach
    void removeCommittedVictimGraph() {
        victimUserIds.forEach(userId -> {
            jdbcTemplate.update(
                    "DELETE FROM attempts WHERE app_id IN (SELECT id FROM apps WHERE environment_id IN "
                            + "(SELECT id FROM environments WHERE user_id = ?))",
                    userId);
            jdbcTemplate.update(
                    "DELETE FROM deliveries WHERE app_id IN (SELECT id FROM apps WHERE environment_id IN "
                            + "(SELECT id FROM environments WHERE user_id = ?))",
                    userId);
            jdbcTemplate.update(
                    "DELETE FROM messages WHERE app_id IN (SELECT id FROM apps WHERE environment_id IN "
                            + "(SELECT id FROM environments WHERE user_id = ?))",
                    userId);
            jdbcTemplate.update(
                    "DELETE FROM endpoints WHERE app_id IN (SELECT id FROM apps WHERE environment_id IN "
                            + "(SELECT id FROM environments WHERE user_id = ?))",
                    userId);
            jdbcTemplate.update(
                    "DELETE FROM events WHERE app_id IN (SELECT id FROM apps WHERE environment_id IN "
                            + "(SELECT id FROM environments WHERE user_id = ?))",
                    userId);
            jdbcTemplate.update("DELETE FROM apps WHERE environment_id IN "
                    + "(SELECT id FROM environments WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM environments WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        });
        victimUserIds.clear();
    }

    @Test
    void policyBypass_realRetrySchedulerPromotesAnotherContextsCommittedScheduledRow() {
        UUID attemptId = insertAttempt("SCHEDULED", Instant.now().minusSeconds(60));

        try (AnnotationConfigApplicationContext foreignSource = policyBypassedSource()) {
            ControllableTaskScheduler scheduler = foreignSource.getBean(ControllableTaskScheduler.class);
            assertTrue(scheduler.hasCallback("RetryScheduler.scheduledReleaseDueRetries"));

            scheduler.runCapturedTasks();

            assertEquals("CREATED", status(attemptId));
        }
    }

    @Test
    void policyBypass_realReadyWorkDispatcherClaimsAnotherContextsCommittedResetRow() {
        UUID attemptId = insertAttempt("IN_FLIGHT", Instant.now().minusSeconds(3600));
        reconciliationSweeper.sweep();
        assertResetAndUnclaimed(attemptId);

        try (AnnotationConfigApplicationContext foreignSource = policyBypassedSource()) {
            ControllableTaskScheduler scheduler = foreignSource.getBean(ControllableTaskScheduler.class);
            assertTrue(scheduler.hasCallback("ReadyWorkDispatcher.scheduledDispatch"));

            scheduler.runCapturedTasks();

            assertEquals("CREATED", status(attemptId));
            assertTrue(hasClaim(attemptId));
        }
    }

    @Test
    void ordinaryPolicy_registersNoRetryCallbackAndCannotPromoteTheVictimRow() {
        UUID attemptId = insertAttempt("SCHEDULED", Instant.now().minusSeconds(60));
        ControllableTaskScheduler scheduler = ordinaryScheduler();

        assertEquals(0, scheduler.capturedTaskCount());
        scheduler.runCapturedTasks();

        assertEquals("SCHEDULED", status(attemptId));
    }

    @Test
    void ordinaryPolicy_registersNoDispatcherCallbackAndCannotClaimTheResetVictimRow() {
        UUID attemptId = insertAttempt("IN_FLIGHT", Instant.now().minusSeconds(3600));
        reconciliationSweeper.sweep();
        assertResetAndUnclaimed(attemptId);
        ControllableTaskScheduler scheduler = ordinaryScheduler();

        assertEquals(0, scheduler.capturedTaskCount());
        scheduler.runCapturedTasks();

        assertResetAndUnclaimed(attemptId);
    }

    @Test
    void schedulingOptIn_closesItsSchedulerDatasourceAndDestroyCallbackAfterClassLifecycle() throws Exception {
        TestContextManager manager = new TestContextManager(OptInSourceFixture.class);
        manager.beforeTestClass();
        ConfigurableApplicationContext context;
        ControllableTaskScheduler scheduler;
        HikariDataSource dataSource;
        CloseSignal closeSignal;
        try {
            context = (ConfigurableApplicationContext) manager.getTestContext().getApplicationContext();
            scheduler = context.getBean(ControllableTaskScheduler.class);
            dataSource = context.getBean(HikariDataSource.class);
            closeSignal = context.getBean(CloseSignal.class);

            assertEquals(2, scheduler.capturedTaskCount());
            assertFalse(scheduler.isClosed());
            assertFalse(dataSource.isClosed());
        } finally {
            manager.afterTestClass();
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> closeSignal.latch().getCount() == 0);
        assertTrue(scheduler.isClosed());
        assertTrue(dataSource.isClosed());
        assertFalse(context.isActive());
    }

    private ControllableTaskScheduler ordinaryScheduler() {
        return ORDINARY_SOURCE
                .getTestContext()
                .getApplicationContext()
                .getBean(ControllableTaskScheduler.class);
    }

    private AnnotationConfigApplicationContext policyBypassedSource() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "backgroundRegressionCadence",
                java.util.Map.of(
                        "relay.retry.scheduler-interval", "1h",
                        "relay.retry.dispatcher-interval", "1h")));
        context.register(ProductionBackgroundSourceConfiguration.class);
        context.refresh();
        return context;
    }

    private UUID insertAttempt(String status, Instant updatedAt) {
        UUID userId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTimestamp = Timestamp.from(now);
        Timestamp updatedTimestamp = Timestamp.from(updatedAt);
        victimUserIds.add(userId);

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password, email_verified, version) VALUES (?, ?, ?, false, 0)",
                userId, "p00-" + userId + "@example.com", "hash");
        jdbcTemplate.update(
                "INSERT INTO environments (id, user_id, name, description, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                environmentId, userId, "P00 environment", "cross-context regression", nowTimestamp, nowTimestamp);
        jdbcTemplate.update("INSERT INTO apps (id, name, environment_id, created_at) VALUES (?, ?, ?, ?)", appId,
                "P00 app", environmentId, nowTimestamp);
        jdbcTemplate.update("INSERT INTO events (id, name, app_id, created_at) VALUES (?, ?, ?, ?)", eventId,
                "p00.event." + eventId, appId, nowTimestamp);
        jdbcTemplate.update(
                "INSERT INTO endpoints (id, name, url, signing_secret, is_active, app_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, ?, ?, ?)",
                endpointId, "P00 endpoint " + endpointId, "https://example.com/p00", "secret", appId, nowTimestamp,
                nowTimestamp);
        jdbcTemplate.update(
                "INSERT INTO messages (id, app_id, event_id, body, created_at) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                messageId, appId, eventId, "{}", nowTimestamp);
        jdbcTemplate.update(
                "INSERT INTO deliveries (id, app_id, message_id, endpoint_id, created_at) VALUES (?, ?, ?, ?, ?)",
                deliveryId, appId, messageId, endpointId, nowTimestamp);
        jdbcTemplate.update(
                "INSERT INTO attempts (id, app_id, message_id, endpoint_id, delivery_id, attempt_no, status, "
                        + "next_retry_at, created_at, updated_at, execution_generation, execution_claimed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, CASE WHEN ? = 'IN_FLIGHT' THEN 1 ELSE 0 END, "
                        + "CASE WHEN ? = 'IN_FLIGHT' THEN CAST(? AS timestamptz) ELSE NULL END)",
                attemptId, appId, messageId, endpointId, deliveryId, status,
                "SCHEDULED".equals(status) ? updatedTimestamp : null, nowTimestamp, updatedTimestamp,
                status, status, "IN_FLIGHT".equals(status) ? updatedTimestamp : null);
        return attemptId;
    }

    private String status(UUID attemptId) {
        return jdbcTemplate.queryForObject("SELECT status FROM attempts WHERE id = ?", String.class, attemptId);
    }

    private boolean hasClaim(UUID attemptId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT ready_dispatch_claim_id IS NOT NULL AND ready_dispatch_claimed_at IS NOT NULL "
                        + "FROM attempts WHERE id = ?",
                Boolean.class, attemptId));
    }

    private void assertResetAndUnclaimed(UUID attemptId) {
        assertEquals("CREATED", status(attemptId));
        jdbcTemplate.queryForObject(
                "SELECT ready_published_at, ready_dispatch_claim_id, ready_dispatch_claimed_at "
                        + "FROM attempts WHERE id = ?",
                (resultSet, rowNumber) -> {
                    assertNull(resultSet.getObject("ready_published_at"));
                    assertNull(resultSet.getObject("ready_dispatch_claim_id"));
                    assertNull(resultSet.getObject("ready_dispatch_claimed_at"));
                    return null;
                },
                attemptId);
    }

    @SpringJUnitConfig(ProductionBackgroundSourceConfiguration.class)
    @TestPropertySource(properties = {
            "relay.retry.scheduler-interval=1h",
            "relay.retry.dispatcher-interval=1h"
    })
    private static class OrdinarySourceFixture {}

    @SpringJUnitConfig(ProductionBackgroundSourceConfiguration.class)
    @TestPropertySource(properties = {
            "relay.retry.scheduler-interval=1h",
            "relay.retry.dispatcher-interval=1h"
    })
    @EnableTestBackgroundExecution(TestBackgroundComponent.SCHEDULING)
    private static class OptInSourceFixture {}

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableTransactionManagement
    static class ProductionBackgroundSourceConfiguration {

        @Bean
        HikariDataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(SharedPostgresContainer.POSTGRES.getJdbcUrl());
            dataSource.setUsername(SharedPostgresContainer.POSTGRES.getUsername());
            dataSource.setPassword(SharedPostgresContainer.POSTGRES.getPassword());
            dataSource.setDriverClassName(SharedPostgresContainer.POSTGRES.getDriverClassName());
            dataSource.setPoolName("p00-foreign-source-" + UUID.randomUUID());
            dataSource.setMaximumPoolSize(2);
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(HikariDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(HikariDataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        ReadyWorkRepository readyWorkRepository(NamedParameterJdbcTemplate jdbcTemplate) {
            return new ReadyWorkRepositoryImpl(jdbcTemplate);
        }

        @Bean
        RetryProperties retryProperties() {
            RetryProperties properties = new RetryProperties();
            properties.setSchedulingEnabled(true);
            return properties;
        }

        @Bean(name = "deliveryProgressTaskScheduler")
        ControllableTaskScheduler deliveryProgressTaskScheduler() {
            return new ControllableTaskScheduler();
        }

        @Bean(name = "readyWorkConfirmationExecutor")
        Executor readyWorkConfirmationExecutor() {
            return Runnable::run;
        }

        @Bean
        ReadyTaskPublisher readyTaskPublisher() {
            return attemptId -> CompletableFuture.completedFuture(ReadyPublishOutcome.AMBIGUOUS);
        }

        @Bean
        RetryScheduler retryScheduler(ReadyWorkRepository repository, RetryProperties properties) {
            return new RetryScheduler(repository, properties);
        }

        @Bean
        ReadyWorkDispatcher readyWorkDispatcher(
                ReadyWorkRepository repository,
                ReadyTaskPublisher publisher,
                @Qualifier("readyWorkConfirmationExecutor") Executor confirmationExecutor,
                RetryProperties properties) {
            return new ReadyWorkDispatcher(repository, publisher, confirmationExecutor, properties);
        }

        @Bean
        CloseSignal closeSignal() {
            return new CloseSignal(new CountDownLatch(1));
        }
    }

    record CloseSignal(CountDownLatch latch) implements DisposableBean {
        @Override
        public void destroy() {
            latch.countDown();
        }
    }

    static final class ControllableTaskScheduler implements TaskScheduler, DisposableBean {

        private final List<Runnable> capturedTasks = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return capture(task);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return capture(task);
        }

        @Override
        public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return capture(task);
        }

        @Override
        public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return capture(task);
        }

        @Override
        public synchronized ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task, Instant startTime, Duration delay) {
            return capture(task);
        }

        @Override
        public synchronized ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return capture(task);
        }

        synchronized int capturedTaskCount() {
            return capturedTasks.size();
        }

        synchronized boolean hasCallback(String methodName) {
            return capturedTasks.stream().map(Object::toString).anyMatch(description -> description.contains(methodName));
        }

        void runCapturedTasks() {
            List<Runnable> snapshot;
            synchronized (this) {
                snapshot = List.copyOf(capturedTasks);
            }
            CountDownLatch completed = new CountDownLatch(snapshot.size());
            snapshot.forEach(task -> {
                try {
                    task.run();
                } finally {
                    completed.countDown();
                }
            });
            assertEquals(0, completed.getCount());
        }

        boolean isClosed() {
            return closed.get();
        }

        @Override
        public void destroy() {
            closed.set(true);
        }

        private ScheduledFuture<?> capture(Runnable task) {
            capturedTasks.add(task);
            return NeverScheduledFuture.INSTANCE;
        }
    }

    private static final class NeverScheduledFuture implements ScheduledFuture<Object> {

        private static final NeverScheduledFuture INSTANCE = new NeverScheduledFuture();

        private NeverScheduledFuture() {}

        @Override
        public long getDelay(TimeUnit unit) {
            return Long.MAX_VALUE;
        }

        @Override
        public int compareTo(Delayed other) {
            return 1;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}
