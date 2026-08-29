package com.example.relay.endpoint.application;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.endpoint.api.dto.EndpointCreateDto;
import com.example.relay.endpoint.api.dto.EndpointUpdateDto;
import com.example.relay.endpoint.domain.Endpoint;
import com.example.relay.endpoint.exception.EndpointAlreadyExistsException;
import com.example.relay.endpoint.exception.EndpointNotFoundException;
import com.example.relay.endpoint.infrastructure.EndpointRepository;
import com.example.relay.endpoint.mapper.EndpointMapper;
import com.example.relay.endpoint.utils.SigningSecretGenerator;

@Service
public class EndpointService {

    private final AppRepository appRepository;
    private final EndpointMapper endpointMapper;
    private final EndpointRepository endpointRepository;
    private final SigningSecretGenerator signingSecretGenerator;

    public EndpointService(
        AppRepository appRepository,
        EndpointMapper endpointMapper,
        EndpointRepository endpointRepository,
        SigningSecretGenerator signingSecretGenerator
    ) {
        this.appRepository = appRepository;
        this.endpointMapper = endpointMapper;
        this.endpointRepository = endpointRepository;
        this.signingSecretGenerator = signingSecretGenerator;
    }

    public Endpoint create(
        EndpointCreateDto request,
        UUID appId,
        UUID environmentId,
        UUID userId
    ) throws AppNotFoundException,EndpointAlreadyExistsException {
        App app = appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(
            appId, environmentId, userId
        ).orElseThrow(
            () -> new AppNotFoundException(appId)
        );

        endpointRepository.findByNameAndAppIdAndEnvironmentIdAndUserId(
            request.name(),
            appId,
            environmentId,
            userId
        ).ifPresent(
            endpoint -> {
                throw new EndpointAlreadyExistsException(endpoint.getName());
            }
        );

        Endpoint toCreate = endpointMapper.toEntity(
            request,
            app,
            signingSecretGenerator.generate()
        );

        try {
            return endpointRepository.saveAndFlush(toCreate);
        } catch (DataIntegrityViolationException ex) {
            throw new EndpointAlreadyExistsException(request.name());
        }
    }

    public Endpoint getById(
        UUID endpointId,
        UUID appId,
        UUID environmentId,
        UUID userId
    ) {
        return endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(
            endpointId,
            appId,
            environmentId,
            userId
        ).orElseThrow(
            () -> new EndpointNotFoundException(endpointId)
        );
    }

    public List<Endpoint> getAll(
        UUID appId,
        UUID environmentId,
        UUID userId
    ) {
        return endpointRepository.findAllByAppIdAndEnvironmentIdAndUserId(
            appId,
            environmentId,
            userId
        );
    }

    public Endpoint update(
        EndpointUpdateDto request,
        UUID endpointId,
        UUID appId,
        UUID environmentId,
        UUID userId
    ) throws EndpointNotFoundException {
        Endpoint endpoint = endpointRepository.findByIdAndAppIdAndEnvironmentIdAndUserId(
            endpointId,
            appId,
            environmentId,
            userId
        ).orElseThrow(
            () -> new EndpointNotFoundException(endpointId)
        );

        if (request.name() != null) {
            endpoint.setName(request.name());
        }

        if (request.active() != null) {
            endpoint.setActive(request.active());
        }

        if (request.url() != null) {
            endpoint.setUrl(request.url());
        }

        endpointRepository.save(endpoint);

        return endpoint;
    }

    public void delete(
        UUID endpointId,
        UUID appId,
        UUID environmentId,
        UUID userId
    ) throws EndpointNotFoundException {
        Endpoint endpoint = getById(endpointId, appId, environmentId, userId);
        endpointRepository.delete(endpoint);
    }
}
