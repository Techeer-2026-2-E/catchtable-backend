package com.catchtable.waiting.dto;

import java.time.LocalDate;
import java.util.UUID;

public record WaitingQueueChangedEvent(Long storeId, LocalDate waitingDate,
                                       WaitingEventReference waitingEvent, String deliveryKey) {
    public WaitingQueueChangedEvent(Long storeId, LocalDate waitingDate) {
        this(storeId, waitingDate, null, UUID.randomUUID().toString());
    }
    public WaitingQueueChangedEvent withWaitingEvent(WaitingEventReference reference) {
        return new WaitingQueueChangedEvent(storeId, waitingDate, reference, deliveryKey);
    }
}
