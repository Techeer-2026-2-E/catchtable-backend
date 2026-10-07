package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.LocalDate;

public record WaitingPositionResponse(
        Long waitingId,
        Long storeId,
        String storeName,
        LocalDate waitingDate,
        int waitNumber,           // 신청 때 받은 번호표 (고정)
        Integer currentPosition,  // 현재 순번 = 앞 팀 수 + 1
        Long teamsAhead,          // 내 앞에 남은 진행 중 팀 수
        int partyCount,
        WaitingStatus status
) {
    public static WaitingPositionResponse of(Waiting waiting, Long teamsAhead)
    {
        return new WaitingPositionResponse(
                waiting.getId(),
                waiting.getStore().getId(),
                waiting.getStore().getName(),
                waiting.getWaitingDate(),
                waiting.getWaitNumber(),
                teamsAhead == null ? null : (int) (teamsAhead + 1),
                teamsAhead,
                waiting.getPartyCount(),
                waiting.getStatus()
        );
    }
}
