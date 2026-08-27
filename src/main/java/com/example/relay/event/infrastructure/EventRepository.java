package com.example.relay.event.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.relay.event.domain.Event;

public interface EventRepository extends JpaRepository<Event, UUID> {
    List<Event> findAllByAppId(UUID appId);
    Optional<Event> findByNameAndAppId(String name, UUID appId);
}
