package com.example.relay.attempt.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AttemptAllocationRepositoryImpl implements AttemptAllocationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AttemptAllocationRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockReplayAllocationParentsIfEndpointActive(UUID endpointId, UUID deliveryId) {
        var parameters = parentParameters(endpointId, deliveryId);
        boolean active = jdbc.queryForObject("""
                SELECT is_active FROM endpoints WHERE id = :endpointId FOR SHARE
                """, parameters, Boolean.class);
        if (!active) {
            return false;
        }
        lockDelivery(parameters);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockRetryAllocationParents(UUID endpointId, UUID deliveryId) {
        var parameters = parentParameters(endpointId, deliveryId);
        jdbc.queryForObject("""
                SELECT id FROM endpoints WHERE id = :endpointId FOR KEY SHARE
                """, parameters, UUID.class);
        lockDelivery(parameters);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int nextAttemptNoUnderDeliveryLock(UUID deliveryId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM attempts WHERE delivery_id = :deliveryId
                """, new MapSqlParameterSource("deliveryId", deliveryId), Integer.class);
    }

    private void lockDelivery(MapSqlParameterSource parameters) {
        jdbc.queryForObject("""
                SELECT id FROM deliveries
                WHERE id = :deliveryId AND endpoint_id = :endpointId
                FOR UPDATE
                """, parameters, UUID.class);
    }

    private MapSqlParameterSource parentParameters(UUID endpointId, UUID deliveryId) {
        return new MapSqlParameterSource("endpointId", endpointId).addValue("deliveryId", deliveryId);
    }
}
