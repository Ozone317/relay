package com.example.relay.app.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.relay.app.domain.App;

public interface AppRepository extends JpaRepository<App, UUID> {

    List<App> findAllByEnvironmentId(UUID environmentId);
    Optional<App> findByIdAndEnvironmentUserId(UUID id, UUID userId);
    Optional<App> findByIdAndEnvironmentIdAndEnvironmentUserId(UUID id, UUID environmentId, UUID userId);
}
