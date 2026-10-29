package com.catchtable.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentPrepareRequest(@NotNull @Positive Long holdId) {
}
