package com.example.relay.delivery.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.relay.delivery.exception.InvalidSortPropertyException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class DeliverySortTranslatorTest {

    @Test
    void translateForDeliveryStatus_translatesADtoAlias_toItsRealEntityPropertyName() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "latestAttemptNo"));

        Pageable translated = DeliverySortTranslator.translateForDeliveryStatus(pageable);

        Sort.Order order = translated.getSort().getOrderFor("attemptNo");
        assertEquals(Sort.Direction.ASC, order.getDirection());
        assertEquals(1, translated.getSort().stream().count());
    }

    @Test
    void translateForDeliveryStatus_passesThroughAnIdentityMappedLegalName() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "status"));

        Pageable translated = DeliverySortTranslator.translateForDeliveryStatus(pageable);

        Sort.Order order = translated.getSort().getOrderFor("status");
        assertEquals(Sort.Direction.DESC, order.getDirection());
    }

    @Test
    void translateForDeliveryStatus_throwsInvalidSortProperty_forAnIllegalName() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("notAnActualField"));

        assertThrows(InvalidSortPropertyException.class,
                () -> DeliverySortTranslator.translateForDeliveryStatus(pageable));
    }

    @Test
    void translateForDeliveryStatus_returnsTheSamePageable_whenUnsorted() {
        Pageable pageable = PageRequest.of(0, 20);

        Pageable translated = DeliverySortTranslator.translateForDeliveryStatus(pageable);

        assertEquals(pageable, translated);
    }

    @Test
    void translateForAttempt_passesThroughALegalAttemptFieldName() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "attemptNo"));

        Pageable translated = DeliverySortTranslator.translateForAttempt(pageable);

        Sort.Order order = translated.getSort().getOrderFor("attemptNo");
        assertEquals(Sort.Direction.DESC, order.getDirection());
    }

    @Test
    void translateForAttempt_throwsInvalidSortProperty_forAnIllegalName() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("garbage"));

        assertThrows(InvalidSortPropertyException.class, () -> DeliverySortTranslator.translateForAttempt(pageable));
    }
}
