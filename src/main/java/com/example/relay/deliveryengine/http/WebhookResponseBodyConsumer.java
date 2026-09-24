package com.example.relay.deliveryengine.http;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface WebhookResponseBodyConsumer {

    String consume(InputStream body) throws IOException;
}
