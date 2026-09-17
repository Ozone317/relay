package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.api.dto.LoginRequest;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Covers design spec Section 10/12: an unverified user must be rejected with 403, must not receive any tokens
 * (Bearer body or refresh cookie), and the credential check must still happen BEFORE the verification check - a
 * wrong password for an unverified account must 401, not 403, so a caller can't use the login response to fingerprint
 * whether an email exists/is unverified via a different status code than a bad-credentials failure would produce.
 *
 * <p>
 * This runs over a real embedded HTTP server (not MockMvc) so the assertion about the absence of a Set-Cookie header
 * exercises the actual response Spring produces, mirroring AuthLifecycleIntegrationTest's style.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginEmailVerificationHttpIntegrationTest implements SharedPostgresContainer {

    private static final String EMAIL = "unverified-login-probe@example.com";
    private static final String PASSWORD = "somePassword123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @AfterEach
    void removeTheRegisteredUser() {
        userRepository.findByEmail(EMAIL).ifPresent(user -> {
            List<com.example.relay.user.domain.RefreshToken> ownedRefreshTokens =
                    refreshTokenRepository.findAll().stream()
                            .filter(token -> token.getUser().getId().equals(user.getId())).toList();
            refreshTokenRepository.deleteAll(ownedRefreshTokens);
            List<com.example.relay.user.domain.EmailVerificationToken> ownedVerificationTokens =
                    emailVerificationTokenRepository.findAll().stream()
                            .filter(token -> token.getUser().getId().equals(user.getId())).toList();
            emailVerificationTokenRepository.deleteAll(ownedVerificationTokens);
            userRepository.delete(user);
        });
    }

    @Test
    void login_forAnUnverifiedUser_is403_withNoTokensAndNoSetCookie() {
        rest.postForEntity("/api/v1/auth/register", new RegisterRequest(EMAIL, PASSWORD), String.class);

        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/v1/auth/login",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(new LoginRequest(EMAIL, PASSWORD)),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE),
                "a rejected login must never set a refresh cookie");
        Map<String, Object> body = response.getBody();
        assertFalse(body != null && body.containsKey("accessToken"),
                "a rejected login must never carry an access token in the body");
    }

    @Test
    void login_withWrongPasswordForAnUnverifiedUser_isStill401_notMaskedAs403() {
        rest.postForEntity("/api/v1/auth/register", new RegisterRequest(EMAIL, PASSWORD), String.class);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                new LoginRequest(EMAIL, "definitelyTheWrongPassword"), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "the credential check must run before the verification check, per spec Section 12");
    }
}
