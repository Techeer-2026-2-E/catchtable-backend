package com.catchtable.payment.dto;

import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentRefund;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.entity.PaymentStatus;

import java.time.OffsetDateTime;

// 환불 응답 (Notion 결제 취소·환불 계약, 같은 키 재요청은 최초 결과)
public record RefundResult(
        Long paymentId,
        Long reservationId,
        String currency,
        long refundAmount,
        long totalRefundedAmount,
        long remainingAmount,
        PaymentStatus paymentStatus,
        PaymentRefundStatus refundStatus,   // COMPLETED, NOT_REQUIRED
        OffsetDateTime refundedAt           // NOT_REQUIRED는 null
) {
    // 처리 당시 누적액 기준
    public static RefundResult from(PaymentRefund refund) {
        Payment payment = refund.getPayment();
        long total = refund.getTotalRefundedAmount();
        return new RefundResult(
                payment.getId(),
                payment.getReservation().getId(),
                "KRW",
                refund.getRefundAmount(),
                total,
                payment.getAmount() - total,
                statusAt(total, payment.getAmount()),
                refund.getStatus(),
                refund.getCompletedAt());
    }

    private static PaymentStatus statusAt(long totalRefunded, long amount) {
        if (totalRefunded == 0) {
            return PaymentStatus.DONE;
        }
        return totalRefunded == amount ? PaymentStatus.REFUNDED : PaymentStatus.PARTIAL_REFUNDED;
    }
}
