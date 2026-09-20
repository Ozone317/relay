package com.example.relay.deliveryengine.publisher;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ReadyTaskPublisher {

    CompletableFuture<ReadyPublishOutcome> publishReady(UUID attemptId);
}
