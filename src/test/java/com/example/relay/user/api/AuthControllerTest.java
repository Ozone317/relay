package com.example.relay.user.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.user.api.dto.LoginRequest;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.application.IssuedTokens;
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
@Import(SecurityConfig.class)
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
    void register_returns201AndToken_whenRequestIsValid() throws Exception {
        // Arrange
        RegisterRequest registerRequest = new RegisterRequest("test@mail.com", "somePassword");
        String token = "someToken";
        IssuedTokens issuedTokens = new IssuedTokens(token, "raw-refresh", 900L);

        when(authService.register(registerRequest.email(), registerRequest.password())).thenReturn(issuedTokens);

        // Act + Assert
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value(token));

        // Verify
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
}
