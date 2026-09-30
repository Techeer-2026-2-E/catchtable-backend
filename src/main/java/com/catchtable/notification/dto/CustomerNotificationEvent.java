package com.catchtable.notification.dto;

public record CustomerNotificationEvent(
         Long memberId,
        NotificationType type,
        Long targetId,      // 예약 id 또는 웨이팅 id
        String message
) {
}
