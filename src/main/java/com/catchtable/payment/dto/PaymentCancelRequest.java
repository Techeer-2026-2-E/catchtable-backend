package com.catchtable.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentCancelRequest(@NotBlank @Size(max = 500) String cancelReason,
                                   @Positive Long cancelAmount) {
}
