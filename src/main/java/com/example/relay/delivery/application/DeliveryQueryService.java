package com.example.relay.delivery.application;

import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.AttemptStatus;
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

    public DeliveryQueryService(AppRepository appRepository, DeliveryStatusRepository deliveryStatusRepository,
            DeliveryRepository deliveryRepository) {
        this.appRepository = appRepository;
        this.deliveryStatusRepository = deliveryStatusRepository;
        this.deliveryRepository = deliveryRepository;
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

        DeliveryStatus status = deliveryStatusRepository.findById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        return new DeliveryDetail(status, delivery.getMessage().getBody());
    }

    public record DeliveryDetail(DeliveryStatus status, JsonNode payload) {}
}
