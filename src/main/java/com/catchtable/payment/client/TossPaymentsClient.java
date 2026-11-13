package com.catchtable.payment.client;

// Toss Payments 결제 API (v1)
// 전송 전 용량 거절: PaymentCapacityExceededException (재시도 가능)
// PG 확정 실패: TossPaymentsException, 전송 후/전체 deadline 결과 불명: TossPaymentsUnknownResultException
public interface TossPaymentsClient {

    // 승인 (멱등키 null이면 헤더 생략)
    TossPaymentResponse confirm(String paymentKey, String orderId, long amount, String idempotencyKey);

    TossPaymentResponse getByPaymentKey(String paymentKey);

    TossPaymentResponse getByOrderId(String orderId);

    // 취소 (cancelAmount null이면 전액)
    TossPaymentResponse cancel(String paymentKey, Long cancelAmount, String cancelReason, String idempotencyKey);
}
