package com.example.relay.attempt.exception;

import com.example.relay.attempt.domain.AttemptStatus;
import java.util.UUID;

public class AttemptNotDeadException extends RuntimeException {

    public AttemptNotDeadException(UUID attemptId, AttemptStatus actualStatus) {
        super("Attempt " + attemptId + " is not DEAD (current status: " + actualStatus
                + "); only DEAD attempts can be replayed");
    }
}
