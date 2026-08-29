package com.example.relay.event.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.relay.event.domain.Event;

public interface EventRepository extends JpaRepository<Event, UUID> {
    List<Event> findAllByAppId(UUID appId);
    Optional<Event> findByNameAndAppId(String name, UUID appId);

    @Query(
        """
            SELECT e
            FROM Event e
            WHERE
                e.id = :id
                AND e.app.id = :appId
                AND e.app.environment.id = :environmentId
                AND e.app.environment.user.id = :userId
        """
    )
    Optional<Event> findByIdAndAppIdAndEnvironmentIdAndUserId(
        UUID id,
        UUID appId,
        UUID environmentId,
        UUID userId
    );
}
