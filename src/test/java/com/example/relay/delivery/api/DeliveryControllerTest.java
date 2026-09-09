package com.example.relay.delivery.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.mapper.AttemptMapper;
import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CsrfHeaderFilter;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.delivery.api.dto.DeliverySummaryDto;
import com.example.relay.delivery.application.DeliveryQueryService;
import com.example.relay.delivery.application.DeliveryQueryService.DeliveryDetail;
import com.example.relay.delivery.application.DeliveryReplayService;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.mapper.DeliveryMapper;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DeliveryController.class)
@Import({SecurityConfig.class, AuthProperties.class, CsrfHeaderFilter.class, RefreshCookieFactory.class})
class DeliveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeliveryQueryService deliveryQueryService;
    @MockitoBean
    private DeliveryReplayService deliveryReplayService;
    @MockitoBean
    private DeliveryMapper deliveryMapper;
    @MockitoBean
    private AttemptMapper attemptMapper;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private Authentication authFor(UUID userId, String email) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, email);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void getAll_returns200_withAPageOfDeliverySummaries() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryQueryService.getPage(eq(appId), eq(environmentId), eq(user.getId()), any(), any(), any(),
                any(), any())).thenReturn(new PageImpl<>(java.util.List.of(new DeliveryStatus())));
        when(deliveryMapper.toSummaryDto(any())).thenReturn(new DeliverySummaryDto(UUID.randomUUID(),
                "payment.completed", UUID.randomUUID(), "EP 1", AttemptStatus.SUCCEEDED, 1, 1, 200, 100L,
                Instant.now(), Instant.now()));

        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/deliveries", environmentId, appId)
                .with(authentication(auth)))
                .andExpect(status().isOk());
    }

    @Test
    void getById_returns404_whenServiceThrowsDeliveryNotFound() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryQueryService.getById(deliveryId, appId, environmentId, user.getId()))
                .thenThrow(new DeliveryNotFoundException(deliveryId));

        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}",
                environmentId, appId, deliveryId).with(authentication(auth)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAttempts_returns200() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryQueryService.getAttempts(eq(deliveryId), eq(appId), eq(environmentId), eq(user.getId()),
                any())).thenReturn(Page.empty());

        mockMvc.perform(get(
                "/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/attempts",
                environmentId, appId, deliveryId).with(authentication(auth)))
                .andExpect(status().isOk());
    }

    @Test
    void getAttemptDetail_returns404_whenAttemptNotFound() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryQueryService.getAttemptDetail(attemptId, deliveryId, appId, environmentId, user.getId()))
                .thenThrow(new com.example.relay.attempt.exception.AttemptNotFoundException(attemptId));

        mockMvc.perform(get(
                "/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/attempts/{attemptId}",
                environmentId, appId, deliveryId, attemptId).with(authentication(auth)))
                .andExpect(status().isNotFound());
    }
}
