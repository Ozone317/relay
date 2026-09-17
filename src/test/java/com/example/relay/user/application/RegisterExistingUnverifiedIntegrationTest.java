package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.ExistingUnverifiedAccountException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Under Design A, nothing about register() ever determines an account's final password - that is bound to
 * EmailVerificationTokenService.consumeAndVerify (see AccountPreHijackingRegressionTest for the security property
 * itself). So the only thing left to prove here is that register() against an existing UNVERIFIED email behaves
 * consistently and harmlessly, however many times it is called: it always throws ExistingUnverifiedAccountException
 * (never UserAlreadyExistsException, never a 500), and never corrupts or verifies the existing row.
 */
@Tag("integration")
@SpringBootTest
class RegisterExistingUnverifiedIntegrationTest implements SharedPostgresContainer {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Test
    void register_forAnExistingUnverifiedEmail_alwaysThrowsExistingUnverifiedAndLeavesTheRowIntact() {
        String email = "reissue-consistency-check@example.com";
        RegisteredUser first = authService.register(email, "originalPassword123");

        for (int attempt = 0; attempt < 3; attempt++) {
            ExistingUnverifiedAccountException thrown = assertThrows(ExistingUnverifiedAccountException.class,
                    () -> authService.register(email, "anotherPassword456"));
            assertEquals(email, thrown.getEmail());
        }

        User reloaded = userRepository.findByEmail(email).orElseThrow();
        assertEquals(first.user().getId(), reloaded.getId(), "repeated register() attempts must not replace the row");
        assertEquals(email, reloaded.getEmail());
        assertFalse(reloaded.isEmailVerified(), "a failed re-registration must never verify the account");

        // The email_verification_tokens FK to users(id) means this user's owned token rows must be
        // removed before the user row itself can be deleted - same cleanup shape as
        // AuthServiceRegisterAtomicityTest.
        emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(reloaded.getId()))
                .forEach(emailVerificationTokenRepository::delete);

        userRepository.delete(reloaded);
    }
}
