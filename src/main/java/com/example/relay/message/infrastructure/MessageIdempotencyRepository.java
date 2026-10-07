package com.example.relay.message.infrastructure;

import com.example.relay.message.api.MessageIdempotencyKey;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MessageIdempotencyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MessageIdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<MessageIdempotencyAcquisition> tryAcquire(UUID userId, UUID appId, MessageIdempotencyKey key,
            UUID proposedMessageId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("appId", appId)
                .addValue("idempotencyKey", key.value())
                .addValue("messageId", proposedMessageId);
        return jdbc.query("""
                INSERT INTO message_idempotency (
                    user_id, app_id, idempotency_key, fingerprint_version, message_id, accepted_at
                ) VALUES (
                    :userId, :appId, :idempotencyKey, 1, :messageId, transaction_timestamp()
                )
                ON CONFLICT (user_id, app_id, idempotency_key) DO NOTHING
                RETURNING message_id, accepted_at, fingerprint_version
                """, parameters, rows -> {
            if (!rows.next()) {
                return Optional.empty();
            }
            return Optional.of(new MessageIdempotencyAcquisition(
                    rows.getObject("message_id", UUID.class), rows.getTimestamp("accepted_at").toInstant()));
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CommittedMessageIdempotency findCommittedAndCompare(UUID userId, UUID appId, MessageIdempotencyKey key,
            UUID eventId, JsonNode body) {
        Map<String, ?> parameters = Map.of(
                "userId", userId,
                "appId", appId,
                "idempotencyKey", key.value(),
                "eventId", eventId,
                "body", body.toString());
        return jdbc.query("""
                SELECT i.message_id,
                       i.accepted_at,
                       i.fingerprint_version,
                       (i.fingerprint_version = 1
                        AND m.event_id = :eventId
                        AND m.body = CAST(:body AS jsonb)) AS fingerprint_matches
                FROM message_idempotency i
                JOIN messages m ON m.id = i.message_id
                WHERE i.user_id = :userId
                  AND i.app_id = :appId
                  AND i.idempotency_key = :idempotencyKey
                """, parameters, rows -> {
            if (!rows.next()) {
                throw new IllegalStateException("idempotency conflict did not resolve to a committed Message");
            }
            return new CommittedMessageIdempotency(rows.getObject("message_id", UUID.class),
                    rows.getTimestamp("accepted_at").toInstant(), rows.getShort("fingerprint_version"),
                    rows.getBoolean("fingerprint_matches"));
        });
    }
}
