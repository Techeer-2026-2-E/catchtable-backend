package com.catchtable.notification.dto;

import com.catchtable.waiting.dto.WaitingEventReference;

public record CustomerNotificationEvent(
         Long memberId,
        NotificationType type,
        Long targetId,      // 예약/웨이팅 id, WAITING_CHANGED는 매장 id
        String message,
        NotificationPayload payload,   // 부가 데이터가 없는 알림은 null
        WaitingEventReference waitingEvent // internal correlation; NotificationMessage does not expose this
) {

    public CustomerNotificationEvent withWaitingEvent(WaitingEventReference reference) {
        return new CustomerNotificationEvent(memberId, type, targetId, message, payload, reference);
    }

    public CustomerNotificationEvent(Long memberId, NotificationType type, Long targetId,
                                     String message, NotificationPayload payload) {
        this(memberId, type, targetId, message, payload, null);
    }

    public CustomerNotificationEvent(Long memberId, NotificationType type, Long targetId,
                                     String message)
    {
        this(memberId,type,targetId,message,null);
    }
}
