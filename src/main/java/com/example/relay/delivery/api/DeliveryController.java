package com.example.relay.delivery.api;

import com.example.relay.attempt.api.dto.AttemptDetailDto;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.mapper.AttemptMapper;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.delivery.api.dto.DeliveryAttemptSummaryDto;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        @PageableDefault(sort = "deliveryCreatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Pageable translated = DeliverySortTranslator.translateForDeliveryStatus(pageable);
        Page<DeliveryStatus> deliveries = deliveryQueryService.getPage(
            appId, environmentId, user.getId(), endpointId, status, createdFrom, createdTo, translated
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

    @GetMapping("/{deliveryId}/attempts")
    public ResponseEntity<Page<DeliveryAttemptSummaryDto>> getAttempts(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @PathVariable UUID deliveryId,
        @AuthenticationPrincipal AuthenticatedUser user,
        @PageableDefault(sort = "attemptNo", direction = Sort.Direction.DESC) Pageable pageable
    ) throws DeliveryNotFoundException {
        Pageable translated = DeliverySortTranslator.translateForAttempt(pageable);
        Page<Attempt> attempts = deliveryQueryService.getAttempts(deliveryId, appId, environmentId, user.getId(),
                translated);

        return ResponseEntity.ok(attempts.map(deliveryMapper::toAttemptSummaryDto));
    }

    @GetMapping("/{deliveryId}/attempts/{attemptId}")
    public ResponseEntity<AttemptDetailDto> getAttemptDetail(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @PathVariable UUID deliveryId,
        @PathVariable UUID attemptId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) throws AttemptNotFoundException {
        Attempt attempt = deliveryQueryService.getAttemptDetail(attemptId, deliveryId, appId, environmentId,
                user.getId());

        return ResponseEntity.ok(attemptMapper.toDetailDto(attempt));
    }

    @PostMapping("/{deliveryId}/replay")
    public ResponseEntity<DeliverySummaryDto> replay(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @PathVariable UUID deliveryId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        DeliveryStatus updated = deliveryReplayService.replay(deliveryId, appId, environmentId, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(deliveryMapper.toSummaryDto(updated));
    }
}
