package com.example.relay.attempt.infrastructure;

import java.util.UUID;

/**
 * PostgreSQL parent locks for appending Attempts to an existing Delivery. Every operation requires an active,
 * caller-owned Spring transaction; locks remain held until that transaction commits or rolls back.
 */
public interface AttemptAllocationRepository {
    /**
     * Locks Endpoint FOR SHARE and reads its current activity before locking the matching Delivery FOR UPDATE. Returns
     * false only for an existing inactive Endpoint, without locking Delivery. Missing parents or a Delivery belonging
     * to another Endpoint fail loudly.
     */
    boolean lockReplayAllocationParentsIfEndpointActive(UUID endpointId, UUID deliveryId);

    /**
     * Locks Endpoint FOR KEY SHARE before locking the matching Delivery FOR UPDATE. Endpoint activity is not retry
     * eligibility. Missing parents or a Delivery belonging to another Endpoint fail loudly.
     */
    void lockRetryAllocationParents(UUID endpointId, UUID deliveryId);

    /**
     * Returns the current maximum Attempt ordinal plus one (one for an empty history). Safe only after an ordered
     * parent-lock operation above acquired this Delivery's lock in the same transaction. The caller must insert the
     * allocated Attempt before that transaction ends; rollback consumes no ordinal.
     */
    int nextAttemptNoUnderDeliveryLock(UUID deliveryId);
}
