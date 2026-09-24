package com.example.relay.deliveryengine.http;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface WebhookResponseBodyConsumer {

    /**
     * Consumes the response stream without retaining it after return. Implementations must honor stream close and
     * thread interruption so an absolute delivery deadline can terminate an in-progress read.
     */
    String consume(InputStream body) throws IOException;
}
