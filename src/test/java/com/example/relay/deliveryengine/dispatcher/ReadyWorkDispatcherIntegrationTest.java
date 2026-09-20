package com.example.relay.deliveryengine.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.publisher.ReadyPublishOutcome;
import com.example.relay.deliveryengine.publisher.ReadyTaskPublisher;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.deliveryengine.retry.RetryScheduler;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.domain.Message;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "relay.retry.scheduling-enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadyWorkDispatcherIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @Autowired
    private ReadyWorkRepository readyWorkRepository;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private AttemptService attemptService;

    @Autowired
    private AttemptPublisher attemptPublisher;

    @Autowired
    private RetryProperties retryProperties;

    @Autowired
    @Qualifier("readyWorkConfirmationExecutor")
    private ThreadPoolTaskExecutor confirmationExecutor;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private Endpoint endpoint;
    private Message message;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        messageRepository.deleteAll();
        endpointRepository.deleteAll();
        eventRepository.deleteAll();
        appRepository.deleteAll();
        environmentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        while (rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 100) != null) {
            // discard messages left by an earlier test
        }

        User user = userRepository.save(new User("dispatcher-" + UUID.randomUUID() + "@mail.com", "hash"));
        Environment environment = environmentRepository.save(new Environment("Environment", "Description", user));
        App app = appRepository.save(new App("App", environment));
        Event event = eventRepository.save(new Event("dispatcher." + UUID.randomUUID(), app));
        endpoint = endpointRepository.save(new Endpoint("Endpoint", "https://example.com/webhook", "secret", app));
        message = messageRepository.save(new Message(app, event, new ObjectMapper().createObjectNode()));
        retryProperties.setDispatcherBatchSize(100);
        retryProperties.setUnconfirmedReadyGrace(Duration.ofSeconds(10));
    }

    @Test
    void freshCreatedAttemptPublishesWithoutWaitingForRecoveryGrace() {
        Attempt attempt = createCreatedAttempt();
        ReadyWorkDispatcher dispatcher = dispatcher(attemptPublisher);

        dispatcher.dispatchOnce();

        org.springframework.amqp.core.Message received = rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody())).isEqualTo(attempt.getId().toString());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(reload(attempt).getReadyPublishedAt()).isNotNull());
    }

    @Test
    void confirmedBrokerBacklogIsNotPublishedAgain() {
        Attempt attempt = createCreatedAttempt();
        ReadyWorkDispatcher dispatcher = dispatcher(attemptPublisher);

        dispatcher.dispatchOnce();
        assertThat(rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 5000)).isNotNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(reload(attempt).getReadyPublishedAt()).isNotNull());

        dispatcher.dispatchOnce();

        assertThat(rabbitTemplate.receive(RabbitMqConfig.TASKS_QUEUE, 250)).isNull();
    }

    @Test
    void definiteFailureKeepsMarkerNullAndLeaseUntilGrace() {
        Attempt attempt = createCreatedAttempt();
        RecordingPublisher publisher = new RecordingPublisher(
                CompletableFuture.completedFuture(ReadyPublishOutcome.DEFINITE_FAILURE));
        ReadyWorkDispatcher dispatcher = dispatcher(publisher);

        dispatcher.dispatchOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Attempt reloaded = reload(attempt);
            assertThat(reloaded.getReadyPublishedAt()).isNull();
            assertThat(reloaded.getReadyDispatchClaimId()).isNotNull();
        });
        dispatcher.dispatchOnce();
        assertThat(publisher.ids()).containsExactly(attempt.getId());

        backdateLease(attempt);
        dispatcher.dispatchOnce();
        assertThat(publisher.ids()).containsExactly(attempt.getId(), attempt.getId());
    }

    @Test
    void ambiguousFailureKeepsMarkerNullAndRecoversAfterGrace() {
        Attempt attempt = createCreatedAttempt();
        RecordingPublisher publisher = new RecordingPublisher(
                CompletableFuture.completedFuture(ReadyPublishOutcome.AMBIGUOUS));
        ReadyWorkDispatcher dispatcher = dispatcher(publisher);

        dispatcher.dispatchOnce();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(reload(attempt).getReadyPublishedAt()).isNull());
        backdateLease(attempt);

        dispatcher.dispatchOnce();

        assertThat(publisher.ids()).containsExactly(attempt.getId(), attempt.getId());
        assertThat(reload(attempt).getReadyPublishedAt()).isNull();
    }

    @Test
    void twoDispatchersClaimDisjointBatches() throws Exception {
        Attempt firstAttempt = createCreatedAttempt();
        Attempt secondAttempt = createCreatedAttempt();
        retryProperties.setDispatcherBatchSize(1);
        RecordingPublisher publisher = new RecordingPublisher(new CompletableFuture<>());
        ReadyWorkDispatcher first = dispatcher(publisher);
        ReadyWorkDispatcher second = dispatcher(publisher);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = callers.submit(first::dispatchOnce);
            Future<?> secondRun = callers.submit(second::dispatchOnce);
            firstRun.get(5, TimeUnit.SECONDS);
            secondRun.get(5, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(publisher.ids()).hasSize(2));
        assertThat(Set.copyOf(publisher.ids())).containsExactlyInAnyOrder(firstAttempt.getId(), secondAttempt.getId());
    }

    @Test
    void fastConsumerAndSlowConfirmCannotMarkInFlightAttempt() throws Exception {
        Attempt attempt = createCreatedAttempt();
        CompletableFuture<ReadyPublishOutcome> unresolved = new CompletableFuture<>();
        RecordingPublisher publisher = new RecordingPublisher(unresolved);
        ConfirmationTrackingExecutor executor = new ConfirmationTrackingExecutor(confirmationExecutor);
        ReadyWorkDispatcher dispatcher = new ReadyWorkDispatcher(
                readyWorkRepository, publisher, executor, retryProperties);

        dispatcher.dispatchOnce();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(publisher.ids()).containsExactly(attempt.getId()));
        assertThat(attemptService.claim(attempt.getId(), Instant.now())).isTrue();

        unresolved.complete(ReadyPublishOutcome.CONFIRMED);
        assertThat(executor.completed.await(5, TimeUnit.SECONDS)).isTrue();

        Attempt reloaded = reload(attempt);
        assertThat(reloaded.getStatus()).isEqualTo(AttemptStatus.IN_FLIGHT);
        assertThat(reloaded.getReadyPublishedAt()).isNull();
    }

    @Test
    void unresolvedConfirmationDoesNotBlockTickOrRetryPromotion() {
        Attempt attempt = createCreatedAttempt();
        CompletableFuture<ReadyPublishOutcome> unresolved = new CompletableFuture<>();
        RecordingPublisher publisher = new RecordingPublisher(unresolved);
        ReadyWorkDispatcher dispatcher = dispatcher(publisher);

        assertThatCode(dispatcher::dispatchOnce).doesNotThrowAnyException();
        assertThat(unresolved).isNotDone();
        assertThat(reload(attempt).getReadyPublishedAt()).isNull();

        Attempt scheduled = createScheduledAttempt(Instant.now().minusSeconds(1));
        new RetryScheduler(readyWorkRepository, retryProperties).releaseDueRetries();
        assertThat(reload(scheduled).getStatus()).isEqualTo(AttemptStatus.CREATED);
    }

    @Test
    void confirmationExecutorRejectionLeavesMarkerNullAndLeaseIntact() {
        Attempt attempt = createCreatedAttempt();
        RecordingPublisher publisher = new RecordingPublisher(
                CompletableFuture.completedFuture(ReadyPublishOutcome.CONFIRMED));
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("confirmation executor is closed");
        };
        ReadyWorkDispatcher dispatcher = new ReadyWorkDispatcher(
                readyWorkRepository, publisher, rejectingExecutor, retryProperties);

        dispatcher.dispatchOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Attempt reloaded = reload(attempt);
            assertThat(reloaded.getReadyPublishedAt()).isNull();
            assertThat(reloaded.getReadyDispatchClaimId()).isNotNull();
        });
    }

    @Test
    void ambiguousRepublishStillAllowsOnlyOneWorkerClaim() {
        Attempt attempt = createCreatedAttempt();
        RecordingPublisher publisher = new RecordingPublisher(
                CompletableFuture.completedFuture(ReadyPublishOutcome.AMBIGUOUS));
        ReadyWorkDispatcher dispatcher = dispatcher(publisher);

        dispatcher.dispatchOnce();
        backdateLease(attempt);
        dispatcher.dispatchOnce();

        assertThat(publisher.ids()).containsExactly(attempt.getId(), attempt.getId());
        assertThat(attemptService.claim(attempt.getId(), Instant.now())).isTrue();
        assertThat(attemptService.claim(attempt.getId(), Instant.now())).isFalse();
    }

    private ReadyWorkDispatcher dispatcher(ReadyTaskPublisher publisher) {
        return new ReadyWorkDispatcher(readyWorkRepository, publisher, confirmationExecutor, retryProperties);
    }

    private Attempt createCreatedAttempt() {
        Endpoint attemptEndpoint = newEndpoint();
        Delivery delivery = deliveryRepository.save(new Delivery(attemptEndpoint.getApp(), message, attemptEndpoint));
        return attemptRepository.save(new Attempt(attemptEndpoint.getApp(), message, attemptEndpoint, delivery, 1));
    }

    private Attempt createScheduledAttempt(Instant nextRetryAt) {
        Endpoint attemptEndpoint = newEndpoint();
        Delivery delivery = deliveryRepository.save(new Delivery(attemptEndpoint.getApp(), message, attemptEndpoint));
        Attempt attempt = new Attempt(attemptEndpoint.getApp(), message, attemptEndpoint, delivery, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(nextRetryAt);
        return attemptRepository.save(attempt);
    }

    private Endpoint newEndpoint() {
        return endpointRepository.save(new Endpoint(
                "Endpoint-" + UUID.randomUUID(),
                "https://example.com/webhook/" + UUID.randomUUID(),
                "secret-" + UUID.randomUUID(),
                endpoint.getApp()));
    }

    private Attempt reload(Attempt attempt) {
        return attemptRepository.findById(attempt.getId()).orElseThrow();
    }

    private void backdateLease(Attempt attempt) {
        jdbcTemplate.update(
                "UPDATE attempts SET ready_dispatch_claimed_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                attempt.getId());
    }

    private static final class RecordingPublisher implements ReadyTaskPublisher {

        private final CompletableFuture<ReadyPublishOutcome> outcome;
        private final Queue<UUID> published = new ConcurrentLinkedQueue<>();

        private RecordingPublisher(CompletableFuture<ReadyPublishOutcome> outcome) {
            this.outcome = outcome;
        }

        @Override
        public CompletableFuture<ReadyPublishOutcome> publishReady(UUID attemptId) {
            published.add(attemptId);
            return outcome;
        }

        private List<UUID> ids() {
            return List.copyOf(published);
        }
    }

    private static final class ConfirmationTrackingExecutor implements Executor {

        private final Executor delegate;
        private final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);

        private ConfirmationTrackingExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    completed.countDown();
                }
            });
        }
    }
}
