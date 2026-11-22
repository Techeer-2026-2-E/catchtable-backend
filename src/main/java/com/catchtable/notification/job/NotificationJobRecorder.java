package com.catchtable.notification.job;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationMessage;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.entity.Notification;
import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.repository.NotificationJobRepository;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

// 알림 전송 작업 저장 (아웃박스). 업무 TX 안에서 같이 저장하고, 커밋되면 전송 스레드를 깨운다.
@Component
@RequiredArgsConstructor
public class NotificationJobRecorder {

    private final NotificationJobRepository jobRepository;
    private final NotificationJobWorker worker;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 고객 알림 6종 → NOTIFY, 점주 목록 갱신 → REFRESH
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CustomerNotificationEvent event) {
        requireWritableTransaction();
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (Notification.isInboxType(event.type())) {
            int saved = jobRepository.insertNotifyIfAbsent(
                    jobKeyOf(event),
                    event.memberId(),
                    event.type().name(),
                    event.targetId(),
                    callCountOf(event),
                    payloadOf(event),
                    now);
            wakeAfterCommit(saved, NotificationJobKind.NOTIFY);
            return;
        }
        if (event.type() == NotificationType.WAITING_CHANGED) {
            // WAITING_CHANGED의 targetId는 매장 id
            int saved = jobRepository.insertRefreshIfAbsent(event.targetId(), now);
            wakeAfterCommit(saved, NotificationJobKind.REFRESH);
        }
        // WAITING_POSITION_CHANGED는 업무 코드가 직접 발행하지 않음 (QUEUE 작업이 보낼 때 만듦)
    }

    // 대기열 변경 → QUEUE
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(WaitingQueueChangedEvent event) {
        requireWritableTransaction();
        int saved = jobRepository.insertQueueIfAbsent(
                event.storeId(), event.waitingDate(), OffsetDateTime.now(clock));
        wakeAfterCommit(saved, NotificationJobKind.QUEUE);
    }

    // 업무 이벤트 키(없으면 발행 키) + 알림 종류 + 수신자. 알림함 event_key에 수신자를 붙인 것
    private String jobKeyOf(CustomerNotificationEvent event) {
        String base = (event.waitingEvent() != null)
                ? event.waitingEvent().eventKey()
                : event.deliveryKey();
        return base + ":" + event.type() + ":" + event.memberId();
    }

    // 호출 알림만 몇 번째 호출인지 남김 (보낼 때 지난 호출 거르기)
    private Integer callCountOf(CustomerNotificationEvent event) {
        if (event.type() != NotificationType.WAITING_CALLED || event.waitingEvent() == null) {
            return null;
        }
        return event.waitingEvent().callCount();
    }

    // SSE 본문을 지금 만들어 둠. eventId도 여기서 한 번 정해져서 다시 보내도 같음
    private String payloadOf(CustomerNotificationEvent event) {
        NotificationMessage message = NotificationMessage.from(event, LocalDateTime.now(clock));
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("알림 본문 변환 실패: type=" + event.type(), e);
        }
    }

    // 새로 저장했을 때만, 커밋된 뒤에 해당 전송 스레드를 깨움 (롤백되면 안 깨움)
    private void wakeAfterCommit(int saved, NotificationJobKind kind) {
        if (saved == 0) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.wake(kind);
            }
        });
    }

    private void requireWritableTransaction() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("알림 작업은 쓰기 가능한 업무 TX에서만 저장");
        }
    }
}
