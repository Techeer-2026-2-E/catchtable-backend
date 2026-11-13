package com.catchtable.payment.client;

/** Admission was refused before sending this call to PG; never a definite payment failure. */
public class PaymentCapacityExceededException extends RuntimeException {
    public PaymentCapacityExceededException() {
        super("결제 처리 용량을 초과했습니다. 같은 요청으로 잠시 후 다시 시도해 주세요.");
    }
}
