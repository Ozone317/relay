package com.example.relay.email.brevo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class BrevoEmailClientConfigTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void requestFactory_returnsFirst429_withoutAnAutomaticRetry() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(429));
        mockWebServer.enqueue(new MockResponse().setResponseCode(429));
        RestClient restClient = RestClient.builder().baseUrl(mockWebServer.url("/").toString())
                .requestFactory(new BrevoEmailClientConfig().brevoRequestFactory()).build();

        assertThatThrownBy(() -> restClient.post().retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
}
