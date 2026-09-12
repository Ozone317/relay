package com.example.relay.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EmailServiceTest {

    @Test
    void send_rendersThenSends_andReturnsTheSenderResult() {
        EmailRenderer emailRenderer = mock(EmailRenderer.class);
        EmailSender emailSender = mock(EmailSender.class);
        EmailService emailService = new EmailService(emailRenderer, emailSender);

        RenderedEmail rendered = new RenderedEmail("Subject", "<p>html</p>", "text");
        Map<String, Object> params = Map.of("appName", "My App");
        String idempotencyKey = UUID.randomUUID().toString();
        when(emailRenderer.render(EmailTemplate.DEAD_LETTER_NOTIFICATION, params)).thenReturn(rendered);
        when(emailSender.send(rendered, "user@example.com", idempotencyKey)).thenReturn(EmailSendResult.SENT);

        EmailSendResult result = emailService.send(EmailTemplate.DEAD_LETTER_NOTIFICATION, params,
                "user@example.com", idempotencyKey);

        assertThat(result).isEqualTo(EmailSendResult.SENT);
        verify(emailRenderer).render(eq(EmailTemplate.DEAD_LETTER_NOTIFICATION), eq(params));
        verify(emailSender).send(eq(rendered), eq("user@example.com"), eq(idempotencyKey));
    }

    @Test
    void send_passesThroughDuplicateResult_unmodified() {
        EmailRenderer emailRenderer = mock(EmailRenderer.class);
        EmailSender emailSender = mock(EmailSender.class);
        EmailService emailService = new EmailService(emailRenderer, emailSender);

        RenderedEmail rendered = new RenderedEmail("Subject", "<p>html</p>", "text");
        when(emailRenderer.render(any(), any())).thenReturn(rendered);
        when(emailSender.send(any(), any(), any())).thenReturn(EmailSendResult.DUPLICATE);

        EmailSendResult result = emailService.send(EmailTemplate.DEAD_LETTER_NOTIFICATION, Map.of(),
                "user@example.com", UUID.randomUUID().toString());

        assertThat(result).isEqualTo(EmailSendResult.DUPLICATE);
    }
}
