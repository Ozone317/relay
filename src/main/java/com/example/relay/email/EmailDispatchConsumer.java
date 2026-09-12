package com.example.relay.email;

import com.example.relay.email.config.EmailQueueConfig;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generic by design - any future EmailTemplate can ride this queue with no changes here - with one deliberate, narrow
 * exception: a PASSWORD_RESET message's idempotencyKey IS the originating password_reset_tokens row's id (see
 * PasswordResetService), and that row's own reliable-delivery mechanism (see
 * com.example.relay.user.recovery.PasswordResetEmailRecoverySweeper) needs to know when the send actually succeeds.
 * Rather than build a generic post-send-callback abstraction for this one case, this consumer accepts one small,
 * explicit coupling to PasswordResetTokenRepository - the same kind of accepted small coupling this project already has
 * elsewhere (see GlobalExceptionHandler's dependency on RefreshCookieFactory).
 */
@Component
public class EmailDispatchConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchConsumer.class);

    private final EmailService emailService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public EmailDispatchConsumer(EmailService emailService, PasswordResetTokenRepository passwordResetTokenRepository,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.emailService = emailService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @RabbitListener(id = "emailDispatchConsumer", queues = EmailQueueConfig.DISPATCH_QUEUE)
    public void onMessage(String payload) throws Exception {
        EmailDispatchMessage message = objectMapper.readValue(payload, EmailDispatchMessage.class);

        // Deliberately un-transactional, matching DeadLetterNotifier.onMessage: this is an external
        // HTTP call to Brevo, and per this project's own stated design (see
        // application.properties:29-30, "the HTTP call... holds no DB connection"), it must not hold a
        // DB connection open for its duration. Only the claim below - a genuine DB write - gets a
        // transaction, and only via an explicit TransactionTemplate, since
        // claimResetEmailDispatch is a custom @Modifying @Query method on a repository injected
        // directly into this consumer (see class javadoc on that coupling) and Spring Data JPA does
        // not wrap such methods in a transaction on its own, unlike SimpleJpaRepository's built-in
        // CRUD methods.
        EmailSendResult result = emailService.send(message.template(), message.params(), message.recipientEmail(),
                message.idempotencyKey());
        log.info("Email dispatch for template {} (idempotency key {}) result: {}", message.template(),
                message.idempotencyKey(), result);

        if (message.template() == EmailTemplate.PASSWORD_RESET) {
            UUID tokenId = UUID.fromString(message.idempotencyKey());
            boolean claimed = Boolean.TRUE.equals(transactionTemplate.execute(
                    status -> passwordResetTokenRepository.claimResetEmailDispatch(tokenId, Instant.now()) == 1));
            if (!claimed) {
                log.warn("Reset-link email sent for token {} but the dispatch claim lost the race "
                        + "(already claimed)", tokenId);
            }
        }
    }
}
