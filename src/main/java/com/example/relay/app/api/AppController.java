package com.example.relay.app.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.relay.app.api.dto.AppCreateDto;
import com.example.relay.app.api.dto.AppResponseDto;
import com.example.relay.app.application.AppService;
import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.mapper.AppMapper;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.environment.exception.EnvironmentNotFoundException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
public class AppController {

    private final AppService appService;
    private final AppMapper appMapper;

    public AppController(AppService appService, AppMapper appMapper) {
        this.appService = appService;
        this.appMapper = appMapper;
    }

    @PostMapping("/apps")
    public ResponseEntity<AppResponseDto> create(
        @PathVariable UUID environmentId,
        @Valid @RequestBody AppCreateDto request,
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        App createdApp = appService.create(request, environmentId, user.getId());
        AppResponseDto response = appMapper.toResponseDto(createdApp);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/apps")
    public ResponseEntity<List<AppResponseDto>> getAll(
        @PathVariable UUID environmentId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) throws EnvironmentNotFoundException {
        List<App> apps = appService.getAll(environmentId, user.getId());
        List<AppResponseDto> response = appMapper.toResponseDtoList(apps);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/apps/{appId}")
    public ResponseEntity<AppResponseDto> getById(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) throws AppNotFoundException {
        App app = appService.getById(appId, user.getId());
        AppResponseDto response = appMapper.toResponseDto(app);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @DeleteMapping("/apps/{appId}")
    public ResponseEntity<Void> delete(
        @PathVariable UUID environmentId,
        @PathVariable UUID appId,
        @AuthenticationPrincipal AuthenticatedUser user
    ) throws AppNotFoundException {
        appService.delete(appId, user.getId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(null);
    }
}
