package com.catchtable.reservation.service;

import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import com.catchtable.store.service.BusinessHourService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationTimeValidationTest {

    private final BusinessHourService businessHourService = mock(BusinessHourService.class);
    private final ReservationAvailabilityService service = new ReservationAvailabilityService(
            mock(StoreRepository.class), mock(StoreTableRepository.class), businessHourService);
    private final Store store = Store.builder()
            .reservationDurationMinutes(120)
            .reservationSlotMinutes(30)
            .arrivalGraceMinutes(10)
            .build();

    @Test
    void validatesFutureTimeAndStoreSlot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-29T09:00:00+09:00");
        when(businessHourService.isOpenBetween(any(), any(), any())).thenReturn(true);

        assertDoesNotThrow(() -> service.validateBookable(store, now.plusHours(1), now));
        assertThrows(ResponseStatusException.class,
                () -> service.validateBookable(store, now.minusMinutes(30), now));
        assertThrows(ResponseStatusException.class,
                () -> service.validateBookable(store, now.plusMinutes(45), now));
    }

    @Test
    void rejectsTimeOutsideBusinessHours() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-29T09:00:00+09:00");

        assertThrows(ResponseStatusException.class,
                () -> service.validateBookable(store, now.plusHours(1), now));
    }
}
