package com.example.relay.delivery.infrastructure;

import com.example.relay.delivery.domain.DeliveryStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeliveryStatusRepository
        extends JpaRepository<DeliveryStatus, UUID>, JpaSpecificationExecutor<DeliveryStatus> {
}
