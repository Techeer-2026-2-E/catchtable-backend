package com.catchtable.payment.dto;

import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;

import java.time.OffsetDateTime;

// 프런트 Payment 타입 기준
public record PaymentResponse(
        Long paymentId,
        Long reservationId,     // 승인 이후에만 (선점과 같은 id)
        Long holdId,
        String orderId,
        String orderName,
        long amount,
        PaymentStatus status,
        String method,
        OffsetDateTime approvedAt,
        long canceledAmount,    // 누적 환불액
        String receiptUrl
) {
    public static PaymentResponse from(Payment payment) {
        Long reservationId = payment.getReservation().getId();
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus().isApproved() ? reservationId : null,
                reservationId,
                payment.getOrderId(),
                payment.getOrderName(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getMethod(),
                payment.getApprovedAt(),
                payment.getRefundedAmount(),
                payment.getReceiptUrl());
    }
}
