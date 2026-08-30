package com.example.relay.environment.infrastructure;

import com.example.relay.environment.domain.Environment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {

    List<Environment> findAllByUserId(UUID userId);

    Optional<Environment> findByIdAndUserId(UUID id, UUID userId);
}
