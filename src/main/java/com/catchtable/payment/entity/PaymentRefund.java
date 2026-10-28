package com.catchtable.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

// 환불 요청 (결제별 멱등키 1건, 결제 행 잠금 안에서 변경)
@Entity
@Table(name = "payment_refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "idempotency_key", nullable = false, length = 300)
    private String idempotencyKey;

    // 요청 원본 (생략 시 null)
    @Column(name = "requested_amount")
    private Long requestedAmount;

    @Column(name = "cancel_reason", nullable = false, length = 500)
    private String cancelReason;

    // 확정 환불액
    @Column(name = "refund_amount", nullable = false)
    private long refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentRefundStatus status;

    // 처리 후 누적 환불액
    @Column(name = "total_refunded_amount")
    private Long totalRefundedAmount;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 510)
    private String failureMessage;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    private PaymentRefund(Payment payment, String idempotencyKey, Long requestedAmount, String cancelReason,
                          long refundAmount, PaymentRefundStatus status, OffsetDateTime now) {
        this.payment = payment;
        this.idempotencyKey = idempotencyKey;
        this.requestedAmount = requestedAmount;
        this.cancelReason = cancelReason;
        this.refundAmount = refundAmount;
        this.status = status;
        this.requestedAt = now;
    }

    // Toss 취소 요청 전
    public static PaymentRefund requested(Payment payment, String idempotencyKey, Long requestedAmount,
                                          String cancelReason, long refundAmount, OffsetDateTime now) {
        return new PaymentRefund(payment, idempotencyKey, requestedAmount, cancelReason, refundAmount,
                PaymentRefundStatus.REQUESTED, now);
    }

    // 정책상 0원
    public static PaymentRefund notRequired(Payment payment, String idempotencyKey, String cancelReason,
                                            OffsetDateTime now) {
        PaymentRefund refund = new PaymentRefund(payment, idempotencyKey, null, cancelReason, 0,
                PaymentRefundStatus.NOT_REQUIRED, now);
        refund.totalRefundedAmount = payment.getRefundedAmount();
        return refund;
    }

    // 같은 요청 원본 여부
    public boolean isSameRequest(Long requestedAmount, String cancelReason) {
        return Objects.equals(this.requestedAmount, requestedAmount) && this.cancelReason.equals(cancelReason);
    }

    // 환불 확정 (이미 확정이면 유지)
    public void complete(long totalRefundedAmount, OffsetDateTime now) {
        if (!status.isInFlight()) {
            return;
        }
        status = PaymentRefundStatus.COMPLETED;
        this.totalRefundedAmount = totalRefundedAmount;
        completedAt = now;
    }

    // Toss 거절 (처리 중일 때만)
    public void fail(String code, String message) {
        if (!status.isInFlight()) {
            return;
        }
        status = PaymentRefundStatus.FAILED;
        failureCode = code;
        failureMessage = message;
    }

    // 결과 불명 (처리 중일 때만)
    public void markUnknown() {
        if (status == PaymentRefundStatus.REQUESTED) {
            status = PaymentRefundStatus.UNKNOWN;
        }
    }
}
