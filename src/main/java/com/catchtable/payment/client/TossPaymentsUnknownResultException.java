package com.catchtable.payment.client;

// 처리 여부를 모르는 요청 (응답 시간초과·연결 끊김·Toss 5xx·처리 중) → 조회로 재확인
public class TossPaymentsUnknownResultException extends RuntimeException {

    public TossPaymentsUnknownResultException(String message, Throwable cause) {
        super(message, cause);
    }
}
