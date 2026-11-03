package com.catchtable.waiting.dto;

import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingDeferHistory;

// 순번 연기 결과
public record WaitingDeferResponse(
        Long waitingId,
        int waitNumber,
        int deferCount,
        int beforePosition,
        int afterPosition
) {
    public static WaitingDeferResponse of(Waiting waiting, WaitingDeferHistory history) {
        return new WaitingDeferResponse(waiting.getId(), waiting.getWaitNumber(), waiting.getDeferCount(),
                history.getBeforePosition(), history.getAfterPosition());
    }
}
