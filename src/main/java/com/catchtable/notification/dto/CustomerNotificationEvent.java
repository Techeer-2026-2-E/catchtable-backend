package com.catchtable.notification.dto;

public record CustomerNotificationEvent(
         Long memberId,
        NotificationType type,
        Long targetId,      // 예약 id 또는 웨이팅 id
        String message,
        NotificationPayload payload   // 부가 데이터가 없는 알림은 null
) {

    public CustomerNotificationEvent(Long memberId, NotificationType type, Long targetId,
                                     String message)
    {
        this(memberId,type,targetId,message,null);
    }
}
