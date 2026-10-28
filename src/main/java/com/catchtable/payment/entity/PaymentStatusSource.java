package com.catchtable.payment.entity;

// 상태 변경 출처
public enum PaymentStatusSource {
    USER,          // 고객 요청
    TOSS_CONFIRM,  // Toss 승인 응답
    TOSS_QUERY,    // Toss 조회 결과
    TOSS_CANCEL,   // Toss 취소 응답
    SYSTEM         // 서버 판단 (시간초과 등)
}
