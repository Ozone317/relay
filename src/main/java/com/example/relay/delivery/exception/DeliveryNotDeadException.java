package com.example.relay.delivery.exception;

import com.example.relay.attempt.domain.AttemptStatus;
import java.util.UUID;

public class DeliveryNotDeadException extends RuntimeException {

    public DeliveryNotDeadException(UUID deliveryId, AttemptStatus actualStatus) {
        super("Delivery " + deliveryId + " is not eligible for replay (current status: " + actualStatus
                + "); only deliveries whose latest attempt is DEAD can be replayed");
    }
}
