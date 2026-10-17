package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

// 입장 처리 결과
public record WaitingEnterResponse(
        Long waitingId,
        WaitingStatus status,
        OffsetDateTime enteredAt
) {
    public static WaitingEnterResponse from(Waiting waiting) {
        return new WaitingEnterResponse(waiting.getId(), waiting.getStatus(), waiting.getEnteredAt());
    }
}
