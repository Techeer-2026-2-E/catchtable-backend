package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

public record WaitingCallResponse(
        Long waitingId,
        WaitingStatus status,
        int waitNumber,
        int callCount,
        OffsetDateTime calledAt,
        OffsetDateTime expiresAt
) {
    public static WaitingCallResponse from(Waiting waiting) {
        return new WaitingCallResponse(waiting.getId(), waiting.getStatus(), waiting.getWaitNumber(),
                waiting.getCallCount(), waiting.getCalledAt(), waiting.getCallExpiresAt());
    }
}
