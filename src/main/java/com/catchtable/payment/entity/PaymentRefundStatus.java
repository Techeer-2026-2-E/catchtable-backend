package com.catchtable.payment.entity;

// 환불 요청 상태
public enum PaymentRefundStatus {
    REQUESTED,     // Toss 취소 요청 전·중
    COMPLETED,     // 환불 확정
    NOT_REQUIRED,  // 정책상 0원 (Toss 미호출)
    FAILED,        // Toss 거절
    UNKNOWN;       // 결과 불명 (같은 키 재시도·재확인 대상)

    // 처리 중·결과 불명
    public boolean isInFlight() {
        return this == REQUESTED || this == UNKNOWN;
    }
}
