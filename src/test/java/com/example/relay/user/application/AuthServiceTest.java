package com.example.relay.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.relay.common.security.JwtService;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.UserAlreadyExistsException;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks
    private AuthService underTest;

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
        verify(userRepository, never()).save(any()); // proves it bailed out BEFORE trying to save a duplicate
    }

    @Test
    void register_savesEncodedUserAndReturnsToken_whenEmailIsNew() {

        // Arrange
        String email = "dakshkant8@gmail.com";
        String hashedPassword = "somePassword";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("somePassword")).thenReturn(hashedPassword);
        User user = new User(email, hashedPassword);
        when(userRepository.save(any(User.class))).thenReturn(user);
        String token = "someToken";
        when(jwtService.generateToken(eq(email), any(UUID.class))).thenReturn(token);

        // Act
        String password = "somePassword";
        String result = underTest.register(email, password);

        // Assert
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        assertEquals(email, savedUser.getEmail());
        assertEquals(hashedPassword, savedUser.getPasswordHash());
        assertEquals(token, result);
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
        String result = underTest.login(email, rawPassword);

        // Assert
        assertEquals(token, result);
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
}
