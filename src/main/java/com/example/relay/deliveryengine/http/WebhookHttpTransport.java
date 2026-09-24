package com.example.relay.deliveryengine.http;

public interface WebhookHttpTransport {

    WebhookHttpResponse post(String rawDestinationUrl, byte[] body, WebhookHeaders headers)
            throws WebhookDeliveryException;
}
