package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.ArrivalIntent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingEndReason;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;

public record WaitingEventSnapshot(
        WaitingStatus status, int callCount, int recallCount, OffsetDateTime calledAt,
        OffsetDateTime callExpiresAt, OffsetDateTime lastRecalledAt, OffsetDateTime respondedAt,
        ArrivalIntent arrivalIntent, OffsetDateTime enteredAt, Long tableId, OffsetDateTime endedAt,
        WaitingEndReason endReason, int queueOrder, int deferCount) {
    public static WaitingEventSnapshot of(Waiting w) {
        return new WaitingEventSnapshot(w.getStatus(), w.getCallCount(), w.getRecallCount(), w.getCalledAt(),
                w.getCallExpiresAt(), w.getLastRecalledAt(), w.getRespondedAt(), w.getArrivalIntent(),
                w.getEnteredAt(), w.getTableId(), w.getEndedAt(), w.getEndReason(), w.getQueueOrder(), w.getDeferCount());
    }
}
