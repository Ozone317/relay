package com.example.relay.deliveryengine.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.attempt.infrastructure.ReadyWorkRepository;
import com.example.relay.attempt.infrastructure.ReadyWorkRepositoryImpl;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReadyWorkRepositoryImpl.class)
class RetrySchedulerPostgresTest implements SharedPostgresContainer {

    @org.springframework.beans.factory.annotation.Autowired
    private ReadyWorkRepository readyWorkRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private AttemptRepository attemptRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private TestEntityManager testEntityManager;

    private App app;
    private Message message;
    private int endpointSequence;

    @BeforeEach
    void setUp() throws Exception {
        User user = new User("retry-scheduler-" + UUID.randomUUID() + "@mail.com", "hash");
        Environment environment = new Environment("Environment", "Description", user);
        app = new App("App", environment);
        Event event = new Event("retry-scheduler." + UUID.randomUUID(), app);
        message = new Message(app, event, new ObjectMapper().readTree("{\"name\":\"hello\"}"));

        testEntityManager.persistAndFlush(user);
        testEntityManager.persistAndFlush(environment);
        testEntityManager.persistAndFlush(app);
        testEntityManager.persistAndFlush(event);
        testEntityManager.persistAndFlush(message);
    }

    @Test
    void releaseDueRetriesPromotesOnlyRowsDueInPostgres() {
        Attempt due = scheduledAttempt(Instant.now().minusSeconds(5));
        Attempt future = scheduledAttempt(Instant.now().plusSeconds(60));
        setReadyFields(due);
        testEntityManager.flush();

        new RetryScheduler(readyWorkRepository, validProperties()).releaseDueRetries();

        Attempt promoted = reload(due);
        assertThat(promoted.getStatus()).isEqualTo(AttemptStatus.CREATED);
        assertThat(promoted.getReadyPublishedAt()).isNull();
        assertThat(promoted.getReadyDispatchClaimId()).isNull();
        assertThat(promoted.getReadyDispatchClaimedAt()).isNull();
        assertThat(reload(future).getStatus()).isEqualTo(AttemptStatus.SCHEDULED);
    }

    @Test
    void concurrentSchedulersPromoteDisjointBatches() throws Exception {
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            attempts.add(scheduledAttempt(Instant.now().minusSeconds(5)));
        }
        testEntityManager.flush();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        RetryProperties properties = validProperties();
        properties.setSchedulerBatchSize(10);
        RecordingReadyWorkRepository firstRepository = new RecordingReadyWorkRepository(readyWorkRepository);
        RecordingReadyWorkRepository secondRepository = new RecordingReadyWorkRepository(readyWorkRepository);
        RetryScheduler first = new RetryScheduler(firstRepository, properties);
        RetryScheduler second = new RetryScheduler(secondRepository, properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = executor.submit(first::releaseDueRetries);
            Future<?> secondRun = executor.submit(second::releaseDueRetries);
            firstRun.get();
            secondRun.get();
        } finally {
            executor.shutdownNow();
        }

        Set<UUID> firstPromoted = new HashSet<>(firstRepository.promotedIds());
        Set<UUID> secondPromoted = new HashSet<>(secondRepository.promotedIds());
        assertThat(firstPromoted).doesNotContainAnyElementsOf(secondPromoted);
        Set<UUID> allPromoted = new HashSet<>(firstPromoted);
        allPromoted.addAll(secondPromoted);
        assertThat(allPromoted).hasSize(20);

        Set<UUID> promoted = new HashSet<>();
        for (Attempt attempt : attempts) {
            if (reloadAfterCommit(attempt).getStatus() == AttemptStatus.CREATED) {
                promoted.add(attempt.getId());
            }
        }
        assertThat(promoted).hasSize(20);
    }

    private RetryProperties validProperties() {
        RetryProperties properties = new RetryProperties();
        properties.validate();
        return properties;
    }

    private Attempt scheduledAttempt(Instant nextRetryAt) {
        Endpoint endpoint = new Endpoint("endpoint-" + endpointSequence,
                "https://example.com/" + endpointSequence, "whsec_" + endpointSequence, app);
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 2);
        attempt.setStatus(AttemptStatus.SCHEDULED);
        attempt.setNextRetryAt(nextRetryAt);
        endpointSequence++;

        testEntityManager.persistAndFlush(endpoint);
        testEntityManager.persistAndFlush(delivery);
        testEntityManager.persistAndFlush(attempt);
        return attempt;
    }

    private void setReadyFields(Attempt attempt) {
        attempt.setReadyPublishedAt(Instant.now());
        attempt.setReadyDispatchClaimId(UUID.randomUUID());
        attempt.setReadyDispatchClaimedAt(Instant.now());
    }

    private Attempt reload(Attempt attempt) {
        testEntityManager.clear();
        return attemptRepository.findById(attempt.getId()).orElseThrow();
    }

    private Attempt reloadAfterCommit(Attempt attempt) {
        return attemptRepository.findById(attempt.getId()).orElseThrow();
    }

    private static final class RecordingReadyWorkRepository implements ReadyWorkRepository {

        private final ReadyWorkRepository delegate;
        private volatile List<UUID> promoted = List.of();

        private RecordingReadyWorkRepository(ReadyWorkRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<UUID> promoteDueScheduled(int batchSize) {
            promoted = delegate.promoteDueScheduled(batchSize);
            return promoted;
        }

        @Override
        public List<UUID> claimUnpublishedReady(UUID claimId, Duration grace, int batchSize) {
            return delegate.claimUnpublishedReady(claimId, grace, batchSize);
        }

        @Override
        public int markReadyPublished(UUID attemptId, UUID claimId) {
            return delegate.markReadyPublished(attemptId, claimId);
        }

        private List<UUID> promotedIds() {
            return promoted;
        }
    }
}
