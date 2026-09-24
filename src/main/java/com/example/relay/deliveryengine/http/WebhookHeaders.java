package com.example.relay.deliveryengine.http;

public record WebhookHeaders(String relayId, long relayTimestamp, String relaySignature) {
}
