package com.example.relay.delivery.application;

import com.example.relay.attempt.application.AttemptService;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.delivery.infrastructure.DeliveryStatusRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DeliveryReplayService {
    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusRepository deliveryStatusRepository;
    private final AttemptService attemptService;

    public DeliveryReplayService(DeliveryRepository deliveryRepository,
            DeliveryStatusRepository deliveryStatusRepository, AttemptService attemptService) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryStatusRepository = deliveryStatusRepository;
        this.attemptService = attemptService;
    }

    public DeliveryStatus replay(UUID deliveryId, UUID appId, UUID environmentId, UUID userId) {
        Delivery delivery = deliveryRepository
                .findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deliveryId, appId, environmentId, userId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        attemptService.createReplay(delivery);
        // The allocation service commits before this first view read, including in an OSIV-bound HTTP request.
        return deliveryStatusRepository.findById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
    }
}
