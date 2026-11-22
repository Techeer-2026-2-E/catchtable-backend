package com.catchtable.notification.entity;

public enum NotificationJobStatus {
    PENDING,     // 보낼 작업
    PROCESSING,  // 전송 스레드가 가져감 (lease_until까지)
    DONE,        // 끝남 (보냈거나, 보낼 필요가 없었음)
    FAILED       // 가져간 횟수 상한 초과
}
