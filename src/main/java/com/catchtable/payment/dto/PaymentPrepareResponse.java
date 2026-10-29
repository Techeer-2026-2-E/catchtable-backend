package com.catchtable.payment.dto;

import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;

import java.time.Duration;
import java.time.OffsetDateTime;

public record PaymentPrepareResponse(
        Long paymentId,
        Long reservationId,
        Long holdId,
        String orderId,
        String orderName,
        long amount,
        PaymentStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime serverTime,
        long remainingSeconds
) {
    public static PaymentPrepareResponse from(Payment payment, OffsetDateTime now) {
        var reservation = payment.getReservation();
        return new PaymentPrepareResponse(payment.getId(), reservation.getId(), reservation.getId(),
                payment.getOrderId(), payment.getOrderName(), payment.getAmount(), payment.getStatus(),
                reservation.getHoldExpiresAt(), now,
                Math.max(0, Duration.between(now, reservation.getHoldExpiresAt()).toSeconds()));
    }
}
