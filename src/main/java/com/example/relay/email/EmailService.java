package com.example.relay.email;

import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class EmailService {

    private final EmailRenderer emailRenderer;
    private final EmailSender emailSender;

    public EmailService(EmailRenderer emailRenderer, EmailSender emailSender) {
        this.emailRenderer = emailRenderer;
        this.emailSender = emailSender;
    }

    public EmailSendResult send(EmailTemplate template, Map<String, Object> params, String recipientEmail,
            String idempotencyKey) {
        RenderedEmail email = emailRenderer.render(template, params);
        return emailSender.send(email, recipientEmail, idempotencyKey);
    }
}
