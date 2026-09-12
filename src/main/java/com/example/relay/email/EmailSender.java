package com.example.relay.email;

public interface EmailSender {

    EmailSendResult send(RenderedEmail email, String recipientEmail, String idempotencyKey);
}
