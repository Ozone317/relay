package com.example.relay.email.brevo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.example.relay.email.EmailProperties;
import com.example.relay.email.EmailSendException;
import com.example.relay.email.EmailSendResult;
import com.example.relay.email.RenderedEmail;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

class BrevoEmailSenderTest {

    private MockWebServer mockWebServer;
    private BrevoEmailSender brevoEmailSender;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setSenderEmail("noreply@relay.test");
        emailProperties.setSenderName("Relay");
        emailProperties.getBrevo().setApiKey("test-api-key");

        RestClient restClient = RestClient.builder().baseUrl(mockWebServer.url("/").toString())
                .defaultHeader("api-key", "test-api-key").defaultHeader("Content-Type", "application/json").build();

        brevoEmailSender = new BrevoEmailSender(restClient, emailProperties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void send_postsExpectedRequestShape_andReturnsSent() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(201).setBody("{\"messageId\":\"abc-123\"}")
                .addHeader("Content-Type", "application/json"));

        String idempotencyKey = UUID.randomUUID().toString();
        RenderedEmail email = new RenderedEmail("Subject line", "<p>html</p>", "text body");
        EmailSendResult result = brevoEmailSender.send(email, "user@example.com", idempotencyKey);

        assertThat(result).isEqualTo(EmailSendResult.SENT);

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/smtp/email");
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo(idempotencyKey);
        assertThat(recorded.getHeader("api-key")).isEqualTo("test-api-key");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"subject\":\"Subject line\"");
        assertThat(body).contains("noreply@relay.test");
        assertThat(body).contains("user@example.com");
    }

    @Test
    void send_returnsDuplicate_whenBrevoReportsDuplicateParameter() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"code\":\"duplicate_parameter\",\"message\":\"already sent\"}")
                .addHeader("Content-Type", "application/json"));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");
        EmailSendResult result = brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString());

        assertThat(result).isEqualTo(EmailSendResult.DUPLICATE);
    }

    @Test
    void send_throwsIllegalArgumentException_whenIdempotencyKeyIsNotAValidUuid_andMakesNoHttpCall() {
        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void send_throwsEmailSendException_on401Unauthorized() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"unauthorized\",\"message\":\"invalid api key\"}"));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("invalid api key");
    }

    @Test
    void send_throwsEmailSendException_on403Forbidden() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(403).setBody("{\"code\":\"forbidden\"}"));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class);
    }

    @Test
    void send_throwsEmailSendException_on429RateLimited() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(429).setBody("{\"code\":\"too_many_requests\"}"));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class);
    }

    @Test
    void send_throwsEmailSendException_onGenericOther4xx() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(422).setBody("{\"code\":\"invalid_parameter\"}"));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class);
    }

    @Test
    void send_throwsEmailSendException_on5xx_andDoesNotLeakBodyIntoMessage() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("internal secret stack trace details"));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("internal secret stack trace details");
    }

    @Test
    void send_throwsEmailSendException_onMalformedErrorBody() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("not valid json at all"));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class);
    }

    @Test
    void send_throwsEmailSendException_onConnectionFailure() {
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class);
    }

    @Test
    void send_doesNotRetryInternally_onFailure() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        RenderedEmail email = new RenderedEmail("Subject", "<p>html</p>", "text");

        assertThatThrownBy(() -> brevoEmailSender.send(email, "user@example.com", UUID.randomUUID().toString()))
                .isInstanceOf(EmailSendException.class);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
}
