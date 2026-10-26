package com.catchtable.waiting.dto;

import com.catchtable.store.dto.StoreTableResponse;
import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.entity.TableStatus;
import com.catchtable.waiting.entity.ArrivalIntent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record OwnerWaitingResponse(
        Long id,
        Long storeId,
        String storeName,
        int waitingNumber,
        int partySize,
        WaitingStatus status,
        String customerName,
        OffsetDateTime createdAt,
        List<StoreTableResponse> compatibleTables,
        // 호출·응답 정보
        OffsetDateTime calledAt,
        OffsetDateTime callExpiresAt,
        OffsetDateTime respondedAt,
        ArrivalIntent arrivalIntent
) {
    public static OwnerWaitingResponse from(Waiting waiting, List<StoreTable> tables) {
        return new OwnerWaitingResponse(
                waiting.getId(), waiting.getStore().getId(), waiting.getStore().getName(),
                waiting.getWaitNumber(), waiting.getPartyCount(), waiting.getStatus(),
                waiting.getMember().getName(), waiting.getCreatedAt(),
                tables.stream()
                        .filter(table -> table.getStatus() == TableStatus.ACTIVE
                                && table.canSeat(waiting.getPartyCount()))
                        .map(StoreTableResponse::from)
                        .toList(),
                waiting.getCalledAt(), waiting.getCallExpiresAt(),
                waiting.getRespondedAt(), waiting.getArrivalIntent());
    }
}
