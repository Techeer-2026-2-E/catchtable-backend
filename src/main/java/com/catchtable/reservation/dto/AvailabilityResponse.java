package com.catchtable.reservation.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record AvailabilityResponse(
        Long storeId,
        LocalDate date,
        int partySize,
        List<Slot> slots
) {
    public record Slot(OffsetDateTime reservationStartAt, long availableTableCount, boolean available) {
    }
}
