package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.JwtService;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.UserAlreadyExistsException;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private EmailVerificationTokenService emailVerificationTokenService;

    private AuthService underTest;

    @BeforeEach
    void setUp() {
        underTest = new AuthService(userRepository, passwordEncoder, authenticationManager, jwtService,
                refreshTokenService, new AuthProperties(), emailVerificationTokenService);
    }

    @Test
    void register_throwsUserAlreadyExistsException_whenEmailBelongsToAVerifiedAccount() {
        String email = "dakshkant8@gmail.com";
        String password = "somePassword";
        User existing = new User(email, "existing-hash");
        existing.markEmailVerified();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existing));

        UserAlreadyExistsException thrown =
                assertThrows(UserAlreadyExistsException.class, () -> underTest.register(email, password));

        assertEquals("User already exists with email: " + email, thrown.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_throwsExistingUnverifiedAccountException_whenEmailBelongsToAnUnverifiedAccount() {
        String email = "dakshkant8@gmail.com";
        User existing = new User(email, "existing-hash");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existing));

        com.example.relay.user.exception.ExistingUnverifiedAccountException thrown = assertThrows(
                com.example.relay.user.exception.ExistingUnverifiedAccountException.class,
                () -> underTest.register(email, "somePassword"));

        assertEquals(email, thrown.getEmail());
        verify(userRepository, never()).saveAndFlush(any());
        verify(emailVerificationTokenService, never()).issue(any(), any());
    }

    @Test
    void register_throwsExistingUnverifiedAccountException_whenItLosesTheUniqueConstraintRace() {
        // A race loser against a concurrent brand-new registration is, by construction, always
        // racing an unverified row - a freshly inserted User defaults to unverified (Task 2), and
        // nothing else could have verified it in that window. Both detection paths must produce
        // the identical outcome - see the design spec Section 5.
        String email = "daksh@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("pw")).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        com.example.relay.user.exception.ExistingUnverifiedAccountException thrown = assertThrows(
                com.example.relay.user.exception.ExistingUnverifiedAccountException.class,
                () -> underTest.register(email, "pw"));

        assertEquals(email, thrown.getEmail());
        verify(emailVerificationTokenService, never()).issue(any(), any());
    }

    @Test
    void register_savesEncodedUserAndIssuesAFirstVerificationToken_whenEmailIsNew() {
        String email = "dakshkant8@gmail.com";
        String hashedPassword = "somePassword";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("somePassword")).thenReturn(hashedPassword);
        User user = new User(email, hashedPassword);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(user);
        com.example.relay.user.domain.EmailVerificationToken token = new com.example.relay.user.domain.EmailVerificationToken(
                user, "hash", java.time.Instant.now().plusSeconds(3600), java.time.Instant.now());
        when(emailVerificationTokenService.issue(any(), any()))
                .thenReturn(new EmailVerificationTokenService.IssuedVerificationToken(token, "raw-token"));

        RegisteredUser result = underTest.register(email, "somePassword");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertEquals(email, userCaptor.getValue().getEmail());
        assertEquals(hashedPassword, userCaptor.getValue().getPasswordHash());
        assertEquals(email, result.user().getEmail());
        assertEquals("raw-token", result.issuedToken().rawToken());
        verify(refreshTokenService, never()).issue(any(), any());
    }

    @Test
    void login_returnsToken_whenCredentialsAreValid() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        String rawPassword = "somePassword";
        User user = new User(email, "someHashedPassword");
        user.markEmailVerified();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        String token = "someToken";
        when(jwtService.generateToken(eq(email), eq(user.getId()), eq(true))).thenReturn(token);

        // Act
        IssuedTokens result = underTest.login(email, rawPassword);

        // Assert
        assertEquals(token, result.accessToken());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void login_throwsEmailNotVerifiedException_whenTheAccountIsUnverified() {
        String email = "unverified@example.com";
        String rawPassword = "somePassword";
        User user = new User(email, "someHashedPassword");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        assertThrows(com.example.relay.user.exception.EmailNotVerifiedException.class,
                () -> underTest.login(email, rawPassword));

        verify(refreshTokenService, never()).issue(any(), any());
    }

    @Test
    void login_throwsBadCredentialsException_whenAuthenticationFails() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        String rawPassword = "somePassword";
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        // Act + Assert
        assertThrows(BadCredentialsException.class, () -> {
            underTest.login(email, rawPassword);
        });
    }

    @Test
    void refresh_mintsANewAccessTokenAndEchoesTheSameRefreshToken() {
        User user = new User("daksh@example.com", "hashed");
        when(refreshTokenService.validateAndSlide(eq("raw-refresh"), any())).thenReturn(user);
        when(jwtService.generateToken(eq(user.getEmail()), eq(user.getId()), any(Boolean.class))).thenReturn("new-access");

        IssuedTokens result = underTest.refresh("raw-refresh");

        assertEquals("new-access", result.accessToken());
        assertEquals("raw-refresh", result.rawRefreshToken());
    }

    @Test
    void logoutAll_delegatesWithTheUsersId() {
        UUID userId = UUID.randomUUID();

        underTest.logoutAll(userId);

        verify(refreshTokenService).revokeAll(eq(userId), any());
    }
}
