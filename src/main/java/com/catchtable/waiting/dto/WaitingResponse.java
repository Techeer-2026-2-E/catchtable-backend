package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;

// 웨이팅 응답
public record WaitingResponse(
        Long waitingId,       // 웨이팅 ID
        Long storeId,         // 매장 ID
        int waitNumber,       // 대기번호
        int partyCount,       // 인원수
        WaitingStatus status, // 상태
        long teamsAhead       // 내 앞 대기 팀 수
) {
    // 엔티티 → 응답 변환
    public static WaitingResponse of(Waiting waiting, Long storeId, long teamsAhead) {
        return new WaitingResponse(
                waiting.getId(),
                storeId,
                waiting.getWaitNumber(),
                waiting.getPartyCount(),
                waiting.getStatus(),
                teamsAhead
        );
    }
}