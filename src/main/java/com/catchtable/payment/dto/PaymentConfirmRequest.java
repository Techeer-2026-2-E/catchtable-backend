package com.catchtable.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentConfirmRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 200) String paymentKey,
        @Positive long amount
) {
}
