package com.example.relay.user.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CsrfHeaderFilter;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.user.api.dto.LoginRequest;
import com.example.relay.user.api.dto.RegisterRequest;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.application.IssuedTokens;
import com.example.relay.user.domain.User;
import com.example.relay.user.exception.InvalidRefreshTokenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AuthProperties.class, CsrfHeaderFilter.class, RefreshCookieFactory.class})
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
                .andExpect(jsonPath("$.accessToken").value(token))
                .andExpect(header().string("Set-Cookie", containsString("relay_refresh=raw-refresh")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));

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
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));

        verify(authService).logout(null);
    }

    @Test
    void refresh_returns401AndClearsTheCookie_whenTheTokenIsInvalid() throws Exception {
        when(authService.refresh("bad")).thenThrow(new InvalidRefreshTokenException("Refresh token is not recognised"));

        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Relay-Auth", "1")
                .cookie(new jakarta.servlet.http.Cookie("relay_refresh", "bad"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void refresh_returns200AndReSetsTheSameCookieValue() throws Exception {
        when(authService.refresh("good-token")).thenReturn(new IssuedTokens("new-access", "good-token", 900L));

        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Relay-Auth", "1")
                .cookie(new jakarta.servlet.http.Cookie("relay_refresh", "good-token"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(header().string("Set-Cookie", containsString("relay_refresh=good-token")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));
    }

    @Test
    void refresh_returns401_whenNoCookieIsPresent() throws Exception {
        when(authService.refresh(null)).thenThrow(new InvalidRefreshTokenException("No refresh token supplied"));

        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Relay-Auth", "1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void refresh_saysTheSameThing_whateverTheRealReasonWas() throws Exception {
        // A revoked session and an expired one must be indistinguishable here. The caller is
        // unauthenticated by definition, so answering "has been revoked" tells whoever holds a
        // stolen token that the victim has logged out - which is session state they should not be
        // able to read. The distinct causes still reach the log; only the response is flattened.
        when(authService.refresh("revoked"))
                .thenThrow(new InvalidRefreshTokenException("Refresh token has been revoked"));
        when(authService.refresh("expired")).thenThrow(new InvalidRefreshTokenException("Refresh token has expired"));

        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Relay-Auth", "1")
                .cookie(new jakarta.servlet.http.Cookie("relay_refresh", "revoked")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid refresh token"));

        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Relay-Auth", "1")
                .cookie(new jakarta.servlet.http.Cookie("relay_refresh", "expired")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    void logoutAll_returns204AndClearsTheCookie() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        mockMvc.perform(post("/api/v1/auth/logout-all").header("X-Relay-Auth", "1").with(authentication(auth)))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));

        verify(authService).logoutAll(user.getId());
    }

    @Test
    void logoutAll_returns401_forAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());

        verify(authService, never()).logoutAll(any());
    }
}
