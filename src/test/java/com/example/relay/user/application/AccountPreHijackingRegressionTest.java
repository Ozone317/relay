package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.ExistingUnverifiedAccountException;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end proof that the account pre-hijacking vulnerability is closed: an attacker who
 * pre-registers a victim's email can no longer keep their own password live on the account once
 * the victim re-registers and verifies. See
 * .superpowers/sdd/2026-09-14-email-verification/security-fix-password-overwrite-brief.md.
 */
@SpringBootTest
class AccountPreHijackingRegressionTest implements SharedPostgresContainer {

    private static final String EMAIL = "pre-hijack-regression@example.com";

    @Autowired
    private AuthService authService;

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
        // Both login() calls in this test issue refresh tokens, and register() calls issue
        // email-verification tokens - both FK to users(id), so both must be cleared before the
        // user row itself can be deleted (children before parent), same pattern as
        // AuthServiceRegisterAtomicityTest / RegisterExistingUnverifiedIntegrationTest.
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
    void attackerPreRegistersVictimsEmail_victimReRegistersAndVerifies_attackersPasswordNoLongerWorks() {
        // 1. Attacker pre-registers the victim's email, creating an unverified row with the
        // attacker's chosen password.
        authService.register(EMAIL, "attackerPassword");

        // 2. Victim genuinely tries to register the same (still-unverified) email. The call still
        // throws ExistingUnverifiedAccountException exactly as before - but now the stored password
        // has been overwritten with the victim's submission.
        assertThrows(ExistingUnverifiedAccountException.class,
                () -> authService.register(EMAIL, "victimPassword"));

        User afterReRegistration = userRepository.findByEmail(EMAIL).orElseThrow();
        assertTrue(passwordEncoder.matches("victimPassword", afterReRegistration.getPasswordHash()),
                "the victim's re-registration must overwrite the stored password");

        // 3. Simulate the victim clicking their own verification link.
        afterReRegistration.markEmailVerified();
        userRepository.save(afterReRegistration);

        // 4. The victim can now log in with their own password - no exception means success.
        authService.login(EMAIL, "victimPassword");

        // 5. The attacker's original password no longer works - the vulnerability is closed.
        assertThrows(BadCredentialsException.class, () -> authService.login(EMAIL, "attackerPassword"));
    }
}
