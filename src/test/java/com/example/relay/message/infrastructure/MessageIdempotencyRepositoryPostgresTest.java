package com.example.relay.message.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.message.api.MessageIdempotencyKey;
import com.example.relay.support.SharedPostgresContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringJUnitConfig(MessageIdempotencyRepositoryPostgresTest.Config.class)
class MessageIdempotencyRepositoryPostgresTest implements SharedPostgresContainer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA = "p07_repository_" + UUID.randomUUID().toString().replace("-", "");
    private static final DataSource DATA_SOURCE = createDataSource();

    @Autowired
    private MessageIdempotencyRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private Fixture fixture;

    private static DataSource createDataSource() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA).defaultSchema(SCHEMA).locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName(POSTGRES.getDriverClassName());
        source.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + SCHEMA);
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    @AfterAll
    static void dropSchema() {
        new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @BeforeEach
    void setUp() {
        fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id,email,password,email_verified) VALUES (?, ?, 'hash', true)",
                    fixture.userId(), fixture.email());
            jdbc.update(
                    "INSERT INTO environments(id,user_id,name,description,created_at,updated_at) "
                            + "VALUES (?, ?, 'env', 'desc', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    fixture.environmentId(), fixture.userId());
            jdbc.update(
                    "INSERT INTO apps(id,name,environment_id,created_at) " + "VALUES (?, 'app', ?, CURRENT_TIMESTAMP)",
                    fixture.appId(), fixture.environmentId());
            jdbc.update("INSERT INTO events(id,name,app_id,created_at) " + "VALUES (?, 'event', ?, CURRENT_TIMESTAMP)",
                    fixture.eventId(), fixture.appId());
        });
    }

    @Test
    void acquiresIdentityBeforeMessageAndComparesCommittedJsonbFingerprint() throws Exception {
        UUID messageId = UUID.randomUUID();
        String bodyText = "{ \"a\": 1, \"b\": 2 }";
        JsonNode body = MAPPER.readTree(bodyText);
        MessageIdempotencyAcquisition acquired = transactions.execute(status -> {
            MessageIdempotencyAcquisition identity = repository
                    .tryAcquire(fixture.userId(), fixture.appId(), new MessageIdempotencyKey("happy"), messageId)
                    .orElseThrow();
            assertThat(identity.messageId()).isEqualTo(messageId);
            jdbc.update(
                    "INSERT INTO messages(id,app_id,event_id,body,created_at) "
                            + "VALUES (?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)",
                    messageId, fixture.appId(), fixture.eventId(), bodyText);
            return identity;
        });

        assertThat(acquired.acceptedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT accepted_at FROM message_idempotency WHERE message_id = ?",
                Instant.class, messageId)).isEqualTo(acquired.acceptedAt());
        assertThat(jdbc.queryForObject("SELECT fingerprint_version FROM message_idempotency WHERE message_id = ?",
                Short.class, messageId)).isEqualTo((short) 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_idempotency WHERE message_id = ?", Integer.class,
                messageId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT i.app_id = m.app_id AS app_matches, i.user_id = e.user_id AS user_matches "
                + "FROM message_idempotency i JOIN messages m ON m.id = i.message_id "
                + "JOIN apps a ON a.id = i.app_id JOIN environments e ON e.id = a.environment_id "
                + "WHERE i.message_id = ?", messageId)).containsEntry("app_matches", true)
                .containsEntry("user_matches", true);

        CommittedMessageIdempotency committed =
                transactions.execute(status -> repository.findCommittedAndCompare(fixture.userId(), fixture.appId(),
                        new MessageIdempotencyKey("happy"), fixture.eventId(), json("{\"b\":2.0,\"a\":1}")));
        assertThat(committed.messageId()).isEqualTo(messageId);
        assertThat(committed.acceptedAt()).isEqualTo(acquired.acceptedAt());
        assertThat(committed.fingerprintVersion()).isEqualTo((short) 1);
        assertThat(committed.fingerprintMatches()).isTrue();
    }

    @Test
    void comparesFingerprintUsingPostgresJsonbAndEventIdentity() throws Exception {
        UUID messageId = acquireAndInsert("semantic", fixture.appId(), fixture.eventId(), "[1,2]");
        assertMatch("semantic", fixture.appId(), fixture.eventId(), "[1,2]", true);
        assertMatch("semantic", fixture.appId(), fixture.eventId(), "[2,1]", false);
        assertMatch("semantic", fixture.appId(), fixture.eventId(), "[\"1\",2]", false);
        assertMatch("semantic", fixture.appId(), UUID.randomUUID(), "[1,2]", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE id = ?", Integer.class, messageId))
                .isEqualTo(1);
    }

    @Test
    void scopeAndKeyComparisonAreIndependentAndCaseSensitive() {
        String body = "{}";
        UUID otherApp = UUID.randomUUID();
        UUID otherEnvironment = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO environments(id,user_id,name,description,created_at,updated_at) "
                            + "VALUES (?, ?, 'other', 'desc', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    otherEnvironment, fixture.userId());
            jdbc.update("INSERT INTO apps(id,name,environment_id,created_at) VALUES (?, 'other', ?, CURRENT_TIMESTAMP)",
                    otherApp, otherEnvironment);
            jdbc.update("INSERT INTO events(id,name,app_id,created_at) VALUES (?, 'other', ?, CURRENT_TIMESTAMP)",
                    otherEvent, otherApp);
        });
        UUID lowerMessage = acquireAndInsert("CaseKey", fixture.appId(), fixture.eventId(), body);
        UUID upperMessage = acquireAndInsert("casekey", fixture.appId(), fixture.eventId(), body);
        UUID otherAppMessage = acquireAndInsert("CaseKey", otherApp, otherEvent, body);

        assertThat(jdbc.queryForList("SELECT message_id FROM message_idempotency WHERE idempotency_key = 'CaseKey'",
                UUID.class)).containsExactlyInAnyOrder(lowerMessage, otherAppMessage);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_idempotency WHERE message_id = ?", Integer.class,
                upperMessage)).isEqualTo(1);
    }

    @Test
    void loserWaitsForWinnerCommitThenReturnsCommittedAuthority() throws Exception {
        observeWinnerResolution(true);
    }

    @Test
    void loserAcquiresAuthorityAfterWinnerRollback() throws Exception {
        observeWinnerResolution(false);
    }

    private void observeWinnerResolution(boolean commitWinner) throws Exception {
        String key = "contended-" + UUID.randomUUID();
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        CountDownLatch loserStarted = new CountDownLatch(1);
        AtomicInteger winnerPid = new AtomicInteger();
        AtomicInteger loserPid = new AtomicInteger();
        AtomicReference<MessageIdempotencyAcquisition> winnerAuthority = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = executor.submit(() -> transactions.executeWithoutResult(status -> {
                winnerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                MessageIdempotencyAcquisition authority = repository.tryAcquire(fixture.userId(), fixture.appId(),
                        new MessageIdempotencyKey(key), UUID.randomUUID()).orElseThrow();
                winnerAuthority.set(authority);
                insertMessage(authority.messageId(), fixture.appId(), fixture.eventId(), "{}");
                winnerInserted.countDown();
                await(releaseWinner);
                if (!commitWinner) {
                    status.setRollbackOnly();
                }
            }));
            await(winnerInserted);
            Future<Optional<MessageIdempotencyAcquisition>> loser =
                    executor.submit(() -> transactions.execute(status -> {
                        loserPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        loserStarted.countDown();
                        Optional<MessageIdempotencyAcquisition> acquisition = repository.tryAcquire(fixture.userId(),
                                fixture.appId(), new MessageIdempotencyKey(key), UUID.randomUUID());
                        if (commitWinner && acquisition.isEmpty()) {
                            MessageIdempotencyAcquisition winnerRecord = winnerAuthority.get();
                            CommittedMessageIdempotency committed = repository.findCommittedAndCompare(fixture.userId(),
                                    fixture.appId(), new MessageIdempotencyKey(key), fixture.eventId(), json("{}"));
                            assertThat(committed.messageId()).isEqualTo(winnerRecord.messageId());
                            assertThat(committed.acceptedAt()).isEqualTo(winnerRecord.acceptedAt());
                            assertThat(committed.fingerprintMatches()).isTrue();
                        }
                        if (acquisition.isPresent()) {
                            insertMessage(acquisition.get().messageId(), fixture.appId(), fixture.eventId(), "{}");
                        }
                        return acquisition;
                    }));
            await(loserStarted);
            assertThat(loserPid.get()).isNotEqualTo(winnerPid.get());
            awaitTransactionIdBlocker(loserPid.get(), winnerPid.get());
            releaseWinner.countDown();
            winner.get(10, TimeUnit.SECONDS);
            Optional<MessageIdempotencyAcquisition> loserResult = loser.get(10, TimeUnit.SECONDS);
            if (commitWinner) {
                System.out.printf("Winner resolution: committed; loser returned empty for key=%s%n", key);
                assertThat(loserResult).isEmpty();
            } else {
                System.out.printf("Winner resolution: rolled back; loser acquired authority for key=%s%n", key);
                assertThat(loserResult).isPresent();
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM message_idempotency WHERE user_id = ? "
                                + "AND app_id = ? AND idempotency_key = ?",
                        Integer.class, fixture.userId(), fixture.appId(), key)).isEqualTo(1);
            }
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void awaitTransactionIdBlocker(int waiterPid, int expectedBlockerPid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var activity =
                    jdbc.queryForMap("SELECT query, wait_event_type, wait_event, pg_blocking_pids(pid) AS blockers, "
                            + "? = ANY(pg_blocking_pids(pid)) AS blocked_by_expected "
                            + "FROM pg_stat_activity WHERE pid = ?", expectedBlockerPid, waiterPid);
            if (Boolean.TRUE.equals(activity.get("blocked_by_expected"))) {
                assertThat(activity.get("wait_event_type")).isEqualTo("Lock");
                assertThat(activity.get("wait_event")).isEqualTo("transactionid");
                assertThat(activity.get("query").toString()).contains("INSERT INTO message_idempotency");
                System.out.printf(
                        "Observed idempotency insert blocker: loser_pid=%d winner_pid=%d "
                                + "wait_event_type=%s wait_event=%s blockers=%s query=%s%n",
                        waiterPid, expectedBlockerPid, activity.get("wait_event_type"), activity.get("wait_event"),
                        activity.get("blockers"), activity.get("query"));
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("loser did not observe winner transaction-ID blocker within timeout");
    }

    private UUID acquireAndInsert(String key, UUID appId, UUID eventId, String body) {
        return transactions.execute(status -> {
            UUID messageId = UUID.randomUUID();
            MessageIdempotencyAcquisition acquisition = repository
                    .tryAcquire(fixture.userId(), appId, new MessageIdempotencyKey(key), messageId).orElseThrow();
            insertMessage(acquisition.messageId(), appId, eventId, body);
            return acquisition.messageId();
        });
    }

    private void assertMatch(String key, UUID appId, UUID eventId, String body, boolean matches) throws Exception {
        CommittedMessageIdempotency result = transactions.execute(status -> repository
                .findCommittedAndCompare(fixture.userId(), appId, new MessageIdempotencyKey(key), eventId, json(body)));
        assertThat(result.fingerprintMatches()).isEqualTo(matches);
    }

    private void insertMessage(UUID messageId, UUID appId, UUID eventId, String body) {
        jdbc.update("INSERT INTO messages(id,app_id,event_id,body,created_at) "
                + "VALUES (?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)", messageId, appId, eventId, body);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "transaction synchronization timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting transaction synchronization", exception);
        }
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid JSON fixture", exception);
        }
    }

    private record Fixture(UUID userId, UUID environmentId, UUID appId, UUID eventId) {
        String email() {
            return "idempotency-" + userId + "@example.test";
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @Import(MessageIdempotencyRepository.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return DATA_SOURCE;
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource source) {
            return new NamedParameterJdbcTemplate(source);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
