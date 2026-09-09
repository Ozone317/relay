package com.example.relay.delivery.infrastructure;

import com.example.relay.attempt.domain.AttemptStatus;
import com.example.relay.delivery.domain.DeliveryStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the deliveries-list query from only the filters actually supplied, using JPA Criteria so
 * every bind parameter is correctly typed.
 *
 * <p>
 * This deliberately avoids a single JPQL/native query of the shape {@code (:param IS NULL OR column
 * = :param)}, which is broken on PostgreSQL: Postgres fixes parameter types at PREPARE time from the
 * SQL text alone, and a parameter whose only appearance is {@code ? IS NULL} offers nothing to infer
 * from, so the statement fails with {@code SQLState 42P18 - could not determine data type of
 * parameter}. That happened on every call regardless of which filters the caller passed, because it
 * is the syntactic position that defeats inference, not the runtime value. H2 (used for repository
 * tests at the time this was first discovered, in {@code AttemptSpecifications}) accepted the same
 * SQL happily, which is why those tests stayed green while the endpoint was completely dead on the
 * real database (handoff Section 16, commit 3c66e67).
 *
 * <p>
 * Omitting an absent filter instead of neutralising it with {@code IS NULL} sidesteps the whole
 * class of problem - no untyped parameter is ever emitted - and it keeps the predicate list short
 * enough for supporting indexes to remain usable, which an {@code OR}-based filter would defeat.
 * This mirrors the pattern this project first established for attempts in the (now-deleted, see
 * Task 7) {@code AttemptSpecifications}.
 */
public final class DeliveryStatusSpecifications {

    private DeliveryStatusSpecifications() {}

    public static Specification<DeliveryStatus> matching(UUID appId, UUID endpointId, AttemptStatus status,
            Instant createdFrom, Instant createdTo) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(criteriaBuilder.equal(root.get("appId"), appId));

            if (endpointId != null) {
                predicates.add(criteriaBuilder.equal(root.get("endpointId"), endpointId));
            }
            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            if (createdFrom != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("deliveryCreatedAt"), createdFrom));
            }
            if (createdTo != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("deliveryCreatedAt"), createdTo));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
