package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end proof, against real Spring beans and a real Postgres container (never mocks - a mock-based version of this
 * test could not prove anything about what actually commits), that the account pre-hijacking vulnerability is closed by
 * Design A.
 *
 * <p>
 * The property under test: whoever calls register() is IRRELEVANT to the account's final credential. The password
 * submitted at register() time is provisional and is unconditionally replaced by the one submitted alongside the
 * verification token. Since the raw token is only ever delivered to the account's own mailbox, and issue() keeps
 * exactly one token valid at a time, only whoever genuinely controls the mailbox can ever set the live password.
 */
@Tag("integration")
@SpringBootTest
class AccountPreHijackingRegressionTest implements SharedPostgresContainer {

    private static final String EMAIL = "pre-hijack-regression@example.com";
    private static final String ATTACKER_PASSWORD = "attackerPassword";
    private static final String REAL_OWNER_PASSWORD = "realOwnerPassword";

    @Autowired
    private AuthService authService;

    @Autowired
    private EmailVerificationTokenService emailVerificationTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void removeAnythingThisTestCommitted() {
        // The successful login() issues a refresh token, and register() issues an email-verification
        // token - both FK to users(id), so both must be cleared before the user row itself can be
        // deleted (children before parent), same pattern as AuthServiceRegisterAtomicityTest /
        // RegisterExistingUnverifiedIntegrationTest.
        userRepository.findByEmail(EMAIL).ifPresent(user -> {
            refreshTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .forEach(refreshTokenRepository::delete);
            emailVerificationTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .forEach(emailVerificationTokenRepository::delete);
            userRepository.delete(user);
        });
    }

    @Test
    void whoeverConsumesTheVerificationToken_notWhoeverRegistered_determinesTheLivePassword() {
        // 1. An attacker (or anyone at all - it makes no difference) registers the email with a
        // password of their choosing. This is the only thing an unauthenticated caller can do.
        RegisteredUser registered = authService.register(EMAIL, ATTACKER_PASSWORD);
        String rawToken = registered.issuedToken().rawToken();

        // The attacker's password is merely provisional at this point.
        User afterRegistration = userRepository.findByEmail(EMAIL).orElseThrow();
        assertFalse(afterRegistration.isEmailVerified());
        assertTrue(passwordEncoder.matches(ATTACKER_PASSWORD, afterRegistration.getPasswordHash()),
                "register() seeds the row with the submitted password - but only provisionally");

        // 2. The REAL mailbox owner clicks the link in the email they received and chooses their own
        // password. In production the raw token only ever reaches this mailbox, never whoever called
        // register() - which is precisely why this step is the security boundary.
        emailVerificationTokenService.consumeAndVerify(rawToken, passwordEncoder.encode(REAL_OWNER_PASSWORD),
                Instant.now());

        // 3. The real owner's password is now the live credential.
        authService.login(EMAIL, REAL_OWNER_PASSWORD);

        // 4. The password chosen at register() time is dead - the pre-hijacking hole is closed.
        assertThrows(BadCredentialsException.class, () -> authService.login(EMAIL, ATTACKER_PASSWORD));

        User afterVerification = userRepository.findByEmail(EMAIL).orElseThrow();
        assertTrue(afterVerification.isEmailVerified(),
                "consumeAndVerify must have committed email_verified=true to Postgres");
        assertTrue(passwordEncoder.matches(REAL_OWNER_PASSWORD, afterVerification.getPasswordHash()),
                "the committed password must be the one submitted with the verification token");
        assertFalse(passwordEncoder.matches(ATTACKER_PASSWORD, afterVerification.getPasswordHash()),
                "the provisional register()-time password must not survive verification");
    }
}
