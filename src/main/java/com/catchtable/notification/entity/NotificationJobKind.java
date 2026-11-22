package com.catchtable.notification.entity;

// 전송 작업 종류 (전송 스레드: NOTIFY 전용 / REFRESH·QUEUE 전용)
public enum NotificationJobKind {
    NOTIFY,   // 고객 알림 6종, 수신자 1명당 1행
    REFRESH,  // 점주 대기 목록 갱신, 매장당 PENDING 1개
    QUEUE     // 순번 변경, 매장·날짜당 PENDING 1개
}
