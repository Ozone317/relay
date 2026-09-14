package com.example.relay.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class EmailDispatchConsumerEmailVerificationTest {

    @Test
    void onMessage_sendsEmailVerification_withNoClaimSideEffect() throws Exception {
        EmailService emailService = mock(EmailService.class);
        PasswordResetTokenRepository passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        EmailDispatchConsumer underTest =
                new EmailDispatchConsumer(emailService, passwordResetTokenRepository, objectMapper, transactionManager);
        when(emailService.send(any(), any(), any(), any())).thenReturn(EmailSendResult.SENT);

        EmailDispatchMessage message = new EmailDispatchMessage(EmailTemplate.EMAIL_VERIFICATION,
                Map.of("verificationUrl", "https://example.com?token=x"), "user@example.com",
                UUID.randomUUID().toString());

        underTest.onMessage(objectMapper.writeValueAsString(message));

        verify(emailService).send(EmailTemplate.EMAIL_VERIFICATION, message.params(), "user@example.com",
                message.idempotencyKey());
        verifyNoInteractions(passwordResetTokenRepository);
        verifyNoInteractions(transactionManager);
    }
}
