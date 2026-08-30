package com.example.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
public class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter underTest;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthHeader_passesThroughWithoutSettingAuthentication() throws IOException, ServletException {

        // Arrange
        when(request.getHeader("Authorization")).thenReturn(null);

        // Act
        underTest.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void nonBearerAuthHeader_passesThroughWithoutSettingAuthentication() throws IOException, ServletException {

        // Arrange
        when(request.getHeader("Authorization")).thenReturn("Non Bearer");

        // Act
        underTest.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validBearerToken_setsAuthenticationInSecurityContext() throws IOException, ServletException {

        // Arrange
        when(request.getHeader("Authorization")).thenReturn("Bearer someToken");
        String token = "someToken";
        when(jwtService.isValid(token)).thenReturn(true);
        String email = "dakshkant8@gmail.com";
        UUID userId = UUID.fromString("87492bba-28ba-4850-83fe-cee99fad11be");
        when(jwtService.extractEmail(token)).thenReturn(email);
        when(jwtService.extractUserId(token)).thenReturn(userId);

        // Act
        underTest.doFilterInternal(request, response, filterChain);

        // Assert
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        AuthenticatedUser principal =
                (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(userId, principal.getId());
        assertEquals(email, principal.getUsername());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void invalidBearerToken_doesNotSetAuthentication() throws IOException, ServletException {

        // Arrange
        when(request.getHeader("Authorization")).thenReturn("Bearer someToken");
        String token = "someToken";
        when(jwtService.isValid(token)).thenReturn(false);

        // Act
        underTest.doFilterInternal(request, response, filterChain);

        // Assert
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void alreadyAuthentictedContext_neverCallsJwtService() throws ServletException, IOException {

        // Arrange
        when(request.getHeader("Authorization")).thenReturn("Bearer someToken");

        Authentication existingAuth = new UsernamePasswordAuthenticationToken("existingUser", null);
        SecurityContextHolder.getContext().setAuthentication(existingAuth);

        // Act
        underTest.doFilterInternal(request, response, filterChain);

        // Assert
        verifyNoInteractions(jwtService);
        verify(filterChain).doFilter(request, response);
    }
}
