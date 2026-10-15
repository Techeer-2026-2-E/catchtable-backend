package com.catchtable.waiting.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.service.NotificationService;
import com.catchtable.waiting.dto.WaitingPositionPayload;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingQueueNotifier {

    private final WaitingRepository waitingRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void handle(WaitingQueueChangedEvent event)
    {
        List<Waiting> queue= waitingRepository.findAllByStoreIdAndWaitingDateAndStatusInOrderByWaitNumberAsc(
                event.storeId(), event.waitingDate(), WaitingStatus.ACTIVE);

        for(int i=0; i< queue.size(); i++){
            Waiting waiting=queue.get(i);
            try{
               notificationService.send(new CustomerNotificationEvent(
                       waiting.getMember().getId(),
                       NotificationType.WAITING_POSITION_CHANGED,
                       waiting.getId(),
                       null,   // 화면 숫자만 바꾸므로 문구 없음
                       new WaitingPositionPayload(waiting.getId(), i + 1, i, waiting.getStatus())));

            }
            catch(Exception e)
            {
                log.warn("대기 순번 전송 실패 waitingId={}", waiting.getId(), e);
            }
        }

    }
}
