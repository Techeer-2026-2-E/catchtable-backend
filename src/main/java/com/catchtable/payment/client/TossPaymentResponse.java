package com.catchtable.payment.client;

import com.catchtable.payment.entity.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

// Toss Payment 객체 중 사용하는 필드
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentResponse(
        String paymentKey,
        String orderId,
        String orderName,
        String status,
        String method,
        long totalAmount,
        long balanceAmount,     // 남은 취소 가능 금액
        OffsetDateTime approvedAt,
        Receipt receipt,
        Failure failure
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Receipt(String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String code, String message) {
    }

    // Toss 상태 → 내부 상태
    public PaymentStatus paymentStatus() {
        return switch (status) {
            case "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> PaymentStatus.READY;
            case "DONE" -> PaymentStatus.DONE;
            case "ABORTED" -> PaymentStatus.FAILED;
            case "EXPIRED" -> PaymentStatus.CANCELED;
            case "PARTIAL_CANCELED" -> PaymentStatus.PARTIAL_REFUNDED;
            case "CANCELED" -> PaymentStatus.REFUNDED;
            default -> throw new IllegalStateException("알 수 없는 Toss 결제 상태: " + status);
        };
    }

    // 누적 취소액
    public long canceledAmount() {
        return totalAmount - balanceAmount;
    }

    public String receiptUrl() {
        return receipt == null ? null : receipt.url();
    }
}
