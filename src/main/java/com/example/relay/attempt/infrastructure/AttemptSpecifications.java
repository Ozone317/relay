package com.example.relay.attempt.infrastructure;

import com.example.relay.attempt.domain.Attempt;
import com.example.relay.attempt.domain.AttemptStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the dashboard's attempt-list query from only the filters actually supplied.
 *
 * <p>
 * This deliberately replaces a single JPQL query of the shape {@code (:param IS NULL OR column = :param)}, which is
 * broken on PostgreSQL: Postgres fixes parameter types at PREPARE time from the SQL text alone, and a parameter whose
 * only appearance is {@code ? IS NULL} offers nothing to infer from, so the statement fails with
 * {@code SQLState 42P18 - could not determine data type of parameter}. That happened on every call regardless of which
 * filters the caller passed, because it is the syntactic position that defeats inference, not the runtime value. H2
 * (used for all repository tests at the time) accepted the same SQL happily, which is why those tests stayed green
 * while the endpoint was completely dead on the real database.
 *
 * <p>
 * Omitting an absent filter instead of neutralising it with {@code IS NULL} sidesteps the whole class of problem — no
 * untyped parameter is ever emitted — and it keeps the predicate list short enough for the index on
 * {@code (endpoint_id, created_at)} to remain usable, which an {@code OR}-based filter defeats.
 */
public final class AttemptSpecifications {

    private AttemptSpecifications() {}

    public static Specification<Attempt> matching(UUID appId, UUID endpointId, AttemptStatus status,
            Instant createdFrom, Instant createdTo) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(criteriaBuilder.equal(root.get("app").get("id"), appId));

            if (endpointId != null) {
                predicates.add(criteriaBuilder.equal(root.get("endpoint").get("id"), endpointId));
            }
            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            // Both bounds inclusive, and filtering the attempt's OWN createdAt rather than the
            // parent message's - a retry has its own, later timestamp.
            if (createdFrom != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }
            if (createdTo != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), createdTo));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
