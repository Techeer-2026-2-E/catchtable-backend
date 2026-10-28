package com.catchtable.payment.entity;

// 결제 상태
public enum PaymentStatus {
    READY,             // 결제 대기
    DONE,              // 승인 완료
    FAILED,            // 승인 실패
    CANCELED,          // 승인 전 취소·이탈
    TIMEOUT,           // 결과 불명 (재확인 대상)
    PARTIAL_REFUNDED,  // 부분 환불
    REFUNDED;          // 전액 환불

    // 허용 전이
    public boolean canChangeTo(PaymentStatus next) {
        return switch (this) {
            case READY -> next == DONE || next == FAILED || next == CANCELED || next == TIMEOUT;
            case TIMEOUT -> next == DONE || next == FAILED || next == CANCELED;
            case DONE -> next == PARTIAL_REFUNDED || next == REFUNDED;
            case PARTIAL_REFUNDED -> next == PARTIAL_REFUNDED || next == REFUNDED;
            case FAILED, CANCELED, REFUNDED -> false;
        };
    }

    // 승인 이후 상태
    public boolean isApproved() {
        return this == DONE || this == PARTIAL_REFUNDED || this == REFUNDED;
    }
}
