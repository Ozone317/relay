package com.example.relay.email;

import java.util.Map;

public record EmailDispatchMessage(EmailTemplate template, Map<String, Object> params, String recipientEmail,
        String idempotencyKey) {
}
