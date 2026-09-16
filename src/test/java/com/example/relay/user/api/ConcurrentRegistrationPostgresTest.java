package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.relay.email.EmailDispatchMessage;
import com.example.relay.email.EmailDispatchPublisher;
import com.example.relay.email.EmailTemplate;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.application.EmailVerificationService;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrentRegistrationPostgresTest implements SharedPostgresContainer {

    private static final String EMAIL = "concurrent-register-race@example.com";
    private static final String PASSWORD_A = "racePasswordA123";
    private static final String PASSWORD_B = "racePasswordB123";
    private static final String CLIENT_IP = "127.0.0.1";

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private EmailDispatchPublisher emailDispatchPublisher;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(
                "email-verification:cooldown:" + EMAIL,
                "email-verification:hourly:email:" + EMAIL,
                "email-verification:hourly:ip:" + CLIENT_IP));
        userRepository.findByEmail(EMAIL).ifPresent(user -> {
            List<EmailVerificationToken> ownedTokens = emailVerificationTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .toList();
            emailVerificationTokenRepository.deleteAll(ownedTokens);
            userRepository.delete(user);
        });
    }

    @Test
    void concurrentRegistrationForANewEmail_dispatchesOneTokenAndLeavesItUsable() throws Exception {
        CountDownLatch bothReachedEncode = new CountDownLatch(2);
        doAnswer(invocation -> {
            String rawPassword = invocation.getArgument(0);
            if (PASSWORD_A.equals(rawPassword) || PASSWORD_B.equals(rawPassword)) {
                bothReachedEncode.countDown();
                assertTrue(bothReachedEncode.await(10, TimeUnit.SECONDS),
                        "both requests must pass the empty lookup before either insert begins");
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<byte[]>> first = executor.submit(() -> rest.postForEntity(
                    "/api/v1/auth/register", new RegisterRequest(EMAIL, PASSWORD_A), byte[].class));
            Future<ResponseEntity<byte[]>> second = executor.submit(() -> rest.postForEntity(
                    "/api/v1/auth/register", new RegisterRequest(EMAIL, PASSWORD_B), byte[].class));

            ResponseEntity<byte[]> responseA = first.get(20, TimeUnit.SECONDS);
            ResponseEntity<byte[]> responseB = second.get(20, TimeUnit.SECONDS);

            assertEquals(HttpStatus.CREATED, responseA.getStatusCode());
            assertEquals(HttpStatus.CREATED, responseB.getStatusCode());
            assertArrayEquals(responseA.getBody(), responseB.getBody());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        ArgumentCaptor<EmailDispatchMessage> dispatch = ArgumentCaptor.forClass(EmailDispatchMessage.class);
        verify(emailDispatchPublisher, times(1)).publish(dispatch.capture());
        assertEquals(EmailTemplate.EMAIL_VERIFICATION, dispatch.getValue().template());

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        List<EmailVerificationToken> liveTokens = emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> token.getUsedAt() == null)
                .toList();
        assertEquals(1, liveTokens.size());
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("email-verification:cooldown:" + EMAIL)),
                "the race loser must not enter resend or claim its cooldown");

        String verificationUrl = dispatch.getValue().params().get("verificationUrl").toString();
        String rawToken = UriComponentsBuilder.fromUriString(verificationUrl).build()
                .getQueryParams().getFirst("token");
        assertNotNull(rawToken);

        emailVerificationService.verify(rawToken, "mailboxOwnerPassword123");

        assertTrue(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());
    }
}
