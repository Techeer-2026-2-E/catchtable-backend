package com.catchtable.waiting.dto;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.waiting.entity.WaitingStatus;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class WaitingCallNotification {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private WaitingCallNotification()
    {

    }
    public static CustomerNotificationEvent of(Long memberId, Long waitingId, int waitNumber,
                                               String storeName, OffsetDateTime calledAt, OffsetDateTime expiresAt)
    {
        String until=expiresAt.atZoneSameInstant(STORE_ZONE).format(HH_MM);
        String message="%d번 고객님, %s까지 %s에 입장해 주세요.".formatted(waitNumber, until, storeName);

        return new CustomerNotificationEvent(
                memberId,
                NotificationType.WAITING_CALLED,
                waitingId,
                message,
                new WaitingCallPayload(WaitingStatus.CALLED, waitNumber, storeName, calledAt, expiresAt)
        );
    }

}
