package com.catchtable.waiting.service;

import com.catchtable.notification.durability.WaitingSseJournal;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Synchronous capture in the business transaction; never sends from a callback. */
@Component
@RequiredArgsConstructor
public class WaitingQueueNotifier {
    private final WaitingSseJournal journal;
    @EventListener
    public void handle(WaitingQueueChangedEvent event) { journal.enqueueQueue(event); }
}
