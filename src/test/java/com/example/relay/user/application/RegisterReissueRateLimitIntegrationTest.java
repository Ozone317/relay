package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.EmailVerificationToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.ExistingUnverifiedAccountException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Regression test for the griefing vector described in design spec Section 6: before the fix, a repeated
 * register() call for an existing-unverified email could reissue a fresh verification token (and re-dispatch an
 * email) on every attempt, letting an attacker who doesn't own the mailbox spam the real owner's inbox with no rate
 * limit at all. AuthController.register() now funnels the existing-unverified branch through
 * EmailVerificationService.resend() - the SAME cooldown-gated path resend() itself uses - so this exercises
 * AuthService.register() + EmailVerificationService.resend() together the way the controller wires them. Note that
 * the FIRST register() call for a brand-new row never claims the cooldown itself (only the existing-unverified
 * reissue branch does), so this test drives three attempts: the owning registration, then two re-registrations of
 * the same still-unverified email, to prove the SECOND reissue attempt inside the cooldown window is the one that
 * gets blocked.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
class RegisterReissueRateLimitIntegrationTest implements SharedPostgresContainer {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final String EMAIL = "reissue-rate-limit@example.com";

    @Autowired
    private AuthService authService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete("email-verification:cooldown:" + EMAIL);
        userRepository.findByEmail(EMAIL).ifPresent(user -> {
            emailVerificationTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .forEach(emailVerificationTokenRepository::delete);
            userRepository.delete(user);
        });
    }

    @Test
    void register_calledRepeatedlyForTheSameUnverifiedEmail_reissuesAtMostOncePerCooldownWindow() {
        // Attempt 1: the real, owning registration. AuthService.register()'s OWN token issuance (for a
        // genuinely new row) never touches the rate limiter at all - only the existing-unverified
        // reissue branch (resend(), below) does - so this alone claims no cooldown.
        authService.register(EMAIL, "somePassword123");

        // Attempt 2: an attacker (or the same user, impatiently) re-registers the still-unverified
        // email. AuthController.register() funnels this into resend(), which claims the cooldown and
        // issues a second token.
        ExistingUnverifiedAccountException secondAttempt = assertThrows(ExistingUnverifiedAccountException.class,
                () -> authService.register(EMAIL, "differentPassword456"));
        emailVerificationService.resend(secondAttempt.getEmail(), "127.0.0.1");

        String rawTokenAfterSecondAttempt = latestUnusedToken().getTokenHash();

        // Attempt 3: another re-registration landing inside the SAME cooldown window. Before the fix
        // this griefing vector closed, this would have issued yet another fresh token/email on every
        // single request; the resend()-backed cooldown must block it instead.
        ExistingUnverifiedAccountException thirdAttempt = assertThrows(ExistingUnverifiedAccountException.class,
                () -> authService.register(EMAIL, "yetAnotherPassword789"));
        emailVerificationService.resend(thirdAttempt.getEmail(), "127.0.0.1");

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        List<EmailVerificationToken> unusedTokens = emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> token.getUsedAt() == null).toList();

        assertEquals(1, unusedTokens.size(),
                "a third register() attempt inside the cooldown window must not issue yet another token");
        assertEquals(rawTokenAfterSecondAttempt, unusedTokens.get(0).getTokenHash(),
                "the token left standing must be the one issued by the SECOND attempt's resend() call, "
                        + "proving the third attempt's reissue was actually rate-limited away rather than "
                        + "coincidentally producing the same outcome");
    }

    private EmailVerificationToken latestUnusedToken() {
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        List<EmailVerificationToken> unusedTokens = emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> token.getUsedAt() == null).toList();
        assertEquals(1, unusedTokens.size(), "expected exactly one unused token at this point in the test");
        return unusedTokens.get(0);
    }
}
