package com.example.relay.deliveryengine.deadletter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.deliveryengine.config.RabbitMqConfig;
import com.example.relay.email.EmailSendResult;
import com.example.relay.email.EmailService;
import com.example.relay.email.EmailTemplate;

@Component
public class DeadLetterNotifier {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterNotifier.class);

    private final AttemptRepository attemptRepository;
    private final AttemptService attemptService;
    private final EmailService emailService;

    public DeadLetterNotifier(AttemptRepository attemptRepository, AttemptService attemptService,
            EmailService emailService) {
        this.attemptRepository = attemptRepository;
        this.attemptService = attemptService;
        this.emailService = emailService;
    }

    @RabbitListener(id = "deadLetterNotifier", queues = RabbitMqConfig.DEADLETTER_QUEUE)
    public void onMessage(String attemptIdRaw) {
        UUID attemptId = UUID.fromString(attemptIdRaw);
        Attempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("Dead-lettered attempt " + attemptId + " not found"));

        if (attempt.getDeadLetterNotifiedAt() != null) {
            log.info("Attempt {} was already notified, skipping", attemptId);
            return;
        }

        String recipient = attempt.getApp().getEnvironment().getUser().getEmail();
        Map<String, Object> params = Map.of("appName", attempt.getApp().getName(), "endpointUrl",
                attempt.getEndpoint().getUrl(), "lastError",
                attempt.getLastError() == null ? "" : attempt.getLastError());

        // send-then-claim, deliberately: attemptId as the idempotency key means a redelivered message
        // (from RabbitMQ itself, or recoverDeadLetter()) collapses to Brevo's own duplicate_parameter
        // response instead of a second real email in the common case. See
        // docs/superpowers/specs/2026-09-12-email-integration-design.md Section 6 for what this does
        // and does not guarantee - it is NOT exactly-once, and an outage longer than Brevo's
        // idempotency window between a successful send and this claim can still produce a duplicate.
        // SENT and DUPLICATE are both treated as "safe to claim" - see Section 6.1 for exactly what
        // DUPLICATE does and does not mean (it is not a delivery receipt).
        EmailSendResult result =
                emailService.send(EmailTemplate.DEAD_LETTER_NOTIFICATION, params, recipient, attemptId.toString());
        log.info("Attempt {} dead-letter notification result: {}", attemptId, result);

        boolean claimed = attemptService.claimDeadLetterNotification(attemptId, Instant.now());
        if (!claimed) {
            log.warn("Attempt {} dead-letter notification sent but claim lost the race (already claimed)",
                    attemptId);
        }
    }
}
