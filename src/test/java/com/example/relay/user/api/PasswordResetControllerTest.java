package com.example.relay.user.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.common.ratelimit.ClientIpResolver;
import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.CsrfHeaderFilter;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.user.application.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Same @WebMvcTest wiring AuthControllerTest already needs and has proven correct - SecurityConfig pulls in the full
 * filter chain, which needs AuthProperties/CsrfHeaderFilter/RefreshCookieFactory as real beans and
 * JwtService/CustomUserDetailsService as mocks purely to let the chain construct ({@code @WebMvcTest} auto-includes
 * {@code @RestControllerAdvice} beans like GlobalExceptionHandler, so it needs no explicit @Import here either -
 * confirmed by AuthControllerTest not importing it and still exercising its 409 mapping).
 */
@WebMvcTest(PasswordResetController.class)
@Import({SecurityConfig.class, AuthProperties.class, CsrfHeaderFilter.class, RefreshCookieFactory.class})
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void request_returnsTheGenericMessage_forAnyInput() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");

        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"anyone@example.com\"}")).andExpect(status().isOk()).andExpect(
                        content().json("{\"message\":\"If an account exists for this email, a password reset link has "
                                + "been sent.\"}"));

        verify(passwordResetService).requestReset(anyString(), anyString());
    }

    @Test
    void request_returns400_forAMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void confirm_returnsSuccess_whenTheServiceDoesNotThrow() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw-token\",\"newPassword\":\"newPassword123\"}")).andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"Password reset successful.\"}"));
    }

    @Test
    void confirm_returns400_forATooShortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw-token\",\"newPassword\":\"short\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void confirm_returns409_whenTheTokenIsInvalidOrExpired() throws Exception {
        org.mockito.Mockito.doThrow(new com.example.relay.user.exception.InvalidOrExpiredResetTokenException("bad"))
                .when(passwordResetService).confirmReset(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw-token\",\"newPassword\":\"newPassword123\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"Invalid or expired password reset token.\"}"));
    }
}
