package com.catchtable.reservation.dto;

import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

public record OwnerReservationUpdateRequest(
        OffsetDateTime reservationStartAt,
        @Positive Integer partySize
) {
}
