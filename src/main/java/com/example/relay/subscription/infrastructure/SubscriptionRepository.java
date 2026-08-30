package com.example.relay.subscription.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.relay.subscription.domain.Subscription;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    @Query(
        """
            SELECT s
            FROM Subscription s
            WHERE
                s.app.id = :appId
                AND s.app.environment.id = :environmentId
                AND s.app.environment.user.id = :userId
        """
    )
    List<Subscription> findAllByAppIdAndEnvironmentIdAndUserId(
        UUID appId,
        UUID environmentId,
        UUID userId
    );

    @Query(
        """
            SELECT s
            FROM Subscription s
            WHERE
                s.app.id = :appId
                AND s.app.environment.id = :environmentId
                AND s.endpoint.id = :endpointId
                AND s.app.environment.user.id = :userId
        """
    )
    List<Subscription> findAllByAppIdAndEnvironmentIdAndEndpointIdAndUserId(
        UUID appId,
        UUID environmentId,
        UUID endpointId,
        UUID userId
    );

    @Query(
        """
            SELECT s
            FROM Subscription s
            WHERE
                s.app.id = :appId
                AND s.app.environment.id = :environmentId
                AND s.event.id = :eventId
                AND s.endpoint.id = :endpointId
                AND s.app.environment.user.id = :userId
        """
    )
    Optional<Subscription> findByAppIdAndEnvironmentIdAndEventIdAndEndpointIdAndUserId(
        UUID appId,
        UUID environmentId,
        UUID eventId,
        UUID endpointId,
        UUID userId
    );

    @Query(
        """
            SELECT COUNT(s)
            FROM Subscription s
            WHERE
                s.app.id = :appId
                AND s.app.environment.id = :environmentId
                AND s.event.id = :eventId
                AND s.app.environment.user.id = :userId
        """
    )
    long countByAppIdAndEnvironmentIdAndEventIdAndUserId(UUID appId, UUID environmentId, UUID eventId, UUID userId);

    @Query(
        """
            SELECT s.event.id AS eventId, COUNT(s) AS count
            FROM Subscription s
            WHERE
                s.event.id IN :eventIds
                AND s.app.id = :appId
                AND s.app.environment.id = :environmentId
                AND s.app.environment.user.id = :userId
            GROUP BY s.event.id
        """
    )
    List<EventSubscriptionCount> countByEventIdIn(UUID appId, UUID environmentId, List<UUID> eventIds, UUID userId);

    /*
     * Spring Data maps the AS eventId/AS count aliases onto matching getter names automatically.
     */
    public interface EventSubscriptionCount {
        UUID getEventId();
        Long getCount();    
    }
}
