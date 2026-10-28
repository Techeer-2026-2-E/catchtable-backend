package com.catchtable.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

// 결제 상태 변경 이력
@Entity
@Table(name = "payment_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 20)
    private PaymentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatusSource source;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    static PaymentStatusHistory record(Payment payment, PaymentStatus status, PaymentStatusSource source,
                                       String detail, OffsetDateTime now) {
        PaymentStatusHistory history = new PaymentStatusHistory();
        history.payment = payment;
        history.previousStatus = payment.getStatus();
        history.status = status;
        history.source = source;
        history.detail = detail;
        history.createdAt = now;
        return history;
    }
}
