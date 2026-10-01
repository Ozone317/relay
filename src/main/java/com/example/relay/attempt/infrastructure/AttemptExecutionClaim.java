package com.example.relay.attempt.infrastructure;

import java.time.Instant;

public record AttemptExecutionClaim(long generation, Instant claimedAt) {}
