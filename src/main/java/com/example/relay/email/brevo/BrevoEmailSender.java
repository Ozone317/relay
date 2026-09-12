package com.example.relay.email.brevo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.relay.email.EmailProperties;
import com.example.relay.email.EmailSendException;
import com.example.relay.email.EmailSendResult;
import com.example.relay.email.EmailSender;
import com.example.relay.email.RenderedEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class BrevoEmailSender implements EmailSender {

    private final RestClient brevoRestClient;
    private final EmailProperties emailProperties;
    private final ObjectMapper objectMapper;

    public BrevoEmailSender(@Qualifier("brevoRestClient") RestClient brevoRestClient, EmailProperties emailProperties,
            ObjectMapper objectMapper) {
        this.brevoRestClient = brevoRestClient;
        this.emailProperties = emailProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public EmailSendResult send(RenderedEmail email, String recipientEmail, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);

        Map<String, Object> body = Map.of(
                "sender", Map.of("email", emailProperties.getSenderEmail(), "name", emailProperties.getSenderName()),
                "to", List.of(Map.of("email", recipientEmail)),
                "subject", email.subject(),
                "htmlContent", email.html(),
                "textContent", email.text());

        try {
            brevoRestClient.post().uri("/smtp/email").header("Idempotency-Key", idempotencyKey).body(body)
                    .retrieve().toBodilessEntity();
            return EmailSendResult.SENT;
        } catch (HttpStatusCodeException e) {
            if (isDuplicateParameter(e)) {
                return EmailSendResult.DUPLICATE;
            }
            // Deliberately NOT including e.getResponseBodyAsString() in this message - it's external
            // provider data and must not flow automatically into logs/error trackers. The original
            // exception (which still carries the full body) is preserved as the cause for anyone who
            // deliberately needs it.
            throw new EmailSendException("Brevo send failed with status " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new EmailSendException("Brevo send failed", e);
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be a valid UUID string for Brevo's Idempotency-Key header, got: '"
                            + idempotencyKey + "'",
                    e);
        }
    }

    private boolean isDuplicateParameter(HttpStatusCodeException e) {
        try {
            JsonNode responseBody = objectMapper.readTree(e.getResponseBodyAsString());
            return "duplicate_parameter".equals(responseBody.path("code").asText());
        } catch (Exception parseFailure) {
            return false;
        }
    }
}
