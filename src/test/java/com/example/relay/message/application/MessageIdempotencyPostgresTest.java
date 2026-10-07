package com.example.relay.message.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.app.domain.App;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.event.domain.Event;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.message.api.MessageIdempotencyKey;
import com.example.relay.message.api.dto.MessageCreateDto;
import com.example.relay.message.domain.Message;
import com.example.relay.message.exception.IdempotencyConflictException;
import com.example.relay.message.infrastructure.CommittedMessageIdempotency;
import com.example.relay.message.infrastructure.MessageIdempotencyAcquisition;
import com.example.relay.message.infrastructure.MessageIdempotencyRepository;
import com.example.relay.message.infrastructure.MessageRepository;
import com.example.relay.message.mapper.MessageMapper;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.infrastructure.SubscriptionRepository;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end PostgreSQL evidence for idempotent message admission and its HTTP contract. */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class MessageIdempotencyPostgresTest implements SharedPostgresContainer {

    private static final String BODY = "{\"amount\":1}";

    @Autowired
    private MessageService service;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EnvironmentRepository environmentRepository;
    @Autowired
    private AppRepository appRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EndpointRepository endpointRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private MessageRepository messageRepository;
    @MockitoSpyBean
    private MessageIdempotencyRepository idempotencyRepository;
    @MockitoSpyBean
    private MessageMapper messageMapper;

    private final List<UUID> fixtureUsers = new ArrayList<>();

    @BeforeEach
    void resetSpies() {
        reset(idempotencyRepository, messageMapper);
    }

    @AfterEach
    void cleanFixtures() {
        for (UUID userId : fixtureUsers) {
            jdbc.update("DELETE FROM attempts WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                    + "ON e.id = a.environment_id WHERE e.user_id = ?)", userId);
            jdbc.update("DELETE FROM deliveries WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                    + "ON e.id = a.environment_id WHERE e.user_id = ?)", userId);
            jdbc.update("DELETE FROM message_idempotency WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM message_idempotency WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                    + "ON e.id = a.environment_id WHERE e.user_id = ?)", userId);
            jdbc.update("DELETE FROM messages WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                    + "ON e.id = a.environment_id WHERE e.user_id = ?)", userId);
            jdbc.update("DELETE FROM subscriptions WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                    + "ON e.id = a.environment_id WHERE e.user_id = ?)", userId);
            jdbc.update("DELETE FROM endpoints WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                    + "ON e.id = a.environment_id WHERE e.user_id = ?)", userId);
            jdbc.update("DELETE FROM events WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                    + "ON e.id = a.environment_id WHERE e.user_id = ?)", userId);
            jdbc.update("DELETE FROM apps WHERE environment_id IN (SELECT id FROM environments WHERE user_id = ?)",
                    userId);
            jdbc.update("DELETE FROM environments WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM email_verification_tokens WHERE user_id = ?", userId);
            userRepository.deleteById(userId);
        }
        fixtureUsers.clear();
    }

    @Test
    void sequentialRetriesResponseLossAndKeylessCallsHaveExactThreeSubscriberCardinality() throws Exception {
        Fixture fixture = fixture(3);
        MessageCreateDto request = request(fixture.event());
        MessageIdempotencyKey key = new MessageIdempotencyKey("stable-" + UUID.randomUUID());

        Message first = service.create(request, Optional.of(key), fixture.app().getId(), fixture.environment().getId(),
                fixture.user().getId()).message();
        assertGraph(fixture, key, first.getId(), 1, 3, 3);

        for (int i = 0; i < 5; i++) {
            Message replay = service.create(request, Optional.of(key), fixture.app().getId(),
                    fixture.environment().getId(), fixture.user().getId()).message();
            assertThat(replay.getId()).isEqualTo(first.getId());
        }
        assertGraph(fixture, key, first.getId(), 1, 3, 3);

        // Model a lost response: discard the first result and retry in a distinct service transaction.
        Message recovered = service.create(request, Optional.of(key), fixture.app().getId(),
                fixture.environment().getId(), fixture.user().getId()).message();
        assertThat(recovered.getId()).isEqualTo(first.getId());
        assertGraph(fixture, key, first.getId(), 1, 3, 3);

        Fixture keylessFixture = fixture(3);
        MessageCreateDto keylessRequest = request(keylessFixture.event());
        service.create(keylessRequest, Optional.empty(), keylessFixture.app().getId(),
                keylessFixture.environment().getId(), keylessFixture.user().getId());
        service.create(keylessRequest, Optional.empty(), keylessFixture.app().getId(),
                keylessFixture.environment().getId(), keylessFixture.user().getId());
        assertCounts(keylessFixture, 0, 2, 6, 6);
    }

    @Test
    void agedAuthorityStillReplaysOriginalMessageWithoutExpiryOrCleanupContract() throws Exception {
        Fixture fixture = fixture(3);
        MessageCreateDto request = request(fixture.event());
        MessageIdempotencyKey key = new MessageIdempotencyKey("aged-" + UUID.randomUUID());
        Message accepted = service.create(request, Optional.of(key), fixture.app().getId(),
                fixture.environment().getId(), fixture.user().getId()).message();
        assertGraph(fixture, key, accepted.getId(), 1, 3, 3);

        Instant agedAt = Instant.parse("2000-01-01T00:00:00Z");
        Timestamp agedTimestamp = Timestamp.from(agedAt);
        jdbc.update("UPDATE messages SET created_at = ? WHERE id = ?", agedTimestamp, accepted.getId());
        jdbc.update(
                "UPDATE message_idempotency SET accepted_at = ? "
                        + "WHERE user_id = ? AND app_id = ? AND idempotency_key = ?",
                agedTimestamp, fixture.user().getId(), fixture.app().getId(), key.value());

        assertEquals(1L,
                scalar("SELECT COUNT(*) FROM message_idempotency i JOIN messages m ON m.id=i.message_id "
                        + "WHERE i.user_id=? AND i.app_id=? AND i.idempotency_key=? "
                        + "AND i.accepted_at=? AND m.created_at=?", fixture.user().getId(), fixture.app().getId(),
                        key.value(), agedTimestamp, agedTimestamp));
        assertEquals(0L,
                scalar("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'message_idempotency' "
                        + "AND (column_name ILIKE '%expir%' OR column_name ILIKE '%delet%' "
                        + "OR column_name ILIKE '%cleanup%')"));

        Message replay = service.create(request, Optional.of(key), fixture.app().getId(), fixture.environment().getId(),
                fixture.user().getId()).message();

        assertEquals(accepted.getId(), replay.getId());
        assertEquals(agedAt, replay.getCreatedAt());
        assertGraph(fixture, key, accepted.getId(), 1, 3, 3);
    }

    @Test
    void sameKeyScopesByAppAndDurableUserIdRatherThanPresentationCredentials() throws Exception {
        Fixture fixture = fixture(1);
        App secondApp = app(fixture.environment(), "second-app");
        Event secondEvent = event(secondApp);
        subscribe(secondApp, secondEvent, 1);
        App thirdApp = app(newEnvironment(fixture.user(), "second-env"), "third-app");
        Event thirdEvent = event(thirdApp);
        subscribe(thirdApp, thirdEvent, 1);
        MessageIdempotencyKey key = new MessageIdempotencyKey("same-scope-key");

        Message first = service.create(request(fixture.event()), Optional.of(key), fixture.app().getId(),
                fixture.environment().getId(), fixture.user().getId()).message();
        Message sameCredentialsDifferentApp = service.create(request(secondEvent), Optional.of(key), secondApp.getId(),
                fixture.environment().getId(), fixture.user().getId()).message();
        Message otherEnvironment = service.create(request(thirdEvent), Optional.of(key), thirdApp.getId(),
                thirdApp.getEnvironment().getId(), fixture.user().getId()).message();
        Message sameDurableIdentity = service.create(request(fixture.event()), Optional.of(key), fixture.app().getId(),
                fixture.environment().getId(), fixture.user().getId()).message();

        assertThat(sameCredentialsDifferentApp.getId()).isNotEqualTo(first.getId());
        assertThat(otherEnvironment.getId()).isNotEqualTo(first.getId());
        assertThat(sameDurableIdentity.getId()).isEqualTo(first.getId());
        assertGraph(fixture, key, first.getId(), 1, 1, 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_idempotency WHERE user_id = ? AND idempotency_key = ?", Long.class,
                fixture.user().getId(), key.value())).isEqualTo(3L);

        Authentication alternatePresentation = principalAuth(fixture.user().getId(), "renamed@example.test");
        mockMvc.perform(post(path(fixture)).with(authentication(alternatePresentation))
                .header("Idempotency-Key", key.value()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(fixture.event())))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first.getId().toString()));
    }

    @Test
    void concurrentSameKeyObservesReadCommittedTransactionsAndCommittedWinner() throws Exception {
        runContended(false, false);
    }

    @Test
    void concurrentDifferentBodyObservesBlockerThenReturnsConflict() throws Exception {
        runContended(true, false);
    }

    @Test
    void contenderTakesOverAfterWinnerFanoutRollsBack() throws Exception {
        runContended(false, true);
    }

    private void runContended(boolean conflictingBody, boolean rollbackOwner) throws Exception {
        Fixture fixture = fixture(3);
        String value = "race-" + UUID.randomUUID();
        MessageIdempotencyKey key = new MessageIdempotencyKey(value);
        MessageCreateDto ownerRequest = request(fixture.event());
        MessageCreateDto contenderRequest =
                conflictingBody ? new MessageCreateDto(fixture.event().getId(), objectMapper.readTree("{\"amount\":2}"))
                        : ownerRequest;
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger ownerPid = new AtomicInteger();
        AtomicInteger contenderPid = new AtomicInteger();
        AtomicReference<String> ownerIsolation = new AtomicReference<>();
        AtomicReference<String> contenderIsolation = new AtomicReference<>();
        AtomicReference<Optional<MessageIdempotencyAcquisition>> ownerAcquisition = new AtomicReference<>();
        AtomicReference<Optional<MessageIdempotencyAcquisition>> contenderAcquisition = new AtomicReference<>();
        AtomicReference<CommittedMessageIdempotency> committedReplay = new AtomicReference<>();
        CountDownLatch ownerAcquired = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch contenderEnteringInsert = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        MessageIdempotencyRepository repositoryTarget = AopTestUtils.getUltimateTargetObject(idempotencyRepository);
        doAnswer(invocation -> {
            boolean owner = calls.incrementAndGet() == 1;
            String isolation = jdbc.queryForObject("SHOW transaction_isolation", String.class);
            int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertThat(isolation).isEqualTo("read committed");
            if (owner) {
                ownerIsolation.set(isolation);
                ownerPid.set(pid);
            } else {
                contenderIsolation.set(isolation);
                contenderPid.set(pid);
                contenderEnteringInsert.countDown();
            }
            @SuppressWarnings("unchecked")
            Optional<MessageIdempotencyAcquisition> acquired =
                    (Optional<MessageIdempotencyAcquisition>) invocation.callRealMethod();
            if (owner) {
                ownerAcquisition.set(acquired);
                assertThat(acquired).isPresent();
                ownerAcquired.countDown();
                await(releaseOwner);
            } else {
                contenderAcquisition.set(acquired);
            }
            return acquired;
        }).when(repositoryTarget).tryAcquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            CommittedMessageIdempotency result = (CommittedMessageIdempotency) invocation.callRealMethod();
            committedReplay.set(result);
            return result;
        }).when(repositoryTarget).findCommittedAndCompare(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        if (rollbackOwner) {
            AtomicInteger failuresLeft = new AtomicInteger(1);
            doAnswer(invocation -> {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new RuntimeException("injected message fanout conversion failure");
                }
                return invocation.callRealMethod();
            }).when(messageMapper).toEntity(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }
        try {
            Future<UUID> owner = executor.submit(() -> service.create(ownerRequest, Optional.of(key),
                    fixture.app().getId(), fixture.environment().getId(), fixture.user().getId()).message().getId());
            await(ownerAcquired);
            Future<UUID> contender = executor.submit(() -> service.create(contenderRequest, Optional.of(key),
                    fixture.app().getId(), fixture.environment().getId(), fixture.user().getId()).message().getId());
            await(contenderEnteringInsert);
            assertThat(contenderPid.get()).isNotEqualTo(ownerPid.get());
            assertBlocking(ownerPid.get(), contenderPid.get());
            releaseOwner.countDown();

            if (rollbackOwner) {
                var ownerFailure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> owner.get(10, TimeUnit.SECONDS));
                assertThat(ownerFailure.getCause()).hasMessage("injected message fanout conversion failure");
                UUID takeoverId = contender.get(10, TimeUnit.SECONDS);
                assertThat(ownerAcquisition.get()).isPresent();
                assertThat(contenderAcquisition.get()).isPresent();
                assertThat(committedReplay.get()).isNull();
                assertThat(takeoverId).isNotNull();
                assertThat(takeoverId).isNotEqualTo(ownerAcquisition.get().orElseThrow().messageId());
                assertGraph(fixture, key, takeoverId, 1, 3, 3);
            } else if (conflictingBody) {
                owner.get(10, TimeUnit.SECONDS);
                var failure = org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.ExecutionException.class, () -> contender.get(10, TimeUnit.SECONDS));
                assertThat(failure.getCause()).isInstanceOf(IdempotencyConflictException.class);
                assertThat(contenderAcquisition.get()).isEmpty();
                assertThat(committedReplay.get().fingerprintMatches()).isFalse();
                UUID messageId = jdbc.queryForObject(
                        "SELECT message_id FROM message_idempotency "
                                + "WHERE user_id = ? AND app_id = ? AND idempotency_key = ?",
                        UUID.class, fixture.user().getId(), fixture.app().getId(), key.value());
                assertGraph(fixture, key, messageId, 1, 3, 3);
            } else {
                UUID first = owner.get(10, TimeUnit.SECONDS);
                UUID second = contender.get(10, TimeUnit.SECONDS);
                assertThat(second).isEqualTo(first);
                assertThat(ownerAcquisition.get()).isPresent();
                assertThat(contenderAcquisition.get()).isEmpty();
                assertThat(committedReplay.get().messageId()).isEqualTo(first);
                assertThat(committedReplay.get().fingerprintMatches()).isTrue();
                assertGraph(fixture, key, first, 1, 3, 3);
            }
            assertThat(ownerIsolation.get()).isEqualTo("read committed");
            assertThat(contenderIsolation.get()).isEqualTo("read committed");
            System.out.printf(
                    "Service admission isolation: owner=%s pid=%d; contender=%s pid=%d; rollback=%s conflict=%s%n",
                    ownerIsolation.get(), ownerPid.get(), contenderIsolation.get(), contenderPid.get(), rollbackOwner,
                    conflictingBody);
        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            reset(idempotencyRepository, messageMapper);
        }
    }

    @Test
    void authorizationAndNotFoundPrecedeIdempotencyLookupAndNoSubscriberRetryCanSucceed() throws Exception {
        Fixture fixture = fixture(0);
        MessageCreateDto request = request(fixture.event());
        MessageIdempotencyKey key = new MessageIdempotencyKey("later-subscriber-" + UUID.randomUUID());
        mockMvc.perform(post(path(fixture))
                .with(authentication(principalAuth(fixture.user().getId(), fixture.user().getEmail())))
                .header("Idempotency-Key", key.value()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM message_idempotency WHERE idempotency_key = ?", key.value()));
        subscribe(fixture.app(), fixture.event(), 1);

        mockMvc.perform(post(path(fixture))
                .with(authentication(principalAuth(fixture.user().getId(), fixture.user().getEmail())))
                .header("Idempotency-Key", key.value()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isCreated());

        MessageCreateDto missingEventRequest = new MessageCreateDto(UUID.randomUUID(), request.body());
        reset(idempotencyRepository);
        mockMvc.perform(post(path(fixture))
                .with(authentication(principalAuth(fixture.user().getId(), fixture.user().getEmail())))
                .header("Idempotency-Key", key.value()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(missingEventRequest))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        verify(idempotencyRepository, never()).tryAcquire(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        User other = user("unrelated");
        Authentication unauthorizedOwner = principalAuth(other.getId(), other.getEmail());
        reset(idempotencyRepository);
        mockMvc.perform(
                post(path(fixture)).with(authentication(unauthorizedOwner)).header("Idempotency-Key", key.value())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        verify(idempotencyRepository, never()).tryAcquire(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        mockMvc.perform(post(path(fixture)).header("Idempotency-Key", key.value())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        assertEquals(1L, scalar(
                "SELECT COUNT(*) FROM message_idempotency WHERE user_id = ? AND app_id = ? AND idempotency_key = ?",
                fixture.user().getId(), fixture.app().getId(), key.value()));
    }

    @Test
    void httpReplayAndMismatchExposeStableSuccessAndCurrentApiErrorShape() throws Exception {
        Fixture fixture = fixture(1);
        MessageCreateDto request = request(fixture.event());
        String key = "http-" + UUID.randomUUID();
        var first = mockMvc
                .perform(post(path(fixture))
                        .with(authentication(principalAuth(fixture.user().getId(), fixture.user().getEmail())))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var replay = mockMvc
                .perform(post(path(fixture))
                        .with(authentication(principalAuth(fixture.user().getId(), fixture.user().getEmail())))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var firstJson = objectMapper.readTree(first.getContentAsByteArray());
        var replayJson = objectMapper.readTree(replay.getContentAsByteArray());
        assertThat(replayJson.get("id")).isEqualTo(firstJson.get("id"));
        assertThat(replayJson.get("eventId")).isEqualTo(firstJson.get("eventId"));
        assertThat(replayJson.get("eventName")).isEqualTo(firstJson.get("eventName"));
        assertThat(replayJson.get("body")).isEqualTo(firstJson.get("body"));
        assertThat(replayJson.get("createdAt")).isEqualTo(firstJson.get("createdAt"));

        MessageCreateDto mismatch =
                new MessageCreateDto(fixture.event().getId(), objectMapper.readTree("{\"amount\":2}"));
        mockMvc.perform(post(path(fixture))
                .with(authentication(principalAuth(fixture.user().getId(), fixture.user().getEmail())))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mismatch))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key is already associated with a different message request"))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void validForeignKeyAuthorityCorruptionIsAuditedAndReplayFailsLoudly() throws Exception {
        Fixture fixture = fixture(1);
        App appB = app(fixture.environment(), "corrupt-app-b");
        Event eventB = event(appB);
        subscribe(appB, eventB, 1);
        UUID messageId = UUID.randomUUID();
        String key = "corrupt-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO messages(id, app_id, event_id, body, created_at) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                messageId, fixture.app().getId(), eventB.getId(), BODY, Timestamp.from(Instant.now()));
        jdbc.update(
                "INSERT INTO message_idempotency(user_id, app_id, idempotency_key, fingerprint_version, message_id, accepted_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?)",
                fixture.user().getId(), appB.getId(), key, messageId, Timestamp.from(Instant.now()));
        assertThat(scalar(
                "SELECT COUNT(*) FROM message_idempotency i JOIN messages m ON m.id=i.message_id "
                        + "WHERE i.user_id=? AND i.app_id=? AND i.message_id=? AND i.app_id <> m.app_id",
                fixture.user().getId(), appB.getId(), messageId)).isEqualTo(1L);
        assertThrows(IllegalStateException.class,
                () -> service.create(request(eventB), Optional.of(new MessageIdempotencyKey(key)), appB.getId(),
                        fixture.environment().getId(), fixture.user().getId()));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM messages WHERE app_id = ?", fixture.app().getId()));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM messages WHERE app_id = ?", appB.getId()));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM deliveries WHERE app_id = ?", appB.getId()));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM attempts WHERE app_id = ?", appB.getId()));

        User wrongAuthorityUser = user("corrupt-authority");
        UUID secondMessageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO messages(id, app_id, event_id, body, created_at) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                secondMessageId, fixture.app().getId(), fixture.event().getId(), BODY, Timestamp.from(Instant.now()));
        jdbc.update(
                "INSERT INTO message_idempotency(user_id, app_id, idempotency_key, fingerprint_version, message_id, accepted_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?)",
                wrongAuthorityUser.getId(), fixture.app().getId(), "wrong-user-" + UUID.randomUUID(), secondMessageId,
                Timestamp.from(Instant.now()));
        assertThat(scalar(
                "SELECT COUNT(*) FROM message_idempotency i JOIN apps a ON a.id=i.app_id "
                        + "JOIN environments e ON e.id=a.environment_id "
                        + "WHERE i.user_id=? AND i.app_id=? AND i.message_id=? AND i.user_id <> e.user_id",
                wrongAuthorityUser.getId(), fixture.app().getId(), secondMessageId)).isEqualTo(1L);
    }

    private Fixture fixture(int subscribers) {
        User user = user("fixture");
        Environment environment = newEnvironment(user, "primary-env");
        App app = app(environment, "primary-app");
        Event event = event(app);
        subscribe(app, event, subscribers);
        return new Fixture(user, environment, app, event);
    }

    private User user(String prefix) {
        User user = userRepository.saveAndFlush(new User(prefix + "-" + UUID.randomUUID() + "@example.test", "hash"));
        fixtureUsers.add(user.getId());
        return user;
    }

    private Environment newEnvironment(User user, String suffix) {
        return environmentRepository
                .saveAndFlush(new Environment(suffix + "-" + UUID.randomUUID(), "description", user));
    }

    private App app(Environment environment, String suffix) {
        return appRepository.saveAndFlush(new App(suffix + "-" + UUID.randomUUID(), environment));
    }

    private Event event(App app) {
        return eventRepository.saveAndFlush(new Event("event-" + UUID.randomUUID(), app));
    }

    private void subscribe(App app, Event event, int count) {
        for (int i = 0; i < count; i++) {
            Endpoint endpoint = endpointRepository.saveAndFlush(new Endpoint("endpoint-" + UUID.randomUUID(),
                    "https://example.test/" + UUID.randomUUID(), "secret", app));
            subscriptionRepository.saveAndFlush(new Subscription(app, event, endpoint));
        }
    }

    private MessageCreateDto request(Event event) throws Exception {
        return new MessageCreateDto(event.getId(), objectMapper.readTree(BODY));
    }

    private void assertGraph(Fixture fixture, MessageIdempotencyKey key, UUID messageId, long messages, long deliveries,
            long attempts) {
        assertThat(scalar("SELECT COUNT(*) FROM messages WHERE app_id = ?", fixture.app().getId())).isEqualTo(messages);
        assertThat(scalar("SELECT COUNT(*) FROM deliveries WHERE app_id = ?", fixture.app().getId()))
                .isEqualTo(deliveries);
        assertThat(scalar("SELECT COUNT(*) FROM attempts WHERE app_id = ?", fixture.app().getId())).isEqualTo(attempts);
        assertThat(scalar("SELECT COUNT(*) FROM message_idempotency WHERE user_id = ? AND app_id = ?",
                fixture.user().getId(), fixture.app().getId())).isEqualTo(1L);
        assertThat(scalar(
                "SELECT COUNT(*) FROM message_idempotency WHERE user_id = ? AND app_id = ? "
                        + "AND idempotency_key = ? AND message_id = ?",
                fixture.user().getId(), fixture.app().getId(), key.value(), messageId)).isEqualTo(1L);
        assertThat(scalar("SELECT COUNT(*) FROM attempts WHERE message_id = ? AND "
                + "(attempt_no <> 1 OR status <> 'CREATED' OR execution_generation <> 0 "
                + "OR execution_claimed_at IS NOT NULL)", messageId)).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM attempts WHERE message_id = ?", messageId)).isEqualTo(attempts);
        assertThat(scalar("SELECT COUNT(*) FROM deliveries WHERE message_id = ?", messageId)).isEqualTo(deliveries);
        assertThat(scalar(
                "SELECT COUNT(*) FROM message_idempotency i JOIN messages m ON m.id=i.message_id "
                        + "JOIN apps a ON a.id=i.app_id JOIN environments e ON e.id=a.environment_id "
                        + "WHERE i.user_id=e.user_id AND i.app_id=m.app_id AND i.user_id=? AND i.app_id=? "
                        + "AND i.idempotency_key=? AND i.message_id=?",
                fixture.user().getId(), fixture.app().getId(), key.value(), messageId)).isEqualTo(1L);
        Message scoped = messageRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(messageId, fixture.app().getId(),
                fixture.environment().getId(), fixture.user().getId()).orElseThrow();
        assertThat(scoped.getId()).isEqualTo(messageId);
    }

    private void assertCounts(Fixture fixture, long identities, long messages, long deliveries, long attempts) {
        assertThat(scalar(
                "SELECT COUNT(*) FROM message_idempotency WHERE user_id IN " + "(SELECT id FROM users WHERE id = ?)",
                fixture.user().getId())).isEqualTo(identities);
        assertThat(scalar("SELECT COUNT(*) FROM messages WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                + "ON e.id=a.environment_id WHERE e.user_id=?)", fixture.user().getId())).isEqualTo(messages);
        assertThat(
                scalar("SELECT COUNT(*) FROM deliveries WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                        + "ON e.id=a.environment_id WHERE e.user_id=?)", fixture.user().getId()))
                .isEqualTo(deliveries);
        assertThat(scalar("SELECT COUNT(*) FROM attempts WHERE app_id IN (SELECT a.id FROM apps a JOIN environments e "
                + "ON e.id=a.environment_id WHERE e.user_id=?)", fixture.user().getId())).isEqualTo(attempts);
    }

    private long scalar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void assertBlocking(int ownerPid, int contenderPid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var row = jdbc.queryForMap("SELECT pg_blocking_pids(pid) AS blockers, "
                    + "? = ANY(pg_blocking_pids(pid)) AS blocked_by_expected, wait_event_type, wait_event "
                    + "FROM pg_stat_activity WHERE pid = ?", ownerPid, contenderPid);
            if (Boolean.TRUE.equals(row.get("blocked_by_expected"))) {
                assertThat(row.get("wait_event_type")).isEqualTo("Lock");
                assertThat(row.get("wait_event")).isEqualTo("transactionid");
                System.out.printf(
                        "Observed service insert blocker: owner_pid=%d contender_pid=%d blockers=%s wait=%s/%s%n",
                        ownerPid, contenderPid, row.get("blockers"), row.get("wait_event_type"), row.get("wait_event"));
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("service contender did not wait on the owner's transaction ID");
    }

    private Authentication principalAuth(UUID id, String email) {
        AuthenticatedUser principal = new AuthenticatedUser(id, email);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private String path(Fixture fixture) {
        return "/api/v1/environments/" + fixture.environment().getId() + "/apps/" + fixture.app().getId() + "/messages";
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "service transaction gate timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for service transaction gate", exception);
        }
    }

    private record Fixture(User user, Environment environment, App app, Event event) {
    }
}
