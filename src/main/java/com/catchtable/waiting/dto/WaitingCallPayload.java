package com.catchtable.waiting.dto;

import com.catchtable.notification.dto.NotificationPayload;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

public record WaitingCallPayload(
        WaitingStatus status,
        int waitNumber,
        String storeName,
        OffsetDateTime calledAt,
        OffsetDateTime expiresAt
) implements NotificationPayload {
}
