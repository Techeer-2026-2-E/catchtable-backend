package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

// 착석 결과
public record WaitingSeatResponse(
        Long waitingId,
        WaitingStatus status,
        OffsetDateTime seatedAt
) {
    public static WaitingSeatResponse from(Waiting waiting) {
        return new WaitingSeatResponse(waiting.getId(), waiting.getStatus(), waiting.getSeatedAt());
    }
}
