package com.example.relay.delivery.application;

import com.example.relay.attempt.application.AttemptService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.domain.Delivery;
import com.example.relay.delivery.domain.DeliveryStatus;
import com.example.relay.delivery.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.delivery.exception.DeliveryNotDeadException;
import com.example.relay.delivery.exception.DeliveryNotFoundException;
import com.example.relay.delivery.exception.ReplayEndpointInactiveException;
import com.example.relay.delivery.infrastructure.DeliveryRepository;
import com.example.relay.delivery.infrastructure.DeliveryStatusRepository;
import com.example.relay.deliveryengine.publisher.AttemptPublisher;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DeliveryReplayService {

    private static final List<AttemptStatus> ACTIVE_STATUSES =
            List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusRepository deliveryStatusRepository;
    private final AttemptRepository attemptRepository;
    private final AttemptService attemptService;
    private final AttemptPublisher attemptPublisher;
    private final EntityManager entityManager;

    public DeliveryReplayService(DeliveryRepository deliveryRepository,
            DeliveryStatusRepository deliveryStatusRepository, AttemptRepository attemptRepository,
            AttemptService attemptService, AttemptPublisher attemptPublisher, EntityManager entityManager) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryStatusRepository = deliveryStatusRepository;
        this.attemptRepository = attemptRepository;
        this.attemptService = attemptService;
        this.attemptPublisher = attemptPublisher;
        this.entityManager = entityManager;
    }

    public DeliveryStatus replay(UUID deliveryId, UUID appId, UUID environmentId, UUID userId) {
        Delivery delivery = deliveryRepository
                .findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(deliveryId, appId, environmentId, userId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        DeliveryStatus current = deliveryStatusRepository.findById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        if (current.getStatus() != AttemptStatus.DEAD) {
            throw new DeliveryNotDeadException(deliveryId, current.getStatus());
        }

        if (!delivery.getEndpoint().isActive()) {
            throw new ReplayEndpointInactiveException(delivery.getEndpoint().getId());
        }

        UUID messageId = delivery.getMessage().getId();
        UUID endpointId = delivery.getEndpoint().getId();

        if (attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(messageId, endpointId, ACTIVE_STATUSES)) {
            throw new ActiveAttemptAlreadyExistsException(messageId, endpointId);
        }

        Attempt original = attemptRepository.findById(current.getLatestAttemptId())
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        Attempt replay;
        try {
            replay = attemptService.createReplay(original);
        } catch (DataIntegrityViolationException ex) {
            // Lost the check-then-insert race against a concurrent replay for the same pair. The
            // check above is only a fast path; idx_attempts_one_active_per_message_endpoint is the
            // authority.
            throw new ActiveAttemptAlreadyExistsException(messageId, endpointId);
        }

        attemptPublisher.publish(replay.getId());

        // The DeliveryStatus row loaded at the top of this method is in the session's identity
        // map, and open-in-view binds one session to the whole request - so a plain findById here
        // returns that stale instance without issuing any SQL. Re-querying via JPQL would not help
        // either (Hibernate returns the managed instance without refreshing its state), and
        // @Immutable means it is never refreshed on flush. Evict it so the read below really hits
        // the delivery_status view after createReplay's commit.
        entityManager.detach(current);
        return deliveryStatusRepository.findById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
    }
}
