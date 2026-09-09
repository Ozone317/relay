package com.example.relay.delivery.infrastructure;

import com.example.relay.delivery.domain.Delivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Optional<Delivery> findByMessageIdAndEndpointId(UUID messageId, UUID endpointId);

    Optional<Delivery> findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(UUID id, UUID appId,
            UUID environmentId, UUID userId);
}
