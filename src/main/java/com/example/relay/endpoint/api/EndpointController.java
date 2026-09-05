package com.example.relay.endpoint.api;

import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.common.security.AuthenticatedUser;
import com.example.relay.endpoint.api.dto.EndpointCreateDto;
import com.example.relay.endpoint.api.dto.EndpointCreatedDto;
import com.example.relay.endpoint.api.dto.EndpointResponseDto;
import com.example.relay.endpoint.api.dto.EndpointUpdateDto;
import com.example.relay.endpoint.application.EndpointService;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.exception.EndpointAlreadyExistsException;
import com.example.relay.endpoint.exception.EndpointNotFoundException;
import com.example.relay.endpoint.mapper.EndpointMapper;
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
@RequestMapping("/api/v1/environments/{environmentId}/apps/{appId}")
public class EndpointController {

    private final EndpointService endpointService;
    private final EndpointMapper endpointMapper;

    public EndpointController(EndpointService endpointService, EndpointMapper endpointMapper) {
        this.endpointService = endpointService;
        this.endpointMapper = endpointMapper;
    }

    @PostMapping("/endpoints")
    public ResponseEntity<EndpointCreatedDto> create(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @AuthenticationPrincipal AuthenticatedUser user, @RequestBody @Valid EndpointCreateDto request)
            throws AppNotFoundException, EndpointAlreadyExistsException {
        Endpoint endpoint = endpointService.create(request, appId, environmentId, user.getId());
        EndpointCreatedDto response = endpointMapper.toEndpointCreatedDto(endpoint);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/endpoints/{endpointId}")
    public ResponseEntity<EndpointResponseDto> getById(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @PathVariable UUID endpointId, @AuthenticationPrincipal AuthenticatedUser user)
            throws EndpointNotFoundException {
        Endpoint endpoint = endpointService.getById(endpointId, appId, environmentId, user.getId());
        EndpointResponseDto response = endpointMapper.toEndpointResponseDto(endpoint);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/endpoints")
    public ResponseEntity<List<EndpointResponseDto>> getAll(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<Endpoint> endpoints = endpointService.getAll(appId, environmentId, user.getId());
        List<EndpointResponseDto> response = endpointMapper.toEndpointResponseDtoList(endpoints);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PatchMapping("/endpoints/{endpointId}")
    public ResponseEntity<EndpointResponseDto> update(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @PathVariable UUID endpointId, @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody @Valid EndpointUpdateDto request) throws EndpointNotFoundException {
        Endpoint endpoint = endpointService.update(request, endpointId, appId, environmentId, user.getId());
        EndpointResponseDto response = endpointMapper.toEndpointResponseDto(endpoint);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @DeleteMapping("/endpoints/{endpointId}")
    public ResponseEntity<Void> delete(@PathVariable UUID environmentId, @PathVariable UUID appId,
            @PathVariable UUID endpointId, @AuthenticationPrincipal AuthenticatedUser user)
            throws EndpointNotFoundException {
        endpointService.delete(endpointId, appId, environmentId, user.getId());

        return ResponseEntity.noContent().build();
    }
}
