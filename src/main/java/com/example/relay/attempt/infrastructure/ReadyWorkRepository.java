package com.example.relay.attempt.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface ReadyWorkRepository {

    List<UUID> promoteDueScheduled(int batchSize);

    List<UUID> claimUnpublishedReady(UUID claimId, Duration grace, int batchSize);

    int markReadyPublished(UUID attemptId, UUID claimId);
}
