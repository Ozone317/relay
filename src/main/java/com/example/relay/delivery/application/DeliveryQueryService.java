package com.example.relay.delivery.application;

import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.delivery.infrastructure.DeliveryStatusRepository;
import com.example.relay.delivery.infrastructure.DeliveryStatusSpecifications;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class DeliveryQueryService {

    private final AppRepository appRepository;
    private final DeliveryStatusRepository deliveryStatusRepository;
    private final DeliveryRepository deliveryRepository;
    private final AttemptRepository attemptRepository;

    public DeliveryQueryService(AppRepository appRepository, DeliveryStatusRepository deliveryStatusRepository,
            DeliveryRepository deliveryRepository, AttemptRepository attemptRepository) {
        this.appRepository = appRepository;
        this.deliveryStatusRepository = deliveryStatusRepository;
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
    }

    public Page<DeliveryStatus> getPage(UUID appId, UUID environmentId, UUID userId, UUID endpointId,
            AttemptStatus status, Instant createdFrom, Instant createdTo, Pageable pageable) {
        appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId)
                .orElseThrow(() -> new AppNotFoundException(appId));

        return deliveryStatusRepository.findAll(
                DeliveryStatusSpecifications.matching(appId, endpointId, status, createdFrom, createdTo), pageable);
    }

    public DeliveryDetail getById(UUID deliveryId, UUID appId, UUID environmentId, UUID userId) {
        Delivery delivery = deliveryRepository
                .findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deliveryId, appId, environmentId, userId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        // Structurally unreachable in practice: delivery_status inner-joins attempts (V5), and a
        // Delivery is only ever created in the same transaction as its first Attempt (see
        // AttemptService), so a Delivery row existing (checked above) guarantees a matching
        // delivery_status row exists too. Kept as a defensive orElseThrow rather than an unchecked
        // .get() so a future change to that invariant fails loudly instead of NPE-ing - do not
        // "simplify" this into believing the row can never be missing.
        DeliveryStatus status = deliveryStatusRepository.findById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        return new DeliveryDetail(status, delivery.getMessage().getBody());
    }

    public record DeliveryDetail(DeliveryStatus status, JsonNode payload) {}

    public Page<Attempt> getAttempts(UUID deliveryId, UUID appId, UUID environmentId, UUID userId,
            Pageable pageable) {
        deliveryRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deliveryId, appId,
                environmentId, userId).orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        return attemptRepository.findByDeliveryId(deliveryId, pageable);
    }

    public Attempt getAttemptDetail(UUID attemptId, UUID deliveryId, UUID appId, UUID environmentId, UUID userId) {
        return attemptRepository
                .findByIdAndDeliveryIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(attemptId, deliveryId, appId,
                        environmentId, userId)
                .orElseThrow(() -> new AttemptNotFoundException(attemptId));
    }
}
