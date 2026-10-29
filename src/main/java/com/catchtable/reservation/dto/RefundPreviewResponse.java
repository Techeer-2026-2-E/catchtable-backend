package com.catchtable.reservation.dto;

import com.catchtable.reservation.entity.RefundPolicy;

import java.time.OffsetDateTime;

public record RefundPreviewResponse(
        Long reservationId,
        Long paymentId,
        String currency,
        long paidAmount,
        long alreadyRefundedAmount,
        RefundPolicy policyType,
        OffsetDateTime reservationStartAt,
        OffsetDateTime calculatedAt,
        int refundRate,
        long refundableAmount,
        long nonRefundableAmount,
        String policyDescription
) {
}
