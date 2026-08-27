package com.example.relay.app.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.relay.app.api.dto.AppCreateDto;
import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.app.mapper.AppMapper;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.exception.EnvironmentNotFoundException;
import com.example.relay.environment.infrastructure.EnvironmentRepository;

@Service
public class AppService {

    private final EnvironmentRepository environmentRepository;
    private final AppMapper appMapper;
    private final AppRepository appRepository;
    
    public AppService(EnvironmentRepository environmentRepository, AppMapper appMapper, AppRepository appRepository) {
        this.environmentRepository = environmentRepository;
        this.appMapper = appMapper;
        this.appRepository = appRepository;
    }

    public App create(AppCreateDto dto, UUID environmentId, UUID userId) {
        Environment env = environmentRepository.findByIdAndUserId(
            environmentId,
            userId
        ).orElseThrow(
            () -> new EnvironmentNotFoundException(environmentId)
        );

        App app = appMapper.toEntity(dto, env);
        
        app = appRepository.save(app);
        
        return app;
    }

    public List<App> getAll(UUID environmentId, UUID userId) {
        environmentRepository.findByIdAndUserId(
            environmentId,
            userId
        ).orElseThrow(
            () -> new EnvironmentNotFoundException(environmentId)
        );

        List<App> apps = appRepository.findAllByEnvironmentId(environmentId);
        
        return apps;
    }

    public App getById(UUID id, UUID environmentId, UUID userId) {
        return appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(
            id,
            environmentId,
            userId
        ).orElseThrow(
            () -> new AppNotFoundException(id)
        );
    }

    public void delete(UUID id, UUID environmentId, UUID userId) {
        App app = appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(
            id,
            environmentId,
            userId
        ).orElseThrow(
            () -> new AppNotFoundException(id)
        );

        appRepository.delete(app);
    }
}
