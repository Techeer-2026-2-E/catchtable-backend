package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

// 호출 재전송 결과
public record WaitingRecallResponse(
        Long waitingId,
        WaitingStatus status,
        int waitNumber,
        int callCount,
        OffsetDateTime calledAt,
        OffsetDateTime expiresAt,
        int recallCount,
        OffsetDateTime lastRecalledAt
) {
    public static WaitingRecallResponse from(Waiting waiting) {
        return new WaitingRecallResponse(waiting.getId(), waiting.getStatus(), waiting.getWaitNumber(),
                waiting.getCallCount(), waiting.getCalledAt(), waiting.getCallExpiresAt(),
                waiting.getRecallCount(), waiting.getLastRecalledAt());
    }
}
