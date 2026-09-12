package com.example.relay.email.brevo.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.example.relay.email.EmailProperties;

@Configuration
public class BrevoEmailClientConfig {

    // Not configurable, deliberately: a misconfigured base URL would send the real Brevo API key to
    // an unintended host, and Brevo's API endpoint has no legitimate reason to vary by environment.
    static final String BREVO_BASE_URL = "https://api.brevo.com/v3";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public RestClient brevoRestClient(EmailProperties emailProperties) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults().withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder().baseUrl(BREVO_BASE_URL).requestFactory(requestFactory)
                .defaultHeader("api-key", emailProperties.getBrevo().getApiKey())
                .defaultHeader("Content-Type", "application/json").build();
    }
}
