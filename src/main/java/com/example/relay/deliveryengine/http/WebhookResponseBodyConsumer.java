package com.example.relay.deliveryengine.http;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface WebhookResponseBodyConsumer {

    /**
     * Consumes the response stream without retaining it after return. Implementations must perform actual stream I/O,
     * honor stream close, and promptly return when a close causes an I/O failure so the absolute delivery deadline can
     * terminate an in-progress read on the exchange thread.
     */
    String consume(InputStream body) throws IOException;
}
