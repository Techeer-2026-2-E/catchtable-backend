package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

public record WaitingTerminationResponse(
        Long waitingId,
        WaitingStatus status,
        WaitingEndReason reason,
        String detail,
        OffsetDateTime endedAt
) {
    public static WaitingTerminationResponse from(Waiting waiting) {
        return new WaitingTerminationResponse(waiting.getId(), waiting.getStatus(),
                waiting.getEndReason(), waiting.getEndDetail(), waiting.getEndedAt());
    }
}
