package com.example.relay.attempt.application;

import com.example.relay.attempt.domain.Attempt;
import java.time.Instant;

public record AttemptExecution(Attempt attempt, long generation, Instant claimedAt) {}
