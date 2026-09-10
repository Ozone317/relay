package com.example.relay.deliveryengine.config;

import java.net.http.HttpClient;
import java.time.Duration;
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
                .build();
    }

    @Bean
    public RestClient deliveryRestClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(buildHttpClient());
        requestFactory.setReadTimeout(DELIVERY_TIMEOUT_MILLIS);

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
