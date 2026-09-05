package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.relay.user.domain.RefreshToken;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.RefreshTokenRepository;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves {@link AuthService#register} commits the user row and the session row as one transaction.
 *
 * <p>
 * Before {@code @Transactional} was added, {@code RefreshTokenService.issue} ran in its own transaction, so a failure
 * there left an already-committed user behind. The caller saw a 500, retried, and then got a permanent 409 for an
 * account they never successfully created - with no way to discover that {@code /login} would actually have worked.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthServiceRegisterAtomicityTest {

    private static final String EMAIL = "register-atomicity@example.com";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoSpyBean
    private RefreshTokenService refreshTokenService;

    /**
     * Scoped to this test's own email rather than a blanket deleteAll.
     *
     * <p>
     * A passing run commits nothing, so this looks redundant - but if the transaction ever regresses, the rollback test
     * leaves a real user row in the JVM-wide {@code jdbc:h2:mem:relay} instance that every {@code @SpringBootTest}
     * shares. Leftover users with live refresh_tokens rows are exactly what broke six unrelated test classes in the
     * previous cycle, so the test that proves rollback has to clean up for the case where rollback did not happen.
     * Children before parents, same ordering as the fix in ee35ac0.
     */
    @AfterEach
    void removeAnythingThisTestCommitted() {
        userRepository.findByEmail(EMAIL).ifPresent(user -> {
            List<RefreshToken> owned = refreshTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId())).toList();
            refreshTokenRepository.deleteAll(owned);
            userRepository.delete(user);
        });
    }

    @Test
    void aFailureIssuingTheSession_rollsBackTheUserInsertToo() {
        doThrow(new RuntimeException("simulated crash while inserting the session row")).when(refreshTokenService)
                .issue(any(User.class), any());

        assertThrows(RuntimeException.class, () -> authService.register(EMAIL, "somePassword"));

        // The user row is the thing at stake. If this is present, the caller has an account they
        // cannot get a session for and cannot re-register - the exact trap this test defends.
        assertTrue(userRepository.findByEmail(EMAIL).isEmpty(),
                "the user insert must roll back when the session insert fails");
    }

    @Test
    void aSuccessfulRegistration_commitsTheUserAndTheSessionTogether() {
        // Positive control: without this, the assertion above would still pass if register() had
        // simply stopped persisting anything at all.
        authService.register(EMAIL, "somePassword");

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertEquals(1, refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId())).count());
    }
}
