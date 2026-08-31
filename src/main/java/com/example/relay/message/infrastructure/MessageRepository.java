package com.example.relay.message.infrastructure;

import com.example.relay.message.domain.Message;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("""
                SELECT m
                FROM Message m
                WHERE
                    m.id = :id
                    AND m.app.id = :appId
                    AND m.app.environment.id = :environmentId
                    AND m.app.environment.user.id = :userId
            """)
    Optional<Message> findByIdAndAppIdAndEnvironmentIdAndUserId(UUID id, UUID appId, UUID environmentId, UUID userId);
}
