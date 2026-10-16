package com.catchtable.waiting.dto;

import java.time.LocalDate;

public record WaitingQueueChangedEvent(Long storeId, LocalDate waitingDate)
{
}
