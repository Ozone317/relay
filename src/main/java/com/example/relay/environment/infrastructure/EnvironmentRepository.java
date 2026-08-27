package com.example.relay.environment.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.relay.environment.domain.Environment;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
    
    List<Environment> findAllByUserId(UUID userId);
    Optional<Environment> findByIdAndUserId(UUID id, UUID userId);
}
