package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.ArrivalIntent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

// 응답 결과
public record WaitingRespondResponse(
        Long waitingId,
        WaitingStatus status,
        ArrivalIntent arrivalIntent,
        OffsetDateTime respondedAt,
        OffsetDateTime expiresAt
) {
    public static WaitingRespondResponse from(Waiting waiting) {
        return new WaitingRespondResponse(waiting.getId(), waiting.getStatus(), waiting.getArrivalIntent(),
                waiting.getRespondedAt(), waiting.getCallExpiresAt());
    }
}
