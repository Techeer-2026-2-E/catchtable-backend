package com.catchtable.payment.entity;

import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.reservation.entity.Reservation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

// 결제 시도 (상태 변경은 엔티티 메서드로만)
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 결제 고객
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // 선점·예약 (같은 행)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "order_id", nullable = false, unique = true, length = 64)
    private String orderId;

    @Column(name = "order_name", nullable = false, length = 100)
    private String orderName;

    @Column(nullable = false)
    private long amount;

    // 승인 전 null
    @Column(name = "payment_key", unique = true, length = 200)
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // Toss 결제수단 (예: 카드, 간편결제)
    @Column(length = 20)
    private String method;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    // 누적 환불액
    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    @Column(name = "receipt_url", length = 500)
    private String receiptUrl;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 510)
    private String failureMessage;

    // 환불 필요 표시 (예약 확정 없는 승인)
    @Column(name = "refund_required_at")
    private OffsetDateTime refundRequiredAt;

    @Column(name = "refund_required_reason", length = 300)
    private String refundRequiredReason;

    @Version
    @Column(nullable = false)
    private long version;

    private Payment(Member member, Reservation reservation, String orderId, String orderName, long amount) {
        this.member = member;
        this.reservation = reservation;
        this.orderId = orderId;
        this.orderName = orderName;
        this.amount = amount;
        this.status = PaymentStatus.READY;
    }

    // 결제 대기 생성
    public static Payment ready(Member member, Reservation reservation, String orderId, String orderName, long amount) {
        if (orderId == null || orderId.isBlank() || orderName == null || orderName.isBlank() || amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new Payment(member, reservation, orderId, orderName, amount);
    }

    public PaymentStatusHistory initialHistory(OffsetDateTime now) {
        if (status != PaymentStatus.READY) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        return PaymentStatusHistory.record(this, status, PaymentStatusSource.USER, "결제 준비", now);
    }

    // 승인 (같은 paymentKey 재요청은 null)
    public PaymentStatusHistory approve(String paymentKey, String method, OffsetDateTime approvedAt, String receiptUrl,
                                        PaymentStatusSource source, OffsetDateTime now) {
        if (paymentKey == null || paymentKey.isBlank() || approvedAt == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (status.isApproved() && paymentKey.equals(this.paymentKey)) {
            return null;
        }
        PaymentStatusHistory history = change(PaymentStatus.DONE, source, "결제 승인", now);
        this.paymentKey = paymentKey;
        this.method = method;
        this.approvedAt = approvedAt;
        this.receiptUrl = receiptUrl;
        return history;
    }

    // 승인 실패
    public PaymentStatusHistory fail(String code, String message, PaymentStatusSource source, OffsetDateTime now) {
        String failureCode = validateDetail(code);
        if (status == PaymentStatus.FAILED && failureCode.equals(this.failureCode)) {
            return null;
        }
        PaymentStatusHistory history = change(PaymentStatus.FAILED, source, "결제 실패: " + failureCode, now);
        this.failureCode = failureCode;
        this.failureMessage = message;
        return history;
    }

    // 승인 전 취소·이탈
    public PaymentStatusHistory cancel(String reason, PaymentStatusSource source, OffsetDateTime now) {
        String detail = validateDetail(reason);
        if (status == PaymentStatus.CANCELED) {
            return null;
        }
        return change(PaymentStatus.CANCELED, source, detail, now);
    }

    // 결과 불명
    public PaymentStatusHistory markTimeout(String reason, PaymentStatusSource source, OffsetDateTime now) {
        String detail = validateDetail(reason);
        if (status == PaymentStatus.TIMEOUT) {
            return null;
        }
        return change(PaymentStatus.TIMEOUT, source, detail, now);
    }

    // 환불 (이번 환불액이 아닌 누적 환불액 기준)
    public PaymentStatusHistory refund(long totalRefundedAmount, String reason, PaymentStatusSource source,
                                       OffsetDateTime now) {
        String detail = validateDetail(reason);
        if (totalRefundedAmount > amount) {
            throw new BusinessException(ErrorCode.REFUND_AMOUNT_EXCEEDED);
        }
        // 누적액 감소 거부
        if (totalRefundedAmount <= 0 || totalRefundedAmount < refundedAmount) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if ((status == PaymentStatus.PARTIAL_REFUNDED || status == PaymentStatus.REFUNDED)
                && totalRefundedAmount == refundedAmount) {
            return null;
        }
        PaymentStatus next = totalRefundedAmount == amount ? PaymentStatus.REFUNDED : PaymentStatus.PARTIAL_REFUNDED;
        PaymentStatusHistory history = change(next, source,
                "누적 환불 %d원: %s".formatted(totalRefundedAmount, detail), now);
        refundedAmount = totalRefundedAmount;
        return history;
    }

    // 환불 필요 표시 (처음 1회만 이력, 상태 유지)
    public PaymentStatusHistory requireRefund(String reason, OffsetDateTime now) {
        String detail = validateDetail(reason);
        if (!status.isApproved()) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        if (refundRequiredAt != null) {
            return null;
        }
        refundRequiredAt = now;
        refundRequiredReason = detail;
        return PaymentStatusHistory.record(this, status, PaymentStatusSource.SYSTEM, "환불 필요: " + detail, now);
    }

    private PaymentStatusHistory change(PaymentStatus next, PaymentStatusSource source, String detail,
                                        OffsetDateTime now) {
        if (!status.canChangeTo(next)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        PaymentStatusHistory history = PaymentStatusHistory.record(this, next, Objects.requireNonNull(source),
                detail, now);
        status = next;
        return history;
    }

    private static String validateDetail(String detail) {
        if (detail == null || detail.isBlank() || detail.length() > 300) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return detail.strip();
    }
}
