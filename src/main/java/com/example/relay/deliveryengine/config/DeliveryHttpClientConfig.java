package com.example.relay.deliveryengine.config;

import java.time.Duration;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.relay.deliveryengine.destination.PolicyEnforcingDnsResolver;
import com.example.relay.deliveryengine.destination.PublicDestinationAddressPolicy;
import com.example.relay.deliveryengine.destination.SystemHostAddressLookup;
import com.example.relay.deliveryengine.http.BoundedApacheResponseBodyConsumer;
import com.example.relay.deliveryengine.http.ApacheWebhookHttpTransport;
import com.example.relay.deliveryengine.http.WebhookHttpTransport;

@Configuration
public class DeliveryHttpClientConfig {

    private static final int DELIVERY_MAX_CONNECTIONS = 40;
    private static final Duration CONNECTION_TIME_TO_LIVE = Duration.ofMinutes(5);
    private static final Duration IDLE_CONNECTION_EVICTION = Duration.ofMinutes(1);

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
    public WebhookHttpTransport webhookHttpTransport(CloseableHttpClient client,
            BoundedApacheResponseBodyConsumer responseBodyConsumer) {
        return new ApacheWebhookHttpTransport(client, responseBodyConsumer);
    }
}
