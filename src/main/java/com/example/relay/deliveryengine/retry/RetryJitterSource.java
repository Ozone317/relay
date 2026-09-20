package com.example.relay.deliveryengine.retry;

import java.time.Duration;

public interface RetryJitterSource {

    Duration next(Duration maximumInclusive);
}
