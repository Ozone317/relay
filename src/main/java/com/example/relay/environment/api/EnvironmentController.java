package com.example.relay.environment.api;

import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.environment.api.dto.EnvironmentCreateDto;
import com.example.relay.environment.api.dto.EnvironmentResponseDto;
import com.example.relay.environment.api.dto.EnvironmentUpdateDto;
import com.example.relay.environment.application.EnvironmentService;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.mapper.EnvironmentMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;
    private final EnvironmentMapper environmentMapper;

    public EnvironmentController(EnvironmentService environmentService, EnvironmentMapper environmentMapper) {
        this.environmentService = environmentService;
        this.environmentMapper = environmentMapper;
    }

    @PostMapping
    public ResponseEntity<EnvironmentResponseDto> create(@Valid @RequestBody EnvironmentCreateDto request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Environment environment = environmentService.create(request, user.getId());
        EnvironmentResponseDto responseDto = environmentMapper.toResponseDto(environment);
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }

    // list all environments
    @GetMapping
    public List<EnvironmentResponseDto> getAll(@AuthenticationPrincipal AuthenticatedUser user) {
        List<Environment> environments = environmentService.getAll(user.getId());
        return environmentMapper.toResponseDtoList(environments);
    }

    // get environment by id
    @GetMapping("/{id}")
    public EnvironmentResponseDto getById(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        Environment environment = environmentService.getById(id, user.getId());
        return environmentMapper.toResponseDto(environment);
    }

    // update environment description
    @PatchMapping("/{id}")
    public EnvironmentResponseDto partialUpdate(@PathVariable UUID id, @Valid @RequestBody EnvironmentUpdateDto request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Environment environment = environmentService.updateDescription(id, request.description(), user.getId());
        return environmentMapper.toResponseDto(environment);
    }

    // delete environment by id
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        environmentService.delete(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
