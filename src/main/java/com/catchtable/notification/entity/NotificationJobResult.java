package com.catchtable.notification.entity;

public enum NotificationJobResult {
    SENT,              // 연결에 send 정상 반환 (고객 수신 여부는 모름)
    NO_CONNECTION,     // 보낼 연결 없음 → 알림함에서 확인
    SETTING_DISABLED,  // 선택 알림을 꺼 둠
    STALE_CALL,        // 새 호출 차수·상태 변경·기한 지남으로 지난 호출 알림
    MERGED,            // 되돌리려 할 때 같은 매장 PENDING이 이미 있어 합쳐짐
    ATTEMPTS_EXCEEDED  // 가져간 횟수 상한 초과
}
