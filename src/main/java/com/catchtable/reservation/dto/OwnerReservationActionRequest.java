package com.catchtable.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OwnerReservationActionRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
