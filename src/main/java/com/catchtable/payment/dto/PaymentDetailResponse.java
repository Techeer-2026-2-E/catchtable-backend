package com.catchtable.payment.dto;

import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentRefund;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.OffsetDateTime;
import java.util.List;

// 결제 상세 (목록 필드 + 환불 이력)
public record PaymentDetailResponse(
        @JsonUnwrapped PaymentResponse payment,
        List<Refund> refunds    // 요청순
) {
    public static PaymentDetailResponse of(Payment payment, List<PaymentRefund> refunds) {
        return new PaymentDetailResponse(PaymentResponse.from(payment),
                refunds.stream().map(Refund::from).toList());
    }

    public record Refund(
            Long refundId,
            long refundAmount,
            PaymentRefundStatus status,
            String cancelReason,
            OffsetDateTime refundedAt   // 확정 전 null
    ) {
        static Refund from(PaymentRefund refund) {
            return new Refund(refund.getId(), refund.getRefundAmount(), refund.getStatus(),
                    refund.getCancelReason(), refund.getCompletedAt());
        }
    }
}
