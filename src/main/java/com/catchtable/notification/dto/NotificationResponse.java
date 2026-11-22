package com.catchtable.notification.dto;

import com.catchtable.notification.entity.Notification;

import java.time.OffsetDateTime;

public record NotificationResponse(
        Long id,
        NotificationType type,
        Long targetId,
        String message,
        boolean read,
        OffsetDateTime createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTargetId(),
                n.getMessage(), n.getReadAt() != null, n.getCreatedAt());
    }
}
