package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.ArrivalIntent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record WaitingPositionResponse(
        Long waitingId,
        Long storeId,
        String storeName,
        LocalDate waitingDate,
        int waitNumber,           // 신청 때 받은 번호표 (고정)
        Integer currentPosition,  // 현재 순번 = 앞 팀 수 + 1
        Long teamsAhead,          // 내 앞에 남은 진행 중 팀 수
        int partyCount,
        WaitingStatus status,
        OffsetDateTime calledAt,  // 호출 시각 (호출 전·종료 시 null)
        OffsetDateTime expiresAt, // 호출 만료 시각 (호출 전·종료 시 null)
        OffsetDateTime respondedAt,
        ArrivalIntent arrivalIntent
) {
    public static WaitingPositionResponse of(Waiting waiting, Long teamsAhead)
    {
        // 종료된 웨이팅에는 지난 호출 정보를 내려주지 않는다
        boolean active = WaitingStatus.ACTIVE.contains(waiting.getStatus());
        return new WaitingPositionResponse(
                waiting.getId(),
                waiting.getStore().getId(),
                waiting.getStore().getName(),
                waiting.getWaitingDate(),
                waiting.getWaitNumber(),
                teamsAhead == null ? null : (int) (teamsAhead + 1),
                teamsAhead,
                waiting.getPartyCount(),
                waiting.getStatus(),
                active ? waiting.getCalledAt() : null,
                active ? waiting.getCallExpiresAt() : null,
                active ? waiting.getRespondedAt() : null,
                active ? waiting.getArrivalIntent() : null
        );
    }
}
