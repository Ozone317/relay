package com.example.relay.deliveryengine.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SystemHostAddressLookup;
import com.example.relay.deliveryengine.http.BoundedApacheResponseBodyConsumer;

@Configuration
public class DeliveryHttpClientConfig {

    static final int DELIVERY_TIMEOUT_MILLIS = 15_000;

    private static final int DELIVERY_MAX_CONNECTIONS = 40;
    private static final Duration CONNECTION_TIME_TO_LIVE = Duration.ofMinutes(5);
    private static final Duration IDLE_CONNECTION_EVICTION = Duration.ofMinutes(1);

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
    public PolicyEnforcingDnsResolver deliveryDnsResolver(SystemHostAddressLookup lookup) {
        return new PolicyEnforcingDnsResolver(lookup, new PublicDestinationAddressPolicy());
    }

    @Bean(destroyMethod = "close")
    public PoolingHttpClientConnectionManager deliveryConnectionManager(PolicyEnforcingDnsResolver resolver) {
        SocketConfig socketConfig = SocketConfig.custom()
                .setSocksProxyAddress(null)
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setTlsSocketStrategy(DefaultClientTlsStrategy.createDefault())
                .setDefaultSocketConfig(socketConfig)
                .setMaxConnTotal(DELIVERY_MAX_CONNECTIONS)
                .setMaxConnPerRoute(DELIVERY_MAX_CONNECTIONS)
                .setConnectionTimeToLive(TimeValue.of(CONNECTION_TIME_TO_LIVE))
                .setValidateAfterInactivity(TimeValue.of(IDLE_CONNECTION_EVICTION))
                .build();
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient deliveryApacheHttpClient(PoolingHttpClientConnectionManager connectionManager) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.of(IDLE_CONNECTION_EVICTION))
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    public BoundedApacheResponseBodyConsumer deliveryResponseBodyConsumer() {
        return new BoundedApacheResponseBodyConsumer();
    }

    @Bean
    @Qualifier("deliveryRestClient")
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
