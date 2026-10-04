package com.example.relay.deliveryengine.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.relay.attempt.application.AttemptExecution;
import com.example.relay.attempt.application.AttemptMutationOutcome;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.deliveryengine.http.WebhookHttpResponse;
import com.example.relay.deliveryengine.http.WebhookHttpTransport;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import com.example.relay.deliveryengine.retry.RetryProperties;
import com.example.relay.deliveryengine.signing.HmacSigner;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.message.domain.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DeliveryWorkerOwnershipDiagnosticTest {
    private static final UUID ATTEMPT_ID = UUID.fromString("00000000-0000-0000-0000-000000000091");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000092");

    @Test
    void missingDiagnosticRow_afterOwnershipLoss_completesNormallyWithoutRetry() throws Exception {
        runLostSuccess(Optional.empty(), null, "missing");
    }

    @Test
    void failedDiagnosticRead_afterOwnershipLoss_completesNormallyWithoutRetry() throws Exception {
        runLostSuccess(null, new IllegalStateException("diagnostic database unavailable"), "unknown");
    }

    @Test
    void authoritativeCompletionException_remainsExceptional() throws Exception {
        AttemptService service = mock(AttemptService.class);
        AttemptRepository repository = mock(AttemptRepository.class);
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        AttemptExecution execution = execution();
        when(service.claim(ATTEMPT_ID)).thenReturn(Optional.of(execution));
        when(transport.post(any(), any(), any())).thenReturn(new WebhookHttpResponse(200, "ok"));
        when(service.markSucceeded(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("authoritative completion failed"));

        try (var executor = Executors.newSingleThreadExecutor()) {
            DeliveryWorker worker = worker(service, repository, transport, new SimpleMeterRegistry(), executor);
            assertThrows(CompletionException.class, () -> worker.onMessage(ATTEMPT_ID.toString()).join());
            verify(repository, never()).findById(any());
        }
    }

    @Test
    void claimException_remainsExceptional() {
        AttemptService service = mock(AttemptService.class);
        AttemptRepository repository = mock(AttemptRepository.class);
        when(service.claim(ATTEMPT_ID)).thenThrow(new IllegalStateException("claim failed"));

        try (var executor = Executors.newSingleThreadExecutor()) {
            DeliveryWorker worker = worker(service, repository, mock(WebhookHttpTransport.class),
                    new SimpleMeterRegistry(), executor);
            assertThrows(CompletionException.class, () -> worker.onMessage(ATTEMPT_ID.toString()).join());
            verify(repository, never()).findById(any());
        }
    }

    private void runLostSuccess(Optional<Attempt> diagnosticRow, RuntimeException diagnosticFailure,
            String expectedStatus) throws Exception {
        AttemptService service = mock(AttemptService.class);
        AttemptRepository repository = mock(AttemptRepository.class);
        WebhookHttpTransport transport = mock(WebhookHttpTransport.class);
        AttemptExecution execution = execution();
        when(service.claim(ATTEMPT_ID)).thenReturn(Optional.of(execution));
        when(transport.post(any(), any(), any())).thenReturn(new WebhookHttpResponse(200, "ok"));
        when(service.markSucceeded(any(), any(), any(), any())).thenReturn(AttemptMutationOutcome.OWNERSHIP_LOST);
        if (diagnosticFailure == null) {
            when(repository.findById(ATTEMPT_ID)).thenReturn(diagnosticRow);
        } else {
            when(repository.findById(ATTEMPT_ID)).thenThrow(diagnosticFailure);
        }
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Logger logger = (Logger) LoggerFactory.getLogger(DeliveryWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (var executor = Executors.newSingleThreadExecutor()) {
            DeliveryWorker worker = worker(service, repository, transport, meters, executor);
            assertDoesNotThrow(() -> worker.onMessage(ATTEMPT_ID.toString()).join());
            assertEquals(1.0, meters.get(ExecutionOwnershipMetrics.OWNERSHIP_LOST_COUNTER)
                    .tag("completion", "success").tag("current_status", expectedStatus).counter().count());
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains(ATTEMPT_ID.toString())
                            && message.contains("success")
                            && message.contains("stale generation 1")
                            && message.contains("current generation " + expectedStatus)
                            && message.contains("current status " + expectedStatus)
                            && message.matches(".*execution age [0-9]+ms.*")));
            assertFalse(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("private.example.test")
                            || message.contains("secret") || message.contains("value")));
            verify(service, never()).markFailedAndCreateRetry(any(), any(), any(), any(), any(), any());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private AttemptExecution execution() {
        Attempt attempt = mock(Attempt.class);
        Endpoint endpoint = mock(Endpoint.class);
        Message message = mock(Message.class);
        when(attempt.getId()).thenReturn(ATTEMPT_ID);
        when(attempt.getEndpoint()).thenReturn(endpoint);
        when(attempt.getMessage()).thenReturn(message);
        when(message.getId()).thenReturn(MESSAGE_ID);
        when(message.getBody()).thenReturn(new ObjectMapper().createObjectNode().put("value", 1));
        when(endpoint.getUrl()).thenReturn("https://private.example.test/hook");
        when(endpoint.getSigningSecret()).thenReturn("secret");
        return new AttemptExecution(attempt, 1L, Instant.now().minusSeconds(5));
    }

    private DeliveryWorker worker(AttemptService service, AttemptRepository repository,
            WebhookHttpTransport transport, SimpleMeterRegistry meters, java.util.concurrent.ExecutorService executor) {
        HmacSigner signer = mock(HmacSigner.class);
        when(signer.sign(any(), any(Long.class), any(), any())).thenReturn("signature");
        return new DeliveryWorker(service, signer, transport, mock(AttemptPublisher.class), executor, Clock.systemUTC(),
                new RetryProperties(), maximum -> maximum, repository, new ExecutionOwnershipMetrics(meters));
    }
}
