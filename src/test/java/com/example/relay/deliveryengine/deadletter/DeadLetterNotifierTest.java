package com.example.relay.deliveryengine.deadletter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.relay.app.domain.App;
import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.email.EmailSendException;
import com.example.relay.email.EmailSendResult;
import com.example.relay.email.EmailService;
import com.example.relay.email.EmailTemplate;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.domain.Event;
import com.example.relay.message.domain.Message;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;

class DeadLetterNotifierTest {

    private final AttemptRepository attemptRepository = mock(AttemptRepository.class);
    private final AttemptService attemptService = mock(AttemptService.class);
    private final EmailService emailService = mock(EmailService.class);
    private final DeadLetterNotifier notifier =
            new DeadLetterNotifier(attemptRepository, attemptService, emailService);

    private Attempt deadAttemptWithFreshNotification() {
        User user = new User("owner@example.com", "hash");
        Environment environment = new Environment("Env", "Desc", user);
        App app = new App("App 1", environment);
        Event event = new Event("payment.completed", app);
        Endpoint endpoint = new Endpoint("EP", "https://example.com/webhook", "whsec", app);
        Message message = new Message(app, event, new ObjectMapper().createObjectNode());
        Delivery delivery = new Delivery(app, message, endpoint);
        Attempt attempt = new Attempt(app, message, endpoint, delivery, 6);
        attempt.setStatus(AttemptStatus.DEAD);
        return attempt;
    }

    @Test
    void onMessage_whenSendReturnsSent_claimsTheNotification() {
        Attempt attempt = deadAttemptWithFreshNotification();
        when(attemptRepository.findById(attempt.getId())).thenReturn(java.util.Optional.of(attempt));
        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenReturn(EmailSendResult.SENT);

        notifier.onMessage(attempt.getId().toString());

        verify(attemptService, times(1)).claimDeadLetterNotification(eq(attempt.getId()), any(Instant.class));
    }

    @Test
    void onMessage_whenSendReturnsDuplicate_alsoClaimsTheNotification() {
        // This is the direct proof of spec Section 6.1: DUPLICATE means the provider recognized the
        // idempotency key, not "guaranteed delivered" - but DeadLetterNotifier still treats it as
        // sufficient to claim, same as SENT.
        Attempt attempt = deadAttemptWithFreshNotification();
        when(attemptRepository.findById(attempt.getId())).thenReturn(java.util.Optional.of(attempt));
        when(emailService.send(any(), anyMap(), anyString(), anyString())).thenReturn(EmailSendResult.DUPLICATE);

        notifier.onMessage(attempt.getId().toString());

        verify(attemptService, times(1)).claimDeadLetterNotification(eq(attempt.getId()), any(Instant.class));
    }

    @Test
    void onMessage_whenAlreadyNotified_neitherSendsNorClaims() {
        Attempt attempt = deadAttemptWithFreshNotification();
        attempt.setDeadLetterNotifiedAt(Instant.now());
        when(attemptRepository.findById(attempt.getId())).thenReturn(java.util.Optional.of(attempt));

        notifier.onMessage(attempt.getId().toString());

        verify(emailService, never()).send(any(), anyMap(), anyString(), anyString());
        verify(attemptService, never()).claimDeadLetterNotification(any(), any());
    }

    @Test
    void onMessage_whenSendThrows_doesNotClaim() {
        Attempt attempt = deadAttemptWithFreshNotification();
        when(attemptRepository.findById(attempt.getId())).thenReturn(java.util.Optional.of(attempt));
        when(emailService.send(any(), anyMap(), anyString(), anyString()))
                .thenThrow(new EmailSendException("simulated failure"));

        org.junit.jupiter.api.Assertions.assertThrows(EmailSendException.class,
                () -> notifier.onMessage(attempt.getId().toString()));

        verify(attemptService, never()).claimDeadLetterNotification(any(), any());
    }
}
