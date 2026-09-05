package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.user.api.dto.AuthResponse;
import com.example.relay.user.api.dto.LoginRequest;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AuthLifecycleIntegrationTest {

    // This class is the only one in the suite that drives real, committing writes through the
    // embedded HTTP server rather than through Mockito or a rolled-back @DataJpaTest transaction.
    // Those writes land in the same named H2 instance ("relay") that every other @SpringBootTest
    // class shares for the life of the JVM. Several of those classes do an unconditional
    // userRepository.deleteAll() in their own setUp/cleanUp; if this class left its users behind,
    // their still-referencing refresh_tokens rows would break that deleteAll() with an FK
    // violation - and did, before this cleanup was added. Deleting only the emails this class
    // itself registers keeps the fix scoped to the mess this class actually makes.
    private static final List<String> TEST_EMAILS = List.of("lifecycle@example.com", "idempotent@example.com",
            "everywhere@example.com", "csrf@example.com", "idle@example.com");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUpPersistedTestUsers() {
        for (String email : TEST_EMAILS) {
            jdbcTemplate.update(
                    "DELETE FROM refresh_tokens WHERE user_id = (SELECT id FROM users WHERE email = ?)", email);
            jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
        }
    }

    private HttpHeaders csrfHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Relay-Auth", "1");
        return headers;
    }

    @Test
    void loginRefreshLogout_thenTheRefreshTokenIsDeadForever() {
        // 1. Register - expect an access token and a refresh cookie
        ResponseEntity<AuthResponse> registered = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest("lifecycle@example.com", "somePassword"), AuthResponse.class);

        assertEquals(HttpStatus.CREATED, registered.getStatusCode());
        assertNotNull(registered.getBody().accessToken());
        String cookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Path=/api/v1/auth"));

        String refreshCookie = cookie.substring(0, cookie.indexOf(';'));

        // 2. The access token opens a protected route
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(registered.getBody().accessToken());
        assertEquals(HttpStatus.OK, rest
                .exchange("/api/v1/environments", HttpMethod.GET, new HttpEntity<>(bearer), String.class)
                .getStatusCode());

        // 3. Refresh works while the session is live
        HttpHeaders refreshHeaders = csrfHeaders();
        refreshHeaders.add(HttpHeaders.COOKIE, refreshCookie);
        ResponseEntity<AuthResponse> refreshed = rest.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(refreshHeaders), AuthResponse.class);
        assertEquals(HttpStatus.OK, refreshed.getStatusCode());
        assertNotNull(refreshed.getBody().accessToken());

        // 4. Logout
        HttpHeaders logoutHeaders = csrfHeaders();
        logoutHeaders.add(HttpHeaders.COOKIE, refreshCookie);
        assertEquals(HttpStatus.NO_CONTENT, rest
                .exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), Void.class)
                .getStatusCode());

        // 5. THE POINT OF THE WHOLE FEATURE: the same refresh token is now dead server-side
        HttpHeaders deadHeaders = csrfHeaders();
        deadHeaders.add(HttpHeaders.COOKIE, refreshCookie);
        assertEquals(HttpStatus.UNAUTHORIZED, rest
                .exchange("/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(deadHeaders), String.class)
                .getStatusCode());
    }

    @Test
    void logout_isIdempotent() {
        ResponseEntity<AuthResponse> registered = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest("idempotent@example.com", "somePassword"), AuthResponse.class);
        String cookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String refreshCookie = cookie.substring(0, cookie.indexOf(';'));

        for (int i = 0; i < 2; i++) {
            HttpHeaders headers = csrfHeaders();
            headers.add(HttpHeaders.COOKIE, refreshCookie);
            assertEquals(HttpStatus.NO_CONTENT, rest
                    .exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), Void.class)
                    .getStatusCode());
        }
    }

    @Test
    void logoutAll_is401_forAnUnauthenticatedCaller() {
        // Guards the Task 7 matcher ordering. If /api/v1/auth/** were declared ABOVE logout-all it
        // would swallow it and this destructive endpoint would be public - a rule that fails open.
        assertEquals(HttpStatus.UNAUTHORIZED, rest.exchange("/api/v1/auth/logout-all", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class).getStatusCode());
    }

    @Test
    void logoutAll_revokesEverySessionForTheUser_includingOnesThisDeviceNeverHeld() {
        // Two independent logins for the same user - the "laptop" and the "phone"
        rest.postForEntity("/api/v1/auth/register", new RegisterRequest("everywhere@example.com", "somePassword"),
                AuthResponse.class);

        ResponseEntity<AuthResponse> laptop = rest.postForEntity("/api/v1/auth/login",
                new LoginRequest("everywhere@example.com", "somePassword"), AuthResponse.class);
        ResponseEntity<AuthResponse> phone = rest.postForEntity("/api/v1/auth/login",
                new LoginRequest("everywhere@example.com", "somePassword"), AuthResponse.class);

        String laptopCookie = laptop.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        laptopCookie = laptopCookie.substring(0, laptopCookie.indexOf(';'));

        // The phone calls logout-all with its own Bearer token
        HttpHeaders phoneAuth = new HttpHeaders();
        phoneAuth.setBearerAuth(phone.getBody().accessToken());
        assertEquals(HttpStatus.NO_CONTENT, rest.exchange("/api/v1/auth/logout-all", HttpMethod.POST,
                new HttpEntity<>(phoneAuth), Void.class).getStatusCode());

        // The laptop's refresh token - which the phone never possessed - is now dead
        HttpHeaders laptopHeaders = csrfHeaders();
        laptopHeaders.add(HttpHeaders.COOKIE, laptopCookie);
        assertEquals(HttpStatus.UNAUTHORIZED, rest.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(laptopHeaders), String.class).getStatusCode());
    }

    @Test
    void refresh_is403_withoutTheCsrfHeader() {
        ResponseEntity<AuthResponse> registered = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest("csrf@example.com", "somePassword"), AuthResponse.class);
        String cookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';')));

        assertEquals(HttpStatus.FORBIDDEN, rest
                .exchange("/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), String.class)
                .getStatusCode());
    }

    @Test
    void refresh_is401_onceTheIdleWindowHasLapsed() {
        ResponseEntity<AuthResponse> registered = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest("idle@example.com", "somePassword"), AuthResponse.class);
        String cookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String refreshCookie = cookie.substring(0, cookie.indexOf(';'));

        // Age only this test's own refresh token row past its idle window. The H2 instance is
        // shared across the whole suite (see other @SpringBootTest classes), so an unscoped
        // findAll().forEach(...) would age every row ever written by any test that happened to
        // run first - a footgun for whichever test runs next. Filtering to this user's row keeps
        // the blast radius to exactly the session under test.
        refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getEmail().equals("idle@example.com"))
                .forEach(token -> jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(60)), token.getId()));

        HttpHeaders headers = csrfHeaders();
        headers.add(HttpHeaders.COOKIE, refreshCookie);

        assertEquals(HttpStatus.UNAUTHORIZED, rest
                .exchange("/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), String.class)
                .getStatusCode());
    }
}
