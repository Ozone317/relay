package com.example.relay.email.brevo.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.example.relay.email.EmailProperties;

@Configuration
public class BrevoEmailClientConfig {

    // Not configurable, deliberately: a misconfigured base URL would send the real Brevo API key to
    // an unintended host, and Brevo's API endpoint has no legitimate reason to vary by environment.
    private static final String BREVO_BASE_URL = "https://api.brevo.com/v3";

    // On the JDK client (see ClientHttpRequestFactoryBuilder.detect() below), the read timeout is a
    // TOTAL-exchange budget, not per-read socket inactivity - same caveat as
    // DeliveryHttpClientConfig's read timeout comment. Worst case is ~READ_TIMEOUT total, not
    // CONNECT_TIMEOUT + READ_TIMEOUT added together.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    @Qualifier("brevoRestClient")
    public RestClient brevoRestClient(EmailProperties emailProperties) {
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults().withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)
                        // Explicit, not just left at the default (which follows redirects): the
                        // "api-key" header is a defaultHeader on this RestClient, so a followed 3xx
                        // would replay the real Brevo API key to whatever host the redirect points at -
                        // exactly the "API key sent to an unintended host" risk that hardcoding
                        // BREVO_BASE_URL above was meant to close off.
                        .withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW);
        // detect() resolves to JdkClientHttpRequestFactory here too, same as DeliveryHttpClientConfig -
        // there's no Apache HttpComponents/Jetty/Reactor Netty on this project's classpath. Unlike the
        // delivery path (dozens of concurrent deliveries needing real backpressure and explicit
        // virtual-thread pinning), dead-letter/password-reset email volume is low and this client isn't
        // invoked from the delivery worker's virtual-thread executor, so that extra tuning isn't
        // warranted here.
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder().baseUrl(BREVO_BASE_URL).requestFactory(requestFactory)
                .defaultHeader("api-key", emailProperties.getBrevo().getApiKey())
                .defaultHeader("Content-Type", "application/json").build();
    }
}
