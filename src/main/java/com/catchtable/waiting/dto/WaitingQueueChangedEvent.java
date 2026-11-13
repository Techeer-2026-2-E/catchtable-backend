package com.catchtable.waiting.dto;

import java.time.LocalDate;

public record WaitingQueueChangedEvent(Long storeId, LocalDate waitingDate,
                                       WaitingEventReference waitingEvent) {
    public WaitingQueueChangedEvent(Long storeId, LocalDate waitingDate) {
        this(storeId, waitingDate, null);
    }
    public WaitingQueueChangedEvent withWaitingEvent(WaitingEventReference reference) {
        return new WaitingQueueChangedEvent(storeId, waitingDate, reference);
    }
}
