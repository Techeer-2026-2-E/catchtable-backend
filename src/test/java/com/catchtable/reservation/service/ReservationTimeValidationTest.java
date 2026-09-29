package com.catchtable.reservation.service;

import com.catchtable.store.entity.Store;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservationTimeValidationTest {

    private final ReservationAvailabilityService service = new ReservationAvailabilityService();
    private final Store store = Store.builder()
            .reservationDurationMinutes(120)
            .reservationSlotMinutes(30)
            .arrivalGraceMinutes(10)
            .build();

    @Test
    void validatesFutureTimeAndStoreSlot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-29T09:00:00+09:00");

        assertDoesNotThrow(() -> service.validateBookable(store, now.plusHours(1), now));
        assertThrows(ResponseStatusException.class,
                () -> service.validateBookable(store, now.minusMinutes(30), now));
        assertThrows(ResponseStatusException.class,
                () -> service.validateBookable(store, now.plusMinutes(45), now));
    }
}
