package com.example.relay.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.relay.attempt.application.AttemptQueryService;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.RefreshToken;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A server-side fault must reach the client as a 500, not as an authentication failure.
 *
 * <p>
 * Before {@code GlobalExceptionHandler.handleUnexpected} existed, an exception escaping a controller was dispatched to
 * {@code /error}; the JWT filter does not run on an ERROR dispatch and {@code STATELESS} keeps no
 * {@code SecurityContext}, so the request was rejected there and the entry point returned
 * {@code 401 "Authentication required"}. A completely broken SQL statement in the attempts list therefore looked
 * exactly like a bad token, and cost a frontend integrator real time chasing a nonexistent auth bug.
 *
 * <p>
 * This runs on a real servlet container deliberately: MockMvc does not perform the {@code /error} dispatch, so a
 * {@code @WebMvcTest} cannot reproduce the masking and would pass either way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnhandledExceptionIntegrationTest implements SharedPostgresContainer {

    private static final String EMAIL = "unhandled-exception-probe@example.com";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoSpyBean
    private AttemptQueryService attemptQueryService;

    private String accessToken;

    private final String attemptsUrl =
            "/api/v1/environments/" + UUID.randomUUID() + "/apps/" + UUID.randomUUID() + "/attempts";

    @BeforeEach
    void registerAUser() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange("/api/v1/auth/register", HttpMethod.POST,
                jsonBody(Map.of("email", EMAIL, "password", "password123")),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        accessToken = (String) response.getBody().get("accessToken");
    }

    /** Registration commits into the JVM-wide shared H2 instance; children before parents. */
    @AfterEach
    void removeTheRegisteredUser() {
        userRepository.findByEmail(EMAIL).ifPresent(user -> {
            List<RefreshToken> owned = refreshTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId())).toList();
            refreshTokenRepository.deleteAll(owned);
            userRepository.delete(user);
        });
    }

    @Test
    void aFailureInsideTheControllerIsReportedAs500_notAsA401() {
        doThrow(new IllegalStateException("simulated server-side fault")).when(attemptQueryService).getPage(any(),
                any(), any(), any(), any(), any(), any(), any());

        ResponseEntity<String> response =
                restTemplate.exchange(attemptsUrl, HttpMethod.GET, authorised(), String.class);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "a server-side fault must not be disguised as an authentication failure");
    }

    @Test
    void anUnauthenticatedCallerStillGets401() {
        // Negative control: the fix must not have made the endpoint public. Without this, the
        // assertion above would also pass if every response had simply become a 500.
        ResponseEntity<String> response =
                restTemplate.exchange(attemptsUrl, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    private HttpEntity<Void> authorised() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Map<String, String>> jsonBody(Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
