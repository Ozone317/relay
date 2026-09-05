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

    private AuthService underTest;

    @BeforeEach
    void setUp() {
        underTest = new AuthService(userRepository, passwordEncoder, authenticationManager, jwtService,
                refreshTokenService, new AuthProperties());
    }

    @Test
    void register_throwsUserAlreadyExistsException_whenEmailAlreadyExists() {
        // Arrange
        String email = "dakshkant8@gmail.com";
        String password = "somePassword";
        User user = new User(email, password);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // Act + Assert
        assertThrows(UserAlreadyExistsException.class, () -> {
            underTest.register(email, password);
        });

        // Assert
        verify(userRepository, never()).saveAndFlush(any()); // proves it bailed out BEFORE trying to save a duplicate
    }

    @Test
    void register_savesEncodedUserAndReturnsToken_whenEmailIsNew() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        String hashedPassword = "somePassword";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("somePassword")).thenReturn(hashedPassword);
        User user = new User(email, hashedPassword);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(user);
        String token = "someToken";
        when(jwtService.generateToken(eq(email), any(UUID.class))).thenReturn(token);

        // Act
        String password = "somePassword";
        IssuedTokens result = underTest.register(email, password);

        // Assert
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        assertEquals(email, savedUser.getEmail());
        assertEquals(hashedPassword, savedUser.getPasswordHash());
        assertEquals(token, result.accessToken());
    }

    @Test
    void register_returnsBothCredentialsAndTtlInSeconds() {
        String email = "daksh@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("pw")).thenReturn("hashed");
        when(jwtService.generateToken(eq(email), any())).thenReturn("access-token");
        when(refreshTokenService.issue(any(), any())).thenReturn("raw-refresh");

        IssuedTokens result = underTest.register(email, "pw");

        assertEquals("access-token", result.accessToken());
        assertEquals("raw-refresh", result.rawRefreshToken());
        assertEquals(900L, result.expiresIn());
    }

    @Test
    void register_throwsUserAlreadyExists_whenItLosesTheUniqueConstraintRace() {
        // The pre-check passes because the concurrent registration had not committed yet; the
        // users.email UNIQUE constraint is what actually catches the duplicate. Without the
        // translation this surfaces as an unmapped DataIntegrityViolationException, i.e. a 500.
        String email = "daksh@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("pw")).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThrows(UserAlreadyExistsException.class, () -> underTest.register(email, "pw"));

        // a registration that did not happen must not open a session
        verify(refreshTokenService, never()).issue(any(), any());
    }

    @Test
    void login_returnsToken_whenCredentialsAreValid() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        String rawPassword = "somePassword";
        User user = new User(email, "someHashedPassword");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        String token = "someToken";
        when(jwtService.generateToken(eq(email), eq(user.getId()))).thenReturn(token);

        // Act
        IssuedTokens result = underTest.login(email, rawPassword);

        // Assert
        assertEquals(token, result.accessToken());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
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
        when(jwtService.generateToken(eq(user.getEmail()), eq(user.getId()))).thenReturn("new-access");

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
