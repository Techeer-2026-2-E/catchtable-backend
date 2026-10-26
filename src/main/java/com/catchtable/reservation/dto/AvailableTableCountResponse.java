package com.catchtable.reservation.dto;

import java.time.OffsetDateTime;

public record AvailableTableCountResponse(
        Long storeId,
        OffsetDateTime reservationStartAt,
        int partySize,
        long availableTableCount
) {
}
