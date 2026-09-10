package com.example.relay.deliveryengine.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class DeliveryHttpClientConfig {

    static final int DELIVERY_TIMEOUT_MILLIS = 15_000;

    HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(DELIVERY_TIMEOUT_MILLIS))
                // Without an explicit executor, JdkClientHttpRequestFactory falls back to a
                // SimpleAsyncTaskExecutor (a fresh, unpooled platform thread per request body) and the
                // JDK HttpClient itself spins up its own unbounded platform-thread pool for request
                // execution - defeating the point of this worker's virtual-thread redesign. Pin it to
                // virtual threads explicitly.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                // Explicit, not just the JDK default - HttpURLConnection (the old
                // SimpleClientHttpRequestFactory's backing implementation) followed 301/302/303
                // redirects by default; java.net.http.HttpClient defaults to Redirect.NEVER already,
                // but this deliberately spells it out rather than relying on the default, since
                // silently re-sending a signed webhook POST as a followed redirect is not something we
                // want - a 3xx response is now recorded as a non-2xx delivery failure instead.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public RestClient deliveryRestClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(buildHttpClient());
        // Unlike SimpleClientHttpRequestFactory's read timeout (per-read socket inactivity),
        // JdkClientHttpRequestFactory's read timeout is a TOTAL-exchange budget - it wraps
        // sendAsync(...).get(timeout), including connection setup. Worst-case latency is now ~15s
        // total, not up to 15s connect + 15s read as it was under the old client.
        requestFactory.setReadTimeout(DELIVERY_TIMEOUT_MILLIS);

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
