package com.example.relay.user.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.common.security.SecureTokenGenerator;
import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.application.PasswordResetService;
import com.example.relay.user.domain.PasswordResetToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.PasswordResetTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class PasswordResetActivatesPendingAccountIntegrationTest implements SharedPostgresContainer {

    @Autowired
    private PasswordResetService underTest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private SecureTokenGenerator secureTokenGenerator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User user;

    @AfterEach
    void cleanUp() {
        if (user == null) {
            return;
        }
        passwordResetTokenRepository.findAll().stream().filter(token -> token.getUser().getId().equals(user.getId()))
                .forEach(passwordResetTokenRepository::delete);
        // Reload to get the current @Version before deleting - the in-memory `user` reference may be
        // stale by now (confirmReset's own transaction bumped the row's version when it activated the
        // account and/or set the password), and Hibernate rejects a delete against a stale version.
        user = userRepository.findById(user.getId()).orElse(user);
        userRepository.delete(user);
    }

    @Test
    void confirmReset_onAPendingAccount_activatesIt() {
        user = userRepository.saveAndFlush(
                new User("pending-reset-activation@example.com", passwordEncoder.encode("provisional")));
        String rawToken = secureTokenGenerator.generateRawToken();
        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(rawToken), Instant.now().plusSeconds(1800), Instant.now()));

        underTest.confirmReset(rawToken, "myNewPassword123");

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified(), "a successful reset on a PENDING account must activate it");
        assertTrue(passwordEncoder.matches("myNewPassword123", reloaded.getPasswordHash()));
    }

    @Test
    void confirmReset_onAnAlreadyActiveAccount_changesPasswordAndStaysActive() {
        user = userRepository.saveAndFlush(new User("active-reset@example.com", passwordEncoder.encode("original")));
        user.markEmailVerified();
        user = userRepository.saveAndFlush(user);
        String rawToken = secureTokenGenerator.generateRawToken();
        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user,
                secureTokenGenerator.hash(rawToken), Instant.now().plusSeconds(1800), Instant.now()));

        underTest.confirmReset(rawToken, "myNewPassword123");

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified(), "email_verified must remain true across an ordinary reset");
        assertTrue(passwordEncoder.matches("myNewPassword123", reloaded.getPasswordHash()));
    }
}
