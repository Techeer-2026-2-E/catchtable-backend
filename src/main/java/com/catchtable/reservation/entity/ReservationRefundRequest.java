package com.catchtable.reservation.entity;

import com.catchtable.payment.dto.RefundResult;
import com.catchtable.payment.entity.Payment;
import com.catchtable.payment.entity.PaymentRefund;
import com.catchtable.payment.entity.PaymentRefundStatus;
import com.catchtable.payment.entity.PaymentStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "reservation_refund_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationRefundRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reservation_id", nullable = false, updatable = false)
    private Reservation reservation;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;
    @Column(name = "idempotency_key", nullable = false, length = 300, updatable = false)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false)
    private ReservationRefundIntent intent;
    @Column(name = "cancel_reason", nullable = false, length = 500, updatable = false)
    private String cancelReason;
    @Column(name = "requested_amount", updatable = false)
    private Long requestedAmount;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;
    @Column(name = "reservation_start_at", nullable = false, updatable = false)
    private OffsetDateTime reservationStartAt;
    @Column(name = "policy_refund_limit", nullable = false, updatable = false)
    private long policyRefundLimit;
    @Column(name = "refund_amount", nullable = false, updatable = false)
    private long refundAmount;
    @Column(name = "refunded_amount_at_request", nullable = false, updatable = false)
    private long refundedAmountAtRequest;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private PaymentRefundStatus status;
    @Column(name = "total_refunded_amount")
    private Long totalRefundedAmount;
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public static ReservationRefundRequest requested(Reservation reservation, Payment payment, String key,
            ReservationRefundIntent intent, String reason, Long requestedAmount, OffsetDateTime now,
            long policyRefundLimit, long refundAmount) {
        var request = new ReservationRefundRequest();
        request.reservation = reservation;
        request.payment = payment;
        request.idempotencyKey = key;
        request.intent = intent;
        request.cancelReason = reason;
        request.requestedAmount = requestedAmount;
        request.requestedAt = now;
        request.reservationStartAt = reservation.getReservationStartAt();
        request.policyRefundLimit = policyRefundLimit;
        request.refundAmount = refundAmount;
        request.refundedAmountAtRequest = payment.getRefundedAmount();
        request.status = refundAmount == 0 ? PaymentRefundStatus.NOT_REQUIRED : PaymentRefundStatus.REQUESTED;
        if (refundAmount == 0) request.totalRefundedAmount = payment.getRefundedAmount();
        return request;
    }

    public boolean matches(Long paymentId, ReservationRefundIntent intent, String reason, Long requestedAmount) {
        return (paymentId == null || payment.getId().equals(paymentId)) && this.intent == intent
                && cancelReason.equals(reason) && Objects.equals(this.requestedAmount, requestedAmount);
    }

    public void apply(PaymentRefund refund) {
        status = refund.getStatus();
        totalRefundedAmount = refund.getTotalRefundedAmount();
        completedAt = refund.getCompletedAt();
    }

    public RefundResult result() {
        long total = totalRefundedAmount;
        long paid = payment.getAmount();
        return new RefundResult(payment.getId(), reservation.getId(), "KRW", refundAmount, total, paid - total,
                total == 0 ? PaymentStatus.DONE : total == paid ? PaymentStatus.REFUNDED : PaymentStatus.PARTIAL_REFUNDED,
                status, completedAt);
    }
}
