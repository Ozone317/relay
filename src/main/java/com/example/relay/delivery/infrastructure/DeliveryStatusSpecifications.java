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
 * Mirrors {@link com.example.relay.attempt.infrastructure.AttemptSpecifications} exactly - builds
 * the deliveries-list query from only the filters actually supplied, using JPA Criteria so every
 * bind parameter is correctly typed. Do not replace this with a native query using
 * {@code (:param IS NULL OR ...)} - see AttemptSpecifications' own javadoc for why that broke on
 * PostgreSQL (handoff Section 16, commit 3c66e67).
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
