package com.example.relay.delivery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class DeliveryHttpClientConfig {

    private static final int DELIVERY_TIMEOUT_MILLIS = 15_000;

    @Bean
    public RestClient deliveryRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(DELIVERY_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(DELIVERY_TIMEOUT_MILLIS);

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
