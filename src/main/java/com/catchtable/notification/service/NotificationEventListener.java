package com.catchtable.notification.service;


import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.durability.WaitingSseJournal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase= TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(CustomerNotificationEvent event)
    {
        if (WaitingSseJournal.isWaiting(event)) return;
        try{
            notificationService.send(event);
        }
        catch(Exception e)
        {
            log.warn("알림 전송 실패 memberId={}, type={}", event.memberId(), event.type(),e);
        }
    }
}
