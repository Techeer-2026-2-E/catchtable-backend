package com.catchtable.notification.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.entity.Notification;
import com.catchtable.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

// 알림함 저장 (업무 TX가 있으면 같이 커밋, 설정과 무관하게 저장)
@Component
@RequiredArgsConstructor
public class NotificationInboxRecorder {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    @EventListener
    public void record(CustomerNotificationEvent event) {
        // 점주 화면 갱신·순번 변경은 저장 안 함
        if (!Notification.isInboxType(event.type())) {
            return;
        }
        notificationRepository.insertIfAbsent(
                event.memberId(),
                event.targetId(),
                event.type().name(),
                event.message(),
                eventKeyOf(event),
                OffsetDateTime.now(clock));
    }

    // 웨이팅은 업무 이벤트 키, 예약은 발행 키 (WaitingSseJournal과 같은 규칙)
    private String eventKeyOf(CustomerNotificationEvent event) {
        String base = (event.waitingEvent() != null)
                ? event.waitingEvent().eventKey()
                : event.deliveryKey();
        return base + ":" + event.type();
    }
}
