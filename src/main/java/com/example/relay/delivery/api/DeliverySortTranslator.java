package com.example.relay.delivery.api;

import com.example.relay.delivery.exception.InvalidSortPropertyException;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Translates the {@code sort} query parameter's client-facing property names into the real JPA
 * property names the backing entity ({@code DeliveryStatus} or {@code Attempt}) actually has,
 * before a {@link Pageable} reaches the repository layer.
 *
 * <p>
 * {@code DeliverySummaryDto} exposes three fields under names that don't match {@code
 * DeliveryStatus}'s own property names ({@code id}/{@code deliveryId}, {@code createdAt}/{@code
 * deliveryCreatedAt}, {@code latestAttemptNo}/{@code attemptNo}). Without translation, {@code
 * sort=id} (a perfectly reasonable client guess, since that's the field name in the response body)
 * throws an unhandled {@code PropertyReferenceException} from deep inside Spring Data, surfacing as
 * a raw 500 - a client input error dressed up as a server fault. Translating first, and rejecting
 * anything left over with {@link InvalidSortPropertyException}, keeps that distinction honest.
 */
final class DeliverySortTranslator {

    private DeliverySortTranslator() {}

    private static final Map<String, String> DELIVERY_STATUS_FIELDS = Map.ofEntries(
            // DTO aliases that don't match DeliveryStatus's own property names.
            Map.entry("id", "deliveryId"),
            Map.entry("createdAt", "deliveryCreatedAt"),
            Map.entry("latestAttemptNo", "attemptNo"),
            // Real DeliveryStatus/DeliverySummaryDto property names, identity-mapped so callers who
            // already know the entity's own names aren't rejected either.
            Map.entry("deliveryId", "deliveryId"),
            Map.entry("deliveryCreatedAt", "deliveryCreatedAt"),
            Map.entry("attemptNo", "attemptNo"),
            Map.entry("status", "status"),
            Map.entry("responseCode", "responseCode"),
            Map.entry("latencyMs", "latencyMs"),
            Map.entry("attemptCount", "attemptCount"),
            Map.entry("lastAttemptAt", "lastAttemptAt"),
            Map.entry("eventName", "eventName"),
            Map.entry("endpointId", "endpointId"),
            Map.entry("endpointName", "endpointName"),
            Map.entry("messageId", "messageId"));

    private static final Map<String, String> ATTEMPT_FIELDS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("attemptNo", "attemptNo"),
            Map.entry("status", "status"),
            Map.entry("responseCode", "responseCode"),
            Map.entry("latencyMs", "latencyMs"),
            Map.entry("nextRetryAt", "nextRetryAt"),
            Map.entry("deadLetterNotifiedAt", "deadLetterNotifiedAt"),
            Map.entry("createdAt", "createdAt"),
            Map.entry("updatedAt", "updatedAt"));

    static Pageable translateForDeliveryStatus(Pageable pageable) {
        return translate(pageable, DELIVERY_STATUS_FIELDS);
    }

    static Pageable translateForAttempt(Pageable pageable) {
        return translate(pageable, ATTEMPT_FIELDS);
    }

    private static Pageable translate(Pageable pageable, Map<String, String> fieldMap) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }

        Sort translatedSort = Sort.by(pageable.getSort().stream()
                .map(order -> {
                    String realProperty = fieldMap.get(order.getProperty());
                    if (realProperty == null) {
                        throw new InvalidSortPropertyException(order.getProperty(), fieldMap.keySet());
                    }
                    return new Sort.Order(order.getDirection(), realProperty);
                })
                .toList());

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), translatedSort);
    }
}
