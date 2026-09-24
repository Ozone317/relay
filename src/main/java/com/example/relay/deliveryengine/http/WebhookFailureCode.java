package com.example.relay.deliveryengine.http;

public enum WebhookFailureCode {
    DESTINATION_INVALID,
    DNS_RESOLUTION_FAILED,
    DESTINATION_POLICY_BLOCKED,
    DELIVERY_TIMEOUT,
    TRANSPORT_FAILURE
}
