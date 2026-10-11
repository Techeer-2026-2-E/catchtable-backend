package com.catchtable.waiting.dto;

import com.catchtable.notification.dto.NotificationPayload;
import com.catchtable.waiting.entity.WaitingStatus;

public record WaitingPositionPayload(
        Long waitingId,
        int currentPosition, //앞 팀 수 +1
        long teamsAhead,      // 내 앞에 남은 진행 중 팀 수
        WaitingStatus status

) implements NotificationPayload {
}
