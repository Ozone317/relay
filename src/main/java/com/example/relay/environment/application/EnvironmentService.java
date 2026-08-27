package com.example.relay.environment.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.relay.environment.api.dto.EnvironmentCreateDto;
import com.example.relay.environment.domain.Environment;
import com.example.relay.environment.exception.EnvironmentNotFoundException;
import com.example.relay.environment.infrastructure.EnvironmentRepository;
import com.example.relay.environment.mapper.EnvironmentMapper;
import com.example.relay.user.domain.User;
import com.example.relay.user.infrastructure.UserRepository;

@Service
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;
    private final UserRepository userRepository;
    private final EnvironmentMapper environmentMapper;

    public EnvironmentService(EnvironmentRepository environmentRepository, UserRepository userRepository, EnvironmentMapper environmentMapper) {
        this.environmentRepository = environmentRepository;
        this.userRepository = userRepository;
        this.environmentMapper = environmentMapper;
    }

    public Environment create(EnvironmentCreateDto request, UUID userId){
        User user = userRepository.getReferenceById(userId);
        Environment environment = environmentMapper.toEntity(request, user);
        return environmentRepository.save(environment);
    }

    public List<Environment> getAll(UUID userId) {
        return environmentRepository.findAllByUserId(userId);
    }

    public Environment getById(UUID id, UUID userId) {
        return environmentRepository.findByIdAndUserId(id, userId)
                .orElseThrow(
                    () -> new EnvironmentNotFoundException(id)
                );

    }

    public Environment updateDescription(UUID id, String description, UUID userId) {
        Environment environment = getById(id, userId);
        environment.updateDescription(description);
        return environmentRepository.save(environment);
    }

    public void delete(UUID id, UUID userId) {
        Environment environment = getById(id, userId);
        environmentRepository.delete(environment);
    }
}
