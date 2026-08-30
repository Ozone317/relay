package com.example.relay.subscription.api;

import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.subscription.api.dto.SubscriptionResponseDto;
import com.example.relay.subscription.application.SubscriptionService;
import com.example.relay.subscription.domain.Subscription;
import com.example.relay.subscription.mapper.SubscriptionMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/apps/{appId}/endpoints/{endpointId}")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionMapper subscriptionMapper;

    public SubscriptionController(SubscriptionService subscriptionService, SubscriptionMapper subscriptionMapper) {
        this.subscriptionService = subscriptionService;
        this.subscriptionMapper = subscriptionMapper;
    }

    @PutMapping("/subscriptions/{eventId}")
    public ResponseEntity<SubscriptionResponseDto> create(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @PathVariable UUID endpointId, @PathVariable UUID eventId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Subscription subscription = subscriptionService.create(environmentId, appId, endpointId, eventId, user.getId());

        SubscriptionResponseDto response = subscriptionMapper.toResponseDto(subscription);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<List<SubscriptionResponseDto>> getAll(@PathVariable UUID environmentId,
            @PathVariable UUID appId, @PathVariable UUID endpointId, @AuthenticationPrincipal AuthenticatedUser user) {
        List<Subscription> subscriptions = subscriptionService.getAll(environmentId, appId, endpointId, user.getId());
        List<SubscriptionResponseDto> response = subscriptionMapper.toResponseDtoList(subscriptions);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @DeleteMapping("/subscriptions/{eventId}")
    public ResponseEntity<Void> delete(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @PathVariable UUID endpointId, @PathVariable UUID eventId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        subscriptionService.delete(environmentId, appId, endpointId, eventId, user.getId());

        return ResponseEntity.noContent().build();
    }
}
