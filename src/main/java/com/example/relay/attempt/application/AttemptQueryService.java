package com.example.relay.attempt.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.infrastructure.AttemptRepository;

@Service
public class AttemptQueryService {

    private final AppRepository appRepository;
    private final AttemptRepository attemptRepository;

    public AttemptQueryService(AppRepository appRepository, AttemptRepository attemptRepository) {
        this.appRepository = appRepository;
        this.attemptRepository = attemptRepository;
    }

    public Page<Attempt> getPage(
        UUID appId,
        UUID environmentId,
        UUID userId,
        UUID endpointId,
        AttemptStatus status,
        Instant createdFrom,
        Instant createdTo,
        Pageable pageable
    ) throws AppNotFoundException {
        appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(appId, environmentId, userId)
            .orElseThrow(() -> new AppNotFoundException(appId));
        
        return attemptRepository.findByAppIdAndFilters(appId, endpointId, status, createdFrom, createdTo, pageable);
    }

    public Attempt getById(
        UUID attemptId,
        UUID appId,
        UUID environmentId,
        UUID userId
    ) throws AttemptNotFoundException {
        return attemptRepository.findByIdAndAppIdAndAppEnvironmentIdAndAppEnvironmentUserId(attemptId, appId, environmentId, userId)
            .orElseThrow(() -> new AttemptNotFoundException(attemptId));
    }
}
