package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.EmailVerificationTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves {@link AuthService#register} commits the user row and its first verification-token row as one transaction -
 * same property AuthServiceRegisterAtomicityTest always proved, retargeted from RefreshTokenService (register() no
 * longer touches sessions at all) to EmailVerificationTokenService.
 */
@Tag("integration")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthServiceRegisterAtomicityTest implements SharedPostgresContainer {

    private static final String EMAIL = "register-atomicity@example.com";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @MockitoSpyBean
    private EmailVerificationTokenService emailVerificationTokenService;

    @AfterEach
    void removeAnythingThisTestCommitted() {
        userRepository.findByEmail(EMAIL).ifPresent(user -> {
            emailVerificationTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .forEach(emailVerificationTokenRepository::delete);
            userRepository.delete(user);
        });
    }

    @Test
    void aFailureIssuingTheVerificationToken_rollsBackTheUserInsertToo() {
        doThrow(new RuntimeException("simulated crash while inserting the verification-token row"))
                .when(emailVerificationTokenService).issue(any(User.class), any());

        assertThrows(RuntimeException.class, () -> authService.register(EMAIL, "somePassword"));

        assertTrue(userRepository.findByEmail(EMAIL).isEmpty(),
                "the user insert must roll back when the verification-token insert fails");
    }

    @Test
    void aSuccessfulRegistration_commitsTheUserAndTheVerificationTokenTogether() {
        authService.register(EMAIL, "somePassword");

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertEquals(1, emailVerificationTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId())).count());
    }
}
