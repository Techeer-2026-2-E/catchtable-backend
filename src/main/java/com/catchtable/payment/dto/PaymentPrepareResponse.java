package com.catchtable.payment.dto;

import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.reservation.entity.RefundPolicy;
import java.util.List;

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
        long remainingSeconds,
        RefundPolicy refundPolicy,
        String refundPolicyName,
        List<RefundPolicy.Rule> refundRules,
        String refundPolicyNotice
) {
    public static PaymentPrepareResponse from(Payment payment, OffsetDateTime now) {
        var reservation = payment.getReservation();
        return new PaymentPrepareResponse(payment.getId(), reservation.getId(), reservation.getId(),
                payment.getOrderId(), payment.getOrderName(), payment.getAmount(), payment.getStatus(),
                reservation.getHoldExpiresAt(), now,
                Math.max(0, Duration.between(now, reservation.getHoldExpiresAt()).toSeconds()),
                reservation.getRefundPolicySnapshot(), reservation.getRefundPolicySnapshot().getDescription(),
                reservation.getRefundPolicySnapshot().getRefundRules(),
                "매장 현지시간(Asia/Seoul)의 예약일 기준입니다. 점주 취소는 전액 환불하며 노쇼는 환불하지 않습니다.");
    }
}
