package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
public class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService underTest;

    @Test
    void loadUserByUsername_returnsUserDetails_whenUserExists() {
        // Arrange
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        // Act
        var mockedUser = underTest.loadUserByUsername(user.getEmail());

        // Assert
        assertEquals("dakshkant8@gmail.com", mockedUser.getUsername());
        assertEquals("passwordhash", mockedUser.getPassword());
    }

    @Test
    void loadUserByUsername_throwsUsernameNotFoundException_whenUserDoesNotExist() {
        // Arrange
        User user = new User("dakshkant8@gmail.com", "passwordhash");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(UsernameNotFoundException.class, () -> {
            underTest.loadUserByUsername(user.getEmail());
        });
    }
}
