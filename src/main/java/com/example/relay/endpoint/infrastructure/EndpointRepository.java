package com.example.relay.endpoint.infrastructure;

import com.example.relay.endpoint.domain.Endpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    @Query("""
                SELECT e
                FROM Endpoint e
                WHERE
                    e.app.id = :appId
                    AND e.app.environment.id = :environmentId
                    AND e.app.environment.user.id = :userId
            """)
    List<Endpoint> findAllByAppIdAndEnvironmentIdAndUserId(UUID appId, UUID environmentId, UUID userId);

    @Query("""
                SELECT e
                FROM Endpoint e
                WHERE
                    e.id = :id
                    AND e.app.id = :appId
                    AND e.app.environment.id = :environmentId
                    AND e.app.environment.user.id = :userId
            """)
    Optional<Endpoint> findByIdAndAppIdAndEnvironmentIdAndUserId(UUID id, UUID appId, UUID environmentId, UUID userId);

    @Query("""
                SELECT e
                FROM Endpoint e
                WHERE
                    e.name = :name
                    AND e.app.id = :appId
                    AND e.app.environment.id = :environmentId
                    AND e.app.environment.user.id = :userId
            """)
    Optional<Endpoint> findByNameAndAppIdAndEnvironmentIdAndUserId(String name, UUID appId, UUID environmentId,
            UUID userId);
}
