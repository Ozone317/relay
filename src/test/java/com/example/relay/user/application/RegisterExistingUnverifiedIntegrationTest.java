package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RegisterExistingUnverifiedIntegrationTest implements SharedPostgresContainer {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Test
    void register_forAnExistingUnverifiedEmail_reissuesRatherThanOverwritingThePassword() {
        String email = "reissue-password-check@example.com";
        RegisteredUser first = authService.register(email, "originalPassword123");
        String originalHash = first.user().getPasswordHash();

        try {
            authService.register(email, "attackerChosenPassword456");
        } catch (com.example.relay.user.exception.ExistingUnverifiedAccountException expected) {
            // expected - this is the point of the test
        }

        User reloaded = userRepository.findByEmail(email).orElseThrow();
        assertEquals(originalHash, reloaded.getPasswordHash(),
                "a re-registration attempt for a still-unverified account must never change the stored password");
        assertTrue(!reloaded.isEmailVerified());

        // The email_verification_tokens FK to users(id) means this user's owned token rows must be
        // removed before the user row itself can be deleted - this test's two register() calls each
        // issue one, same cleanup shape as AuthServiceRegisterAtomicityTest.
        emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(reloaded.getId()))
                .forEach(emailVerificationTokenRepository::delete);

        userRepository.delete(reloaded);
    }
}
