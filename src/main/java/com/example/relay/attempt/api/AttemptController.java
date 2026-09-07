package com.example.relay.attempt.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.relay.attempt.api.dto.AttemptDetailDto;
import com.example.relay.attempt.api.dto.AttemptSummaryDto;
import com.example.relay.attempt.application.AttemptQueryService;
import com.example.relay.attempt.application.AttemptReplayService;
import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.attempt.exception.ActiveAttemptAlreadyExistsException;
import com.example.relay.attempt.exception.AttemptNotDeadException;
import com.example.relay.attempt.exception.AttemptNotFoundException;
import com.example.relay.attempt.exception.ReplayEndpointInactiveException;
import com.example.relay.attempt.mapper.AttemptMapper;
import com.example.relay.common.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/apps/{appId}")
public class AttemptController {

    private AttemptQueryService attemptQueryService;
    private AttemptReplayService attemptReplayService;
    private AttemptMapper attemptMapper;

    public AttemptController(AttemptQueryService attemptQueryService, AttemptReplayService attemptReplayService,
            AttemptMapper attemptMapper) {
        this.attemptQueryService = attemptQueryService;
        this.attemptReplayService = attemptReplayService;
        this.attemptMapper = attemptMapper;
    }

    @GetMapping("/attempts")
    public ResponseEntity<Page<AttemptSummaryDto>> getAll(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @RequestParam(required = false) UUID endpointId,
        @RequestParam(required = false) AttemptStatus status,
        @RequestParam(required = false) Instant createdFrom,
        @RequestParam(required = false) Instant createdTo,
        @AuthenticationPrincipal AuthenticatedUser user,
        Pageable pageable
    ) {
        Page<Attempt> attempts = attemptQueryService.getPage(
            appId,
            environmentId,
            user.getId(),
            endpointId,
            status,
            createdFrom,
            createdTo,
            pageable
        );

        return ResponseEntity.ok(attempts.map(attemptMapper::toSummaryDto));
    }

    @GetMapping("/attempts/{attemptId}")
    public ResponseEntity<AttemptDetailDto> getById(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @PathVariable UUID attemptId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) throws AttemptNotFoundException {
        Attempt attempt = attemptQueryService.getById(attemptId, appId, environmentId, user.getId());
        AttemptDetailDto response = attemptMapper.toDetailDto(attempt);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/attempts/{attemptId}/replay")
    public ResponseEntity<AttemptSummaryDto> replay(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @PathVariable UUID attemptId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) throws AttemptNotFoundException, AttemptNotDeadException, ReplayEndpointInactiveException,
            ActiveAttemptAlreadyExistsException {
        Attempt replay = attemptReplayService.replay(attemptId, appId, environmentId, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(attemptMapper.toSummaryDto(replay));
    }
}
