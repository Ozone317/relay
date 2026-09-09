package com.example.relay.delivery.api;

import com.example.relay.attempt.mapper.AttemptMapper;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.delivery.api.dto.DeliveryDetailDto;
import com.example.relay.delivery.api.dto.DeliverySummaryDto;
import com.example.relay.delivery.application.DeliveryQueryService;
import com.example.relay.delivery.application.DeliveryQueryService.DeliveryDetail;
import com.example.relay.delivery.application.DeliveryReplayService;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.mapper.DeliveryMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.relay.attempt.domain.AttemptStatus;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/apps/{appId}/deliveries")
public class DeliveryController {

    private final DeliveryQueryService deliveryQueryService;
    private final DeliveryReplayService deliveryReplayService;
    private final DeliveryMapper deliveryMapper;
    private final AttemptMapper attemptMapper;

    public DeliveryController(DeliveryQueryService deliveryQueryService, DeliveryReplayService deliveryReplayService,
            DeliveryMapper deliveryMapper, AttemptMapper attemptMapper) {
        this.deliveryQueryService = deliveryQueryService;
        this.deliveryReplayService = deliveryReplayService;
        this.deliveryMapper = deliveryMapper;
        this.attemptMapper = attemptMapper;
    }

    @GetMapping
    public ResponseEntity<Page<DeliverySummaryDto>> getAll(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @RequestParam(required = false) UUID endpointId,
        @RequestParam(required = false) AttemptStatus status,
        @RequestParam(required = false) Instant createdFrom,
        @RequestParam(required = false) Instant createdTo,
        @AuthenticationPrincipal AuthenticatedUser user,
        Pageable pageable
    ) {
        Page<DeliveryStatus> deliveries = deliveryQueryService.getPage(
            appId, environmentId, user.getId(), endpointId, status, createdFrom, createdTo, pageable
        );

        return ResponseEntity.ok(deliveries.map(deliveryMapper::toSummaryDto));
    }

    @GetMapping("/{deliveryId}")
    public ResponseEntity<DeliveryDetailDto> getById(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @PathVariable UUID deliveryId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) throws DeliveryNotFoundException {
        DeliveryDetail detail = deliveryQueryService.getById(deliveryId, appId, environmentId, user.getId());
        return ResponseEntity.ok(deliveryMapper.toDetailDto(detail.status(), detail.payload()));
    }
}
