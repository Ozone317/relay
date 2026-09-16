package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.relay.common.ratelimit.EmailVerificationRateLimiter;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailVerificationResendResponseEqualityIntegrationTest implements SharedPostgresContainer {

    private static final String RATE_LIMITED_EMAIL = "resend-equality-limited@example.com";
    private static final String NO_ACCOUNT_EMAIL = "resend-equality-missing@example.com";
    private static final String VERIFIED_EMAIL = "resend-equality-verified@example.com";
    private static final String CLIENT_IP = "127.0.0.1";

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailVerificationRateLimiter rateLimiter;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private EmailDispatchPublisher emailDispatchPublisher;

    @BeforeEach
    void setUp() {
        clearFixtures();
        User verifiedUser = userRepository.saveAndFlush(new User(VERIFIED_EMAIL, "hash"));
        verifiedUser.markEmailVerified();
        userRepository.saveAndFlush(verifiedUser);
    }

    @AfterEach
    void tearDown() {
        clearFixtures();
    }

    @Test
    void resend_responseIsByteIdentical_forRateLimitedMissingAndVerifiedAccounts() throws Exception {
        assertTrue(rateLimiter.allow(RATE_LIMITED_EMAIL, CLIENT_IP),
                "precondition: the test must claim the cooldown before calling the endpoint");

        MvcResult rateLimited = resend(RATE_LIMITED_EMAIL);
        MvcResult noAccount = resend(NO_ACCOUNT_EMAIL);
        MvcResult alreadyVerified = resend(VERIFIED_EMAIL);

        assertEquals(200, rateLimited.getResponse().getStatus());
        assertEquals(200, noAccount.getResponse().getStatus());
        assertEquals(200, alreadyVerified.getResponse().getStatus());
        assertArrayEquals(rateLimited.getResponse().getContentAsByteArray(),
                noAccount.getResponse().getContentAsByteArray());
        assertArrayEquals(rateLimited.getResponse().getContentAsByteArray(),
                alreadyVerified.getResponse().getContentAsByteArray());
    }

    private MvcResult resend(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/email-verification/resend")
                .with(request -> {
                    request.setRemoteAddr(CLIENT_IP);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
                .andReturn();
    }

    private void clearFixtures() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        List<String> emails = List.of(RATE_LIMITED_EMAIL, NO_ACCOUNT_EMAIL, VERIFIED_EMAIL);
        emails.forEach(email -> userRepository.findByEmail(email).ifPresent(user -> {
            emailVerificationTokenRepository.deleteAll(emailVerificationTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .toList());
            userRepository.delete(user);
        }));
    }
}
