package com.example.relay.user.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.user.api.dto.LoginRequest;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.application.IssuedTokens;
import com.example.relay.user.exception.InvalidRefreshTokenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AuthProperties.class, RefreshCookieFactory.class})
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService; // mock purely added as the spring security chain wakes up with @WebMvcTest

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService; // mock purely added as the spring security chain wakes
                                                               // up with

    // @WebMvcTest

    @Test
    void register_returns201AndAccessTokenAndSetsRefreshCookie() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest("test@mail.com", "somePassword");
        when(authService.register(registerRequest.email(), registerRequest.password()))
                .thenReturn(new IssuedTokens("access-token", "raw-refresh", 900L));

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(header().string("Set-Cookie", containsString("relay_refresh=raw-refresh")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));

        verify(authService).register(registerRequest.email(), registerRequest.password());
    }

    @Test
    void register_returns400_whenEmailIsBlank() throws Exception {
        // Arrange
        RegisterRequest registerRequest = new RegisterRequest("", "somePassword");

        // Act + Assert
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest))).andExpect(status().isBadRequest());

        // Verify
        verify(authService, never()).register(any(), any());
    }

    @Test
    void login_returns200AndToken_whenCredentialsAreValid() throws Exception {
        // Arrange
        LoginRequest loginRequest = new LoginRequest("test@mail.com", "somePassword");
        String token = "someToken";
        IssuedTokens issuedTokens = new IssuedTokens(token, "raw-refresh", 900L);

        when(authService.login(loginRequest.email(), loginRequest.password())).thenReturn(issuedTokens);

        // Act + Assert
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(token));

        // Verify
        verify(authService).login(loginRequest.email(), loginRequest.password());
    }

    @Test
    void login_returns401_whenCredentialsAreInvalid() throws Exception {
        // Arrange
        LoginRequest loginRequest = new LoginRequest("test@mail.com", "somePassword");
        when(authService.login(loginRequest.email(), loginRequest.password()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        // Act + Assert
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        // Verify
        verify(authService).login(loginRequest.email(), loginRequest.password());
    }

    @Test
    void logout_returns204AndClearsTheCookie_evenWithNoCookiePresent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header("X-Relay-Auth", "1")).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("relay_refresh=")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(authService).logout(null);
    }

    @Test
    void refresh_returns401AndClearsTheCookie_whenTheTokenIsInvalid() throws Exception {
        when(authService.refresh("bad")).thenThrow(new InvalidRefreshTokenException("Refresh token is not recognised"));

        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Relay-Auth", "1")
                .cookie(new jakarta.servlet.http.Cookie("relay_refresh", "bad"))).andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }
}
