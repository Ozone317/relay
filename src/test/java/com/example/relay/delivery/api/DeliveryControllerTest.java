package com.example.relay.delivery.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.relay.attempt.api.dto.AttemptDetailDto;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.mapper.AttemptMapper;
import com.example.relay.common.security.AuthProperties;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.common.security.CsrfHeaderFilter;
import com.example.relay.common.security.CustomUserDetailsService;
import com.example.relay.common.security.JwtService;
import com.example.relay.common.security.RefreshCookieFactory;
import com.example.relay.common.security.SecurityConfig;
import com.example.relay.delivery.api.dto.DeliveryAttemptSummaryDto;
import com.example.relay.delivery.api.dto.DeliveryDetailDto;
import com.example.relay.delivery.api.dto.DeliverySummaryDto;
import com.example.relay.delivery.application.DeliveryQueryService;
import com.example.relay.delivery.application.DeliveryQueryService.DeliveryDetail;
import com.example.relay.delivery.application.DeliveryReplayService;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.mapper.DeliveryMapper;
import com.example.relay.user.application.AuthService;
import com.example.relay.user.domain.User;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryQueryService.getPage(eq(appId), eq(environmentId), eq(user.getId()), any(), any(), any(),
                any(), any())).thenReturn(new PageImpl<>(java.util.List.of(new DeliveryStatus())));
        when(deliveryMapper.toSummaryDto(any())).thenReturn(new DeliverySummaryDto(deliveryId,
                "payment.completed", endpointId, "EP 1", AttemptStatus.SUCCEEDED, 1, 1, 200, 100L,
                Instant.now(), Instant.now()));

        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/deliveries", environmentId, appId)
                .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(deliveryId.toString()))
                .andExpect(jsonPath("$.content[0].eventName").value("payment.completed"))
                .andExpect(jsonPath("$.content[0].endpointId").value(endpointId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.content[0].latestAttemptNo").value(1));
    }

    @Test
    void getAll_passesEndpointStatusAndDateFilters_toTheService() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Instant createdFrom = Instant.parse("2026-01-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-02-01T00:00:00Z");
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryQueryService.getPage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/deliveries", environmentId, appId)
                .param("endpointId", endpointId.toString())
                .param("status", "DEAD")
                .param("createdFrom", createdFrom.toString())
                .param("createdTo", createdTo.toString())
                .with(authentication(auth)))
                .andExpect(status().isOk());

        ArgumentCaptor<UUID> endpointIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<AttemptStatus> statusCaptor = ArgumentCaptor.forClass(AttemptStatus.class);
        ArgumentCaptor<Instant> createdFromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> createdToCaptor = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(deliveryQueryService).getPage(eq(appId), eq(environmentId), eq(user.getId()),
                endpointIdCaptor.capture(), statusCaptor.capture(), createdFromCaptor.capture(),
                createdToCaptor.capture(), any(Pageable.class));

        org.junit.jupiter.api.Assertions.assertEquals(endpointId, endpointIdCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(AttemptStatus.DEAD, statusCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(createdFrom, createdFromCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(createdTo, createdToCaptor.getValue());
    }

    @Test
    void getById_returns200_withTheDeliveryDetail() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());
        ObjectNode payload = new ObjectMapper().createObjectNode().put("amount", 4999);

        DeliveryDetail detail = new DeliveryDetail(new DeliveryStatus(), payload);
        when(deliveryQueryService.getById(deliveryId, appId, environmentId, user.getId())).thenReturn(detail);
        when(deliveryMapper.toDetailDto(detail.status(), detail.payload())).thenReturn(new DeliveryDetailDto(
                deliveryId, "payment.completed", UUID.randomUUID(), "EP 1", messageId, payload,
                AttemptStatus.SUCCEEDED, 1, 1, 200, 100L, Instant.now(), Instant.now()));

        mockMvc.perform(get("/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}",
                environmentId, appId, deliveryId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deliveryId.toString()))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.payload.amount").value(4999))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
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
    void getAttempts_returns200_withAPageOfAttemptSummaries() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        // DeliveryMapper is mocked, so the Page's actual element content is irrelevant here - a
        // single null placeholder is enough to drive one row through toAttemptSummaryDto, which is
        // stubbed below to the DTO shape under test. Attempt has no public no-arg constructor, so a
        // real instance isn't a convenient option in a slice test like this one.
        when(deliveryQueryService.getAttempts(eq(deliveryId), eq(appId), eq(environmentId), eq(user.getId()),
                any())).thenReturn(new PageImpl<>(java.util.Collections.singletonList(null)));
        when(deliveryMapper.toAttemptSummaryDto(any())).thenReturn(new DeliveryAttemptSummaryDto(attemptId, 3,
                AttemptStatus.SUCCEEDED, 200, 150L, Instant.now()));

        mockMvc.perform(get(
                "/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/attempts",
                environmentId, appId, deliveryId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(attemptId.toString()))
                .andExpect(jsonPath("$.content[0].attemptNo").value(3))
                .andExpect(jsonPath("$.content[0].status").value("SUCCEEDED"));
    }

    @Test
    void getAttemptDetail_returns200_withTheAttemptDetail() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryQueryService.getAttemptDetail(attemptId, deliveryId, appId, environmentId, user.getId()))
                .thenReturn(null);
        when(attemptMapper.toDetailDto(null)).thenReturn(new AttemptDetailDto(attemptId, deliveryId, 3,
                AttemptStatus.SUCCEEDED, 200, "ok", null, 150L, null, Instant.now(), Instant.now()));

        mockMvc.perform(get(
                "/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/attempts/{attemptId}",
                environmentId, appId, deliveryId, attemptId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attemptId.toString()))
                .andExpect(jsonPath("$.deliveryId").value(deliveryId.toString()))
                .andExpect(jsonPath("$.attemptNo").value(3))
                .andExpect(jsonPath("$.responseBody").value("ok"));
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

    @Test
    void replay_returns201_withTheUpdatedDeliverySummary() throws Exception {
        User user = new User("test@mail.com", "passwordHash");
        UUID environmentId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Authentication auth = authFor(user.getId(), user.getEmail());

        when(deliveryReplayService.replay(deliveryId, appId, environmentId, user.getId()))
                .thenReturn(new DeliveryStatus());
        when(deliveryMapper.toSummaryDto(any())).thenReturn(new DeliverySummaryDto(deliveryId,
                "payment.completed", UUID.randomUUID(), "EP 1", AttemptStatus.CREATED, 7, 7, null, null,
                Instant.now(), Instant.now()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/environments/{environmentId}/apps/{appId}/deliveries/{deliveryId}/replay",
                        environmentId, appId, deliveryId)
                .with(authentication(auth)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(deliveryId.toString()))
                .andExpect(jsonPath("$.latestAttemptNo").value(7))
                .andExpect(jsonPath("$.attemptCount").value(7))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }
}
