package com.example.relay.attempt.application;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.attempt.exception.AttemptNotDeadException;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.exception.ReplayEndpointInactiveException;
import com.example.relay.attempt.infrastructure.AttemptRepository;
import com.example.relay.delivery.publisher.AttemptPublisher;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class AttemptReplayService {

    private static final List<AttemptStatus> ACTIVE_STATUSES =
            List.of(AttemptStatus.CREATED, AttemptStatus.IN_FLIGHT, AttemptStatus.SCHEDULED);

    private final AttemptRepository attemptRepository;
    private final AttemptService attemptService;
    private final AttemptPublisher attemptPublisher;

    public AttemptReplayService(AttemptRepository attemptRepository, AttemptService attemptService,
            AttemptPublisher attemptPublisher) {
        this.attemptRepository = attemptRepository;
        this.attemptService = attemptService;
        this.attemptPublisher = attemptPublisher;
    }

    public Attempt replay(UUID attemptId, UUID appId, UUID environmentId, UUID userId) {
        Attempt original = attemptRepository
                .findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(attemptId, appId, environmentId, userId)
                .orElseThrow(() -> new AttemptNotFoundException(attemptId));

        if (original.getStatus() != AttemptStatus.DEAD) {
            throw new AttemptNotDeadException(attemptId, original.getStatus());
        }

        if (!original.getEndpoint().isActive()) {
            throw new ReplayEndpointInactiveException(original.getEndpoint().getId());
        }

        UUID messageId = original.getMessage().getId();
        UUID endpointId = original.getEndpoint().getId();

        if (attemptRepository.existsByMessageIdAndEndpointIdAndStatusIn(messageId, endpointId, ACTIVE_STATUSES)) {
            throw new ActiveAttemptAlreadyExistsException(messageId, endpointId);
        }

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
        return replay;
    }
}
