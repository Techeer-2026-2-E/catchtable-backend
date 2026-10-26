package com.catchtable.notification.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationMessage(
        String eventId,
        NotificationType type,
        Long targetId,
        String message,
        NotificationPayload payload,
        LocalDateTime occurredAt
) {
    public static NotificationMessage from(CustomerNotificationEvent event, LocalDateTime now) {

        return new NotificationMessage(
                UUID.randomUUID().toString(),
                event.type(),
                event.targetId(),
                event.message(),
                event.payload(),
                now
        );
    }
}

