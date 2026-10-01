package com.example.relay.attempt.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record AttemptExecutionCandidate(UUID id, long generation, Instant claimedAt) {}
