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
import com.example.relay.user.application.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmailVerificationController.class)
@Import({SecurityConfig.class, AuthProperties.class, CsrfHeaderFilter.class, RefreshCookieFactory.class})
class EmailVerificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void verify_returnsTheGenericMessage_whenTheServiceDoesNotThrow() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verification/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw-token\",\"password\":\"realOwnerPassword\"}")).andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"Email verified successfully.\"}"));

        verify(emailVerificationService).verify("raw-token", "realOwnerPassword");
    }

    @Test
    void verify_returns409_whenTheTokenIsInvalidOrExpired() throws Exception {
        org.mockito.Mockito.doThrow(new com.example.relay.user.exception.InvalidOrExpiredVerificationTokenException("bad"))
                .when(emailVerificationService).verify(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/email-verification/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw-token\",\"password\":\"realOwnerPassword\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"Invalid or expired verification link.\"}"));
    }

    @Test
    void verify_returns400_forATooShortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verification/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw-token\",\"password\":\"short\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void verify_returns400_forABlankPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verification/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"raw-token\",\"password\":\"\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void resend_returnsTheGenericMessage_forAnyInput() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");

        mockMvc.perform(post("/api/v1/auth/email-verification/resend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"anyone@example.com\"}")).andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"message\":\"If an account needs verifying, a verification email has been sent.\"}"));

        verify(emailVerificationService).resend(anyString(), anyString());
    }

    @Test
    void resend_returns400_forAMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verification/resend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}")).andExpect(status().isBadRequest());
    }
}
